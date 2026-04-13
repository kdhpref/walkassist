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

data class VoxelColumnUi(
    val relativeX: Float,
    val relativeZ: Float,
    val occupancyScore: Float,
    val confidenceScore: Float,
    val heightMeters: Float,
    val voxelCount: Int,
)

data class VoxelPointUi(
    val worldX: Float,
    val worldY: Float,
    val worldZ: Float,
    val relativeX: Float,
    val relativeY: Float,
    val relativeZ: Float,
    val occupancyScore: Float,
    val confidenceScore: Float,
)

data class VoxelOverlayPointUi(
    val xRatio: Float,
    val yRatio: Float,
    val occupancyScore: Float,
    val confidenceScore: Float,
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
    val statusLabel: String = "Move the phone slowly to detect floor and wall planes.",
    val statusLevel: ArStatusLevel = ArStatusLevel.INFO,
    val objectDetections: List<ObjectOverlayDetection> = emptyList(),
    val planeDetections: List<PlaneOverlayDetection> = emptyList(),
    val planePolygons: List<PlanePolygonOverlay> = emptyList(),
    val worldMapCells: List<WorldMapCellUi> = emptyList(),
    val worldMapRangeMeters: Float = 4f,
    val worldMapCellSizeMeters: Float = 0.2f,
    val worldMapKnownCells: Int = 0,
    val worldMapOccupiedCells: Int = 0,
    val worldMapObservationCount: Int = 0,
    val worldMapConfidenceScore: Int = 0,
    val voxelColumns: List<VoxelColumnUi> = emptyList(),
    val voxelPoints: List<VoxelPointUi> = emptyList(),
    val voxelOverlayPoints: List<VoxelOverlayPointUi> = emptyList(),
    val voxelRangeMeters: Float = 5f,
    val voxelSizeMeters: Float = 0.25f,
    val voxelKnownCount: Int = 0,
    val voxelOccupiedCount: Int = 0,
    val voxelObstacleColumns: Int = 0,
    val voxelConfidenceScore: Int = 0,
    val note: String = "",
)
