package com.example.walkassist.feedback.core

import java.util.Locale

/**
 * 거리 기준값
 *
 * B안 기준:
 * 1 = AR 0.5m 이하 즉시 위험
 * 2 = AR 1.0m 이하 위험 + 길찾기
 * 3 = AR 1.5m 이하 주의 + OCR
 * 4 = AR 2.0m 이하 진동만
 * 5 = SAFE / 무음
 */
object FeedbackThresholds {
    const val PRIORITY_1_METERS = 1.3f
    const val PRIORITY_2_METERS = 1.3f
    const val PRIORITY_3_METERS = 3.0f
    const val PRIORITY_4_METERS = 3.0f

    const val EXIT_BUFFER = 0.2f

    /**
     * 같은 안내 반복 방지 시간.
     *
     * 현재 기존 FeedbackQueue.kt가 FeedbackThresholds.ANNOUNCE_THROTTLE_MS를
     * 참조할 수 있으므로 유지합니다.
     */
    const val ANNOUNCE_THROTTLE_MS = 2_000L

    /**
     * 1순위 위험 안내는 안전상 더 짧게만 제한합니다.
     *
     * 현재 기존 FeedbackQueue.kt가 FeedbackThresholds.DANGER_THROTTLE_MS를
     * 참조할 수 있으므로 유지합니다.
     */
    const val DANGER_THROTTLE_MS = 700L

    /**
     * FeedbackRequest별 throttle 기준값입니다.
     *
     * 실제 반복 제한 판단은 FeedbackQueue에서 하고,
     * 이 값들은 FeedbackPolicy가 요청을 만들 때 기준값으로 넣습니다.
     */
    const val CRITICAL_OBSTACLE_THROTTLE_MS = 1_000L
    const val DANGER_OBSTACLE_THROTTLE_MS = 1_000L
    const val CAUTION_OBSTACLE_THROTTLE_MS = 2_000L
    const val HAPTIC_ONLY_THROTTLE_MS = 2_000L
    const val NAVIGATION_THROTTLE_MS = 2_500L
    const val OCR_THROTTLE_MS = 1_000L
    const val SENSOR_STATUS_THROTTLE_MS = 5_000L
}

/**
 * 피드백 정책 클래스.
 *
 * 공식 FeedbackRequest 생성 경로입니다.
 * A 길찾기, B OCR, C AR 위험 안내는 모두 이 클래스를 통해
 * FeedbackRequest를 만든 뒤 FeedbackQueue.enqueue(...)로 넣습니다.
 *
 * 주의:
 * - 이 버전은 사용자가 올린 기존 FeedbackPolicy.kt의 함수명과 흐름을 최대한 유지합니다.
 * - FeedbackAlertLevel.CRITICAL, HAPTIC_ONLY, SENSOR는 사용하지 않습니다.
 * - FeedbackSource.SENSOR_STATUS는 사용하지 않습니다.
 * - throttleKey / throttleMillis 기준값은 여기서 정하고, 실제 반복 제한은 FeedbackQueue가 처리합니다.
 */
class FeedbackPolicy {

    /**
     * 거리값을 기준으로 우선순위, 위험 단계, 출력 방식을 결정합니다.
     *
     * 현재 FeedbackAlertLevel이 DANGER / CAUTION / SAFE 중심이라고 보고 작성했습니다.
     */
    fun classifyByDistance(
        distanceMeters: Float
    ): Triple<Int, FeedbackAlertLevel, FeedbackOutputMode> {
        return when {
            distanceMeters <= FeedbackThresholds.PRIORITY_1_METERS -> {
                Triple(
                    1,
                    FeedbackAlertLevel.DANGER,
                    FeedbackOutputMode(
                        useSpeech = false,
                        useHaptic = true,
                        hapticStrength = HapticStrength.STRONG
                    )
                )
            }

            distanceMeters <= FeedbackThresholds.PRIORITY_2_METERS -> {
                Triple(
                    2,
                    FeedbackAlertLevel.DANGER,
                    FeedbackOutputMode(
                        useSpeech = false,
                        useHaptic = true,
                        hapticStrength = HapticStrength.MEDIUM
                    )
                )
            }

            distanceMeters <= FeedbackThresholds.PRIORITY_3_METERS -> {
                Triple(
                    3,
                    FeedbackAlertLevel.CAUTION,
                    FeedbackOutputMode(
                        useSpeech = false,
                        useHaptic = true,
                        hapticStrength = HapticStrength.LIGHT
                    )
                )
            }

            distanceMeters <= FeedbackThresholds.PRIORITY_4_METERS -> {
                Triple(
                    4,
                    FeedbackAlertLevel.CAUTION,
                    FeedbackOutputMode(
                        useSpeech = false,
                        useHaptic = true,
                        hapticStrength = HapticStrength.LIGHT
                    )
                )
            }

            else -> {
                Triple(
                    5,
                    FeedbackAlertLevel.SAFE,
                    FeedbackOutputMode(
                        useSpeech = false,
                        useHaptic = false,
                        hapticStrength = HapticStrength.LIGHT
                    )
                )
            }
        }
    }

    /**
     * C 작업자 - AR 장애물 안내 요청.
     */
    fun obstacleRequest(
        distanceMeters: Float,
        direction: String = "unknown",
        crosswalkDetected: Boolean = false
    ): FeedbackRequest {
        val (priority, alertLevel, outputMode) = classifyByDistance(distanceMeters)

        return FeedbackRequest(
            priority = priority,
            source = FeedbackSource.AR_OBSTACLE,
            alertLevel = alertLevel,
            message = obstacleMessage(
                alertLevel = alertLevel,
                priority = priority,
                distanceMeters = distanceMeters,
                direction = direction,
                crosswalkDetected = crosswalkDetected
            ),
            outputMode = outputMode,
            distanceMeters = distanceMeters,
            interruptCurrent = priority <= 2,
            throttleKey = obstacleThrottleKey(priority),
            throttleMillis = obstacleThrottleMillis(priority)
        )
    }

    /**
     * A 작업자 - 길찾기 안내.
     *
     * B안 유지:
     * 길찾기 안내는 2순위로 둡니다.
     */
    fun navigationRequest(
        message: String,
        distanceMeters: Float? = null
    ): FeedbackRequest {
        val normalizedMessage = message.trim().ifBlank {
            "길찾기 안내가 없습니다."
        }

        return FeedbackRequest(
            priority = 2,
            source = FeedbackSource.NAVIGATION,
            alertLevel = FeedbackAlertLevel.CAUTION,
            message = normalizedMessage,
            outputMode = FeedbackOutputMode(
                useSpeech = true,
                useHaptic = false,
                hapticStrength = HapticStrength.LIGHT
            ),
            distanceMeters = distanceMeters,
            interruptCurrent = false,
            throttleKey = "navigation",
            throttleMillis = FeedbackThresholds.NAVIGATION_THROTTLE_MS
        )
    }

    /**
     * B 작업자 - OCR 결과 안내.
     *
     * B안 유지:
     * OCR 안내는 3순위로 둡니다.
     */
    fun ocrRequest(
        message: String
    ): FeedbackRequest {
        val normalizedMessage = message.trim().ifBlank {
            "읽을 수 있는 글자가 없습니다."
        }

        return FeedbackRequest(
            priority = 3,
            source = FeedbackSource.OCR,
            alertLevel = FeedbackAlertLevel.SAFE,
            message = normalizedMessage,
            outputMode = FeedbackOutputMode(
                useSpeech = true,
                useHaptic = false,
                hapticStrength = HapticStrength.LIGHT
            ),
            distanceMeters = null,
            interruptCurrent = false,
            throttleKey = "ocr",
            throttleMillis = FeedbackThresholds.OCR_THROTTLE_MS
        )
    }

    /**
     * 센서 상태 안내 요청.
     *
     * 현재 FeedbackSource.SENSOR_STATUS가 프로젝트에 없기 때문에
     * 빌드 안정화를 위해 source는 AR_OBSTACLE로 유지합니다.
     *
     * 의미상으로는 SENSOR_STATUS가 더 정확하지만,
     * 지금은 위험 오류 제거가 우선이므로 기존 enum에 맞춥니다.
     */
    fun sensorStatusRequest(
        status: FeedbackSensorStatus
    ): FeedbackRequest {
        val alertLevel = when (status) {
            FeedbackSensorStatus.ERROR,
            FeedbackSensorStatus.DISCONNECTED -> FeedbackAlertLevel.CAUTION

            FeedbackSensorStatus.WAITING,
            FeedbackSensorStatus.CONNECTED -> FeedbackAlertLevel.SAFE
        }

        val useSpeech = when (status) {
            FeedbackSensorStatus.ERROR,
            FeedbackSensorStatus.DISCONNECTED,
            FeedbackSensorStatus.WAITING -> true

            FeedbackSensorStatus.CONNECTED -> false
        }
        return FeedbackRequest(
            priority = if (useSpeech) 3 else 5,
            source = FeedbackSource.AR_OBSTACLE,
            alertLevel = alertLevel,
            message = statusMessage(status),
            outputMode = FeedbackOutputMode(
                useSpeech = useSpeech,
                useHaptic = false,
                hapticStrength = HapticStrength.LIGHT
            ),
            distanceMeters = null,
            interruptCurrent = false,
            throttleKey = sensorThrottleKey(status),
            throttleMillis = FeedbackThresholds.SENSOR_STATUS_THROTTLE_MS
        )
    }

    /**
     * 기존 decide 함수 호환용입니다.
     *
     * 다른 코드에서 아직 decide(...)를 호출하고 있을 수 있으므로 유지합니다.
     * "Function decide is never used"는 위험 오류가 아니라 경고입니다.
     */
    fun decide(
        distanceMeters: Float,
        currentLevel: FeedbackAlertLevel,
        direction: String = "unknown",
        crosswalkDetected: Boolean = false
    ): FeedbackDecision {
        val nextLevel = classifyWithHysteresis(
            distanceMeters = distanceMeters,
            currentLevel = currentLevel
        )

        val priority = classifyByDistance(distanceMeters).first

        return FeedbackDecision(
            alertLevel = nextLevel,
            message = obstacleMessage(
                alertLevel = nextLevel,
                priority = priority,
                distanceMeters = distanceMeters,
                direction = direction,
                crosswalkDetected = crosswalkDetected
            )
        )
    }

    fun statusMessage(
        status: FeedbackSensorStatus
    ): String {
        return when (status) {
            FeedbackSensorStatus.WAITING -> "공간 인식 대기 중입니다."
            FeedbackSensorStatus.CONNECTED -> "공간 인식이 연결되었습니다."
            FeedbackSensorStatus.DISCONNECTED -> "센서 데이터가 일시적으로 끊겼습니다."
            FeedbackSensorStatus.ERROR -> "공간 인식에 문제가 발생했습니다."
        }
    }

    /**
     * 기존 FeedbackAlertLevel 구조에 맞춘 hysteresis 처리입니다.
     *
     * 현재 enum에 DANGER / CAUTION / SAFE만 있다고 보고 작성했습니다.
     * 따라서 CRITICAL, HAPTIC_ONLY, SENSOR 분기는 넣지 않습니다.
     */
    private fun classifyWithHysteresis(
        distanceMeters: Float,
        currentLevel: FeedbackAlertLevel
    ): FeedbackAlertLevel {
        return when (currentLevel) {
            FeedbackAlertLevel.DANGER -> {
                if (distanceMeters > FeedbackThresholds.PRIORITY_2_METERS + FeedbackThresholds.EXIT_BUFFER) {
                    FeedbackAlertLevel.CAUTION
                } else {
                    FeedbackAlertLevel.DANGER
                }
            }

            FeedbackAlertLevel.CAUTION -> {
                when {
                    distanceMeters <= FeedbackThresholds.PRIORITY_2_METERS -> FeedbackAlertLevel.DANGER
                    distanceMeters > FeedbackThresholds.PRIORITY_4_METERS + FeedbackThresholds.EXIT_BUFFER -> FeedbackAlertLevel.SAFE
                    else -> FeedbackAlertLevel.CAUTION
                }
            }

            FeedbackAlertLevel.SAFE -> {
                when {
                    distanceMeters <= FeedbackThresholds.PRIORITY_2_METERS -> FeedbackAlertLevel.DANGER
                    distanceMeters <= FeedbackThresholds.PRIORITY_4_METERS -> FeedbackAlertLevel.CAUTION
                    else -> FeedbackAlertLevel.SAFE
                }
            }

            else -> FeedbackAlertLevel.SAFE
        }
    }

    private fun obstacleMessage(
        alertLevel: FeedbackAlertLevel,
        priority: Int,
        distanceMeters: Float,
        direction: String,
        crosswalkDetected: Boolean
    ): String {
        val base = when {
            priority == 1 -> "즉시 멈추세요. 전방 ${formatDistance(distanceMeters)} 안에 장애물이 있습니다."
            priority == 2 -> "위험합니다. 전방 ${formatDistance(distanceMeters)} 안에 장애물이 있습니다."
            priority == 3 -> "주의하세요. 전방 ${formatDistance(distanceMeters)} 안에 장애물이 있습니다."
            priority == 4 -> "전방 ${formatDistance(distanceMeters)} 안에 장애물이 감지되었습니다."
            alertLevel == FeedbackAlertLevel.SAFE -> "안전합니다. 전방 공간이 확보되어 있습니다."
            else -> "전방 상황을 확인하세요."
        }

        val directionHint = when {
            alertLevel == FeedbackAlertLevel.SAFE -> ""
            direction == "left" -> " 왼쪽으로 이동하세요."
            direction == "right" -> " 오른쪽으로 이동하세요."
            direction == "center" -> " 중앙을 유지하세요."
            direction == "blocked" -> " 잠시 멈추고 주변을 확인하세요."
            else -> ""
        }

        val crosswalkHint = if (crosswalkDetected) {
            " 횡단보도가 감지되었습니다."
        } else {
            ""
        }

        return base + directionHint + crosswalkHint
    }

    private fun obstacleThrottleKey(
        priority: Int
    ): String {
        return when (priority) {
            1 -> "obstacle:critical"
            2 -> "obstacle:danger"
            3 -> "obstacle:caution"
            4 -> "obstacle:haptic_only"
            else -> "obstacle:safe"
        }
    }

    private fun obstacleThrottleMillis(
        priority: Int
    ): Long {
        return when (priority) {
            1 -> FeedbackThresholds.CRITICAL_OBSTACLE_THROTTLE_MS
            2 -> FeedbackThresholds.DANGER_OBSTACLE_THROTTLE_MS
            3 -> FeedbackThresholds.CAUTION_OBSTACLE_THROTTLE_MS
            4 -> FeedbackThresholds.HAPTIC_ONLY_THROTTLE_MS
            else -> 0L
        }
    }

    private fun sensorThrottleKey(
        status: FeedbackSensorStatus
    ): String {
        return when (status) {
            FeedbackSensorStatus.WAITING -> "sensor:waiting"
            FeedbackSensorStatus.CONNECTED -> "sensor:connected"
            FeedbackSensorStatus.DISCONNECTED -> "sensor:disconnected"
            FeedbackSensorStatus.ERROR -> "sensor:error"
            else -> "sensor:unknown"
        }
    }

    private fun formatDistance(
        distanceMeters: Float
    ): String {
        return if (distanceMeters < 1f) {
            "${(distanceMeters * 100).toInt()}cm"
        } else {
            String.format(Locale.KOREA, "%.1fm", distanceMeters)
        }
    }
}
