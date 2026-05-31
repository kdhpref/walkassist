package com.example.walkassist

/*
 * ============================================================
 * 작업자 C(여준호) 수정 안내 - 버튼 UI/UX 언어 전환 기능
 * ============================================================
 * 수정 목적:
 * 1) 시각장애인 사용자가 화면의 주요 버튼을 한국어/영어로 전환해서 볼 수 있게 함
 * 2) 버튼에 TalkBack 접근성 설명(contentDescription)을 붙여 기능을 명확히 안내함
 * 3) MainActivity.kt는 A/B/C 작업자가 함께 쓰는 공통 파일이므로
 *    기존 AR, OCR, 길찾기 흐름은 유지하고 화면 텍스트 표시 부분만 최소 수정함
 *
 * 수정 범위:
 * - WalkAssistRootOverlay(): 언어 상태(appLanguage) 추가, 언어 전환 버튼 추가
 * - GuideStatusOverlay()/CameraUiControls()/MeasurementOverlay(): language 파라미터 전달 및 표시 텍스트 변환
 * - GuideActionChip(): TalkBack 설명과 버튼 역할 semantics 추가
 * - WalkAssistLanguage / WalkAssistUiText / translate 함수들 추가
 *
 * 주의:
 * - FeedbackQueue, FeedbackPolicy, FeedbackManager 같은 피드백 처리 로직은 건드리지 않음
 * - 버튼/화면 텍스트 UI에 필요한 최소 변경만 반영함
 */

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
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import androidx.compose.ui.graphics.Path
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
import com.example.walkassist.feedback.core.FeedbackPolicy      // 여준호 코드 추가
import com.example.walkassist.feedback.runtime.FeedbackQueue    // 여준호 코드 추가
import java.util.Locale // [작업자 C - 여준호] 영어 모드 텍스트 변환에서 lowercase(Locale.KOREA)를 사용하기 위한 최소 import 추가
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {      // 여기부터
    private val fragmentContainerId = 1001
    private val feedbackViewModel by viewModels<FeedbackViewModel>()
    private val arFeedbackMapper = ArFeedbackMapper()

    private val feedbackPolicy = FeedbackPolicy()
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var feedbackQueue: FeedbackQueue

    private var arFragment: WalkAssistArFragment? = null       // 여기까지 코드를 여준호가 수정

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        feedbackManager = FeedbackManager(this)         // 여기부터
        feedbackQueue = FeedbackQueue(
            manager = feedbackManager,
            scope = lifecycleScope
        )

        bindArStateToFeedback()     // 여기까지 여준호가 코드를 수정

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
        fragment.onOneShotOcrResult = { message ->          // 여기부터
            feedbackQueue.enqueue(
                feedbackPolicy.ocrRequest(message)
            )
        }       // 여기까지 여준호가 코드를 수정
    }

    private fun requestOneShotOcr() {
        val fragment = arFragment
            ?: (supportFragmentManager.findFragmentById(fragmentContainerId) as? WalkAssistArFragment)
                ?.also(::configureArFragment)

        if (fragment == null) {
            feedbackQueue.enqueue(          // 여기서부터
                feedbackPolicy.ocrRequest("문자 인식 준비 중입니다.")     // 여기까지 여준호가 코드 수정
            )
            return
        }

        fragment.requestOneShotOcr()
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
                        if (feedbackState.shouldAnnounce) {     // 여기서부터
                            val request = if (feedbackState.distanceMeters != null) {
                                feedbackPolicy.obstacleRequest(
                                    distanceMeters = feedbackState.distanceMeters,
                                    direction = feedbackState.direction,
                                    crosswalkDetected = feedbackState.crosswalkDetected
                                )
                            } else {
                                feedbackPolicy.sensorStatusRequest(feedbackState.sensorStatus)
                            }

                            feedbackQueue.enqueue(request)
                            feedbackViewModel.consumeAnnouncement()
                        }       // 여기가지 여준호가 코드 수정
                    }
                }
            }
        }
    }

    override fun onDestroy() {          // 여기서 부터
        if (::feedbackQueue.isInitialized) {
            feedbackQueue.clear()
        }

        if (::feedbackManager.isInitialized) {
            feedbackManager.release()
        }

        super.onDestroy()
    }               // 여기까지 여준호가 코드 수정

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
    onReplayTestClick: () -> Unit,
) {
    var cameraUiVisible by remember { mutableStateOf(false) }

    // ============================================================
    // 작업자 C(여준호) 수정 1: 화면 언어 상태 추가
    // ============================================================
    // appLanguage는 현재 화면 UI 텍스트가 한국어인지 영어인지 기억하는 Compose 상태입니다.
    // 기본값은 한국어(KO)로 두어 기존 앱 화면과 발표 자료 기준을 유지합니다.
    //
    // MainActivity.kt는 공통 파일이므로 SharedPreference, 설정 DB, 전역 Application 상태까지는 건드리지 않고
    // 이 화면 오버레이 내부에서만 언어를 전환하도록 최소 구현했습니다.
    //
    // uiText는 현재 언어에 맞는 버튼 텍스트와 TalkBack 설명을 한 번에 꺼내 쓰기 위한 객체입니다.
    var appLanguage by remember { mutableStateOf(WalkAssistLanguage.KO) }
    val uiText = walkAssistUiText(appLanguage)

    val context = LocalContext.current
    val openMapNavigation = {
        context.startActivity(Intent(context, MapNavigationActivity::class.java))
    }
    val openGuideSettings = {
        context.startActivity(Intent(context, GuideSettingsActivity::class.java))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraUiVisible) {
            MeasurementOverlay(
                state = state,
                feedbackState = feedbackState,
                language = appLanguage,
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
                onSettingsClick = {
                    context.startActivity(Intent(context, GuideSettingsActivity::class.java))
                },
                onNavigationClick = openMapNavigation,
                onReplayTestClick = onReplayTestClick,
            )
        }

        // ============================================================
        // 작업자 C(여준호) 수정 2: 피드백 카드에도 현재 언어 전달
        // ============================================================
        // 기본 안내 화면(cameraUiVisible == false)에서도 하단 피드백 카드가 보이도록 유지합니다.
        // 단, 카메라 화면에서는 MeasurementOverlay 안에서 이미 FeedbackOverlayCard를 표시하므로
        // 여기서는 기본 안내 화면일 때만 표시하여 카드가 중복으로 뜨지 않게 합니다.
        //
        // languageCode = appLanguage.code 를 넘겨서
        // 피드백 카드의 "피드백 안전", "TTS/진동 대기" 같은 문구도 언어 전환에 맞게 바뀌도록 했습니다.
        if (!cameraUiVisible) {
            FeedbackOverlayCard(
                state = feedbackState,
                languageCode = appLanguage.code,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 14.dp, bottom = 18.dp),
            )
        }

        // 작업자 C(여준호) 수정: 길찾기 버튼과 설정 버튼의 위치를 바꾸기 위해 상단 오른쪽 첫 번째 버튼을 설정 버튼으로 배치했습니다.
        Column(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 14.dp, end = 14.dp),
            horizontalAlignment = Alignment.End,
        ) {
            GuideActionChip(
                text = uiText.settings,
                onClick = openGuideSettings,
                contentDescription = uiText.settingsA11y,
            )
            Spacer(modifier = Modifier.height(10.dp))
            GuideActionChip(
                text = uiText.languageToggle,
                onClick = { appLanguage = appLanguage.toggle() },
                contentDescription = uiText.languageToggleA11y,
            )
        }

        // 작업자 C(여준호) 수정: 문자 인식 버튼을 화면 왼쪽으로 옮기기 위해 하단 시작 위치 기준으로 배치했습니다.
        GuideActionChip(
            text = uiText.ocr,
            onClick = onOcrClick,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 14.dp, bottom = 188.dp),
            contentDescription = uiText.ocrA11y,
        )
    }
}

// ============================================================
// 작업자 C(여준호) 수정 - 중앙 상태 안내 위치 조절값
// ============================================================
// 이미지에 보이는 점 3개(...), Waiting/대기 큰 글씨,
// 안내 문구, 거리/신뢰도 표시를 "한 묶음"으로 위/아래 이동시키는 값입니다.
//
// 사용 방법:
// - 더 위로 올리고 싶으면 음수 값을 더 크게 합니다. 예: (-72).dp
// - 아래로 내리고 싶으면 음수 값을 줄이거나 양수로 바꿉니다. 예: (-24).dp, 0.dp, 24.dp
//
// 주의:
// - 이 값은 상태 안내 텍스트 묶음만 이동합니다.
// - Route, 한국어 버튼, Camera, Settings, Read text, Replay test 버튼 위치는 건드리지 않습니다.
// - MainActivity.kt는 공통 파일이므로 버튼/기능 로직은 유지하고 화면 위치만 최소 수정했습니다.
private val GUIDE_STATUS_CONTENT_OFFSET_Y = (-56).dp

@Composable
private fun GuideStatusOverlay(
    arState: ArMeasurementState,
    feedbackState: FeedbackUiState,
    language: WalkAssistLanguage,
    onCameraClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onNavigationClick: () -> Unit,
    onReplayTestClick: () -> Unit,
) {
    val palette = guidePalette(
        alertLevel = feedbackState.alertLevel,
        sensorStatus = feedbackState.sensorStatus,
        language = language,
    )
    // 작업자 C(여준호) 수정 5:
    // GuideStatusOverlay 안의 버튼/상태 문구도 현재 언어에 맞게 표시하기 위해
    // language 파라미터를 받아 uiText를 생성합니다.
    val uiText = walkAssistUiText(language)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(palette.background)
            .padding(24.dp),
    ) {
        Column(
            // ============================================================
            // 작업자 C(여준호) 수정 - 상태 안내 영역 위치 조정
            // ============================================================
            // 아래 Column 안에는 이미지에 보이는 핵심 상태 안내가 모두 들어 있습니다.
            // 1) 점 3개(...) / OK / ! / !!
            // 2) Waiting / Safe / Caution / Danger 또는 대기 / 안전 / 주의 / 위험
            // 3) 안내 문구
            // 4) 거리와 신뢰도 표시
            //
            // 기존에는 Alignment.Center만 사용해서 화면 정중앙에 고정되었습니다.
            // 수정 후에는 offset(y = GUIDE_STATUS_CONTENT_OFFSET_Y)를 추가해서
            // 이 상태 안내 묶음을 조금 더 위쪽으로 올렸습니다.
            //
            // 위치를 자유롭게 바꾸고 싶으면 파일 위쪽의
            // GUIDE_STATUS_CONTENT_OFFSET_Y 값만 수정하면 됩니다.
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
                text = translateFeedbackMessage(feedbackState.message, language),
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
                text = guideSensorStatus(feedbackState.sensorStatus, language),
                color = palette.foreground.copy(alpha = 0.82f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(10.dp))
            // 작업자 C(여준호) 수정: 길찾기 버튼과 설정 버튼의 위치를 바꾸기 위해 기존 설정 버튼 자리에 길찾기 버튼을 배치했습니다.
            GuideActionChip(
                text = uiText.navigation,
                onClick = onNavigationClick,
                contentDescription = uiText.navigationA11y,
            )
            Spacer(modifier = Modifier.height(10.dp))
            GuideActionChip(
                text = uiText.cameraView,
                onClick = onCameraClick,
                contentDescription = uiText.cameraViewA11y,
            )
            Spacer(modifier = Modifier.height(10.dp))
            GuideActionChip(
                text = uiText.replayTest,
                onClick = onReplayTestClick,
                contentDescription = uiText.replayTestA11y,
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
        // 작업자 C(여준호): 카메라 모드의 대기 화면 버튼 아래 설정 버튼은 상단 설정 버튼과 중복되어 제거했습니다.
    }
}

// ============================================================
// 작업자 C(여준호) 수정 6: 공통 버튼 컴포저블 접근성 개선
// ============================================================
// 기존 Text 기반 클릭 UI 구조를 유지하되, 아래 두 가지를 추가했습니다.
// 1) contentDescription 파라미터: TalkBack이 버튼 기능을 자세히 읽을 수 있게 함
// 2) Role.Button semantics: 단순 텍스트가 아니라 버튼으로 인식되게 함
//
// 기존 호출부 보호를 위해 contentDescription 기본값을 text로 두었습니다.
// 따라서 기존 GuideActionChip 호출부가 일부 남아 있어도 빌드 영향이 적습니다.
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
        modifier = modifier
            // 작업자 C 수정:
            // Text를 클릭 가능한 버튼처럼 쓰고 있으므로 TalkBack에서 버튼 역할과 설명이 읽히도록 합니다.
            .semantics {
                role = Role.Button
                this.contentDescription = contentDescription
            }
            // 작업자 C 수정:
            // 시각장애인 사용자의 터치 실수를 줄이기 위해 최소 터치 영역을 확보합니다.
            .defaultMinSize(minWidth = 72.dp, minHeight = 48.dp)
            .background(Color(0x99121820), RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
}

// ============================================================
// 작업자 C(여준호) 수정 7: 화면 언어 타입 정의
// ============================================================
// KO: 한국어 화면
// EN: 영어 화면
// code 값은 FeedbackOverlayCard에 넘겨 피드백 카드 텍스트도 같은 언어로 맞추기 위해 사용합니다.
private enum class WalkAssistLanguage(val code: String) {
    KO("ko"),
    EN("en"),
}

private fun WalkAssistLanguage.toggle(): WalkAssistLanguage {
    return if (this == WalkAssistLanguage.KO) WalkAssistLanguage.EN else WalkAssistLanguage.KO
}

// ============================================================
// 작업자 C(여준호) 수정 8: 버튼 텍스트와 TalkBack 설명을 한 곳에서 관리
// ============================================================
// 화면에 흩어져 있던 "길찾기", "문자 인식", "설정" 같은 버튼 문구를
// 언어별로 한 곳에서 관리하기 위한 데이터 클래스입니다.
//
// A11y는 Accessibility의 줄임말로, TalkBack이 읽어줄 설명 문구입니다.
// 예: 화면 표시 텍스트는 "문자 인식", TalkBack 설명은 "문자 인식 시작 버튼"
private data class WalkAssistUiText(
    val navigation: String,
    val navigationA11y: String,
    val ocr: String,
    val ocrA11y: String,
    val cameraView: String,
    val cameraViewA11y: String,
    val settings: String,
    val settingsA11y: String,
    val replayTest: String,
    val replayTestA11y: String,
    val guideScreen: String,
    val guideScreenA11y: String,
    val languageToggle: String,
    val languageToggleA11y: String,
)

// ============================================================
// 작업자 C(여준호) 수정 9: 한국어/영어 UI 문구 매핑
// ============================================================
// 버튼 화면 텍스트와 TalkBack 설명을 언어별로 반환합니다.
// 새로운 버튼 문구를 추가할 때는 여기의 WalkAssistUiText 필드와 KO/EN 값을 같이 추가하면 됩니다.
private fun walkAssistUiText(language: WalkAssistLanguage): WalkAssistUiText {
    return when (language) {
        WalkAssistLanguage.KO -> WalkAssistUiText(
            navigation = "길찾기",
            navigationA11y = "길찾기 화면으로 이동 버튼",
            ocr = "문자 인식",
            ocrA11y = "문자 인식 시작 버튼",
            cameraView = "카메라 보기",
            cameraViewA11y = "카메라 화면 보기 버튼",
            settings = "설정",
            settingsA11y = "설정 화면으로 이동 버튼",
            replayTest = "리플레이 테스트",
            replayTestA11y = "개발자용 영상 리플레이 테스트 버튼",
            guideScreen = "대기 화면",
            guideScreenA11y = "대기 안내 화면으로 이동 버튼",
            languageToggle = "English",
            languageToggleA11y = "Change language to English",
        )

        WalkAssistLanguage.EN -> WalkAssistUiText(
            navigation = "Route",
            navigationA11y = "Open route navigation screen",
            ocr = "OCR",
            ocrA11y = "Start text recognition",
            cameraView = "Camera",
            cameraViewA11y = "Open camera view",
            settings = "Settings",
            settingsA11y = "Open settings screen",
            replayTest = "Replay test",
            replayTestA11y = "Developer video replay test button",
            guideScreen = "Guide screen",
            guideScreenA11y = "Open guide status screen",
            languageToggle = "Korean",
            languageToggleA11y = "Change language to Korean",
        )
    }
}

private data class GuidePalette(
    val background: Color,
    val foreground: Color,
    val title: String,
    val icon: String,
)

// ============================================================
// 작업자 C(여준호) 수정 10: 대기/안전/주의/위험 상태 제목 언어 전환
// ============================================================
// 기존 위험도 색상과 상태 판단 로직은 그대로 유지하고,
// 화면에 보이는 title만 language에 따라 한국어/영어로 바꿉니다.
private fun guidePalette(
    alertLevel: FeedbackAlertLevel,
    sensorStatus: FeedbackSensorStatus,
    language: WalkAssistLanguage,
): GuidePalette {
    if (sensorStatus == FeedbackSensorStatus.WAITING || sensorStatus == FeedbackSensorStatus.DISCONNECTED) {
        return GuidePalette(
            background = Color(0xFF4A5568),
            foreground = Color.White,
            title = if (language == WalkAssistLanguage.KO) "대기" else "Waiting",
            icon = "..."
        )
    }
    return when (alertLevel) {
        FeedbackAlertLevel.SAFE -> GuidePalette(
            background = Color(0xFF15803D),
            foreground = Color.White,
            title = if (language == WalkAssistLanguage.KO) "안전" else "Safe",
            icon = "OK",
        )
        FeedbackAlertLevel.CAUTION -> GuidePalette(
            background = Color(0xFFF59E0B),
            foreground = Color(0xFF17120A),
            title = if (language == WalkAssistLanguage.KO) "주의" else "Caution",
            icon = "!",
        )
        FeedbackAlertLevel.DANGER -> GuidePalette(
            background = Color(0xFFDC2626),
            foreground = Color.White,
            title = if (language == WalkAssistLanguage.KO) "위험" else "Danger",
            icon = "!!",
        )
    }
}

// 작업자 C(여준호) 수정 11:
// 거리/공간 인식 안내 문구를 현재 언어에 맞게 표시합니다.
// 피드백 정책이나 거리 계산 로직은 변경하지 않고, 화면 표시 문자열만 바꿉니다.
private fun guideDistanceText(
    feedbackState: FeedbackUiState,
    arState: ArMeasurementState,
    language: WalkAssistLanguage,
): String {
    val distance = feedbackState.distanceMeters ?: arState.collisionDistanceMeters
    val confidence = (feedbackState.confidence * 100f).toInt().coerceIn(0, 100)
    return if (distance == null) {
        if (language == WalkAssistLanguage.KO) {
            "공간 정보를 수집하는 중입니다."
        } else {
            "Collecting spatial information."
        }
    } else {
        if (language == WalkAssistLanguage.KO) {
            "전방 ${formatMetersShort(distance)} / 신뢰도 $confidence%"
        } else {
            "Ahead ${formatMetersShort(distance)} / confidence $confidence%"
        }
    }
}

// 작업자 C(여준호) 수정 12:
// 센서 상태 표시 문구를 한국어/영어로 분리합니다.
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

// 작업자 C(여준호) 수정 13:
// FeedbackViewModel/FeedbackPolicy에서 넘어오는 기존 한국어 메시지를
// 영어 모드일 때 화면 표시용으로만 간단히 번역합니다.
// 실제 TTS 정책/큐 정책은 건드리지 않습니다.
private fun translateFeedbackMessage(
    message: String,
    language: WalkAssistLanguage,
): String {
    if (language == WalkAssistLanguage.KO) return message

    // ============================================================
    // 작업자 C(여준호) 수정 16: 영어 모드 잔여 한국어 안내 문구 보정
    // ============================================================
    // 작성/수정: 작업자 C 여준호
    // FeedbackPolicy/FeedbackViewModel에서 넘어오는 message는 기존 구조상 한국어일 수 있습니다.
    // 영어 모드에서는 화면에 보이는 텍스트만 영어로 바꾸기 위해 여기서 표시용 번역을 수행합니다.
    // 피드백 큐, 우선순위, TTS/진동 실행 로직은 변경하지 않습니다.
    var translated = message

    translated = translated
        .replace("공간 인식 대기 중입니다.", "Waiting for spatial recognition.")
        .replace("공간 정보를 수집하는 중입니다.", "Collecting spatial information.")
        .replace("공간 인식이 연결되었습니다.", "Spatial recognition connected.")
        .replace("센서 데이터가 일시적으로 끊겼습니다.", "Sensor data was temporarily disconnected.")
        .replace("공간 인식에 문제가 발생했습니다.", "A spatial recognition problem occurred.")
        .replace("전방 장애물 주의", "Obstacle ahead. Be careful.")
        .replace("전방 장애물 위험", "Dangerous obstacle ahead.")
        .replace("전방 매우 가까운 장애물", "Very close obstacle ahead.")
        .replace("인식된 글자가 없습니다.", "No text was recognized.")
        .replace("인식된 글자가 깁니다. 앞부분만 안내합니다.", "The recognized text is long. Reading only the first part.")
        .replace("안전합니다. 전방 공간이 확보되어 있습니다.", "Safe. The path ahead is clear.")
        .replace("전방 상황을 확인하세요.", "Check the path ahead.")
        .replace("왼쪽으로 이동하세요.", "Move left.")
        .replace("오른쪽으로 이동하세요.", "Move right.")
        .replace("중앙을 유지하세요.", "Keep center.")
        .replace("잠시 멈추고 주변을 확인하세요.", "Stop briefly and check your surroundings.")
        .replace("횡단보도가 감지되었습니다.", "Crosswalk detected.")

    translated = Regex("즉시 멈추세요\\. 전방 ([^ ]+) 안에 장애물이 있습니다\\.")
        .replace(translated) { match ->
            "Stop immediately. Obstacle detected ${match.groupValues[1]} ahead."
        }

    translated = Regex("위험합니다\\. 전방 ([^ ]+) 안에 장애물이 있습니다\\.")
        .replace(translated) { match ->
            "Danger. Obstacle detected ${match.groupValues[1]} ahead."
        }

    translated = Regex("주의하세요\\. 전방 ([^ ]+) 안에 장애물이 있습니다\\.")
        .replace(translated) { match ->
            "Caution. Obstacle detected ${match.groupValues[1]} ahead."
        }

    translated = Regex("전방 ([^ ]+) 안에 장애물이 감지되었습니다\\.")
        .replace(translated) { match ->
            "Obstacle detected ${match.groupValues[1]} ahead."
        }

    return translated
}

// 작업자 C(여준호) 수정 14:
// AR 측에서 넘어오는 guidanceLabel/statusLabel이 영어 또는 한국어로 섞여 있을 수 있어
// 화면 표시 단계에서 최소한의 한국어/영어 변환만 수행합니다.
// 근본적인 문구 생성 위치는 AR 담당 파일일 수 있으므로 여기서는 UI 표시용 보정만 합니다.
private fun translateArLabel(
    text: String,
    language: WalkAssistLanguage,
): String {
    if (language == WalkAssistLanguage.KO) {
        return when (text) {
            "Scan the floor and wall slowly." -> "바닥과 벽을 천천히 비춰 주세요."
            "Move the phone left and right over a textured floor." -> "바닥을 향해 휴대폰을 좌우로 천천히 움직여 주세요."
            else -> text
        }
    }

    return when (text) {
        "바닥과 벽을 천천히 비춰 주세요." -> "Scan the floor and wall slowly."
        "바닥을 향해 휴대폰을 좌우로 천천히 움직여 주세요." -> "Move the phone left and right over a textured floor."
        else -> text
    }
}

// ============================================================
// 작업자 C(여준호) 수정 15: 카메라 오버레이 문구 언어 전환
// ============================================================
// 카메라 화면에서도 위험도, 공간 인식 신뢰도, 피드백 카드 문구가
// 현재 선택된 언어와 맞게 보이도록 language 파라미터를 전달합니다.
@Composable
private fun MeasurementOverlay(
    state: ArMeasurementState,
    feedbackState: FeedbackUiState,
    language: WalkAssistLanguage,
) {
    var debugVisible by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize()) {
        ObjectDetectionOverlay(
            detections = state.objectDetections,
            language = language,
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
                text = translateArLabel(state.guidanceLabel, language),
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
                text = if (language == WalkAssistLanguage.KO) "공간 인식 신뢰도 ${state.sensingConfidenceScore}점" else "Spatial confidence ${state.sensingConfidenceScore}",
                color = confidenceColor,
                fontSize = 13.sp,
            )
            state.timeToCollisionSeconds?.takeIf { state.riskLabel != "stable" }?.let { ttc ->
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (language == WalkAssistLanguage.KO) "충돌 예상 ${formatSeconds(ttc)}" else "Collision in ${formatSeconds(ttc)}",
                    color = riskColor,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            if (state.crosswalkDetected) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (language == WalkAssistLanguage.KO) "횡단보도 감지 ${formatMapScore(state.crosswalkScore)}점" else "Crosswalk detected ${formatMapScore(state.crosswalkScore)}",
                    color = Color(0xFFB6E7FF),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = translateArLabel(state.statusLabel, language),
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
                .padding(top = 134.dp, end = 14.dp), // 작업자 C(여준호): DBG 버튼이 English 버튼과 겹치지 않도록 English 버튼 아래 위치로 내렸습니다.
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

private data class VoxelOverlayCluster(
    val hullPoints: List<Offset>,
    val averageOccupancy: Float,
    val averageConfidence: Float,
    val pointCount: Int,
)

private fun cross(o: Offset, a: Offset, b: Offset): Float {
    return ((a.x - o.x) * (b.y - o.y)) - ((a.y - o.y) * (b.x - o.x))
}

private fun convexHull(points: List<Offset>): List<Offset> {
    if (points.size <= 3) return points.distinct()

    val sorted = points
        .distinctBy { "${it.x},${it.y}" }
        .sortedWith(compareBy<Offset> { it.x }.thenBy { it.y })

    if (sorted.size <= 3) return sorted

    val lower = mutableListOf<Offset>()
    sorted.forEach { point ->
        while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), point) <= 0f) {
            lower.removeAt(lower.lastIndex)
        }
        lower += point
    }

    val upper = mutableListOf<Offset>()
    for (index in sorted.indices.reversed()) {
        val point = sorted[index]
        while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), point) <= 0f) {
            upper.removeAt(upper.lastIndex)
        }
        upper += point
    }

    lower.removeAt(lower.lastIndex)
    upper.removeAt(upper.lastIndex)
    return lower + upper
}

private fun clusterVoxelOverlayPoints(
    points: List<VoxelOverlayPointUi>,
): List<VoxelOverlayCluster> {
    if (points.isEmpty()) return emptyList()

    val remaining = points.toMutableList()
    val clusters = mutableListOf<VoxelOverlayCluster>()
    val xThreshold = 0.07f
    val yThreshold = 0.09f

    while (remaining.isNotEmpty()) {
        val seed = remaining.removeAt(0)
        val clusterPoints = mutableListOf(seed)
        var index = 0
        while (index < clusterPoints.size) {
            val current = clusterPoints[index]
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val candidate = iterator.next()
                if (
                    abs(candidate.xRatio - current.xRatio) <= xThreshold &&
                    abs(candidate.yRatio - current.yRatio) <= yThreshold
                ) {
                    clusterPoints += candidate
                    iterator.remove()
                }
            }
            index += 1
        }

        if (clusterPoints.size < 6) continue

        var occupancySum = 0f
        var confidenceSum = 0f

        clusterPoints.forEach { point ->
            occupancySum += point.occupancyScore
            confidenceSum += point.confidenceScore
        }

        val centerX = clusterPoints.map { it.xRatio }.average().toFloat()
        val centerY = clusterPoints.map { it.yRatio }.average().toFloat()
        val expandedPoints = clusterPoints.map { point ->
            val dx = point.xRatio - centerX
            val dy = point.yRatio - centerY
            Offset(
                x = (point.xRatio + (dx * 0.18f)).coerceIn(0f, 1f),
                y = (point.yRatio + (dy * 0.18f)).coerceIn(0f, 1f),
            )
        }
        val hull = convexHull(expandedPoints)
        if (hull.size < 3) continue

        clusters += VoxelOverlayCluster(
            hullPoints = hull,
            averageOccupancy = occupancySum / clusterPoints.size,
            averageConfidence = confidenceSum / clusterPoints.size,
            pointCount = clusterPoints.size,
        )
    }

    return clusters.sortedByDescending { it.pointCount }
}

@Composable
private fun VoxelClusterOverlay(
    points: List<VoxelOverlayPointUi>,
    modifier: Modifier = Modifier,
) {
    val clusters = remember(points) { clusterVoxelOverlayPoints(points).take(12) }

    Canvas(modifier = modifier) {
        clusters.forEach { cluster ->
            val fillColor = when {
                cluster.averageOccupancy >= 0.55f -> Color(0x4DFF8E8E)
                cluster.averageOccupancy >= 0.28f -> Color(0x4DFFC870)
                else -> Color(0x407A8794)
            }.copy(alpha = 0.18f + (cluster.averageConfidence * 0.28f))
            val borderColor = when {
                cluster.averageOccupancy >= 0.55f -> Color(0xCCFF8E8E)
                cluster.averageOccupancy >= 0.28f -> Color(0xCCFFC870)
                else -> Color(0xAA9AA7B4)
            }.copy(alpha = 0.45f + (cluster.averageConfidence * 0.35f))

            val path = Path().apply {
                cluster.hullPoints.forEachIndexed { index, point ->
                    val x = point.x * size.width
                    val y = point.y * size.height
                    if (index == 0) {
                        moveTo(x, y)
                    } else {
                        lineTo(x, y)
                    }
                }
                close()
            }

            drawPath(
                path = path,
                color = fillColor,
            )
            drawPath(
                path = path,
                color = borderColor,
                style = Stroke(width = 3f),
            )
        }
    }
}

@Composable
private fun ObjectDetectionOverlay(
    detections: List<ObjectOverlayDetection>,
    language: WalkAssistLanguage = WalkAssistLanguage.KO,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier = modifier) {
        val boxStrokeColor = Color(0xFFFFB648)
        val labelBackground = Color(0xCC121820)

        detections.forEach { detection ->
            val containerMaxWidth = this.maxWidth
            val containerMaxHeight = this.maxHeight

            val boxLeft = containerMaxWidth * detection.leftRatio.coerceIn(0f, 1f)
            val boxTop = containerMaxHeight * detection.topRatio.coerceIn(0f, 1f)
            val boxWidth = containerMaxWidth * detection.widthRatio.coerceIn(0.05f, 1f)
            val boxHeight = containerMaxHeight * detection.heightRatio.coerceIn(0.05f, 1f)

            Box(
                modifier = Modifier
                    .offset(x = boxLeft, y = boxTop)
                    .width(boxWidth)
                    .height(boxHeight)
                    .border(2.dp, boxStrokeColor, RoundedCornerShape(8.dp)),
            ) {
                Text(
                    text = buildString {
                        append(presentableLabel(detection.label, language))
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
                            append(presentableMotion(it, language))
                        }
                        detection.avoidanceDirectionLabel?.let {
                            append(" ")
                            append(presentableAvoidance(it, language))
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

private fun presentableRisk(
    riskLabel: String,
    language: WalkAssistLanguage,
): Pair<String, Color> {
    return when (riskLabel) {
        "critical" -> (if (language == WalkAssistLanguage.KO) "위험" else "Danger") to Color(0xFFFF8E8E)
        "high" -> (if (language == WalkAssistLanguage.KO) "경고" else "Warning") to Color(0xFFFFC870)
        "watch" -> (if (language == WalkAssistLanguage.KO) "주의" else "Caution") to Color(0xFFFFDB7A)
        "stable" -> (if (language == WalkAssistLanguage.KO) "안전" else "Safe") to Color(0xFF96E2B5)
        else -> (if (language == WalkAssistLanguage.KO) "주의" else "Caution") to Color(0xFFD8E3EE)
    }
}

// ============================================================
// 작업자 C(여준호) 수정 17: 카메라 객체 라벨 영어 모드 대응
// ============================================================
// 작성/수정: 작업자 C 여준호
// 영어 모드에서는 객체 감지 박스에 사람/자동차 같은 한국어 라벨이 남지 않도록
// language에 따라 표시 문자열을 분기합니다.
private fun presentableLabel(
    label: String,
    language: WalkAssistLanguage = WalkAssistLanguage.KO,
): String {
    val normalized = label.lowercase(Locale.KOREA)
    if (language == WalkAssistLanguage.EN) {
        return when (normalized) {
            "person" -> "person"
            "bicycle" -> "bicycle"
            "car" -> "car"
            "motorcycle" -> "motorcycle"
            "bus" -> "bus"
            "truck" -> "truck"
            "chair" -> "chair"
            "bench" -> "bench"
            "dog" -> "dog"
            "cat" -> "cat"
            "stop sign" -> "stop sign"
            else -> label
        }
    }

    return when (normalized) {
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
private fun VoxelMiniMap(state: ArMeasurementState) {
    Box(
        modifier = Modifier
            .width(150.dp)
            .height(150.dp)
            .background(Color(0x22212A34), RoundedCornerShape(8.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val halfRange = state.voxelRangeMeters.coerceAtLeast(0.1f)
            drawRect(Color(0x163A4654))

            state.voxelColumns.forEach { column ->
                val xRatio = ((column.relativeX + halfRange) / (halfRange * 2f)).coerceIn(0f, 1f)
                val zRatio = (1f - ((column.relativeZ + halfRange) / (halfRange * 2f))).coerceIn(0f, 1f)
                val voxelSizePx = (size.minDimension * (state.voxelSizeMeters / (halfRange * 2f)))
                    .coerceAtLeast(3f)
                val heightInfluence = ((column.heightMeters + 0.4f) / 1.8f).coerceIn(0f, 1f)
                val color = when {
                    column.occupancyScore >= 0.55f -> Color(0xFFFF8E8E)
                    column.occupancyScore >= 0.28f -> Color(0xFFFFC870)
                    else -> Color(0xFF8C97A3)
                }.copy(alpha = 0.28f + (column.confidenceScore * 0.42f) + (heightInfluence * 0.2f))
                drawRect(
                    color = color,
                    topLeft = Offset(
                        (size.width * xRatio) - (voxelSizePx * 0.5f),
                        (size.height * zRatio) - (voxelSizePx * 0.5f),
                    ),
                    size = Size(
                        voxelSizePx + (heightInfluence * 3f),
                        voxelSizePx + (heightInfluence * 3f),
                    ),
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
private fun VoxelSideProfile(state: ArMeasurementState) {
    Box(
        modifier = Modifier
            .width(150.dp)
            .height(110.dp)
            .background(Color(0x1F1F2933), RoundedCornerShape(8.dp)),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val maxForward = state.voxelRangeMeters.coerceAtLeast(0.1f)
            val minHeight = -1.5f
            val maxHeight = 2.5f
            drawRect(Color(0x143A4654))

            state.voxelPoints.forEach { point ->
                if (point.relativeZ !in 0f..maxForward) return@forEach
                val zRatio = (point.relativeZ / maxForward).coerceIn(0f, 1f)
                val yRatio = (1f - ((point.relativeY - minHeight) / (maxHeight - minHeight))).coerceIn(0f, 1f)
                val voxelPx = (size.minDimension * (state.voxelSizeMeters / (maxForward + maxHeight))).coerceAtLeast(3f)
                val color = when {
                    point.occupancyScore >= 0.55f -> Color(0xFFFF8E8E)
                    point.occupancyScore >= 0.28f -> Color(0xFFFFC870)
                    else -> Color(0xFF8C97A3)
                }.copy(alpha = 0.28f + (point.confidenceScore * 0.5f))
                drawRect(
                    color = color,
                    topLeft = Offset(
                        (size.width * zRatio) - (voxelPx * 0.5f),
                        (size.height * yRatio) - (voxelPx * 0.5f),
                    ),
                    size = Size(voxelPx, voxelPx),
                )
            }

            drawLine(
                color = Color(0x55FFFFFF),
                start = Offset(0f, size.height * 0.72f),
                end = Offset(size.width, size.height * 0.72f),
                strokeWidth = 2f,
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

// 작업자 C(여준호) 수정 18:
// 객체 움직임 라벨도 영어 모드에서는 영어로 표시합니다.
private fun presentableMotion(
    label: String,
    language: WalkAssistLanguage = WalkAssistLanguage.KO,
): String {
    return when (label) {
        "approaching_right" -> if (language == WalkAssistLanguage.KO) "접근/우" else "approaching right"
        "approaching_left" -> if (language == WalkAssistLanguage.KO) "접근/좌" else "approaching left"
        "approaching" -> if (language == WalkAssistLanguage.KO) "접근" else "approaching"
        "moving_right" -> if (language == WalkAssistLanguage.KO) "우측 이동" else "moving right"
        "moving_left" -> if (language == WalkAssistLanguage.KO) "좌측 이동" else "moving left"
        "receding" -> if (language == WalkAssistLanguage.KO) "멀어짐" else "moving away"
        else -> ""
    }
}

// 작업자 C(여준호) 수정 19:
// 객체 회피 방향 라벨도 영어 모드에서는 영어로 표시합니다.
private fun presentableAvoidance(
    label: String,
    language: WalkAssistLanguage = WalkAssistLanguage.KO,
): String {
    return when (label) {
        "left" -> if (language == WalkAssistLanguage.KO) "좌측 회피" else "avoid left"
        "center" -> if (language == WalkAssistLanguage.KO) "중앙 유지" else "keep center"
        "right" -> if (language == WalkAssistLanguage.KO) "우측 회피" else "avoid right"
        "stop_or_sidestep" -> if (language == WalkAssistLanguage.KO) "정지/회피" else "stop or sidestep"
        else -> ""
    }
}
