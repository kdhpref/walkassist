package com.example.walkassist.feedback.runtime

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.TextView
import com.example.walkassist.feedback.core.FeedbackAlertLevel
import com.example.walkassist.feedback.core.FeedbackOutputMode
import com.example.walkassist.feedback.core.FeedbackRequest
import com.example.walkassist.feedback.core.FeedbackSource
import com.example.walkassist.feedback.core.HapticStrength

class FeedbackManager(context: Context) {
    private val accessibilityAnnouncer = AccessibilityAnnouncer(context)
    private val hapticController = HapticFeedbackController(context)
    private val speechController = SpeechFeedbackController(context)
    private var prioritySpeechProtectedUntilMs = 0L

    fun provideFeedback(
        request: FeedbackRequest,
        announcementView: TextView? = null,
    ) {
        if (
            request.message.isBlank() &&
            !request.outputMode.useHaptic &&
            !request.outputMode.useSpeech
        ) {
            return
        }

        if (request.outputMode.useHaptic) {
            hapticController.vibrate(
                level = request.alertLevel,
                strength = request.outputMode.hapticStrength,
            )
        }

        if (request.outputMode.useSpeech && request.message.isNotBlank()) {
            speakIfNeeded(
                message = request.message,
                level = request.alertLevel,
                announcementView = announcementView,
                prioritySpeech = request.interruptCurrent || request.priority <= 2,
            )
        }
    }

    fun provideFeedback(
        message: String,
        level: FeedbackAlertLevel,
        announcementView: TextView? = null,
        prioritySpeech: Boolean = false,
    ) {
        provideFeedback(
            message = message,
            level = level,
            outputMode = FeedbackOutputMode(
                useSpeech = true,
                useHaptic = true,
                hapticStrength = defaultHapticStrength(level),
            ),
            announcementView = announcementView,
            prioritySpeech = prioritySpeech,
        )
    }

    fun provideFeedback(
        message: String,
        level: FeedbackAlertLevel,
        outputMode: FeedbackOutputMode,
        announcementView: TextView? = null,
        prioritySpeech: Boolean = false,
    ) {
        if (message.isBlank() && !outputMode.useHaptic) return

        provideFeedback(
            request = FeedbackRequest(
                priority = defaultPriority(level),
                source = FeedbackSource.AR_OBSTACLE,
                alertLevel = level,
                message = message,
                outputMode = outputMode,
                distanceMeters = null,
                interruptCurrent = prioritySpeech,
            ),
            announcementView = announcementView,
        )
    }

    fun stop() {
        hapticController.cancel()
    }

    fun release() {
        hapticController.cancel()
        speechController.release()
    }

    private fun speakIfNeeded(
        message: String,
        level: FeedbackAlertLevel,
        announcementView: TextView?,
        prioritySpeech: Boolean,
    ) {
        val announcedByTalkBack = accessibilityAnnouncer.announce(message, announcementView)
        if (announcedByTalkBack) return

        val now = SystemClock.elapsedRealtime()
        if (prioritySpeech) {
            prioritySpeechProtectedUntilMs = now + PRIORITY_SPEECH_PROTECTION_MS
            speechController.speak(
                message = message,
                level = level,
                queueMode = TextToSpeech.QUEUE_FLUSH,
            )
            return
        }

        if (now < prioritySpeechProtectedUntilMs) {
            Log.d(TAG, "Skipping non-priority speech while priority speech is protected")
            return
        }

        speechController.speak(
            message = message,
            level = level,
            queueMode = TextToSpeech.QUEUE_FLUSH,
        )
    }

    private fun defaultHapticStrength(
        level: FeedbackAlertLevel,
    ): HapticStrength {
        return when (level) {
            FeedbackAlertLevel.DANGER -> HapticStrength.STRONG
            FeedbackAlertLevel.CAUTION -> HapticStrength.MEDIUM
            FeedbackAlertLevel.SAFE -> HapticStrength.LIGHT
        }
    }

    private fun defaultPriority(
        level: FeedbackAlertLevel,
    ): Int {
        return when (level) {
            FeedbackAlertLevel.DANGER -> 1
            FeedbackAlertLevel.CAUTION -> 3
            FeedbackAlertLevel.SAFE -> 5
        }
    }

    companion object {
        private const val TAG = "FeedbackManager"
        private const val PRIORITY_SPEECH_PROTECTION_MS = 8_000L
    }
}
