package com.example.walkassist

import android.graphics.Bitmap
import kotlin.math.abs

class PathAnalyzer(
    private val cameraHeightMeters: Float = 1.45f,
    private val verticalFovDegrees: Float = 60f,
) {
    private val groundProjector = GroundProjector(
        cameraHeightMeters = cameraHeightMeters,
        verticalFovDegrees = verticalFovDegrees,
    )

    fun analyze(
        bitmap: Bitmap,
        floorSegmentation: FloorSegmentationResult?,
        pitchRadians: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): PathMetrics {
        val segmentation = floorSegmentation
        val centerClear = estimateCenterPathDistance(segmentation, pitchRadians, imageWidth, imageHeight)
        val centerObstacle = detectCenterLaneObstacle(bitmap, segmentation, pitchRadians)
        val collisionPlane = detectCollisionPlane(bitmap, pitchRadians)
        val laneWidthMeters = estimateLaneWidthMeters(segmentation, pitchRadians, imageWidth, imageHeight)
        val egoOcclusionDetected = detectEgoOcclusion(bitmap)
        val collisionDistance = listOfNotNull(centerObstacle, collisionPlane?.distanceMeters).minOrNull()
        val pathClearMeters = listOfNotNull(centerClear, collisionDistance).minOrNull() ?: centerClear

        return PathMetrics(
            pathClearMeters = pathClearMeters,
            centerObstacleMeters = centerObstacle,
            collisionDistanceMeters = collisionDistance,
            laneWidthMeters = laneWidthMeters,
            likelyWallAhead = collisionPlane?.likelyWall == true,
            egoOcclusionDetected = egoOcclusionDetected,
            timeToCollisionSeconds = null,
        )
    }

    private fun estimateCenterPathDistance(
        floorSegmentation: FloorSegmentationResult?,
        pitchRadians: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): Float? {
        val segmentation = floorSegmentation ?: return null
        val centerSamples = listOf(0.43f, 0.50f, 0.57f)
        val distances = centerSamples.mapNotNull { xNorm ->
            val y = segmentation.boundaryYAt(
                imageX = xNorm * imageWidth,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
            ) ?: return@mapNotNull null
            groundProjector.distanceFromImageY(y, imageHeight, pitchRadians)
        }
        if (distances.isEmpty()) return null
        return distances.sorted()[distances.size / 2]
    }

    private fun estimateLaneWidthMeters(
        floorSegmentation: FloorSegmentationResult?,
        pitchRadians: Float,
        imageWidth: Int,
        imageHeight: Int,
    ): Float? {
        val segmentation = floorSegmentation ?: return null
        val leftY = segmentation.boundaryYAt(imageWidth * 0.35f, imageWidth, imageHeight) ?: return null
        val rightY = segmentation.boundaryYAt(imageWidth * 0.65f, imageWidth, imageHeight) ?: return null
        val centerDistance = estimateCenterPathDistance(segmentation, pitchRadians, imageWidth, imageHeight) ?: return null
        val avgY = (leftY + rightY) * 0.5f
        val forwardDistance = groundProjector.distanceFromImageY(avgY, imageHeight, pitchRadians) ?: return null
        val laneAngle = groundProjector.horizontalFovRadians() * 0.30f
        val effectiveDistance = (centerDistance + forwardDistance) * 0.5f
        return 2f * effectiveDistance * kotlin.math.tan(laneAngle * 0.5f)
    }

    private fun detectCenterLaneObstacle(
        bitmap: Bitmap,
        floorSegmentation: FloorSegmentationResult?,
        pitchRadians: Float,
    ): Float? {
        val segmentation = floorSegmentation ?: return null
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val centerColumns = listOf(0.42f, 0.50f, 0.58f)
        val distances = mutableListOf<Float>()

        for (xNorm in centerColumns) {
            val imageX = xNorm * width
            val floorY = segmentation.boundaryYAt(imageX, width, height) ?: continue
            val floorYInt = floorY.toInt().coerceIn(1, height - 2)
            val refColor = pixels[floorYInt * width + imageX.toInt().coerceIn(0, width - 1)]
            val searchTop = (floorYInt - height * 0.20f).toInt().coerceAtLeast(0)
            var bestY: Int? = null

            for (y in floorYInt downTo searchTop) {
                if (isLikelyEgoZone(imageX / width.toFloat(), y / height.toFloat())) continue
                val color = pixels[y * width + imageX.toInt().coerceIn(0, width - 1)]
                val diff = colorDistance(color, refColor)
                if (diff > 34f) {
                    bestY = y
                } else if (bestY != null) {
                    break
                }
            }

            if (bestY != null) {
                groundProjector.distanceFromImageY(bestY.toFloat(), height, pitchRadians)?.let { distances += it }
            }
        }

        return distances.minOrNull()
    }

    private fun detectCollisionPlane(
        bitmap: Bitmap,
        pitchRadians: Float,
    ): CollisionPlane? {
        val width = bitmap.width
        val height = bitmap.height
        val left = (width * 0.20f).toInt()
        val right = (width * 0.80f).toInt()
        val top = (height * 0.12f).toInt()
        val bottom = (height * 0.74f).toInt()
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        var bestRow = -1
        var bestScore = 0f
        for (y in top until bottom - 1) {
            var diffSum = 0f
            var count = 0
            for (x in left until right) {
                if (isLikelyEgoZone(x / width.toFloat(), y / height.toFloat())) continue
                val current = pixels[y * width + x]
                val next = pixels[(y + 1) * width + x]
                diffSum += abs(luminance(next) - luminance(current))
                count += 1
            }
            if (count < (right - left) * 0.55f) continue
            val diff = diffSum / count
            val centerBias = 1f - abs(y - height * 0.48f) / (height * 0.48f)
            val score = diff * (0.6f + 0.4f * centerBias.coerceIn(0f, 1f))
            if (score > bestScore && diff > 12f) {
                bestScore = score
                bestRow = y
            }
        }

        if (bestRow < 0) return null
        val distance = groundProjector.distanceFromImageY(bestRow.toFloat(), height, pitchRadians) ?: return null
        return CollisionPlane(bestRow.toFloat(), distance, bestScore > 18f)
    }

    private fun detectEgoOcclusion(bitmap: Bitmap): Boolean {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var centerBrightness = 0f
        var sideBrightness = 0f
        var centerCount = 0
        var sideCount = 0

        for (y in (height * 0.78f).toInt() until height) {
            for (x in 0 until width) {
                val lum = luminance(pixels[y * width + x])
                val xNorm = x / width.toFloat()
                if (xNorm in 0.33f..0.67f) {
                    centerBrightness += lum
                    centerCount += 1
                } else {
                    sideBrightness += lum
                    sideCount += 1
                }
            }
        }

        if (centerCount == 0 || sideCount == 0) return false
        val centerAvg = centerBrightness / centerCount
        val sideAvg = sideBrightness / sideCount
        return abs(centerAvg - sideAvg) > 22f
    }

    private fun isLikelyEgoZone(xNorm: Float, yNorm: Float): Boolean {
        return yNorm > 0.80f && xNorm in 0.25f..0.75f
    }

    private fun colorDistance(color: Int, reference: Int): Float {
        val r = ((color shr 16) and 0xFF) - ((reference shr 16) and 0xFF)
        val g = ((color shr 8) and 0xFF) - ((reference shr 8) and 0xFF)
        val b = (color and 0xFF) - (reference and 0xFF)
        return (abs(r) + abs(g) + abs(b)) / 3f
    }

    private fun luminance(color: Int): Float {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        return (0.299f * r) + (0.587f * g) + (0.114f * b)
    }

    private data class CollisionPlane(
        val imageY: Float,
        val distanceMeters: Float,
        val likelyWall: Boolean,
    )
}
