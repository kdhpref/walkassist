package com.example.walkassist

class FreeSpaceTracker {
    private var previousBoundary: IntArray? = null
    private var previousTimestampNanos: Long? = null
    private var previousCollisionDistanceMeters: Float? = null
    private var previousPathClearMeters: Float? = null

    fun update(
        floorSegmentation: FloorSegmentationResult,
        pathMetrics: PathMetrics,
        timestampNanos: Long,
    ): Pair<FloorSegmentationResult, PathMetrics> {
        val smoothedSegmentation = smoothSegmentation(floorSegmentation)
        val ttc = estimateTtc(pathMetrics.collisionDistanceMeters, timestampNanos)
        val smoothedPathClear = smoothScalar(previousPathClearMeters, pathMetrics.pathClearMeters, 0.7f)
        val smoothedCollision = smoothScalar(previousCollisionDistanceMeters, pathMetrics.collisionDistanceMeters, 0.65f)

        previousPathClearMeters = smoothedPathClear
        previousCollisionDistanceMeters = smoothedCollision
        previousTimestampNanos = timestampNanos

        return smoothedSegmentation to pathMetrics.copy(
            pathClearMeters = smoothedPathClear,
            collisionDistanceMeters = smoothedCollision,
            timeToCollisionSeconds = ttc,
        )
    }

    private fun smoothSegmentation(segmentation: FloorSegmentationResult): FloorSegmentationResult {
        val previous = previousBoundary
        val current = segmentation.boundaryYByColumn
        if (previous == null || previous.size != current.size) {
            previousBoundary = current.copyOf()
            return segmentation
        }

        val smoothed = IntArray(current.size)
        for (index in current.indices) {
            val currentValue = current[index]
            val previousValue = previous[index]
            smoothed[index] = when {
                currentValue < 0 -> previousValue
                previousValue < 0 -> currentValue
                else -> ((previousValue * 0.72f) + (currentValue * 0.28f)).toInt()
            }
        }
        previousBoundary = smoothed.copyOf()
        return segmentation.copy(boundaryYByColumn = smoothed)
    }

    private fun estimateTtc(currentDistanceMeters: Float?, timestampNanos: Long): Float? {
        val previousDistance = previousCollisionDistanceMeters ?: return null
        val previousTimestamp = previousTimestampNanos ?: return null
        val currentDistance = currentDistanceMeters ?: return null
        val dtSeconds = (timestampNanos - previousTimestamp) / 1_000_000_000f
        if (dtSeconds <= 0.08f) return null
        val closingSpeed = (previousDistance - currentDistance) / dtSeconds
        if (!closingSpeed.isFinite() || closingSpeed <= 0.05f) return null
        val ttc = currentDistance / closingSpeed
        return if (ttc.isFinite() && ttc in 0.1f..12f) ttc else null
    }

    private fun smoothScalar(previous: Float?, current: Float?, previousWeight: Float): Float? {
        if (current == null) return previous
        if (previous == null) return current
        return previous * previousWeight + current * (1f - previousWeight)
    }
}
