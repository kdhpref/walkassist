package com.example.walkassist.feedback

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import java.util.ArrayDeque
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FeedbackViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(FeedbackUiState())
    val uiState: StateFlow<FeedbackUiState> = _uiState.asStateFlow()

    private val confidenceWindow = ArrayDeque<Boolean>()
    private var lastValidDataTimeMillis = 0L
    private var lastAnnouncedLevel: FeedbackAlertLevel? = null
    private var lastAnnouncedTimeMillis = 0L
    private var watchdogJob: Job? = null

    fun startWatchdog() {
        if (watchdogJob?.isActive == true) {
            return
        }

        watchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(FeedbackThresholds.SENSOR_WATCHDOG_MS / 2)
                val now = System.currentTimeMillis()
                val state = _uiState.value
                if (
                    state.sensorStatus == FeedbackSensorStatus.CONNECTED &&
                    now - lastValidDataTimeMillis > FeedbackThresholds.SENSOR_WATCHDOG_MS
                ) {
                    _uiState.update {
                        it.copy(
                            sensorStatus = FeedbackSensorStatus.DISCONNECTED,
                            guidanceMessage = "센서 데이터가 일시적으로 끊겼습니다.",
                            shouldAnnounce = true,
                        )
                    }
                }
            }
        }
    }

    fun stopWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    fun reportObstacle(data: FeedbackObstacleData, nowMillis: Long = System.currentTimeMillis()) {
        if (!data.isValid() || !data.isFresh(nowMillis, FeedbackThresholds.DATA_FRESHNESS_MS)) {
            return
        }

        updateConfidenceWindow(data.confidence >= FeedbackThresholds.MIN_CONFIDENCE)
        if (!isConfidenceWindowStable()) {
            return
        }

        lastValidDataTimeMillis = nowMillis
        val nextLevel = classifyWithHysteresis(data.distanceMeters, _uiState.value.alertLevel)
        val message = guidanceMessage(nextLevel, data.distanceMeters)
        val shouldAnnounce = shouldAnnounce(nextLevel, nowMillis)

        _uiState.update {
            it.copy(
                alertLevel = nextLevel,
                sensorStatus = FeedbackSensorStatus.CONNECTED,
                distanceMeters = data.distanceMeters,
                confidence = data.confidence,
                guidanceMessage = message,
                shouldAnnounce = shouldAnnounce,
            )
        }
    }

    fun reportSensorStatus(status: FeedbackSensorStatus) {
        _uiState.update {
            it.copy(
                sensorStatus = status,
                guidanceMessage = statusMessage(status),
                shouldAnnounce = status != FeedbackSensorStatus.CONNECTED,
            )
        }
    }

    fun consumeAnnouncement() {
        _uiState.update { it.copy(shouldAnnounce = false) }
    }

    private fun updateConfidenceWindow(isValid: Boolean) {
        confidenceWindow.addLast(isValid)
        while (confidenceWindow.size > FeedbackThresholds.CONFIDENCE_WINDOW_SIZE) {
            confidenceWindow.removeFirst()
        }
    }

    private fun isConfidenceWindowStable(): Boolean {
        if (confidenceWindow.size < FeedbackThresholds.CONFIDENCE_WINDOW_SIZE) {
            return false
        }
        val validCount = confidenceWindow.count { it }
        return validCount.toFloat() / confidenceWindow.size >=
            FeedbackThresholds.CONFIDENCE_VALID_MIN_RATIO
    }

    private fun classifyWithHysteresis(
        distanceMeters: Float,
        currentLevel: FeedbackAlertLevel,
    ): FeedbackAlertLevel {
        return when (currentLevel) {
            FeedbackAlertLevel.DANGER -> {
                if (distanceMeters > FeedbackThresholds.DANGER_EXIT_METERS) {
                    FeedbackAlertLevel.CAUTION
                } else {
                    FeedbackAlertLevel.DANGER
                }
            }
            FeedbackAlertLevel.CAUTION -> {
                when {
                    distanceMeters <= FeedbackThresholds.DANGER_ENTER_METERS -> FeedbackAlertLevel.DANGER
                    distanceMeters > FeedbackThresholds.CAUTION_EXIT_METERS -> FeedbackAlertLevel.SAFE
                    else -> FeedbackAlertLevel.CAUTION
                }
            }
            FeedbackAlertLevel.SAFE -> {
                when {
                    distanceMeters <= FeedbackThresholds.DANGER_ENTER_METERS -> FeedbackAlertLevel.DANGER
                    distanceMeters <= FeedbackThresholds.CAUTION_ENTER_METERS -> FeedbackAlertLevel.CAUTION
                    else -> FeedbackAlertLevel.SAFE
                }
            }
        }
    }

    private fun shouldAnnounce(level: FeedbackAlertLevel, nowMillis: Long): Boolean {
        val isLevelChanged = level != lastAnnouncedLevel
        val isThrottleExpired = nowMillis - lastAnnouncedTimeMillis >
            FeedbackThresholds.ANNOUNCE_THROTTLE_MS

        if (isLevelChanged || isThrottleExpired) {
            lastAnnouncedLevel = level
            lastAnnouncedTimeMillis = nowMillis
            return true
        }
        return false
    }

    private fun guidanceMessage(level: FeedbackAlertLevel, distanceMeters: Float): String {
        return when (level) {
            FeedbackAlertLevel.SAFE -> "안전합니다. 전방 공간이 확보되어 있습니다."
            FeedbackAlertLevel.CAUTION -> "주의하세요. 전방 ${formatDistance(distanceMeters)} 안에 장애물이 있습니다."
            FeedbackAlertLevel.DANGER -> "위험합니다. 즉시 속도를 줄이거나 멈추세요."
        }
    }

    private fun statusMessage(status: FeedbackSensorStatus): String {
        return when (status) {
            FeedbackSensorStatus.WAITING -> "공간 인식 대기 중입니다."
            FeedbackSensorStatus.CONNECTED -> "공간 인식이 연결되었습니다."
            FeedbackSensorStatus.DISCONNECTED -> "센서 데이터가 일시적으로 끊겼습니다."
            FeedbackSensorStatus.ERROR -> "공간 인식에 문제가 발생했습니다."
        }
    }

    private fun formatDistance(distanceMeters: Float): String {
        return if (distanceMeters < 1f) {
            "${(distanceMeters * 100).toInt()}cm"
        } else {
            String.format("%.1fm", distanceMeters)
        }
    }

    override fun onCleared() {
        stopWatchdog()
        super.onCleared()
    }
}
