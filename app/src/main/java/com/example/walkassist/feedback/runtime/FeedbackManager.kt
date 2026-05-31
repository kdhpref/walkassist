package com.example.walkassist.feedback.runtime

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
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
    private val toneGenerator = ToneGenerator(AudioManager.STREAM_NOTIFICATION, TONE_VOLUME_PERCENT)
    private var prioritySpeechProtectedUntilMs = 0L

    fun provideFeedback(
        request: FeedbackRequest,
        announcementView: TextView? = null,
        queueSpeech: Boolean = false,
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
                queueSpeech = queueSpeech,
            )
        }
    }

    fun provideFeedback(
        message: String,
        level: FeedbackAlertLevel,
        announcementView: TextView? = null,
        prioritySpeech: Boolean = false,
        queueSpeech: Boolean = false,
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
            queueSpeech = queueSpeech,
        )
    }

    fun provideFeedback(
        message: String,
        level: FeedbackAlertLevel,
        outputMode: FeedbackOutputMode,
        announcementView: TextView? = null,
        prioritySpeech: Boolean = false,
        queueSpeech: Boolean = false,
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
                interruptCurrent = prioritySpeech && !queueSpeech,
            ),
            announcementView = announcementView,
            queueSpeech = queueSpeech,
        )
    }

    fun stop() {
        hapticController.cancel()
    }

    fun stopSpeech() {
        speechController.stopSpeaking()
    }

    fun speakQueued(
        message: String,
        level: FeedbackAlertLevel,
    ) {
        speechController.speak(
            message = message,
            level = level,
            queueMode = TextToSpeech.QUEUE_ADD,
        )
    }

    fun playObstaclePulse(urgent: Boolean) {
        val level = if (urgent) FeedbackAlertLevel.DANGER else FeedbackAlertLevel.CAUTION
        toneGenerator.startTone(
            ToneGenerator.TONE_PROP_BEEP,
            if (urgent) URGENT_PULSE_TONE_MS else WATCH_PULSE_TONE_MS,
        )
        hapticController.vibrate(
            level = level,
            strength = if (urgent) HapticStrength.STRONG else HapticStrength.MEDIUM,
        )
    }

    fun release() {
        hapticController.cancel()
        toneGenerator.release()
        speechController.release()
    }

    private fun speakIfNeeded(
        message: String,
        level: FeedbackAlertLevel,
        announcementView: TextView?,
        prioritySpeech: Boolean,
        queueSpeech: Boolean = false,
    ) {
        val announcedByTalkBack = accessibilityAnnouncer.announce(message, announcementView)
        if (announcedByTalkBack) return

        val now = SystemClock.elapsedRealtime()
        if (queueSpeech) {
            speechController.speak(
                message = message,
                level = level,
                queueMode = TextToSpeech.QUEUE_ADD,
            )
            return
        }

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
        private const val TONE_VOLUME_PERCENT = 80
        private const val WATCH_PULSE_TONE_MS = 120
        private const val URGENT_PULSE_TONE_MS = 80
    }
}
