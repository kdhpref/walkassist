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
    private val detector = ObjectAnalyzer(context)
    private val distanceEstimator = DistanceEstimator()
    private val floorSegmenter = ModelFloorSegmenter(context)
    private val tracker = ObjectTracker()
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
            val detections = detector.detect(bitmap).map { detection ->
                DetectedObjectResult(
                    boundingBox = detection.boundingBox,
                    confidence = detection.confidence,
                    imageHeight = detection.imageHeight,
                    imageWidth = detection.imageWidth,
                    label = detection.label,
                    distanceEstimate = distanceEstimator.estimate(
                        detection = detection,
                        pitchRadians = currentPitchRadians(),
                        floorSegmentation = floorSegmentation,
                    ),
                )
            }
            val trackedDetections = tracker.update(detections)
            mainHandler.post {
                onAnalysis(
                    FrameAnalysis(
                        detections = trackedDetections,
                        nearestObstacle = trackedDetections.firstOrNull(),
                        floorSegmentation = floorSegmentation,
                        debugInfo = AnalyzerDebugInfo(
                            detectorReady = detector.isReady(),
                            floorConfidence = floorSegmentation.confidence,
                            floorMode = floorSegmenter.lastMode,
                            modelInputSize = detector.modelInputSizeLabel(),
                            modelOutputShape = detector.modelOutputShapeLabel(),
                            processedFrames = frameCounter / 2,
                            rawDetectionCount = detector.lastRawDetectionCount,
                            trackedDetectionCount = trackedDetections.size,
                            lastError = detector.lastErrorMessage,
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
        detector.close()
    }
}
