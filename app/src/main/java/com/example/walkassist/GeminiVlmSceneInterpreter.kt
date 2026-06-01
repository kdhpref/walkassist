package com.example.walkassist

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

class GeminiVlmSceneInterpreter(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val model: String = DEFAULT_MODEL,
) : VlmSceneInterpreter {
    fun prepareForUse(): VlmModelPreparationStatus {
        val configured = apiKey.isNotBlank()
        return VlmModelPreparationStatus(
            modelName = model,
            statusLabel = if (configured) "api_key_configured" else "missing_api_key",
            downloadState = "remote_api",
            isAvailable = configured,
            isFallbackLikely = !configured,
            explanation = if (configured) {
                "Gemini API key is configured. The API is called when the guide area is tapped."
            } else {
                "Add GEMINI_API_KEY to local.properties to enable Gemini image description."
            },
        )
    }

    override fun interpret(
        frame: SpatialFrame,
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
        outputLanguageCode: String,
    ): VlmSceneInterpretation? {
        if (frame.requestMode != VlmRequestMode.MANUAL || apiKey.isBlank()) {
            return null
        }

        val responseText = runCatching {
            requestGeminiDescription(frame, outputLanguageCode)
        }.onFailure {
            Log.w(TAG, "Gemini VLM request failed", it)
        }.getOrNull()

        if (responseText.isNullOrBlank()) return null

        return VlmSceneInterpretation(
            modelName = "$model-image-description",
            schemaVersion = 1,
            risk = VlmWalkingRisk.UNKNOWN,
            suggestedAction = VlmSuggestedAction.UNKNOWN,
            confidence = 0.5f,
            pathSummary = sanitizeGeminiUserText(
                text = responseText.twoOrThreeSentences(),
                fallback = "Gemini response is unavailable.",
            ),
            evidence = emptyList(),
            shouldOverridePrimary = false,
        )
    }

    private fun requestGeminiDescription(
        frame: SpatialFrame,
        outputLanguageCode: String,
    ): String {
        val connection = (URL("$API_BASE/models/$model:generateContent").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 20_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("x-goog-api-key", apiKey)

        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray()
                                .put(
                                    JSONObject().put(
                                        "inline_data",
                                        JSONObject()
                                            .put("mime_type", "image/jpeg")
                                            .put("data", frame.bitmap.toBase64Jpeg()),
                                    ),
                                )
                                .put(JSONObject().put("text", geminiImageDescriptionPrompt(outputLanguageCode))),
                        ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.1)
                    .put("topP", 0.8)
                    .put("maxOutputTokens", 160),
            )

        connection.outputStream.use { output ->
            output.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReaderUtf8().use { it.readText() }
        if (statusCode !in 200..299) {
            throw IllegalStateException("Gemini API HTTP $statusCode: ${body.take(240)}")
        }

        return JSONObject(body)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.let { parts ->
                (0 until parts.length())
                    .asSequence()
                    .mapNotNull { index -> parts.optJSONObject(index)?.optString("text") }
                    .firstOrNull { it.isNotBlank() }
            }
            ?: throw IllegalStateException("Gemini response has no text part")
    }

    private fun sanitizeGeminiUserText(text: String, fallback: String): String {
        val cleaned = VlmWalkingAnnouncementFormatter.sanitizeForWalkingTts(
            text = text,
            fallback = fallback,
        )
        return if (cleaned.containsPrivateHintTerm()) fallback else cleaned
    }

    private fun String.twoOrThreeSentences(): String {
        val normalized = trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return normalized
        val sentences = Regex("""[^.!?。！？]+[.!?。！？]?""")
            .findAll(normalized)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .take(3)
            .toList()
        return sentences.joinToString(" ").ifBlank { normalized }
    }

    private fun String.containsPrivateHintTerm(): Boolean {
        val lower = lowercase()
        return PRIVATE_HINT_TERMS.any { lower.contains(it) }
    }

    private fun Bitmap.toBase64Jpeg(): String {
        val resized = scaleForGemini(MAX_IMAGE_SIDE)
        return try {
            ByteArrayOutputStream().use { output ->
                resized.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            if (resized !== this && !resized.isRecycled) {
                resized.recycle()
            }
        }
    }

    private fun Bitmap.scaleForGemini(maxSideLimit: Int): Bitmap {
        val maxSide = maxOf(width, height)
        if (maxSide <= maxSideLimit) return this
        val scale = maxSideLimit.toFloat() / maxSide.toFloat()
        return Bitmap.createScaledBitmap(
            this,
            (width * scale).toInt().coerceAtLeast(1),
            (height * scale).toInt().coerceAtLeast(1),
            true,
        )
    }

    private fun java.io.InputStream.bufferedReaderUtf8(): BufferedReader {
        return BufferedReader(InputStreamReader(this, Charsets.UTF_8))
    }

    companion object {
        private const val TAG = "GeminiVlm"
        private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
        private const val DEFAULT_MODEL = "gemini-2.5-flash-lite"
        private const val MAX_IMAGE_SIDE = 640
        private const val JPEG_QUALITY = 72
        private fun geminiImageDescriptionPrompt(outputLanguageCode: String): String {
            return if (outputLanguageCode == "en") {
                GEMINI_IMAGE_DESCRIPTION_PROMPT_EN
            } else {
                GEMINI_IMAGE_DESCRIPTION_PROMPT_KO
            }
        }

        private const val GEMINI_IMAGE_DESCRIPTION_PROMPT_KO = """
Describe only what is visible in the image.
If readable signs or text are visible, summarize them naturally.
Answer in Korean in no more than two short sentences.
"""
        private const val GEMINI_IMAGE_DESCRIPTION_PROMPT_EN = """
Describe only what is visible in the image for a blind or low-vision pedestrian.
If readable signs or text are visible, summarize them naturally in English.
Answer in English in no more than two short sentences.
"""
        private val PRIVATE_HINT_TERMS = listOf(
            "yolo",
            "arcore",
            "floorconfidence",
            "confidence score",
            "bounding box",
            "collisiondistancemeters",
            "pathclearmeters",
            "centerobstaclemeters",
            "sensor",
            "detector",
        )
    }
}
