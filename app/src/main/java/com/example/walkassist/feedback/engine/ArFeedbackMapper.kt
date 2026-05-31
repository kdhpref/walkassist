package com.example.walkassist.feedback.engine

import com.example.walkassist.ArMeasurementState
import com.example.walkassist.feedback.core.FeedbackInput
import com.example.walkassist.feedback.core.FeedbackObstacleSample
import com.example.walkassist.feedback.core.FeedbackSensorStatus
import com.example.walkassist.feedback.core.FeedbackSensorType

class ArFeedbackMapper {

    fun map(state: ArMeasurementState): FeedbackInput {
        val distance = state.collisionDistanceMeters

        val sensorType = when (state.trackingLabel) {
            "tracking" -> FeedbackSensorType.ARCORE
            "video_replay" -> FeedbackSensorType.VIDEO_REPLAY
            else -> null
        }

        return when {
            sensorType == null -> {
                FeedbackInput.SensorStatus(
                    status = FeedbackSensorStatus.WAITING,
                )
            }

            distance == null -> {
                FeedbackInput.SensorStatus(
                    status = FeedbackSensorStatus.WAITING,
                )
            }

            else -> {
                FeedbackInput.Obstacle(
                    sample = FeedbackObstacleSample(
                        distanceMeters = distance,
                        confidence = (state.worldMapConfidenceScore / 100f).coerceIn(0f, 1f),
                        sensorType = sensorType,
                    ),
                    direction = state.suggestedDirection,
                    crosswalkDetected = state.crosswalkDetected,
                )
            }
        }
    }
}
