package com.example.walkassist.ocr

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class OcrViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(OcrUiState())
    val uiState: StateFlow<OcrUiState> = _uiState.asStateFlow()

    fun onRecognizedText(text: String, nowMillis: Long = System.currentTimeMillis()): String? {
        val normalizedText = text.trim()
        if (normalizedText.isBlank()) {
            return null
        }

        val currentState = _uiState.value
        _uiState.update { it.copy(recognizedText = normalizedText) }

        val shouldSpeak = normalizedText != currentState.lastSpokenText ||
            nowMillis - currentState.lastSpokenAtMillis > SPEECH_THROTTLE_MILLIS

        if (!shouldSpeak) {
            return null
        }

        _uiState.update {
            it.copy(
                lastSpokenText = normalizedText,
                lastSpokenAtMillis = nowMillis,
            )
        }
        return normalizedText
    }

    companion object {
        private const val SPEECH_THROTTLE_MILLIS = 3_000L
    }
}
