package com.example.walkassist

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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
    context: Context? = null,
) : VlmSceneInterpreter {
    private val liveSessionLock = Any()
    private var liveSessionSocket: WebSocket? = null
    private var liveSessionSetupComplete = false
    private var liveSessionTextCallback: ((String) -> Unit)? = null
    private var liveSessionErrorCallback: ((String) -> Unit)? = null
    private var liveSessionTimingCallback: ((LiveVlmTimingEvent) -> Unit)? = null
    private var liveSessionLastPromptAtMs = 0L
    private var liveGuidanceRequestInFlight = false
    private var liveRealtimeFramesSent = 0
    private val liveResponseTextBuffer = StringBuilder()
    private val liveAudioPlayer = GeminiLiveAudioPlayer(context?.applicationContext)
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
        onTiming: (LiveVlmTimingEvent) -> Unit,
    ): Boolean {
        if (model != GEMINI_FLASH_LIVE_MODEL || apiKey.isBlank()) return false
        synchronized(liveSessionLock) {
            if (liveSessionSocket != null) return true
            liveSessionTextCallback = onText
            liveSessionErrorCallback = onError
            liveSessionTimingCallback = onTiming
            liveSessionSetupComplete = false
            resetLiveStreamStateLocked()
            liveSessionApiVersion = LIVE_API_VERSIONS.first()
            openLiveSessionLocked(liveSessionApiVersion)
        }
        return true
    }

    override fun streamLiveFrame(frame: SpatialFrame) {
        val socket: WebSocket
        synchronized(liveSessionLock) {
            socket = liveSessionSocket ?: return
            if (!liveSessionSetupComplete) return
            val now = System.currentTimeMillis()
            val canSendFrame = liveSessionLastPromptAtMs == 0L ||
                now - liveSessionLastPromptAtMs >= LIVE_STREAM_FRAME_INTERVAL_MS
            if (!canSendFrame) return
            liveSessionLastPromptAtMs = now
        }
        val sent = socket.send(liveRealtimeVideoPayload(frame.bitmap.toBase64LiveJpeg()))
        if (sent) {
            synchronized(liveSessionLock) {
                if (liveSessionSocket == socket) {
                    liveRealtimeFramesSent += 1
                }
            }
        }
        Log.d(TAG, "Gemini Live sent realtime video sent=$sent")
    }

    override fun requestLiveGuidance(): Boolean {
        val socket: WebSocket
        synchronized(liveSessionLock) {
            socket = liveSessionSocket ?: return false
            if (!liveSessionSetupComplete || liveGuidanceRequestInFlight) {
                return false
            }
            liveGuidanceRequestInFlight = true
            liveResponseTextBuffer.clear()
            liveAudioPlayer.startRequest()
        }
        val sent = socket.send(liveClientTextTurnPayload(GEMINI_LIVE_GUIDANCE_PROMPT))
        if (!sent) {
            synchronized(liveSessionLock) {
                liveGuidanceRequestInFlight = false
            }
            emitLiveTiming(
                eventName = "send_failed",
                errorMessage = "Gemini Live websocket send returned false.",
            )
        } else {
            emitLiveTiming("request_sent")
        }
        return sent
    }

    override fun stopLiveSession() {
        synchronized(liveSessionLock) {
            liveSessionSocket?.close(1000, "live_session_stopped")
            liveSessionSocket = null
            liveSessionSetupComplete = false
            resetLiveStreamStateLocked()
            liveSessionTextCallback = null
            liveSessionErrorCallback = null
            liveSessionTimingCallback = null
        }
        liveAudioPlayer.stop()
    }

    override fun isLiveSessionActive(): Boolean {
        return synchronized(liveSessionLock) {
            liveSessionSocket != null
        }
    }

    override fun close() {
        stopLiveSession()
        liveAudioPlayer.release()
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
                        webSocket.send(liveRealtimeVideoPayload(base64Image))
                        webSocket.send(liveClientTextTurnPayload(GEMINI_LIVE_GUIDANCE_PROMPT))
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
                                resetLiveStreamStateLocked()
                            }
                        }
                        return
                    }
                    if (ENABLE_LIVE_NATIVE_AUDIO_PLAYBACK) {
                        collectLiveAudio(message).forEach { audio ->
                            liveAudioPlayer.play(audio.bytes, audio.mimeType)
                            if (liveAudioPlayer.markFirstAudio()) {
                                emitLiveTiming(
                                    eventName = "first_audio",
                                    audioBytes = liveAudioPlayer.audioBytesReceived(),
                                )
                            }
                        }
                    }
                    collectLiveText(message)?.let { chunk ->
                        synchronized(liveSessionLock) {
                            if (liveSessionSocket == webSocket && liveGuidanceRequestInFlight) {
                                if (liveResponseTextBuffer.isNotEmpty()) liveResponseTextBuffer.append(' ')
                                liveResponseTextBuffer.append(chunk)
                            }
                        }
                    }
                    val serverContent = message.optJSONObject("serverContent")
                    val modelTurnParts = serverContent
                        ?.optJSONObject("modelTurn")
                        ?.optJSONArray("parts")
                    if (
                        liveGuidanceRequestInFlight &&
                        modelTurnParts != null &&
                        modelTurnParts.length() > 0 &&
                        liveAudioPlayer.audioBytesReceived() <= 0 &&
                        synchronized(liveSessionLock) { liveResponseTextBuffer.isEmpty() }
                    ) {
                        emitLiveTiming(
                            eventName = "model_turn_without_audio_or_text",
                            errorMessage = modelTurnParts.optJSONObject(0)
                                ?.keys()
                                ?.asSequence()
                                ?.joinToString(",")
                                ?: "unknown_part_shape",
                        )
                    }
                    if (serverContent?.optBoolean("generationComplete", false) == true) {
                        emitLiveTiming(
                            eventName = "generation_complete",
                            audioBytes = liveAudioPlayer.audioBytesReceived(),
                            transcriptChars = synchronized(liveSessionLock) {
                                liveResponseTextBuffer.length
                            },
                        )
                    }
                    if (serverContent?.optBoolean("turnComplete", false) == true) {
                        val completeText = synchronized(liveSessionLock) {
                            if (liveSessionSocket == webSocket) {
                                liveGuidanceRequestInFlight = false
                                liveResponseTextBuffer.toString().trim().also {
                                    liveResponseTextBuffer.clear()
                                }
                            } else {
                                ""
                            }
                        }
                        emitLiveTiming(
                            eventName = "turn_complete",
                            audioBytes = liveAudioPlayer.audioBytesReceived(),
                            transcriptChars = completeText.length,
                        )
                        if (!ENABLE_LIVE_NATIVE_AUDIO_PLAYBACK && completeText.isNotBlank()) {
                            liveSessionTextCallback?.invoke(completeText)
                        }
                    }
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
                                resetLiveStreamStateLocked()
                            }
                        }
                        liveSessionErrorCallback?.invoke(t.message ?: "Gemini Live session failed.")
                        emitLiveTiming(
                            eventName = "failure",
                            errorMessage = t.message ?: "Gemini Live session failed.",
                        )
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "Gemini Live session $apiVersion websocket closing code=$code reason=$reason")
                    val shouldNotify = synchronized(liveSessionLock) {
                        if (liveSessionSocket == webSocket) {
                            liveSessionSocket = null
                            liveSessionSetupComplete = false
                            resetLiveStreamStateLocked()
                            true
                        } else {
                            false
                        }
                    }
                    if (shouldNotify && code != 1000) {
                        val message = "Gemini Live socket closed: code=$code reason=$reason"
                        liveSessionErrorCallback?.invoke(message)
                        emitLiveTiming(eventName = "closed", errorMessage = message)
                    }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.w(TAG, "Gemini Live session $apiVersion websocket closed code=$code reason=$reason")
                    synchronized(liveSessionLock) {
                        if (liveSessionSocket == webSocket) {
                            liveSessionSocket = null
                            liveSessionSetupComplete = false
                            resetLiveStreamStateLocked()
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

    private fun resetLiveStreamStateLocked() {
        liveSessionLastPromptAtMs = 0L
        liveGuidanceRequestInFlight = false
        liveRealtimeFramesSent = 0
        liveResponseTextBuffer.clear()
        liveAudioPlayer.resetRequest()
    }

    private fun emitLiveTiming(
        eventName: String,
        audioBytes: Int = liveAudioPlayer.audioBytesReceived(),
        transcriptChars: Int = synchronized(liveSessionLock) { liveResponseTextBuffer.length },
        errorMessage: String? = null,
    ) {
        liveSessionTimingCallback?.invoke(
            LiveVlmTimingEvent(
                eventName = eventName,
                elapsedMs = liveAudioPlayer.requestElapsedMs(),
                audioBytes = audioBytes,
                transcriptChars = transcriptChars,
                errorMessage = errorMessage,
            ),
        )
    }

    private fun liveConfigPayload(): String {
        val setup = JSONObject()
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
            .put(
                "realtimeInputConfig",
                JSONObject()
                    .put("turnCoverage", "TURN_INCLUDES_ALL_INPUT")
                    .put("activityHandling", "NO_INTERRUPTION"),
            )
        if (ENABLE_LIVE_OUTPUT_TRANSCRIPTION) {
            setup.put("outputAudioTranscription", JSONObject())
        }
        return JSONObject()
            .put("setup", setup)
            .toString()
    }

    private fun liveApiBase(apiVersion: String): String {
        return "wss://generativelanguage.googleapis.com/ws/" +
            "google.ai.generativelanguage.$apiVersion.GenerativeService.BidiGenerateContent"
    }

    private fun liveClientTextTurnPayload(text: String): String {
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
                                    JSONArray().put(JSONObject().put("text", text)),
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

    private fun collectLiveAudio(message: JSONObject): List<LiveAudioChunk> {
        val chunks = mutableListOf<LiveAudioChunk>()
        val serverContent = message.optJSONObject("serverContent") ?: return chunks
        serverContent.optJSONObject("modelTurn")
            ?.optJSONArray("parts")
            ?.let { parts ->
                for (index in 0 until parts.length()) {
                    val part = parts.optJSONObject(index) ?: continue
                    val inlineData = part.optJSONObject("inlineData")
                        ?: part.optJSONObject("inline_data")
                        ?: continue
                    val data = inlineData.optString("data").takeIf { it.isNotBlank() } ?: continue
                    val mimeType = inlineData.optString("mimeType")
                        .takeIf { it.isNotBlank() }
                        ?: inlineData.optString("mime_type")
                            .takeIf { it.isNotBlank() }
                        ?: DEFAULT_LIVE_AUDIO_MIME_TYPE
                    chunks += LiveAudioChunk(
                        bytes = Base64.decode(data, Base64.DEFAULT),
                        mimeType = mimeType,
                    )
                }
            }
        return chunks
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
        val outputTranscription = serverContent.optJSONObject("outputTranscription")
            ?: serverContent.optJSONObject("output_transcription")
        outputTranscription
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
        return toBase64Jpeg(maxSide = MAX_IMAGE_SIDE, quality = 72)
    }

    private fun Bitmap.toBase64LiveJpeg(): String {
        return toBase64Jpeg(maxSide = LIVE_MAX_IMAGE_SIDE, quality = LIVE_JPEG_QUALITY)
    }

    private fun Bitmap.toBase64Jpeg(maxSide: Int, quality: Int): String {
        val resized = scaleForGemini(maxSide)
        return try {
            ByteArrayOutputStream().use { output ->
                resized.compress(Bitmap.CompressFormat.JPEG, quality, output)
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
        const val GEMINI_FLASH_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        private const val DEFAULT_MODEL = "gemini-2.5-flash-lite"
        private const val MAX_IMAGE_SIDE = 640
        private const val LIVE_SETUP_TIMEOUT_SECONDS = 8L
        private const val LIVE_TIMEOUT_SECONDS = 25L
        private const val LIVE_MAX_IMAGE_SIDE = 384
        private const val LIVE_JPEG_QUALITY = 55
        private const val LIVE_STREAM_FRAME_INTERVAL_MS = 500L
        private const val LIVE_RESPONSE_MODALITY = "AUDIO"
        private const val ENABLE_LIVE_OUTPUT_TRANSCRIPTION = true
        private const val ENABLE_LIVE_NATIVE_AUDIO_PLAYBACK = true
        private const val DEFAULT_LIVE_AUDIO_MIME_TYPE = "audio/pcm;rate=24000"
        private val liveHttpClient = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .pingInterval(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
        private val LIVE_API_VERSIONS = listOf("v1alpha", "v1beta")
        private const val GEMINI_LIVE_SYSTEM_PROMPT =
            "You are a walking-assistance helper for a blind or low-vision pedestrian. " +
            "Always produce a final Korean walking guidance answer. " +
            "Do not stop after internal thinking. " +
            "Use only concrete navigation or safety information from the camera frames. " +
            "Answer immediately in one short sentence unless there is a critical hazard."
        private const val GEMINI_LIVE_GUIDANCE_PROMPT =
            "Using the realtime camera video received so far, immediately give one short Korean walking guidance sentence."
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

private data class LiveAudioChunk(
    val bytes: ByteArray,
    val mimeType: String,
)

private class GeminiLiveAudioPlayer(context: Context?) {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "GeminiLiveAudioPlayer").apply { isDaemon = true }
    }
    private val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var audioTrack: AudioTrack? = null
    private var currentSampleRate = 0
    private var requestStartedAtMs = 0L
    private var firstAudioSeen = false
    private var receivedBytes = 0
    private var submittedFrames = 0L
    private var pendingWrites = 0
    @Volatile
    private var released = false

    fun startRequest() {
        synchronized(lock) {
            requestAudioFocusLocked()
            requestStartedAtMs = SystemClock.elapsedRealtime()
            firstAudioSeen = false
            receivedBytes = 0
            submittedFrames = audioTrack?.playbackHeadPositionUnsigned() ?: 0L
            pendingWrites = 0
        }
    }

    fun resetRequest() {
        synchronized(lock) {
            requestStartedAtMs = 0L
            firstAudioSeen = false
            receivedBytes = 0
            submittedFrames = audioTrack?.playbackHeadPositionUnsigned() ?: 0L
            pendingWrites = 0
        }
    }

    fun play(bytes: ByteArray, mimeType: String) {
        if (bytes.isEmpty() || released) return
        val sampleRate = sampleRateFromMimeType(mimeType)
        synchronized(lock) {
            receivedBytes += bytes.size
            pendingWrites += 1
        }
        executor.execute {
            if (released) {
                synchronized(lock) {
                    pendingWrites = (pendingWrites - 1).coerceAtLeast(0)
                }
                return@execute
            }
            runCatching {
                val track = synchronized(lock) {
                    ensureTrackLocked(sampleRate)
                }
                val written = track.write(bytes, 0, bytes.size, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    Log.w(
                        "GeminiLiveAudio",
                        "AudioTrack write failed result=$written mimeType=$mimeType bytes=${bytes.size}",
                    )
                } else {
                    synchronized(lock) {
                        submittedFrames += written / PCM_16_MONO_BYTES_PER_FRAME
                    }
                    Log.d(
                        "GeminiLiveAudio",
                        "AudioTrack wrote bytes=$written mimeType=$mimeType sampleRate=$sampleRate " +
                            "playState=${track.playState} state=${track.state}",
                    )
                }
            }.onFailure {
                Log.w("GeminiLiveAudio", "Failed to play Gemini Live audio chunk", it)
            }.also {
                synchronized(lock) {
                    pendingWrites = (pendingWrites - 1).coerceAtLeast(0)
                }
            }
        }
    }

    fun markFirstAudio(): Boolean {
        return synchronized(lock) {
            if (firstAudioSeen) {
                false
            } else {
                firstAudioSeen = true
                true
            }
        }
    }

    fun audioBytesReceived(): Int {
        return synchronized(lock) { receivedBytes }
    }

    fun requestElapsedMs(): Long {
        return synchronized(lock) {
            if (requestStartedAtMs <= 0L) 0L else SystemClock.elapsedRealtime() - requestStartedAtMs
        }
    }

    fun stop() {
        executor.execute {
            synchronized(lock) {
                audioTrack?.pause()
                audioTrack?.flush()
                submittedFrames = audioTrack?.playbackHeadPositionUnsigned() ?: 0L
                pendingWrites = 0
                abandonAudioFocusLocked()
            }
        }
        resetRequest()
    }

    fun release() {
        released = true
        executor.execute {
            synchronized(lock) {
                audioTrack?.stop()
                audioTrack?.release()
                audioTrack = null
                currentSampleRate = 0
                submittedFrames = 0L
                pendingWrites = 0
                abandonAudioFocusLocked()
            }
        }
    }

    private fun ensureTrackLocked(sampleRate: Int): AudioTrack {
        audioTrack?.takeIf { currentSampleRate == sampleRate }?.let { track ->
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                requestAudioFocusLocked()
                track.play()
            }
            return track
        }

        audioTrack?.release()
        requestAudioFocusLocked()
        currentSampleRate = sampleRate
        submittedFrames = 0L
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ).coerceAtLeast(sampleRate / 2)
        val bufferSize = (minBufferSize * 2).coerceAtLeast(PCM_16_MONO_BYTES_PER_FRAME * sampleRate / 10)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
            .also { track ->
                audioTrack = track
                submittedFrames = track.playbackHeadPositionUnsigned()
                Log.d(
                    "GeminiLiveAudio",
                    "Created AudioTrack sampleRate=$sampleRate minBufferSize=$minBufferSize " +
                        "bufferSize=$bufferSize state=${track.state}",
                )
                track.play()
            }
    }

    private fun requestAudioFocusLocked() {
        val manager = audioManager ?: return
        if (audioFocusRequest != null) return
        audioFocusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .build()
        audioFocusRequest?.let(manager::requestAudioFocus)
    }

    private fun abandonAudioFocusLocked() {
        val manager = audioManager ?: return
        audioFocusRequest?.let(manager::abandonAudioFocusRequest)
        audioFocusRequest = null
    }

    private fun sampleRateFromMimeType(mimeType: String): Int {
        return Regex("""rate=(\d+)""")
            .find(mimeType)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?: 24_000
    }

    private fun AudioTrack.playbackHeadPositionUnsigned(): Long {
        return playbackHeadPosition.toLong() and 0xFFFF_FFFFL
    }

    private companion object {
        private const val PCM_16_MONO_BYTES_PER_FRAME = 2
    }
}
