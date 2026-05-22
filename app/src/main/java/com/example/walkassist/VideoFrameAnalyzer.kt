package com.example.walkassist

import android.content.Context
import android.graphics.Bitmap
import com.example.walkassist.feedback.core.FeedbackInput
import com.example.walkassist.feedback.core.FeedbackObstacleSample
import com.example.walkassist.feedback.core.FeedbackSensorStatus
import com.example.walkassist.feedback.core.FeedbackSensorType
import kotlin.math.PI

data class VideoFrameAnalysisResult(
    val frameTimeMs: Long,
    val imageWidth: Int,
    val imageHeight: Int,
    val detections: List<ObjectOverlayDetection>,
    val floorSegmentation: FloorSegmentationResult?,
    val pathMetrics: PathMetrics?,
    val crosswalk: CrosswalkPatternResult,
    val debugInfo: AnalyzerDebugInfo,
    val vlmInterpretation: VlmSceneInterpretation?,
    val feedbackInput: FeedbackInput,
    val measurementState: ArMeasurementState,
) {
    val summary: String
        get() {
            val collision = pathMetrics?.collisionDistanceMeters ?: pathMetrics?.centerObstacleMeters
            val clear = pathMetrics?.pathClearMeters
            return buildString {
                append("객체=${detections.size}")
                append(" 바닥=${((floorSegmentation?.confidence ?: 0f) * 100f).toInt()}%")
                append(" 이동가능=${clear?.let { String.format("%.1fm", it) } ?: "-"}")
                append("장애물=${collision?.let { String.format("%.1fm", it) } ?: "-"}")
                if (crosswalk.detected) {
                    append(" 횡단보도=${(crosswalk.score * 100f).toInt()}%")
                }
            }
        }
}

class VideoFrameAnalyzer(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val floorSegmenter = ModelFloorSegmenter(appContext)
    private val objectAnalyzer = ObjectAnalyzer(appContext)
    private val distanceEstimator = DistanceEstimator()
    private val pathAnalyzer = PathAnalyzer()
    private val objectTracker = ObjectTracker()
    private val crosswalkPatternDetector = CrosswalkPatternDetector()

    /*
     * Video replay only exercises image-based recognition. A plain gallery video
     * does not contain ARCore tracking state, device pose, hit tests, planes, or
     * raw depth, so this class must not pretend to replace the live AR session.
     */
    fun analyze(
        bitmap: Bitmap,
        frameTimeMs: Long,
        pitchRadians: Float = DEFAULT_REPLAY_PITCH_RADIANS,
    ): VideoFrameAnalysisResult {
        val floorSegmentation = floorSegmenter.segment(bitmap)
        val rawDetections = if (objectAnalyzer.isReady()) {
            objectAnalyzer.detect(bitmap)
        } else {
            emptyList()
        }
        val detectedObjects = rawDetections.map { detection ->
            DetectedObjectResult(
                boundingBox = detection.boundingBox,
                confidence = detection.confidence,
                imageHeight = detection.imageHeight,
                imageWidth = detection.imageWidth,
                label = detection.label,
                distanceEstimate = distanceEstimator.estimate(
                    detection = detection,
                    pitchRadians = pitchRadians,
                    floorSegmentation = floorSegmentation,
                ),
                segmentCoverageRatio = detection.segmentCoverageRatio,
                segmentCenterXRatio = detection.segmentCenterXRatio,
                segmentCenterYRatio = detection.segmentCenterYRatio,
            )
        }
        val trackedObjects = objectTracker.update(
            detections = detectedObjects,
            timestampNanos = frameTimeMs * 1_000_000L,
        )
        val pathMetrics = pathAnalyzer.analyze(
            bitmap = bitmap,
            floorSegmentation = floorSegmentation,
            pitchRadians = pitchRadians,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
        )
        val yoloCrosswalkConfidence = rawDetections
            .filter { it.label.equals("crosswalk", ignoreCase = true) }
            .maxOfOrNull { it.confidence } ?: 0f
        val crosswalk = crosswalkPatternDetector.detect(
            bitmap = bitmap,
            floorSegmentation = floorSegmentation,
            yoloConfidence = yoloCrosswalkConfidence,
        )
        val primaryAnalysis = FrameAnalysis(
            detections = trackedObjects,
            nearestObstacle = trackedObjects
                .mapNotNull { detection ->
                    detection.distanceEstimate.distanceMeters?.let { distance -> detection to distance }
                }
                .minByOrNull { it.second }
                ?.first,
            floorSegmentation = floorSegmentation,
            pathMetrics = pathMetrics,
        )
        val spatialFrame = SpatialFrame(
            bitmap = bitmap,
            timestampMillis = frameTimeMs,
            source = SpatialFrameSource.VIDEO_REPLAY,
            pitchRadians = pitchRadians,
        )
        val vlmInterpretation: VlmSceneInterpretation? = null
        val overlayDetections = trackedObjects
            .sortedWith(
                compareByDescending<DetectedObjectResult> { it.trackingState?.isStable == true }
                    .thenBy { it.trackingState?.missedFrames ?: 0 }
                    .thenByDescending { it.confidence },
            )
            .take(8)
            .map { detection ->
                val centerXRatio = (
                    (detection.boundingBox.left + detection.boundingBox.right) * 0.5f /
                        bitmap.width.toFloat()
                    ).coerceIn(0f, 1f)
                ObjectOverlayDetection(
                    leftRatio = (detection.boundingBox.left / bitmap.width).coerceIn(0f, 1f),
                    topRatio = (detection.boundingBox.top / bitmap.height).coerceIn(0f, 1f),
                    widthRatio = (detection.boundingBox.width() / bitmap.width).coerceIn(0.02f, 1f),
                    heightRatio = (detection.boundingBox.height() / bitmap.height).coerceIn(0.02f, 1f),
                    label = detection.label,
                    confidence = detection.confidence,
                    distanceMeters = detection.distanceEstimate.distanceMeters,
                    lane = classifyScreenLane(centerXRatio),
                    isStable = detection.trackingState?.isStable == true,
                    trackId = detection.trackingState?.trackId,
                    segmentCoverageRatio = detection.segmentCoverageRatio,
                    segmentCenterXRatio = detection.segmentCenterXRatio,
                    segmentCenterYRatio = detection.segmentCenterYRatio,
                    objectTimeToCollisionSeconds = detection.trackingState?.timeToCollisionSeconds,
                    objectClosingSpeedMetersPerSecond = detection.trackingState?.closingSpeedMetersPerSecond,
                )
            }
        val leftDistance = overlayDetections
            .filter { it.lane == "left" }
            .mapNotNull { it.distanceMeters }
            .minOrNull()
        val centerDistance = listOfNotNull(
            pathMetrics.collisionDistanceMeters,
            pathMetrics.centerObstacleMeters,
            overlayDetections
                .filter { it.lane == "center" }
                .mapNotNull { it.distanceMeters }
                .minOrNull(),
        ).minOrNull()
        val rightDistance = overlayDetections
            .filter { it.lane == "right" }
            .mapNotNull { it.distanceMeters }
            .minOrNull()
        val collisionDistance = listOfNotNull(
            centerDistance,
            leftDistance,
            rightDistance,
            pathMetrics.pathClearMeters,
        ).minOrNull()
        val confidence = maxOf(
            floorSegmentation.confidence,
            overlayDetections.maxOfOrNull { it.confidence } ?: 0f,
        ).coerceIn(0f, 1f)
        val riskLabel = riskLabelFor(collisionDistance)
        val suggestedDirection = suggestedDirectionFor(
            leftDistance = leftDistance,
            centerDistance = centerDistance,
            rightDistance = rightDistance,
            pathClearMeters = pathMetrics.pathClearMeters,
        )
        val statusLevel = statusLevelFor(riskLabel)
        val primaryGuidanceLabel = guidanceLabelFor(
            collisionDistance = collisionDistance,
            riskLabel = riskLabel,
            suggestedDirection = suggestedDirection,
        )
        val guidanceLabel = guidanceLabelWithVlm(primaryGuidanceLabel, vlmInterpretation)
        val feedbackInput = if (collisionDistance != null) {
            FeedbackInput.Obstacle(
                FeedbackObstacleSample(
                    distanceMeters = collisionDistance,
                    confidence = confidence,
                    sensorType = FeedbackSensorType.VIDEO_REPLAY,
                ),
            )
        } else {
            FeedbackInput.SensorStatus(FeedbackSensorStatus.WAITING)
        }

        return VideoFrameAnalysisResult(
            frameTimeMs = frameTimeMs,
            imageWidth = bitmap.width,
            imageHeight = bitmap.height,
            detections = overlayDetections,
            floorSegmentation = floorSegmentation,
            pathMetrics = pathMetrics,
            crosswalk = crosswalk,
            debugInfo = AnalyzerDebugInfo(
                detectorReady = objectAnalyzer.isReady(),
                floorConfidence = floorSegmentation.confidence,
                floorMode = floorSegmenter.lastMode,
                modelInputSize = objectAnalyzer.modelInputSizeLabel(),
                modelOutputShape = objectAnalyzer.modelOutputShapeLabel(),
                processedFrames = (frameTimeMs / DEFAULT_FRAME_INTERVAL_MS).toInt() + 1,
                rawDetectionCount = objectAnalyzer.lastRawDetectionCount,
                trackedDetectionCount = overlayDetections.size,
                lastError = objectAnalyzer.lastErrorMessage,
            ),
            vlmInterpretation = vlmInterpretation,
            feedbackInput = feedbackInput,
            measurementState = ArMeasurementState(
                trackingLabel = "video_replay",
                sensingConfidenceScore = (confidence * 100f).toInt().coerceIn(0, 100),
                leftLaneWidthRatio = 0.33f,
                centerLaneWidthRatio = 0.34f,
                rightLaneWidthRatio = 0.33f,
                leftDistanceMeters = leftDistance,
                centerDistanceMeters = centerDistance,
                rightDistanceMeters = rightDistance,
                suggestedDirection = suggestedDirection,
                floorDistanceMeters = pathMetrics.pathClearMeters,
                collisionDistanceMeters = collisionDistance,
                timeToCollisionSeconds = overlayDetections
                    .mapNotNull { it.objectTimeToCollisionSeconds }
                    .minOrNull(),
                riskLabel = riskLabel,
                guidanceLabel = guidanceLabel,
                statusLabel = "영상 테스트 보행 안내",
                statusLevel = statusLevel,
                crosswalkDetected = crosswalk.detected,
                crosswalkScore = crosswalk.score,
                crosswalkStripeCount = crosswalk.stripeCount,
                crosswalkYoloConfidence = crosswalk.yoloConfidence,
                crosswalkModeLabel = crosswalk.modeLabel,
                objectDetections = overlayDetections,
                vlmModelName = vlmInterpretation?.modelName.orEmpty(),
                vlmRiskLabel = vlmInterpretation?.risk?.name?.lowercase().orEmpty(),
                vlmSuggestedAction = vlmInterpretation?.suggestedAction?.name?.lowercase().orEmpty(),
                vlmConfidenceScore = ((vlmInterpretation?.confidence ?: 0f) * 100f).toInt().coerceIn(0, 100),
                vlmSummary = vlmInterpretation?.pathSummary.orEmpty(),
                note = "영상 테스트는 라이브 카메라 대신 영상 프레임을 분석합니다. ARCore 자세, 깊이, 평면 정보는 사용할 수 없습니다.",
            ),
        )
    }

    fun close() {
        objectAnalyzer.close()
    }

    private fun classifyScreenLane(centerXRatio: Float): String {
        return when {
            centerXRatio < 0.34f -> "left"
            centerXRatio > 0.66f -> "right"
            else -> "center"
        }
    }

    private fun riskLabelFor(collisionDistance: Float?): String {
        return when {
            collisionDistance == null -> "searching"
            collisionDistance < 0.55f -> "critical"
            collisionDistance < 0.9f -> "high"
            collisionDistance < 1.6f -> "watch"
            else -> "stable"
        }
    }

    private fun statusLevelFor(riskLabel: String): ArStatusLevel {
        return when (riskLabel) {
            "critical" -> ArStatusLevel.DANGER
            "high", "watch" -> ArStatusLevel.WARNING
            "stable" -> ArStatusLevel.SAFE
            else -> ArStatusLevel.INFO
        }
    }

    private fun suggestedDirectionFor(
        leftDistance: Float?,
        centerDistance: Float?,
        rightDistance: Float?,
        pathClearMeters: Float?,
    ): String {
        if (centerDistance == null && pathClearMeters == null) return "unknown"
        val centerClear = minOf(centerDistance ?: Float.MAX_VALUE, pathClearMeters ?: Float.MAX_VALUE)
        if (centerClear >= 1.6f) return "center"
        val left = leftDistance ?: 0f
        val right = rightDistance ?: 0f
        return when {
            left >= right && left >= 1.2f -> "left"
            right > left && right >= 1.2f -> "right"
            else -> "blocked"
        }
    }

    private fun guidanceLabelFor(
        collisionDistance: Float?,
        riskLabel: String,
        suggestedDirection: String,
    ): String {
        if (collisionDistance == null) return "영상에서 보행 공간을 인식하는 중입니다."
        return when (riskLabel) {
            "critical" -> "영상 기준 전방 장애물이 매우 가깝습니다."
            "high" -> "영상 기준 전방 장애물을 주의하세요."
            "watch" -> when (suggestedDirection) {
                "left" -> "영상 기준 왼쪽 공간이 더 열려 있습니다."
                "right" -> "영상 기준 오른쪽 공간이 더 열려 있습니다."
                "blocked" -> "영상 기준 전방 공간이 좁아 보입니다."
                else -> "영상에서 전방 공간을 인식 중입니다."
            }
            else -> "영상 기준 전방 공간이 확보되어 있습니다."
        }
    }
    private fun guidanceLabelWithVlm(
        primaryGuidanceLabel: String,
        vlmInterpretation: VlmSceneInterpretation?,
    ): String {
        if (vlmInterpretation == null) return primaryGuidanceLabel
        return when (vlmInterpretation.risk) {
            VlmWalkingRisk.BLOCKED -> "영상 장면 분석상 이동이 어려워 보입니다. $primaryGuidanceLabel"
            VlmWalkingRisk.CAUTION -> "영상 장면 분석상 주의가 필요합니다. $primaryGuidanceLabel"
            else -> primaryGuidanceLabel
        }
    }

    companion object {
        const val DEFAULT_FRAME_INTERVAL_MS = 300L
        val DEFAULT_REPLAY_PITCH_RADIANS: Float = (25.0 * PI / 180.0).toFloat()
    }
}
