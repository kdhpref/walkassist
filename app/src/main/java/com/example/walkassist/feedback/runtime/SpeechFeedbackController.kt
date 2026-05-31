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
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = TextToSpeech(appContext, this)
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isReady = false

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
        } // 작업자 C(여준호): 기본 시작 언어는 기존 한국어 모드와 호환되도록 한국어로 유지합니다.

        isReady = true

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
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
        })
    }

    fun speak(message: String, level: FeedbackAlertLevel) {
        if (!isReady || message.isBlank()) return

        requestAudioFocus()
        applyLanguageForMessage(message) // 작업자 C(여준호): 영어 문장은 영어 TTS 억양으로, 한국어 문장은 한국어 TTS 억양으로 읽도록 발화 직전에 언어를 선택합니다.
        tts?.setPitch(pitchFor(level))
        tts?.setSpeechRate(rateFor(level))

        val queueMode = if (level == FeedbackAlertLevel.DANGER) {
            TextToSpeech.QUEUE_FLUSH
        } else {
            TextToSpeech.QUEUE_ADD
        }

        val params = Bundle().apply {
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_NOTIFICATION)
        }

        tts?.speak(message, queueMode, params, UTTERANCE_ID)
    }

    private fun applyLanguageForMessage(message: String) {
        val targetLocale = if (containsKorean(message)) Locale.KOREAN else Locale.US
        val languageResult = tts?.setLanguage(targetLocale)
        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts?.setLanguage(Locale.KOREAN)
        }
    } // 작업자 C(여준호): 영어 모드의 숫자와 단위가 한국어 발음이 아니라 영어 발음으로 나가도록 TTS Locale을 바꿉니다.

    private fun containsKorean(message: String): Boolean {
        return message.any { it in '\uAC00'..'\uD7A3' }
    } // 작업자 C(여준호): 안내 문장에 한글이 남아 있으면 한국어 TTS로 읽도록 간단히 판별합니다.

    fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
        abandonAudioFocus()
    }

    // 코드 추가
    fun stop() {
        tts?.stop()
        abandonAudioFocus()
    }

    /**
     * minSdk가 26 이상이면 AudioFocusRequest를 바로 사용할 수 있으므로
     * Build.VERSION_CODES.O 분기는 제거했습니다.
     */
    private fun requestAudioFocus() {
        try {
            audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
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
            FeedbackAlertLevel.CAUTION -> 1.2f
            FeedbackAlertLevel.DANGER -> 1.5f
        }
    }

    private fun rateFor(level: FeedbackAlertLevel): Float {
        return when (level) {
            FeedbackAlertLevel.SAFE -> 0.9f
            FeedbackAlertLevel.CAUTION -> 1.1f
            FeedbackAlertLevel.DANGER -> 1.3f
        }
    }

    companion object {
        private const val TAG = "SpeechFeedback"
        private const val UTTERANCE_ID = "walkassist_feedback"
    }
}
