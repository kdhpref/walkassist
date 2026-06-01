package com.example.walkassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
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
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.example.walkassist.feedback.core.FeedbackOutputMode
import com.example.walkassist.feedback.core.FeedbackRequest
import com.example.walkassist.feedback.core.FeedbackSensorStatus
import com.example.walkassist.feedback.core.FeedbackSource
import com.example.walkassist.feedback.core.FeedbackThresholds
import com.example.walkassist.feedback.core.FeedbackPolicy
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
import kotlin.math.roundToLong

private const val OCR_LONG_PRESS_TRIGGER_MS = 2_000L
private const val TOUCH_SLOP_PX = 36f

class MainActivity : AppCompatActivity() {
    private val fragmentContainerId = 1001
    private val feedbackViewModel by viewModels<FeedbackViewModel>()
    private val arFeedbackMapper = ArFeedbackMapper()
    private val feedbackPolicy = FeedbackPolicy()
    private lateinit var feedbackManager: FeedbackManager
    private var arFragment: WalkAssistArFragment? = null
    private var lowDistanceConfidenceStartedAtMs = 0L
    private var lastLowDistanceConfidenceAnnouncementAtMs = 0L
    private var awaitingDistanceConfidenceRecoveryAnnouncement = false
    private var lastLocalMapObstaclePulseAtMs = 0L

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
            if (WalkAssistSettings.debugPipelineFlags(this@MainActivity).geospatialEnabled &&
                !hasPreciseLocationPermission()
            ) {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (missingStartupPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                missingStartupPermissions.toTypedArray(),
                REQUEST_STARTUP_PERMISSIONS,
            )
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
                        onOcrLongPress = ::requestOneShotOcr,
                        onVlmRelease = ::requestOneShotVlm,
                        onStopReplayRecording = ::stopArCoreReplayRecording,
                        onMapOpening = { feedbackManager.stopSpeech() },
                        onPlaneMeshDebugChanged = ::setPlaneMeshDebugVisible,
                        onDebugVisualizationChanged = ::setDebugVisualizationVisible,
                        onDebugFlagsPersisted = ::persistDebugPipelineFlags,
                    )
                }
            }
        }

        root.addView(fragmentContainer)
        root.addView(overlay)
        setContentView(root)

        attachArFragmentIfCameraPermitted()
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
                feedbackPolicy.ocrRequest(message)
            )
        }
        fragment.onOneShotVlmResult = { message ->
            feedbackManager.provideFeedback(
                userRequestedFeedbackRequest(
                    message = message,
                    throttleKey = "vlm:manual_result",
                ),
                queueSpeech = true,
            )
        }
    }

    private fun attachArFragmentIfCameraPermitted(retryExistingSession: Boolean = false) {
        if (!hasCameraPermission()) {
            arFragment = null
            ArMeasurementBridge.publish(
                ArMeasurementState(
                    trackingLabel = "permission",
                    guidanceLabel = "카메라 권한을 허용하면 전방 안내를 시작합니다.",
                    statusLabel = "Camera permission required.",
                    statusLevel = ArStatusLevel.WARNING,
                    note = "Android requires CAMERA permission before ARCore session creation.",
                ),
            )
            return
        }

        val existingFragment =
            supportFragmentManager.findFragmentById(fragmentContainerId) as? WalkAssistArFragment
        if (existingFragment != null) {
            configureArFragment(existingFragment)
            if (retryExistingSession) {
                existingFragment.retrySessionAfterCameraPermissionGranted()
            }
            return
        }

        val fragment = WalkAssistArFragment()
        configureArFragment(fragment)
        supportFragmentManager.commitNow {
            replace(fragmentContainerId, fragment)
        }
    }

    private fun prepareVlmModelAtStartup() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = runCatching {
                WalkAssistVlmFactory.prepareSelected(this@MainActivity)
            }.onFailure {
                Log.w(TAG, "VLM startup preparation failed", it)
            }.getOrElse {
                VlmModelPreparationStatus(
                    modelName = WalkAssistSettings.vlmModelOption(this@MainActivity).displayName,
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

        Log.i(TAG, "One-shot OCR requested from 2s long press")
        feedbackManager.stopSpeech()
        feedbackManager.provideFeedback(
            lowPriorityStatusRequest(
                message = "문자 인식을 시작합니다.",
                throttleKey = "ocr:start",
                alertLevel = FeedbackAlertLevel.CAUTION,
            )
        )
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

        announceVlmDirectionHint(ArMeasurementBridge.state.value)
        fragment.requestOneShotVlm()
    }

    private fun announceVlmDirectionHint(state: ArMeasurementState) {
        val message = vlmDirectionHintMessage(state) ?: return
        feedbackManager.provideFeedback(
            userRequestedFeedbackRequest(
                message = message,
                throttleKey = "vlm:direction_hint",
            )
        )
    }

    private fun vlmDirectionHintMessage(state: ArMeasurementState): String? {
        if (state.trackingLabel != "tracking") {
            return "보행 가능 방향 확인 필요"
        }
        return when (state.suggestedDirection) {
            "left" -> "왼쪽 공간 확보됨"
            "right" -> "오른쪽 공간 확보됨"
            "center" -> "전방 공간 확보됨"
            "blocked", "stop_or_sidestep" -> "공간 확보 안됨. 보행 가능 방향 확인 필요"
            "searching", "unknown" -> "보행 가능 방향 확인 필요"
            else -> null
        }
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

    private fun setDebugVisualizationVisible(visible: Boolean) {
        val fragment = arFragment
            ?: (supportFragmentManager.findFragmentById(fragmentContainerId) as? WalkAssistArFragment)
                ?.also(::configureArFragment)
        fragment?.setDebugVisualizationVisible(visible)
    }

    private fun bindArStateToFeedback() {
        feedbackViewModel.startWatchdog()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    ArMeasurementBridge.state.collect { state ->
                        maybeAnnounceLowDistanceConfidence(state)
                        maybePulseLocalMapObstacle(state)
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

    private fun maybeAnnounceLowDistanceConfidence(state: ArMeasurementState) {
        val now = SystemClock.elapsedRealtime()
        if (!shouldMonitorDistanceConfidence(state)) {
            lowDistanceConfidenceStartedAtMs = 0L
            awaitingDistanceConfidenceRecoveryAnnouncement = false
            return
        }

        if (!state.hasLowDistanceConfidence()) {
            lowDistanceConfidenceStartedAtMs = 0L
            maybeAnnounceDistanceConfidenceRecovered()
            return
        }

        if (lowDistanceConfidenceStartedAtMs == 0L) {
            lowDistanceConfidenceStartedAtMs = now
            return
        }

        val lowConfidenceDurationMs = now - lowDistanceConfidenceStartedAtMs
        val elapsedSinceLastAnnouncementMs = now - lastLowDistanceConfidenceAnnouncementAtMs
        if (
            lowConfidenceDurationMs < LOW_DISTANCE_CONFIDENCE_PERSISTENCE_MS ||
            elapsedSinceLastAnnouncementMs < LOW_DISTANCE_CONFIDENCE_ANNOUNCE_COOLDOWN_MS ||
            !WalkAssistSettings.isArcoreTtsEnabled(this)
        ) {
            return
        }

        feedbackManager.provideFeedback(
            lowPriorityStatusRequest(
                message = LOW_DISTANCE_CONFIDENCE_MESSAGE,
                throttleKey = "sensor:low_distance_confidence",
                alertLevel = FeedbackAlertLevel.CAUTION,
                throttleMillis = LOW_DISTANCE_CONFIDENCE_ANNOUNCE_COOLDOWN_MS,
            )
        )
        lastLowDistanceConfidenceAnnouncementAtMs = now
        awaitingDistanceConfidenceRecoveryAnnouncement = true
    }

    private fun maybeAnnounceDistanceConfidenceRecovered() {
        if (!awaitingDistanceConfidenceRecoveryAnnouncement) return

        awaitingDistanceConfidenceRecoveryAnnouncement = false
    }

    private fun lowPriorityStatusRequest(
        message: String,
        throttleKey: String,
        alertLevel: FeedbackAlertLevel = FeedbackAlertLevel.SAFE,
        throttleMillis: Long = FeedbackThresholds.SENSOR_STATUS_THROTTLE_MS,
    ): FeedbackRequest {
        return FeedbackRequest(
            priority = 4,
            source = FeedbackSource.AR_OBSTACLE,
            alertLevel = alertLevel,
            message = message,
            outputMode = FeedbackOutputMode(
                useSpeech = true,
                useHaptic = false,
            ),
            interruptCurrent = false,
            throttleKey = throttleKey,
            throttleMillis = throttleMillis,
        )
    }

    private fun userRequestedFeedbackRequest(
        message: String,
        throttleKey: String,
    ): FeedbackRequest {
        return FeedbackRequest(
            priority = 3,
            source = FeedbackSource.OCR,
            alertLevel = FeedbackAlertLevel.CAUTION,
            message = message,
            outputMode = FeedbackOutputMode(
                useSpeech = true,
                useHaptic = false,
            ),
            interruptCurrent = false,
            throttleKey = throttleKey,
            throttleMillis = FeedbackThresholds.OCR_THROTTLE_MS,
        )
    }

    private fun maybePulseLocalMapObstacle(state: ArMeasurementState) {
        val obstacleDistance = state.collisionDistanceMeters ?: return
        if (state.trackingLabel != "tracking" || state.worldMapOccupiedCells <= 0) {
            return
        }

        if (obstacleDistance > LOCAL_MAP_WATCH_OBSTACLE_METERS) return

        val now = SystemClock.elapsedRealtime()
        val intervalMs = pulseIntervalForObstacleDistance(obstacleDistance)
        if (now - lastLocalMapObstaclePulseAtMs < intervalMs) return

        feedbackManager.playObstaclePulse(
            urgent = obstacleDistance <= LOCAL_MAP_CRITICAL_OBSTACLE_METERS
        )
        lastLocalMapObstaclePulseAtMs = now
    }

    private fun pulseIntervalForObstacleDistance(distanceMeters: Float): Long {
        val normalized = (
            (distanceMeters - LOCAL_MAP_CRITICAL_OBSTACLE_METERS) /
                (LOCAL_MAP_WATCH_OBSTACLE_METERS - LOCAL_MAP_CRITICAL_OBSTACLE_METERS)
            ).coerceIn(0f, 1f)
        return (
            LOCAL_MAP_FASTEST_PULSE_INTERVAL_MS +
                ((LOCAL_MAP_SLOWEST_PULSE_INTERVAL_MS - LOCAL_MAP_FASTEST_PULSE_INTERVAL_MS) * normalized)
            ).roundToLong()
    }

    private fun shouldMonitorDistanceConfidence(state: ArMeasurementState): Boolean {
        return state.trackingLabel !in setOf(
            "permission",
            "unavailable",
            "video_replay",
        )
    }

    private fun ArMeasurementState.hasLowDistanceConfidence(): Boolean {
        val noMeasuredDistance = collisionDistanceMeters == null &&
            centerDistanceMeters == null &&
            depthDistanceMeters == null &&
            floorDistanceMeters == null
        val noFloorPlane = horizontalPlaneCount <= 0 && floorDistanceMeters == null
        return trackingLabel != "tracking" ||
            sensingConfidenceScore < LOW_DISTANCE_CONFIDENCE_SCORE_THRESHOLD ||
            (noMeasuredDistance && noFloorPlane)
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

    private fun hasPreciseLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun persistDebugPipelineFlags(flags: DebugPipelineFlags) {
        WalkAssistSettings.setDebugPipelineFlags(this, flags)
        if (flags.geospatialEnabled) {
            requestPreciseLocationForGeospatial()
        }
    }

    private fun requestPreciseLocationForGeospatial() {
        if (hasPreciseLocationPermission()) {
            Toast.makeText(
                this,
                "정확한 위치 권한이 이미 허용되어 있습니다.",
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        Toast.makeText(
            this,
            "Geospatial 사용을 위해 정확한 위치 권한을 요청합니다.",
            Toast.LENGTH_SHORT,
        ).show()
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            REQUEST_GEOSPATIAL_LOCATION_PERMISSION,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_STARTUP_PERMISSIONS) {
            val cameraWasRequested = permissions.contains(Manifest.permission.CAMERA)
            val cameraGranted = permissions
                .zip(grantResults.toTypedArray())
                .any { (permission, result) ->
                    permission == Manifest.permission.CAMERA &&
                        result == PackageManager.PERMISSION_GRANTED
                }
            if (cameraGranted || hasCameraPermission()) {
                attachArFragmentIfCameraPermitted(retryExistingSession = cameraWasRequested)
            } else {
                Toast.makeText(
                    this,
                    "카메라 권한이 필요합니다. 권한을 허용한 뒤 다시 실행해 주세요.",
                    Toast.LENGTH_LONG,
                ).show()
                attachArFragmentIfCameraPermitted(retryExistingSession = cameraWasRequested)
            }

            if (!permissions.contains(Manifest.permission.ACCESS_FINE_LOCATION)) return
        } else if (requestCode != REQUEST_GEOSPATIAL_LOCATION_PERMISSION) {
            return
        }

        val preciseGranted = permissions
            .zip(grantResults.toTypedArray())
            .any { (permission, result) ->
                permission == Manifest.permission.ACCESS_FINE_LOCATION &&
                    result == PackageManager.PERMISSION_GRANTED
            }
        Toast.makeText(
            this,
            if (preciseGranted) {
                "정확한 위치 권한이 허용되었습니다. Geospatial을 다시 안정화합니다."
            } else {
                "Geospatial은 정확한 위치 권한이 필요합니다. 앱 설정에서 정확한 위치를 허용해 주세요."
            },
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun configureFullscreenCutout() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 35) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
        }
    }

    companion object {
        private const val TAG = "WalkAssistMain"
        private const val REQUEST_STARTUP_PERMISSIONS = 100
        private const val REQUEST_GEOSPATIAL_LOCATION_PERMISSION = 101
        private const val LOW_DISTANCE_CONFIDENCE_SCORE_THRESHOLD = 55
        private const val LOW_DISTANCE_CONFIDENCE_PERSISTENCE_MS = 15_000L
        private const val LOW_DISTANCE_CONFIDENCE_ANNOUNCE_COOLDOWN_MS = 60_000L
        private const val LOW_DISTANCE_CONFIDENCE_MESSAGE =
            "거리 신뢰도값이 낮음. 바닥과 주변 경계를 인식할 수 있도록 천천히 카메라를 움직여주세요."
        private const val LOCAL_MAP_WATCH_OBSTACLE_METERS = 3.0f
        private const val LOCAL_MAP_CRITICAL_OBSTACLE_METERS = 0.7f
        private const val LOCAL_MAP_FASTEST_PULSE_INTERVAL_MS = 500L
        private const val LOCAL_MAP_SLOWEST_PULSE_INTERVAL_MS = 2_000L
    }
}

@Composable
private fun WalkAssistRootOverlay(
    state: ArMeasurementState,
    feedbackState: FeedbackUiState,
    onOcrLongPress: () -> Unit,
    onVlmRelease: () -> Unit,
    onStopReplayRecording: () -> Unit,
    onMapOpening: () -> Unit,
    onPlaneMeshDebugChanged: (Boolean) -> Unit,
    onDebugVisualizationChanged: (Boolean) -> Unit,
    onDebugFlagsPersisted: (DebugPipelineFlags) -> Unit,
) {
    var cameraUiVisible by remember { mutableStateOf(false) }
    var appLanguage by remember { mutableStateOf(WalkAssistLanguage.KO) }
    val uiText = walkAssistUiText(appLanguage)
    var replayState by remember { mutableStateOf(ArCoreReplayController.currentState()) }
    val context = LocalContext.current
    var debugFlags by remember { mutableStateOf(WalkAssistSettings.debugPipelineFlags(context)) }
    val resourceMonitor = remember(context) { ResourceMonitor(context.applicationContext) }
    var resourceUsage by remember { mutableStateOf(ResourceUsageSnapshot()) }
    val updateDebugFlags: (DebugPipelineFlags) -> Unit = { nextFlags ->
        debugFlags = nextFlags
        onDebugFlagsPersisted(nextFlags)
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
        onMapOpening()
        context.startActivity(Intent(context, MapNavigationActivity::class.java))
    }
    val openSettings = {
        context.startActivity(Intent(context, GuideSettingsActivity::class.java))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .walkAssistCameraGestureInput(
                    onOcrLongPress = onOcrLongPress,
                    onVlmRelease = onVlmRelease,
                ),
        )

        if (cameraUiVisible) {
            MeasurementOverlay(
                state = state,
                feedbackState = feedbackState,
                language = appLanguage,
                debugFlags = debugFlags,
                onDebugFlagsChanged = updateDebugFlags,
                onPlaneMeshDebugChanged = onPlaneMeshDebugChanged,
                onDebugVisualizationChanged = onDebugVisualizationChanged,
            )
            CameraUiControls(
                language = appLanguage,
                onGuideClick = { cameraUiVisible = false },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 185.dp),
            )
        } else {
            GuideStatusOverlay(
                arState = state,
                feedbackState = feedbackState,
                language = appLanguage,
                onCameraClick = { cameraUiVisible = true },
                onMapClick = openMapNavigation,
            )
        }

        if (!cameraUiVisible) {
            FeedbackOverlayCard(
                state = feedbackState,
                languageCode = appLanguage.code,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 18.dp),
            )
        }

        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 104.dp, end = 6.dp),
            horizontalAlignment = Alignment.End,
        ) {
            GuideActionChip(
                text = uiText.settings,
                onClick = openSettings,
                contentDescription = uiText.settingsA11y,
            )
            Spacer(modifier = Modifier.height(10.dp))
            GuideActionChip(
                text = uiText.languageToggle,
                onClick = { appLanguage = appLanguage.toggle() },
                contentDescription = uiText.languageToggleA11y,
            )
        }

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
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.walkAssistCameraGestureInput(
    onOcrLongPress: () -> Unit,
    onVlmRelease: () -> Unit,
): Modifier = composed {
    val gestureState = remember {
        object {
            val handler = Handler(Looper.getMainLooper())
            var downAtMs = 0L
            var ocrTriggered = false
            var pointerMovedTooFar = false
            var downX = 0f
            var downY = 0f
            var triggerOcr: Runnable? = null

            fun clearTimer() {
                triggerOcr?.let(handler::removeCallbacks)
                triggerOcr = null
            }

            fun reset() {
                clearTimer()
                downAtMs = 0L
                ocrTriggered = false
                pointerMovedTooFar = false
                downX = 0f
                downY = 0f
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            gestureState.reset()
        }
    }

    pointerInteropFilter { event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gestureState.reset()
                gestureState.downAtMs = event.eventTime
                gestureState.downX = event.x
                gestureState.downY = event.y
                val triggerOcr = Runnable {
                    if (
                        gestureState.downAtMs > 0L &&
                        !gestureState.pointerMovedTooFar &&
                        !gestureState.ocrTriggered
                    ) {
                        gestureState.ocrTriggered = true
                        gestureState.clearTimer()
                        onOcrLongPress()
                    }
                }
                gestureState.triggerOcr = triggerOcr
                gestureState.handler.postDelayed(triggerOcr, OCR_LONG_PRESS_TRIGGER_MS)
                true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - gestureState.downX
                val dy = event.y - gestureState.downY
                if ((dx * dx) + (dy * dy) > TOUCH_SLOP_PX * TOUCH_SLOP_PX) {
                    gestureState.pointerMovedTooFar = true
                    gestureState.clearTimer()
                }
                true
            }

            MotionEvent.ACTION_UP -> {
                gestureState.clearTimer()
                if (
                    gestureState.downAtMs > 0L &&
                    !gestureState.pointerMovedTooFar &&
                    !gestureState.ocrTriggered
                ) {
                    onVlmRelease()
                }
                gestureState.reset()
                true
            }

            MotionEvent.ACTION_CANCEL -> {
                gestureState.reset()
                true
            }

            else -> true
        }
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

private val GUIDE_STATUS_CONTENT_OFFSET_Y = (-56).dp

@Composable
private fun GuideStatusOverlay(
    arState: ArMeasurementState,
    feedbackState: FeedbackUiState,
    language: WalkAssistLanguage,
    onCameraClick: () -> Unit,
    onMapClick: () -> Unit,
) {
    val palette = guidePalette(feedbackState.alertLevel, feedbackState.sensorStatus, language)
    val uiText = walkAssistUiText(language)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(24.dp),
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = GUIDE_STATUS_CONTENT_OFFSET_Y),
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
                text = presentableFeedbackMessage(feedbackState, language),
                color = palette.foreground,
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(14.dp))
            Text(
                text = guideDistanceText(feedbackState, arState, language),
                color = palette.foreground.copy(alpha = 0.86f),
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
            )
            if (arState.routeRealityGuidanceLabel.isNotBlank()) {
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = arState.routeRealityGuidanceLabel,
                    color = palette.foreground,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                if (arState.routeRealityGuidanceDetail.isNotBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = arState.routeRealityGuidanceDetail,
                        color = palette.foreground.copy(alpha = 0.82f),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
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
            Text(
                text = guideSensorStatus(feedbackState.sensorStatus, language),
                color = palette.foreground.copy(alpha = 0.82f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(10.dp))
            GuideActionChip(
                text = uiText.navigation,
                onClick = onMapClick,
                contentDescription = uiText.navigationA11y,
            )
            Spacer(modifier = Modifier.height(10.dp))
            GuideActionChip(
                text = uiText.cameraView,
                onClick = onCameraClick,
                modifier = Modifier.width(112.dp),
                contentDescription = uiText.cameraViewA11y,
            )
        }
    }
}

@Composable
private fun CameraUiControls(
    language: WalkAssistLanguage,
    onGuideClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val uiText = walkAssistUiText(language)
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.End,
    ) {
        GuideActionChip(
            text = uiText.guideScreen,
            onClick = onGuideClick,
            contentDescription = uiText.guideScreenA11y,
        )
    }
}

@Composable
private fun GuideActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = text,
) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = modifier
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            .defaultMinSize(minWidth = 72.dp, minHeight = 48.dp)
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

private enum class WalkAssistLanguage(val code: String) {
    KO("ko"),
    EN("en"),
}

private fun WalkAssistLanguage.toggle(): WalkAssistLanguage {
    return if (this == WalkAssistLanguage.KO) WalkAssistLanguage.EN else WalkAssistLanguage.KO
}

private data class WalkAssistUiText(
    val navigation: String,
    val navigationA11y: String,
    val cameraView: String,
    val cameraViewA11y: String,
    val settings: String,
    val settingsA11y: String,
    val guideScreen: String,
    val guideScreenA11y: String,
    val languageToggle: String,
    val languageToggleA11y: String,
)

private fun walkAssistUiText(language: WalkAssistLanguage): WalkAssistUiText {
    return when (language) {
        WalkAssistLanguage.KO -> WalkAssistUiText(
            navigation = "길찾기",
            navigationA11y = "길찾기 화면으로 이동",
            cameraView = "카메라 보기",
            cameraViewA11y = "카메라 화면 보기",
            settings = "설정",
            settingsA11y = "설정 화면으로 이동",
            guideScreen = "큰 화면",
            guideScreenA11y = "큰 안내 화면으로 이동",
            languageToggle = "English",
            languageToggleA11y = "영어 표시로 변경",
        )

        WalkAssistLanguage.EN -> WalkAssistUiText(
            navigation = "Route",
            navigationA11y = "Open route navigation",
            cameraView = "Camera",
            cameraViewA11y = "Open camera view",
            settings = "Settings",
            settingsA11y = "Open settings",
            guideScreen = "Guide",
            guideScreenA11y = "Open guide screen",
            languageToggle = "Korean",
            languageToggleA11y = "Change display language to Korean",
        )
    }
}

private fun guidePalette(
    alertLevel: FeedbackAlertLevel,
    sensorStatus: FeedbackSensorStatus,
    language: WalkAssistLanguage,
): GuidePalette {
    if (sensorStatus == FeedbackSensorStatus.WAITING || sensorStatus == FeedbackSensorStatus.DISCONNECTED) {
        return GuidePalette(
            background = Color(0xFF4A5568),
            foreground = Color.White,
            title = if (language == WalkAssistLanguage.EN) "Waiting" else "대기",
            icon = "..."
        )
    }
    return when (alertLevel) {
        FeedbackAlertLevel.SAFE -> GuidePalette(
            background = Color(0xFF15803D),
            foreground = Color.White,
            title = if (language == WalkAssistLanguage.EN) "Safe" else "안전",
            icon = "OK",
        )
        FeedbackAlertLevel.CAUTION -> GuidePalette(
            background = Color(0xFFF59E0B),
            foreground = Color(0xFF17120A),
            title = if (language == WalkAssistLanguage.EN) "Caution" else "주의",
            icon = "!",
        )
        FeedbackAlertLevel.DANGER -> GuidePalette(
            background = Color(0xFFDC2626),
            foreground = Color.White,
            title = if (language == WalkAssistLanguage.EN) "Danger" else "위험",
            icon = "!!",
        )
    }
}

private fun presentableFeedbackMessage(
    feedbackState: FeedbackUiState,
    language: WalkAssistLanguage,
): String {
    if (language == WalkAssistLanguage.KO) return feedbackState.message

    return when (feedbackState.sensorStatus) {
        FeedbackSensorStatus.WAITING -> "Collecting spatial information."
        FeedbackSensorStatus.DISCONNECTED -> "Sensor data is temporarily disconnected."
        FeedbackSensorStatus.ERROR -> "A spatial recognition problem occurred."
        FeedbackSensorStatus.CONNECTED -> when (feedbackState.alertLevel) {
            FeedbackAlertLevel.SAFE -> "Path ahead is clear."
            FeedbackAlertLevel.CAUTION -> englishDirectionMessage(feedbackState.direction)
                ?: "Check the path ahead."
            FeedbackAlertLevel.DANGER -> englishDirectionMessage(feedbackState.direction)
                ?: "Obstacle ahead. Stop and check your surroundings."
        }
    }
}

private fun englishDirectionMessage(direction: String): String? {
    return when (direction.lowercase()) {
        "left" -> "Move left."
        "right" -> "Move right."
        "center" -> "Keep center."
        "blocked" -> "Stop and check your surroundings."
        else -> null
    }
}

private fun guideDistanceText(
    feedbackState: FeedbackUiState,
    arState: ArMeasurementState,
    language: WalkAssistLanguage,
): String {
    val distance = feedbackState.distanceMeters ?: arState.collisionDistanceMeters
    val confidence = (feedbackState.confidence * 100f).toInt().coerceIn(0, 100)
    return if (distance == null) {
        if (language == WalkAssistLanguage.EN) {
            "Collecting spatial information."
        } else {
            "공간 정보를 수집하는 중입니다."
        }
    } else {
        if (language == WalkAssistLanguage.EN) {
            "Ahead ${formatMetersShort(distance)} / confidence $confidence%"
        } else {
            "전방 ${formatMetersShort(distance)} / 신뢰도 $confidence%"
        }
    }
}

private fun guideSensorStatus(
    status: FeedbackSensorStatus,
    language: WalkAssistLanguage,
): String {
    return when (language) {
        WalkAssistLanguage.KO -> when (status) {
            FeedbackSensorStatus.WAITING -> "센서 대기 중"
            FeedbackSensorStatus.CONNECTED -> "센서 연결됨"
            FeedbackSensorStatus.DISCONNECTED -> "센서 끊김"
            FeedbackSensorStatus.ERROR -> "센서 오류"
        }

        WalkAssistLanguage.EN -> when (status) {
            FeedbackSensorStatus.WAITING -> "Sensor waiting"
            FeedbackSensorStatus.CONNECTED -> "Sensor connected"
            FeedbackSensorStatus.DISCONNECTED -> "Sensor disconnected"
            FeedbackSensorStatus.ERROR -> "Sensor error"
        }
    }
}

@Composable
private fun MeasurementOverlay(
    state: ArMeasurementState,
    feedbackState: FeedbackUiState,
    language: WalkAssistLanguage,
    debugFlags: DebugPipelineFlags,
    onDebugFlagsChanged: (DebugPipelineFlags) -> Unit,
    onPlaneMeshDebugChanged: (Boolean) -> Unit,
    onDebugVisualizationChanged: (Boolean) -> Unit,
) {
    var debugVisible by remember { mutableStateOf(false) }

    LaunchedEffect(debugVisible, debugFlags.arCoreHitTestEnabled) {
        onPlaneMeshDebugChanged(debugVisible && debugFlags.arCoreHitTestEnabled)
        onDebugVisualizationChanged(debugVisible)
    }
    DisposableEffect(Unit) {
        onDispose {
            onPlaneMeshDebugChanged(false)
            onDebugVisualizationChanged(false)
        }
    }

    Box(
        modifier = Modifier.fillMaxSize(),
    ) {
        if (debugVisible) {
            ObjectDetectionOverlay(
                detections = state.objectDetections,
                modifier = Modifier.fillMaxSize(),
            )
        }

        if (debugVisible) {
            CompactWorldMapOverlay(
                state = state,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 18.dp),
            )
        }

        FeedbackOverlayCard(
            state = feedbackState,
            languageCode = language.code,
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
            val (riskText, riskColor) = presentableRisk(state.riskLabel, language)
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
                text = presentableArGuidance(state.guidanceLabel, language),
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
            if (state.routeRealityGuidanceLabel.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.routeRealityGuidanceLabel,
                    color = Color(0xFFB6E7FF),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                )
                if (state.routeRealityGuidanceDetail.isNotBlank()) {
                    Text(
                        text = state.routeRealityGuidanceDetail,
                        color = Color(0xFFD8E3EE),
                        fontSize = 11.sp,
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            ConfidenceBar(
                score = state.sensingConfidenceScore,
                color = confidenceColor,
            )
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = if (language == WalkAssistLanguage.EN) {
                    "Spatial confidence ${state.sensingConfidenceScore}"
                } else {
                    "공간 인식 신뢰도 ${state.sensingConfidenceScore}점"
                },
                color = confidenceColor,
                fontSize = 13.sp,
            )
            state.timeToCollisionSeconds?.takeIf { state.riskLabel != "stable" }?.let { ttc ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (language == WalkAssistLanguage.EN) {
                        "Collision in ${formatSeconds(ttc, language)}"
                    } else {
                        "충돌 예상 ${formatSeconds(ttc, language)}"
                    },
                    color = riskColor,
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
            if (debugFlags.rawDepthEnabled) {
                DepthGridOverlay(
                    cells = state.depthGridCells,
                    geospatialEnabled = debugFlags.geospatialEnabled,
                    geospatialStatusLabel = state.geospatialStatusLabel,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (debugFlags.rawDepthEnabled && debugFlags.walkingZoneDistanceEnabled) {
                WalkingZoneDistanceOverlay(
                    samples = state.walkingZoneDepthSamples,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            DebugOverlay(
                state = state,
                feedbackState = feedbackState,
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

        Canvas(modifier = Modifier.fillMaxSize()) {
            detections.forEach { detection ->
                val polygon = detection.segmentPolygon
                if (polygon.size >= 3) {
                    val path = Path().apply {
                        moveTo(polygon.first().xRatio * size.width, polygon.first().yRatio * size.height)
                        polygon.drop(1).forEach { point ->
                            lineTo(point.xRatio * size.width, point.yRatio * size.height)
                        }
                        close()
                    }
                    val color = if (detection.distanceMeters != null && detection.distanceMeters < 1.2f) {
                        Color(0xFFE85D75)
                    } else {
                        Color(0xFFFFB648)
                    }
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.28f),
                    )
                    drawPath(
                        path = path,
                        color = color.copy(alpha = 0.92f),
                        style = Stroke(width = 3f),
                    )
                }
            }
        }

        detections.forEach { detection ->
            val boxLeft = maxWidth * detection.leftRatio.coerceIn(0f, 1f)
            val boxTop = maxHeight * detection.topRatio.coerceIn(0f, 1f)
            val boxWidth = maxWidth * detection.widthRatio.coerceIn(0.05f, 1f)
            val boxHeight = maxHeight * detection.heightRatio.coerceIn(0.05f, 1f)
            val showFallbackBox = detection.segmentPolygon.size < 3
            val boxModifier = Modifier
                .offset(x = boxLeft, y = boxTop)
                .width(boxWidth)
                .height(boxHeight)
                .let { base ->
                    if (showFallbackBox) {
                        base.border(2.dp, Color(0xFFFFB648), RoundedCornerShape(8.dp))
                    } else {
                        base
                    }
                }

            Box(
                modifier = boxModifier,
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
private fun DepthGridOverlay(
    cells: List<DepthGridCell>,
    geospatialEnabled: Boolean = false,
    geospatialStatusLabel: String = "off",
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val cellWidth = maxWidth / 4
        val cellHeight = maxHeight / 4
        val cellsByPosition = cells.associateBy { it.column to it.row }
        val longRangeCount = cells.count { it.isLongRange && it.distanceMeters != null }

        if (geospatialEnabled) {
            Text(
                text = "Geospatial depth $geospatialStatusLabel / long-range $longRangeCount",
                color = if (geospatialStatusLabel == "enabled") Color(0xFFB6E7FF) else Color(0xFFFFDB7A),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 110.dp)
                    .background(Color(0xAA121820), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }

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
                        text = depthGridLabel(cell),
                        color = Color.White,
                        fontSize = if (cell?.isLongRange == true) 14.sp else 18.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .background(depthGridColor(cell), RoundedCornerShape(10.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun WalkingZoneDistanceOverlay(
    samples: List<WalkingZoneDepthSample>,
    modifier: Modifier = Modifier,
) {
    if (samples.isEmpty()) return

    BoxWithConstraints(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val leftBoundary = size.width * 0.38f
            val rightBoundary = size.width * 0.62f
            val topY = size.height * 0.4f
            val bottomY = size.height * 0.82f
            drawRect(
                color = Color(0x2222C55E),
                topLeft = Offset(0f, topY),
                size = Size(leftBoundary, bottomY - topY),
            )
            drawRect(
                color = Color(0x2238BDF8),
                topLeft = Offset(leftBoundary, topY),
                size = Size(rightBoundary - leftBoundary, bottomY - topY),
            )
            drawRect(
                color = Color(0x22F59E0B),
                topLeft = Offset(rightBoundary, topY),
                size = Size(size.width - rightBoundary, bottomY - topY),
            )
            drawLine(
                color = Color(0xAAFFFFFF),
                start = Offset(leftBoundary, topY),
                end = Offset(leftBoundary, bottomY),
                strokeWidth = 2f,
            )
            drawLine(
                color = Color(0xAAFFFFFF),
                start = Offset(rightBoundary, topY),
                end = Offset(rightBoundary, bottomY),
                strokeWidth = 2f,
            )
        }

        samples.forEach { sample ->
            val markerColor = walkingZoneSampleColor(sample.distanceMeters)
            val x = maxWidth * sample.xRatio
            val y = maxHeight * sample.yRatio
            if (sample.distanceMeters == null) {
                Box(
                    modifier = Modifier
                        .offset(x = x - 4.dp, y = y - 4.dp)
                        .size(8.dp)
                        .border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(999.dp))
                        .background(Color(0x55121820), RoundedCornerShape(999.dp)),
                )
            } else {
                Text(
                    text = formatMetersShort(sample.distanceMeters),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .offset(x = x - 18.dp, y = y - 10.dp)
                        .width(36.dp)
                        .background(markerColor, RoundedCornerShape(8.dp))
                        .padding(horizontal = 3.dp, vertical = 2.dp),
                )
            }
        }
    }
}

private fun walkingZoneSampleColor(distanceMeters: Float?): Color {
    return when {
        distanceMeters == null -> Color(0x66121820)
        distanceMeters < 1.2f -> Color(0xEADC2626)
        distanceMeters < 2.5f -> Color(0xEAF59E0B)
        else -> Color(0xEA15803D)
    }
}

private fun isDepthProfilePitchUsable(pitchDownDegrees: Float): Boolean {
    return pitchDownDegrees in 18f..78f
}

private fun depthProfilePitchLabel(pitchDownDegrees: Float): String {
    return when {
        pitchDownDegrees < 18f -> "low"
        pitchDownDegrees > 78f -> "steep"
        pitchDownDegrees < 30f -> "soft"
        else -> "on"
    }
}

@Composable
private fun DebugOverlay(
    state: ArMeasurementState,
    feedbackState: FeedbackUiState,
    debugFlags: DebugPipelineFlags,
    onDebugFlagsChanged: (DebugPipelineFlags) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = Modifier
            .then(modifier)
            .wrapContentWidth()
            .width(220.dp)
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
            label = "Geospatial",
            enabled = debugFlags.geospatialEnabled,
            onClick = {
                onDebugFlagsChanged(debugFlags.copy(geospatialEnabled = !debugFlags.geospatialEnabled))
            },
        )
        DebugToggleRow(
            label = "보행구역 거리측정",
            enabled = debugFlags.walkingZoneDistanceEnabled,
            onClick = {
                onDebugFlagsChanged(
                    debugFlags.copy(walkingZoneDistanceEnabled = !debugFlags.walkingZoneDistanceEnabled),
                )
            },
        )
        DebugToggleRow(
            label = "Map",
            enabled = debugFlags.localMapEnabled,
            onClick = { onDebugFlagsChanged(debugFlags.copy(localMapEnabled = !debugFlags.localMapEnabled)) },
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
        if (state.trackingFailureLabel.isNotBlank()) {
            Text("Tracking issue: ${state.trackingFailureLabel}", color = Color(0xFFFFE08B))
        }
        val geospatialActiveLabel = if (debugFlags.geospatialEnabled) "ON" else "OFF"
        Text(
            "Geo flag $geospatialActiveLabel / ${state.geospatialStatusLabel} ${state.geospatialEarthStateLabel}".trim(),
            color = if (debugFlags.geospatialEnabled && state.geospatialStatusLabel == "enabled") {
                Color(0xFFB6E7FF)
            } else {
                Color(0xFFD9E2EA)
            },
        )
        if (debugFlags.geospatialEnabled) {
            Text("Streetscape: ${state.geospatialStreetscapeGeometryCount}", color = Color(0xFFD9E2EA))
        }
        if (state.routeRealityGuidanceLabel.isNotBlank()) {
            Text("Route: ${state.routeRealityGuidanceAction}/${state.routeRealityGuidanceSource}", color = Color(0xFFB6E7FF))
            Text(state.routeRealityGuidanceDetail, color = Color(0xFFD9E2EA))
        }
        Text(
            "Pitch ${state.pitchDownDegrees.toInt()}deg / profile ${depthProfilePitchLabel(state.pitchDownDegrees)}",
            color = if (isDepthProfilePitchUsable(state.pitchDownDegrees)) Color(0xFFB7F7CE) else Color(0xFFFFDB7A),
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
        val rawGridMinDistance = state.depthGridCells.mapNotNull { it.distanceMeters }.minOrNull()
        val walkingSampleMinDistance = state.walkingZoneDepthSamples.mapNotNull { it.distanceMeters }.minOrNull()
        Text(
            "Alert/Beep ${state.collisionDistanceMeters?.let(::formatMetersShort) ?: "-"}  UI ${feedbackState.distanceMeters?.let(::formatMetersShort) ?: "-"}",
            color = Color(0xFFFFE08B),
        )
        Text(
            "Lane near L ${state.leftDistanceMeters?.let(::formatMetersShort) ?: "-"} / C ${state.centerDistanceMeters?.let(::formatMetersShort) ?: "-"} / R ${state.rightDistanceMeters?.let(::formatMetersShort) ?: "-"}",
            color = Color(0xFFD9E2EA),
        )
        Text(
            "Raw 4x4 min ${rawGridMinDistance?.let(::formatMetersShort) ?: "-"}  Walk min ${walkingSampleMinDistance?.let(::formatMetersShort) ?: "-"}",
            color = Color(0xFFD9E2EA),
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

private fun presentableRisk(
    riskLabel: String,
    language: WalkAssistLanguage = WalkAssistLanguage.KO,
): Pair<String, Color> {
    return when (riskLabel) {
        "critical" -> (if (language == WalkAssistLanguage.EN) "Danger" else "위험") to Color(0xFFFF8E8E)
        "high" -> (if (language == WalkAssistLanguage.EN) "Warning" else "경고") to Color(0xFFFFC870)
        "watch" -> (if (language == WalkAssistLanguage.EN) "Caution" else "주의") to Color(0xFFFFDB7A)
        "stable" -> (if (language == WalkAssistLanguage.EN) "Safe" else "안전") to Color(0xFF96E2B5)
        else -> (if (language == WalkAssistLanguage.EN) "Caution" else "주의") to Color(0xFFD8E3EE)
    }
}

private fun presentableArGuidance(
    guidanceLabel: String,
    language: WalkAssistLanguage,
): String {
    if (language == WalkAssistLanguage.KO) return guidanceLabel

    return when {
        guidanceLabel.contains("안전") -> "Path ahead is clear."
        guidanceLabel.contains("왼쪽") || guidanceLabel.contains("좌측") -> "Move left."
        guidanceLabel.contains("오른쪽") || guidanceLabel.contains("우측") -> "Move right."
        guidanceLabel.contains("중앙") -> "Keep center."
        guidanceLabel.contains("정지") || guidanceLabel.contains("멈") -> "Stop and check your surroundings."
        guidanceLabel.contains("주의") -> "Check the path ahead."
        guidanceLabel.contains("위험") -> "Obstacle ahead."
        guidanceLabel.isBlank() -> "Scanning surroundings."
        else -> guidanceLabel
    }
}

private fun depthGridLabel(cell: DepthGridCell?): String {
    val distance = cell?.distanceMeters ?: return "--"
    return if (cell.isLongRange) {
        "GEO?\n${formatMetersShort(distance)}"
    } else {
        formatMetersShort(distance)
    }
}

private fun depthGridColor(cell: DepthGridCell?): Color {
    val distance = cell?.distanceMeters ?: return Color(0xFFFFB648)
    if (cell.isLongRange) return Color(0xDD0E7490)
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

private fun formatSeconds(
    seconds: Float,
    language: WalkAssistLanguage = WalkAssistLanguage.KO,
): String {
    return if (language == WalkAssistLanguage.EN) {
        String.format("%.1fs", seconds)
    } else {
        String.format("%.1f초", seconds)
    }
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
