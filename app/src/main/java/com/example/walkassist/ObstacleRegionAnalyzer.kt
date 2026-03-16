package com.example.walkassist

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs

class ObstacleRegionAnalyzer {
    fun detect(
        bitmap: Bitmap,
        floorSegmentation: FloorSegmentationResult?,
    ): List<RawDetection> {
        val segmentation = floorSegmentation ?: return emptyList()
        if (segmentation.confidence < 0.15f) return emptyList()

        val maskWidth = 96
        val maskHeight = 128
        val scaled = Bitmap.createScaledBitmap(bitmap, maskWidth, maskHeight, true)
        return try {
            val pixels = IntArray(maskWidth * maskHeight)
            scaled.getPixels(pixels, 0, maskWidth, 0, 0, maskWidth, maskHeight)
            val candidateMask = BooleanArray(maskWidth * maskHeight)
            val boundary = IntArray(maskWidth) { -1 }

            for (x in 0 until maskWidth) {
                val y = segmentation.boundaryYAt(
                    imageX = x / (maskWidth - 1).toFloat() * bitmap.width,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                )
                boundary[x] = if (y == null) -1 else (y / bitmap.height.toFloat() * maskHeight).toInt()
            }

            for (x in 0 until maskWidth) {
                val floorY = boundary[x]
                if (floorY < 0) continue
                val startY = (floorY - maskHeight * 0.18f).toInt().coerceAtLeast(0)
                val endY = (floorY + maskHeight * 0.03f).toInt().coerceAtMost(maskHeight - 1)
                val floorRef = sampleFloorReference(pixels, maskWidth, maskHeight, x, floorY)

                for (y in startY..endY) {
                    if (isLikelyEgoMask(x, y, maskWidth, maskHeight)) continue
                    val pixel = pixels[y * maskWidth + x]
                    val diff = colorDistance(pixel, floorRef)
                    if (diff > 42f && y <= floorY) {
                        candidateMask[y * maskWidth + x] = true
                    }
                }
            }

            connectedComponents(
                candidateMask = candidateMask,
                maskWidth = maskWidth,
                maskHeight = maskHeight,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            )
        } finally {
            if (scaled != bitmap) scaled.recycle()
        }
    }

    private fun sampleFloorReference(
        pixels: IntArray,
        width: Int,
        height: Int,
        x: Int,
        floorY: Int,
    ): Int {
        val sampleY = (floorY + height * 0.04f).toInt().coerceIn(0, height - 1)
        return pixels[sampleY * width + x]
    }

    private fun connectedComponents(
        candidateMask: BooleanArray,
        maskWidth: Int,
        maskHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
    ): List<RawDetection> {
        val visited = BooleanArray(candidateMask.size)
        val detections = mutableListOf<RawDetection>()
        val queue = IntArray(candidateMask.size)

        for (index in candidateMask.indices) {
            if (!candidateMask[index] || visited[index]) continue

            var head = 0
            var tail = 0
            queue[tail++] = index
            visited[index] = true

            var minX = maskWidth
            var minY = maskHeight
            var maxX = 0
            var maxY = 0
            var count = 0

            while (head < tail) {
                val current = queue[head++]
                val x = current % maskWidth
                val y = current / maskWidth
                count += 1
                minX = minOf(minX, x)
                minY = minOf(minY, y)
                maxX = maxOf(maxX, x)
                maxY = maxOf(maxY, y)

                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx !in 0 until maskWidth || ny !in 0 until maskHeight) continue
                        val neighbor = ny * maskWidth + nx
                        if (candidateMask[neighbor] && !visited[neighbor]) {
                            visited[neighbor] = true
                            queue[tail++] = neighbor
                        }
                    }
                }
            }

            val width = maxX - minX + 1
            val height = maxY - minY + 1
            if (count < 18 || width < 6 || height < 6) continue

            detections += RawDetection(
                boundingBox = RectF(
                    minX / maskWidth.toFloat() * imageWidth,
                    minY / maskHeight.toFloat() * imageHeight,
                    (maxX + 1) / maskWidth.toFloat() * imageWidth,
                    (maxY + 1) / maskHeight.toFloat() * imageHeight,
                ),
                confidence = (count / 180f).coerceIn(0f, 1f),
                imageHeight = imageHeight,
                imageWidth = imageWidth,
                label = "obstacle",
            )
        }

        return detections.sortedByDescending { it.confidence }
    }

    private fun isLikelyEgoMask(x: Int, y: Int, width: Int, height: Int): Boolean {
        val xNorm = x / width.toFloat()
        val yNorm = y / height.toFloat()
        return yNorm > 0.78f && xNorm in 0.25f..0.75f
    }

    private fun colorDistance(color: Int, reference: Int): Float {
        val r = ((color shr 16) and 0xFF) - ((reference shr 16) and 0xFF)
        val g = ((color shr 8) and 0xFF) - ((reference shr 8) and 0xFF)
        val b = (color and 0xFF) - (reference and 0xFF)
        return (abs(r) + abs(g) + abs(b)) / 3f
    }
}
