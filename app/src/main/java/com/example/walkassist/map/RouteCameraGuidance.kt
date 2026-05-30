package com.example.walkassist.map

import com.naver.maps.geometry.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class RouteCameraGuidance(
    val action: RouteCameraAction,
    val message: String,
    val distanceToPathMeters: Double,
    val distanceToNextGuideMeters: Double?,
    val routeBearingDegrees: Double?,
    val headingDeltaDegrees: Double?,
)

enum class RouteCameraAction {
    STRAIGHT,
    LEFT,
    RIGHT,
    TURN_AROUND,
    REJOIN_ROUTE,
    ARRIVED,
    WAITING,
}

class RouteCameraGuidanceEngine(
    private val lookAheadMeters: Double = 14.0,
    private val offRouteMeters: Double = 35.0,
    private val arrivalMeters: Double = 8.0,
) {
    fun compute(
        route: List<LatLng>,
        currentLocation: LatLng,
        cameraHeadingDegrees: Double?,
        nextGuidePoint: LatLng?,
    ): RouteCameraGuidance {
        if (route.size < 2) {
            return RouteCameraGuidance(
                action = RouteCameraAction.WAITING,
                message = "경로 정보를 기다리는 중입니다.",
                distanceToPathMeters = 0.0,
                distanceToNextGuideMeters = null,
                routeBearingDegrees = null,
                headingDeltaDegrees = null,
            )
        }

        val distanceToDestination = currentLocation.distanceTo(route.last())
        if (distanceToDestination <= arrivalMeters) {
            return RouteCameraGuidance(
                action = RouteCameraAction.ARRIVED,
                message = "목적지 근처에 도착했습니다.",
                distanceToPathMeters = 0.0,
                distanceToNextGuideMeters = 0.0,
                routeBearingDegrees = null,
                headingDeltaDegrees = null,
            )
        }

        val nearest = findNearestSegment(route, currentLocation)
        if (nearest.distanceMeters > offRouteMeters) {
            return RouteCameraGuidance(
                action = RouteCameraAction.REJOIN_ROUTE,
                message = "경로에서 벗어났습니다. 지도 경로 쪽으로 돌아가세요.",
                distanceToPathMeters = nearest.distanceMeters,
                distanceToNextGuideMeters = nextGuidePoint?.let { currentLocation.distanceTo(it) },
                routeBearingDegrees = null,
                headingDeltaDegrees = null,
            )
        }

        val target = lookAheadPoint(
            route = route,
            segmentIndex = nearest.segmentIndex,
            segmentProgress = nearest.segmentProgress,
            lookAheadMeters = lookAheadMeters,
        ) ?: route.last()
        val routeBearing = bearingDegrees(currentLocation, target)
        val delta = cameraHeadingDegrees?.let { normalizeDeltaDegrees(routeBearing - it) }
        val action = actionForDelta(delta)
        val distanceToNextGuide = nextGuidePoint?.let { currentLocation.distanceTo(it) }
        val message = buildMessage(action, distanceToNextGuide, delta)

        return RouteCameraGuidance(
            action = action,
            message = message,
            distanceToPathMeters = nearest.distanceMeters,
            distanceToNextGuideMeters = distanceToNextGuide,
            routeBearingDegrees = routeBearing,
            headingDeltaDegrees = delta,
        )
    }

    private fun actionForDelta(delta: Double?): RouteCameraAction {
        if (delta == null) return RouteCameraAction.WAITING
        return when {
            delta in -20.0..20.0 -> RouteCameraAction.STRAIGHT
            delta > 120.0 || delta < -120.0 -> RouteCameraAction.TURN_AROUND
            delta > 0.0 -> RouteCameraAction.RIGHT
            else -> RouteCameraAction.LEFT
        }
    }

    private fun buildMessage(
        action: RouteCameraAction,
        distanceToNextGuideMeters: Double?,
        delta: Double?,
    ): String {
        val turnPart = when (action) {
            RouteCameraAction.STRAIGHT -> "현재 바라보는 방향으로 직진하세요."
            RouteCameraAction.LEFT -> "왼쪽으로 방향을 맞추세요."
            RouteCameraAction.RIGHT -> "오른쪽으로 방향을 맞추세요."
            RouteCameraAction.TURN_AROUND -> "반대 방향입니다. 뒤쪽으로 돌아서세요."
            RouteCameraAction.REJOIN_ROUTE -> "경로 쪽으로 복귀하세요."
            RouteCameraAction.ARRIVED -> "목적지 근처에 도착했습니다."
            RouteCameraAction.WAITING -> "카메라 방향을 확인하는 중입니다."
        }
        val deltaPart = delta?.let { " ${kotlin.math.abs(it).toInt()}도 차이입니다." }.orEmpty()
        val guidePart = distanceToNextGuideMeters
            ?.takeIf { it.isFinite() }
            ?.let { " 다음 안내 지점까지 ${it.toInt()}m 남았습니다." }
            .orEmpty()
        return turnPart + deltaPart + guidePart
    }

    private fun findNearestSegment(
        route: List<LatLng>,
        point: LatLng,
    ): NearestSegment {
        var best = NearestSegment(
            segmentIndex = 0,
            segmentProgress = 0.0,
            distanceMeters = Double.MAX_VALUE,
        )

        for (index in 0 until route.lastIndex) {
            val start = route[index]
            val end = route[index + 1]
            val projection = projectOntoSegment(start, end, point)
            if (projection.distanceMeters < best.distanceMeters) {
                best = NearestSegment(
                    segmentIndex = index,
                    segmentProgress = projection.progress,
                    distanceMeters = projection.distanceMeters,
                )
            }
        }
        return best
    }

    private fun lookAheadPoint(
        route: List<LatLng>,
        segmentIndex: Int,
        segmentProgress: Double,
        lookAheadMeters: Double,
    ): LatLng? {
        var remaining = lookAheadMeters
        var index = segmentIndex
        var progress = segmentProgress.coerceIn(0.0, 1.0)

        while (index < route.lastIndex) {
            val start = route[index]
            val end = route[index + 1]
            val segmentLength = start.distanceTo(end)
            val available = segmentLength * (1.0 - progress)
            if (remaining <= available && segmentLength > 0.0) {
                val nextProgress = progress + (remaining / segmentLength)
                return interpolate(start, end, nextProgress)
            }
            remaining -= available
            index += 1
            progress = 0.0
        }
        return route.lastOrNull()
    }

    private fun interpolate(start: LatLng, end: LatLng, progress: Double): LatLng {
        val clamped = progress.coerceIn(0.0, 1.0)
        return LatLng(
            start.latitude + ((end.latitude - start.latitude) * clamped),
            start.longitude + ((end.longitude - start.longitude) * clamped),
        )
    }

    private fun projectOntoSegment(
        start: LatLng,
        end: LatLng,
        point: LatLng,
    ): SegmentProjection {
        val originLatRadians = Math.toRadians(point.latitude)
        val startX = metersEast(point.longitude, start.longitude, originLatRadians)
        val startY = metersNorth(point.latitude, start.latitude)
        val endX = metersEast(point.longitude, end.longitude, originLatRadians)
        val endY = metersNorth(point.latitude, end.latitude)
        val dx = endX - startX
        val dy = endY - startY
        val segmentLengthSquared = (dx * dx) + (dy * dy)
        if (segmentLengthSquared <= 0.0001) {
            return SegmentProjection(progress = 0.0, distanceMeters = sqrt((startX * startX) + (startY * startY)))
        }

        val progress = -((startX * dx) + (startY * dy)) / segmentLengthSquared
        val clamped = progress.coerceIn(0.0, 1.0)
        val projectedX = startX + (dx * clamped)
        val projectedY = startY + (dy * clamped)
        return SegmentProjection(
            progress = clamped,
            distanceMeters = sqrt((projectedX * projectedX) + (projectedY * projectedY)),
        )
    }

    private fun bearingDegrees(from: LatLng, to: LatLng): Double {
        val fromLat = Math.toRadians(from.latitude)
        val toLat = Math.toRadians(to.latitude)
        val deltaLon = Math.toRadians(to.longitude - from.longitude)
        val y = sin(deltaLon) * cos(toLat)
        val x = (cos(fromLat) * sin(toLat)) - (sin(fromLat) * cos(toLat) * cos(deltaLon))
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    private fun normalizeDegrees(value: Double): Double {
        var normalized = value % 360.0
        if (normalized < 0.0) normalized += 360.0
        return normalized
    }

    private fun normalizeDeltaDegrees(value: Double): Double {
        var normalized = ((value + 540.0) % 360.0) - 180.0
        if (normalized == -180.0) normalized = 180.0
        return normalized
    }

    private fun metersNorth(originLatitude: Double, latitude: Double): Double {
        return (latitude - originLatitude) * METERS_PER_DEGREE_LATITUDE
    }

    private fun metersEast(originLongitude: Double, longitude: Double, originLatRadians: Double): Double {
        return (longitude - originLongitude) * METERS_PER_DEGREE_LATITUDE * cos(originLatRadians)
    }

    private data class NearestSegment(
        val segmentIndex: Int,
        val segmentProgress: Double,
        val distanceMeters: Double,
    )

    private data class SegmentProjection(
        val progress: Double,
        val distanceMeters: Double,
    )

    companion object {
        private const val METERS_PER_DEGREE_LATITUDE = 111_320.0
    }
}
