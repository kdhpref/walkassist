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
                "Gemini API key is configured. The API is called only when the VLM button is pressed."
            } else {
                "Add GEMINI_API_KEY to local.properties to enable Gemini image description."
            },
        )
    }

    override fun interpret(
        frame: SpatialFrame,
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
    ): VlmSceneInterpretation? {
        if (frame.requestMode != VlmRequestMode.MANUAL || apiKey.isBlank()) {
            return null
        }

        val responseText = runCatching {
            requestGeminiDescription(frame)
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
                fallback = "The image description is unavailable.",
            ),
            evidence = emptyList(),
            shouldOverridePrimary = false,
        )
    }

    private fun requestGeminiDescription(frame: SpatialFrame): String {
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
                            JSONArray().put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject()
                                        .put("mime_type", "image/jpeg")
                                        .put("data", frame.bitmap.toBase64Jpeg()),
                                ),
                            ).put(JSONObject().put("text", GEMINI_IMAGE_DESCRIPTION_PROMPT)),
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
        val sentences = Regex("[^.!?。！？]+[.!?。！？]?")
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
        val resized = scaleForGemini()
        return try {
            ByteArrayOutputStream().use { output ->
                resized.compress(Bitmap.CompressFormat.JPEG, 72, output)
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
            }
        } finally {
            if (resized !== this && !resized.isRecycled) {
                resized.recycle()
            }
        }
    }

    private fun Bitmap.scaleForGemini(): Bitmap {
        val maxSide = maxOf(width, height)
        if (maxSide <= MAX_IMAGE_SIDE) return this
        val scale = MAX_IMAGE_SIDE.toFloat() / maxSide.toFloat()
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
        private const val GEMINI_IMAGE_DESCRIPTION_PROMPT = """
화면에 비친 이미지 자체만 보고 장면을 한국어로 짧게 묘사해 주세요.
실제로 보이는 표지판이나 읽을 수 있는 글자들이 있다면 자연스럽게 요약해 주세요.
답변은 길어도 두 문장 또는 세 문장으로만 작성해 주세요.
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
