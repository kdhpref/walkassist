package com.example.walkassist

import android.os.Bundle
import android.view.View
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Session
import com.google.ar.core.TrackingState
import com.google.ar.sceneform.ux.ArFragment
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

class WalkAssistArFragment : ArFragment() {
    private data class LaneDistances(
        val floor: Float?,
        val wall: Float?,
        val depth: Float?,
        val collision: Float?,
    )

    private data class CorridorHit(
        val distanceMeters: Float,
        val lateralMeters: Float,
        val forwardMeters: Float,
        val source: HitSource,
    )

    private enum class HitSource {
        FLOOR,
        WALL,
        DEPTH,
    }

    private var smoothedFloorDistance: Float? = null
    private var smoothedWallDistance: Float? = null
    private var smoothedDepthDistance: Float? = null
    private var smoothedCollisionDistance: Float? = null
    private var smoothedLeftDistance: Float? = null
    private var smoothedCenterDistance: Float? = null
    private var smoothedRightDistance: Float? = null

    private var lastTimestampNs: Long? = null
    private var lastCameraX: Float? = null
    private var lastCameraY: Float? = null
    private var lastCameraZ: Float? = null

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

    private fun publishFrameState() {
        val frame = arSceneView.arFrame ?: return
        val camera = frame.camera
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
                    note = "ARCore needs visible feature points and steady motion.",
                ),
            )
            return
        }

        val pitchDownDegrees = computePitchDownDegrees(frame)
        val corridorHits = sampleWorldCorridor(frame)

        val leftLane = corridorLaneDistances(corridorHits, "left")
        val centerLane = corridorLaneDistances(corridorHits, "center")
        val rightLane = corridorLaneDistances(corridorHits, "right")

        val floorDistance = listOfNotNull(leftLane.floor, centerLane.floor, rightLane.floor).minOrNull()
        val wallDistance = listOfNotNull(leftLane.wall, centerLane.wall, rightLane.wall).minOrNull()
        val depthDistance = listOfNotNull(leftLane.depth, centerLane.depth, rightLane.depth).minOrNull()
        val rawCollisionDistance = listOfNotNull(
            leftLane.collision,
            centerLane.collision,
            rightLane.collision,
        ).minOrNull()

        val smoothedFloor = smoothDistance(smoothedFloorDistance, floorDistance).also { smoothedFloorDistance = it }
        val smoothedWall = smoothDistance(smoothedWallDistance, wallDistance).also { smoothedWallDistance = it }
        val smoothedDepth = smoothDistance(smoothedDepthDistance, depthDistance).also { smoothedDepthDistance = it }
        val collisionDistance = smoothDistance(smoothedCollisionDistance, rawCollisionDistance).also {
            smoothedCollisionDistance = it
        }
        val leftDistance = smoothDistance(smoothedLeftDistance, leftLane.collision).also { smoothedLeftDistance = it }
        val centerDistance = smoothDistance(smoothedCenterDistance, centerLane.collision).also {
            smoothedCenterDistance = it
        }
        val rightDistance = smoothDistance(smoothedRightDistance, rightLane.collision).also {
            smoothedRightDistance = it
        }

        val motionSpeed = computeMotionSpeed(frame)
        val approachSpeed = computeApproachSpeed(collisionDistance)
        val riskLabel = computeRiskLabel(collisionDistance, approachSpeed, motionSpeed)
        val instantDirection = computeSuggestedDirection(leftDistance, centerDistance, rightDistance)
        val suggestedDirection = stabilizeDirection(instantDirection, riskLabel)
        val guidanceLabel = computeGuidanceLabel(suggestedDirection, collisionDistance, riskLabel)

        val (statusLabel, level, note) = when {
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

        ArMeasurementBridge.publish(
            ArMeasurementState(
                trackingLabel = "tracking",
                horizontalPlaneCount = horizontalPlaneCount,
                verticalPlaneCount = verticalPlaneCount,
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
                collisionDistanceMeters = collisionDistance,
                approachSpeedMetersPerSecond = approachSpeed,
                motionMetersPerSecond = motionSpeed,
                riskLabel = riskLabel,
                guidanceLabel = guidanceLabel,
                statusLabel = statusLabel,
                statusLevel = level,
                note = note,
            ),
        )
    }

    private fun sampleWorldCorridor(frame: Frame): List<CorridorHit> {
        val width = arSceneView.width.toFloat().coerceAtLeast(1f)
        val height = arSceneView.height.toFloat().coerceAtLeast(1f)
        val sampleXs = listOf(0.14f, 0.24f, 0.34f, 0.44f, 0.5f, 0.56f, 0.66f, 0.76f, 0.86f)
        val sampleYs = listOf(0.44f, 0.56f, 0.68f, 0.8f)

        return buildList {
            for (yRatio in sampleYs) {
                for (xRatio in sampleXs) {
                    val hit = nearestWorldHit(frame, width * xRatio, height * yRatio)
                    if (hit != null) {
                        add(hit)
                    }
                }
            }
        }
    }

    private fun corridorLaneDistances(
        hits: List<CorridorHit>,
        lane: String,
    ): LaneDistances {
        val filtered = hits.filter { classifyLane(it.lateralMeters) == lane }
        val floor = filtered.filter { it.source == HitSource.FLOOR }.minOfOrNull { it.distanceMeters }
        val wall = filtered.filter { it.source == HitSource.WALL }.minOfOrNull { it.distanceMeters }
        val depth = filtered.filter { it.source == HitSource.DEPTH }.minOfOrNull { it.distanceMeters }
        return LaneDistances(
            floor = floor,
            wall = wall,
            depth = depth,
            collision = listOfNotNull(floor, wall, depth).minOrNull(),
        )
    }

    private fun classifyLane(lateralMeters: Float): String? {
        return when {
            lateralMeters < -0.28f && lateralMeters >= -1.05f -> "left"
            lateralMeters in -0.28f..0.28f -> "center"
            lateralMeters > 0.28f && lateralMeters <= 1.05f -> "right"
            else -> null
        }
    }

    private fun nearestWorldHit(
        frame: Frame,
        x: Float,
        y: Float,
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
                if (forward <= 0.15f || forward > 4.5f) {
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
                            source = source,
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
                        source = HitSource.DEPTH,
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
                            source = HitSource.DEPTH,
                        )
                    }
                    else -> null
                }
            }
            .filter { classifyLane(it.lateralMeters) != null }
            .minByOrNull { it.distanceMeters }
    }

    private fun smoothDistance(previous: Float?, current: Float?): Float? {
        if (current == null) return previous
        if (previous == null) return current
        val delta = abs(current - previous)
        val alpha = if (delta > 0.7f) 0.2f else 0.35f
        return previous + ((current - previous) * alpha)
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

    private fun computeRiskLabel(
        collisionDistance: Float?,
        approachSpeed: Float?,
        motionSpeed: Float?,
    ): String {
        if (collisionDistance == null) return "searching"
        if (collisionDistance < 0.8f) return "critical"
        if ((approachSpeed ?: 0f) > 0.55f && (motionSpeed ?: 0f) > 0.12f) return "critical"
        if (collisionDistance < 1.5f || (approachSpeed ?: 0f) > 0.25f) return "high"
        if ((motionSpeed ?: 0f) > 0.08f && collisionDistance < 2.2f) return "watch"
        return "stable"
    }

    private fun computeSuggestedDirection(
        leftDistance: Float?,
        centerDistance: Float?,
        rightDistance: Float?,
    ): String {
        val options = listOf(
            "left" to leftDistance,
            "center" to centerDistance,
            "right" to rightDistance,
        ).filter { it.second != null }

        if (options.isEmpty()) return "searching"

        val best = options.maxByOrNull { it.second ?: -1f } ?: return "searching"
        val bestDistance = best.second ?: return "searching"
        val center = centerDistance

        return when {
            center != null && center >= 1.4f && center >= bestDistance - 0.15f -> "center"
            bestDistance < 0.9f -> "blocked"
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
            return "Scanning surroundings."
        }
        if (riskLabel == "critical") {
            return when (suggestedDirection) {
                "left" -> "Obstacle very close. Move left."
                "right" -> "Obstacle very close. Move right."
                "center" -> "Obstacle very close. Slow down now."
                else -> "Collision risk is high. Stop."
            }
        }
        if (riskLabel == "high") {
            return when (suggestedDirection) {
                "left" -> "Left path looks safer."
                "right" -> "Right path looks safer."
                "center" -> "Center path is usable, but be careful."
                else -> "Obstacle nearby. Move carefully."
            }
        }
        return when (suggestedDirection) {
            "left" -> "More open space on the left."
            "right" -> "More open space on the right."
            "center" -> "Center path looks clear."
            "blocked" -> "Space ahead is narrow. Check your surroundings."
            else -> "Scanning surroundings."
        }
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
