package com.example.walkassist.feedback.runtime

import android.content.Context
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.util.Log
import android.widget.TextView
import com.example.walkassist.feedback.core.FeedbackAlertLevel

class FeedbackManager(context: Context) {
    private val accessibilityAnnouncer = AccessibilityAnnouncer(context)
    private val hapticController = HapticFeedbackController(context)
    private val speechController = SpeechFeedbackController(context)
    private var prioritySpeechProtectedUntilMs = 0L

    fun provideFeedback(
        message: String,
        level: FeedbackAlertLevel,
        announcementView: TextView? = null,
        prioritySpeech: Boolean = false,
    ) {
        if (message.isBlank()) return

        hapticController.vibrate(level)
        val announcedByTalkBack = accessibilityAnnouncer.announce(message, announcementView)
        if (!announcedByTalkBack) {
            val now = SystemClock.elapsedRealtime()
            if (prioritySpeech) {
                prioritySpeechProtectedUntilMs = now + PRIORITY_SPEECH_PROTECTION_MS
                speechController.speak(
                    message = message,
                    level = level,
                    queueMode = TextToSpeech.QUEUE_FLUSH,
                )
            } else if (now < prioritySpeechProtectedUntilMs) {
                Log.d(TAG, "Skipping non-priority speech while priority speech is protected")
            } else {
                speechController.speak(
                    message = message,
                    level = level,
                    queueMode = TextToSpeech.QUEUE_FLUSH,
                )
            }
        }
    }

    fun release() {
        hapticController.cancel()
        speechController.release()
    }

    companion object {
        private const val TAG = "FeedbackManager"
        private const val PRIORITY_SPEECH_PROTECTION_MS = 8_000L
    }
}
