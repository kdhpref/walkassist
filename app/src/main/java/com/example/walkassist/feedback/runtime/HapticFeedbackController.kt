package com.example.walkassist.feedback.runtime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.walkassist.feedback.core.FeedbackAlertLevel

class HapticFeedbackController(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager =
            context.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    fun vibrate(level: FeedbackAlertLevel) {
        if (!vibrator.hasVibrator()) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(effectFor(level))
            } else {
                @Suppress("DEPRECATION")
                when (level) {
                    FeedbackAlertLevel.SAFE -> vibrator.vibrate(50)
                    FeedbackAlertLevel.CAUTION -> vibrator.vibrate(longArrayOf(0, 200, 200, 200), -1)
                    FeedbackAlertLevel.DANGER -> vibrator.vibrate(longArrayOf(0, 80, 40, 80, 40, 80, 40, 80), -1)
                }
            }
        } catch (error: Exception) {
            Log.e(TAG, "Haptic feedback failed", error)
        }
    }

    fun cancel() {
        vibrator.cancel()
    }

    private fun effectFor(level: FeedbackAlertLevel): VibrationEffect {
        return when (level) {
            FeedbackAlertLevel.SAFE -> VibrationEffect.createOneShot(50, 30)
            FeedbackAlertLevel.CAUTION -> VibrationEffect.createWaveform(
                longArrayOf(0, 200, 200, 200),
                intArrayOf(0, 160, 0, 160),
                -1,
            )
            FeedbackAlertLevel.DANGER -> VibrationEffect.createWaveform(
                longArrayOf(0, 80, 40, 80, 40, 80, 40, 80),
                intArrayOf(0, 255, 0, 255, 0, 255, 0, 255),
                -1,
            )
        }
    }

    companion object {
        private const val TAG = "HapticFeedback"
    }
}
