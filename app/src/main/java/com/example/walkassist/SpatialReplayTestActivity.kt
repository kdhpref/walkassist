package com.example.walkassist

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.example.walkassist.feedback.core.FeedbackInput
import com.example.walkassist.feedback.core.FeedbackSensorStatus
import com.example.walkassist.feedback.engine.FeedbackViewModel
import com.example.walkassist.feedback.runtime.FeedbackManager
import com.example.walkassist.feedback.ui.FeedbackOverlayCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SpatialReplayTestActivity : AppCompatActivity() {
    private val feedbackViewModel by viewModels<FeedbackViewModel>()
    private val analyzer by lazy { VideoFrameAnalyzer(applicationContext) }
    private val currentVideoUri = mutableStateOf<Uri?>(null)
    private val currentResult = mutableStateOf<VideoFrameAnalysisResult?>(null)
    private val currentMeasurementState = mutableStateOf(ArMeasurementState())
    private val statusMessage = mutableStateOf("분석할 영상을 선택하세요.")
    private val progress = mutableFloatStateOf(0f)
    private val processingFps = mutableFloatStateOf(0f)
    private var replayJob: Job? = null
    private var analysisStartedForUri: String? = null
    private var pickerLaunched = false
    private lateinit var feedbackManager: FeedbackManager

    private val pickVideo = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            statusMessage.value = "영상 선택이 취소되었습니다."
            feedbackViewModel.onInput(FeedbackInput.SensorStatus(FeedbackSensorStatus.WAITING))
            return@registerForActivityResult
        }
        persistReadPermission(uri)
        startReplay(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feedbackManager = FeedbackManager(this)
        savedInstanceState?.getString(KEY_VIDEO_URI)?.let { savedUri ->
            Uri.parse(savedUri)?.let { uri ->
                currentVideoUri.value = uri
            }
        }
        feedbackViewModel.startWatchdog()
        bindReplayFeedbackToRuntime()
        setContent {
            MaterialTheme {
                SpatialReplayTestScreen(
                    videoUri = currentVideoUri.value,
                    resultState = currentResult,
                    measurementState = currentMeasurementState.value,
                    statusMessage = statusMessage.value,
                    progress = progress.floatValue,
                    processingFps = processingFps.floatValue,
                    feedbackViewModel = feedbackViewModel,
                    onPickVideo = { pickVideo.launch(arrayOf("video/*")) },
                    onVideoReady = { uri -> startAnalysisIfNeeded(uri) },
                    onVideoError = {
                        statusMessage.value = "영상을 재생하지 못했습니다. 다른 영상 파일을 선택하세요."
                    },
                    onClose = {
                        finish()
                    },
                )
            }
        }
        statusMessage.value = if (currentVideoUri.value == null) {
            "영상 테스트는 라이브 AR 카메라 대신 선택한 영상을 분석합니다."
        } else {
            "저장된 영상을 불러왔습니다. 분석을 시작합니다."
        }
        if (savedInstanceState == null && currentVideoUri.value == null && !pickerLaunched) {
            pickerLaunched = true
            pickVideo.launch(arrayOf("video/*"))
        }
    }

    private fun persistReadPermission(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun startReplay(uri: Uri) {
        replayJob?.cancel()
        analysisStartedForUri = null
        currentVideoUri.value = uri
        currentResult.value = null
        progress.floatValue = 0f
        processingFps.floatValue = 0f
        statusMessage.value = "영상을 불러왔습니다. 자동으로 분석을 시작합니다."
    }

    private fun startAnalysisIfNeeded(uri: Uri) {
        val uriKey = uri.toString()
        if (analysisStartedForUri == uriKey && replayJob?.isActive == true) return
        analysisStartedForUri = uriKey

        replayJob?.cancel()
        currentResult.value = null
        progress.floatValue = 0f
        processingFps.floatValue = 0f
        statusMessage.value = "라이브 카메라 대신 영상 프레임을 분석 중입니다."

        replayJob = lifecycleScope.launch {
            val source = VideoReplayFrameSource(
                context = applicationContext,
                uri = uri,
            )
            var processedFrames = 0
            val startedAtMs = System.currentTimeMillis()
            try {
                source.replay { frame ->
                    val result = withContext(Dispatchers.Default) {
                        analyzer.analyze(
                            bitmap = frame.bitmap,
                            frameTimeMs = frame.timeMs,
                        )
                    }
                    frame.bitmap.recycle()
                    withContext(Dispatchers.Main) {
                        processedFrames += 1
                        currentResult.value = result
                        currentMeasurementState.value = result.measurementState
                        ArMeasurementBridge.publish(result.measurementState)
                        progress.floatValue = if (frame.durationMs > 0L) {
                            (frame.timeMs / frame.durationMs.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }
                        val elapsedSeconds = ((System.currentTimeMillis() - startedAtMs) / 1000f)
                            .coerceAtLeast(0.1f)
                        processingFps.floatValue = processedFrames / elapsedSeconds
                        feedbackViewModel.onInput(result.feedbackInput)
                        statusMessage.value =
                            "영상 분석 중입니다. 이 테스트에서는 라이브 AR 카메라를 사용하지 않습니다."
                    }
                }
                statusMessage.value = "영상 분석이 끝났습니다. 영상 재생은 반복됩니다."
            } catch (_: CancellationException) {
                statusMessage.value = "영상 분석을 중지했습니다."
            } catch (error: Exception) {
                statusMessage.value = "영상 테스트 오류: ${error.message ?: "알 수 없는 오류"}"
                feedbackViewModel.onInput(FeedbackInput.SensorStatus(FeedbackSensorStatus.ERROR))
            }
        }
    }

    override fun onDestroy() {
        replayJob?.cancel()
        analyzer.close()
        if (::feedbackManager.isInitialized) {
            feedbackManager.release()
        }
        super.onDestroy()
    }

    private fun bindReplayFeedbackToRuntime() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                feedbackViewModel.uiState.collect { feedbackState ->
                    if (feedbackState.shouldAnnounce) {
                        feedbackManager.provideFeedback(
                            message = feedbackState.message,
                            level = feedbackState.alertLevel,
                        )
                        feedbackViewModel.consumeAnnouncement()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        currentVideoUri.value?.let { outState.putString(KEY_VIDEO_URI, it.toString()) }
        super.onSaveInstanceState(outState)
    }

    companion object {
        private const val KEY_VIDEO_URI = "video_uri"
    }
}

@Composable
private fun SpatialReplayTestScreen(
    videoUri: Uri?,
    resultState: MutableState<VideoFrameAnalysisResult?>,
    measurementState: ArMeasurementState,
    statusMessage: String,
    progress: Float,
    processingFps: Float,
    feedbackViewModel: FeedbackViewModel,
    onPickVideo: () -> Unit,
    onVideoReady: (Uri) -> Unit,
    onVideoError: () -> Unit,
    onClose: () -> Unit,
) {
    val feedbackState by feedbackViewModel.uiState.collectAsState()
    val result = resultState.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (videoUri != null) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    VideoView(context).apply {
                        setVideoURI(videoUri)
                        tag = videoUri
                        setOnPreparedListener { player ->
                            player.isLooping = true
                            start()
                            onVideoReady(videoUri)
                        }
                        setOnErrorListener { _, _, _ ->
                            onVideoError()
                            true
                        }
                    }
                },
                update = { view ->
                    if (view.tag != videoUri) {
                        view.setVideoURI(videoUri)
                        view.tag = videoUri
                        view.setOnPreparedListener { player ->
                            player.isLooping = true
                            view.start()
                            onVideoReady(videoUri)
                        }
                        view.setOnErrorListener { _, _, _ ->
                            onVideoError()
                            true
                        }
                    } else if (!view.isPlaying) {
                        view.start()
                    }
                },
            )
            FloorBoundaryOverlay(
                result = result,
                modifier = Modifier.fillMaxSize(),
            )
            ReplayDetectionOverlay(
                detections = measurementState.objectDetections,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "영상 테스트",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.Center),
            )
        }

        ReplayInfoPanel(
            result = result,
            statusMessage = statusMessage,
            progress = progress,
            processingFps = processingFps,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp),
        )

        FeedbackOverlayCard(
            state = feedbackState,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 18.dp),
        )

        WalkingGuidancePanel(
            state = measurementState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp),
            horizontalAlignment = Alignment.End,
        ) {
            ReplayChip(text = "영상 선택", onClick = onPickVideo)
            Spacer(modifier = Modifier.height(10.dp))
            ReplayChip(text = "닫기", onClick = onClose)
        }
    }
}

@Composable
private fun ReplayInfoPanel(
    result: VideoFrameAnalysisResult?,
    statusMessage: String,
    progress: Float,
    processingFps: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(270.dp)
            .background(Color(0xB8121820), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = "영상 테스트",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(statusMessage, color = Color(0xFFD8E3EE), fontSize = 12.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "프레임=${result?.frameTimeMs ?: 0}ms  분석=${String.format("%.1f", processingFps)}fps",
            color = Color(0xFFD8E3EE),
            fontSize = 12.sp,
        )
        Text(
            text = "분석 진행률 ${(progress * 100f).toInt()}%",
            color = Color(0xFFD8E3EE),
            fontSize = 12.sp,
        )
        Text(
            text = "초록 선: 바닥과 장애물/벽이 갈리는 경계 추정",
            color = Color(0xFF9BE7B6),
            fontSize = 12.sp,
        )
        result?.let {
            Spacer(modifier = Modifier.height(8.dp))
            Text(it.summary, color = Color(0xFFB7F7CE), fontSize = 12.sp)
            Text(
                text = "탐지=${it.debugInfo.detectorReady} 원본=${it.debugInfo.rawDetectionCount} 추적=${it.debugInfo.trackedDetectionCount}",
                color = Color(0xFFD8E3EE),
                fontSize = 12.sp,
            )
            Text(
                text = "영상 테스트에서는 ARCore 자세, 깊이, 평면 정보를 사용할 수 없습니다.",
                color = Color(0xFFFFDB7A),
                fontSize = 12.sp,
            )
        }
    }
}

@Composable
private fun WalkingGuidancePanel(
    state: ArMeasurementState,
    modifier: Modifier = Modifier,
) {
    val color = when (state.statusLevel) {
        ArStatusLevel.DANGER -> Color(0xFFE54545)
        ArStatusLevel.WARNING -> Color(0xFFFFC857)
        ArStatusLevel.SAFE -> Color(0xFF2FA66A)
        ArStatusLevel.INFO -> Color(0xFF4B5563)
    }
    Column(
        modifier = modifier
            .width(260.dp)
            .background(Color(0xCC121820), RoundedCornerShape(18.dp))
            .border(2.dp, color, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.guidanceLabel,
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = state.collisionDistanceMeters?.let { "전방 ${formatReplayMeters(it)}" }
                ?: "공간 인식 중",
            color = Color(0xFFD8E3EE),
            fontSize = 13.sp,
        )
    }
}

@Composable
private fun ReplayDetectionOverlay(
    detections: List<ObjectOverlayDetection>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        detections.forEach { detection ->
            val boxLeft = maxWidth * detection.leftRatio.coerceIn(0f, 1f)
            val boxTop = maxHeight * detection.topRatio.coerceIn(0f, 1f)
            val boxWidth = maxWidth * detection.widthRatio.coerceIn(0.02f, 1f)
            val boxHeight = maxHeight * detection.heightRatio.coerceIn(0.02f, 1f)

            Box(
                modifier = Modifier
                    .offset(x = boxLeft, y = boxTop)
                    .width(boxWidth)
                    .height(boxHeight)
                    .border(2.dp, Color(0xFFFFB648), RoundedCornerShape(6.dp)),
            ) {
                Text(
                    text = buildString {
                        append(detection.label)
                        detection.distanceMeters?.let { append(" ${formatReplayMeters(it)}") }
                        append(" ${(detection.confidence * 100f).toInt()}%")
                    },
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(y = (-22).dp)
                        .background(Color(0xCC121820), RoundedCornerShape(6.dp))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                )
            }
        }
    }
}

@Composable
private fun FloorBoundaryOverlay(
    result: VideoFrameAnalysisResult?,
    modifier: Modifier = Modifier,
) {
    val segmentation = result?.floorSegmentation ?: return
    Canvas(modifier = modifier) {
        if (segmentation.boundaryYByColumn.isEmpty()) return@Canvas
        val step = (segmentation.width / 24).coerceAtLeast(1)
        var previous: Offset? = null
        for (column in 0 until segmentation.width step step) {
            val boundary = segmentation.boundaryYByColumn[column]
            if (boundary < 0) {
                previous = null
                continue
            }
            val x = (column / (segmentation.width - 1).toFloat()) * size.width
            val y = (boundary / segmentation.height.toFloat()) * size.height
            val current = Offset(x, y)
            val last = previous
            if (last != null) {
                drawLine(
                    color = Color(0xFF66E39A),
                    start = last,
                    end = current,
                    strokeWidth = 4f,
                )
            }
            previous = current
        }
        drawRect(
            color = Color(0x3366E39A),
            style = Stroke(width = 2f),
        )
    }
}

@Composable
private fun ReplayChip(
    text: String,
    onClick: () -> Unit,
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .background(Color(0xAA121820), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

private fun formatReplayMeters(distanceMeters: Float): String {
    return if (distanceMeters < 1f) {
        "${(distanceMeters * 100f).toInt()}cm"
    } else {
        String.format("%.1fm", distanceMeters)
    }
}
