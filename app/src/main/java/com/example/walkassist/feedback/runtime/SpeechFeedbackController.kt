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

class SpeechFeedbackController(context: Context) : TextToSpeech.OnInitListener {
    private data class PendingSpeech(
        val message: String,
        val level: FeedbackAlertLevel,
        val queueMode: Int,
    )

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isReady = false
    private var pendingSpeech: PendingSpeech? = null

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

        isReady = true
        tts?.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit

                override fun onDone(utteranceId: String?) {
                    abandonAudioFocus()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    abandonAudioFocus()
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
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
            )
        }
    }

    fun speak(
        message: String,
        level: FeedbackAlertLevel,
        queueMode: Int = TextToSpeech.QUEUE_FLUSH,
    ) {
        if (message.isBlank()) return
        if (!isReady) {
            pendingSpeech = PendingSpeech(message, level, queueMode)
            Log.d(TAG, "TTS not ready; queued pending speech")
            return
        }

        requestAudioFocus()
        tts?.setPitch(pitchFor(level))
        tts?.setSpeechRate(rateFor(level))

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION)
        }

        val result = tts?.speak(message, queueMode, params, UTTERANCE_ID)
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS speak failed result=$result")
        }
    }

    fun release() {
        pendingSpeech = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        abandonAudioFocus()
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
            FeedbackAlertLevel.CAUTION -> 1.02f
            FeedbackAlertLevel.DANGER -> 1.12f
        }
    }

    companion object {
        private const val TAG = "SpeechFeedback"
        private const val UTTERANCE_ID = "walkassist_feedback"
    }
}
