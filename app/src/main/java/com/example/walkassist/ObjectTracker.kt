package com.example.walkassist

import android.graphics.RectF
import kotlin.math.abs

private data class TrackMemory(
    val ageFrames: Int,
    val boundingBox: RectF,
    val closingSpeedMetersPerSecond: Float?,
    val consecutiveHits: Int,
    val distanceMeters: Float?,
    val id: Int,
    val label: String,
    val lastTimestampNanos: Long?,
    val missedFrames: Int,
    val timeToCollisionSeconds: Float?,
)

class ObjectTracker {
    private val tracks = mutableMapOf<Int, TrackMemory>()
    private var nextTrackId = 1

    fun update(detections: List<DetectedObjectResult>, timestampNanos: Long): List<DetectedObjectResult> {
        val updatedTracks = mutableMapOf<Int, TrackMemory>()
        val unassignedTrackIds = tracks.keys.toMutableSet()
        val results = mutableListOf<DetectedObjectResult>()

        for (detection in detections) {
            val matchedTrackId = findBestTrack(detection, unassignedTrackIds)
            val previousTrack = matchedTrackId?.let { tracks[it] }
            val trackId = matchedTrackId ?: nextTrackId++

            if (matchedTrackId != null) {
                unassignedTrackIds.remove(matchedTrackId)
            }

            val smoothedDistance = smoothDistance(
                previous = previousTrack?.distanceMeters,
                current = detection.distanceEstimate.distanceMeters,
            )
            val motion = estimateMotion(
                previousDistance = previousTrack?.distanceMeters,
                currentDistance = smoothedDistance,
                previousTimestampNanos = previousTrack?.lastTimestampNanos,
                timestampNanos = timestampNanos,
            )
            val ageFrames = (previousTrack?.ageFrames ?: 0) + 1
            val consecutiveHits = (previousTrack?.consecutiveHits ?: 0) + 1
            val memory = TrackMemory(
                ageFrames = ageFrames,
                boundingBox = RectF(detection.boundingBox),
                closingSpeedMetersPerSecond = motion.first,
                consecutiveHits = consecutiveHits,
                distanceMeters = smoothedDistance,
                id = trackId,
                label = detection.label,
                lastTimestampNanos = timestampNanos,
                missedFrames = 0,
                timeToCollisionSeconds = motion.second,
            )
            updatedTracks[trackId] = memory
            results += detection.copy(
                trackingState = TrackingState(
                    trackId = trackId,
                    ageFrames = ageFrames,
                    consecutiveHits = consecutiveHits,
                    isStable = consecutiveHits >= 4,
                    smoothedDistanceMeters = smoothedDistance,
                    closingSpeedMetersPerSecond = motion.first,
                    timeToCollisionSeconds = motion.second,
                ),
            )
        }

        for (trackId in unassignedTrackIds) {
            val track = tracks[trackId] ?: continue
            if (track.missedFrames < 5) {
                updatedTracks[trackId] = track.copy(
                    ageFrames = track.ageFrames + 1,
                    consecutiveHits = 0,
                    missedFrames = track.missedFrames + 1,
                )
            }
        }

        tracks.clear()
        tracks.putAll(updatedTracks)

        return results.sortedWith(
            compareBy<DetectedObjectResult> {
                when (it.distanceEstimate.riskLevel) {
                    RiskLevel.DANGER -> 0
                    RiskLevel.WARNING -> 1
                    RiskLevel.SAFE -> 2
                }
            }.thenBy { it.trackingState?.smoothedDistanceMeters ?: it.distanceEstimate.distanceMeters ?: Float.MAX_VALUE }
             .thenByDescending { it.confidence }
        )
    }

    private fun findBestTrack(
        detection: DetectedObjectResult,
        candidateTrackIds: Set<Int>,
    ): Int? {
        var bestTrackId: Int? = null
        var bestScore = 0f

        for (trackId in candidateTrackIds) {
            val track = tracks[trackId] ?: continue

            val iou = calculateIoU(track.boundingBox, detection.boundingBox)
            val centerPenalty = centerDistancePenalty(track.boundingBox, detection.boundingBox)
            val score = iou - centerPenalty

            if (score > bestScore && score > 0.15f) {
                bestScore = score
                bestTrackId = trackId
            }
        }

        return bestTrackId
    }

    private fun smoothDistance(previous: Float?, current: Float?): Float? {
        if (current == null) return previous
        if (previous == null) return current
        return (previous * 0.7f) + (current * 0.3f)
    }

    private fun estimateMotion(
        previousDistance: Float?,
        currentDistance: Float?,
        previousTimestampNanos: Long?,
        timestampNanos: Long,
    ): Pair<Float?, Float?> {
        if (previousDistance == null || currentDistance == null || previousTimestampNanos == null) {
            return null to null
        }
        val dtSeconds = (timestampNanos - previousTimestampNanos) / 1_000_000_000f
        if (dtSeconds <= 0.05f) return null to null
        val closingSpeed = (previousDistance - currentDistance) / dtSeconds
        if (!closingSpeed.isFinite() || closingSpeed <= 0.05f) return 0f to null
        val ttc = currentDistance / closingSpeed
        return closingSpeed to if (ttc.isFinite() && ttc in 0.1f..12f) ttc else null
    }

    private fun centerDistancePenalty(a: RectF, b: RectF): Float {
        val aCx = (a.left + a.right) * 0.5f
        val aCy = (a.top + a.bottom) * 0.5f
        val bCx = (b.left + b.right) * 0.5f
        val bCy = (b.top + b.bottom) * 0.5f
        val dx = abs(aCx - bCx)
        val dy = abs(aCy - bCy)
        val normalizer = maxOf(a.width(), a.height(), b.width(), b.height(), 1f)
        return ((dx + dy) / normalizer) * 0.08f
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)

        if (right <= left || bottom <= top) return 0f

        val intersection = (right - left) * (bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
