package com.example.walkassist.feedback.ui

/*
 * ============================================================
 * 작업자 C(여준호) 수정 안내 - FeedbackOverlay 언어 전환 대응
 * ============================================================
 * 수정 목적:
 * - MainActivity의 언어 전환 상태를 하단 피드백 카드에도 반영합니다.
 * - "피드백 안전", "TTS/진동 대기", "센서 대기" 같은 카드 내부 문구를
 *   한국어/영어로 나누어 표시합니다.
 *
 * 최소 수정 원칙:
 * - FeedbackUiState 구조, 위험도 판단, 진동/TTS 실행 로직은 변경하지 않음
 * - 화면에 표시되는 문자열과 TalkBack contentDescription만 언어에 따라 바꿈
 * - languageCode 기본값을 "ko"로 둬서 기존 호출부가 있어도 깨지지 않게 함
 */

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.walkassist.feedback.core.FeedbackAlertLevel
import com.example.walkassist.feedback.core.FeedbackSensorStatus
import com.example.walkassist.feedback.core.FeedbackUiState
import java.util.Locale

@Composable
fun FeedbackOverlayCard(
    state: FeedbackUiState,
    modifier: Modifier = Modifier,
    // ============================================================
    // 작업자 C(여준호) 수정 1: 피드백 카드 언어 코드 추가
    // ============================================================
    // MainActivity에서 현재 선택된 언어를 "ko" 또는 "en" 문자열로 넘겨받습니다.
    // 기본값을 "ko"로 둔 이유:
    // - 다른 파일에서 기존 방식인 FeedbackOverlayCard(state = ...)로 호출해도 빌드가 깨지지 않음
    // - 기존 한국어 UI를 기본 동작으로 유지할 수 있음
    languageCode: String = "ko",
) {
    // 작업자 C(여준호) 수정 2:
    // languageCode가 "en"이면 영어, 그 외에는 한국어로 처리합니다.
    // 이렇게 하면 예상치 못한 값이 들어와도 기본 한국어 화면으로 안전하게 표시됩니다.
    val isEnglish = languageCode == "en"

    // 작업자 C(여준호) 수정 3:
    // 위험도별 카드 제목만 언어에 맞게 바꾸고, 기존 색상 정책은 그대로 유지합니다.
    val labelAndColor: Pair<String, Color> = when (state.alertLevel) {
        FeedbackAlertLevel.SAFE -> {
            (if (isEnglish) "Feedback safe" else "피드백 안전") to Color(0xFF96E2B5)
        }

        FeedbackAlertLevel.CAUTION -> {
            (if (isEnglish) "Feedback caution" else "피드백 주의") to Color(0xFFFFDB7A)
        }

        FeedbackAlertLevel.DANGER -> {
            (if (isEnglish) "Feedback danger" else "피드백 위험") to Color(0xFFFF8E8E)
        }
    }

    val label = labelAndColor.first
    val targetColor = labelAndColor.second

    // ── 추가: 위험도 전환 시 색상이 부드럽게 변하는 애니메이션 ──
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 300),
        label = "feedbackCardColor",
    )

    // ============================================================
    // 작업자 C(여준호) 수정 4: TalkBack 접근성 라벨 언어 전환
    // ============================================================
    // semantics.liveRegion(Assertive)를 쓰면 카드 내용이 바뀔 때 TalkBack이 변경사항을 읽어줍니다.
    // 그래서 화면에 보이는 텍스트뿐 아니라 TalkBack이 읽는 문장도 한국어/영어로 맞췄습니다.
    // 예: 한국어 "거리 1.2m" / 영어 "Distance 1.2m"
    val a11yLabel = buildString {
        append(label)
        append(". ")
        append(presentableSensorStatus(state.sensorStatus, isEnglish))

        state.distanceMeters?.let { distance ->
            append(if (isEnglish) ". Distance " else ". 거리 ")
            append(formatDistanceShort(distance))
        }

        if (state.crosswalkDetected) {
            append(if (isEnglish) ". Crosswalk detected" else ". 횡단보도 감지")
        }

        val directionText = presentableDirection(state.direction, isEnglish)
        if (directionText.isNotBlank()) {
            append(". ")
            append(directionText)
        }
    }

    Column(
        modifier = modifier
            .width(172.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xB8121820))
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .semantics {
                contentDescription = a11yLabel
                liveRegion = LiveRegionMode.Assertive
            },
    ) {
        Text(
            text = label,
            color = animatedColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 작업자 C(여준호) 수정 7:
        // 영어 모드에서 "TTS/진동 connected"처럼 한국어/영어가 섞여 보이지 않도록
        // 표시 라벨을 "Voice/haptic"으로 분리합니다.
        Text(
            text = if (isEnglish) "Voice/haptic ${presentableSensorStatus(state.sensorStatus, isEnglish)}" else "TTS/진동 ${presentableSensorStatus(state.sensorStatus, isEnglish)}",
            color = Color.White,
            fontSize = 12.sp,
        )

        state.distanceMeters?.let { distance ->
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = if (isEnglish) "Distance ${formatDistanceShort(distance)}" else "기준 거리 ${formatDistanceShort(distance)}",
                color = Color(0xFFD8E3EE),
                fontSize = 12.sp,
            )
        }

        // ── 추가: 횡단보도 감지 표시 ──
        if (state.crosswalkDetected) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = if (isEnglish) "Crosswalk detected" else "횡단보도 감지됨",
                color = Color(0xFFB6E7FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        // ── 추가: 방향 안내 표시 ──
        val directionText = presentableDirection(state.direction, isEnglish)
        if (directionText.isNotBlank()) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = directionText,
                color = Color(0xFFD8E3EE),
                fontSize = 12.sp,
            )
        }
    }
}

// 작업자 C(여준호) 수정 5:
// 피드백 카드에 표시되는 센서 상태 문구를 한국어/영어로 분리합니다.
// state.sensorStatus 값 자체는 그대로 사용하고, 사용자가 보는 문자열만 바꿉니다.
private fun presentableSensorStatus(
    status: FeedbackSensorStatus,
    isEnglish: Boolean,
): String {
    return when (status) {
        FeedbackSensorStatus.WAITING -> {
            if (isEnglish) "waiting" else "대기"
        }

        FeedbackSensorStatus.CONNECTED -> {
            if (isEnglish) "connected" else "연결"
        }

        FeedbackSensorStatus.DISCONNECTED -> {
            if (isEnglish) "disconnected" else "끊김"
        }

        FeedbackSensorStatus.ERROR -> {
            if (isEnglish) "error" else "오류"
        }
    }
}

// 작업자 C(여준호) 수정 6:
// 방향 안내 문구를 한국어/영어로 분리합니다.
// direction 값 자체는 기존 데이터 흐름을 그대로 사용하고, 화면 표시 문자열만 바꿉니다.
private fun presentableDirection(
    direction: String,
    isEnglish: Boolean,
): String {
    return when (direction.lowercase(Locale.KOREA)) {
        "left" -> {
            if (isEnglish) "← move left" else "← 왼쪽 이동"
        }

        "right" -> {
            if (isEnglish) "→ move right" else "→ 오른쪽 이동"
        }

        "center" -> {
            if (isEnglish) "↑ go straight" else "↑ 정면 통행"
        }

        "blocked" -> {
            if (isEnglish) "✋ stop" else "✋ 정지 필요"
        }

        else -> {
            ""
        }
    }
}

// 거리 포맷은 언어와 관계없이 숫자 단위 표시가 동일하므로 기존 로직을 유지합니다.
private fun formatDistanceShort(
    distanceMeters: Float,
): String {
    return if (distanceMeters < 1f) {
        "${(distanceMeters * 100f).toInt()}cm"
    } else {
        String.format(Locale.KOREA, "%.1fm", distanceMeters)
    }
}