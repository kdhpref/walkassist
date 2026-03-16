package com.example.walkassist

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy

class UnifiedFrameAnalyzer(
    context: Context,
    private val currentPitchRadians: () -> Float,
    private val onAnalysis: (FrameAnalysis) -> Unit,
) : ImageAnalysis.Analyzer {
    private val floorSegmenter = ModelFloorSegmenter(context)
    private val pathAnalyzer = PathAnalyzer()
    private val tracker = FreeSpaceTracker()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameCounter = 0

    override fun analyze(imageProxy: ImageProxy) {
        val bitmap = imageProxy.toUprightBitmap()
        try {
            frameCounter += 1
            if (frameCounter % 2 != 0) {
                return
            }

            val floorSegmentation = floorSegmenter.segment(bitmap)
            val pitchRadians = currentPitchRadians()
            val rawPathMetrics = pathAnalyzer.analyze(
                bitmap = bitmap,
                floorSegmentation = floorSegmentation,
                pitchRadians = pitchRadians,
                imageWidth = bitmap.width,
                imageHeight = bitmap.height,
            )
            val (smoothedSegmentation, pathMetrics) = tracker.update(
                floorSegmentation = floorSegmentation,
                pathMetrics = rawPathMetrics,
                timestampNanos = imageProxy.imageInfo.timestamp,
            )
            mainHandler.post {
                onAnalysis(
                    FrameAnalysis(
                        detections = emptyList(),
                        nearestObstacle = null,
                        floorSegmentation = smoothedSegmentation,
                        pathMetrics = pathMetrics,
                        debugInfo = AnalyzerDebugInfo(
                            detectorReady = true,
                            floorConfidence = smoothedSegmentation.confidence,
                            floorMode = floorSegmenter.lastMode,
                            modelInputSize = "free-space-ipm",
                            modelOutputShape = "path-metrics",
                            processedFrames = frameCounter / 2,
                            rawDetectionCount = 0,
                            trackedDetectionCount = 0,
                            lastError = null,
                        ),
                    ),
                )
            }
        } catch (exception: Exception) {
            Log.e("UnifiedFrameAnalyzer", "Frame analysis failed", exception)
        } finally {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
            imageProxy.close()
        }
    }

    fun close() {
        Unit
    }
}
