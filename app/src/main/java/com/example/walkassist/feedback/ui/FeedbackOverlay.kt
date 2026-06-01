package com.example.walkassist.feedback.ui

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
    languageCode: String = "ko",
) {
    val isEnglish = languageCode == "en"
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
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 300),
        label = "feedbackCardColor",
    )

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

        Text(
            text = if (isEnglish) {
                "Voice/haptic ${presentableSensorStatus(state.sensorStatus, isEnglish)}"
            } else {
                "TTS/진동 ${presentableSensorStatus(state.sensorStatus, isEnglish)}"
            },
            color = Color.White,
            fontSize = 12.sp,
        )

        state.distanceMeters?.let { distance ->
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = if (isEnglish) {
                    "Distance ${formatDistanceShort(distance)}"
                } else {
                    "기준 거리 ${formatDistanceShort(distance)}"
                },
                color = Color(0xFFD8E3EE),
                fontSize = 12.sp,
            )
        }

        if (state.crosswalkDetected) {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = if (isEnglish) "Crosswalk detected" else "횡단보도 감지됨",
                color = Color(0xFFB6E7FF),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )
        }

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

private fun presentableSensorStatus(
    status: FeedbackSensorStatus,
    isEnglish: Boolean,
): String {
    return when (status) {
        FeedbackSensorStatus.WAITING -> if (isEnglish) "waiting" else "대기"
        FeedbackSensorStatus.CONNECTED -> if (isEnglish) "connected" else "연결"
        FeedbackSensorStatus.DISCONNECTED -> if (isEnglish) "disconnected" else "끊김"
        FeedbackSensorStatus.ERROR -> if (isEnglish) "error" else "오류"
    }
}

private fun presentableDirection(
    direction: String,
    isEnglish: Boolean,
): String {
    return when (direction.lowercase(Locale.KOREA)) {
        "left" -> if (isEnglish) "← move left" else "← 왼쪽 이동"
        "right" -> if (isEnglish) "→ move right" else "→ 오른쪽 이동"
        "center" -> if (isEnglish) "↑ go straight" else "↑ 정면 통행"
        "blocked" -> if (isEnglish) "✋ stop" else "✋ 정지 필요"
        else -> ""
    }
}

private fun formatDistanceShort(
    distanceMeters: Float,
): String {
    return if (distanceMeters < 1f) {
        "${(distanceMeters * 100f).toInt()}cm"
    } else {
        String.format(Locale.KOREA, "%.1fm", distanceMeters)
    }
}
