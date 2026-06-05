package com.example.walkassist

import android.graphics.Bitmap
import android.graphics.RectF

enum class DistanceSource {
    FLOOR_SEGMENTATION,
    HYBRID_GEOMETRY_SIZE,
    SIZE_PRIOR,
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
    val missedFrames: Int,
    val isPredicted: Boolean,
    val smoothedDistanceMeters: Float?,
    val closingSpeedMetersPerSecond: Float?,
    val timeToCollisionSeconds: Float?,
)

data class DetectedObjectResult(
    val boundingBox: RectF,
    val confidence: Float,
    val imageHeight: Int,
    val imageWidth: Int,
    val label: String,
    val distanceEstimate: DetectionDistanceEstimate,
    val segmentCoverageRatio: Float? = null,
    val segmentCenterXRatio: Float? = null,
    val segmentCenterYRatio: Float? = null,
    val segmentLeftXRatio: Float? = null,
    val segmentTopYRatio: Float? = null,
    val segmentRightXRatio: Float? = null,
    val segmentBottomYRatio: Float? = null,
    val segmentPolygon: List<SegmentMaskPoint> = emptyList(),
    val trackingState: TrackingState? = null,
)

data class AnalyzerDebugInfo(
    val detectorReady: Boolean,
    val floorConfidence: Float,
    val floorMode: String,
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

data class PathMetrics(
    val pathClearMeters: Float?,
    val centerObstacleMeters: Float?,
    val collisionDistanceMeters: Float?,
    val laneWidthMeters: Float?,
    val likelyWallAhead: Boolean,
    val egoOcclusionDetected: Boolean,
    val timeToCollisionSeconds: Float?,
)

data class FrameAnalysis(
    val detections: List<DetectedObjectResult>,
    val nearestObstacle: DetectedObjectResult?,
    val floorSegmentation: FloorSegmentationResult? = null,
    val pathMetrics: PathMetrics? = null,
    val debugInfo: AnalyzerDebugInfo? = null,
)

enum class SpatialFrameSource {
    LIVE_CAMERA,
    VIDEO_REPLAY,
}

enum class VlmRequestMode {
    AUTO,
    MANUAL,
}

data class SpatialFrame(
    val bitmap: Bitmap,
    val timestampMillis: Long,
    val source: SpatialFrameSource,
    val pitchRadians: Float,
    val arState: ArMeasurementState? = null,
    val requestMode: VlmRequestMode = VlmRequestMode.AUTO,
)
