package com.example.walkassist

enum class ArStatusLevel {
    INFO,
    SAFE,
    WARNING,
    DANGER,
}

data class ArMeasurementState(
    val trackingLabel: String = "initializing",
    val trackingFailureLabel: String = "",
    val horizontalPlaneCount: Int = 0,
    val verticalPlaneCount: Int = 0,
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
    val collisionDistanceMeters: Float? = null,
    val approachSpeedMetersPerSecond: Float? = null,
    val motionMetersPerSecond: Float? = null,
    val riskLabel: String = "unknown",
    val guidanceLabel: String = "Scanning surroundings.",
    val statusLabel: String = "Move the phone slowly to detect floor and wall planes.",
    val statusLevel: ArStatusLevel = ArStatusLevel.INFO,
    val note: String = "",
)
