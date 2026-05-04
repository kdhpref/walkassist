package com.example.walkassist

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.ModelReleaseStage
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import com.google.mlkit.genai.prompt.generationConfig
import com.google.mlkit.genai.prompt.modelConfig
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

class AiCoreGemmaVlmSceneInterpreter(
    context: Context,
    private val fallback: VlmSceneInterpreter = StubVlmSceneInterpreter(),
) : VlmSceneInterpreter {
    private val appContext = context.applicationContext
    private val modelLock = Any()
    private var activeSelection: VlmModelSelection? = null
    private var activeModel: GenerativeModel? = null

    private fun generativeModel(): Pair<VlmModelSelection, GenerativeModel> {
        val selection = WalkAssistSettings.vlmModelSelection(appContext)
        synchronized(modelLock) {
            val model = activeModel
            if (model != null && activeSelection == selection) {
                return selection to model
            }
            runCatching { model?.close() }
            val newModel = Generation.getClient(
                generationConfig {
                    modelConfig = modelConfig {
                        releaseStage = ModelReleaseStage.PREVIEW
                        preference = selection.preference
                    }
                },
            )
            activeSelection = selection
            activeModel = newModel
            return selection to newModel
        }
    }

    override fun interpret(
        frame: SpatialFrame,
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
    ): VlmSceneInterpretation? {
        val (selection, model) = generativeModel()
        val status = runCatching { runBlocking { model.checkStatus() } }
            .onFailure { Log.w(TAG, "Gemma 4 Nano status check failed", it) }
            .getOrNull()

        if (status != FeatureStatus.AVAILABLE) {
            val fallbackResult = fallback.interpret(frame, primaryAnalysis, crosswalk) ?: return null
            return fallbackResult.copy(
                modelName = "gemma4-nano-${selection.name.lowercase()}-${statusLabel(status)}-fallback",
                evidence = fallbackResult.evidence + "aicore=${statusLabel(status)}",
            )
        }

        val vlmBitmap = scaledBitmapForVlm(frame.bitmap)
        return try {
            val response = runBlocking {
                model.generateContent(
                    generateContentRequest(
                        ImagePart(vlmBitmap),
                        TextPart(buildPrompt(primaryAnalysis, crosswalk)),
                    ) {
                        temperature = 0.1f
                        topK = 8
                        candidateCount = 1
                        maxOutputTokens = 192
                    },
                )
            }
            parseInterpretation(response.candidates.firstOrNull()?.text.orEmpty())
                ?: fallback.interpret(frame, primaryAnalysis, crosswalk)?.let { fallbackResult ->
                    fallbackResult.copy(
                        modelName = "gemma4-nano-${selection.name.lowercase()}-parse-fallback",
                        evidence = fallbackResult.evidence + "aicore=parse_fallback",
                    )
                }
        } catch (error: Exception) {
            Log.w(TAG, "Gemma 4 Nano inference failed", error)
            fallback.interpret(frame, primaryAnalysis, crosswalk)?.let { fallbackResult ->
                fallbackResult.copy(
                    modelName = "gemma4-nano-${selection.name.lowercase()}-error-fallback",
                    evidence = fallbackResult.evidence + "aicore=error_fallback",
                )
            }
        } finally {
            if (vlmBitmap !== frame.bitmap && !vlmBitmap.isRecycled) {
                vlmBitmap.recycle()
            }
        }
    }

    override fun close() {
        synchronized(modelLock) {
            runCatching { activeModel?.close() }
            activeModel = null
            activeSelection = null
        }
        fallback.close()
    }

    private fun buildPrompt(
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
    ): String {
        val metrics = primaryAnalysis.pathMetrics
        val objects = primaryAnalysis.detections
            .take(8)
            .joinToString(separator = "; ") { detection ->
                "${detection.label}:${String.format("%.2f", detection.confidence)}"
            }
            .ifBlank { "none" }
        return """
${VlmScenePromptSchema.SYSTEM_PROMPT}

Fast CV primary result:
- floorConfidence=${String.format("%.2f", primaryAnalysis.floorSegmentation?.confidence ?: 0f)}
- pathClearMeters=${metrics?.pathClearMeters?.let { String.format("%.2f", it) } ?: "unknown"}
- centerObstacleMeters=${metrics?.centerObstacleMeters?.let { String.format("%.2f", it) } ?: "unknown"}
- collisionDistanceMeters=${metrics?.collisionDistanceMeters?.let { String.format("%.2f", it) } ?: "unknown"}
- crosswalkDetected=${crosswalk.detected}
- crosswalkScore=${String.format("%.2f", crosswalk.score)}
- objects=$objects

Task:
Judge the walking path from the image as a practical walking-assistance check.
Describe what is in front of the user and whether it is safe to continue.
If the path is blocked or risky, explain why, including visible causes such as construction signs, cones, vehicles, people, stairs, curbs, holes, clutter, or narrow passages.
Write pathSummary and evidence in Korean. Keep pathSummary concise but useful for TextToSpeech.
Evidence must contain only visible, user-understandable cues. Do not include internal metric names, model names, source labels, confidence keys, or key=value debug text.
Return JSON exactly matching this schema:
${VlmScenePromptSchema.OUTPUT_SCHEMA}
""".trimIndent()
    }

    private fun parseInterpretation(text: String): VlmSceneInterpretation? {
        val jsonText = text.substringAfter('{', missingDelimiterValue = "")
            .substringBeforeLast('}', missingDelimiterValue = "")
            .takeIf { it.isNotBlank() }
            ?.let { "{$it}" }
            ?: return null
        val json = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
        val evidenceJson = json.optJSONArray("evidence") ?: JSONArray()
        return VlmSceneInterpretation(
            modelName = "gemma4-nano-aicore",
            schemaVersion = json.optInt("schemaVersion", 1),
            risk = parseRisk(json.optString("risk")),
            suggestedAction = parseAction(json.optString("suggestedAction")),
            confidence = json.optDouble("confidence", 0.0).toFloat().coerceIn(0f, 1f),
            pathSummary = json.optString("pathSummary", "장면 분석이 완료되었습니다."),
            evidence = List(evidenceJson.length()) { index -> evidenceJson.optString(index) }
                .filter { it.isNotBlank() }
                .take(6),
            shouldOverridePrimary = json.optBoolean("shouldOverridePrimary", false),
        )
    }

    private fun parseRisk(value: String): VlmWalkingRisk {
        return runCatching { VlmWalkingRisk.valueOf(value.uppercase()) }.getOrDefault(VlmWalkingRisk.UNKNOWN)
    }

    private fun parseAction(value: String): VlmSuggestedAction {
        return runCatching { VlmSuggestedAction.valueOf(value.uppercase()) }.getOrDefault(VlmSuggestedAction.UNKNOWN)
    }

    private fun scaledBitmapForVlm(bitmap: Bitmap): Bitmap {
        val maxSide = 512
        val currentMaxSide = maxOf(bitmap.width, bitmap.height)
        if (currentMaxSide <= maxSide) return bitmap
        val scale = maxSide / currentMaxSide.toFloat()
        val width = (bitmap.width * scale).toInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun statusLabel(status: Int?): String {
        return when (status) {
            FeatureStatus.AVAILABLE -> "available"
            FeatureStatus.DOWNLOADABLE -> "downloadable"
            FeatureStatus.DOWNLOADING -> "downloading"
            FeatureStatus.UNAVAILABLE -> "unavailable"
            else -> "unknown"
        }
    }

    companion object {
        private const val TAG = "AiCoreGemmaVlm"
    }
}
