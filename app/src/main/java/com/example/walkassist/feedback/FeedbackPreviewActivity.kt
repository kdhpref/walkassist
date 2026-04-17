package com.example.walkassist.feedback

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.walkassist.R
import kotlinx.coroutines.launch

class FeedbackPreviewActivity : AppCompatActivity() {
    private lateinit var viewModel: FeedbackViewModel
    private lateinit var feedbackManager: FeedbackManager
    private lateinit var statusText: TextView
    private lateinit var distanceText: TextView
    private lateinit var sensorText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_feedback_preview)

        viewModel = ViewModelProvider(this)[FeedbackViewModel::class.java]
        feedbackManager = FeedbackManager(this)
        statusText = findViewById(R.id.feedbackStatusText)
        distanceText = findViewById(R.id.feedbackDistanceText)
        sensorText = findViewById(R.id.feedbackSensorText)

        findViewById<Button>(R.id.feedbackSafeButton).setOnClickListener {
            reportTestDistance(4.0f)
        }
        findViewById<Button>(R.id.feedbackCautionButton).setOnClickListener {
            reportTestDistance(2.0f)
        }
        findViewById<Button>(R.id.feedbackDangerButton).setOnClickListener {
            reportTestDistance(1.0f)
        }

        lifecycleScope.launch {
            viewModel.uiState.collect(::render)
        }
        viewModel.startWatchdog()
    }

    private fun reportTestDistance(distanceMeters: Float) {
        repeat(FeedbackThresholds.CONFIDENCE_WINDOW_SIZE) {
            viewModel.reportObstacle(
                FeedbackObstacleData(
                    distanceMeters = distanceMeters,
                    confidence = 1.0f,
                    sensorType = FeedbackSensorType.TEST,
                ),
            )
        }
    }

    private fun render(state: FeedbackUiState) {
        statusText.text = when (state.alertLevel) {
            FeedbackAlertLevel.SAFE -> "안전"
            FeedbackAlertLevel.CAUTION -> "주의"
            FeedbackAlertLevel.DANGER -> "위험"
        }
        distanceText.text = state.distanceMeters?.let {
            "전방 거리 ${String.format("%.1fm", it)} / 신뢰도 ${(state.confidence * 100).toInt()}%"
        } ?: "거리 정보 없음"
        sensorText.text = "센서 상태: ${state.sensorStatus.name}"

        if (state.shouldAnnounce) {
            feedbackManager.provideFeedback(
                message = state.guidanceMessage,
                level = state.alertLevel,
                announcementView = statusText,
            )
            viewModel.consumeAnnouncement()
        }
    }

    override fun onDestroy() {
        feedbackManager.release()
        super.onDestroy()
    }
}
