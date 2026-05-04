package com.example.walkassist

enum class VlmWalkingRisk {
    CLEAR,
    CAUTION,
    BLOCKED,
    UNKNOWN,
}

enum class VlmSuggestedAction {
    PROCEED,
    SLOW_DOWN,
    VEER_LEFT,
    VEER_RIGHT,
    STOP,
    UNKNOWN,
}

data class VlmSceneInterpretation(
    val modelName: String,
    val schemaVersion: Int,
    val risk: VlmWalkingRisk,
    val suggestedAction: VlmSuggestedAction,
    val confidence: Float,
    val pathSummary: String,
    val evidence: List<String>,
    val shouldOverridePrimary: Boolean = false,
)

interface VlmSceneInterpreter {
    fun interpret(
        frame: SpatialFrame,
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
    ): VlmSceneInterpretation?

    fun close() = Unit
}

object VlmScenePromptSchema {
    const val SYSTEM_PROMPT = """
You are a low-frequency walking-assistance visual scene interpreter.
Use the image only as a secondary judgement layer after fast CV has already run.
Return compact JSON only. Do not replace geometric distance estimates unless the scene is clearly unsafe.
"""

    const val OUTPUT_SCHEMA = """
{
  "schemaVersion": 1,
  "risk": "CLEAR|CAUTION|BLOCKED|UNKNOWN",
  "suggestedAction": "PROCEED|SLOW_DOWN|VEER_LEFT|VEER_RIGHT|STOP|UNKNOWN",
  "confidence": 0.0,
  "pathSummary": "short Korean or English summary",
  "evidence": ["short visual cue"],
  "shouldOverridePrimary": false
}
"""
}

class StubVlmSceneInterpreter : VlmSceneInterpreter {
    override fun interpret(
        frame: SpatialFrame,
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
    ): VlmSceneInterpretation {
        val collisionDistance = primaryAnalysis.pathMetrics?.collisionDistanceMeters
            ?: primaryAnalysis.pathMetrics?.centerObstacleMeters
        val centerBlocked = collisionDistance != null && collisionDistance < 1.2f
        val hasPerson = primaryAnalysis.detections.any { it.label.equals("person", ignoreCase = true) }
        val risk = when {
            centerBlocked -> VlmWalkingRisk.BLOCKED
            hasPerson || crosswalk.detected -> VlmWalkingRisk.CAUTION
            primaryAnalysis.floorSegmentation?.confidence != null &&
                primaryAnalysis.floorSegmentation.confidence >= 0.55f -> VlmWalkingRisk.CLEAR
            else -> VlmWalkingRisk.UNKNOWN
        }
        val action = when (risk) {
            VlmWalkingRisk.BLOCKED -> VlmSuggestedAction.SLOW_DOWN
            VlmWalkingRisk.CAUTION -> VlmSuggestedAction.SLOW_DOWN
            VlmWalkingRisk.CLEAR -> VlmSuggestedAction.PROCEED
            VlmWalkingRisk.UNKNOWN -> VlmSuggestedAction.UNKNOWN
        }
        return VlmSceneInterpretation(
            modelName = "stub-gemma-vlm",
            schemaVersion = 1,
            risk = risk,
            suggestedAction = action,
            confidence = when (risk) {
                VlmWalkingRisk.CLEAR -> 0.62f
                VlmWalkingRisk.CAUTION -> 0.58f
                VlmWalkingRisk.BLOCKED -> 0.66f
                VlmWalkingRisk.UNKNOWN -> 0.35f
            },
            pathSummary = when (risk) {
                VlmWalkingRisk.CLEAR -> "전방 보행 공간이 대체로 확보되어 보입니다."
                VlmWalkingRisk.CAUTION -> "전방을 계속 확인하며 천천히 이동해야 합니다."
                VlmWalkingRisk.BLOCKED -> "전방 통로에 장애물이 있을 수 있습니다."
                VlmWalkingRisk.UNKNOWN -> "장면 판단 신뢰도가 낮습니다."
            },
            evidence = buildList {
                add("source=${frame.source.name.lowercase()}")
                primaryAnalysis.pathMetrics?.pathClearMeters?.let { add("pathClear=${String.format("%.1f", it)}m") }
                if (crosswalk.detected) add("crosswalk=${(crosswalk.score * 100f).toInt()}%")
                if (hasPerson) add("person_detected")
            },
            shouldOverridePrimary = false,
        )
    }
}

class VlmInvocationPolicy(
    private val minIntervalMillis: Long = 1_500L,
    private val periodicIntervalMillis: Long = 5_000L,
) {
    private var lastInvocationMillis: Long? = null

    fun shouldInvoke(
        frame: SpatialFrame,
        primaryAnalysis: FrameAnalysis,
    ): Boolean {
        val metrics = primaryAnalysis.pathMetrics
        val collisionDistance = metrics?.collisionDistanceMeters ?: metrics?.centerObstacleMeters
        val lowConfidence = (primaryAnalysis.floorSegmentation?.confidence ?: 0f) < 0.55f
        val cautionDistance = collisionDistance == null || collisionDistance < 1.8f
        val usefulObjectCue = primaryAnalysis.detections.any {
            it.label.equals("person", ignoreCase = true) || it.label.equals("crosswalk", ignoreCase = true)
        }
        return shouldInvoke(
            timestampMillis = frame.timestampMillis,
            hasPriorityCue = lowConfidence || cautionDistance || usefulObjectCue,
        )
    }

    internal fun shouldInvoke(
        timestampMillis: Long,
        hasPriorityCue: Boolean,
    ): Boolean {
        val previousInvocationMillis = lastInvocationMillis
        if (
            previousInvocationMillis != null &&
            timestampMillis >= previousInvocationMillis &&
            timestampMillis - previousInvocationMillis < minIntervalMillis
        ) {
            return false
        }
        val elapsedMillis = previousInvocationMillis?.let { timestampMillis - it }
        val shouldRun = previousInvocationMillis == null ||
            hasPriorityCue ||
            elapsedMillis == null ||
            elapsedMillis >= periodicIntervalMillis
        return if (shouldRun) {
            lastInvocationMillis = timestampMillis
            true
        } else {
            false
        }
    }
}
