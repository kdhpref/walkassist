package com.example.walkassist.ocr

import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions

class OneShotOcrReader {
    private val recognizer: TextRecognizer =
        TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    fun read(
        bitmap: Bitmap,
        onResult: (String) -> Unit,
    ) {
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = normalizeText(visionText.text)
                onResult(text.ifBlank { NO_TEXT_MESSAGE })
            }
            .addOnFailureListener { error ->
                Log.e(TAG, "One-shot OCR failed", error)
                onResult(OCR_ERROR_MESSAGE)
            }
            .addOnCompleteListener {
                bitmap.recycle()
            }
    }

    fun close() {
        recognizer.close()
    }

    private fun normalizeText(text: String): String {
        return text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString(separator = ". ")
            .take(MAX_SPOKEN_CHARS)
    }

    companion object {
        private const val TAG = "OneShotOcrReader"
        private const val MAX_SPOKEN_CHARS = 280
        private const val NO_TEXT_MESSAGE = "읽을 수 있는 문자가 없습니다."
        private const val OCR_ERROR_MESSAGE = "문자 인식에 실패했습니다."
    }
}
