package com.example.walkassist

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.ImageProxy
import java.io.ByteArrayOutputStream

internal fun ImageProxy.toUprightBitmap(): Bitmap {
    val nv21 = yuv420888ToNv21(this)
    val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
    val out = ByteArrayOutputStream()
    yuvImage.compressToJpeg(Rect(0, 0, width, height), 92, out)
    val jpegBytes = out.toByteArray()
    val bitmap = BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        ?: error("Failed to decode camera frame")

    if (imageInfo.rotationDegrees == 0) {
        return bitmap
    }

    val matrix = Matrix().apply {
        postRotate(imageInfo.rotationDegrees.toFloat())
    }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated != bitmap) {
        bitmap.recycle()
    }
    return rotated
}

private fun yuv420888ToNv21(image: ImageProxy): ByteArray {
    val yPlane = image.planes[0]
    val uPlane = image.planes[1]
    val vPlane = image.planes[2]

    val ySize = image.width * image.height
    val uvSize = image.width * image.height / 4
    val nv21 = ByteArray(ySize + uvSize * 2)

    copyPlane(
        plane = yPlane,
        width = image.width,
        height = image.height,
        output = nv21,
        outputOffset = 0,
        outputStride = 1,
    )
    copyPlane(
        plane = vPlane,
        width = image.width / 2,
        height = image.height / 2,
        output = nv21,
        outputOffset = ySize,
        outputStride = 2,
    )
    copyPlane(
        plane = uPlane,
        width = image.width / 2,
        height = image.height / 2,
        output = nv21,
        outputOffset = ySize + 1,
        outputStride = 2,
    )

    return nv21
}

private fun copyPlane(
    plane: ImageProxy.PlaneProxy,
    width: Int,
    height: Int,
    output: ByteArray,
    outputOffset: Int,
    outputStride: Int,
) {
    val buffer = plane.buffer
    buffer.rewind()

    val rowBuffer = ByteArray(plane.rowStride)
    var outputIndex = outputOffset

    for (row in 0 until height) {
        val length = if (plane.pixelStride == 1 && outputStride == 1) {
            width
        } else {
            (width - 1) * plane.pixelStride + 1
        }

        buffer.get(rowBuffer, 0, length)

        var inputIndex = 0
        for (col in 0 until width) {
            output[outputIndex] = rowBuffer[inputIndex]
            outputIndex += outputStride
            inputIndex += plane.pixelStride
        }

        if (row < height - 1) {
            buffer.position(buffer.position() + plane.rowStride - length)
        }
    }
}
