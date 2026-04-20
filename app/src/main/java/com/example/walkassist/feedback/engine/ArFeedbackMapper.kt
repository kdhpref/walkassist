package com.example.walkassist.feedback.engine

import com.example.walkassist.ArMeasurementState
import com.example.walkassist.feedback.core.FeedbackInput
import com.example.walkassist.feedback.core.FeedbackObstacleSample
import com.example.walkassist.feedback.core.FeedbackSensorStatus
import com.example.walkassist.feedback.core.FeedbackSensorType

class ArFeedbackMapper {
    fun map(state: ArMeasurementState): FeedbackInput {
        val distance = state.collisionDistanceMeters
        return when {
            state.trackingLabel != "tracking" -> {
                FeedbackInput.SensorStatus(FeedbackSensorStatus.WAITING)
            }
            distance == null -> {
                FeedbackInput.SensorStatus(FeedbackSensorStatus.WAITING)
            }
            else -> {
                FeedbackInput.Obstacle(
                    FeedbackObstacleSample(
                        distanceMeters = distance,
                        confidence = (state.sensingConfidenceScore / 100f).coerceIn(0f, 1f),
                        sensorType = FeedbackSensorType.ARCORE,
                    ),
                )
            }
        }
    }
}
