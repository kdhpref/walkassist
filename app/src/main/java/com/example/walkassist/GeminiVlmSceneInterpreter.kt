package com.example.walkassist

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
            if (model == GEMINI_3_1_FLASH_LIVE_MODEL) {
                requestGeminiLivePreviewResponse(frame)
            } else {
                requestGeminiDescription(frame)
            }
        }.onFailure {
            Log.w(TAG, "Gemini VLM request failed", it)
        }.getOrNull()

        if (responseText.isNullOrBlank()) return null

        return VlmSceneInterpretation(
            modelName = if (model == GEMINI_3_1_FLASH_LIVE_MODEL) "$model-live-demo" else "$model-image-description",
            schemaVersion = 1,
            risk = VlmWalkingRisk.UNKNOWN,
            suggestedAction = VlmSuggestedAction.UNKNOWN,
            confidence = 0.5f,
            pathSummary = sanitizeGeminiUserText(
                text = responseText.twoOrThreeSentences(),
                fallback = "Gemini response is unavailable.",
            ),
            evidence = if (model == GEMINI_3_1_FLASH_LIVE_MODEL) {
                listOf("gemini_live_websocket")
            } else {
                emptyList()
            },
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

    private fun requestGeminiLivePreviewResponse(frame: SpatialFrame): String {
        val done = CountDownLatch(1)
        val textChunks = mutableListOf<String>()
        val base64Image = frame.bitmap.toBase64Jpeg()
        var audioBytesReceived = 0
        var setupComplete = false
        var failure: Throwable? = null
        var socket: WebSocket? = null

        val request = Request.Builder()
            .url("$LIVE_API_BASE?key=$apiKey")
            .build()

        socket = liveHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Gemini Live demo websocket opened http=${response.code}")
                    webSocket.send(liveConfigPayload())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Gemini Live demo received=${text.take(500)}")
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (message.has("setupComplete")) {
                        setupComplete = true
                        webSocket.send(
                            liveClientContentPayload(
                                text = GEMINI_LIVE_DEMO_PROMPT,
                                base64Image = base64Image,
                            ),
                        )
                        return
                    }

                    val serverContent = message.optJSONObject("serverContent") ?: return
                    serverContent.optJSONObject("modelTurn")
                        ?.optJSONArray("parts")
                        ?.let { parts ->
                            for (index in 0 until parts.length()) {
                                val part = parts.optJSONObject(index) ?: continue
                                part.optString("text")
                                    .takeIf { it.isNotBlank() }
                                    ?.let(textChunks::add)
                                part.optJSONObject("inlineData")
                                    ?.optString("data")
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { audioBytesReceived += Base64.decode(it, Base64.DEFAULT).size }
                            }
                        }
                    serverContent.optJSONObject("outputTranscription")
                        ?.optString("text")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(textChunks::add)

                    if (serverContent.optBoolean("turnComplete", false)) {
                        done.countDown()
                        webSocket.close(1000, "demo_done")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    failure = response?.let {
                        IllegalStateException("Gemini Live HTTP ${it.code} ${it.message}", t)
                    } ?: t
                    done.countDown()
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (code != 1000 && textChunks.isEmpty() && audioBytesReceived == 0) {
                        failure = IllegalStateException(
                            "Gemini Live websocket closed before response: code=$code reason=$reason",
                        )
                    }
                    done.countDown()
                }
            },
        )

        val completed = done.await(LIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            socket?.cancel()
            throw IllegalStateException(
                if (setupComplete) {
                    "Gemini Live API timed out after setup"
                } else {
                    "Gemini Live API setup timed out"
                },
            )
        }
        failure?.let { throw it }

        return textChunks.joinToString(" ")
            .trim()
            .ifBlank {
                if (audioBytesReceived > 0) {
                    "Gemini Live API connected and returned ${audioBytesReceived} bytes of audio."
                } else {
                    throw IllegalStateException("Gemini Live response has no text or audio part")
                }
            }
    }

    private fun liveConfigPayload(): String {
        return JSONObject()
            .put(
                "setup",
                JSONObject()
                    .put("model", "models/$model")
                    .put(
                        "generationConfig",
                        JSONObject().put("responseModalities", JSONArray().put(LIVE_RESPONSE_MODALITY)),
                    )
                    .put("outputAudioTranscription", JSONObject())
                    .put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put(
                                    "text",
                                    "You are a walking-assistance helper. " +
                                        "Use the camera scene to help the user walk safely. " +
                                        "Answer briefly in Korean with only useful navigation or safety information.",
                                ),
                            ),
                        ),
                    ),
            )
            .toString()
    }

    private fun liveClientContentPayload(text: String, base64Image: String): String {
        return JSONObject()
            .put(
                "clientContent",
                JSONObject()
                    .put(
                        "turns",
                        JSONArray().put(
                            JSONObject()
                                .put("role", "user")
                                .put(
                                    "parts",
                                    JSONArray()
                                        .put(
                                            JSONObject().put(
                                                "inlineData",
                                                JSONObject()
                                                    .put("mimeType", "image/jpeg")
                                                    .put("data", base64Image),
                                            ),
                                        )
                                        .put(JSONObject().put("text", text)),
                                ),
                        ),
                    )
                    .put("turnComplete", true),
            )
            .toString()
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
        private const val LIVE_API_BASE =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        const val GEMINI_3_1_FLASH_LIVE_MODEL = "gemini-3.1-flash-live-preview"
        private const val DEFAULT_MODEL = "gemini-2.5-flash-lite"
        private const val MAX_IMAGE_SIDE = 640
        private const val LIVE_TIMEOUT_SECONDS = 25L
        private const val LIVE_RESPONSE_MODALITY = "AUDIO"
        private val liveHttpClient = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        private const val GEMINI_LIVE_DEMO_PROMPT =
            "현재 카메라 장면에서 보행자가 바로 주의해야 할 점을 한국어 한 문장으로 말해줘."
        private const val GEMINI_IMAGE_DESCRIPTION_PROMPT = """
Describe only what is visible in the image.
If readable signs or text are visible, summarize them naturally.
Answer in Korean in no more than two short sentences.
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
