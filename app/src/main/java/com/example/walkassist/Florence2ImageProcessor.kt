package com.example.walkassist

import android.graphics.Bitmap

object Florence2ImageProcessor {
    private const val IMAGE_SIZE = 768
    private val mean = floatArrayOf(0.485f, 0.456f, 0.406f)
    private val std = floatArrayOf(0.229f, 0.224f, 0.225f)

    fun preprocess(bitmap: Bitmap): FloatArray {
        val resized = Bitmap.createScaledBitmap(bitmap, IMAGE_SIZE, IMAGE_SIZE, true)
        return try {
            val pixels = IntArray(IMAGE_SIZE * IMAGE_SIZE)
            resized.getPixels(pixels, 0, IMAGE_SIZE, 0, 0, IMAGE_SIZE, IMAGE_SIZE)

            val output = FloatArray(3 * IMAGE_SIZE * IMAGE_SIZE)
            pixels.forEachIndexed { index, argb ->
                val red = ((argb shr 16) and 0xFF) / 255f
                val green = ((argb shr 8) and 0xFF) / 255f
                val blue = (argb and 0xFF) / 255f

                output[index] = (red - mean[0]) / std[0]
                output[IMAGE_SIZE * IMAGE_SIZE + index] = (green - mean[1]) / std[1]
                output[2 * IMAGE_SIZE * IMAGE_SIZE + index] = (blue - mean[2]) / std[2]
            }
            output
        } finally {
            if (resized !== bitmap && !resized.isRecycled) {
                resized.recycle()
            }
        }
    }

    fun pixelShape(): LongArray = longArrayOf(1L, 3L, IMAGE_SIZE.toLong(), IMAGE_SIZE.toLong())
}
