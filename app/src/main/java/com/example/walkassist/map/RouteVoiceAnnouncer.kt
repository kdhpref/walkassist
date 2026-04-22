package com.example.walkassist.map

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class RouteVoiceAnnouncer(
    context: Context,
) : TextToSpeech.OnInitListener {
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext, this)

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts.language = Locale.KOREAN
        }
    }

    fun speak(message: String) {
        if (!ready) return
        tts.speak(message, TextToSpeech.QUEUE_FLUSH, null, "walkassist-map")
    }

    fun shutdown() {
        tts.stop()
        tts.shutdown()
    }
}
