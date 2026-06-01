package com.example.walkassist.feedback.core

/**
 * 안내가 어디서 왔는지 구분
 *
 * A - 길찾기
 * B - OCR 결과
 * C - AR 장애물 감지
 */
enum class FeedbackSource {
    AR_OBSTACLE,    // C - AR 장애물 감지 / AR 센서 상태 안내
    NAVIGATION,     // A - 길찾기
    OCR             // B - OCR 결과
}

/**
 * 안내 위험도 / 중요도
 *
 * DANGER  : 즉시 주의가 필요한 위험 상황
 * CAUTION : 주의가 필요한 상황
 * SAFE    : 안전하거나 일반 정보 안내
 */
enum class FeedbackAlertLevel {
    DANGER,
    CAUTION,
    SAFE
}

/**
 * 센서 연결 상태
 */
enum class FeedbackSensorStatus {
    WAITING,
    CONNECTED,
    DISCONNECTED,
    ERROR
}

/**
 * 센서 입력 종류
 */
enum class FeedbackSensorType {
    ARCORE,
    VIDEO_REPLAY,
    UNKNOWN
}

/**
 * 길찾기 안내 종류.
 *
 * 같은 지도 안내라도 경로 이탈/다음 지점 안내는 현실 방향 보정보다 우선합니다.
 */
enum class NavigationFeedbackKind {
    ROUTE_DEVIATION,
    ROUTE_STEP,
    ROUTE_ARRIVAL,
    ROUTE_START_END,
    ROUTE_SEARCH_STATUS,
    ROUTE_REALITY,
    ROUTE_POINT_INFO,
}

/**
 * 진동 강도
 */
enum class HapticStrength {
    LIGHT,
    MEDIUM,
    STRONG
}

/**
 * 어떤 출력을 사용할지 결정하는 모델
 */
data class FeedbackOutputMode(
    val useSpeech: Boolean = true,
    val useHaptic: Boolean = true,
    val hapticStrength: HapticStrength = HapticStrength.MEDIUM
)

/**
 * FeedbackPolicy가 판단한 결과
 */
data class FeedbackDecision(
    val alertLevel: FeedbackAlertLevel,
    val message: String
)

/**
 * AR 장애물 감지 샘플
 */
data class FeedbackObstacleSample(
    val distanceMeters: Float,
    val confidence: Float = 0f,
    val sensorType: FeedbackSensorType = FeedbackSensorType.UNKNOWN
)

/**
 * FeedbackViewModel로 들어오는 입력 모델
 */
sealed class FeedbackInput {
    data class SensorStatus(
        val status: FeedbackSensorStatus,
        val message: String = when (status) {
            FeedbackSensorStatus.WAITING -> "공간 정보를 수집하는 중입니다."
            FeedbackSensorStatus.CONNECTED -> "공간 인식이 연결되었습니다."
            FeedbackSensorStatus.DISCONNECTED -> "센서 정보가 일시적으로 끊겼습니다."
            FeedbackSensorStatus.ERROR -> "공간 인식 오류가 발생했습니다."
        }
    ) : FeedbackInput()

    data class Obstacle(
        val sample: FeedbackObstacleSample,
        val direction: String = "unknown",
        val crosswalkDetected: Boolean = false
    ) : FeedbackInput()

    data class Message(
        val source: FeedbackSource,
        val alertLevel: FeedbackAlertLevel,
        val message: String,
        val distanceMeters: Float? = null,
        val confidence: Float = 0f,
        val sensorStatus: FeedbackSensorStatus = FeedbackSensorStatus.CONNECTED,
        val direction: String = "unknown",
        val crosswalkDetected: Boolean = false
    ) : FeedbackInput()
}

/**
 * 피드백 오버레이 UI 상태 모델
 */
data class FeedbackUiState(
    val alertLevel: FeedbackAlertLevel = FeedbackAlertLevel.SAFE,
    val sensorStatus: FeedbackSensorStatus = FeedbackSensorStatus.WAITING,
    val distanceMeters: Float? = null,
    val crosswalkDetected: Boolean = false,
    val direction: String = "unknown",
    val message: String = "공간 정보를 수집하는 중입니다.",
    val confidence: Float = 0f,
    val shouldAnnounce: Boolean = false
)

/**
 * 예전 코드 호환용 팩토리입니다.
 *
 * 공식 생성 경로는 FeedbackPolicy입니다.
 * 신규 코드는 아래 함수를 직접 쓰지 말고 다음을 사용하세요.
 *
 * - FeedbackPolicy().obstacleRequest(...)
 * - FeedbackPolicy().navigationRequest(...)
 * - FeedbackPolicy().ocrRequest(...)
 * - FeedbackPolicy().sensorStatusRequest(...)
 */
@Deprecated(
    message = "FeedbackRequest 생성은 FeedbackPolicy를 공식 경로로 사용하세요.",
    replaceWith = ReplaceWith("FeedbackPolicy().obstacleRequest(distanceMeters ?: 0f)")
)
object FeedbackRequestFactory {

    private val policy = FeedbackPolicy()

    @Deprecated(
        message = "FeedbackPolicy().obstacleRequest(...)를 사용하세요.",
        replaceWith = ReplaceWith("FeedbackPolicy().obstacleRequest(distanceMeters ?: 0f)")
    )
    fun createArObstacleRequest(
        message: String,
        distanceMeters: Float? = null
    ): FeedbackRequest {
        return if (distanceMeters != null) {
            policy.obstacleRequest(distanceMeters = distanceMeters)
        } else {
            FeedbackRequest(
                priority = 1,
                source = FeedbackSource.AR_OBSTACLE,
                alertLevel = FeedbackAlertLevel.DANGER,
                message = message,
                outputMode = FeedbackOutputMode(
                    useSpeech = true,
                    useHaptic = true,
                    hapticStrength = HapticStrength.STRONG
                ),
                distanceMeters = null,
                interruptCurrent = true,
                throttleKey = "obstacle:danger",
                throttleMillis = 700L
            )
        }
    }

    @Deprecated(
        message = "FeedbackPolicy().navigationRequest(...)를 사용하세요.",
        replaceWith = ReplaceWith("FeedbackPolicy().navigationRequest(message)")
    )
    fun createNavigationRequest(
        message: String,
        distanceMeters: Float? = null
    ): FeedbackRequest {
        return policy.navigationRequest(
            message = message,
            distanceMeters = distanceMeters
        )
    }

    @Deprecated(
        message = "FeedbackPolicy().ocrRequest(...)를 사용하세요.",
        replaceWith = ReplaceWith("FeedbackPolicy().ocrRequest(message)")
    )
    fun createOcrRequest(
        message: String
    ): FeedbackRequest {
        return policy.ocrRequest(message)
    }

    @Deprecated(
        message = "FeedbackPolicy().sensorStatusRequest(...)를 사용하세요.",
        replaceWith = ReplaceWith("FeedbackPolicy().sensorStatusRequest(status)")
    )
    fun createSensorStatusRequest(
        status: FeedbackSensorStatus
    ): FeedbackRequest {
        return policy.sensorStatusRequest(status)
    }
}
