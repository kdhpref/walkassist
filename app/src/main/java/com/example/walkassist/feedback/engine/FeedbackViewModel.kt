package com.example.walkassist.feedback.engine

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.walkassist.feedback.core.FeedbackAlertLevel
import com.example.walkassist.feedback.core.FeedbackInput
import com.example.walkassist.feedback.core.FeedbackPolicy
import com.example.walkassist.feedback.core.FeedbackRequest
import com.example.walkassist.feedback.core.FeedbackSensorStatus
import com.example.walkassist.feedback.core.FeedbackUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class FeedbackViewModel(
    private val feedbackPolicy: FeedbackPolicy = FeedbackPolicy()
) : ViewModel() {

    private val _uiState = MutableStateFlow(FeedbackUiState())
    val uiState: StateFlow<FeedbackUiState> = _uiState.asStateFlow()

    private var watchdogJob: Job? = null
    private var lastInputTimeMillis: Long = System.currentTimeMillis()

    fun onInput(input: FeedbackInput) {
        lastInputTimeMillis = System.currentTimeMillis()

        val previous = _uiState.value

        val next = when (input) {
            is FeedbackInput.SensorStatus -> {
                handleSensorStatusInput(
                    previous = previous,
                    status = input.status
                )
            }

            is FeedbackInput.Obstacle -> {
                handleObstacleInput(
                    previous = previous,
                    input = input
                )
            }

            is FeedbackInput.Message -> {
                handleMessageInput(
                    previous = previous,
                    input = input
                )
            }
        }

        _uiState.value = next
    }

    /**
     * MainActivity에서 TTS/진동 출력을 한 번 처리한 뒤 다시 false로 내려줍니다.
     */
    fun consumeAnnouncement() {
        _uiState.value = _uiState.value.copy(
            shouldAnnounce = false
        )
    }

    /**
     * AR 입력이 일정 시간 들어오지 않을 때 센서 끊김 상태로 전환합니다.
     */
    fun startWatchdog() {
        if (watchdogJob != null) return

        watchdogJob = viewModelScope.launch {
            while (isActive) {
                delay(WATCHDOG_CHECK_INTERVAL_MS)

                val elapsedMillis = System.currentTimeMillis() - lastInputTimeMillis

                if (elapsedMillis > WATCHDOG_DISCONNECTED_TIMEOUT_MS) {
                    val previous = _uiState.value

                    if (previous.sensorStatus != FeedbackSensorStatus.DISCONNECTED) {
                        val request = feedbackPolicy.sensorStatusRequest(
                            FeedbackSensorStatus.DISCONNECTED
                        )

                        _uiState.value = request.toUiState(
                            sensorStatus = FeedbackSensorStatus.DISCONNECTED,
                            confidence = 0f,
                            direction = previous.direction,
                            crosswalkDetected = previous.crosswalkDetected,
                            shouldAnnounce = shouldAnnounceRequest(
                                previous = previous,
                                request = request
                            )
                        )
                    }
                }
            }
        }
    }

    override fun onCleared() {
        watchdogJob?.cancel()
        watchdogJob = null
        super.onCleared()
    }

    private fun handleSensorStatusInput(
        previous: FeedbackUiState,
        status: FeedbackSensorStatus
    ): FeedbackUiState {
        val request = feedbackPolicy.sensorStatusRequest(status)

        val nextDistanceMeters = when (status) {
            FeedbackSensorStatus.CONNECTED -> previous.distanceMeters
            FeedbackSensorStatus.WAITING,
            FeedbackSensorStatus.DISCONNECTED,
            FeedbackSensorStatus.ERROR -> null
        }

        val nextConfidence = when (status) {
            FeedbackSensorStatus.CONNECTED -> previous.confidence
            FeedbackSensorStatus.WAITING,
            FeedbackSensorStatus.DISCONNECTED,
            FeedbackSensorStatus.ERROR -> 0f
        }

        val nextDirection = when (status) {
            FeedbackSensorStatus.CONNECTED -> previous.direction
            FeedbackSensorStatus.WAITING,
            FeedbackSensorStatus.DISCONNECTED,
            FeedbackSensorStatus.ERROR -> "unknown"
        }

        val nextCrosswalkDetected = when (status) {
            FeedbackSensorStatus.CONNECTED -> previous.crosswalkDetected
            FeedbackSensorStatus.WAITING,
            FeedbackSensorStatus.DISCONNECTED,
            FeedbackSensorStatus.ERROR -> false
        }

        return FeedbackUiState(
            alertLevel = request.alertLevel,
            sensorStatus = status,
            distanceMeters = nextDistanceMeters,
            crosswalkDetected = nextCrosswalkDetected,
            direction = nextDirection,
            message = request.message,
            confidence = nextConfidence,
            shouldAnnounce = shouldAnnounceRequest(
                previous = previous,
                request = request
            )
        )
    }

    private fun handleObstacleInput(
        previous: FeedbackUiState,
        input: FeedbackInput.Obstacle
    ): FeedbackUiState {
        val request = feedbackPolicy.obstacleRequest(
            distanceMeters = input.sample.distanceMeters,
            direction = input.direction,
            crosswalkDetected = input.crosswalkDetected
        )

        return request.toUiState(
            sensorStatus = FeedbackSensorStatus.CONNECTED,
            confidence = input.sample.confidence.coerceIn(0f, 1f),
            direction = input.direction,
            crosswalkDetected = input.crosswalkDetected,
            shouldAnnounce = shouldAnnounceRequest(
                previous = previous,
                request = request
            )
        )
    }

    private fun handleMessageInput(
        previous: FeedbackUiState,
        input: FeedbackInput.Message
    ): FeedbackUiState {
        val normalizedMessage = input.message.trim()

        return FeedbackUiState(
            alertLevel = input.alertLevel,
            sensorStatus = input.sensorStatus,
            distanceMeters = input.distanceMeters,
            crosswalkDetected = input.crosswalkDetected,
            direction = input.direction,
            message = normalizedMessage,
            confidence = input.confidence.coerceIn(0f, 1f),
            shouldAnnounce = shouldAnnounceMessage(
                previous = previous,
                nextAlertLevel = input.alertLevel,
                nextMessage = normalizedMessage
            )
        )
    }

    private fun FeedbackRequest.toUiState(
        sensorStatus: FeedbackSensorStatus,
        confidence: Float,
        direction: String,
        crosswalkDetected: Boolean,
        shouldAnnounce: Boolean
    ): FeedbackUiState {
        return FeedbackUiState(
            alertLevel = alertLevel,
            sensorStatus = sensorStatus,
            distanceMeters = distanceMeters,
            crosswalkDetected = crosswalkDetected,
            direction = direction,
            message = message,
            confidence = confidence,
            shouldAnnounce = shouldAnnounce
        )
    }

    private fun shouldAnnounceRequest(          // 여기서부터
        previous: FeedbackUiState,
        request: FeedbackRequest
    ): Boolean {
        if (!request.outputMode.useSpeech && !request.outputMode.useHaptic) {
            return false
        }

        // 실제 반복 제한은 FeedbackQueue의 throttle이 담당하도록 한다.
        // priority 1~4는 실제 보행 안내 대상이므로 Queue까지 보낸다.
        if (request.priority <= 4) {
            return true
        }

        if (
            previous.sensorStatus != FeedbackSensorStatus.CONNECTED &&
            request.alertLevel == FeedbackAlertLevel.DANGER
        ) {
            return true
        }

        if (previous.alertLevel != request.alertLevel) {
            return true
        }

        if (
            previous.message != request.message &&
            request.alertLevel != FeedbackAlertLevel.SAFE
        ) {
            return true
        }

        return false
    }           // 여기까지 코드를 수정함

    private fun shouldAnnounceMessage(
        previous: FeedbackUiState,
        nextAlertLevel: FeedbackAlertLevel,
        nextMessage: String
    ): Boolean {
        if (nextAlertLevel == FeedbackAlertLevel.DANGER) {
            return true
        }

        if (nextAlertLevel == FeedbackAlertLevel.SAFE) {
            return false
        }

        return previous.alertLevel != nextAlertLevel ||
                previous.message != nextMessage
    }

    companion object {
        private const val WATCHDOG_CHECK_INTERVAL_MS = 2_000L
        private const val WATCHDOG_DISCONNECTED_TIMEOUT_MS = 5_000L
    }
}