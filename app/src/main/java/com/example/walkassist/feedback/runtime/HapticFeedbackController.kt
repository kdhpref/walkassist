package com.example.walkassist.feedback.runtime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.example.walkassist.feedback.core.FeedbackAlertLevel
import com.example.walkassist.feedback.core.HapticStrength

class HapticFeedbackController(context: Context) {
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager =
            context.applicationContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.applicationContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    /**
     * 위험도와 진동 강도에 따라 진동을 실행합니다.
     *
     * minSdk가 26 이상이면 VibrationEffect를 바로 사용할 수 있으므로
     * Build.VERSION_CODES.O 분기는 제거했습니다.
     */
    fun vibrate(
        level: FeedbackAlertLevel,
        strength: HapticStrength = HapticStrength.MEDIUM,
    ) {
        if (!vibrator.hasVibrator()) return

        try {
            vibrator.vibrate(
                effectFor(
                    level = level,
                    amplitude = amplitudeFor(strength),
                ),
            )
        } catch (error: Exception) {
            Log.e(TAG, "Haptic feedback failed", error)
        }
    }

    fun cancel() {
        vibrator.cancel()
    }

    private fun amplitudeFor(
        strength: HapticStrength,
    ): Int {
        return when (strength) {
            HapticStrength.LIGHT -> 60
            HapticStrength.MEDIUM -> 160
            HapticStrength.STRONG -> 255
        }
    }

    private fun effectFor(
        level: FeedbackAlertLevel,
        amplitude: Int,
    ): VibrationEffect {
        return when (level) {
            FeedbackAlertLevel.SAFE -> {
                VibrationEffect.createOneShot(
                    50,
                    amplitude,
                )
            }

            FeedbackAlertLevel.CAUTION -> {
                VibrationEffect.createWaveform(
                    longArrayOf(0, 200, 200, 200),
                    intArrayOf(0, amplitude, 0, amplitude),
                    -1,
                )
            }

            FeedbackAlertLevel.DANGER -> {
                VibrationEffect.createWaveform(
                    longArrayOf(0, 80, 40, 80, 40, 80, 40, 80),
                    intArrayOf(0, amplitude, 0, amplitude, 0, amplitude, 0, amplitude),
                    -1,
                )
            }
        }
    }

    companion object {
        private const val TAG = "HapticFeedback"
    }
}