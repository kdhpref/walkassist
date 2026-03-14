package com.example.walkassist

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    WalkAssistScreen()
                }
            }
        }
    }
}

@Composable
private fun WalkAssistScreen() {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    val pitchRadians = rememberPitchRadians()
    var analysis by remember { mutableStateOf(FrameAnalysis(emptyList(), null, null, null)) }

    if (!hasCameraPermission) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF08121C)),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Camera permission is required.", color = Color.White)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant permission")
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        CameraPreview(
            pitchRadiansProvider = { pitchRadians },
            onAnalysis = { analysis = it },
        )
        DetectionOverlay(
            analysis = analysis,
            pitchRadians = pitchRadians,
        )
        LiveInfoPanel(analysis = analysis)
        DebugPanel(analysis = analysis)
    }
}

@Composable
private fun rememberPitchRadians(): Float {
    val context = LocalContext.current
    var pitchRadians by remember { mutableFloatStateOf(0f) }

    DisposableEffect(context) {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rotationVectorSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val fallbackSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)

            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                    SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                    SensorManager.getOrientation(rotationMatrix, orientation)
                    pitchRadians = -orientation[1]
                } else {
                    val y = event.values[1].toDouble()
                    val z = event.values[2].toDouble()
                    pitchRadians = kotlin.math.atan2(z, y).toFloat()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        if (rotationVectorSensor != null) {
            sensorManager.registerListener(listener, rotationVectorSensor, SensorManager.SENSOR_DELAY_UI)
        } else if (fallbackSensor != null) {
            sensorManager.registerListener(listener, fallbackSensor, SensorManager.SENSOR_DELAY_UI)
        }

        onDispose {
            sensorManager.unregisterListener(listener)
        }
    }

    return pitchRadians
}

@Composable
private fun CameraPreview(
    pitchRadiansProvider: () -> Float,
    onAnalysis: (FrameAnalysis) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            val previewView = PreviewView(viewContext)
            val mainExecutor = ContextCompat.getMainExecutor(viewContext)
            val analysisExecutor = Executors.newSingleThreadExecutor()
            val analyzer = UnifiedFrameAnalyzer(
                context = viewContext,
                currentPitchRadians = pitchRadiansProvider,
                onAnalysis = onAnalysis,
            )

            cameraProviderFuture.addListener({
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also {
                        it.setAnalyzer(analysisExecutor, analyzer)
                    }

                try {
                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageAnalysis,
                    )
                } catch (exception: Exception) {
                    Log.e("CameraPreview", "Unable to bind camera use cases", exception)
                }
            }, mainExecutor)

            previewView
        },
    )
}

@Composable
private fun DetectionOverlay(
    analysis: FrameAnalysis,
    pitchRadians: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        analysis.detections.forEach { detection ->
            val left = detection.boundingBox.left * size.width / detection.imageWidth
            val top = detection.boundingBox.top * size.height / detection.imageHeight
            val right = detection.boundingBox.right * size.width / detection.imageWidth
            val bottom = detection.boundingBox.bottom * size.height / detection.imageHeight
            val overlayColor = when (detection.distanceEstimate.riskLevel) {
                RiskLevel.DANGER -> Color(0xFFFF5D73)
                RiskLevel.WARNING -> Color(0xFFF4C95D)
                RiskLevel.SAFE -> Color(0xFF4CD6A3)
            }

            drawRect(
                color = overlayColor,
                topLeft = Offset(left, top),
                size = Size(right - left, bottom - top),
                style = Stroke(width = if (detection.distanceEstimate.riskLevel == RiskLevel.DANGER) 6f else 4f),
            )

            val distance = detection.trackingState?.smoothedDistanceMeters
                ?: detection.distanceEstimate.distanceMeters
            val label = buildString {
                append(detection.label)
                append("  ")
                append(distance?.let(::formatMeters) ?: "distance unavailable")
            }

            val textPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.WHITE
                textSize = 30f
                isAntiAlias = true
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            val bgPaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(210, 15, 26, 35)
            }
            val textWidth = textPaint.measureText(label)
            drawContext.canvas.nativeCanvas.drawRoundRect(
                left,
                (top - 44f).coerceAtLeast(16f),
                left + textWidth + 24f,
                top,
                10f,
                10f,
                bgPaint,
            )
            drawContext.canvas.nativeCanvas.drawText(
                label,
                left + 12f,
                (top - 14f).coerceAtLeast(40f),
                textPaint,
            )
        }

        analysis.floorSegmentation?.let { floorSegmentation ->
            val linePaint = android.graphics.Paint().apply {
                color = android.graphics.Color.argb(180, 93, 202, 255)
                strokeWidth = 3f
                isAntiAlias = true
            }
            var previousX: Float? = null
            var previousY: Float? = null
            floorSegmentation.boundaryYByColumn.forEachIndexed { index, boundaryY ->
                if (boundaryY < 0) return@forEachIndexed
                val x = (index / (floorSegmentation.width - 1).toFloat()) * size.width
                val y = (boundaryY / floorSegmentation.height.toFloat()) * size.height
                if (previousX != null && previousY != null) {
                    drawContext.canvas.nativeCanvas.drawLine(previousX!!, previousY!!, x, y, linePaint)
                }
                previousX = x
                previousY = y
            }
        }

        val footerPaint = android.graphics.Paint().apply {
            color = android.graphics.Color.argb(220, 255, 255, 255)
            textSize = 22f
            isAntiAlias = true
        }
        drawContext.canvas.nativeCanvas.drawText(
            "Distance = floor segmentation + phone tilt + object ground contact",
            24f,
            size.height - 56f,
            footerPaint,
        )
        drawContext.canvas.nativeCanvas.drawText(
            "Pitch ${String.format("%.1f", Math.toDegrees(pitchRadians.toDouble()))} deg",
            24f,
            size.height - 24f,
            footerPaint,
        )
    }
}

@Composable
private fun LiveInfoPanel(
    analysis: FrameAnalysis,
) {
    val nearest = analysis.nearestObstacle
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(Color(0xCC08121C), RoundedCornerShape(18.dp))
                .padding(14.dp),
        ) {
            Text("Obstacle Distance", color = Color.White)
            Spacer(modifier = Modifier.height(8.dp))
            if (nearest == null) {
                Text(
                    "No major obstacle detected. Point the camera toward the path ahead.",
                    color = Color(0xFFD9E2EA),
                )
            } else {
                val smoothDistance = nearest.trackingState?.smoothedDistanceMeters
                val shownDistance = smoothDistance ?: nearest.distanceEstimate.distanceMeters
                Text(
                    "Nearest obstacle: ${nearest.label}",
                    color = Color.White,
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Distance: ${shownDistance?.let(::formatMeters) ?: "unavailable"}",
                    color = Color.White,
                )
                Text(
                    "Source: ${nearest.distanceEstimate.source.name.lowercase().replace('_', ' ')}  |  Quality: ${String.format("%.0f%%", nearest.distanceEstimate.qualityScore * 100f)}",
                    color = Color(0xFFD9E2EA),
                )
                Text(
                    "Confidence: ${String.format("%.0f%%", nearest.confidence * 100f)}  |  Track: #${nearest.trackingState?.trackId ?: 0}",
                    color = Color(0xFFD9E2EA),
                )
                Text(
                    when (nearest.distanceEstimate.riskLevel) {
                        RiskLevel.DANGER -> "Status: very close"
                        RiskLevel.WARNING -> "Status: caution"
                        RiskLevel.SAFE -> "Status: visible but not close"
                    },
                    color = when (nearest.distanceEstimate.riskLevel) {
                        RiskLevel.DANGER -> Color(0xFFFF9CA8)
                        RiskLevel.WARNING -> Color(0xFFFFE08B)
                        RiskLevel.SAFE -> Color(0xFF9AE7C7)
                    },
                )
            }
        }
    }
}

@Composable
private fun DebugPanel(
    analysis: FrameAnalysis,
) {
    val debugInfo = analysis.debugInfo ?: return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomStart,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .background(Color(0xCC08121C), RoundedCornerShape(18.dp))
                .padding(14.dp),
        ) {
            Text("Detection Debug", color = Color.White)
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Detector: ${if (debugInfo.detectorReady) "ready" else "not ready"}",
                color = Color(0xFFD9E2EA),
            )
            Text(
                "Model: ${debugInfo.modelInputSize}  |  Output: ${debugInfo.modelOutputShape}",
                color = Color(0xFFD9E2EA),
            )
            Text(
                "Frames: ${debugInfo.processedFrames}  |  Raw detections: ${debugInfo.rawDetectionCount}  |  Shown: ${debugInfo.trackedDetectionCount}",
                color = Color(0xFFD9E2EA),
            )
            Text(
                "Floor: ${debugInfo.floorMode}  |  Confidence: ${String.format("%.0f%%", debugInfo.floorConfidence * 100f)}",
                color = Color(0xFFD9E2EA),
            )
            Text(
                debugInfo.lastError ?: "Last error: none",
                color = if (debugInfo.lastError == null) Color(0xFF9AE7C7) else Color(0xFFFF9CA8),
            )
        }
    }
}

private fun formatMeters(distanceMeters: Float): String {
    return if (distanceMeters < 1f) {
        "${(distanceMeters * 100f).toInt()} cm"
    } else {
        String.format("%.2f m", distanceMeters)
    }
}
