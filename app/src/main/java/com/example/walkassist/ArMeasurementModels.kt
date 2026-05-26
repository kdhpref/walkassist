package com.example.walkassist

enum class ArStatusLevel {
    INFO,
    SAFE,
    WARNING,
    DANGER,
}

data class ObjectOverlayDetection(
    val leftRatio: Float,
    val topRatio: Float,
    val widthRatio: Float,
    val heightRatio: Float,
    val label: String,
    val confidence: Float,
    val distanceMeters: Float? = null,
    val distanceIsReference: Boolean = false,
    val lane: String? = null,
    val isStable: Boolean = false,
    val trackId: Int? = null,
    val segmentCoverageRatio: Float? = null,
    val segmentCenterXRatio: Float? = null,
    val segmentCenterYRatio: Float? = null,
    val objectTimeToCollisionSeconds: Float? = null,
    val objectClosingSpeedMetersPerSecond: Float? = null,
    val motionDirectionLabel: String? = null,
    val avoidanceDirectionLabel: String? = null,
)

data class PlaneOverlayDetection(
    val xRatio: Float,
    val yRatio: Float,
    val label: String,
)

data class OverlayPointRatio(
    val xRatio: Float,
    val yRatio: Float,
)

data class PlanePolygonOverlay(
    val label: String,
    val points: List<OverlayPointRatio>,
)

data class DepthGridCell(
    val column: Int,
    val row: Int,
    val distanceMeters: Float?,
    val confidence: Float = 0f,
)

data class WalkingZoneDepthSample(
    val xRatio: Float,
    val yRatio: Float,
    val distanceMeters: Float?,
    val confidence: Float = 0f,
    val lane: String? = null,
)

data class ArMeasurementState(
    val trackingLabel: String = "initializing",
    val trackingFailureLabel: String = "",
    val horizontalPlaneCount: Int = 0,
    val verticalPlaneCount: Int = 0,
    val sensingConfidenceScore: Int = 0,
    val pitchDownDegrees: Float = 0f,
    val leftLaneWidthRatio: Float = 0.24f,
    val centerLaneWidthRatio: Float = 0.28f,
    val rightLaneWidthRatio: Float = 0.24f,
    val leftDistanceMeters: Float? = null,
    val centerDistanceMeters: Float? = null,
    val rightDistanceMeters: Float? = null,
    val suggestedDirection: String = "unknown",
    val floorDistanceMeters: Float? = null,
    val wallDistanceMeters: Float? = null,
    val depthDistanceMeters: Float? = null,
    val rawDepthDistanceMeters: Float? = null,
    val collisionDistanceMeters: Float? = null,
    val approachSpeedMetersPerSecond: Float? = null,
    val motionMetersPerSecond: Float? = null,
    val timeToCollisionSeconds: Float? = null,
    val riskLabel: String = "unknown",
    val guidanceLabel: String = "Scanning surroundings.",
    val statusLabel: String = "Move the phone slowly while depth samples stabilize.",
    val statusLevel: ArStatusLevel = ArStatusLevel.INFO,
    val crosswalkDetected: Boolean = false,
    val crosswalkScore: Float = 0f,
    val crosswalkStripeCount: Int = 0,
    val crosswalkYoloConfidence: Float = 0f,
    val crosswalkModeLabel: String = "",
    val objectDetections: List<ObjectOverlayDetection> = emptyList(),
    val depthGridCells: List<DepthGridCell> = emptyList(),
    val walkingZoneDepthSamples: List<WalkingZoneDepthSample> = emptyList(),
    val planeDetections: List<PlaneOverlayDetection> = emptyList(),
    val planePolygons: List<PlanePolygonOverlay> = emptyList(),
    val worldMapCells: List<WorldMapCellUi> = emptyList(),
    val worldMapRangeMeters: Float = 4f,
    val worldMapCellSizeMeters: Float = 0.2f,
    val worldMapKnownCells: Int = 0,
    val worldMapOccupiedCells: Int = 0,
    val worldMapObservationCount: Int = 0,
    val worldMapConfidenceScore: Int = 0,
    val worldMapLeftOpenScore: Float = 0f,
    val worldMapCenterOpenScore: Float = 0f,
    val worldMapRightOpenScore: Float = 0f,
    val worldMapLeftFreeSpaceMeters: Float? = null,
    val worldMapCenterFreeSpaceMeters: Float? = null,
    val worldMapRightFreeSpaceMeters: Float? = null,
    val vlmModelName: String = "",
    val vlmRiskLabel: String = "",
    val vlmSuggestedAction: String = "",
    val vlmConfidenceScore: Int = 0,
    val vlmSummary: String = "",
    val note: String = "",
)
