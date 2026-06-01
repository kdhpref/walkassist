package com.example.walkassist

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

object GeminiTextTranslator {
    fun translateToEnglishOrNull(
        text: String,
        contextLabel: String,
        apiKey: String = BuildConfig.GEMINI_API_KEY,
    ): String? {
        val source = text.trim()
        if (source.isBlank() || apiKey.isBlank()) return null
        if (source.isMostlyEnglish()) return source

        return runCatching {
            requestEnglishTranslation(
                apiKey = apiKey,
                text = source,
                contextLabel = contextLabel,
            )
        }.onFailure { error ->
            Log.w(TAG, "English translation failed for $contextLabel", error)
        }.getOrNull()
    }

    private fun requestEnglishTranslation(
        apiKey: String,
        text: String,
        contextLabel: String,
    ): String? {
        val connection = (URL("$API_BASE/models/$MODEL:generateContent").openConnection() as HttpURLConnection)
        connection.requestMethod = "POST"
        connection.connectTimeout = 8_000
        connection.readTimeout = 12_000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.setRequestProperty("x-goog-api-key", apiKey)

        val prompt = """
Translate the following $contextLabel into concise spoken English for a blind pedestrian.
Return only the English sentence or phrase. Do not add labels, quotes, markdown, or explanations.

$text
""".trimIndent()

        val payload = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.1)
                    .put("topP", 0.8)
                    .put("maxOutputTokens", 180),
            )

        connection.outputStream.use { output ->
            output.write(payload.toString().toByteArray(Charsets.UTF_8))
        }

        val statusCode = connection.responseCode
        val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReaderUtf8().use { it.readText() }
        if (statusCode !in 200..299) {
            throw IllegalStateException("Gemini translation HTTP $statusCode: ${body.take(200)}")
        }

        return JSONObject(body)
            .optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.let { parts ->
                (0 until parts.length())
                    .asSequence()
                    .mapNotNull { index -> parts.optJSONObject(index)?.optString("text")?.trim() }
                    .firstOrNull { it.isNotBlank() }
            }
            ?.trim('"', '\'', ' ', '\n', '\r', '\t')
            ?.take(MAX_TRANSLATED_CHARS)
    }

    private fun String.isMostlyEnglish(): Boolean {
        val letters = count { it.isLetter() }
        if (letters == 0) return true
        val latinLetters = count { it in 'A'..'Z' || it in 'a'..'z' }
        return latinLetters.toFloat() / letters.toFloat() >= 0.75f
    }

    private fun java.io.InputStream.bufferedReaderUtf8(): BufferedReader {
        return BufferedReader(InputStreamReader(this, Charsets.UTF_8))
    }

    private const val TAG = "GeminiTextTranslator"
    private const val API_BASE = "https://generativelanguage.googleapis.com/v1beta"
    private const val MODEL = "gemini-2.5-flash-lite"
    private const val MAX_TRANSLATED_CHARS = 320
}
