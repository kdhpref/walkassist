package com.example.walkassist.feedback.runtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.example.walkassist.feedback.core.FeedbackAlertLevel
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class SpeechFeedbackController(context: Context) : TextToSpeech.OnInitListener {
    private data class PendingSpeech(
        val message: String,
        val level: FeedbackAlertLevel,
        val queueMode: Int,
        val priority: Int,
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isReady = false
    private var speechLocale: Locale = Locale.KOREAN
    private var pendingSpeech: PendingSpeech? = null
    private val utteranceCounter = AtomicLong(0L)
    @Volatile
    private var activeSpeechMessage: String? = null
    @Volatile
    private var activeUtteranceId: String? = null
    @Volatile
    private var activeSpeechPriority: Int? = null

    override fun onInit(status: Int) {
        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS initialization failed")
            return
        }

        if (!applySpeechLanguage(speechLocale)) {
            return
        }

        isReady = true
        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    clearActiveSpeech(utteranceId)
                    abandonAudioFocus()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    clearActiveSpeech(utteranceId)
                    abandonAudioFocus()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    clearActiveSpeech(utteranceId)
                    abandonAudioFocus()
                }

                override fun onStop(utteranceId: String?, interrupted: Boolean) {
                    clearActiveSpeech(utteranceId)
                    abandonAudioFocus()
                }
            },
        )

        pendingSpeech?.let { pending ->
            pendingSpeech = null
            speak(
                message = pending.message,
                level = pending.level,
                queueMode = pending.queueMode,
                priority = pending.priority,
            )
        }
    }

    fun setLanguage(locale: Locale) {
        speechLocale = locale
        if (isReady) {
            applySpeechLanguage(locale)
        }
    }

    fun speak(
        message: String,
        level: FeedbackAlertLevel,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
        priority: Int = defaultPriority(level),
    ) {
        if (message.isBlank()) return
        if (!isReady) {
            pendingSpeech = PendingSpeech(message, level, queueMode, priority)
            Log.d(TAG, "TTS not ready; queued pending speech")
            return
        }

        requestAudioFocus()
        applySpeechLanguage(speechLocale)
        tts?.setPitch(pitchFor(level))
        tts?.setSpeechRate(rateFor(level))

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION)
        }

        val utteranceId = "$UTTERANCE_ID_PREFIX-${utteranceCounter.incrementAndGet()}"
        activeSpeechMessage = message
        activeUtteranceId = utteranceId
        activeSpeechPriority = priority
        val result = tts?.speak(message, queueMode, params, utteranceId)
        if (result != TextToSpeech.SUCCESS) {
            activeSpeechMessage = null
            activeUtteranceId = null
            activeSpeechPriority = null
            Log.w(TAG, "TTS speak failed result=$result")
        }
    }

    fun isSpeakingOrPending(message: String): Boolean {
        return activeSpeechMessage == message || pendingSpeech?.message == message
    }

    fun isActiveSpeech(message: String): Boolean {
        return activeSpeechMessage == message
    }

    fun hasActiveOrPendingSpeech(): Boolean {
        return activeSpeechMessage != null || pendingSpeech != null
    }

    fun currentPriority(): Int? {
        return activeSpeechPriority
    }

    fun release() {
        pendingSpeech = null
        activeSpeechMessage = null
        activeUtteranceId = null
        activeSpeechPriority = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        abandonAudioFocus()
    }

    fun stopSpeaking() {
        pendingSpeech = null
        activeSpeechMessage = null
        activeUtteranceId = null
        activeSpeechPriority = null
        tts?.stop()
        abandonAudioFocus()
    }

    private fun clearActiveSpeech(utteranceId: String?) {
        if (utteranceId == activeUtteranceId) {
            activeSpeechMessage = null
            activeUtteranceId = null
            activeSpeechPriority = null
        }
    }

    private fun requestAudioFocus() {
        try {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .build()

            audioFocusRequest?.let { request ->
                audioManager.requestAudioFocus(request)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Audio focus request failed", error)
        }
    }

    private fun abandonAudioFocus() {
        try {
            audioFocusRequest?.let { request ->
                audioManager.abandonAudioFocusRequest(request)
            }
        } catch (error: Exception) {
            Log.e(TAG, "Audio focus abandon failed", error)
        }
        audioFocusRequest = null
    }

    private fun applySpeechLanguage(locale: Locale): Boolean {
        val languageResult = tts?.setLanguage(locale)
        val isUnavailable =
            languageResult == TextToSpeech.LANG_MISSING_DATA ||
                languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        if (isUnavailable) {
            Log.e(TAG, "TTS language is not available: $locale")
            return false
        }
        return true
    }

    private fun pitchFor(level: FeedbackAlertLevel): Float {
        return when (level) {
            FeedbackAlertLevel.SAFE -> 1.0f
            FeedbackAlertLevel.CAUTION -> 1.08f
            FeedbackAlertLevel.DANGER -> 1.16f
        }
    }

    private fun rateFor(level: FeedbackAlertLevel): Float {
        return when (level) {
            FeedbackAlertLevel.SAFE -> 0.95f
            FeedbackAlertLevel.CAUTION -> 1.12f
            FeedbackAlertLevel.DANGER -> 1.18f
        }
    }

    private fun defaultPriority(level: FeedbackAlertLevel): Int {
        return when (level) {
            FeedbackAlertLevel.DANGER -> 1
            FeedbackAlertLevel.CAUTION -> 3
            FeedbackAlertLevel.SAFE -> 5
        }
    }

    companion object {
        private const val TAG = "SpeechFeedback"
        private const val UTTERANCE_ID_PREFIX = "walkassist_feedback"
    }
}
