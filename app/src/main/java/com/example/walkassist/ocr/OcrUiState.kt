package com.example.walkassist.ocr

data class OcrUiState(
    val recognizedText: String = "",
    val lastSpokenText: String = "",
    val lastSpokenAtMillis: Long = 0L,
)
