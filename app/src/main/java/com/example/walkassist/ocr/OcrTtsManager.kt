package com.example.walkassist.ocr

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

class OcrTtsManager(
    context: Context,
    private val onReady: () -> Unit = {},
) : TextToSpeech.OnInitListener {
    private var tts: TextToSpeech? = TextToSpeech(context.applicationContext, this)
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
            Log.e(TAG, "Korean TTS is not supported on this device")
            return
        }

        isReady = true
        onReady()
    }

    fun speak(text: String) {
        if (!isReady || text.isBlank()) {
            return
        }
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, OCR_UTTERANCE_ID)
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isReady = false
    }

    companion object {
        private const val TAG = "OcrTtsManager"
        private const val OCR_UTTERANCE_ID = "walkassist_ocr"
    }
}
