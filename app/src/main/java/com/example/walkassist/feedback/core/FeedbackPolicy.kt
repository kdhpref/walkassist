package com.example.walkassist.feedback.core

class FeedbackPolicy {
    fun decide(
        distanceMeters: Float,
        currentLevel: FeedbackAlertLevel,
    ): FeedbackDecision {
        val nextLevel = classifyWithHysteresis(distanceMeters, currentLevel)
        return FeedbackDecision(
            alertLevel = nextLevel,
            message = guidanceMessage(nextLevel, distanceMeters),
        )
    }

    fun statusMessage(status: FeedbackSensorStatus): String {
        return when (status) {
            FeedbackSensorStatus.WAITING -> "공간 인식 대기 중입니다."
            FeedbackSensorStatus.CONNECTED -> "공간 인식이 연결되었습니다."
            FeedbackSensorStatus.DISCONNECTED -> "센서 데이터가 일시적으로 끊겼습니다."
            FeedbackSensorStatus.ERROR -> "공간 인식에 문제가 발생했습니다."
        }
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

    private fun guidanceMessage(level: FeedbackAlertLevel, distanceMeters: Float): String {
        return when (level) {
            FeedbackAlertLevel.SAFE -> "안전합니다. 전방 공간이 확보되어 있습니다."
            FeedbackAlertLevel.CAUTION -> "주의하세요. 전방 ${formatDistance(distanceMeters)} 안에 장애물이 있습니다."
            FeedbackAlertLevel.DANGER -> "위험합니다. 즉시 속도를 줄이거나 멈추세요."
        }
    }

    private fun formatDistance(distanceMeters: Float): String {
        return if (distanceMeters < 1f) {
            "${(distanceMeters * 100).toInt()}cm"
        } else {
            String.format("%.1fm", distanceMeters)
        }
    }
}
