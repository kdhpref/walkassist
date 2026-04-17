package com.example.walkassist.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import java.util.Locale

class FeedbackManager(
    context: Context,
) : TextToSpeech.OnInitListener {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val accessibilityManager =
        appContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    private val vibrator: Vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager =
            appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var isTtsReady = false
    private var audioFocusRequest: AudioFocusRequest? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS initialization failed")
            return
        }

        val languageResult = tts?.setLanguage(Locale.KOREAN)
        if (
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.e(TAG, "Korean TTS is not available")
            return
        }
        isTtsReady = true
    }

    fun provideFeedback(message: String, level: FeedbackAlertLevel, announcementView: TextView? = null) {
        if (message.isBlank()) {
            return
        }

        triggerHaptic(level)
        if (isTalkBackEnabled() && announcementView != null) {
            announcementView.text = message
            announcementView.contentDescription = message
            announcementView.sendAccessibilityEvent(AccessibilityEvent.TYPE_ANNOUNCEMENT)
            return
        }

        speak(message, level)
    }

    private fun speak(message: String, level: FeedbackAlertLevel) {
        if (!isTtsReady) {
            return
        }

        requestAudioFocus()
        val pitch = when (level) {
            FeedbackAlertLevel.SAFE -> 1.0f
            FeedbackAlertLevel.CAUTION -> 1.08f
            FeedbackAlertLevel.DANGER -> 1.16f
        }
        val rate = when (level) {
            FeedbackAlertLevel.SAFE -> 0.95f
            FeedbackAlertLevel.CAUTION -> 1.02f
            FeedbackAlertLevel.DANGER -> 1.12f
        }
        tts?.setPitch(pitch)
        tts?.setSpeechRate(rate)
        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE_ID)
    }

    private fun requestAudioFocus() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    .build()
                audioFocusRequest?.let { audioManager.requestAudioFocus(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    null,
                    AudioManager.STREAM_NOTIFICATION,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "Audio focus request failed", error)
        }
    }

    private fun triggerHaptic(level: FeedbackAlertLevel) {
        if (!vibrator.hasVibrator()) {
            return
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = when (level) {
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
                vibrator.vibrate(effect)
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

    private fun isTalkBackEnabled(): Boolean {
        return accessibilityManager.isEnabled && accessibilityManager.isTouchExplorationEnabled
    }

    fun release() {
        vibrator.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        isTtsReady = false
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(null)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Audio focus abandon failed", error)
        }
        audioFocusRequest = null
    }

    companion object {
        private const val TAG = "FeedbackManager"
        private const val UTTERANCE_ID = "walkassist_feedback"
    }
}
