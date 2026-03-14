package com.example.walkassist

import android.graphics.RectF
import kotlin.math.tan

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
    private val supportedGroundLabels = setOf(
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

    fun estimate(
        detection: RawDetection,
        pitchRadians: Float,
        floorSegmentation: FloorSegmentationResult?,
    ): DetectionDistanceEstimate {
        val floorContactY = estimateFloorContactY(detection, floorSegmentation)
        val rawGroundDistance = estimateGroundDistance(
            detection = detection,
            pitchRadians = pitchRadians,
            contactY = floorContactY ?: detection.boundingBox.bottom,
        )
        val source = when {
            rawGroundDistance != null && floorContactY != null -> DistanceSource.FLOOR_SEGMENTATION
            rawGroundDistance != null -> DistanceSource.GROUND_GEOMETRY
            else -> DistanceSource.UNKNOWN
        }

        return DetectionDistanceEstimate(
            distanceMeters = rawGroundDistance,
            rawGeometryDistanceMeters = rawGroundDistance,
            qualityScore = estimateQuality(
                detection = detection,
                floorSegmentation = floorSegmentation,
                usedFloorBoundary = floorContactY != null,
            ),
            source = source,
            riskLevel = classifyRisk(rawGroundDistance),
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

        val verticalFovRadians = Math.toRadians(verticalFovDegrees.toDouble()).toFloat()
        val imageCenterY = detection.imageHeight / 2f
        val bottomY = contactY.coerceIn(0f, detection.imageHeight.toFloat())
        val angleOffset = ((bottomY - imageCenterY) / detection.imageHeight.toFloat()) * verticalFovRadians
        val totalAngle = pitchRadians + angleOffset

        if (totalAngle <= 0.08f) {
            return null
        }

        val distance = cameraHeightMeters / tan(totalAngle)
        if (!distance.isFinite() || distance <= 0.15f || distance > 25f) {
            return null
        }

        return distance
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
        usedFloorBoundary: Boolean,
    ): Float {
        val boxAreaRatio =
            (detection.boundingBox.width() * detection.boundingBox.height()) /
                (detection.imageWidth.toFloat() * detection.imageHeight.toFloat())
        val boxScore = (boxAreaRatio / 0.18f).coerceIn(0f, 1f)
        val floorScore = floorSegmentation?.confidence ?: 0f
        val sourceBonus = if (usedFloorBoundary) 0.2f else 0f
        return (boxScore * 0.45f + floorScore * 0.35f + detection.confidence * 0.2f + sourceBonus)
            .coerceIn(0f, 1f)
    }
}
