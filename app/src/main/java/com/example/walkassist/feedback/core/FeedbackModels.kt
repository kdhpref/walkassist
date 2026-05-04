package com.example.walkassist.feedback.core

enum class FeedbackAlertLevel {
    SAFE,
    CAUTION,
    DANGER,
}

enum class FeedbackSensorStatus {
    WAITING,
    CONNECTED,
    DISCONNECTED,
    ERROR,
}

enum class FeedbackSensorType {
    ARCORE,
    YOLO,
    OCR,
    MAP,
    VIDEO_REPLAY,
    UNKNOWN,
}

data class FeedbackObstacleSample(
    val distanceMeters: Float,
    val confidence: Float,
    val sensorType: FeedbackSensorType,
    val timestampMillis: Long = System.currentTimeMillis(),
) {
    fun isValid(): Boolean = distanceMeters >= 0f && confidence in 0f..1f

    fun isFresh(nowMillis: Long, freshnessMillis: Long): Boolean {
        return nowMillis - timestampMillis <= freshnessMillis
    }
}

data class FeedbackUiState(
    val alertLevel: FeedbackAlertLevel = FeedbackAlertLevel.SAFE,
    val sensorStatus: FeedbackSensorStatus = FeedbackSensorStatus.WAITING,
    val distanceMeters: Float? = null,
    val confidence: Float = 0f,
    val message: String = "공간 인식 대기 중입니다.",
    val shouldAnnounce: Boolean = false,
)

sealed interface FeedbackInput {
    data class Obstacle(val sample: FeedbackObstacleSample) : FeedbackInput
    data class SensorStatus(val status: FeedbackSensorStatus) : FeedbackInput
}

data class FeedbackDecision(
    val alertLevel: FeedbackAlertLevel,
    val message: String,
)

object FeedbackThresholds {
    const val DANGER_ENTER_METERS = 1.5f
    const val DANGER_EXIT_METERS = 1.7f
    const val CAUTION_ENTER_METERS = 3.0f
    const val CAUTION_EXIT_METERS = 3.2f
    const val MIN_CONFIDENCE = 0.5f
    const val DATA_FRESHNESS_MS = 500L
    const val CONFIDENCE_WINDOW_SIZE = 3
    const val CONFIDENCE_VALID_MIN_RATIO = 0.5f
    const val SENSOR_WATCHDOG_MS = 2_000L
    const val ANNOUNCE_THROTTLE_MS = 2_000L
}
