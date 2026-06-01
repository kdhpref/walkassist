package com.example.walkassist.feedback.runtime

import android.content.Context
import com.example.walkassist.feedback.core.FeedbackAlertLevel
import com.example.walkassist.feedback.core.HapticStrength

class HapticFeedbackController(
    @Suppress("UNUSED_PARAMETER")
    context: Context,
) {
    @Suppress("UNUSED_PARAMETER")
    fun vibrate(
        level: FeedbackAlertLevel,
        strength: HapticStrength = HapticStrength.MEDIUM,
    ) = Unit

    fun cancel() = Unit
}
