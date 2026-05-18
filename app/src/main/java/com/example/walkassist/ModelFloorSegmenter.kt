package com.example.walkassist

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class ModelFloorSegmenter(
    context: Context,
) {
    private val heuristicSegmenter = FloorSegmenter()
    private val interpreter: Interpreter?
    private val gpuDelegate: GpuDelegate?
    private val inputHeight: Int
    private val inputWidth: Int
    private val outputHeight: Int
    private val outputWidth: Int
    private val outputClasses: Int
    private val inputBuffer: ByteBuffer?
    private val outputBuffer: ByteBuffer?
    private val walkableClasses = setOf(0, 1, 9) // road, sidewalk, terrain
    private var lastInferenceAtMs = 0L
    private var cachedResult: FloorSegmentationResult? = null
    var lastMode: String = "heuristic"
        private set

    init {
        var localInterpreter: Interpreter? = null
        var localGpuDelegate: GpuDelegate? = null
        var localInputHeight = 0
        var localInputWidth = 0
        var localOutputHeight = 0
        var localOutputWidth = 0
        var localOutputClasses = 0
        var localInputBuffer: ByteBuffer? = null
        var localOutputBuffer: ByteBuffer? = null

        try {
            val modelBuffer = TfliteAssetUtils.loadMappedAsset(context, "deeplabv3_cityscapes.tflite")
            val compatibilityList = CompatibilityList()
            val options = Interpreter.Options()
            if (compatibilityList.isDelegateSupportedOnThisDevice) {
                localGpuDelegate = GpuDelegate(compatibilityList.bestOptionsForThisDevice)
                options.addDelegate(localGpuDelegate)
                lastMode = "gpu-model"
            } else {
                options.setNumThreads(2)
                lastMode = "model"
            }
            localInterpreter = Interpreter(modelBuffer, options)
            val inputShape = localInterpreter.getInputTensor(0).shape()
            val outputShape = localInterpreter.getOutputTensor(0).shape()
            localInputHeight = inputShape[1]
            localInputWidth = inputShape[2]
            localOutputHeight = outputShape[1]
            localOutputWidth = outputShape[2]
            localOutputClasses = outputShape[3]
            localInputBuffer = ByteBuffer.allocateDirect(4 * localInputHeight * localInputWidth * 3)
                .order(ByteOrder.nativeOrder())
            localOutputBuffer = ByteBuffer.allocateDirect(4 * localOutputHeight * localOutputWidth * localOutputClasses)
                .order(ByteOrder.nativeOrder())
        } catch (exception: Exception) {
            localGpuDelegate?.close()
            localGpuDelegate = null
            Log.e("ModelFloorSegmenter", "Failed to initialize Cityscapes model", exception)
            lastMode = "heuristic-fallback"
        }

        interpreter = localInterpreter
        gpuDelegate = localGpuDelegate
        inputHeight = localInputHeight
        inputWidth = localInputWidth
        outputHeight = localOutputHeight
        outputWidth = localOutputWidth
        outputClasses = localOutputClasses
        inputBuffer = localInputBuffer
        outputBuffer = localOutputBuffer
    }

    fun segment(bitmap: Bitmap, nowMs: Long = System.currentTimeMillis()): FloorSegmentationResult {
        val localInterpreter = interpreter
        val localInputBuffer = inputBuffer
        val localOutputBuffer = outputBuffer
        if (localInterpreter == null || localInputBuffer == null || localOutputBuffer == null) {
            lastMode = "heuristic-fallback"
            return heuristicSegmenter.segment(bitmap)
        }

        // Throttle the heavy Cityscapes model and reuse the last good mask between inferences.
        if (cachedResult != null && nowMs - lastInferenceAtMs < 1200L) {
            lastMode = if (gpuDelegate != null) "cached-gpu-model" else "cached-model"
            return cachedResult!!
        }

        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, inputWidth, inputHeight, true)

            localInputBuffer.rewind()
            val pixels = IntArray(inputWidth * inputHeight)
            scaled.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            for (pixel in pixels) {
                localInputBuffer.putFloat((((pixel shr 16) and 0xFF) / 127.5f) - 1f)
                localInputBuffer.putFloat((((pixel shr 8) and 0xFF) / 127.5f) - 1f)
                localInputBuffer.putFloat(((pixel and 0xFF) / 127.5f) - 1f)
            }

            localOutputBuffer.rewind()
            localInterpreter.run(localInputBuffer, localOutputBuffer)
            val result = decodeSegmentation(
                output = localOutputBuffer.asFloatBuffer(),
                originalWidth = bitmap.width,
                originalHeight = bitmap.height,
            )
            cachedResult = result
            lastInferenceAtMs = nowMs
            lastMode = if (gpuDelegate != null) "gpu-model" else "model"

            scaled.recycle()
            result
        } catch (exception: Exception) {
            Log.e("ModelFloorSegmenter", "Model inference failed, using heuristic fallback", exception)
            lastMode = "heuristic-fallback"
            heuristicSegmenter.segment(bitmap)
        }
    }

    fun close() {
        interpreter?.close()
        gpuDelegate?.close()
    }

    private fun decodeSegmentation(
        output: FloatBuffer,
        originalWidth: Int,
        originalHeight: Int,
    ): FloorSegmentationResult {
        val targetWidth = 96
        val boundary = IntArray(targetWidth) { -1 }
        var validColumns = 0

        fun outputIndex(y: Int, x: Int, classIndex: Int): Int {
            return ((y * outputWidth + x) * outputClasses) + classIndex
        }

        fun bestClassAt(y: Int, x: Int): Int {
            var bestClass = 0
            var bestScore = Float.NEGATIVE_INFINITY
            for (classIndex in 0 until outputClasses) {
                val score = output.get(outputIndex(y, x, classIndex))
                if (score > bestScore) {
                    bestScore = score
                    bestClass = classIndex
                }
            }
            return bestClass
        }

        for (targetX in 0 until targetWidth) {
            val x = ((targetX / (targetWidth - 1).toFloat()) * (outputWidth - 1)).toInt()
            var foundTop = -1
            var streak = 0
            for (y in outputHeight - 1 downTo 0) {
                val bestClass = bestClassAt(y, x)
                if (bestClass in walkableClasses) {
                    foundTop = y
                    streak = 0
                } else if (foundTop >= 0) {
                    streak += 1
                    if (streak >= 2) {
                        break
                    }
                }
            }

            if (foundTop >= 0) {
                boundary[targetX] = (
                    (foundTop / (outputHeight - 1).toFloat()) * originalHeight.toFloat()
                    ).toInt().coerceIn(0, originalHeight - 1)
                validColumns += 1
            }
        }

        val maskWidth = 64
        val maskHeight = ((originalHeight / originalWidth.toFloat()) * maskWidth)
            .toInt()
            .coerceIn(48, 128)
        val classIds = IntArray(maskWidth * maskHeight)
        for (maskY in 0 until maskHeight) {
            val y = ((maskY / (maskHeight - 1).toFloat()) * (outputHeight - 1)).toInt()
            for (maskX in 0 until maskWidth) {
                val x = ((maskX / (maskWidth - 1).toFloat()) * (outputWidth - 1)).toInt()
                classIds[maskY * maskWidth + maskX] = bestClassAt(y, x)
            }
        }

        val confidence = (validColumns / targetWidth.toFloat()).coerceIn(0f, 1f)
        return FloorSegmentationResult(
            width = targetWidth,
            height = originalHeight,
            boundaryYByColumn = boundary,
            confidence = confidence,
            classMask = SemanticClassMask(
                width = maskWidth,
                height = maskHeight,
                classIds = classIds,
            ),
        )
    }
}
