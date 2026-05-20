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
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

class GeminiVlmSceneInterpreter(
    private val apiKey: String = BuildConfig.GEMINI_API_KEY,
    private val model: String = DEFAULT_MODEL,
) : VlmSceneInterpreter {
    private val liveSessionLock = Any()
    private var liveSessionSocket: WebSocket? = null
    private var liveSessionSetupComplete = false
    private var liveSessionTextCallback: ((String) -> Unit)? = null
    private var liveSessionErrorCallback: ((String) -> Unit)? = null
    private var liveSessionPromptSent = false
    private var liveSessionLastPromptAtMs = 0L
    private var liveSessionApiVersion = LIVE_API_VERSIONS.first()

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
            if (model == GEMINI_FLASH_LIVE_MODEL) {
                requestGeminiLivePreviewResponse(frame)
            } else {
                requestGeminiDescription(frame)
            }
        }.onFailure {
            Log.w(TAG, "Gemini VLM request failed", it)
        }.getOrNull()

        if (responseText.isNullOrBlank()) return null

        return VlmSceneInterpretation(
            modelName = when {
                model == GEMINI_FLASH_LIVE_MODEL -> "$model-live-demo"
                else -> "$model-image-description"
            },
            schemaVersion = 1,
            risk = VlmWalkingRisk.UNKNOWN,
            suggestedAction = VlmSuggestedAction.UNKNOWN,
            confidence = 0.5f,
            pathSummary = sanitizeGeminiUserText(
                text = responseText.twoOrThreeSentences(),
                fallback = "Gemini response is unavailable.",
            ),
            evidence = when {
                model == GEMINI_FLASH_LIVE_MODEL -> listOf("gemini_live_websocket")
                else -> emptyList()
            },
            shouldOverridePrimary = false,
        )
    }

    override fun startLiveSession(
        onText: (String) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (model != GEMINI_FLASH_LIVE_MODEL || apiKey.isBlank()) return false
        synchronized(liveSessionLock) {
            if (liveSessionSocket != null) return true
            liveSessionTextCallback = onText
            liveSessionErrorCallback = onError
            liveSessionSetupComplete = false
            liveSessionPromptSent = false
            liveSessionLastPromptAtMs = 0L
            liveSessionApiVersion = LIVE_API_VERSIONS.first()
            openLiveSessionLocked(liveSessionApiVersion)
        }
        return true
    }

    override fun streamLiveFrame(frame: SpatialFrame) {
        val socket: WebSocket
        val shouldSendFrame: Boolean
        synchronized(liveSessionLock) {
            socket = liveSessionSocket ?: return
            if (!liveSessionSetupComplete) return
            val now = System.currentTimeMillis()
            shouldSendFrame = !liveSessionPromptSent ||
                now - liveSessionLastPromptAtMs >= LIVE_PROMPT_INTERVAL_MS
            liveSessionPromptSent = true
            if (shouldSendFrame) {
                liveSessionLastPromptAtMs = now
            }
        }
        if (shouldSendFrame) {
            socket.send(
                liveClientContentPayload(
                    text = GEMINI_LIVE_WALKING_PROMPT,
                    base64Image = frame.bitmap.toBase64Jpeg(),
                ),
            )
        }
    }

    override fun stopLiveSession() {
        synchronized(liveSessionLock) {
            liveSessionSocket?.close(1000, "live_session_stopped")
            liveSessionSocket = null
            liveSessionSetupComplete = false
            liveSessionPromptSent = false
            liveSessionLastPromptAtMs = 0L
            liveSessionTextCallback = null
            liveSessionErrorCallback = null
        }
    }

    override fun isLiveSessionActive(): Boolean {
        return synchronized(liveSessionLock) {
            liveSessionSocket != null
        }
    }

    override fun close() {
        stopLiveSession()
    }

    private fun requestGeminiDescription(frame: SpatialFrame, modelName: String = model): String {
        val connection = (URL("$API_BASE/models/$modelName:generateContent").openConnection() as HttpURLConnection)
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
        val failures = mutableListOf<String>()
        for (apiVersion in LIVE_API_VERSIONS) {
            val result = runCatching {
                requestGeminiLivePreviewResponse(
                    frame = frame,
                    apiVersion = apiVersion,
                )
            }
            result.getOrNull()?.let { return it }
            val error = result.exceptionOrNull()
            failures += "$apiVersion: ${error?.message ?: error?.javaClass?.simpleName ?: "unknown"}"
            Log.w(TAG, "Gemini Live $apiVersion failed", error)
        }
        throw IllegalStateException("Gemini Live API failed for all versions: ${failures.joinToString("; ")}")
    }

    private fun requestGeminiLivePreviewResponse(frame: SpatialFrame, apiVersion: String): String {
        val setupDone = CountDownLatch(1)
        val done = CountDownLatch(1)
        val textChunks = mutableListOf<String>()
        val base64Image = frame.bitmap.toBase64Jpeg()
        var audioBytesReceived = 0
        var setupComplete = false
        var failure: Throwable? = null
        var socket: WebSocket? = null

        val request = Request.Builder()
            .url("${liveApiBase(apiVersion)}?key=$apiKey")
            .header("x-goog-api-key", apiKey)
            .build()

        socket = liveHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Gemini Live $apiVersion websocket opened http=${response.code}")
                    val sent = webSocket.send(liveConfigPayload())
                    Log.d(TAG, "Gemini Live $apiVersion setup payload sent=$sent")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleMessage(webSocket, bytes.utf8())
                }

                private fun handleMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Gemini Live $apiVersion received=${text.take(500)}")
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (message.has("setupComplete")) {
                        setupComplete = true
                        setupDone.countDown()
                        webSocket.send(
                            liveClientContentPayload(
                                text = GEMINI_LIVE_WALKING_PROMPT,
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
                                if (part.optBoolean("thought", false)) continue
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
                    Log.w(TAG, "Gemini Live $apiVersion websocket failure", t)
                    failure = response?.let {
                        IllegalStateException("Gemini Live HTTP ${it.code} ${it.message}", t)
                    } ?: t
                    setupDone.countDown()
                    done.countDown()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "Gemini Live $apiVersion websocket closing code=$code reason=$reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "Gemini Live $apiVersion websocket closed code=$code reason=$reason")
                    if (code != 1000 && textChunks.isEmpty() && audioBytesReceived == 0) {
                        failure = IllegalStateException(
                            "Gemini Live websocket closed before response: code=$code reason=$reason",
                        )
                    }
                    setupDone.countDown()
                    done.countDown()
                }
            },
        )

        val setupCompleted = setupDone.await(LIVE_SETUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        failure?.let { throw it }
        if (!setupCompleted || !setupComplete) {
            socket?.cancel()
            throw IllegalStateException("Gemini Live $apiVersion setup timed out")
        }

        val completed = done.await(LIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!completed) {
            socket?.cancel()
            throw IllegalStateException("Gemini Live $apiVersion timed out after setup")
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

    private fun openLiveSessionLocked(apiVersion: String) {
        val request = Request.Builder()
            .url("${liveApiBase(apiVersion)}?key=$apiKey")
            .header("x-goog-api-key", apiKey)
            .build()
        liveSessionSocket = liveHttpClient.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.d(TAG, "Gemini Live session $apiVersion websocket opened http=${response.code}")
                    val sent = webSocket.send(liveConfigPayload())
                    Log.d(TAG, "Gemini Live session $apiVersion setup payload sent=$sent")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleMessage(webSocket, text)
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    handleMessage(webSocket, bytes.utf8())
                }

                private fun handleMessage(webSocket: WebSocket, text: String) {
                    Log.d(TAG, "Gemini Live session $apiVersion received=${text.take(500)}")
                    val message = runCatching { JSONObject(text) }.getOrNull() ?: return
                    if (message.has("setupComplete")) {
                        synchronized(liveSessionLock) {
                            if (liveSessionSocket == webSocket) {
                                liveSessionSetupComplete = true
                            }
                        }
                        liveSessionTextCallback?.invoke("Gemini Live session started.")
                        return
                    }
                    collectLiveText(message)?.let { liveSessionTextCallback?.invoke(it) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.w(TAG, "Gemini Live session $apiVersion websocket failure", t)
                    val shouldRetry = synchronized(liveSessionLock) {
                        liveSessionSocket == webSocket &&
                            !liveSessionSetupComplete &&
                            apiVersion == LIVE_API_VERSIONS.first()
                    }
                    if (shouldRetry) {
                        synchronized(liveSessionLock) {
                            if (liveSessionSocket == webSocket) {
                                liveSessionSocket = null
                                liveSessionApiVersion = LIVE_API_VERSIONS.last()
                                openLiveSessionLocked(liveSessionApiVersion)
                            }
                        }
                    } else {
                        synchronized(liveSessionLock) {
                            if (liveSessionSocket == webSocket) {
                                liveSessionSocket = null
                                liveSessionSetupComplete = false
                            }
                        }
                        liveSessionErrorCallback?.invoke(t.message ?: "Gemini Live session failed.")
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "Gemini Live session $apiVersion websocket closing code=$code reason=$reason")
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "Gemini Live session $apiVersion websocket closed code=$code reason=$reason")
                    synchronized(liveSessionLock) {
                        if (liveSessionSocket == webSocket) {
                            liveSessionSocket = null
                            liveSessionSetupComplete = false
                            liveSessionPromptSent = false
                            liveSessionLastPromptAtMs = 0L
                        }
                    }
                }
            },
        )
        liveSessionSocket?.let { socket ->
            startLiveSetupWatchdog(apiVersion = apiVersion, webSocket = socket)
        }
    }

    private fun startLiveSetupWatchdog(apiVersion: String, webSocket: WebSocket) {
        Thread {
            Thread.sleep(TimeUnit.SECONDS.toMillis(LIVE_SETUP_TIMEOUT_SECONDS))
            val shouldHandleTimeout = synchronized(liveSessionLock) {
                liveSessionSocket == webSocket && !liveSessionSetupComplete
            }
            if (!shouldHandleTimeout) return@Thread
            Log.w(TAG, "Gemini Live session $apiVersion setup timed out")
            synchronized(liveSessionLock) {
                if (liveSessionSocket != webSocket || liveSessionSetupComplete) return@synchronized
                liveSessionSocket = null
                webSocket.cancel()
                if (apiVersion == LIVE_API_VERSIONS.first()) {
                    liveSessionApiVersion = LIVE_API_VERSIONS.last()
                    openLiveSessionLocked(liveSessionApiVersion)
                } else {
                    liveSessionErrorCallback?.invoke("Gemini Live setup timed out.")
                }
            }
        }.apply {
            name = "GeminiLiveSetupWatchdog"
            isDaemon = true
            start()
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
                        JSONObject()
                            .put("responseModalities", JSONArray().put(LIVE_RESPONSE_MODALITY))
                            .put("temperature", 0.2)
                            .put("maxOutputTokens", 256),
                    )
                    .put(
                        "systemInstruction",
                        JSONObject()
                            .put(
                                "parts",
                                JSONArray().put(JSONObject().put("text", GEMINI_LIVE_SYSTEM_PROMPT)),
                            ),
                    )
                    .put("outputAudioTranscription", JSONObject()),
            )
            .toString()
    }

    private fun liveApiBase(apiVersion: String): String {
        return "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.$apiVersion.GenerativeService.BidiGenerateContent"
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

    private fun liveRealtimeVideoPayload(base64Image: String): String {
        return JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put(
                    "video",
                    JSONObject()
                        .put("mimeType", "image/jpeg")
                        .put("data", base64Image),
                ),
            )
            .toString()
    }

    private fun liveRealtimeTextPayload(text: String): String {
        return JSONObject()
            .put(
                "realtimeInput",
                JSONObject().put("text", text),
            )
            .toString()
    }

    private fun collectLiveText(message: JSONObject): String? {
        val chunks = mutableListOf<String>()
        val serverContent = message.optJSONObject("serverContent") ?: return null
        serverContent.optJSONObject("modelTurn")
            ?.optJSONArray("parts")
            ?.let { parts ->
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    if (part.optBoolean("thought", false)) continue
                    part.optString("text")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(chunks::add)
                }
            }
        serverContent.optJSONObject("outputTranscription")
            ?.optString("text")
            ?.takeIf { it.isNotBlank() }
            ?.let(chunks::add)
        return chunks.joinToString(" ").trim().ifBlank { null }
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
        const val GEMINI_FLASH_LIVE_MODEL = "gemini-2.5-flash-native-audio-latest"
        private const val DEFAULT_MODEL = "gemini-2.5-flash-lite"
        private const val MAX_IMAGE_SIDE = 640
        private const val LIVE_SETUP_TIMEOUT_SECONDS = 8L
        private const val LIVE_TIMEOUT_SECONDS = 25L
        private const val LIVE_PROMPT_INTERVAL_MS = 5_000L
        private const val LIVE_RESPONSE_MODALITY = "AUDIO"
        private val liveHttpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .pingInterval(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        private val LIVE_API_VERSIONS = listOf("v1alpha", "v1beta")
        private const val GEMINI_LIVE_SYSTEM_PROMPT =
            "You are a walking-assistance helper for a blind or low-vision pedestrian. " +
                "Always produce a final spoken Korean answer. " +
                "Do not stop after internal thinking. " +
                "Use only concrete navigation or safety information from the camera image."
        private const val GEMINI_LIVE_WALKING_PROMPT =
            "이 카메라 이미지를 보고, 사용자가 바로 들을 수 있는 최종 보행 안내만 한국어 한 문장으로 반드시 말해줘."
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
