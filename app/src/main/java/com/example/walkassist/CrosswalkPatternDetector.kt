package com.example.walkassist

import android.graphics.Bitmap
import kotlin.math.abs

data class CrosswalkPatternResult(
    val detected: Boolean,
    val score: Float,
    val stripeCount: Int,
    val yoloConfidence: Float,
    val modeLabel: String,
)

data class CrosswalkMapCue(
    val active: Boolean,
    val confidence: Float,
    val distanceMeters: Float? = null,
    val headingDeltaDegrees: Float? = null,
)

data class CrosswalkFusionResult(
    val detected: Boolean,
    val score: Float,
    val stripeCount: Int,
    val yoloConfidence: Float,
    val modeLabel: String,
    val mapDistanceMeters: Float? = null,
    val mapHeadingDeltaDegrees: Float? = null,
)

class CrosswalkPatternDetector(
    private val analysisWidth: Int = 160,
    private val analysisHeight: Int = 120,
) {
    fun detect(
        bitmap: Bitmap,
        floorSegmentation: FloorSegmentationResult?,
        yoloConfidence: Float = 0f,
    ): CrosswalkPatternResult {
        val segmentation = floorSegmentation

        val scaled = Bitmap.createScaledBitmap(bitmap, analysisWidth, analysisHeight, true)
        return try {
            val pixels = IntArray(analysisWidth * analysisHeight)
            scaled.getPixels(pixels, 0, analysisWidth, 0, 0, analysisWidth, analysisHeight)

            val rowScores = FloatArray(analysisHeight)
            val leftBound = (analysisWidth * 0.08f).toInt()
            val rightBound = (analysisWidth * 0.92f).toInt()
            val topBound = if (segmentation != null && segmentation.confidence >= 0.18f) {
                (analysisHeight * 0.28f).toInt()
            } else {
                (analysisHeight * 0.42f).toInt()
            }
            val bottomBound = (analysisHeight * 0.94f).toInt()

            for (y in topBound..bottomBound) {
                var floorPixels = 0
                var whiteStripePixels = 0
                for (x in leftBound..rightBound) {
                    if (segmentation != null && segmentation.confidence >= 0.18f) {
                        val imageX = (x / (analysisWidth - 1).toFloat()) * bitmap.width.toFloat()
                        val imageY = (y / (analysisHeight - 1).toFloat()) * bitmap.height.toFloat()
                        val floorBoundary = segmentation.boundaryYAt(
                            imageX = imageX,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                        ) ?: continue
                        if (imageY < floorBoundary - (bitmap.height * 0.03f)) continue
                    }

                    floorPixels += 1
                    if (isLikelyWhitePaint(pixels[y * analysisWidth + x])) {
                        whiteStripePixels += 1
                    }
                }
                if (floorPixels >= (rightBound - leftBound) * 0.25f) {
                    rowScores[y] = whiteStripePixels / floorPixels.toFloat()
                }
            }

            val stripes = extractStripeCenters(rowScores, topBound, bottomBound)
            val stripeCountScore = (stripes.size / 5f).coerceIn(0f, 1f)
            val averageStripeStrength = if (stripes.isEmpty()) {
                0f
            } else {
                stripes.map { it.strength }.average().toFloat().coerceIn(0f, 1f)
            }
            val perspectiveScore = perspectiveConsistencyScore(stripes.map { it.centerY })
            val floorConfidence = segmentation?.confidence?.coerceIn(0f, 1f) ?: 0.28f
            val patternScore = (
                (stripeCountScore * 0.35f) +
                    (averageStripeStrength * 0.35f) +
                    (perspectiveScore * 0.2f) +
                    (floorConfidence * 0.1f)
                ).coerceIn(0f, 1f)
            val fusedScore = maxOf(
                patternScore,
                ((patternScore * 0.82f) + (yoloConfidence.coerceIn(0f, 1f) * 0.18f)),
                yoloConfidence.coerceIn(0f, 1f) * 0.62f,
            ).coerceIn(0f, 1f)
            val detected = (stripes.size >= 3 && fusedScore >= 0.52f) || fusedScore >= 0.68f
            CrosswalkPatternResult(
                detected = detected,
                score = fusedScore,
                stripeCount = stripes.size,
                yoloConfidence = yoloConfidence,
                modeLabel = when {
                    segmentation == null && stripes.size >= 3 && yoloConfidence >= 0.35f -> "image-pattern+yolo"
                    segmentation == null && stripes.size >= 3 -> "image-pattern"
                    yoloConfidence >= 0.35f && stripes.size >= 3 -> "pattern+yolo"
                    stripes.size >= 3 -> "pattern"
                    yoloConfidence >= 0.55f -> "yolo-boost"
                    else -> "none"
                },
            )
        } finally {
            if (scaled != bitmap) scaled.recycle()
        }
    }

    fun fuse(
        pattern: CrosswalkPatternResult,
        mapCue: CrosswalkMapCue,
        previousScore: Float = 0f,
    ): CrosswalkFusionResult {
        val mapScore = mapCue.confidence.coerceIn(0f, 1f)
        val visualScore = pattern.score.coerceIn(0f, 1f)
        val combined = when {
            mapCue.active && pattern.stripeCount >= 2 -> (visualScore * 0.58f) + (mapScore * 0.42f) + 0.08f
            mapCue.active -> (visualScore * 0.45f) + (mapScore * 0.55f)
            else -> visualScore
        }.coerceIn(0f, 1f)
        val smoothedScore = ((previousScore * 0.45f) + (combined * 0.55f)).coerceIn(0f, 1f)
        val detected = when {
            pattern.detected && mapCue.active && smoothedScore >= 0.46f -> true
            mapCue.active && pattern.stripeCount >= 2 && smoothedScore >= 0.52f -> true
            pattern.detected && smoothedScore >= 0.62f -> true
            else -> false
        }
        return CrosswalkFusionResult(
            detected = detected,
            score = smoothedScore,
            stripeCount = pattern.stripeCount,
            yoloConfidence = pattern.yoloConfidence,
            modeLabel = when {
                mapCue.active && pattern.stripeCount >= 2 -> "map+${pattern.modeLabel}"
                mapCue.active -> "map-candidate"
                else -> pattern.modeLabel
            },
            mapDistanceMeters = mapCue.distanceMeters,
            mapHeadingDeltaDegrees = mapCue.headingDeltaDegrees,
        )
    }

    private fun isLikelyWhitePaint(color: Int): Boolean {
        val r = (color shr 16) and 0xFF
        val g = (color shr 8) and 0xFF
        val b = color and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val luminance = (0.299f * r) + (0.587f * g) + (0.114f * b)
        return luminance >= 158f && max - min <= 68 && r >= 132 && g >= 132 && b >= 132
    }

    private fun extractStripeCenters(
        rowScores: FloatArray,
        topBound: Int,
        bottomBound: Int,
    ): List<StripeCandidate> {
        val smoothed = FloatArray(rowScores.size)
        for (index in topBound..bottomBound) {
            var sum = 0f
            var count = 0
            for (offset in -2..2) {
                val sample = rowScores.getOrNull(index + offset) ?: continue
                sum += sample
                count += 1
            }
            smoothed[index] = if (count > 0) sum / count else rowScores[index]
        }

        val stripes = mutableListOf<StripeCandidate>()
        var start = -1
        var strengthSum = 0f
        var rowCount = 0
        for (y in topBound..bottomBound) {
            val isStripeRow = smoothed[y] >= 0.18f
            if (isStripeRow) {
                if (start < 0) start = y
                strengthSum += smoothed[y]
                rowCount += 1
            } else if (start >= 0) {
                maybeAddStripe(stripes, start, y - 1, strengthSum, rowCount)
                start = -1
                strengthSum = 0f
                rowCount = 0
            }
        }
        if (start >= 0) {
            maybeAddStripe(stripes, start, bottomBound, strengthSum, rowCount)
        }

        return stripes
            .filterIndexed { index, stripe ->
                index == 0 || abs(stripe.centerY - stripes[index - 1].centerY) >= 5
            }
            .takeLast(8)
    }

    private fun maybeAddStripe(
        stripes: MutableList<StripeCandidate>,
        startY: Int,
        endY: Int,
        strengthSum: Float,
        rowCount: Int,
    ) {
        if (rowCount < 2) return
        val centerY = (startY + endY) * 0.5f
        val strength = (strengthSum / rowCount.toFloat()).coerceIn(0f, 1f)
        if (strength >= 0.2f) {
            stripes += StripeCandidate(centerY = centerY, strength = strength)
        }
    }

    private fun perspectiveConsistencyScore(stripeCenters: List<Float>): Float {
        if (stripeCenters.size < 3) return 0f
        val sorted = stripeCenters.sorted()
        val gaps = sorted.zipWithNext { a, b -> b - a }.filter { it >= 3f }
        if (gaps.size < 2) return 0.35f
        val topGap = gaps.first()
        val bottomGap = gaps.last()
        val growsTowardBottom = if (bottomGap >= topGap * 0.75f) 1f else 0.45f
        val smoothness = 1f - (gaps.zipWithNext { a, b ->
            abs(a - b) / maxOf(a, b, 1f)
        }.average().toFloatOrNull() ?: 0f).coerceIn(0f, 1f)
        return ((growsTowardBottom * 0.55f) + (smoothness * 0.45f)).coerceIn(0f, 1f)
    }

    private fun Double.toFloatOrNull(): Float? {
        return if (isFinite()) toFloat() else null
    }

    private data class StripeCandidate(
        val centerY: Float,
        val strength: Float,
    )
}
