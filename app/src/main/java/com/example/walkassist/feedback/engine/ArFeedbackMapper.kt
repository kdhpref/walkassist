package com.example.walkassist.feedback.engine

import com.example.walkassist.ArMeasurementState
import com.example.walkassist.feedback.core.FeedbackInput
import com.example.walkassist.feedback.core.FeedbackObstacleSample
import com.example.walkassist.feedback.core.FeedbackSensorStatus
import com.example.walkassist.feedback.core.FeedbackSensorType

class ArFeedbackMapper {

    fun map(state: ArMeasurementState): FeedbackInput {
        // ── 개선: 거리 fallback 체인 ──
        // collisionDistance(충돌 예측) > centerDistance(정면) > depthDistance(깊이) > floorDistance
        // 기존에는 collisionDistanceMeters만 사용했으나,
        // collision이 null인 환경(평면만 감지된 초기 단계 등)에서도 안내가 가능하도록 확장.
        val distance = state.collisionDistanceMeters
            ?: state.centerDistanceMeters
            ?: state.depthDistanceMeters
            ?: state.floorDistanceMeters

        return when {
            state.trackingLabel != "tracking" -> {
                FeedbackInput.SensorStatus(
                    status = FeedbackSensorStatus.WAITING
                )
            }

            distance == null -> {
                FeedbackInput.SensorStatus(
                    status = FeedbackSensorStatus.WAITING
                )
            }

            else -> {
                FeedbackInput.Obstacle(
                    sample = FeedbackObstacleSample(
                        distanceMeters = distance,
                        confidence = (state.sensingConfidenceScore / 100f).coerceIn(0f, 1f),
                        sensorType = FeedbackSensorType.ARCORE
                    ),

                    // ── 추가: 환경 맥락 전달 ──
                    direction = state.suggestedDirection,
                    crosswalkDetected = state.crosswalkDetected
                )
            }
        }
    }
}