package com.example.walkassist.feedback.runtime

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.TextView

class AccessibilityAnnouncer(context: Context) {
    private val accessibilityManager =
        context.applicationContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager

    fun isTalkBackEnabled(): Boolean {
        return accessibilityManager.isEnabled && accessibilityManager.isTouchExplorationEnabled
    }

    fun announce(message: String, view: TextView?): Boolean {
        if (!isTalkBackEnabled() || view == null || message.isBlank()) {
            return false
        }
        view.text = message
        view.contentDescription = message
        view.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
        return true
    }
}