package com.example.walkassist

import kotlin.math.tan

class GroundProjector(
    private val cameraHeightMeters: Float = 1.45f,
    private val verticalFovDegrees: Float = 60f,
    private val aspectRatio: Float = 16f / 9f,
) {
    fun distanceFromImageY(
        imageY: Float,
        imageHeight: Int,
        pitchRadians: Float,
    ): Float? {
        val verticalFovRadians = Math.toRadians(verticalFovDegrees.toDouble()).toFloat()
        val imageCenterY = imageHeight / 2f
        val angleOffset = ((imageY - imageCenterY) / imageHeight.toFloat()) * verticalFovRadians
        val totalAngle = pitchRadians + angleOffset
        if (totalAngle <= 0.08f) return null
        val distance = cameraHeightMeters / tan(totalAngle)
        return if (distance.isFinite() && distance in 0.15f..25f) distance else null
    }

    fun horizontalFovRadians(): Float {
        val verticalFovRadians = Math.toRadians(verticalFovDegrees.toDouble()).toFloat()
        return 2f * kotlin.math.atan(tan(verticalFovRadians / 2f) * aspectRatio)
    }

    fun focalLengthPixels(imageHeight: Int): Float {
        val verticalFovRadians = Math.toRadians(verticalFovDegrees.toDouble()).toFloat()
        return imageHeight / (2f * tan(verticalFovRadians / 2f))
    }
}
