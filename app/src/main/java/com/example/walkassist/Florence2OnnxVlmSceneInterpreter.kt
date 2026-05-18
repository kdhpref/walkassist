package com.example.walkassist

import android.content.Context
import android.util.Log

class Florence2OnnxVlmSceneInterpreter(
    context: Context,
    private val variant: Florence2OnnxVariant,
) : VlmSceneInterpreter {
    private val appContext = context.applicationContext
    private var generator: Florence2OnnxGenerator? = null

    fun prepareForUse(): VlmModelPreparationStatus {
        val status = Florence2ModelStore.localStatus(appContext, variant)
        return VlmModelPreparationStatus(
            modelName = variant.modelName,
            statusLabel = if (status.isAvailable) "local_files_ready" else "missing_local_files",
            downloadState = status.rootDirectory.absolutePath,
            isAvailable = status.isAvailable,
            isFallbackLikely = !status.isAvailable,
            explanation = if (status.isAvailable) {
                "Florence-2 ONNX files are present on device."
            } else {
                "Missing ${status.missingFiles.size} Florence-2 file(s). Download them from VLM settings."
            },
        )
    }

    override fun interpret(
        frame: SpatialFrame,
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
    ): VlmSceneInterpretation? {
        if (frame.requestMode != VlmRequestMode.MANUAL) {
            return null
        }

        val status = Florence2ModelStore.localStatus(appContext, variant)
        if (!status.isAvailable) {
            return VlmSceneInterpretation(
                modelName = variant.modelName,
                schemaVersion = 1,
                risk = VlmWalkingRisk.UNKNOWN,
                suggestedAction = VlmSuggestedAction.UNKNOWN,
                confidence = 0.1f,
                pathSummary = "Florence-2 ${variant.displayName} model files are not downloaded yet.",
                evidence = listOf("missing_files=${status.missingFiles.size}"),
                shouldOverridePrimary = false,
            )
        }

        val caption = runCatching {
            getOrCreateGenerator(status).generateCaption(frame.bitmap)
        }.onFailure {
            Log.w(TAG, "Florence-2 ${variant.displayName} generation failed", it)
        }.getOrNull()

        if (!caption.isNullOrBlank()) {
            val summary = VlmWalkingAnnouncementFormatter.sanitizeForWalkingTts(
                text = caption.twoOrThreeSentences(),
                fallback = "Florence-2 image description is unavailable.",
            )
            return VlmSceneInterpretation(
                modelName = variant.modelName,
                schemaVersion = 1,
                risk = VlmWalkingRisk.UNKNOWN,
                suggestedAction = VlmSuggestedAction.UNKNOWN,
                confidence = 0.5f,
                pathSummary = summary,
                evidence = listOf("local_onnx=${variant.displayName}"),
                shouldOverridePrimary = false,
            )
        }

        return VlmSceneInterpretation(
            modelName = variant.modelName,
            schemaVersion = 1,
            risk = VlmWalkingRisk.UNKNOWN,
            suggestedAction = VlmSuggestedAction.UNKNOWN,
            confidence = 0.1f,
            pathSummary = "Florence-2 ${variant.displayName} image description failed.",
            evidence = listOf("model_dir=${status.rootDirectory.absolutePath}"),
            shouldOverridePrimary = false,
        )
    }

    private fun getOrCreateGenerator(status: Florence2ModelLocalStatus): Florence2OnnxGenerator {
        return generator ?: Florence2OnnxGenerator(
            modelRoot = status.rootDirectory,
            variant = variant,
        ).also {
            generator = it
        }
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

    override fun close() {
        generator?.close()
        generator = null
    }

    companion object {
        private const val TAG = "Florence2OnnxVlm"
    }
}
