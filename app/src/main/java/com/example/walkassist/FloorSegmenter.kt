package com.example.walkassist

import android.graphics.Bitmap
import kotlin.math.abs

class FloorSegmenter(
    private val maskWidth: Int = 96,
    private val maskHeight: Int = 128,
) {
    fun segment(bitmap: Bitmap): FloorSegmentationResult {
        val scaled = Bitmap.createScaledBitmap(bitmap, maskWidth, maskHeight, true)
        return try {
            val pixels = IntArray(maskWidth * maskHeight)
            scaled.getPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)

            val leftBound = (maskWidth * 0.1f).toInt()
            val rightBound = (maskWidth * 0.9f).toInt()
            val horizonLimit = (maskHeight * 0.32f).toInt()
            val boundary = IntArray(maskWidth) { -1 }
            var validColumns = 0
            var confidenceAccumulator = 0f

            for (x in leftBound until rightBound) {
                val seedTop = (maskHeight * 0.88f).toInt()
                var refR = 0f
                var refG = 0f
                var refB = 0f
                var seedCount = 0

                for (y in seedTop until maskHeight) {
                    val color = pixels[y * maskWidth + x]
                    refR += ((color shr 16) and 0xFF)
                    refG += ((color shr 8) and 0xFF)
                    refB += (color and 0xFF)
                    seedCount += 1
                }
                if (seedCount == 0) continue

                refR /= seedCount
                refG /= seedCount
                refB /= seedCount

                var failStreak = 0
                var topFloorY = maskHeight - 1
                for (y in (maskHeight - 1) downTo horizonLimit) {
                    val color = pixels[y * maskWidth + x]
                    val r = ((color shr 16) and 0xFF).toFloat()
                    val g = ((color shr 8) and 0xFF).toFloat()
                    val b = (color and 0xFF).toFloat()
                    val distance = (abs(r - refR) + abs(g - refG) + abs(b - refB)) / 3f
                    val verticalBias = 1f + ((maskHeight - 1 - y).toFloat() / maskHeight.toFloat())
                    val allowedDistance = 24f * verticalBias

                    if (distance <= allowedDistance) {
                        topFloorY = y
                        failStreak = 0
                        refR = (refR * 0.9f) + (r * 0.1f)
                        refG = (refG * 0.9f) + (g * 0.1f)
                        refB = (refB * 0.9f) + (b * 0.1f)
                    } else {
                        failStreak += 1
                        if (failStreak >= 3) {
                            break
                        }
                    }
                }

                val floorDepth = (maskHeight - topFloorY).coerceAtLeast(0)
                if (floorDepth > maskHeight * 0.12f) {
                    boundary[x] = topFloorY
                    validColumns += 1
                    confidenceAccumulator += floorDepth / maskHeight.toFloat()
                }
            }

            smoothBoundary(boundary)
            val confidence = if (validColumns == 0) {
                0f
            } else {
                val coverage = validColumns / (rightBound - leftBound).toFloat()
                val averageDepth = confidenceAccumulator / validColumns.toFloat()
                (coverage * 0.6f + averageDepth * 0.4f).coerceIn(0f, 1f)
            }

            FloorSegmentationResult(
                width = maskWidth,
                height = maskHeight,
                boundaryYByColumn = boundary,
                confidence = confidence,
            )
        } finally {
            if (scaled != bitmap) {
                scaled.recycle()
            }
        }
    }

    private fun smoothBoundary(boundary: IntArray) {
        val copy = boundary.copyOf()
        for (index in boundary.indices) {
            val samples = ArrayList<Int>(5)
            for (offset in -2..2) {
                val sample = copy.getOrNull(index + offset) ?: continue
                if (sample >= 0) {
                    samples += sample
                }
            }
            if (samples.isNotEmpty()) {
                samples.sort()
                boundary[index] = samples[samples.size / 2]
            }
        }
    }
}
