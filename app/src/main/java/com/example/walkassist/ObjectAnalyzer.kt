package com.example.walkassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.roundToInt

class ObjectAnalyzer(context: Context) {
    companion object {
        private const val MODEL_ASSET_NAME = "yolo26n-seg.tflite"
        private const val LABELS_ASSET_NAME = "labels.txt"
        private const val CHANNELS_RGB = 3
        private const val FLOAT_BYTES = 4
    }

    private data class SegmentSummary(
        val coverageRatio: Float,
        val centerXRatio: Float,
        val centerYRatio: Float,
    )

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
            val modelBuffer = TfliteAssetUtils.loadMappedAsset(context, MODEL_ASSET_NAME)
            localInterpreter = Interpreter(modelBuffer, Interpreter.Options().apply {
                numThreads = 4
            })
            localLabels = TfliteAssetUtils.loadLabels(context, LABELS_ASSET_NAME)
            val inputShape = localInterpreter.getInputTensor(0).shape()
            if (inputShape.size >= 3) {
                localInputHeight = inputShape[1]
                localInputWidth = inputShape[2]
            }
            localOutputShapeDescription = (0 until localInterpreter.getOutputTensorCount())
                .joinToString(separator = " ") { index ->
                    localInterpreter.getOutputTensor(index).shape().joinToString(
                        prefix = "[",
                        postfix = "]",
                    )
                }
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

            val inputBuffer = if (isQuantized) {
                createQuantizedRgbInputBuffer(paddedBitmap)
            } else {
                createFloatRgbInputBuffer(paddedBitmap)
            }

            val detections = if (isEndToEndSegmentationOutput(localInterpreter)) {
                detectEndToEndSegmentation(
                    interpreter = localInterpreter,
                    inputBuffer = inputBuffer,
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    padX = padX,
                    padY = padY,
                    scale = scale,
                )
            } else {
                val outShape = localInterpreter.getOutputTensor(0).shape()
                if (outShape.size != 3) {
                    lastErrorMessage = "Unexpected output shape ${outShape.joinToString(prefix = "[", postfix = "]")}"
                    return emptyList()
                }

                val outputBuffer = ByteBuffer.allocateDirect(outShape.product() * FLOAT_BYTES)
                    .order(ByteOrder.nativeOrder())
                localInterpreter.run(inputBuffer.rewind(), outputBuffer.rewind())
                parseDetections(
                    outputArray = outputBuffer.toFloatArray(),
                    dim1 = outShape[1],
                    dim2 = outShape[2],
                    imageWidth = bitmap.width,
                    imageHeight = bitmap.height,
                    padX = padX,
                    padY = padY,
                    scale = scale,
                )
            }
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

    private fun createQuantizedRgbInputBuffer(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * CHANNELS_RGB)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        for (pixel in pixels) {
            inputBuffer.put(((pixel shr 16) and 0xFF).toByte())
            inputBuffer.put(((pixel shr 8) and 0xFF).toByte())
            inputBuffer.put((pixel and 0xFF).toByte())
        }
        return inputBuffer.rewind() as ByteBuffer
    }

    private fun createFloatRgbInputBuffer(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = ByteBuffer.allocateDirect(inputWidth * inputHeight * CHANNELS_RGB * FLOAT_BYTES)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        for (pixel in pixels) {
            inputBuffer.putFloat((((pixel shr 16) and 0xFF) / 255f))
            inputBuffer.putFloat((((pixel shr 8) and 0xFF) / 255f))
            inputBuffer.putFloat(((pixel and 0xFF) / 255f))
        }
        return inputBuffer.rewind() as ByteBuffer
    }

    private fun IntArray.product(): Int = fold(1) { total, value -> total * value }

    private fun ByteBuffer.toFloatArray(): FloatArray {
        rewind()
        val floatBuffer = asFloatBuffer()
        return FloatArray(floatBuffer.remaining()).also(floatBuffer::get)
    }

    private fun isEndToEndSegmentationOutput(interpreter: Interpreter): Boolean {
        if (interpreter.getOutputTensorCount() < 2) return false
        val hasDetections = (0 until interpreter.getOutputTensorCount()).any { index ->
            val shape = interpreter.getOutputTensor(index).shape()
            shape.size == 3 && shape[0] == 1 && shape[1] in 1..1000 && shape[2] in 7..128
        }
        val hasPrototypes = (0 until interpreter.getOutputTensorCount()).any { index ->
            val shape = interpreter.getOutputTensor(index).shape()
            shape.size == 4 && shape[0] == 1 && shape[1] > 1 && shape[2] > 1 && shape[3] in 8..64
        }
        return hasDetections && hasPrototypes
    }

    private fun detectEndToEndSegmentation(
        interpreter: Interpreter,
        inputBuffer: java.nio.ByteBuffer,
        imageWidth: Int,
        imageHeight: Int,
        padX: Float,
        padY: Float,
        scale: Float,
    ): List<RawDetection> {
        val detectionOutputIndex = (0 until interpreter.getOutputTensorCount()).first { index ->
            val shape = interpreter.getOutputTensor(index).shape()
            shape.size == 3 && shape[0] == 1 && shape[1] in 1..1000 && shape[2] in 7..128
        }
        val prototypeOutputIndex = (0 until interpreter.getOutputTensorCount()).first { index ->
            val shape = interpreter.getOutputTensor(index).shape()
            shape.size == 4 && shape[0] == 1 && shape[1] > 1 && shape[2] > 1 && shape[3] in 8..64
        }
        val detectionShape = interpreter.getOutputTensor(detectionOutputIndex).shape()
        val prototypeShape = interpreter.getOutputTensor(prototypeOutputIndex).shape()
        val detectionOutput = Array(1) { Array(detectionShape[1]) { FloatArray(detectionShape[2]) } }
        val prototypeOutput = Array(1) {
            Array(prototypeShape[1]) {
                Array(prototypeShape[2]) {
                    FloatArray(prototypeShape[3])
                }
            }
        }
        val outputs = hashMapOf<Int, Any>(
            detectionOutputIndex to detectionOutput,
            prototypeOutputIndex to prototypeOutput,
        )
        inputBuffer.rewind()
        interpreter.runForMultipleInputsOutputs(arrayOf(inputBuffer), outputs)
        return parseEndToEndSegDetections(
            rows = detectionOutput[0],
            prototype = prototypeOutput[0],
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            padX = padX,
            padY = padY,
            scale = scale,
        )
    }

    private fun parseEndToEndSegDetections(
        rows: Array<FloatArray>,
        prototype: Array<Array<FloatArray>>,
        imageWidth: Int,
        imageHeight: Int,
        padX: Float,
        padY: Float,
        scale: Float,
    ): List<RawDetection> {
        val candidates = mutableListOf<RawDetection>()
        val confidenceThreshold = 0.25f
        val iouThreshold = 0.45f

        for (row in rows) {
            if (row.size < 7) continue

            val confidence = row[4]
            val classId = row[5].roundToInt()
            if (confidence < confidenceThreshold || classId !in labels.indices) {
                continue
            }

            val outputsAreNormalized =
                row[0] <= 1.5f && row[1] <= 1.5f && row[2] <= 1.5f && row[3] <= 1.5f
            val inputLeft = if (outputsAreNormalized) row[0] * inputWidth else row[0]
            val inputTop = if (outputsAreNormalized) row[1] * inputHeight else row[1]
            val inputRight = if (outputsAreNormalized) row[2] * inputWidth else row[2]
            val inputBottom = if (outputsAreNormalized) row[3] * inputHeight else row[3]

            val left = ((minOf(inputLeft, inputRight) - padX) / scale).coerceIn(0f, imageWidth.toFloat())
            val top = ((minOf(inputTop, inputBottom) - padY) / scale).coerceIn(0f, imageHeight.toFloat())
            val right = ((maxOf(inputLeft, inputRight) - padX) / scale).coerceIn(0f, imageWidth.toFloat())
            val bottom = ((maxOf(inputTop, inputBottom) - padY) / scale).coerceIn(0f, imageHeight.toFloat())

            if (right - left < 8f || bottom - top < 8f) {
                continue
            }

            val segmentSummary = computeSegmentSummary(
                prototype = prototype,
                coefficients = row.copyOfRange(6, row.size),
                inputLeft = minOf(inputLeft, inputRight),
                inputTop = minOf(inputTop, inputBottom),
                inputRight = maxOf(inputLeft, inputRight),
                inputBottom = maxOf(inputTop, inputBottom),
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                padX = padX,
                padY = padY,
                scale = scale,
            )

            candidates += RawDetection(
                boundingBox = RectF(left, top, right, bottom),
                confidence = confidence.coerceIn(0f, 1f),
                imageHeight = imageHeight,
                imageWidth = imageWidth,
                label = labels[classId],
                segmentCoverageRatio = segmentSummary?.coverageRatio,
                segmentCenterXRatio = segmentSummary?.centerXRatio,
                segmentCenterYRatio = segmentSummary?.centerYRatio,
            )
        }

        lastRawDetectionCount = candidates.size
        return applyNms(candidates, iouThreshold)
    }

    private fun computeSegmentSummary(
        prototype: Array<Array<FloatArray>>,
        coefficients: FloatArray,
        inputLeft: Float,
        inputTop: Float,
        inputRight: Float,
        inputBottom: Float,
        imageWidth: Int,
        imageHeight: Int,
        padX: Float,
        padY: Float,
        scale: Float,
    ): SegmentSummary? {
        val protoHeight = prototype.size
        val protoWidth = prototype.firstOrNull()?.size ?: return null
        val protoChannels = prototype.firstOrNull()?.firstOrNull()?.size ?: return null
        if (protoHeight <= 0 || protoWidth <= 0 || protoChannels <= 0 || coefficients.size < protoChannels) {
            return null
        }

        val minX = ((inputLeft / inputWidth) * protoWidth).toInt().coerceIn(0, protoWidth - 1)
        val maxX = ((inputRight / inputWidth) * protoWidth).toInt().coerceIn(minX, protoWidth - 1)
        val minY = ((inputTop / inputHeight) * protoHeight).toInt().coerceIn(0, protoHeight - 1)
        val maxY = ((inputBottom / inputHeight) * protoHeight).toInt().coerceIn(minY, protoHeight - 1)

        var activePixels = 0
        var totalPixels = 0
        var sumX = 0f
        var sumY = 0f

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                var logit = 0f
                val pixel = prototype[y][x]
                for (channel in 0 until protoChannels) {
                    logit += pixel[channel] * coefficients[channel]
                }
                val probability = sigmoid(logit)
                totalPixels += 1
                if (probability >= 0.5f) {
                    activePixels += 1
                    sumX += x + 0.5f
                    sumY += y + 0.5f
                }
            }
        }

        if (activePixels <= 0 || totalPixels <= 0) return null

        val centerInputX = (sumX / activePixels.toFloat() / protoWidth.toFloat()) * inputWidth
        val centerInputY = (sumY / activePixels.toFloat() / protoHeight.toFloat()) * inputHeight
        val centerOriginalX = ((centerInputX - padX) / scale).coerceIn(0f, imageWidth.toFloat())
        val centerOriginalY = ((centerInputY - padY) / scale).coerceIn(0f, imageHeight.toFloat())

        return SegmentSummary(
            coverageRatio = (activePixels / totalPixels.toFloat()).coerceIn(0f, 1f),
            centerXRatio = (centerOriginalX / imageWidth.toFloat()).coerceIn(0f, 1f),
            centerYRatio = (centerOriginalY / imageHeight.toFloat()).coerceIn(0f, 1f),
        )
    }

    private fun sigmoid(value: Float): Float {
        return (1.0 / (1.0 + exp(-value.toDouble()))).toFloat()
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
