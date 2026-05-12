package com.example.walkassist

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.viewModels
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.launch
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
        feedbackManager = FeedbackManager(this)
        bindArStateToFeedback()

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA), 100)
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
                        onReplayTestClick = {
                            startActivity(Intent(this@MainActivity, SpatialReplayTestActivity::class.java))
                            finish()
                        },
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
            )
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
}

@Composable
private fun WalkAssistRootOverlay(
    state: ArMeasurementState,
    feedbackState: FeedbackUiState,
    onOcrClick: () -> Unit,
    onVlmClick: () -> Unit,
    onReplayTestClick: () -> Unit,
) {
    var cameraUiVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val openMapNavigation = {
        context.startActivity(Intent(context, MapNavigationActivity::class.java))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraUiVisible) {
            MeasurementOverlay(
                state = state,
                feedbackState = feedbackState,
            )
            CameraUiControls(
                onGuideClick = { cameraUiVisible = false },
                onSettingsClick = {
                    context.startActivity(Intent(context, GuideSettingsActivity::class.java))
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 14.dp, bottom = 150.dp),
            )
        } else {
            GuideStatusOverlay(
                arState = state,
                feedbackState = feedbackState,
                onCameraClick = { cameraUiVisible = true },
                onSettingsClick = {
                    context.startActivity(Intent(context, GuideSettingsActivity::class.java))
                },
                onReplayTestClick = onReplayTestClick,
            )
        }

        GuideActionChip(
            text = "길찾기",
            onClick = openMapNavigation,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 14.dp),
        )

        GuideActionChip(
            text = "OCR",
            onClick = onOcrClick,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 18.dp),
        )
        GuideActionChip(
            text = "VLM",
            onClick = onVlmClick,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 18.dp),
        )
    }
}

@Composable
private fun GuideStatusOverlay(
    arState: ArMeasurementState,
    feedbackState: FeedbackUiState,
    onCameraClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onReplayTestClick: () -> Unit,
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
            modifier = Modifier.align(Alignment.BottomEnd),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = guideSensorStatus(feedbackState.sensorStatus),
                color = palette.foreground.copy(alpha = 0.82f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(10.dp))
            GuideActionChip(
                text = "카메라 보기",
                onClick = onCameraClick,
            )
            Spacer(modifier = Modifier.height(10.dp))
            GuideActionChip(
                text = "설정",
                onClick = onSettingsClick,
            )
            Spacer(modifier = Modifier.height(10.dp))
            GuideActionChip(
                text = "영상 리플레이 테스트",
                onClick = onReplayTestClick,
            )
        }
    }
}

@Composable
private fun CameraUiControls(
    onGuideClick: () -> Unit,
    onSettingsClick: () -> Unit,
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
        Spacer(modifier = Modifier.height(10.dp))
        GuideActionChip(
            text = "설정",
            onClick = onSettingsClick,
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
) {
    var debugVisible by remember { mutableStateOf(false) }

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
            DebugOverlay(
                state = state,
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
        val boxStrokeColor = Color(0xFFFFB648)
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
                    .border(2.dp, boxStrokeColor, RoundedCornerShape(8.dp)),
            ) {
                Text(
                    text = buildString {
                        append(presentableLabel(detection.label))
                        detection.distanceMeters?.let {
                            append(" ")
                            append(formatMetersShort(it, detection.distanceIsReference))
                        }
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
private fun DebugOverlay(
    state: ArMeasurementState,
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
