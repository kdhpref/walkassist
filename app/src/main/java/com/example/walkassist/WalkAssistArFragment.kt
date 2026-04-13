package com.example.walkassist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.Surface
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.ux.ArFragment
import java.io.ByteArrayOutputStream
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class WalkAssistArFragment : ArFragment() {
    private data class BoxDepthEstimate(
        val distanceMeters: Float?,
        val isReference: Boolean = false,
    )

    private data class LaneDistances(
        val floor: Float?,
        val wall: Float?,
        val depth: Float?,
        val rawDepth: Float?,
        val collision: Float?,
    )

    private data class VoxelLaneMetrics(
        val leftDistance: Float?,
        val centerDistance: Float?,
        val rightDistance: Float?,
        val collisionDistance: Float?,
        val leftOccupancyRatio: Float,
        val centerOccupancyRatio: Float,
        val rightOccupancyRatio: Float,
    )

    private data class VoxelObstacleCluster(
        val columns: List<VoxelColumnUi>,
        val nearestDistance: Float,
        val averageConfidence: Float,
    )

    private data class WorldMapLaneMetrics(
        val leftDistance: Float?,
        val centerDistance: Float?,
        val rightDistance: Float?,
        val collisionDistance: Float?,
        val leftOccupancyRatio: Float,
        val centerOccupancyRatio: Float,
        val rightOccupancyRatio: Float,
        val leftFreeSpaceMeters: Float?,
        val centerFreeSpaceMeters: Float?,
        val rightFreeSpaceMeters: Float?,
    )

    private data class CorridorHit(
        val distanceMeters: Float,
        val lateralMeters: Float,
        val forwardMeters: Float,
        val worldX: Float,
        val worldY: Float,
        val worldZ: Float,
        val viewXRatio: Float,
        val viewYRatio: Float,
        val source: HitSource,
        val observationConfidence: Float,
        val isBeyondReliableRange: Boolean = false,
    )

    private data class RawDepthSample(
        val depthMillimeters: Int,
        val confidence: Float,
    )

    private data class FloorMaskState(
        val segmentation: FloorSegmentationResult,
        val imageWidth: Int,
        val imageHeight: Int,
        val timestampNanos: Long,
    )

    private data class TtcRiskResult(
        val label: String,
        val timeToCollisionSeconds: Float?,
    )

    private enum class HitSource {
        FLOOR,
        WALL,
        DEPTH,
    }

    private var smoothedFloorDistance: Float? = null
    private var smoothedWallDistance: Float? = null
    private var smoothedDepthDistance: Float? = null
    private var smoothedRawDepthDistance: Float? = null
    private var smoothedCollisionDistance: Float? = null
    private var smoothedLeftDistance: Float? = null
    private var smoothedCenterDistance: Float? = null
    private var smoothedRightDistance: Float? = null

    private var lastTimestampNs: Long? = null
    private var lastCameraX: Float? = null
    private var lastCameraY: Float? = null
    private var lastCameraZ: Float? = null

    private val objectAnalyzer by lazy { ObjectAnalyzer(requireContext().applicationContext) }
    private val floorSegmenter by lazy { ModelFloorSegmenter(requireContext().applicationContext) }
    private val objectTracker = ObjectTracker()
    private val detectorExecutor = Executors.newSingleThreadExecutor()
    private val detectionInFlight = AtomicBoolean(false)
    private var lastDetectionStartedAtMs = 0L
    private var lastObjectDetections: List<ObjectOverlayDetection> = emptyList()
    @Volatile
    private var lastFloorMaskState: FloorMaskState? = null
    private val worldLocalMap = WorldLocalMap(
        halfRangeMeters = 5f,
        cellSizeMeters = 0.2f,
    )
    private val worldVoxelModel = WorldVoxelModel()

    private val collisionHistory = ArrayDeque<Float>()
    private val directionHistory = ArrayDeque<String>()
    private var stableDirection = "searching"

    override fun getSessionConfiguration(session: Session): Config {
        planeDiscoveryController.hide()
        planeDiscoveryController.setInstructionView(null)
        arSceneView.planeRenderer.isEnabled = false

        return Config(session).apply {
            planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
            instantPlacementMode = Config.InstantPlacementMode.LOCAL_Y_UP
            lightEstimationMode = Config.LightEstimationMode.DISABLED
            if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
                depthMode = Config.DepthMode.AUTOMATIC
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        arSceneView.scene.addOnUpdateListener {
            publishFrameState()
        }
    }

    override fun onDestroy() {
        detectorExecutor.shutdownNow()
        objectAnalyzer.close()
        super.onDestroy()
    }

    private fun publishFrameState() {
        val frame = arSceneView.arFrame ?: return
        val camera = frame.camera
        scheduleVisionAnalysis(frame)
        val trackedPlanes = arSceneView.session?.getAllTrackables(Plane::class.java).orEmpty()
        val horizontalPlaneCount = trackedPlanes.count {
            it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.HORIZONTAL_UPWARD_FACING
        }
        val verticalPlaneCount = trackedPlanes.count {
            it.trackingState == TrackingState.TRACKING && it.type == Plane.Type.VERTICAL
        }

        if (camera.trackingState != TrackingState.TRACKING) {
            directionHistory.clear()
            stableDirection = "searching"
            ArMeasurementBridge.publish(
                ArMeasurementState(
                    trackingLabel = camera.trackingState.name.lowercase(),
                    trackingFailureLabel = camera.trackingFailureReason.name.lowercase().replace('_', ' '),
                    horizontalPlaneCount = horizontalPlaneCount,
                    verticalPlaneCount = verticalPlaneCount,
                    guidanceLabel = "Scan the floor and wall slowly.",
                    statusLabel = "Move the phone left and right over a textured floor.",
                    statusLevel = ArStatusLevel.INFO,
                    objectDetections = lastObjectDetections,
                    planeDetections = emptyList(),
                    planePolygons = emptyList(),
                    worldMapRangeMeters = worldLocalMap.rangeMeters(),
                    worldMapCellSizeMeters = worldLocalMap.cellSizeMeters(),
                    worldMapObservationCount = worldLocalMap.totalObservationCount(),
                    worldMapConfidenceScore = worldLocalMap.averageConfidenceScore(),
                    voxelColumns = emptyList(),
                    voxelPoints = emptyList(),
                    voxelOverlayPoints = emptyList(),
                    voxelRangeMeters = 0f,
                    voxelSizeMeters = 0f,
                    voxelKnownCount = 0,
                    voxelOccupiedCount = 0,
                    voxelObstacleColumns = 0,
                    voxelConfidenceScore = 0,
                    note = "ARCore needs visible feature points and steady motion.",
                ),
            )
            return
        }

        val pitchDownDegrees = computePitchDownDegrees(frame)
        val corridorHits = sampleWorldCorridor(frame)
        val floorMaskState = currentFloorMaskState(frame.timestamp)
        val rawDepthHits = sampleRawDepthCorridor(frame)
        val overlayDetections = enrichObjectDetections(frame, lastObjectDetections)
        val nearestPersonDetection = overlayDetections
            .filter {
                it.label.equals("person", ignoreCase = true) &&
                    it.distanceMeters != null &&
                    !it.distanceIsReference
            }
            .sortedWith(
                compareBy<ObjectOverlayDetection> { if (it.isStable) 0 else 1 }
                    .thenBy { it.distanceMeters ?: Float.MAX_VALUE },
            )
            .firstOrNull()
        val cameraPose = frame.camera.displayOrientedPose
        val mapObservations = (corridorHits + rawDepthHits).map {
            WorldMapObservation(
                worldX = it.worldX,
                worldZ = it.worldZ,
                source = it.source.name,
                strength = obstacleStrengthForHit(
                    source = it.source,
                    relativeHeightMeters = it.worldY - cameraPose.ty(),
                ),
                confidence = it.observationConfidence,
            )
        }
        worldLocalMap.update(
            cameraWorldX = cameraPose.tx(),
            cameraWorldZ = cameraPose.tz(),
            observations = mapObservations,
        )
        val worldMapSnapshot = worldLocalMap.snapshot(
            cameraWorldX = cameraPose.tx(),
            cameraWorldZ = cameraPose.tz(),
            cameraXAxisX = cameraPose.xAxis[0],
            cameraXAxisZ = cameraPose.xAxis[2],
            cameraZAxisX = cameraPose.zAxis[0],
            cameraZAxisZ = cameraPose.zAxis[2],
        )
        val voxelSnapshot = emptyList<VoxelColumnUi>()
        val voxelPoints = emptyList<VoxelPointUi>()
        val voxelOverlayPoints = emptyList<VoxelOverlayPointUi>()

        val leftLane = corridorLaneDistances(corridorHits, rawDepthHits, "left", floorMaskState)
        val centerLane = corridorLaneDistances(corridorHits, rawDepthHits, "center", floorMaskState)
        val rightLane = corridorLaneDistances(corridorHits, rawDepthHits, "right", floorMaskState)

        val floorDistance = listOfNotNull(leftLane.floor, centerLane.floor, rightLane.floor).minOrNull()
        val wallDistance = listOfNotNull(leftLane.wall, centerLane.wall, rightLane.wall).minOrNull()
        val depthDistance = listOfNotNull(leftLane.depth, centerLane.depth, rightLane.depth).minOrNull()
        val rawDepthDistance = listOfNotNull(leftLane.rawDepth, centerLane.rawDepth, rightLane.rawDepth).minOrNull()
        val rawCollisionDistance = listOfNotNull(
            leftLane.collision,
            centerLane.collision,
            rightLane.collision,
        ).minOrNull()

        val smoothedFloor = smoothDistance(smoothedFloorDistance, floorDistance).also { smoothedFloorDistance = it }
        val smoothedWall = smoothDistance(smoothedWallDistance, wallDistance).also { smoothedWallDistance = it }
        val smoothedDepth = smoothDistance(smoothedDepthDistance, depthDistance).also { smoothedDepthDistance = it }
        val smoothedRawDepth = smoothDistance(smoothedRawDepthDistance, rawDepthDistance).also {
            smoothedRawDepthDistance = it
        }
        val fusedRawCollisionDistance = nearestPersonDetection?.distanceMeters ?: rawCollisionDistance
        val collisionDistance = smoothDistance(smoothedCollisionDistance, fusedRawCollisionDistance).also {
            smoothedCollisionDistance = it
        }
        val leftDistance = smoothDistance(
            smoothedLeftDistance,
            leftLane.collision,
        ).also {
            smoothedLeftDistance = it
        }
        val centerDistance = smoothDistance(
            smoothedCenterDistance,
            centerLane.collision,
        ).also {
            smoothedCenterDistance = it
        }
        val rightDistance = smoothDistance(
            smoothedRightDistance,
            rightLane.collision,
        ).also {
            smoothedRightDistance = it
        }

        val motionSpeed = computeMotionSpeed(frame)
        val approachSpeed = computeApproachSpeed(collisionDistance)
        val ttcRisk = computeTtcRisk(collisionDistance, approachSpeed, motionSpeed)
        val riskLabel = ttcRisk.label
        val sensingConfidenceScore = computeSensingConfidenceScore(
            horizontalPlaneCount = horizontalPlaneCount,
            verticalPlaneCount = verticalPlaneCount,
            collisionDistance = collisionDistance,
            rawDepthDistance = smoothedRawDepth,
            motionSpeed = motionSpeed,
            trackingFailureLabel = camera.trackingFailureReason.name,
        )
        val instantDirection = computeSuggestedDirection(
            leftDistance = leftDistance,
            centerDistance = centerDistance,
            rightDistance = rightDistance,
            leftLaneScore = null,
            centerLaneScore = null,
            rightLaneScore = null,
        )
        val suggestedDirection = stabilizeDirection(instantDirection, riskLabel)
        val sceneGuidanceLabel = computeGuidanceLabelForScene(
            suggestedDirection = suggestedDirection,
            collisionDistance = collisionDistance,
            riskLabel = riskLabel,
            nearestPerson = nearestPersonDetection,
        )

        val (baseStatusLabel, level, baseNote) = when {
            collisionDistance == null -> Triple(
                "No surface locked yet",
                ArStatusLevel.INFO,
                "Sweep the camera slowly until planes or depth points appear.",
            )
            riskLabel == "critical" -> Triple(
                "Obstacle ahead",
                ArStatusLevel.DANGER,
                "Close and getting closer.",
            )
            riskLabel == "high" -> Triple(
                "Obstacle ahead",
                ArStatusLevel.WARNING,
                "Avoidance recommended.",
            )
            collisionDistance < 1.5f -> Triple(
                "Obstacle ahead",
                ArStatusLevel.WARNING,
                "Keep moving carefully.",
            )
            else -> Triple(
                "Path measured",
                ArStatusLevel.SAFE,
                "Lane distances are stable.",
            )
        }

        val guidanceLabel = sceneGuidanceLabel
        val statusLabel = baseStatusLabel
        val note = baseNote

        ArMeasurementBridge.publish(
            ArMeasurementState(
                trackingLabel = "tracking",
                horizontalPlaneCount = horizontalPlaneCount,
                verticalPlaneCount = verticalPlaneCount,
                sensingConfidenceScore = sensingConfidenceScore,
                pitchDownDegrees = pitchDownDegrees,
                leftLaneWidthRatio = 0.33f,
                centerLaneWidthRatio = 0.34f,
                rightLaneWidthRatio = 0.33f,
                leftDistanceMeters = leftDistance,
                centerDistanceMeters = centerDistance,
                rightDistanceMeters = rightDistance,
                suggestedDirection = suggestedDirection,
                floorDistanceMeters = smoothedFloor,
                wallDistanceMeters = smoothedWall,
                depthDistanceMeters = smoothedDepth,
                rawDepthDistanceMeters = smoothedRawDepth,
                collisionDistanceMeters = collisionDistance,
                approachSpeedMetersPerSecond = approachSpeed,
                motionMetersPerSecond = motionSpeed,
                timeToCollisionSeconds = ttcRisk.timeToCollisionSeconds,
                riskLabel = riskLabel,
                guidanceLabel = guidanceLabel,
                statusLabel = statusLabel,
                statusLevel = level,
                objectDetections = overlayDetections,
                planeDetections = emptyList(),
                planePolygons = emptyList(),
                worldMapCells = worldMapSnapshot,
                worldMapRangeMeters = worldLocalMap.rangeMeters(),
                worldMapCellSizeMeters = worldLocalMap.cellSizeMeters(),
                worldMapKnownCells = worldLocalMap.knownCellCount(),
                worldMapOccupiedCells = worldLocalMap.occupiedCellCount(),
                worldMapObservationCount = worldLocalMap.totalObservationCount(),
                worldMapConfidenceScore = worldLocalMap.averageConfidenceScore(),
                voxelColumns = voxelSnapshot,
                voxelPoints = voxelPoints,
                voxelOverlayPoints = voxelOverlayPoints,
                voxelRangeMeters = 0f,
                voxelSizeMeters = 0f,
                voxelKnownCount = 0,
                voxelOccupiedCount = 0,
                voxelObstacleColumns = 0,
                voxelConfidenceScore = 0,
                note = note,
            ),
        )
    }

    private fun scheduleVisionAnalysis(frame: Frame) {
        if (detectionInFlight.get()) return

        val now = SystemClock.elapsedRealtime()
        if (now - lastDetectionStartedAtMs < 450L) return

        val image = try {
            frame.acquireCameraImage()
        } catch (_: NotYetAvailableException) {
            return
        } catch (_: IllegalStateException) {
            return
        }
        val rotationDegrees = displayRotationDegrees()
        val localFloorSegmenter = floorSegmenter
        val localObjectAnalyzer = objectAnalyzer

        lastDetectionStartedAtMs = now
        detectionInFlight.set(true)
        detectorExecutor.execute {
            try {
                val bitmap = image.toUprightBitmap(rotationDegrees)
                image.close()
                val floorSegmentation = localFloorSegmenter.segment(bitmap)
                lastFloorMaskState = FloorMaskState(
                    segmentation = floorSegmentation,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    timestampNanos = frame.timestamp,
                )
                val detectedObjects = if (localObjectAnalyzer.isReady()) {
                    localObjectAnalyzer.detect(bitmap)
                } else {
                    emptyList()
                }
                val trackedDetections = objectTracker.update(
                    detections = detectedObjects.map { detection ->
                        DetectedObjectResult(
                            boundingBox = detection.boundingBox,
                            confidence = detection.confidence,
                            imageHeight = detection.imageHeight,
                            imageWidth = detection.imageWidth,
                            label = detection.label,
                            distanceEstimate = DetectionDistanceEstimate(
                                distanceMeters = null,
                                rawGeometryDistanceMeters = null,
                                qualityScore = detection.confidence,
                                source = DistanceSource.UNKNOWN,
                                riskLevel = RiskLevel.SAFE,
                            ),
                        )
                    },
                    timestampNanos = frame.timestamp,
                )
                val prioritizedDetections = trackedDetections
                    .sortedWith(
                        compareByDescending<DetectedObjectResult> { it.trackingState?.isStable == true }
                            .thenBy { it.trackingState?.missedFrames ?: 0 }
                            .thenByDescending { it.confidence },
                    )
                    .take(6)
                    .map { detection ->
                        val centerXRatio =
                            ((detection.boundingBox.left + detection.boundingBox.right) * 0.5f / bitmap.width)
                                .coerceIn(0f, 1f)
                        ObjectOverlayDetection(
                            leftRatio = (detection.boundingBox.left / bitmap.width).coerceIn(0f, 1f),
                            topRatio = (detection.boundingBox.top / bitmap.height).coerceIn(0f, 1f),
                            widthRatio = (detection.boundingBox.width() / bitmap.width).coerceIn(0.02f, 1f),
                            heightRatio = (detection.boundingBox.height() / bitmap.height).coerceIn(0.02f, 1f),
                            label = detection.label,
                            confidence = detection.confidence,
                            lane = classifyScreenLane(centerXRatio),
                            isStable = detection.trackingState?.isStable == true,
                        )
                    }
                lastObjectDetections = prioritizedDetections
                bitmap.recycle()
            } catch (_: Exception) {
                runCatching { image.close() }
            } finally {
                detectionInFlight.set(false)
            }
        }
    }

    private fun displayRotationDegrees(): Int {
        val rotation = requireActivity().display?.rotation ?: Surface.ROTATION_0
        return when (rotation) {
            Surface.ROTATION_90 -> 0
            Surface.ROTATION_180 -> 270
            Surface.ROTATION_270 -> 180
            else -> 90
        }
    }

    private fun sampleWorldCorridor(frame: Frame): List<CorridorHit> {
        val width = arSceneView.width.toFloat().coerceAtLeast(1f)
        val height = arSceneView.height.toFloat().coerceAtLeast(1f)
        val sampleXs = listOf(0.14f, 0.24f, 0.34f, 0.44f, 0.5f, 0.56f, 0.66f, 0.76f, 0.86f)
        val sampleYs = listOf(0.44f, 0.56f, 0.68f, 0.8f)

        return buildList {
                for (yRatio in sampleYs) {
                    for (xRatio in sampleXs) {
                        val hit = nearestWorldHit(
                            frame = frame,
                            x = width * xRatio,
                            y = height * yRatio,
                            viewXRatio = xRatio,
                            viewYRatio = yRatio,
                        )
                        if (hit != null) {
                            add(hit)
                        }
                    }
                }
        }
    }

    private fun corridorLaneDistances(
        hits: List<CorridorHit>,
        rawDepthHits: List<CorridorHit>,
        lane: String,
        floorMaskState: FloorMaskState?,
    ): LaneDistances {
        val filtered = hits.filter { classifyLane(it.lateralMeters) == lane }
        val obstacleHits = filtered.filter {
            it.source != HitSource.FLOOR && !isInsideWalkableFloorMask(it, floorMaskState)
        }
        val rawDepth = robustLaneRawDepthDistance(rawDepthHits, lane, floorMaskState)
        val floor = filtered.filter { it.source == HitSource.FLOOR }.minOfOrNull { it.distanceMeters }
        val wall = obstacleHits.filter { it.source == HitSource.WALL }.minOfOrNull { it.distanceMeters }
        val depth = obstacleHits.filter { it.source == HitSource.DEPTH }.minOfOrNull { it.distanceMeters }
        return LaneDistances(
            floor = floor,
            wall = wall,
            depth = depth,
            rawDepth = rawDepth,
            collision = listOfNotNull(wall, depth, rawDepth).minOrNull(),
        )
    }

    private fun robustLaneRawDepthDistance(
        rawDepthHits: List<CorridorHit>,
        lane: String,
        floorMaskState: FloorMaskState?,
    ): Float? {
        val laneDistances = rawDepthHits
            .filter { classifyLane(it.lateralMeters) == lane }
            .filterNot { isInsideWalkableFloorMask(it, floorMaskState) }
            .map { it.distanceMeters }
            .sorted()

        if (laneDistances.isEmpty()) return null

        // Avoid using the single nearest raw-depth point because it is often a flickering outlier.
        val percentileIndex = ((laneDistances.size - 1) * 0.3f).toInt().coerceIn(0, laneDistances.lastIndex)
        return laneDistances[percentileIndex]
    }

    private fun currentFloorMaskState(frameTimestampNanos: Long): FloorMaskState? {
        val state = lastFloorMaskState ?: return null
        val ageSeconds = (frameTimestampNanos - state.timestampNanos) / 1_000_000_000f
        return if (ageSeconds in 0f..2.0f && state.segmentation.confidence >= 0.25f) state else null
    }

    private fun isInsideWalkableFloorMask(
        hit: CorridorHit,
        floorMaskState: FloorMaskState?,
    ): Boolean {
        val state = floorMaskState ?: return false
        val imageX = hit.viewXRatio * state.imageWidth.toFloat()
        val imageY = hit.viewYRatio * state.imageHeight.toFloat()
        val floorBoundaryY = state.segmentation.boundaryYAt(
            imageX = imageX,
            imageWidth = state.imageWidth,
            imageHeight = state.imageHeight,
        ) ?: return false
        val floorMarginPixels = state.imageHeight * 0.025f
        return imageY >= floorBoundaryY - floorMarginPixels
    }

    private fun evaluateVoxelLanes(
        columns: List<VoxelColumnUi>,
    ): VoxelLaneMetrics {
        val obstacleColumns = columns.filter { column ->
            column.relativeZ in 0.25f..5.0f &&
                column.occupancyScore >= 0.2f &&
                column.confidenceScore >= 0.22f &&
                column.heightMeters >= -1.05f
        }

        val obstacleClusters = buildVoxelObstacleClusters(obstacleColumns)

        fun laneColumns(lane: String): List<VoxelColumnUi> {
            return obstacleColumns.filter { classifyLane(it.relativeX) == lane }
        }

        fun nearestDistance(lane: String): Float? {
            return obstacleClusters
                .filter { cluster ->
                    cluster.columns.any { classifyLane(it.relativeX) == lane }
                }
                .map { it.nearestDistance }
                .minOrNull()
        }

        fun occupancyRatio(lane: String): Float {
            val relevant = columns.filter { it.relativeZ in 0.25f..4.5f && classifyLane(it.relativeX) == lane }
            if (relevant.isEmpty()) return 0f
            val occupied = relevant.count {
                it.occupancyScore >= 0.2f &&
                    it.confidenceScore >= 0.22f &&
                    it.heightMeters >= -1.05f
            }
            return (occupied.toFloat() / relevant.size.toFloat()).coerceIn(0f, 1f)
        }

        val leftDistance = nearestDistance("left")
        val centerDistance = nearestDistance("center")
        val rightDistance = nearestDistance("right")
        return VoxelLaneMetrics(
            leftDistance = leftDistance,
            centerDistance = centerDistance,
            rightDistance = rightDistance,
            collisionDistance = listOfNotNull(leftDistance, centerDistance, rightDistance).minOrNull(),
            leftOccupancyRatio = occupancyRatio("left"),
            centerOccupancyRatio = occupancyRatio("center"),
            rightOccupancyRatio = occupancyRatio("right"),
        )
    }

    private fun buildVoxelObstacleClusters(
        columns: List<VoxelColumnUi>,
    ): List<VoxelObstacleCluster> {
        if (columns.isEmpty()) return emptyList()

        val remaining = columns.toMutableList()
        val clusters = mutableListOf<VoxelObstacleCluster>()
        val lateralThreshold = worldVoxelModel.voxelSizeMeters() * 1.7f
        val forwardThreshold = worldVoxelModel.voxelSizeMeters() * 1.7f

        while (remaining.isNotEmpty()) {
            val seed = remaining.removeAt(0)
            val clusterColumns = mutableListOf(seed)
            var index = 0
            while (index < clusterColumns.size) {
                val current = clusterColumns[index]
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val candidate = iterator.next()
                    if (
                        abs(candidate.relativeX - current.relativeX) <= lateralThreshold &&
                        abs(candidate.relativeZ - current.relativeZ) <= forwardThreshold
                    ) {
                        clusterColumns += candidate
                        iterator.remove()
                    }
                }
                index += 1
            }

            if (clusterColumns.size < 2) continue

            val nearestDistance = clusterColumns
                .map { sqrt((it.relativeX * it.relativeX) + (it.relativeZ * it.relativeZ)) }
                .minOrNull() ?: continue
            val averageConfidence = clusterColumns.map { it.confidenceScore }.average().toFloat()
            if (averageConfidence < 0.24f) continue

            clusters += VoxelObstacleCluster(
                columns = clusterColumns,
                nearestDistance = nearestDistance,
                averageConfidence = averageConfidence,
            )
        }

        return clusters.sortedBy { it.nearestDistance }
    }

    private fun evaluateWorldMapLanes(
        cells: List<WorldMapCellUi>,
    ): WorldMapLaneMetrics {
        val obstacleCells = cells.filter { cell ->
            cell.relativeZ in 0.25f..5.0f &&
                cell.occupancyScore >= 0.16f &&
                cell.confidenceScore >= 0.18f
        }

        fun laneCells(lane: String): List<WorldMapCellUi> {
            return obstacleCells.filter { classifyLane(it.relativeX) == lane }
        }

        fun nearestDistance(lane: String): Float? {
            return laneCells(lane)
                .map { sqrt((it.relativeX * it.relativeX) + (it.relativeZ * it.relativeZ)) }
                .minOrNull()
        }

        fun occupancyRatio(lane: String): Float {
            val relevant = cells.filter { it.relativeZ in 0.25f..4.5f && classifyLane(it.relativeX) == lane }
            if (relevant.isEmpty()) return 0f
            val occupied = relevant.count {
                it.occupancyScore >= 0.16f && it.confidenceScore >= 0.18f
            }
            return (occupied.toFloat() / relevant.size.toFloat()).coerceIn(0f, 1f)
        }

        fun freeSpaceLength(lane: String): Float? {
            val relevant = cells.filter { classifyLane(it.relativeX) == lane && it.relativeZ in 0.25f..5.0f }
            if (relevant.isEmpty()) return null

            val stepMeters = 0.25f
            val maxDistance = 5.0f
            var probe = 0.25f
            var lastFree = 0.25f
            while (probe <= maxDistance) {
                val blocked = relevant.any { cell ->
                    cell.relativeZ in (probe - 0.18f)..(probe + 0.18f) &&
                        cell.occupancyScore >= 0.16f &&
                        cell.confidenceScore >= 0.18f
                }
                if (blocked) {
                    return lastFree.coerceAtLeast(0.25f)
                }
                lastFree = probe
                probe += stepMeters
            }
            return maxDistance
        }

        val leftDistance = nearestDistance("left")
        val centerDistance = nearestDistance("center")
        val rightDistance = nearestDistance("right")
        return WorldMapLaneMetrics(
            leftDistance = leftDistance,
            centerDistance = centerDistance,
            rightDistance = rightDistance,
            collisionDistance = listOfNotNull(leftDistance, centerDistance, rightDistance).minOrNull(),
            leftOccupancyRatio = occupancyRatio("left"),
            centerOccupancyRatio = occupancyRatio("center"),
            rightOccupancyRatio = occupancyRatio("right"),
            leftFreeSpaceMeters = freeSpaceLength("left"),
            centerFreeSpaceMeters = freeSpaceLength("center"),
            rightFreeSpaceMeters = freeSpaceLength("right"),
        )
    }

    private fun computeLaneScore(
        distance: Float?,
        occupancyRatio: Float,
        freeSpaceMeters: Float?,
    ): Float {
        val normalizedDistance = (distance ?: 0f).coerceIn(0f, 5f) / 5f
        val normalizedFreeSpace = (freeSpaceMeters ?: 0f).coerceIn(0f, 5f) / 5f
        val openRatio = (1f - occupancyRatio.coerceIn(0f, 1f))
        return (
            (normalizedDistance * 0.45f) +
                (normalizedFreeSpace * 0.4f) +
                (openRatio * 0.15f)
            ).coerceIn(0f, 1f)
    }

    private fun obstacleStrengthForHit(
        source: HitSource,
        relativeHeightMeters: Float,
    ): Float {
        return when (source) {
            HitSource.FLOOR -> 0f
            HitSource.WALL -> 0.42f
            HitSource.DEPTH -> {
                when {
                    relativeHeightMeters <= -1.05f -> 0f
                    relativeHeightMeters <= -0.9f -> 0.08f
                    relativeHeightMeters <= -0.75f -> 0.18f
                    else -> 0.34f
                }
            }
        }
    }

    private fun classifyLane(lateralMeters: Float): String? {
        return when {
            lateralMeters < -0.28f && lateralMeters >= -1.05f -> "left"
            lateralMeters in -0.28f..0.28f -> "center"
            lateralMeters > 0.28f && lateralMeters <= 1.05f -> "right"
            else -> null
        }
    }

    private fun classifyScreenLane(centerXRatio: Float): String {
        return when {
            centerXRatio < 0.38f -> "left"
            centerXRatio > 0.62f -> "right"
            else -> "center"
        }
    }

    private fun enrichObjectDetections(
        frame: Frame,
        detections: List<ObjectOverlayDetection>,
    ): List<ObjectOverlayDetection> {
        if (detections.isEmpty()) return emptyList()

        return try {
            frame.acquireRawDepthImage16Bits().use { rawDepthImage ->
                frame.acquireRawDepthConfidenceImage().use { confidenceImage ->
                    detections.map { detection ->
                        val centerXRatio = (detection.leftRatio + (detection.widthRatio * 0.5f)).coerceIn(0f, 1f)
                        val lane = classifyScreenLane(centerXRatio)
                        val boxDepthEstimate = computeBoxDepthEstimate(
                            frame = frame,
                            rawDepthImage = rawDepthImage,
                            confidenceImage = confidenceImage,
                            detection = detection,
                        )

                        detection.copy(
                            distanceMeters = boxDepthEstimate.distanceMeters,
                            distanceIsReference = boxDepthEstimate.isReference,
                            lane = lane,
                        )
                    }
                }
            }
        } catch (_: NotYetAvailableException) {
            detections
        } catch (_: IllegalStateException) {
            detections
        }
    }

    private fun computeBoxDepthEstimate(
        frame: Frame,
        rawDepthImage: Image,
        confidenceImage: Image,
        detection: ObjectOverlayDetection,
    ): BoxDepthEstimate {
        val width = arSceneView.width.toFloat().coerceAtLeast(1f)
        val height = arSceneView.height.toFloat().coerceAtLeast(1f)
        val innerHits = sampleBoxDepthHits(
            frame = frame,
            rawDepthImage = rawDepthImage,
            confidenceImage = confidenceImage,
            width = width,
            height = height,
            detection = detection,
            sampleXs = listOf(0.2f, 0.35f, 0.5f, 0.65f, 0.8f),
            sampleYs = listOf(0.25f, 0.4f, 0.55f, 0.7f, 0.85f),
        )
        if (innerHits.size < 6) return BoxDepthEstimate(distanceMeters = null)

        val surroundingHits = sampleBoxDepthHits(
            frame = frame,
            rawDepthImage = rawDepthImage,
            confidenceImage = confidenceImage,
            width = width,
            height = height,
            detection = detection,
            sampleXs = listOf(0.1f, 0.5f, 0.9f),
            sampleYs = listOf(0.12f, 0.5f, 0.88f),
            expandRatioX = 0.18f,
            expandRatioY = 0.18f,
            edgeOnly = true,
        )

        val farInnerCount = innerHits.count { it.isBeyondReliableRange }
        val farSurroundingCount = surroundingHits.count { it.isBeyondReliableRange }
        val hasReliableFarContext =
            farInnerCount >= (innerHits.size / 2) &&
                farSurroundingCount >= maxOf(2, surroundingHits.size / 3)

        if (hasReliableFarContext) {
            return BoxDepthEstimate(
                distanceMeters = 5f,
                isReference = true,
            )
        }

        return BoxDepthEstimate(
            distanceMeters = innerHits.map { it.distanceMeters }.average().toFloat(),
            isReference = false,
        )
    }

    private fun sampleBoxDepthHits(
        frame: Frame,
        rawDepthImage: Image,
        confidenceImage: Image,
        width: Float,
        height: Float,
        detection: ObjectOverlayDetection,
        sampleXs: List<Float>,
        sampleYs: List<Float>,
        expandRatioX: Float = 0f,
        expandRatioY: Float = 0f,
        edgeOnly: Boolean = false,
    ): List<CorridorHit> {
        val expandedLeft = (detection.leftRatio - expandRatioX).coerceIn(0f, 1f)
        val expandedTop = (detection.topRatio - expandRatioY).coerceIn(0f, 1f)
        val expandedRight = (detection.leftRatio + detection.widthRatio + expandRatioX).coerceIn(0f, 1f)
        val expandedBottom = (detection.topRatio + detection.heightRatio + expandRatioY).coerceIn(0f, 1f)
        val expandedWidth = (expandedRight - expandedLeft).coerceAtLeast(0.01f)
        val expandedHeight = (expandedBottom - expandedTop).coerceAtLeast(0.01f)

        return buildList {
            sampleYs.forEachIndexed { yIndex, yFactor ->
                sampleXs.forEachIndexed xLoop@{ xIndex, xFactor ->
                    if (edgeOnly) {
                        val isEdgeX = xIndex == 0 || xIndex == sampleXs.lastIndex
                        val isEdgeY = yIndex == 0 || yIndex == sampleYs.lastIndex
                        if (!(isEdgeX || isEdgeY)) return@xLoop
                    }
                    val sampleX = width * (expandedLeft + (expandedWidth * xFactor))
                    val sampleY = height * (expandedTop + (expandedHeight * yFactor))
                    rawDepthHitAt(
                        frame = frame,
                        rawDepthImage = rawDepthImage,
                        confidenceImage = confidenceImage,
                        viewX = sampleX,
                        viewY = sampleY,
                        requireLane = false,
                        maxForwardMeters = 10f,
                    )?.let(::add)
                }
            }
        }
    }

    private fun nearestWorldHit(
        frame: Frame,
        x: Float,
        y: Float,
        viewXRatio: Float,
        viewYRatio: Float,
    ): CorridorHit? {
        val cameraPose = frame.camera.displayOrientedPose
        val xAxis = cameraPose.xAxis
        val zAxis = cameraPose.zAxis
        val cameraX = cameraPose.tx()
        val cameraY = cameraPose.ty()
        val cameraZ = cameraPose.tz()

        return frame.hitTest(x, y)
            .asSequence()
            .mapNotNull { hit ->
                val dx = hit.hitPose.tx() - cameraX
                val dy = hit.hitPose.ty() - cameraY
                val dz = hit.hitPose.tz() - cameraZ

                val lateral = (dx * xAxis[0]) + (dy * xAxis[1]) + (dz * xAxis[2])
                val forward = -((dx * zAxis[0]) + (dy * zAxis[1]) + (dz * zAxis[2]))
                if (forward <= 0.15f || forward > 5.0f) {
                    return@mapNotNull null
                }

                when (val trackable = hit.trackable) {
                    is Plane -> {
                        if (!trackable.isPoseInPolygon(hit.hitPose)) return@mapNotNull null
                        val source = when (trackable.type) {
                            Plane.Type.HORIZONTAL_UPWARD_FACING -> HitSource.FLOOR
                            Plane.Type.VERTICAL -> HitSource.WALL
                            else -> return@mapNotNull null
                        }
                        CorridorHit(
                            distanceMeters = distanceMeters(
                                cameraX,
                                cameraY,
                                cameraZ,
                                hit.hitPose.tx(),
                                hit.hitPose.ty(),
                                hit.hitPose.tz(),
                            ),
                            lateralMeters = lateral,
                            forwardMeters = forward,
                            worldX = hit.hitPose.tx(),
                            worldY = hit.hitPose.ty(),
                            worldZ = hit.hitPose.tz(),
                            viewXRatio = viewXRatio,
                            viewYRatio = viewYRatio,
                            source = source,
                            observationConfidence = if (source == HitSource.FLOOR) 0.92f else 0.88f,
                        )
                    }
                    is DepthPoint -> CorridorHit(
                        distanceMeters = distanceMeters(
                            cameraX,
                            cameraY,
                            cameraZ,
                            hit.hitPose.tx(),
                            hit.hitPose.ty(),
                            hit.hitPose.tz(),
                        ),
                        lateralMeters = lateral,
                        forwardMeters = forward,
                        worldX = hit.hitPose.tx(),
                        worldY = hit.hitPose.ty(),
                        worldZ = hit.hitPose.tz(),
                        viewXRatio = viewXRatio,
                        viewYRatio = viewYRatio,
                        source = HitSource.DEPTH,
                        observationConfidence = 0.72f,
                    )
                    is Point -> {
                        if (trackable.orientationMode != Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                            return@mapNotNull null
                        }
                        CorridorHit(
                            distanceMeters = distanceMeters(
                                cameraX,
                                cameraY,
                                cameraZ,
                                hit.hitPose.tx(),
                                hit.hitPose.ty(),
                                hit.hitPose.tz(),
                            ),
                            lateralMeters = lateral,
                            forwardMeters = forward,
                            worldX = hit.hitPose.tx(),
                            worldY = hit.hitPose.ty(),
                            worldZ = hit.hitPose.tz(),
                            viewXRatio = viewXRatio,
                            viewYRatio = viewYRatio,
                            source = HitSource.DEPTH,
                            observationConfidence = 0.64f,
                        )
                    }
                    else -> null
                }
            }
            .filter { classifyLane(it.lateralMeters) != null }
            .minByOrNull { it.distanceMeters }
    }

    private fun sampleRawDepthCorridor(frame: Frame): List<CorridorHit> {
        val width = arSceneView.width.toFloat().coerceAtLeast(1f)
        val height = arSceneView.height.toFloat().coerceAtLeast(1f)
        val sampleXs = listOf(0.18f, 0.3f, 0.42f, 0.5f, 0.58f, 0.7f, 0.82f)
        val sampleYs = listOf(0.42f, 0.54f, 0.66f, 0.78f)

        return try {
            frame.acquireRawDepthImage16Bits().use { rawDepthImage ->
                frame.acquireRawDepthConfidenceImage().use { confidenceImage ->
                    buildList {
                        for (yRatio in sampleYs) {
                            for (xRatio in sampleXs) {
                                val hit = rawDepthHitAt(
                                    frame = frame,
                                    rawDepthImage = rawDepthImage,
                                    confidenceImage = confidenceImage,
                                    viewX = width * xRatio,
                                    viewY = height * yRatio,
                                )
                                if (hit != null) {
                                    add(hit)
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: NotYetAvailableException) {
            emptyList()
        } catch (_: IllegalStateException) {
            emptyList()
        }
    }

    private fun rawDepthHitAt(
        frame: Frame,
        rawDepthImage: Image,
        confidenceImage: Image,
        viewX: Float,
        viewY: Float,
        requireLane: Boolean = true,
        maxForwardMeters: Float = 5.0f,
    ): CorridorHit? {
        val textureCoordinates = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.VIEW,
            floatArrayOf(viewX, viewY),
            Coordinates2d.TEXTURE_NORMALIZED,
            textureCoordinates,
        )
        val textureX = textureCoordinates[0]
        val textureY = textureCoordinates[1]
        if (textureX !in 0f..1f || textureY !in 0f..1f) {
            return null
        }

        val depthX = (textureX * rawDepthImage.width).toInt().coerceIn(0, rawDepthImage.width - 1)
        val depthY = (textureY * rawDepthImage.height).toInt().coerceIn(0, rawDepthImage.height - 1)

        val rawDepthSample = sampleRawDepthSample(
            depthImage = rawDepthImage,
            confidenceImage = confidenceImage,
            centerX = depthX,
            centerY = depthY,
        )
        if (rawDepthSample == null) {
            return null
        }

        val imageCoordinates = FloatArray(2)
        frame.transformCoordinates2d(
            Coordinates2d.TEXTURE_NORMALIZED,
            floatArrayOf(textureX, textureY),
            Coordinates2d.IMAGE_PIXELS,
            imageCoordinates,
        )

        val fx = frame.camera.imageIntrinsics.focalLength[0]
        val cx = frame.camera.imageIntrinsics.principalPoint[0]
        if (fx == 0f) {
            return null
        }

        val forwardMeters = rawDepthSample.depthMillimeters / 1000f
        if (forwardMeters <= 0.15f || forwardMeters > maxForwardMeters) {
            return null
        }

        val lateralMeters = ((imageCoordinates[0] - cx) / fx) * forwardMeters
        if (requireLane && classifyLane(lateralMeters) == null) {
            return null
        }

        val rayDistanceMeters = sqrt((forwardMeters * forwardMeters) + (lateralMeters * lateralMeters))
        val cameraPose = frame.camera.displayOrientedPose
        val xAxis = cameraPose.xAxis
        val zAxis = cameraPose.zAxis
        val worldX = cameraPose.tx() + (xAxis[0] * lateralMeters) - (zAxis[0] * forwardMeters)
        val worldY = cameraPose.ty() + (xAxis[1] * lateralMeters) - (zAxis[1] * forwardMeters)
        val worldZ = cameraPose.tz() + (xAxis[2] * lateralMeters) - (zAxis[2] * forwardMeters)
        val viewWidth = arSceneView.width.toFloat().coerceAtLeast(1f)
        val viewHeight = arSceneView.height.toFloat().coerceAtLeast(1f)
        return CorridorHit(
            distanceMeters = rayDistanceMeters,
            lateralMeters = lateralMeters,
            forwardMeters = forwardMeters,
            worldX = worldX,
            worldY = worldY,
            worldZ = worldZ,
            viewXRatio = (viewX / viewWidth).coerceIn(0f, 1f),
            viewYRatio = (viewY / viewHeight).coerceIn(0f, 1f),
            source = HitSource.DEPTH,
            observationConfidence = rawDepthSample.confidence,
            isBeyondReliableRange = rawDepthSample.depthMillimeters > 5000,
        )
    }

    private fun getMillimetersDepth(
        depthImage: Image,
        x: Int,
        y: Int,
    ): Int {
        val plane = depthImage.planes[0]
        val byteIndex = (x * plane.pixelStride) + (y * plane.rowStride)
        val buffer = plane.buffer.order(ByteOrder.nativeOrder())
        return buffer.getShort(byteIndex).toInt() and 0xFFFF
    }

    private fun sampleRawDepthSample(
        depthImage: Image,
        confidenceImage: Image,
        centerX: Int,
        centerY: Int,
    ): RawDepthSample? {
        val validDepths = mutableListOf<Int>()
        var confidenceSum = 0f
        val radius = 2

        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val sampleX = (centerX + dx).coerceIn(0, depthImage.width - 1)
                val sampleY = (centerY + dy).coerceIn(0, depthImage.height - 1)
                val confidence = getConfidence(rawDepthImage = confidenceImage, x = sampleX, y = sampleY)
                if (confidence < 80) continue

                val depthMillimeters = getMillimetersDepth(depthImage = depthImage, x = sampleX, y = sampleY)
                if (depthMillimeters in 150..10000) {
                    validDepths += depthMillimeters
                    confidenceSum += confidence / 255f
                }
            }
        }

        if (validDepths.size < 5) return null

        validDepths.sort()
        return RawDepthSample(
            depthMillimeters = validDepths[validDepths.size / 2],
            confidence = (confidenceSum / validDepths.size.toFloat()).coerceIn(0f, 1f),
        )
    }

    private fun getConfidence(
        rawDepthImage: Image,
        x: Int,
        y: Int,
    ): Int {
        val plane = rawDepthImage.planes[0]
        val byteIndex = (x * plane.pixelStride) + (y * plane.rowStride)
        return plane.buffer.get(byteIndex).toInt() and 0xFF
    }


    private fun smoothDistance(previous: Float?, current: Float?): Float? {
        if (current == null) return previous
        if (previous == null) return current
        val delta = abs(current - previous)
        val alpha = if (delta > 0.7f) 0.18f else 0.3f
        return previous + ((current - previous) * alpha)
    }

    private fun formatMetersForNote(distanceMeters: Float): String {
        return if (distanceMeters < 1f) {
            "${(distanceMeters * 100f).toInt()}cm"
        } else {
            String.format("%.1fm", distanceMeters)
        }
    }

    private fun computeSensingConfidenceScore(
        horizontalPlaneCount: Int,
        verticalPlaneCount: Int,
        collisionDistance: Float?,
        rawDepthDistance: Float?,
        motionSpeed: Float?,
        trackingFailureLabel: String,
    ): Int {
        var score = 35
        score += (horizontalPlaneCount.coerceAtMost(2) * 10)
        score += (verticalPlaneCount.coerceAtMost(2) * 7)
        if (collisionDistance != null) score += 16
        if (rawDepthDistance != null) score += 18

        val motionPenalty = when {
            motionSpeed == null -> 0
            motionSpeed > 0.45f -> 18
            motionSpeed > 0.22f -> 10
            motionSpeed > 0.12f -> 4
            else -> 0
        }
        score -= motionPenalty

        if (trackingFailureLabel != "NONE") {
            score -= 15
        }
        return score.coerceIn(0, 100)
    }

    private fun Image.toUprightBitmap(rotationDegrees: Int): Bitmap {
        val nv21 = yuv420888ToNv21(this)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 88, out)
        val jpegBytes = out.toByteArray()
        val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
            ?: error("Failed to decode ARCore camera frame")

        if (rotationDegrees == 0) {
            return bitmap
        }

        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (rotated != bitmap) {
            bitmap.recycle()
        }
        return rotated
    }

    private fun yuv420888ToNv21(image: Image): ByteArray {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val ySize = image.width * image.height
        val uvSize = image.width * image.height / 4
        val nv21 = ByteArray(ySize + uvSize * 2)

        copyPlane(
            buffer = yPlane.buffer,
            rowStride = yPlane.rowStride,
            pixelStride = yPlane.pixelStride,
            width = image.width,
            height = image.height,
            output = nv21,
            outputOffset = 0,
            outputStride = 1,
        )
        copyPlane(
            buffer = vPlane.buffer,
            rowStride = vPlane.rowStride,
            pixelStride = vPlane.pixelStride,
            width = image.width / 2,
            height = image.height / 2,
            output = nv21,
            outputOffset = ySize,
            outputStride = 2,
        )
        copyPlane(
            buffer = uPlane.buffer,
            rowStride = uPlane.rowStride,
            pixelStride = uPlane.pixelStride,
            width = image.width / 2,
            height = image.height / 2,
            output = nv21,
            outputOffset = ySize + 1,
            outputStride = 2,
        )

        return nv21
    }

    private fun copyPlane(
        buffer: java.nio.ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputStride: Int,
    ) {
        buffer.rewind()
        val rowBuffer = ByteArray(rowStride)
        var outputIndex = outputOffset

        for (row in 0 until height) {
            val length = if (pixelStride == 1 && outputStride == 1) {
                width
            } else {
                (width - 1) * pixelStride + 1
            }

            buffer.get(rowBuffer, 0, length)

            var inputIndex = 0
            for (col in 0 until width) {
                output[outputIndex] = rowBuffer[inputIndex]
                outputIndex += outputStride
                inputIndex += pixelStride
            }

            if (row < height - 1) {
                buffer.position(buffer.position() + rowStride - length)
            }
        }
    }


    private fun computePitchDownDegrees(frame: Frame): Float {
        val zAxis = frame.camera.displayOrientedPose.zAxis
        val forwardX = -zAxis[0]
        val forwardY = -zAxis[1]
        val forwardZ = -zAxis[2]
        val horizontal = sqrt((forwardX * forwardX) + (forwardZ * forwardZ))
        return Math.toDegrees(atan2(forwardY.toDouble(), horizontal.toDouble())).toFloat().coerceIn(0f, 85f)
    }

    private fun computeMotionSpeed(frame: Frame): Float? {
        val timestampNs = frame.timestamp
        val cameraPose = frame.camera.displayOrientedPose
        val previousTimestampNs = lastTimestampNs
        val previousX = lastCameraX
        val previousY = lastCameraY
        val previousZ = lastCameraZ

        lastTimestampNs = timestampNs
        lastCameraX = cameraPose.tx()
        lastCameraY = cameraPose.ty()
        lastCameraZ = cameraPose.tz()

        if (previousTimestampNs == null || previousX == null || previousY == null || previousZ == null) {
            return null
        }

        val dtSeconds = (timestampNs - previousTimestampNs) / 1_000_000_000f
        if (dtSeconds <= 0f) return null

        val movement = distanceMeters(
            previousX,
            previousY,
            previousZ,
            cameraPose.tx(),
            cameraPose.ty(),
            cameraPose.tz(),
        )
        return movement / dtSeconds
    }

    private fun computeApproachSpeed(collisionDistance: Float?): Float? {
        if (collisionDistance == null) {
            collisionHistory.clear()
            return null
        }
        collisionHistory.addLast(collisionDistance)
        while (collisionHistory.size > 8) {
            collisionHistory.removeFirst()
        }
        if (collisionHistory.size < 3) return null

        val oldest = collisionHistory.first()
        val newest = collisionHistory.last()
        val samples = collisionHistory.size - 1
        return ((oldest - newest) / samples).coerceAtLeast(0f) * 10f
    }

    private fun computeTtcRisk(
        collisionDistance: Float?,
        approachSpeed: Float?,
        motionSpeed: Float?,
    ): TtcRiskResult {
        if (collisionDistance == null) return TtcRiskResult("searching", null)

        val relativeClosingSpeed = maxOf(approachSpeed ?: 0f, motionSpeed ?: 0f)
        val ttcSeconds = if (relativeClosingSpeed > 0.05f) {
            (collisionDistance / relativeClosingSpeed).takeIf { it.isFinite() && it in 0.1f..12f }
        } else {
            null
        }

        val label = when {
            collisionDistance < 0.55f -> "critical"
            ttcSeconds != null && ttcSeconds <= 1.5f -> "critical"
            collisionDistance < 0.9f -> "high"
            ttcSeconds != null && ttcSeconds <= 3.0f -> "high"
            ttcSeconds != null && ttcSeconds <= 5.0f -> "watch"
            collisionDistance < 1.6f -> "watch"
            else -> "stable"
        }

        return TtcRiskResult(label, ttcSeconds)
    }

    private fun computeSuggestedDirection(
        leftDistance: Float?,
        centerDistance: Float?,
        rightDistance: Float?,
        leftLaneScore: Float? = null,
        centerLaneScore: Float? = null,
        rightLaneScore: Float? = null,
    ): String {
        val options = listOf(
            Triple("left", leftDistance, leftLaneScore),
            Triple("center", centerDistance, centerLaneScore),
            Triple("right", rightDistance, rightLaneScore),
        ).filter { it.second != null || it.third != null }

        if (options.isEmpty()) return "searching"

        val best = options.maxByOrNull {
            it.third ?: ((it.second ?: 0f).coerceIn(0f, 5f) / 5f)
        } ?: return "searching"
        val bestDistance = best.second
        val bestScore = best.third
        val center = centerDistance

        return when {
            center != null &&
                center >= 1.4f &&
                centerLaneScore != null &&
                bestScore != null &&
                centerLaneScore >= (bestScore - 0.05f) -> "center"
            bestDistance != null && bestDistance < 0.9f -> "blocked"
            else -> best.first
        }
    }

    private fun stabilizeDirection(
        instantDirection: String,
        riskLabel: String,
    ): String {
        directionHistory.addLast(instantDirection)
        while (directionHistory.size > 7) {
            directionHistory.removeFirst()
        }
        val scores = linkedMapOf(
            "left" to 0f,
            "center" to 0f,
            "right" to 0f,
            "blocked" to 0f,
            "searching" to 0f,
        )
        directionHistory.forEachIndexed { index, direction ->
            val weight = (index + 1).toFloat()
            scores[direction] = (scores[direction] ?: 0f) + weight
        }
        val candidate = scores.maxByOrNull { it.value }?.key ?: instantDirection
        if (stableDirection == "searching") {
            stableDirection = candidate
            return stableDirection
        }

        val currentScore = scores[stableDirection] ?: 0f
        val candidateScore = scores[candidate] ?: 0f
        val hysteresis = if (riskLabel == "critical" || riskLabel == "high") 1.05f else 1.2f

        if (candidate != stableDirection && candidateScore > currentScore * hysteresis) {
            stableDirection = candidate
        }
        return stableDirection
    }

    private fun computeGuidanceLabel(
        suggestedDirection: String,
        collisionDistance: Float?,
        riskLabel: String,
    ): String {
        if (collisionDistance == null) {
            return "주변을 인식하는 중입니다."
        }
        if (riskLabel == "critical") {
            return "전방 장애물이 매우 가깝습니다."
        }
        if (riskLabel == "high") {
            return "전방 장애물을 주의하세요."
        }
        return when (suggestedDirection) {
            "blocked" -> "전방 공간을 다시 확인해 주세요."
            else -> "전방을 확인해 주세요."
        }
    }

    private fun computeGuidanceLabelForScene(
        suggestedDirection: String,
        collisionDistance: Float?,
        riskLabel: String,
        nearestPerson: ObjectOverlayDetection?,
    ): String {
        val baseLabel = computeGuidanceLabel(
            suggestedDirection = suggestedDirection,
            collisionDistance = collisionDistance,
            riskLabel = riskLabel,
        )
        val personDistance = nearestPerson?.distanceMeters ?: return baseLabel
        if (personDistance > 3.5f) return baseLabel
        return "전방에 사람이 감지되었습니다."
    }

    private fun buildVoxelOverlayPoints(
        voxelPoints: List<VoxelPointUi>,
    ): List<VoxelOverlayPointUi> {
        val sceneCamera = arSceneView.scene.camera
        val viewWidth = arSceneView.width.toFloat().coerceAtLeast(1f)
        val viewHeight = arSceneView.height.toFloat().coerceAtLeast(1f)

        return voxelPoints
            .asSequence()
            .filter { it.relativeZ in 0.2f..5f }
            .sortedWith(
                compareByDescending<VoxelPointUi> { it.confidenceScore }
                    .thenBy { it.relativeZ },
            )
            .take(260)
            .mapNotNull { point ->
                val screenPoint = sceneCamera.worldToScreenPoint(
                    Vector3(point.worldX, point.worldY, point.worldZ),
                )
                val xRatio = screenPoint.x / viewWidth
                val yRatio = screenPoint.y / viewHeight
                if (!xRatio.isFinite() || !yRatio.isFinite()) return@mapNotNull null
                if (xRatio !in -0.1f..1.1f || yRatio !in -0.1f..1.1f) return@mapNotNull null

                VoxelOverlayPointUi(
                    xRatio = xRatio.coerceIn(0f, 1f),
                    yRatio = yRatio.coerceIn(0f, 1f),
                    occupancyScore = point.occupancyScore,
                    confidenceScore = point.confidenceScore,
                )
            }
            .toList()
    }

    private fun distanceMeters(
        ax: Float,
        ay: Float,
        az: Float,
        bx: Float,
        by: Float,
        bz: Float,
    ): Float {
        val dx = ax - bx
        val dy = ay - by
        val dz = az - bz
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
