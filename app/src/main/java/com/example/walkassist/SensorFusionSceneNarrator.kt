package com.example.walkassist

import android.graphics.Bitmap
import kotlin.math.roundToInt

/**
 * 카메라 이미지에서 얻은 VLM/YOLO/floor mask 결과와 IMU, ARCore 거리 정보를 합쳐
 * 시각장애인 보행 안내에 바로 쓸 수 있는 짧은 한국어 장면 설명을 만듭니다.
 *
 * 이 클래스는 "모델이 말한 문장"을 그대로 믿기보다, 거리/방향/움직임 같은 보행 안전 정보는
 * ARCore와 기존 CV 결과를 우선 사용합니다. VLM caption은 시각적 배경 단서로만 보조 반영합니다.
 */
object SensorFusionSceneNarrator {
    fun describe(
        modelName: String,
        frame: SpatialFrame,
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
        modelCaption: String?,
        modelWasUsed: Boolean,
    ): VlmSceneInterpretation {
        val imageQuality = estimateImageQuality(frame.bitmap)
        val arState = frame.arState
        val objects = collectObjectCues(primaryAnalysis, arState)
        val laneSnapshot = laneSnapshot(primaryAnalysis, arState)
        val risk = inferRisk(
            laneSnapshot = laneSnapshot,
            objects = objects,
            crosswalk = crosswalk,
            imageQuality = imageQuality,
            arState = arState,
        )
        val action = inferAction(risk, laneSnapshot, arState)
        val summary = buildSummary(
            objects = objects,
            crosswalk = crosswalk,
            laneSnapshot = laneSnapshot,
            risk = risk,
            action = action,
            imageQuality = imageQuality,
            arState = arState,
            modelCaption = modelCaption,
        )
        val evidence = buildEvidence(
            frame = frame,
            primaryAnalysis = primaryAnalysis,
            crosswalk = crosswalk,
            objects = objects,
            laneSnapshot = laneSnapshot,
            imageQuality = imageQuality,
            arState = arState,
            modelCaption = modelCaption,
            modelWasUsed = modelWasUsed,
        )

        return VlmSceneInterpretation(
            modelName = if (modelWasUsed) modelName else "$modelName-sensor-fusion",
            schemaVersion = 1,
            risk = risk,
            suggestedAction = action,
            confidence = confidenceScore(
                primaryAnalysis = primaryAnalysis,
                arState = arState,
                imageQuality = imageQuality,
                objectCues = objects,
                modelWasUsed = modelWasUsed,
            ),
            pathSummary = summary,
            evidence = evidence,
            shouldOverridePrimary = risk == VlmWalkingRisk.BLOCKED || risk == VlmWalkingRisk.CAUTION,
        )
    }

    private fun buildSummary(
        objects: List<ObjectCue>,
        crosswalk: CrosswalkPatternResult,
        laneSnapshot: LaneSnapshot,
        risk: VlmWalkingRisk,
        action: VlmSuggestedAction,
        imageQuality: ImageQuality,
        arState: ArMeasurementState?,
        modelCaption: String?,
    ): String {
        val sceneSentence = when {
            objects.isNotEmpty() -> {
                val objectPhrase = objects.take(3).joinToString(", ") { it.toKoreanPhrase() }
                "카메라 전방에 $objectPhrase."
            }
            crosswalk.detected -> "바닥 쪽에 횡단보도 무늬가 감지됩니다."
            modelCaption?.containsKorean() == true -> modelCaption.trim().trimEnd('.', '!', '?') + "."
            imageQuality.tooDark -> "카메라 화면이 어두워 큰 물체만 제한적으로 보입니다."
            else -> "카메라 전방에는 큰 물체가 뚜렷하게 잡히지 않습니다."
        }

        val pathSentence = when {
            laneSnapshot.collisionDistance != null && laneSnapshot.collisionDistance < 0.75f ->
                "전방 ${formatMeters(laneSnapshot.collisionDistance)} 안쪽에 가까운 장애물이 있습니다."
            laneSnapshot.centerDistance != null && laneSnapshot.centerDistance < 1.2f -> {
                val side = clearerSide(laneSnapshot)
                if (side == null) {
                    "중앙 보행 공간이 좁게 감지됩니다."
                } else {
                    "중앙은 좁고 ${laneNameKo(side)} 쪽 공간이 더 여유 있습니다."
                }
            }
            laneSnapshot.centerFreeSpace != null && laneSnapshot.centerFreeSpace >= 1.6f ->
                "중앙 보행 공간은 약 ${formatMeters(laneSnapshot.centerFreeSpace)}까지 열려 있습니다."
            laneSnapshot.floorConfidence != null && laneSnapshot.floorConfidence >= 0.55f ->
                "바닥 영역은 비교적 안정적으로 분리됩니다."
            arState?.trackingLabel == "tracking" ->
                "ARCore가 전방 공간을 추적하고 있지만 통로 거리는 아직 제한적입니다."
            else -> "전방 통로 판단에 필요한 거리 정보가 아직 부족합니다."
        }

        val actionSentence = when (action) {
            VlmSuggestedAction.STOP -> "잠시 멈추고 속도를 낮추세요."
            VlmSuggestedAction.SLOW_DOWN -> "속도를 줄이고 조심해서 이동하세요."
            VlmSuggestedAction.VEER_LEFT -> "왼쪽으로 살짝 피해서 이동하세요."
            VlmSuggestedAction.VEER_RIGHT -> "오른쪽으로 살짝 피해서 이동하세요."
            VlmSuggestedAction.PROCEED -> "천천히 직진해도 됩니다."
            VlmSuggestedAction.UNKNOWN -> when (risk) {
                VlmWalkingRisk.BLOCKED -> "잠시 멈추는 편이 안전합니다."
                VlmWalkingRisk.CAUTION -> "속도를 줄이고 이동하세요."
                else -> "천천히 이동하세요."
            }
        }

        return listOf(sceneSentence, pathSentence, actionSentence)
            .joinToString(separator = " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun buildEvidence(
        frame: SpatialFrame,
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
        objects: List<ObjectCue>,
        laneSnapshot: LaneSnapshot,
        imageQuality: ImageQuality,
        arState: ArMeasurementState?,
        modelCaption: String?,
        modelWasUsed: Boolean,
    ): List<String> {
        return buildList {
            objects.take(3).forEach { add(it.toEvidence()) }
            if (crosswalk.detected) {
                add("횡단보도 패턴 ${scorePercent(crosswalk.score)}, 줄무늬 ${crosswalk.stripeCount}개")
            }
            laneSnapshot.collisionDistance?.let {
                add("전방 충돌 거리 ${formatMeters(it)}")
            }
            laneSnapshot.centerFreeSpace?.let {
                add("중앙 여유 공간 ${formatMeters(it)}")
            }
            val pitch = arState?.pitchDownDegrees ?: Math.toDegrees(frame.pitchRadians.toDouble()).toFloat()
            add("IMU: 휴대폰 아래 방향 ${pitch.roundToInt()}도")
            if (arState != null) {
                add("ARCore: ${arState.trackingLabel}, 평면 ${arState.horizontalPlaneCount + arState.verticalPlaneCount}개")
            }
            primaryAnalysis.floorSegmentation?.confidence?.let {
                add("바닥 분할 신뢰도 ${scorePercent(it)}")
            }
            if (imageQuality.tooDark || imageQuality.lowContrast) {
                add("이미지 품질: ${imageQuality.label}")
            }
            if (modelWasUsed && !modelCaption.isNullOrBlank()) {
                add("VLM: ${modelCaption.compactForEvidence()}")
            } else if (modelWasUsed) {
                add("VLM 이미지 입력 실행됨")
            }
        }.distinct().take(6)
    }

    private fun collectObjectCues(
        primaryAnalysis: FrameAnalysis,
        arState: ArMeasurementState?,
    ): List<ObjectCue> {
        val fromOverlay = arState?.objectDetections.orEmpty().map {
            ObjectCue(
                label = it.label,
                lane = it.lane ?: laneFromCenter(it.leftRatio + (it.widthRatio * 0.5f)),
                confidence = it.confidence,
                distanceMeters = it.distanceMeters?.takeUnless { _ -> it.distanceIsReference },
                segmentCoverageRatio = it.segmentCoverageRatio,
                closingSpeedMetersPerSecond = it.objectClosingSpeedMetersPerSecond,
                timeToCollisionSeconds = it.objectTimeToCollisionSeconds,
            )
        }
        val fromPrimary = primaryAnalysis.detections.map {
            val centerXRatio = ((it.boundingBox.left + it.boundingBox.right) * 0.5f) /
                it.imageWidth.coerceAtLeast(1).toFloat()
            ObjectCue(
                label = it.label,
                lane = laneFromCenter(centerXRatio),
                confidence = it.confidence,
                distanceMeters = it.distanceEstimate.distanceMeters,
                segmentCoverageRatio = it.segmentCoverageRatio,
                closingSpeedMetersPerSecond = it.trackingState?.closingSpeedMetersPerSecond,
                timeToCollisionSeconds = it.trackingState?.timeToCollisionSeconds,
            )
        }

        return (fromOverlay + fromPrimary)
            .filter { it.confidence >= 0.25f }
            .sortedWith(
                compareBy<ObjectCue> { it.distanceMeters ?: Float.MAX_VALUE }
                    .thenByDescending { it.confidence },
            )
            .distinctBy { "${it.label.lowercase()}-${it.lane}" }
            .take(6)
    }

    private fun laneSnapshot(
        primaryAnalysis: FrameAnalysis,
        arState: ArMeasurementState?,
    ): LaneSnapshot {
        val metrics = primaryAnalysis.pathMetrics
        return LaneSnapshot(
            leftDistance = arState?.leftDistanceMeters,
            centerDistance = arState?.centerDistanceMeters ?: metrics?.centerObstacleMeters,
            rightDistance = arState?.rightDistanceMeters,
            collisionDistance = arState?.collisionDistanceMeters ?: metrics?.collisionDistanceMeters,
            centerFreeSpace = arState?.worldMapCenterFreeSpaceMeters ?: metrics?.pathClearMeters,
            suggestedDirection = arState?.suggestedDirection,
            floorConfidence = primaryAnalysis.floorSegmentation?.confidence,
            timeToCollisionSeconds = arState?.timeToCollisionSeconds ?: metrics?.timeToCollisionSeconds,
        )
    }

    private fun inferRisk(
        laneSnapshot: LaneSnapshot,
        objects: List<ObjectCue>,
        crosswalk: CrosswalkPatternResult,
        imageQuality: ImageQuality,
        arState: ArMeasurementState?,
    ): VlmWalkingRisk {
        val nearObject = objects.any { (it.distanceMeters ?: Float.MAX_VALUE) < 1.1f }
        val approachingObject = objects.any { (it.timeToCollisionSeconds ?: Float.MAX_VALUE) <= 2.2f }
        return when {
            laneSnapshot.collisionDistance != null && laneSnapshot.collisionDistance < 0.75f -> VlmWalkingRisk.BLOCKED
            laneSnapshot.timeToCollisionSeconds != null && laneSnapshot.timeToCollisionSeconds <= 1.6f ->
                VlmWalkingRisk.BLOCKED
            nearObject && laneSnapshot.centerDistance != null && laneSnapshot.centerDistance < 1.2f ->
                VlmWalkingRisk.BLOCKED
            laneSnapshot.collisionDistance != null && laneSnapshot.collisionDistance < 1.6f -> VlmWalkingRisk.CAUTION
            laneSnapshot.timeToCollisionSeconds != null && laneSnapshot.timeToCollisionSeconds <= 3.5f ->
                VlmWalkingRisk.CAUTION
            approachingObject || nearObject || crosswalk.detected -> VlmWalkingRisk.CAUTION
            imageQuality.tooDark || arState?.trackingLabel != "tracking" -> VlmWalkingRisk.UNKNOWN
            laneSnapshot.centerFreeSpace != null && laneSnapshot.centerFreeSpace >= 1.6f -> VlmWalkingRisk.CLEAR
            (laneSnapshot.floorConfidence ?: 0f) >= 0.55f -> VlmWalkingRisk.CLEAR
            else -> VlmWalkingRisk.UNKNOWN
        }
    }

    private fun inferAction(
        risk: VlmWalkingRisk,
        laneSnapshot: LaneSnapshot,
        arState: ArMeasurementState?,
    ): VlmSuggestedAction {
        if (risk == VlmWalkingRisk.BLOCKED && (laneSnapshot.collisionDistance ?: 9f) < 0.65f) {
            return VlmSuggestedAction.STOP
        }
        val direction = laneSnapshot.suggestedDirection ?: arState?.suggestedDirection
        return when (direction) {
            "left" -> VlmSuggestedAction.VEER_LEFT
            "right" -> VlmSuggestedAction.VEER_RIGHT
            "blocked" -> if (risk == VlmWalkingRisk.BLOCKED) VlmSuggestedAction.STOP else VlmSuggestedAction.SLOW_DOWN
            "center" -> if (risk == VlmWalkingRisk.CLEAR) VlmSuggestedAction.PROCEED else VlmSuggestedAction.SLOW_DOWN
            else -> when (risk) {
                VlmWalkingRisk.CLEAR -> VlmSuggestedAction.PROCEED
                VlmWalkingRisk.CAUTION -> VlmSuggestedAction.SLOW_DOWN
                VlmWalkingRisk.BLOCKED -> VlmSuggestedAction.STOP
                VlmWalkingRisk.UNKNOWN -> VlmSuggestedAction.UNKNOWN
            }
        }
    }

    private fun confidenceScore(
        primaryAnalysis: FrameAnalysis,
        arState: ArMeasurementState?,
        imageQuality: ImageQuality,
        objectCues: List<ObjectCue>,
        modelWasUsed: Boolean,
    ): Float {
        var score = 0.35f
        score += ((primaryAnalysis.floorSegmentation?.confidence ?: 0f) * 0.18f)
        score += ((arState?.sensingConfidenceScore ?: 0) / 100f) * 0.26f
        score += ((objectCues.maxOfOrNull { it.confidence } ?: 0f) * 0.16f)
        if (modelWasUsed) score += 0.1f
        if (imageQuality.tooDark) score -= 0.12f
        if (imageQuality.lowContrast) score -= 0.06f
        return score.coerceIn(0.2f, 0.92f)
    }

    private fun estimateImageQuality(bitmap: Bitmap): ImageQuality {
        val stepX = (bitmap.width / 18).coerceAtLeast(1)
        val stepY = (bitmap.height / 18).coerceAtLeast(1)
        var count = 0
        var sum = 0f
        var min = 255
        var max = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                val luminance = (
                    (((pixel shr 16) and 0xFF) * 0.299f) +
                        (((pixel shr 8) and 0xFF) * 0.587f) +
                        ((pixel and 0xFF) * 0.114f)
                    ).roundToInt()
                sum += luminance
                min = minOf(min, luminance)
                max = maxOf(max, luminance)
                count += 1
                x += stepX
            }
            y += stepY
        }
        val average = if (count == 0) 128f else sum / count
        val contrast = max - min
        val tooDark = average < 38f
        val lowContrast = contrast < 34
        val label = when {
            tooDark -> "어두움"
            lowContrast -> "대비 낮음"
            average > 225f -> "밝음"
            else -> "정상"
        }
        return ImageQuality(label = label, tooDark = tooDark, lowContrast = lowContrast)
    }

    private fun clearerSide(laneSnapshot: LaneSnapshot): String? {
        val left = laneSnapshot.leftDistance ?: 0f
        val right = laneSnapshot.rightDistance ?: 0f
        return when {
            left >= 1.2f && left >= right -> "left"
            right >= 1.2f && right > left -> "right"
            else -> null
        }
    }

    private fun laneFromCenter(centerXRatio: Float): String {
        return when {
            centerXRatio < 0.38f -> "left"
            centerXRatio > 0.62f -> "right"
            else -> "center"
        }
    }

    private fun laneNameKo(lane: String): String {
        return when (lane) {
            "left" -> "왼쪽"
            "right" -> "오른쪽"
            "center" -> "중앙"
            else -> "전방"
        }
    }

    private fun labelKo(label: String): String {
        return when (label.lowercase()) {
            "person" -> "사람"
            "bicycle" -> "자전거"
            "car" -> "자동차"
            "motorcycle" -> "오토바이"
            "bus" -> "버스"
            "truck" -> "트럭"
            "chair" -> "의자"
            "bench" -> "벤치"
            "traffic light" -> "신호등"
            "stop sign" -> "표지판"
            "crosswalk" -> "횡단보도"
            "door" -> "문"
            "stairs", "stair" -> "계단"
            "curb" -> "턱"
            "traffic cone", "cone" -> "안전 콘"
            else -> label
        }
    }

    private fun formatMeters(distanceMeters: Float): String {
        return if (distanceMeters < 1f) {
            "${(distanceMeters * 100f).roundToInt()}cm"
        } else {
            String.format("%.1fm", distanceMeters)
        }
    }

    private fun scorePercent(score: Float): String {
        return "${(score.coerceIn(0f, 1f) * 100f).roundToInt()}%"
    }

    private fun String.containsKorean(): Boolean {
        return any { it in '\uAC00'..'\uD7A3' }
    }

    private fun String.compactForEvidence(): String {
        return replace(Regex("\\s+"), " ")
            .trim()
            .take(90)
    }

    private data class ObjectCue(
        val label: String,
        val lane: String,
        val confidence: Float,
        val distanceMeters: Float?,
        val segmentCoverageRatio: Float?,
        val closingSpeedMetersPerSecond: Float?,
        val timeToCollisionSeconds: Float?,
    ) {
        fun toKoreanPhrase(): String {
            val distance = distanceMeters?.let { " ${formatMeters(it)} 앞" }.orEmpty()
            val motion = when {
                timeToCollisionSeconds != null && timeToCollisionSeconds <= 2.5f -> " 접근 중인"
                (closingSpeedMetersPerSecond ?: 0f) > 0.25f -> " 가까워지는"
                else -> ""
            }
            return "${laneNameKo(lane)}$distance$motion ${labelKo(label)}"
        }

        fun toEvidence(): String {
            val distance = distanceMeters?.let { " ${formatMeters(it)}" }.orEmpty()
            val segment = segmentCoverageRatio?.let { " seg ${scorePercent(it)}" }.orEmpty()
            return "YOLO-seg: ${laneNameKo(lane)} ${labelKo(label)}$distance $segment".trim()
        }
    }

    private data class LaneSnapshot(
        val leftDistance: Float?,
        val centerDistance: Float?,
        val rightDistance: Float?,
        val collisionDistance: Float?,
        val centerFreeSpace: Float?,
        val suggestedDirection: String?,
        val floorConfidence: Float?,
        val timeToCollisionSeconds: Float?,
    )

    private data class ImageQuality(
        val label: String,
        val tooDark: Boolean,
        val lowContrast: Boolean,
    )
}
