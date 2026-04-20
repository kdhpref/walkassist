package com.example.walkassist.feedback.engine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.walkassist.feedback.core.FeedbackAlertLevel
import com.example.walkassist.feedback.core.FeedbackInput
import com.example.walkassist.feedback.core.FeedbackPolicy
import com.example.walkassist.feedback.core.FeedbackSensorStatus
import com.example.walkassist.feedback.core.FeedbackThresholds
import com.example.walkassist.feedback.core.FeedbackUiState
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
    private val policy = FeedbackPolicy()
    private val confidenceWindow = ArrayDeque<Boolean>()
    private val _uiState = MutableStateFlow(FeedbackUiState())

    val uiState: StateFlow<FeedbackUiState> = _uiState.asStateFlow()

    private var lastValidDataTimeMillis = 0L
    private var lastAnnouncedLevel: FeedbackAlertLevel? = null
    private var lastAnnouncedTimeMillis = 0L
    private var watchdogJob: Job? = null

    fun startWatchdog() {
        if (watchdogJob?.isActive == true) return

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
                            message = policy.statusMessage(FeedbackSensorStatus.DISCONNECTED),
                            shouldAnnounce = true,
                        )
                    }
                }
            }
        }
    }

    fun onInput(input: FeedbackInput, nowMillis: Long = System.currentTimeMillis()) {
        when (input) {
            is FeedbackInput.Obstacle -> reportObstacle(input, nowMillis)
            is FeedbackInput.SensorStatus -> reportSensorStatus(input.status)
        }
    }

    fun consumeAnnouncement() {
        _uiState.update { it.copy(shouldAnnounce = false) }
    }

    private fun reportObstacle(input: FeedbackInput.Obstacle, nowMillis: Long) {
        val sample = input.sample
        if (!sample.isValid() || !sample.isFresh(nowMillis, FeedbackThresholds.DATA_FRESHNESS_MS)) {
            return
        }

        updateConfidenceWindow(sample.confidence >= FeedbackThresholds.MIN_CONFIDENCE)
        if (!isConfidenceWindowStable()) {
            return
        }

        lastValidDataTimeMillis = nowMillis
        val decision = policy.decide(
            distanceMeters = sample.distanceMeters,
            currentLevel = _uiState.value.alertLevel,
        )
        val shouldAnnounce = shouldAnnounce(decision.alertLevel, nowMillis)

        _uiState.update {
            it.copy(
                alertLevel = decision.alertLevel,
                sensorStatus = FeedbackSensorStatus.CONNECTED,
                distanceMeters = sample.distanceMeters,
                confidence = sample.confidence,
                message = decision.message,
                shouldAnnounce = shouldAnnounce,
            )
        }
    }

    private fun reportSensorStatus(status: FeedbackSensorStatus) {
        val previousStatus = _uiState.value.sensorStatus
        _uiState.update {
            it.copy(
                sensorStatus = status,
                message = policy.statusMessage(status),
                shouldAnnounce = status != FeedbackSensorStatus.CONNECTED && status != previousStatus,
            )
        }
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

    override fun onCleared() {
        watchdogJob?.cancel()
        super.onCleared()
    }
}
