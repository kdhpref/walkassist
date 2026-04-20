package com.example.walkassist.feedback.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.walkassist.feedback.core.FeedbackAlertLevel
import com.example.walkassist.feedback.core.FeedbackSensorStatus
import com.example.walkassist.feedback.core.FeedbackUiState

@Composable
fun FeedbackOverlayCard(
    state: FeedbackUiState,
    modifier: Modifier = Modifier,
) {
    val (label, color) = when (state.alertLevel) {
        FeedbackAlertLevel.SAFE -> "피드백 안전" to Color(0xFF96E2B5)
        FeedbackAlertLevel.CAUTION -> "피드백 주의" to Color(0xFFFFDB7A)
        FeedbackAlertLevel.DANGER -> "피드백 위험" to Color(0xFFFF8E8E)
    }

    Column(
        modifier = modifier
            .width(172.dp)
            .background(Color(0xB8121820), RoundedCornerShape(16.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = label,
            color = color,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "TTS/진동 ${presentableSensorStatus(state.sensorStatus)}",
            color = Color.White,
            fontSize = 12.sp,
        )
        state.distanceMeters?.let {
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = "기준 거리 ${formatDistanceShort(it)}",
                color = Color(0xFFD8E3EE),
                fontSize = 12.sp,
            )
        }
    }
}

private fun presentableSensorStatus(status: FeedbackSensorStatus): String {
    return when (status) {
        FeedbackSensorStatus.WAITING -> "대기"
        FeedbackSensorStatus.CONNECTED -> "연결"
        FeedbackSensorStatus.DISCONNECTED -> "끊김"
        FeedbackSensorStatus.ERROR -> "오류"
    }
}

private fun formatDistanceShort(distanceMeters: Float): String {
    return if (distanceMeters < 1f) {
        "${(distanceMeters * 100f).toInt()}cm"
    } else {
        String.format("%.1fm", distanceMeters)
    }
}
