package com.example.walkassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer

class ObjectAnalyzer(context: Context) {
    private val interpreter: Interpreter?
    private val labels: List<String>
    private val inputWidth: Int
    private val inputHeight: Int
    private val outputShapeDescription: String
    var lastErrorMessage: String? = null
        private set
    var lastRawDetectionCount: Int = 0
        private set
    var lastFinalDetectionCount: Int = 0
        private set

    init {
        var localInterpreter: Interpreter? = null
        var localLabels: List<String> = emptyList()
        var localInputWidth = 320
        var localInputHeight = 320
        var localOutputShapeDescription = "unavailable"

        try {
            val modelBuffer = FileUtil.loadMappedFile(context, "yolov8n.tflite")
            localInterpreter = Interpreter(modelBuffer, Interpreter.Options().apply {
                numThreads = 4
            })
            localLabels = FileUtil.loadLabels(context, "labels.txt")
            val inputShape = localInterpreter.getInputTensor(0).shape()
            if (inputShape.size >= 3) {
                localInputHeight = inputShape[1]
                localInputWidth = inputShape[2]
            }
            localOutputShapeDescription = localInterpreter.getOutputTensor(0).shape().joinToString(
                prefix = "[",
                postfix = "]",
            )
        } catch (exception: Exception) {
            lastErrorMessage = exception.message
            Log.e("ObjectAnalyzer", "Failed to initialize detector", exception)
        }

        interpreter = localInterpreter
        labels = localLabels
        inputWidth = localInputWidth
        inputHeight = localInputHeight
        outputShapeDescription = localOutputShapeDescription
    }

    fun isReady(): Boolean = interpreter != null

    fun modelInputSizeLabel(): String = "${inputWidth}x$inputHeight"

    fun modelOutputShapeLabel(): String = outputShapeDescription

    fun detect(bitmap: Bitmap): List<RawDetection> {
        val localInterpreter = interpreter ?: return emptyList()

        val scale = minOf(
            inputWidth.toFloat() / bitmap.width,
            inputHeight.toFloat() / bitmap.height,
        )
        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()

        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)
        val paddedBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(paddedBitmap)
        canvas.drawColor(Color.BLACK)

        val padX = (inputWidth - scaledWidth) / 2f
        val padY = (inputHeight - scaledHeight) / 2f
        canvas.drawBitmap(scaledBitmap, padX, padY, null)

        return try {
            val inputTensor = localInterpreter.getInputTensor(0)
            val isQuantized =
                inputTensor.dataType() == DataType.UINT8 || inputTensor.dataType() == DataType.INT8
            val tensorImage = TensorImage(if (isQuantized) DataType.UINT8 else DataType.FLOAT32)
            tensorImage.load(paddedBitmap)

            val inputBuffer = if (isQuantized) {
                tensorImage.buffer
            } else {
                val floatBuffer = TensorBuffer.createFixedSize(
                    intArrayOf(1, inputHeight, inputWidth, 3),
                    DataType.FLOAT32,
                )
                val pixels = IntArray(inputWidth * inputHeight)
                paddedBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
                val floatArray = FloatArray(inputWidth * inputHeight * 3)
                for (i in pixels.indices) {
                    val color = pixels[i]
                    floatArray[i * 3] = ((color shr 16) and 0xFF) / 255f
                    floatArray[i * 3 + 1] = ((color shr 8) and 0xFF) / 255f
                    floatArray[i * 3 + 2] = (color and 0xFF) / 255f
                }
                floatBuffer.loadArray(floatArray)
                floatBuffer.buffer
            }

            val outShape = localInterpreter.getOutputTensor(0).shape()
            if (outShape.size != 3) {
                lastErrorMessage = "Unexpected output shape ${outShape.joinToString(prefix = "[", postfix = "]")}"
                return emptyList()
            }

            val outputBuffer = TensorBuffer.createFixedSize(outShape, DataType.FLOAT32)
            localInterpreter.run(inputBuffer, outputBuffer.buffer.rewind())
            val detections = parseDetections(
                outputArray = outputBuffer.floatArray,
                dim1 = outShape[1],
                dim2 = outShape[2],
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                padX = padX,
                padY = padY,
                scale = scale,
            )
            lastFinalDetectionCount = detections.size
            lastErrorMessage = null
            detections
        } catch (exception: Exception) {
            lastErrorMessage = exception.message
            Log.e("ObjectAnalyzer", "Failed to run detection", exception)
            emptyList()
        } finally {
            if (scaledBitmap != bitmap) scaledBitmap.recycle()
            paddedBitmap.recycle()
        }
    }

    fun close() {
        interpreter?.close()
    }

    private fun parseDetections(
        outputArray: FloatArray,
        dim1: Int,
        dim2: Int,
        imageWidth: Int,
        imageHeight: Int,
        padX: Float,
        padY: Float,
        scale: Float,
    ): List<RawDetection> {
        val candidates = mutableListOf<RawDetection>()
        val confidenceThreshold = 0.25f
        val iouThreshold = 0.45f
        val channelsFirst = dim1 <= dim2
        val channelCount = minOf(dim1, dim2)
        val boxCount = maxOf(dim1, dim2)
        val classStartIndex = when {
            channelCount >= labels.size + 5 -> 5
            channelCount >= labels.size + 4 -> 4
            else -> {
                lastRawDetectionCount = 0
                lastErrorMessage = "Unsupported output channels: $channelCount"
                return emptyList()
            }
        }

        fun valueAt(channel: Int, box: Int): Float {
            return if (channelsFirst) {
                outputArray[channel * boxCount + box]
            } else {
                outputArray[box * channelCount + channel]
            }
        }

        for (box in 0 until boxCount) {
            var bestClassId = -1
            var bestClassScore = 0f

            for (channel in classStartIndex until channelCount) {
                val score = valueAt(channel, box)
                if (score > bestClassScore) {
                    bestClassScore = score
                    bestClassId = channel - classStartIndex
                }
            }

            val objectness = if (classStartIndex == 5) valueAt(4, box) else 1f
            val confidence = objectness * bestClassScore
            if (confidence < confidenceThreshold || bestClassId !in labels.indices) {
                continue
            }

            val centerX = valueAt(0, box)
            val centerY = valueAt(1, box)
            val width = valueAt(2, box)
            val height = valueAt(3, box)
            val outputsAreNormalized = centerX <= 1.5f && centerY <= 1.5f && width <= 1.5f && height <= 1.5f
            val boxCenterX = if (outputsAreNormalized) centerX * inputWidth else centerX
            val boxCenterY = if (outputsAreNormalized) centerY * inputHeight else centerY
            val boxWidth = if (outputsAreNormalized) width * inputWidth else width
            val boxHeight = if (outputsAreNormalized) height * inputHeight else height

            val originalCenterX = (boxCenterX - padX) / scale
            val originalCenterY = (boxCenterY - padY) / scale
            val originalWidth = boxWidth / scale
            val originalHeight = boxHeight / scale

            val left = (originalCenterX - originalWidth / 2f).coerceIn(0f, imageWidth.toFloat())
            val top = (originalCenterY - originalHeight / 2f).coerceIn(0f, imageHeight.toFloat())
            val right = (originalCenterX + originalWidth / 2f).coerceIn(0f, imageWidth.toFloat())
            val bottom = (originalCenterY + originalHeight / 2f).coerceIn(0f, imageHeight.toFloat())

            if (right - left < 8f || bottom - top < 8f) {
                continue
            }

            val label = labels.getOrNull(bestClassId) ?: "class_$bestClassId"
            candidates += RawDetection(
                boundingBox = RectF(left, top, right, bottom),
                confidence = confidence,
                imageHeight = imageHeight,
                imageWidth = imageWidth,
                label = label,
            )
        }

        lastRawDetectionCount = candidates.size
        return applyNms(candidates, iouThreshold)
    }

    private fun applyNms(candidates: List<RawDetection>, iouThreshold: Float): List<RawDetection> {
        val sorted = candidates.sortedByDescending { it.confidence }.toMutableList()
        val kept = mutableListOf<RawDetection>()

        while (sorted.isNotEmpty()) {
            val best = sorted.removeAt(0)
            kept += best
            sorted.removeAll { current ->
                current.label == best.label && calculateIoU(best.boundingBox, current.boundingBox) > iouThreshold
            }
        }

        return kept
    }

    private fun calculateIoU(a: RectF, b: RectF): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)

        if (right <= left || bottom <= top) {
            return 0f
        }

        val intersection = (right - left) * (bottom - top)
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0f) 0f else intersection / union
    }
}
