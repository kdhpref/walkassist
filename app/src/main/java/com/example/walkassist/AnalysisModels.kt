package com.example.walkassist

import android.graphics.RectF

enum class DistanceSource {
    FLOOR_SEGMENTATION,
    GROUND_GEOMETRY,
    UNKNOWN,
}

enum class RiskLevel {
    SAFE,
    WARNING,
    DANGER,
}

data class DetectionDistanceEstimate(
    val distanceMeters: Float?,
    val rawGeometryDistanceMeters: Float?,
    val qualityScore: Float,
    val source: DistanceSource,
    val riskLevel: RiskLevel,
)

data class TrackingState(
    val trackId: Int,
    val ageFrames: Int,
    val consecutiveHits: Int,
    val isStable: Boolean,
    val smoothedDistanceMeters: Float?,
)

data class DetectedObjectResult(
    val boundingBox: RectF,
    val confidence: Float,
    val imageHeight: Int,
    val imageWidth: Int,
    val label: String,
    val distanceEstimate: DetectionDistanceEstimate,
    val trackingState: TrackingState? = null,
)

data class AnalyzerDebugInfo(
    val detectorReady: Boolean,
    val floorConfidence: Float,
    val modelInputSize: String,
    val modelOutputShape: String,
    val processedFrames: Int,
    val rawDetectionCount: Int,
    val trackedDetectionCount: Int,
    val lastError: String?,
)

data class FloorSegmentationResult(
    val width: Int,
    val height: Int,
    val boundaryYByColumn: IntArray,
    val confidence: Float,
) {
    fun boundaryYAt(imageX: Float, imageWidth: Int, imageHeight: Int): Float? {
        if (boundaryYByColumn.isEmpty() || imageWidth <= 0 || imageHeight <= 0) return null
        val xNorm = (imageX / imageWidth.toFloat()).coerceIn(0f, 1f)
        val column = (xNorm * (width - 1)).toInt().coerceIn(0, width - 1)
        val boundaryY = boundaryYByColumn[column]
        if (boundaryY < 0) return null
        return (boundaryY / height.toFloat()) * imageHeight.toFloat()
    }
}

data class FrameAnalysis(
    val detections: List<DetectedObjectResult>,
    val nearestObstacle: DetectedObjectResult?,
    val floorSegmentation: FloorSegmentationResult? = null,
    val debugInfo: AnalyzerDebugInfo? = null,
)
