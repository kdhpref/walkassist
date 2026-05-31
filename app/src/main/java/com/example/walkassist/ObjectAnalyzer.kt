package com.example.walkassist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RectF
import android.media.Image
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
        val leftXRatio: Float,
        val topYRatio: Float,
        val rightXRatio: Float,
        val bottomYRatio: Float,
        val polygon: List<SegmentMaskPoint>,
    )

    private data class PreparedInput(
        val inputBuffer: ByteBuffer,
        val imageWidth: Int,
        val imageHeight: Int,
        val padX: Float,
        val padY: Float,
        val scale: Float,
    )

    private enum class PrototypeLayout {
        NHWC,
        NCHW,
    }

    private data class PrototypeTensor(
        val data: FloatArray,
        val height: Int,
        val width: Int,
        val channels: Int,
        val layout: PrototypeLayout,
    ) {
        fun valueAt(y: Int, x: Int, channel: Int): Float {
            return when (layout) {
                PrototypeLayout.NHWC -> data[((y * width + x) * channels) + channel]
                PrototypeLayout.NCHW -> data[(channel * height * width) + (y * width) + x]
            }
        }
    }

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
    private var reusableInputBuffer: ByteBuffer? = null
    private var reusablePixels = IntArray(0)

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

    fun detect(
        bitmap: Bitmap,
        includeMaskPolygon: Boolean = false,
    ): List<RawDetection> {
        val localInterpreter = interpreter ?: return emptyList()

        val inputTensor = localInterpreter.getInputTensor(0)
        val isQuantized =
            inputTensor.dataType() == DataType.UINT8 || inputTensor.dataType() == DataType.INT8
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
            val inputBuffer = if (isQuantized) {
                createQuantizedRgbInputBuffer(paddedBitmap)
            } else {
                createFloatRgbInputBuffer(paddedBitmap)
            }

            val detections = runInference(
                interpreter = localInterpreter,
                inputBuffer = inputBuffer,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
                padX = padX,
                padY = padY,
                scale = scale,
                includeMaskPolygon = includeMaskPolygon,
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

    fun detect(
        image: Image,
        rotationDegrees: Int,
        includeMaskPolygon: Boolean = false,
    ): List<RawDetection> {
        val localInterpreter = interpreter ?: return emptyList()
        return try {
            val inputTensor = localInterpreter.getInputTensor(0)
            val isQuantized =
                inputTensor.dataType() == DataType.UINT8 || inputTensor.dataType() == DataType.INT8
            val preparedInput = createYuvInputBuffer(
                image = image,
                rotationDegrees = rotationDegrees,
                isQuantized = isQuantized,
            )
            val detections = runInference(
                interpreter = localInterpreter,
                inputBuffer = preparedInput.inputBuffer,
                imageWidth = preparedInput.imageWidth,
                imageHeight = preparedInput.imageHeight,
                padX = preparedInput.padX,
                padY = preparedInput.padY,
                scale = preparedInput.scale,
                includeMaskPolygon = includeMaskPolygon,
            )
            lastFinalDetectionCount = detections.size
            lastErrorMessage = null
            detections
        } catch (exception: Exception) {
            lastErrorMessage = exception.message
            Log.e("ObjectAnalyzer", "Failed to run detection from YUV image", exception)
            emptyList()
        }
    }

    fun close() {
        interpreter?.close()
    }

    private fun runInference(
        interpreter: Interpreter,
        inputBuffer: ByteBuffer,
        imageWidth: Int,
        imageHeight: Int,
        padX: Float,
        padY: Float,
        scale: Float,
        includeMaskPolygon: Boolean,
    ): List<RawDetection> {
        if (isEndToEndSegmentationOutput(interpreter)) {
            return detectEndToEndSegmentation(
                interpreter = interpreter,
                inputBuffer = inputBuffer,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                padX = padX,
                padY = padY,
                scale = scale,
                includeMaskPolygon = includeMaskPolygon,
            )
        }

        val outputTensors = (0 until interpreter.getOutputTensorCount()).map { index ->
            index to interpreter.getOutputTensor(index).shape()
        }
        val detectionOutput = outputTensors.firstOrNull { (_, shape) ->
            shape.size == 3 && shape[0] == 1
        } ?: run {
            lastErrorMessage = "Unexpected output shapes ${
                outputTensors.joinToString { (_, shape) -> shape.joinToString(prefix = "[", postfix = "]") }
            }"
            return emptyList()
        }
        val prototypeOutput = outputTensors.firstOrNull { (_, shape) ->
            isPrototypeShape(shape)
        }

        val outputBuffers = outputTensors.associate { (index, shape) ->
            index to ByteBuffer.allocateDirect(shape.product() * FLOAT_BYTES)
                .order(ByteOrder.nativeOrder())
        }
        if (outputBuffers.size == 1) {
            val outputBuffer = outputBuffers.getValue(detectionOutput.first)
            interpreter.run(inputBuffer.rewind(), outputBuffer.rewind())
        } else {
            outputBuffers.values.forEach(ByteBuffer::rewind)
            val outputMap = HashMap<Int, Any>()
            outputBuffers.forEach { (index, buffer) -> outputMap[index] = buffer }
            interpreter.runForMultipleInputsOutputs(arrayOf<Any>(inputBuffer.rewind() as ByteBuffer), outputMap)
        }

        val prototype = prototypeOutput?.let { (index, shape) ->
            createPrototypeTensor(
                outputArray = outputBuffers.getValue(index).toFloatArray(),
                shape = shape,
            )
        }
        val detectionShape = detectionOutput.second
        return parseDetections(
            outputArray = outputBuffers.getValue(detectionOutput.first).toFloatArray(),
            dim1 = detectionShape[1],
            dim2 = detectionShape[2],
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            padX = padX,
            padY = padY,
            scale = scale,
            prototype = prototype,
            includeMaskPolygon = includeMaskPolygon,
        )
    }

    private fun createQuantizedRgbInputBuffer(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = obtainInputBuffer(inputWidth * inputHeight * CHANNELS_RGB)
        val pixels = obtainPixelBuffer(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        inputBuffer.rewind()
        for (pixel in pixels) {
            inputBuffer.put(((pixel shr 16) and 0xFF).toByte())
            inputBuffer.put(((pixel shr 8) and 0xFF).toByte())
            inputBuffer.put((pixel and 0xFF).toByte())
        }
        return inputBuffer.rewind() as ByteBuffer
    }

    private fun createFloatRgbInputBuffer(bitmap: Bitmap): ByteBuffer {
        val inputBuffer = obtainInputBuffer(inputWidth * inputHeight * CHANNELS_RGB * FLOAT_BYTES)
        val pixels = obtainPixelBuffer(inputWidth * inputHeight)
        bitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        inputBuffer.rewind()
        for (pixel in pixels) {
            inputBuffer.putFloat((((pixel shr 16) and 0xFF) / 255f))
            inputBuffer.putFloat((((pixel shr 8) and 0xFF) / 255f))
            inputBuffer.putFloat(((pixel and 0xFF) / 255f))
        }
        return inputBuffer.rewind() as ByteBuffer
    }

    private fun createYuvInputBuffer(
        image: Image,
        rotationDegrees: Int,
        isQuantized: Boolean,
    ): PreparedInput {
        val normalizedRotation = normalizeRotationDegrees(rotationDegrees)
        val uprightWidth = if (normalizedRotation == 90 || normalizedRotation == 270) {
            image.height
        } else {
            image.width
        }
        val uprightHeight = if (normalizedRotation == 90 || normalizedRotation == 270) {
            image.width
        } else {
            image.height
        }
        val scale = minOf(
            inputWidth.toFloat() / uprightWidth.toFloat(),
            inputHeight.toFloat() / uprightHeight.toFloat(),
        )
        val scaledWidth = (uprightWidth * scale).roundToInt().coerceAtLeast(1)
        val scaledHeight = (uprightHeight * scale).roundToInt().coerceAtLeast(1)
        val padX = (inputWidth - scaledWidth) / 2f
        val padY = (inputHeight - scaledHeight) / 2f
        val bytesPerChannel = if (isQuantized) 1 else FLOAT_BYTES
        val inputBuffer = obtainInputBuffer(inputWidth * inputHeight * CHANNELS_RGB * bytesPerChannel)
        inputBuffer.rewind()

        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        for (inputY in 0 until inputHeight) {
            for (inputX in 0 until inputWidth) {
                val insideContent = inputX >= padX &&
                    inputX < padX + scaledWidth &&
                    inputY >= padY &&
                    inputY < padY + scaledHeight
                if (!insideContent) {
                    putRgb(inputBuffer, 0, 0, 0, isQuantized)
                    continue
                }

                val uprightX = ((inputX - padX + 0.5f) / scale) - 0.5f
                val uprightY = ((inputY - padY + 0.5f) / scale) - 0.5f
                val source = sourcePointFromUpright(
                    uprightX = uprightX,
                    uprightY = uprightY,
                    imageWidth = image.width,
                    imageHeight = image.height,
                    rotationDegrees = normalizedRotation,
                )
                val sourceX = source.first.roundToInt().coerceIn(0, image.width - 1)
                val sourceY = source.second.roundToInt().coerceIn(0, image.height - 1)
                val yValue = yBuffer.getUnsigned((sourceY * yPlane.rowStride) + (sourceX * yPlane.pixelStride))
                val uOffset = ((sourceY / 2) * uPlane.rowStride) + ((sourceX / 2) * uPlane.pixelStride)
                val vOffset = ((sourceY / 2) * vPlane.rowStride) + ((sourceX / 2) * vPlane.pixelStride)
                val argb = yuvToArgb(
                    yValue = yValue,
                    uValue = uBuffer.getUnsigned(uOffset),
                    vValue = vBuffer.getUnsigned(vOffset),
                )
                putRgb(
                    inputBuffer = inputBuffer,
                    red = (argb shr 16) and 0xFF,
                    green = (argb shr 8) and 0xFF,
                    blue = argb and 0xFF,
                    isQuantized = isQuantized,
                )
            }
        }

        return PreparedInput(
            inputBuffer = inputBuffer.rewind() as ByteBuffer,
            imageWidth = uprightWidth,
            imageHeight = uprightHeight,
            padX = padX,
            padY = padY,
            scale = scale,
        )
    }

    private fun obtainInputBuffer(capacity: Int): ByteBuffer {
        val current = reusableInputBuffer
        if (current != null && current.capacity() == capacity) {
            return current
        }
        return ByteBuffer.allocateDirect(capacity)
            .order(ByteOrder.nativeOrder())
            .also { reusableInputBuffer = it }
    }

    private fun obtainPixelBuffer(size: Int): IntArray {
        if (reusablePixels.size < size) {
            reusablePixels = IntArray(size)
        }
        return reusablePixels
    }

    private fun putRgb(
        inputBuffer: ByteBuffer,
        red: Int,
        green: Int,
        blue: Int,
        isQuantized: Boolean,
    ) {
        if (isQuantized) {
            inputBuffer.put(red.toByte())
            inputBuffer.put(green.toByte())
            inputBuffer.put(blue.toByte())
        } else {
            inputBuffer.putFloat(red / 255f)
            inputBuffer.putFloat(green / 255f)
            inputBuffer.putFloat(blue / 255f)
        }
    }

    private fun normalizeRotationDegrees(rotationDegrees: Int): Int {
        return ((rotationDegrees % 360) + 360) % 360
    }

    private fun sourcePointFromUpright(
        uprightX: Float,
        uprightY: Float,
        imageWidth: Int,
        imageHeight: Int,
        rotationDegrees: Int,
    ): Pair<Float, Float> {
        return when (rotationDegrees) {
            90 -> uprightY to (imageHeight - 1) - uprightX
            180 -> (imageWidth - 1) - uprightX to (imageHeight - 1) - uprightY
            270 -> (imageWidth - 1) - uprightY to uprightX
            else -> uprightX to uprightY
        }
    }

    private fun ByteBuffer.getUnsigned(index: Int): Int {
        return get(index).toInt() and 0xFF
    }

    private fun yuvToArgb(
        yValue: Int,
        uValue: Int,
        vValue: Int,
    ): Int {
        val y = (yValue - 16).coerceAtLeast(0)
        val u = uValue - 128
        val v = vValue - 128

        val y1192 = 1192 * y
        val red = clampRgb((y1192 + (1634 * v)) shr 10)
        val green = clampRgb((y1192 - (833 * v) - (400 * u)) shr 10)
        val blue = clampRgb((y1192 + (2066 * u)) shr 10)
        return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun clampRgb(value: Int): Int {
        return value.coerceIn(0, 255)
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

    private fun isPrototypeShape(shape: IntArray): Boolean {
        if (shape.size != 4 || shape[0] != 1) return false
        val nhwc = shape[1] > 1 && shape[2] > 1 && shape[3] in 8..128
        val nchw = shape[1] in 8..128 && shape[2] > 1 && shape[3] > 1
        return nhwc || nchw
    }

    private fun createPrototypeTensor(
        outputArray: FloatArray,
        shape: IntArray,
    ): PrototypeTensor? {
        if (!isPrototypeShape(shape)) return null
        return if (shape[3] in 8..128) {
            PrototypeTensor(
                data = outputArray,
                height = shape[1],
                width = shape[2],
                channels = shape[3],
                layout = PrototypeLayout.NHWC,
            )
        } else {
            PrototypeTensor(
                data = outputArray,
                height = shape[2],
                width = shape[3],
                channels = shape[1],
                layout = PrototypeLayout.NCHW,
            )
        }
    }

    private fun detectEndToEndSegmentation(
        interpreter: Interpreter,
        inputBuffer: java.nio.ByteBuffer,
        imageWidth: Int,
        imageHeight: Int,
        padX: Float,
        padY: Float,
        scale: Float,
        includeMaskPolygon: Boolean,
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
            includeMaskPolygon = includeMaskPolygon,
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
        includeMaskPolygon: Boolean,
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
                includePolygon = includeMaskPolygon,
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
                segmentLeftXRatio = segmentSummary?.leftXRatio,
                segmentTopYRatio = segmentSummary?.topYRatio,
                segmentRightXRatio = segmentSummary?.rightXRatio,
                segmentBottomYRatio = segmentSummary?.bottomYRatio,
                segmentPolygon = segmentSummary?.polygon.orEmpty(),
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
        includePolygon: Boolean,
    ): SegmentSummary? {
        val protoChannels = prototype.firstOrNull()?.firstOrNull()?.size ?: return null
        return computeSegmentSummary(
            protoHeight = prototype.size,
            protoWidth = prototype.firstOrNull()?.size ?: return null,
            protoChannels = protoChannels,
            coefficients = coefficients,
            inputLeft = inputLeft,
            inputTop = inputTop,
            inputRight = inputRight,
            inputBottom = inputBottom,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            padX = padX,
            padY = padY,
            scale = scale,
            includePolygon = includePolygon,
        ) { y, x, channel ->
            prototype[y][x][channel]
        }
    }

    private fun computeSegmentSummary(
        prototype: PrototypeTensor,
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
        includePolygon: Boolean,
    ): SegmentSummary? {
        return computeSegmentSummary(
            protoHeight = prototype.height,
            protoWidth = prototype.width,
            protoChannels = prototype.channels,
            coefficients = coefficients,
            inputLeft = inputLeft,
            inputTop = inputTop,
            inputRight = inputRight,
            inputBottom = inputBottom,
            imageWidth = imageWidth,
            imageHeight = imageHeight,
            padX = padX,
            padY = padY,
            scale = scale,
            includePolygon = includePolygon,
        ) { y, x, channel ->
            prototype.valueAt(y, x, channel)
        }
    }

    private fun computeSegmentSummary(
        protoHeight: Int,
        protoWidth: Int,
        protoChannels: Int,
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
        includePolygon: Boolean,
        prototypeValueAt: (y: Int, x: Int, channel: Int) -> Float,
    ): SegmentSummary? {
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
        var activeMinX = protoWidth
        var activeMaxX = -1
        var activeMinY = protoHeight
        var activeMaxY = -1
        val rowMinX = if (includePolygon) IntArray(protoHeight) { Int.MAX_VALUE } else null
        val rowMaxX = if (includePolygon) IntArray(protoHeight) { -1 } else null

        for (y in minY..maxY) {
            for (x in minX..maxX) {
                var logit = 0f
                for (channel in 0 until protoChannels) {
                    logit += prototypeValueAt(y, x, channel) * coefficients[channel]
                }
                totalPixels += 1
                if (sigmoid(logit) >= 0.5f) {
                    activePixels += 1
                    sumX += x + 0.5f
                    sumY += y + 0.5f
                    activeMinX = minOf(activeMinX, x)
                    activeMaxX = maxOf(activeMaxX, x)
                    activeMinY = minOf(activeMinY, y)
                    activeMaxY = maxOf(activeMaxY, y)
                    if (rowMinX != null && rowMaxX != null) {
                        rowMinX[y] = minOf(rowMinX[y], x)
                        rowMaxX[y] = maxOf(rowMaxX[y], x)
                    }
                }
            }
        }

        if (activePixels <= 0 || totalPixels <= 0) return null

        fun originalX(protoX: Float): Float {
            return (((protoX / protoWidth.toFloat()) * inputWidth - padX) / scale)
                .coerceIn(0f, imageWidth.toFloat())
        }

        fun originalY(protoY: Float): Float {
            return (((protoY / protoHeight.toFloat()) * inputHeight - padY) / scale)
                .coerceIn(0f, imageHeight.toFloat())
        }

        val centerOriginalX = originalX(sumX / activePixels.toFloat())
        val centerOriginalY = originalY(sumY / activePixels.toFloat())
        val leftOriginalX = originalX(activeMinX + 0.5f)
        val rightOriginalX = originalX(activeMaxX + 0.5f)
        val topOriginalY = originalY(activeMinY + 0.5f)
        val bottomOriginalY = originalY(activeMaxY + 0.5f)

        return SegmentSummary(
            coverageRatio = (activePixels / totalPixels.toFloat()).coerceIn(0f, 1f),
            centerXRatio = (centerOriginalX / imageWidth.toFloat()).coerceIn(0f, 1f),
            centerYRatio = (centerOriginalY / imageHeight.toFloat()).coerceIn(0f, 1f),
            leftXRatio = (leftOriginalX / imageWidth.toFloat()).coerceIn(0f, 1f),
            topYRatio = (topOriginalY / imageHeight.toFloat()).coerceIn(0f, 1f),
            rightXRatio = (rightOriginalX / imageWidth.toFloat()).coerceIn(0f, 1f),
            bottomYRatio = (bottomOriginalY / imageHeight.toFloat()).coerceIn(0f, 1f),
            polygon = if (rowMinX != null && rowMaxX != null) {
                buildSegmentPolygon(
                    rowMinX = rowMinX,
                    rowMaxX = rowMaxX,
                    activeMinY = activeMinY,
                    activeMaxY = activeMaxY,
                    protoWidth = protoWidth,
                    protoHeight = protoHeight,
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    padX = padX,
                    padY = padY,
                    scale = scale,
                )
            } else {
                emptyList()
            },
        )
    }

    private fun buildSegmentPolygon(
        rowMinX: IntArray,
        rowMaxX: IntArray,
        activeMinY: Int,
        activeMaxY: Int,
        protoWidth: Int,
        protoHeight: Int,
        imageWidth: Int,
        imageHeight: Int,
        padX: Float,
        padY: Float,
        scale: Float,
    ): List<SegmentMaskPoint> {
        if (activeMinY < 0 || activeMaxY < activeMinY) return emptyList()
        val validRows = (activeMinY..activeMaxY).filter { row ->
            rowMinX[row] != Int.MAX_VALUE && rowMaxX[row] >= rowMinX[row]
        }
        if (validRows.size < 2) return emptyList()
        val step = (validRows.size / 14).coerceAtLeast(1)
        val sampledRows = validRows.filterIndexed { index, _ -> index % step == 0 }
            .let { rows ->
                if (rows.last() == validRows.last()) rows else rows + validRows.last()
            }

        fun point(x: Int, y: Int): SegmentMaskPoint {
            val originalX = ((((x + 0.5f) / protoWidth.toFloat()) * inputWidth - padX) / scale)
                .coerceIn(0f, imageWidth.toFloat())
            val originalY = ((((y + 0.5f) / protoHeight.toFloat()) * inputHeight - padY) / scale)
                .coerceIn(0f, imageHeight.toFloat())
            return SegmentMaskPoint(
                xRatio = (originalX / imageWidth.toFloat()).coerceIn(0f, 1f),
                yRatio = (originalY / imageHeight.toFloat()).coerceIn(0f, 1f),
            )
        }

        val leftEdge = sampledRows.map { row -> point(rowMinX[row], row) }
        val rightEdge = sampledRows.asReversed().map { row -> point(rowMaxX[row], row) }
        return (leftEdge + rightEdge).distinctAdjacent()
    }

    private fun List<SegmentMaskPoint>.distinctAdjacent(): List<SegmentMaskPoint> {
        if (isEmpty()) return this
        val result = ArrayList<SegmentMaskPoint>(size)
        for (point in this) {
            val last = result.lastOrNull()
            if (last == null || kotlin.math.abs(last.xRatio - point.xRatio) > 0.003f ||
                kotlin.math.abs(last.yRatio - point.yRatio) > 0.003f
            ) {
                result += point
            }
        }
        return result
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
        prototype: PrototypeTensor? = null,
        includeMaskPolygon: Boolean = false,
    ): List<RawDetection> {
        val candidates = mutableListOf<RawDetection>()
        val confidenceThreshold = 0.25f
        val iouThreshold = 0.45f
        val channelsFirst = dim1 <= dim2
        val channelCount = minOf(dim1, dim2)
        val boxCount = maxOf(dim1, dim2)
        val maskCoefficientCount = prototype?.channels ?: 0
        val classStartIndex = when {
            channelCount >= labels.size + 5 + maskCoefficientCount -> 5
            channelCount >= labels.size + 4 + maskCoefficientCount -> 4
            channelCount >= labels.size + 5 -> 5
            channelCount >= labels.size + 4 -> 4
            else -> {
                lastRawDetectionCount = 0
                lastErrorMessage = "Unsupported output channels: $channelCount"
                return emptyList()
            }
        }
        val classEndIndex = minOf(classStartIndex + labels.size, channelCount - maskCoefficientCount)
        if (classEndIndex <= classStartIndex) {
            lastRawDetectionCount = 0
            lastErrorMessage = "Unsupported class channels: $channelCount"
            return emptyList()
        }
        val coefficientStartIndex = if (
            prototype != null &&
            channelCount - classEndIndex >= prototype.channels
        ) {
            channelCount - prototype.channels
        } else {
            -1
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

            for (channel in classStartIndex until classEndIndex) {
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

            val segmentSummary = if (prototype != null && coefficientStartIndex >= 0) {
                val coefficients = FloatArray(prototype.channels) { index ->
                    valueAt(coefficientStartIndex + index, box)
                }
                computeSegmentSummary(
                    prototype = prototype,
                    coefficients = coefficients,
                    inputLeft = boxCenterX - (boxWidth / 2f),
                    inputTop = boxCenterY - (boxHeight / 2f),
                    inputRight = boxCenterX + (boxWidth / 2f),
                    inputBottom = boxCenterY + (boxHeight / 2f),
                    imageWidth = imageWidth,
                    imageHeight = imageHeight,
                    padX = padX,
                    padY = padY,
                    scale = scale,
                    includePolygon = includeMaskPolygon,
                )
            } else {
                null
            }
            val label = labels.getOrNull(bestClassId) ?: "class_$bestClassId"
            candidates += RawDetection(
                boundingBox = RectF(left, top, right, bottom),
                confidence = confidence,
                imageHeight = imageHeight,
                imageWidth = imageWidth,
                label = label,
                segmentCoverageRatio = segmentSummary?.coverageRatio,
                segmentCenterXRatio = segmentSummary?.centerXRatio,
                segmentCenterYRatio = segmentSummary?.centerYRatio,
                segmentLeftXRatio = segmentSummary?.leftXRatio,
                segmentTopYRatio = segmentSummary?.topYRatio,
                segmentRightXRatio = segmentSummary?.rightXRatio,
                segmentBottomYRatio = segmentSummary?.bottomYRatio,
                segmentPolygon = segmentSummary?.polygon.orEmpty(),
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
