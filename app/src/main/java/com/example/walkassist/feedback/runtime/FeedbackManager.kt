package com.example.walkassist.feedback.runtime

import android.content.Context
import android.widget.TextView
import com.example.walkassist.feedback.core.FeedbackAlertLevel

class FeedbackManager(context: Context) {
    private val accessibilityAnnouncer = AccessibilityAnnouncer(context)
    private val hapticController = HapticFeedbackController(context)
    private val speechController = SpeechFeedbackController(context)

    fun provideFeedback(
        message: String,
        level: FeedbackAlertLevel,
        announcementView: TextView? = null,
    ) {
        if (message.isBlank()) return

        hapticController.vibrate(level)
        val announcedByTalkBack = accessibilityAnnouncer.announce(message, announcementView)
        if (!announcedByTalkBack) {
            speechController.speak(message, level)
        }
    }

    fun release() {
        hapticController.cancel()
        speechController.release()
    }
}
