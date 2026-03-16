package com.example.walkassist

import android.graphics.RectF
import kotlin.math.abs

data class RawDetection(
    val boundingBox: RectF,
    val confidence: Float,
    val imageHeight: Int,
    val imageWidth: Int,
    val label: String,
)

class DistanceEstimator(
    private val cameraHeightMeters: Float = 1.45f,
    private val verticalFovDegrees: Float = 60f,
) {
    private val groundProjector = GroundProjector(
        cameraHeightMeters = cameraHeightMeters,
        verticalFovDegrees = verticalFovDegrees,
    )
    private val supportedGroundLabels = setOf(
        "obstacle",
        "person",
        "bicycle",
        "car",
        "motorcycle",
        "bus",
        "truck",
        "chair",
        "bench",
        "dog",
        "cat",
        "stop sign",
    )

    private val nominalObjectHeightsMeters = mapOf(
        "person" to 1.70f,
        "bicycle" to 1.10f,
        "car" to 1.50f,
        "motorcycle" to 1.20f,
        "bus" to 3.10f,
        "truck" to 3.20f,
        "chair" to 0.95f,
        "bench" to 0.85f,
        "dog" to 0.60f,
        "cat" to 0.35f,
        "stop sign" to 0.75f,
    )

    fun estimate(
        detection: RawDetection,
        pitchRadians: Float,
        floorSegmentation: FloorSegmentationResult?,
    ): DetectionDistanceEstimate {
        val floorContactY = estimateFloorContactY(detection, floorSegmentation)
        val floorDistance = estimateGroundDistance(
            detection = detection,
            pitchRadians = pitchRadians,
            contactY = floorContactY ?: detection.boundingBox.bottom,
        )
        val sizePriorDistance = estimateDistanceFromObjectSize(detection)
        val fusedDistance = fuseDistances(
            floorDistance = if (floorContactY != null) floorDistance else null,
            geometryFallbackDistance = floorDistance,
            sizePriorDistance = sizePriorDistance,
            floorConfidence = floorSegmentation?.confidence ?: 0f,
        )

        return DetectionDistanceEstimate(
            distanceMeters = fusedDistance.distanceMeters,
            rawGeometryDistanceMeters = floorDistance,
            qualityScore = estimateQuality(
                detection = detection,
                floorSegmentation = floorSegmentation,
                source = fusedDistance.source,
            ),
            source = fusedDistance.source,
            riskLevel = classifyRisk(fusedDistance.distanceMeters),
        )
    }

    private fun estimateFloorContactY(
        detection: RawDetection,
        floorSegmentation: FloorSegmentationResult?,
    ): Float? {
        val segmentation = floorSegmentation ?: return null
        if (segmentation.confidence < 0.2f) return null

        val left = detection.boundingBox.left + detection.boundingBox.width() * 0.25f
        val right = detection.boundingBox.right - detection.boundingBox.width() * 0.25f
        val candidates = ArrayList<Float>(5)

        for (index in 0 until 5) {
            val ratio = index / 4f
            val sampleX = left + (right - left) * ratio
            val boundaryY = segmentation.boundaryYAt(
                imageX = sampleX,
                imageWidth = detection.imageWidth,
                imageHeight = detection.imageHeight,
            ) ?: continue
            if (boundaryY >= detection.boundingBox.top && boundaryY <= detection.imageHeight) {
                candidates += boundaryY
            }
        }

        if (candidates.isEmpty()) return null
        candidates.sort()
        return candidates[candidates.size / 2]
    }

    private fun estimateGroundDistance(
        detection: RawDetection,
        pitchRadians: Float,
        contactY: Float,
    ): Float? {
        if (detection.label !in supportedGroundLabels) {
            return null
        }

        return groundProjector.distanceFromImageY(
            imageY = contactY.coerceIn(0f, detection.imageHeight.toFloat()),
            imageHeight = detection.imageHeight,
            pitchRadians = pitchRadians,
        )
    }

    private fun estimateDistanceFromObjectSize(detection: RawDetection): Float? {
        val nominalHeightMeters = nominalObjectHeightsMeters[detection.label] ?: return null
        val boxHeightPixels = detection.boundingBox.height()
        if (boxHeightPixels < 18f) return null

        val focalLengthPixels = groundProjector.focalLengthPixels(detection.imageHeight)
        val distance = (nominalHeightMeters * focalLengthPixels) / boxHeightPixels
        if (!distance.isFinite() || distance <= 0.15f || distance > 30f) return null
        return distance
    }

    private fun fuseDistances(
        floorDistance: Float?,
        geometryFallbackDistance: Float?,
        sizePriorDistance: Float?,
        floorConfidence: Float,
    ): FusedDistance {
        if (floorDistance != null && sizePriorDistance != null) {
            val relativeGap = abs(floorDistance - sizePriorDistance) / maxOf(floorDistance, sizePriorDistance, 0.1f)
            return if (relativeGap < 0.35f) {
                val floorWeight = (0.55f + floorConfidence * 0.25f).coerceIn(0.4f, 0.8f)
                val sizeWeight = 1f - floorWeight
                FusedDistance(
                    distanceMeters = floorDistance * floorWeight + sizePriorDistance * sizeWeight,
                    source = DistanceSource.HYBRID_GEOMETRY_SIZE,
                )
            } else if (floorConfidence >= 0.45f) {
                FusedDistance(floorDistance, DistanceSource.FLOOR_SEGMENTATION)
            } else {
                FusedDistance(sizePriorDistance, DistanceSource.SIZE_PRIOR)
            }
        }

        if (floorDistance != null) {
            return FusedDistance(floorDistance, DistanceSource.FLOOR_SEGMENTATION)
        }
        if (geometryFallbackDistance != null) {
            return FusedDistance(geometryFallbackDistance, DistanceSource.GROUND_GEOMETRY)
        }
        if (sizePriorDistance != null) {
            return FusedDistance(sizePriorDistance, DistanceSource.SIZE_PRIOR)
        }
        return FusedDistance(null, DistanceSource.UNKNOWN)
    }

    private fun classifyRisk(distanceMeters: Float?): RiskLevel {
        if (distanceMeters == null) {
            return RiskLevel.SAFE
        }

        return when {
            distanceMeters < 1.5f -> RiskLevel.DANGER
            distanceMeters < 3.0f -> RiskLevel.WARNING
            else -> RiskLevel.SAFE
        }
    }

    private fun estimateQuality(
        detection: RawDetection,
        floorSegmentation: FloorSegmentationResult?,
        source: DistanceSource,
    ): Float {
        val boxAreaRatio =
            (detection.boundingBox.width() * detection.boundingBox.height()) /
                (detection.imageWidth.toFloat() * detection.imageHeight.toFloat())
        val boxScore = (boxAreaRatio / 0.18f).coerceIn(0f, 1f)
        val floorScore = floorSegmentation?.confidence ?: 0f
        val sourceScore = when (source) {
            DistanceSource.HYBRID_GEOMETRY_SIZE -> 1f
            DistanceSource.FLOOR_SEGMENTATION -> 0.85f
            DistanceSource.SIZE_PRIOR -> 0.75f
            DistanceSource.GROUND_GEOMETRY -> 0.65f
            DistanceSource.UNKNOWN -> 0.1f
        }

        return (boxScore * 0.35f + floorScore * 0.25f + detection.confidence * 0.2f + sourceScore * 0.2f)
            .coerceIn(0f, 1f)
    }

    private data class FusedDistance(
        val distanceMeters: Float?,
        val source: DistanceSource,
    )
}
