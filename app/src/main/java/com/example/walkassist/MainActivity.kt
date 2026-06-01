package com.example.walkassist

import android.Manifest
import android.content.Intent
import android.graphics.Bitmap
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.commitNow
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.walkassist.feedback.core.FeedbackUiState
import com.example.walkassist.feedback.core.FeedbackAlertLevel
import com.example.walkassist.feedback.core.FeedbackSensorStatus
import com.example.walkassist.feedback.engine.ArFeedbackMapper
import com.example.walkassist.feedback.engine.FeedbackViewModel
import com.example.walkassist.feedback.runtime.FeedbackManager
import com.example.walkassist.feedback.ui.FeedbackOverlayCard
import com.example.walkassist.map.MapNavigationActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private val fragmentContainerId = 1001
    private val feedbackViewModel by viewModels<FeedbackViewModel>()
    private val arFeedbackMapper = ArFeedbackMapper()
    private lateinit var feedbackManager: FeedbackManager
    private var arFragment: WalkAssistArFragment? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureFullscreenCutout()
        feedbackManager = FeedbackManager(this)
        if (!intent.getBooleanExtra(ArCoreReplayController.EXTRA_RECORD_ON_START, false) &&
            intent.getStringExtra(ArCoreReplayController.EXTRA_PLAYBACK_DATASET_URI).isNullOrBlank()
        ) {
            ArCoreReplayController.reset()
        }
        prepareVlmModelAtStartup()
        bindArStateToFeedback()

        val missingStartupPermissions = buildList {
            if (!hasCameraPermission()) add(Manifest.permission.CAMERA)
        }
        if (missingStartupPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingStartupPermissions.toTypedArray(), 100)
        }

        val root = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val fragmentContainer = FragmentContainerView(this).apply {
            id = fragmentContainerId
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }

        val overlay = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.TOP or Gravity.START,
            )
            setContent {
                MaterialTheme {
                    val arState = ArMeasurementBridge.state.collectAsState().value
                    val feedbackState = feedbackViewModel.uiState.collectAsState().value
                    WalkAssistRootOverlay(
                        state = arState,
                        feedbackState = feedbackState,
                        onOcrClick = ::requestOneShotOcr,
                        onVlmClick = ::requestOneShotVlm,
                        vlmButtonText = "VLM 시작",
                        onStopReplayRecording = ::stopArCoreReplayRecording,
                        onPlaneMeshDebugChanged = ::setPlaneMeshDebugVisible,
                    )
                }
            }
        }

        root.addView(fragmentContainer)
        root.addView(overlay)
        setContentView(root)

        if (savedInstanceState == null) {
            val fragment = WalkAssistArFragment()
            configureArFragment(fragment)
            supportFragmentManager.commitNow {
                replace(fragmentContainerId, fragment)
            }
        } else {
            (supportFragmentManager.findFragmentById(fragmentContainerId) as? WalkAssistArFragment)
                ?.let(::configureArFragment)
        }
    }

    private fun configureArFragment(fragment: WalkAssistArFragment) {
        arFragment = fragment
        fragment.recordReplayOnSessionStart =
            intent.getBooleanExtra(ArCoreReplayController.EXTRA_RECORD_ON_START, false)
        fragment.playbackDatasetUri = intent
            .getStringExtra(ArCoreReplayController.EXTRA_PLAYBACK_DATASET_URI)
            ?.takeIf { it.isNotBlank() }
            ?.let(android.net.Uri::parse)
        fragment.onOneShotOcrResult = { message ->
            feedbackManager.provideFeedback(
                message = message,
                level = FeedbackAlertLevel.CAUTION,
            )
        }
        fragment.onOneShotVlmResult = { message ->
            feedbackManager.provideFeedback(
                message = message,
                level = FeedbackAlertLevel.CAUTION,
                prioritySpeech = true,
            )
        }
    }

    private fun prepareVlmModelAtStartup() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = runCatching {
                WalkAssistVlmFactory.prepareSelected()
            }.onFailure {
                Log.w(TAG, "VLM startup preparation failed", it)
            }.getOrElse {
                VlmModelPreparationStatus(
                    modelName = WalkAssistVlmFactory.SELECTED_MODEL_DISPLAY_NAME,
                    statusLabel = "unknown",
                    downloadState = null,
                    isAvailable = false,
                    isFallbackLikely = true,
                    explanation = "장면 분석 모델 상태를 확인하지 못했습니다.",
                )
            }

            Log.i(
                TAG,
                "VLM startup preparation model=${status.modelName} status=${status.statusLabel} " +
                    "download=${status.downloadState ?: "none"} available=${status.isAvailable}",
            )

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@MainActivity,
                    if (status.isAvailable) {
                        "VLM 준비 완료: ${status.modelName}"
                    } else {
                        "VLM ${status.statusLabel}. ${status.explanation}"
                    },
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    private fun requestOneShotOcr() {
        val fragment = arFragment
            ?: (supportFragmentManager.findFragmentById(fragmentContainerId) as? WalkAssistArFragment)
                ?.also(::configureArFragment)

        if (fragment == null) {
            feedbackManager.provideFeedback(
                message = "문자 인식 준비 중입니다.",
                level = FeedbackAlertLevel.CAUTION,
            )
            return
        }

        fragment.requestOneShotOcr()
    }

    private fun requestOneShotVlm() {
        if (!WalkAssistSettings.debugPipelineFlags(this).vlmEnabled) {
            feedbackManager.provideFeedback(
                message = "디버그 설정에서 VLM 파이프라인이 꺼져 있습니다.",
                level = FeedbackAlertLevel.CAUTION,
            )
            return
        }
        val fragment = arFragment
            ?: (supportFragmentManager.findFragmentById(fragmentContainerId) as? WalkAssistArFragment)
                ?.also(::configureArFragment)

        if (fragment == null) {
            feedbackManager.provideFeedback(
                message = "장면 분석을 준비 중입니다. 잠시 후 다시 눌러 주세요.",
                level = FeedbackAlertLevel.CAUTION,
            )
            return
        }

        fragment.requestOneShotVlm()
    }

    private fun stopArCoreReplayRecording() {
        val fragment = arFragment
            ?: (supportFragmentManager.findFragmentById(fragmentContainerId) as? WalkAssistArFragment)
                ?.also(::configureArFragment)
        fragment?.stopArCoreReplayRecording()
    }

    private fun setPlaneMeshDebugVisible(visible: Boolean) {
        val fragment = arFragment
            ?: (supportFragmentManager.findFragmentById(fragmentContainerId) as? WalkAssistArFragment)
                ?.also(::configureArFragment)
        fragment?.setPlaneMeshDebugVisible(visible)
    }

    private fun bindArStateToFeedback() {
        feedbackViewModel.startWatchdog()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ArMeasurementBridge.state.collect { state ->
                        feedbackViewModel.onInput(arFeedbackMapper.map(state))
                    }
                }
                launch {
                    feedbackViewModel.uiState.collect { feedbackState ->
                        if (feedbackState.shouldAnnounce) {
                            if (WalkAssistSettings.isArcoreTtsEnabled(this@MainActivity)) {
                                feedbackManager.provideFeedback(
                                    message = feedbackState.message,
                                    level = feedbackState.alertLevel,
                                )
                            }
                            feedbackViewModel.consumeAnnouncement()
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        if (::feedbackManager.isInitialized) {
            feedbackManager.release()
        }
        super.onDestroy()
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun configureFullscreenCutout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 35) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    companion object {
        private const val TAG = "WalkAssistMain"
    }
}

@Composable
private fun WalkAssistRootOverlay(
    state: ArMeasurementState,
    feedbackState: FeedbackUiState,
    onOcrClick: () -> Unit,
    onVlmClick: () -> Unit,
    vlmButtonText: String,
    onStopReplayRecording: () -> Unit,
    onPlaneMeshDebugChanged: (Boolean) -> Unit,
) {
    var cameraUiVisible by remember { mutableStateOf(false) }
    var replayState by remember { mutableStateOf(ArCoreReplayController.currentState()) }
    val context = LocalContext.current
    var debugFlags by remember { mutableStateOf(WalkAssistSettings.debugPipelineFlags(context)) }
    val resourceMonitor = remember(context) { ResourceMonitor(context.applicationContext) }
    var resourceUsage by remember { mutableStateOf(ResourceUsageSnapshot()) }
    val updateDebugFlags: (DebugPipelineFlags) -> Unit = { nextFlags ->
        debugFlags = nextFlags
        WalkAssistSettings.setDebugPipelineFlags(context, nextFlags)
    }
    LaunchedEffect(resourceMonitor) {
        while (true) {
            resourceUsage = withContext(Dispatchers.Default) {
                resourceMonitor.sample()
            }
            delay(2_000L)
        }
    }
    DisposableEffect(Unit) {
        val listener: (ArCoreReplayUiState) -> Unit = { replayState = it }
        ArCoreReplayController.addListener(listener)
        onDispose { ArCoreReplayController.removeListener(listener) }
    }
    val openMapNavigation = {
        context.startActivity(Intent(context, MapNavigationActivity::class.java))
    }
    val openSettings = {
        context.startActivity(Intent(context, GuideSettingsActivity::class.java))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraUiVisible) {
            MeasurementOverlay(
                state = state,
                feedbackState = feedbackState,
                debugFlags = debugFlags,
                onDebugFlagsChanged = updateDebugFlags,
                onPlaneMeshDebugChanged = onPlaneMeshDebugChanged,
            )
            CameraUiControls(
                onGuideClick = { cameraUiVisible = false },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 150.dp),
            )
        } else {
            GuideStatusOverlay(
                arState = state,
                feedbackState = feedbackState,
                onCameraClick = { cameraUiVisible = true },
                onMapClick = openMapNavigation,
            )
        }

        GuideActionChip(
            text = "설정",
            onClick = openSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 104.dp, end = 6.dp),
        )

        ResourceUsagePanel(
            usage = resourceUsage,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 6.dp),
        )

        ArCoreReplayStatusPanel(
            state = replayState,
            onStopRecording = onStopReplayRecording,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 14.dp, start = 14.dp),
        )

        GuideActionChip(
            text = "OCR",
            onClick = onOcrClick,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
        )
        GuideActionChip(
            text = vlmButtonText,
            onClick = onVlmClick,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 18.dp),
        )
    }
}

@Composable
private fun ResourceUsagePanel(
    usage: ResourceUsageSnapshot,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(178.dp),
        horizontalAlignment = Alignment.End,
    ) {
        ResourceUsageText("CPU ${formatPercent(usage.cpuCorePercent)}% / ${formatPercent(usage.cpuDevicePercent)}%")
        ResourceUsageText("GPU ${usage.gpuPercent?.let { "$it%" } ?: "--"}")
        ResourceUsageText("RAM ${usage.systemRamPercent}%")
    }
}

@Composable
private fun ResourceUsageText(
    text: String,
) {
    Text(
        text = text,
        color = Color(0xFF68F29A),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.End,
    )
}

@Composable
private fun ArCoreReplayStatusPanel(
    state: ArCoreReplayUiState,
    onStopRecording: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.mode == ArCoreReplayMode.LIVE && state.message.isBlank()) return

    Column(
        modifier = modifier
            .width(230.dp)
            .background(Color(0xB8121820), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = when (state.mode) {
                ArCoreReplayMode.RECORDING -> "ARCore 녹화"
                ArCoreReplayMode.PLAYBACK -> "ARCore 재생"
                ArCoreReplayMode.LIVE -> "ARCore 리플레이"
            },
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        if (state.message.isNotBlank()) {
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = state.message,
                color = Color(0xFFD8E3EE),
                fontSize = 12.sp,
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = "REC ${state.recordingStatus} / PLAY ${state.playbackStatus}",
            color = Color(0xFFB7F7CE),
            fontSize = 11.sp,
        )
        if (state.mode == ArCoreReplayMode.RECORDING) {
            Spacer(modifier = Modifier.height(8.dp))
            GuideActionChip(
                text = "녹화 중지",
                onClick = onStopRecording,
                modifier = Modifier.width(104.dp),
            )
        }
    }
}

@Composable
private fun GuideStatusOverlay(
    arState: ArMeasurementState,
    feedbackState: FeedbackUiState,
    onCameraClick: () -> Unit,
    onMapClick: () -> Unit,
) {
    val palette = guidePalette(feedbackState.alertLevel, feedbackState.sensorStatus)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = palette.icon,
                color = palette.foreground,
                fontSize = 52.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = palette.title,
                color = palette.foreground,
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(18.dp))
            Text(
                text = feedbackState.message,
                color = palette.foreground,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = guideDistanceText(feedbackState, arState),
                color = palette.foreground.copy(alpha = 0.86f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Text(
            text = "WalkAssist",
            color = palette.foreground.copy(alpha = 0.86f),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopStart),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 6.dp, bottom = 18.dp),
            horizontalAlignment = Alignment.End,
        ) {
            GuideActionChip(
                text = "길찾기",
                onClick = onMapClick,
            )
            Spacer(modifier = Modifier.height(10.dp))
            GuideActionChip(
                text = "카메라 보기",
                onClick = onCameraClick,
                modifier = Modifier.width(112.dp),
            )
        }
    }
}

@Composable
private fun CameraUiControls(
    onGuideClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        GuideActionChip(
            text = "큰 화면",
            onClick = onGuideClick,
        )
    }
}

@Composable
private fun GuideActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(Color(0x99121820), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

private data class GuidePalette(
    val background: Color,
    val foreground: Color,
    val title: String,
    val icon: String,
)

private fun guidePalette(
    alertLevel: FeedbackAlertLevel,
    sensorStatus: FeedbackSensorStatus,
): GuidePalette {
    if (sensorStatus == FeedbackSensorStatus.WAITING || sensorStatus == FeedbackSensorStatus.DISCONNECTED) {
        return GuidePalette(
            background = Color(0xFF4A5568),
            foreground = Color.White,
            title = "대기",
            icon = "..."
        )
    }
    return when (alertLevel) {
        FeedbackAlertLevel.SAFE -> GuidePalette(
            background = Color(0xFF15803D),
            foreground = Color.White,
            title = "안전",
            icon = "OK",
        )
        FeedbackAlertLevel.CAUTION -> GuidePalette(
            background = Color(0xFFF59E0B),
            foreground = Color(0xFF17120A),
            title = "주의",
            icon = "!",
        )
        FeedbackAlertLevel.DANGER -> GuidePalette(
            background = Color(0xFFDC2626),
            foreground = Color.White,
            title = "위험",
            icon = "!!",
        )
    }
}

private fun guideDistanceText(
    feedbackState: FeedbackUiState,
    arState: ArMeasurementState,
): String {
    val distance = feedbackState.distanceMeters ?: arState.collisionDistanceMeters
    val confidence = (feedbackState.confidence * 100f).toInt().coerceIn(0, 100)
    return if (distance == null) {
        "공간 정보를 수집하는 중입니다."
    } else {
        "전방 ${formatMetersShort(distance)} / 신뢰도 $confidence%"
    }
}

private fun guideSensorStatus(status: FeedbackSensorStatus): String {
    return when (status) {
        FeedbackSensorStatus.WAITING -> "센서 대기 중"
        FeedbackSensorStatus.CONNECTED -> "센서 연결됨"
        FeedbackSensorStatus.DISCONNECTED -> "센서 끊김"
        FeedbackSensorStatus.ERROR -> "센서 오류"
    }
}

@Composable
private fun MeasurementOverlay(
    state: ArMeasurementState,
    feedbackState: FeedbackUiState,
    debugFlags: DebugPipelineFlags,
    onDebugFlagsChanged: (DebugPipelineFlags) -> Unit,
    onPlaneMeshDebugChanged: (Boolean) -> Unit,
) {
    var debugVisible by remember { mutableStateOf(false) }

    LaunchedEffect(debugVisible, debugFlags.arCoreHitTestEnabled) {
        onPlaneMeshDebugChanged(debugVisible && debugFlags.arCoreHitTestEnabled)
    }
    DisposableEffect(Unit) {
        onDispose { onPlaneMeshDebugChanged(false) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ObjectDetectionOverlay(
            detections = state.objectDetections,
            modifier = Modifier.fillMaxSize(),
        )

        CompactWorldMapOverlay(
            state = state,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 14.dp, bottom = 18.dp),
        )

        FeedbackOverlayCard(
            state = feedbackState,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 18.dp),
        )

        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 14.dp, top = 14.dp)
                .width(222.dp)
                .background(Color(0xB8121820), RoundedCornerShape(20.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
        ) {
            val (riskText, riskColor) = presentableRisk(state.riskLabel)
            val confidenceColor = when {
                state.sensingConfidenceScore >= 80 -> Color(0xFF96E2B5)
                state.sensingConfidenceScore >= 55 -> Color(0xFFFFDB7A)
                else -> Color(0xFFFFA0A0)
            }

            Text(
                text = "WalkAssist",
                color = Color(0xFFD8E3EE),
                fontSize = 12.sp,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = riskText,
                color = riskColor,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = state.guidanceLabel,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(12.dp))
            ConfidenceBar(
                score = state.sensingConfidenceScore,
                color = confidenceColor,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "공간 인식 신뢰도 ${state.sensingConfidenceScore}점",
                color = confidenceColor,
                fontSize = 13.sp,
            )
            state.timeToCollisionSeconds?.takeIf { state.riskLabel != "stable" }?.let { ttc ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "충돌 예상 ${formatSeconds(ttc)}",
                    color = riskColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (state.crosswalkDetected) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "횡단보도 감지 ${formatMapScore(state.crosswalkScore)}점",
                    color = Color(0xFFB6E7FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = state.statusLabel,
                color = Color(0xFFD8E3EE),
                fontSize = 12.sp,
            )
        }

        OverlayToggleChip(
            label = "DBG",
            active = debugVisible,
            onClick = { debugVisible = !debugVisible },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 66.dp, end = 14.dp),
        )

        if (debugVisible) {
            if (debugFlags.floorSegmentationEnabled) {
                FloorSegmentationOverlay(
                    classMask = state.semanticClassMask,
                    columns = state.floorOverlayColumns,
                    confidence = state.floorOverlayConfidence,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (debugFlags.rawDepthEnabled) {
                DepthGridOverlay(
                    cells = state.depthGridCells,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            DebugOverlay(
                state = state,
                debugFlags = debugFlags,
                onDebugFlagsChanged = onDebugFlagsChanged,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 14.dp, top = 170.dp),
            )
        }
    }
}

@Composable
private fun OverlayToggleChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = label,
        color = Color.White,
        fontSize = 11.sp,
        modifier = modifier
            .background(
                if (active) Color(0xBF375D7A) else Color(0x7A141B24),
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}

@Composable
private fun ObjectDetectionOverlay(
    detections: List<ObjectOverlayDetection>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val labelBackground = Color(0xCC121820)

        detections.forEach { detection ->
            val boxLeft = maxWidth * detection.leftRatio.coerceIn(0f, 1f)
            val boxTop = maxHeight * detection.topRatio.coerceIn(0f, 1f)
            val boxWidth = maxWidth * detection.widthRatio.coerceIn(0.05f, 1f)
            val boxHeight = maxHeight * detection.heightRatio.coerceIn(0.05f, 1f)

            Box(
                modifier = Modifier
                    .offset(x = boxLeft, y = boxTop)
                    .width(boxWidth)
                    .height(boxHeight)
                    .border(2.dp, Color(0xFFFFB648), RoundedCornerShape(8.dp)),
            ) {
                Text(
                    text = buildString {
                        append(presentableLabel(detection.label))
                        detection.objectTimeToCollisionSeconds?.let {
                            append(" TTC ")
                            append(formatSeconds(it))
                        }
                        detection.motionDirectionLabel?.let {
                            append(" ")
                            append(presentableMotion(it))
                        }
                        detection.avoidanceDirectionLabel?.let {
                            append(" ")
                            append(presentableAvoidance(it))
                        }
                        append(" ")
                        append((detection.confidence * 100f).toInt())
                        append("%")
                    },
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset(y = (-24).dp)
                        .background(labelBackground, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun FloorSegmentationOverlay(
    classMask: SemanticClassMaskOverlay?,
    columns: List<FloorOverlayColumn>,
    confidence: Float,
    modifier: Modifier = Modifier,
) {
    if (classMask == null && (columns.size < 2 || confidence <= 0f)) return
    val classMaskImage = remember(classMask) {
        classMask?.toImageBitmap()
    }

    Canvas(modifier = modifier) {
        if (classMaskImage != null) {
            drawImage(
                image = classMaskImage,
                dstSize = IntSize(
                    width = size.width.toInt().coerceAtLeast(1),
                    height = size.height.toInt().coerceAtLeast(1),
                ),
            )
            return@Canvas
        }

        val sortedColumns = columns.sortedBy { it.xRatio }
        val floorPath = Path()
        val first = sortedColumns.first()
        floorPath.moveTo(
            first.xRatio.coerceIn(0f, 1f) * size.width,
            first.boundaryYRatio.coerceIn(0f, 1f) * size.height,
        )
        sortedColumns.drop(1).forEach { column ->
            floorPath.lineTo(
                column.xRatio.coerceIn(0f, 1f) * size.width,
                column.boundaryYRatio.coerceIn(0f, 1f) * size.height,
            )
        }
        floorPath.lineTo(size.width, size.height)
        floorPath.lineTo(0f, size.height)
        floorPath.close()

        drawPath(
            path = floorPath,
            color = Color(0x5536D399),
        )

        val boundaryPath = Path()
        boundaryPath.moveTo(
            first.xRatio.coerceIn(0f, 1f) * size.width,
            first.boundaryYRatio.coerceIn(0f, 1f) * size.height,
        )
        sortedColumns.drop(1).forEach { column ->
            boundaryPath.lineTo(
                column.xRatio.coerceIn(0f, 1f) * size.width,
                column.boundaryYRatio.coerceIn(0f, 1f) * size.height,
            )
        }
        drawPath(
            path = boundaryPath,
            color = Color(0xCC5FFFD0),
            style = Stroke(width = 4f),
        )
    }
}

private fun SemanticClassMaskOverlay.toImageBitmap() =
    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
        val pixels = IntArray(width * height) { index ->
            semanticClassArgb(if (index in classIds.indices) classIds[index] else -1)
        }
        setPixels(pixels, 0, width, 0, 0, width, height)
    }.asImageBitmap()

private fun semanticClassArgb(classId: Int): Int {
    return when (classId) {
        0 -> 0x66808080 // road
        1 -> 0x66F4D03F // sidewalk
        2 -> 0x668E44AD // building
        3 -> 0x66A04000 // wall
        4 -> 0x66D35400 // fence
        5 -> 0x66E74C3C // pole
        6 -> 0x66F5B7B1 // traffic light
        7 -> 0x66F1948A // traffic sign
        8 -> 0x662ECC71 // vegetation
        9 -> 0x6658D68D // terrain
        10 -> 0x665DADE2 // sky
        11 -> 0x66FF4D6D // person
        12 -> 0x66FF85A1 // rider
        13 -> 0x663498DB // car
        14 -> 0x662E86C1 // truck
        15 -> 0x662876A6 // bus
        16 -> 0x661F618D // train
        17 -> 0x66F39C12 // motorcycle
        18 -> 0x66F7DC6F // bicycle
        else -> 0x33000000
    }
}

@Composable
private fun DepthGridOverlay(
    cells: List<DepthGridCell>,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val cellWidth = maxWidth / 4
        val cellHeight = maxHeight / 4
        val cellsByPosition = cells.associateBy { it.column to it.row }

        for (row in 0 until 4) {
            for (column in 0 until 4) {
                val cell = cellsByPosition[column to row]
                val distance = cell?.distanceMeters
                Box(
                    modifier = Modifier
                        .offset(x = cellWidth * column, y = cellHeight * row)
                        .width(cellWidth)
                        .height(cellHeight)
                        .border(1.dp, Color(0x66FFFFFF)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = distance?.let(::formatMetersShort) ?: "--",
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(depthGridColor(distance), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DebugOverlay(
    state: ArMeasurementState,
    debugFlags: DebugPipelineFlags,
    onDebugFlagsChanged: (DebugPipelineFlags) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = Modifier
            .then(modifier)
            .wrapContentWidth()
            .width(170.dp)
            .background(Color(0x7A141B24), RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text("AR", color = Color.White)
        Spacer(modifier = Modifier.height(6.dp))
        Text("Pipeline", color = Color(0xFFB7F7CE), fontSize = 12.sp)
        DebugToggleRow(
            label = "YOLO",
            enabled = debugFlags.yoloEnabled,
            onClick = { onDebugFlagsChanged(debugFlags.copy(yoloEnabled = !debugFlags.yoloEnabled)) },
        )
        DebugToggleRow(
            label = "Floor",
            enabled = debugFlags.floorSegmentationEnabled,
            onClick = {
                onDebugFlagsChanged(
                    debugFlags.copy(floorSegmentationEnabled = !debugFlags.floorSegmentationEnabled),
                )
            },
        )
        DebugToggleRow(
            label = "AR hit",
            enabled = debugFlags.arCoreHitTestEnabled,
            onClick = {
                onDebugFlagsChanged(debugFlags.copy(arCoreHitTestEnabled = !debugFlags.arCoreHitTestEnabled))
            },
        )
        DebugToggleRow(
            label = "Depth",
            enabled = debugFlags.rawDepthEnabled,
            onClick = { onDebugFlagsChanged(debugFlags.copy(rawDepthEnabled = !debugFlags.rawDepthEnabled)) },
        )
        DebugToggleRow(
            label = "Map",
            enabled = debugFlags.localMapEnabled,
            onClick = { onDebugFlagsChanged(debugFlags.copy(localMapEnabled = !debugFlags.localMapEnabled)) },
        )
        DebugToggleRow(
            label = "Cross",
            enabled = debugFlags.crosswalkEnabled,
            onClick = { onDebugFlagsChanged(debugFlags.copy(crosswalkEnabled = !debugFlags.crosswalkEnabled)) },
        )
        DebugToggleRow(
            label = "VLM",
            enabled = debugFlags.vlmEnabled,
            onClick = { onDebugFlagsChanged(debugFlags.copy(vlmEnabled = !debugFlags.vlmEnabled)) },
        )
        Spacer(modifier = Modifier.height(4.dp))
        WorldMapMiniMap(state = state)
        Spacer(modifier = Modifier.height(6.dp))
        DirectionArrow(state = state)
        Spacer(modifier = Modifier.height(4.dp))
        Text("Tracking: ${state.trackingLabel}", color = Color(0xFFD9E2EA))
        if (state.trackingFailureLabel.isNotBlank()) {
            Text("Tracking issue: ${state.trackingFailureLabel}", color = Color(0xFFFFE08B))
        }
        Text("Pitch ${state.pitchDownDegrees.toInt()}deg", color = Color(0xFFD9E2EA))
        Text(
            "Planes: ${state.horizontalPlaneCount}/${state.verticalPlaneCount}",
            color = Color(0xFFD9E2EA),
        )
        Text(
            state.guidanceLabel,
            color = when (state.statusLevel) {
                ArStatusLevel.DANGER -> Color(0xFFFF9CA8)
                ArStatusLevel.WARNING -> Color(0xFFFFE08B)
                ArStatusLevel.SAFE -> Color(0xFF9AE7C7)
                ArStatusLevel.INFO -> Color(0xFFD9E2EA)
            },
        )
        Text(
            "Risk ${state.riskLabel}",
            color = when (state.statusLevel) {
                ArStatusLevel.DANGER -> Color(0xFFFF9CA8)
                ArStatusLevel.WARNING -> Color(0xFFFFE08B)
                ArStatusLevel.SAFE -> Color(0xFF9AE7C7)
                ArStatusLevel.INFO -> Color(0xFFD9E2EA)
            },
        )
        Text(
            "Move ${state.motionMetersPerSecond?.let(::formatSpeed) ?: "-"}  Close ${state.approachSpeedMetersPerSecond?.let(::formatSpeed) ?: "-"}  TTC ${state.timeToCollisionSeconds?.let(::formatSeconds) ?: "-"}",
            color = Color(0xFFD9E2EA),
        )
        Text(
            "Map L ${formatMapScore(state.worldMapLeftOpenScore)}/${state.worldMapLeftFreeSpaceMeters?.let(::formatMetersShort) ?: "-"}",
            color = Color(0xFFD9E2EA),
        )
        Text(
            "Map C ${formatMapScore(state.worldMapCenterOpenScore)}/${state.worldMapCenterFreeSpaceMeters?.let(::formatMetersShort) ?: "-"}",
            color = Color(0xFFD9E2EA),
        )
        Text(
            "Map R ${formatMapScore(state.worldMapRightOpenScore)}/${state.worldMapRightFreeSpaceMeters?.let(::formatMetersShort) ?: "-"}",
            color = Color(0xFFD9E2EA),
        )
        Text(
            "Crosswalk ${if (state.crosswalkDetected) "yes" else "no"} ${formatMapScore(state.crosswalkScore)} stripes ${state.crosswalkStripeCount} yolo ${formatMapScore(state.crosswalkYoloConfidence)} ${state.crosswalkModeLabel}",
            color = if (state.crosswalkDetected) Color(0xFFB6E7FF) else Color(0xFFD9E2EA),
        )
        if (state.vlmModelName.isNotBlank()) {
            Text(
                "VLM ${state.vlmModelName} ${state.vlmRiskLabel} ${state.vlmConfidenceScore}%",
                color = if (state.vlmModelName.contains("fallback", ignoreCase = true)) {
                    Color(0xFFFFE08B)
                } else {
                    Color(0xFFB6E7FF)
                },
            )
        }
        Text(
            state.statusLabel,
            color = when (state.statusLevel) {
                ArStatusLevel.DANGER -> Color(0xFFFF9CA8)
                ArStatusLevel.WARNING -> Color(0xFFFFE08B)
                ArStatusLevel.SAFE -> Color(0xFF9AE7C7)
                ArStatusLevel.INFO -> Color(0xFFD9E2EA)
            },
        )
        if (state.note.isNotBlank()) {
            Text(state.note, color = Color(0xFFD9E2EA))
        }
    }
}

@Composable
private fun DebugToggleRow(
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = Color(0xFFD9E2EA), fontSize = 12.sp)
        Text(
            text = if (enabled) "ON" else "OFF",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .background(
                    if (enabled) Color(0xBF15803D) else Color(0xBFB91C1C),
                    RoundedCornerShape(10.dp),
                )
                .clickable(onClick = onClick)
                .padding(horizontal = 9.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CompactWorldMapOverlay(
    state: ArMeasurementState,
    modifier: Modifier = Modifier,
) {
    if (state.worldMapCells.isEmpty()) return

    Column(
        modifier = modifier
            .width(124.dp)
            .background(Color(0x88121820), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        Text(
            text = "Space Map",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.height(6.dp))
        WorldMapMiniMap(
            state = state,
            modifier = Modifier
                .width(108.dp)
                .height(108.dp),
        )
    }
}

@Composable
private fun ConfidenceBar(
    score: Int,
    color: Color,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .background(Color(0x333E4A57), RoundedCornerShape(999.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth((score.coerceIn(0, 100) / 100f).coerceAtLeast(0.04f))
                .height(8.dp)
                .background(color, RoundedCornerShape(999.dp)),
        )
    }
}

private fun presentableRisk(riskLabel: String): Pair<String, Color> {
    return when (riskLabel) {
        "critical" -> "위험" to Color(0xFFFF8E8E)
        "high" -> "경고" to Color(0xFFFFC870)
        "watch" -> "주의" to Color(0xFFFFDB7A)
        "stable" -> "안전" to Color(0xFF96E2B5)
        else -> "주의" to Color(0xFFD8E3EE)
    }
}

private fun depthGridColor(distanceMeters: Float?): Color {
    val distance = distanceMeters ?: return Color(0xFFFFB648)
    return when {
        distance < 0.8f -> Color(0xDDE85D75)
        distance < 1.5f -> Color(0xDDDDAA45)
        distance < 3f -> Color(0xDD5CBF88)
        else -> Color(0xDD375D7A)
    }
}

private fun presentableLabel(label: String): String {
    return when (label.lowercase()) {
        "person" -> "사람"
        "bicycle" -> "자전거"
        "car" -> "자동차"
        "motorcycle" -> "오토바이"
        "bus" -> "버스"
        "truck" -> "트럭"
        "chair" -> "의자"
        "bench" -> "벤치"
        "dog" -> "개"
        "cat" -> "고양이"
        "stop sign" -> "표지판"
        else -> label
    }
}

@Composable
private fun CorridorMiniMap(state: ArMeasurementState) {
    val totalWidth = 150f
    Row(
        modifier = Modifier
            .width(totalWidth.dp)
            .height(48.dp)
            .background(Color(0x332B3642), RoundedCornerShape(8.dp)),
        horizontalArrangement = Arrangement.Start,
    ) {
        CorridorSegment("L", state.leftLaneWidthRatio, totalWidth, state.leftDistanceMeters)
        CorridorSegment("C", state.centerLaneWidthRatio, totalWidth, state.centerDistanceMeters)
        CorridorSegment("R", state.rightLaneWidthRatio, totalWidth, state.rightDistanceMeters)
    }
}

@Composable
private fun CorridorSegment(
    label: String,
    widthRatio: Float,
    totalWidth: Float,
    distanceMeters: Float?,
) {
    val color = when {
        distanceMeters == null -> Color(0x553A4654)
        distanceMeters < 0.8f -> Color(0xAAE85D75)
        distanceMeters < 1.5f -> Color(0xAADAAE58)
        else -> Color(0xAA5CBF88)
    }
    Box(
        modifier = Modifier
            .width((totalWidth * widthRatio.coerceAtLeast(0.05f)).dp)
            .height(48.dp)
            .background(color),
    ) {
        Text(
            text = label,
            color = Color.White,
            modifier = Modifier.padding(start = 4.dp, top = 2.dp),
        )
        Text(
            text = distanceMeters?.let(::formatMetersShort) ?: "-",
            color = Color.White,
            modifier = Modifier.padding(start = 4.dp, top = 24.dp),
        )
    }
}

@Composable
private fun WorldMapMiniMap(
    state: ArMeasurementState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(150.dp)
            .height(150.dp)
            .background(Color(0x332B3642), RoundedCornerShape(8.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val halfRange = state.worldMapRangeMeters.coerceAtLeast(0.1f)
            drawRect(Color(0x223A4654))

            state.worldMapCells.forEach { cell ->
                val xRatio = ((cell.relativeX + halfRange) / (halfRange * 2f)).coerceIn(0f, 1f)
                val zRatio = (1f - ((cell.relativeZ + halfRange) / (halfRange * 2f))).coerceIn(0f, 1f)
                val cellSizePx = (size.minDimension * (state.worldMapCellSizeMeters / (halfRange * 2f)))
                    .coerceAtLeast(2f)
                val color = when {
                    cell.occupancyScore >= 0.35f -> Color(0xFFE66D7A).copy(alpha = 0.35f + (cell.confidenceScore * 0.55f))
                    cell.occupancyScore >= 0.14f -> Color(0xFFFFC870).copy(alpha = 0.28f + (cell.confidenceScore * 0.48f))
                    cell.occupancyScore <= -0.18f -> Color(0xFF59C58C).copy(alpha = 0.28f + (cell.confidenceScore * 0.48f))
                    else -> Color(0xFF7A8794).copy(alpha = 0.2f + (cell.confidenceScore * 0.36f))
                }
                drawRect(
                    color = color,
                    topLeft = Offset(
                        (size.width * xRatio) - (cellSizePx * 0.5f),
                        (size.height * zRatio) - (cellSizePx * 0.5f),
                    ),
                    size = Size(cellSizePx, cellSizePx),
                )
            }

            val centerX = size.width * 0.5f
            val centerY = size.height * 0.5f
            drawCircle(
                color = Color.White,
                radius = 5f,
                center = Offset(centerX, centerY),
            )
            drawLine(
                color = Color(0xFF8FC8FF),
                start = Offset(centerX, centerY),
                end = Offset(centerX, centerY - 22f),
                strokeWidth = 4f,
            )
        }
    }
}

@Composable
private fun DirectionArrow(state: ArMeasurementState) {
    val arrow = when (state.suggestedDirection) {
        "left" -> "<"
        "right" -> ">"
        "center" -> "^"
        "blocked" -> "X"
        else -> "?"
    }
    Text(
        text = "Dir $arrow",
        color = when (state.statusLevel) {
            ArStatusLevel.DANGER -> Color(0xFFFF9CA8)
            ArStatusLevel.WARNING -> Color(0xFFFFE08B)
            ArStatusLevel.SAFE -> Color(0xFF9AE7C7)
            ArStatusLevel.INFO -> Color(0xFFD9E2EA)
        },
    )
}

private fun formatMeters(distanceMeters: Float, isReference: Boolean = false): String {
    if (isReference) return "5m+"
    return if (distanceMeters < 1f) {
        "${(distanceMeters * 100f).toInt()} cm"
    } else {
        String.format("%.2f m", distanceMeters)
    }
}

private fun formatSpeed(speedMetersPerSecond: Float): String {
    return String.format("%.2f m/s", speedMetersPerSecond)
}

private fun formatSeconds(seconds: Float): String {
    return String.format("%.1f초", seconds)
}

private fun formatMapScore(score: Float): String {
    return "${(score.coerceIn(0f, 1f) * 100f).toInt()}"
}

private fun formatPercent(value: Float): String {
    return if (value < 9.95f) {
        String.format("%.1f", value)
    } else {
        value.toInt().coerceIn(0, 100).toString()
    }
}

private fun formatMetersShort(distanceMeters: Float, isReference: Boolean = false): String {
    if (isReference) return "5m+"
    return if (distanceMeters < 1f) {
        "${(distanceMeters * 100f).toInt()}c"
    } else {
        String.format("%.1fm", distanceMeters)
    }
}

private fun presentableMotion(label: String): String {
    return when (label) {
        "approaching_right" -> "접근/우"
        "approaching_left" -> "접근/좌"
        "approaching" -> "접근"
        "moving_right" -> "우측 이동"
        "moving_left" -> "좌측 이동"
        "receding" -> "멀어짐"
        else -> ""
    }
}

private fun presentableAvoidance(label: String): String {
    return when (label) {
        "left" -> "좌측 회피"
        "center" -> "중앙 유지"
        "right" -> "우측 회피"
        "stop_or_sidestep" -> "정지/회피"
        else -> ""
    }
}
