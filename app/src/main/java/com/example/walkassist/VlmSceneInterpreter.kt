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

data class VlmModelPreparationStatus(
    val modelName: String,
    val statusLabel: String,
    val downloadState: String?,
    val isAvailable: Boolean,
    val isFallbackLikely: Boolean,
    val explanation: String,
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
You are the camera-based visual guide for a blind or low-vision pedestrian.
Describe the scene as the user's eyes: visible objects, doors, stairs, curbs, people, vehicles, signs, narrow passages, floor/path condition, and left/center/right position when visible.
Never tell the user to look, check, verify, inspect, or confirm the surroundings themselves. Forbidden wording includes "직접 확인", "주변을 확인", "전방을 확인", "확인하세요", "살펴보세요", "look around", and "check yourself".
Use YOLO, ARCore, depth, floor, and distance values only as private supporting context for deciding what matters.
Do not read raw detector labels, confidence scores, meter values, or sensor field names to the user unless a distance is essential for immediate safety.
The user-facing answer must describe what is visibly in the camera image.
If useful text is visible, read or summarize it when it helps walking or orientation: bus numbers and colors, route signs, store names, restaurant menus, entrance/exit signs, warning signs, floor labels, and other navigational text.
Ignore decorative, tiny, cut-off, or irrelevant text.
Do not give only generic safety advice. Always include concrete visual information from the camera image when available. Prefer a compact scene description over a bare risk label.
Return compact JSON only.
"""

    const val OUTPUT_SCHEMA = """
{
  "schemaVersion": 1,
  "risk": "CLEAR|CAUTION|BLOCKED|UNKNOWN",
  "suggestedAction": "PROCEED|SLOW_DOWN|VEER_LEFT|VEER_RIGHT|STOP|UNKNOWN",
  "confidence": 0.0,
  "pathSummary": "two or three short Korean walking-scene sentences with concrete visible details, useful visible text when present, and no raw sensor values",
  "evidence": ["visible cue 1", "useful visible text cue if any", "visible cue 3"],
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
            modelName = "stub-vlm",
            schemaVersion = 1,
            risk = risk,
            suggestedAction = action,
            confidence = when (risk) {
                VlmWalkingRisk.CLEAR -> 0.62f
                VlmWalkingRisk.CAUTION -> 0.58f
                VlmWalkingRisk.BLOCKED -> 0.66f
                VlmWalkingRisk.UNKNOWN -> 0.35f
            },
            pathSummary = buildFallbackSceneSummary(primaryAnalysis, crosswalk, risk),
            evidence = buildList {
                add("source=${frame.source.name.lowercase()}")
                primaryAnalysis.pathMetrics?.pathClearMeters?.let { add("pathClear=${String.format("%.1f", it)}m") }
                if (crosswalk.detected) add("crosswalk=${(crosswalk.score * 100f).toInt()}%")
                if (hasPerson) add("person_detected")
                addAll(
                    primaryAnalysis.detections
                        .take(4)
                        .map { detection -> presentableObjectLabel(detection.label) }
                        .filter { it.isNotBlank() },
                )
            },
            shouldOverridePrimary = false,
        )
    }

    private fun buildFallbackSceneSummary(
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
        risk: VlmWalkingRisk,
    ): String {
        val objectPhrases = primaryAnalysis.detections
            .sortedWith(
                compareBy<DetectedObjectResult> { it.distanceEstimate.distanceMeters ?: Float.MAX_VALUE }
                    .thenByDescending { it.confidence },
            )
            .take(4)
            .map(::objectScenePhrase)
            .filter { it.isNotBlank() }

        val firstSentence = when {
            objectPhrases.isNotEmpty() -> "카메라 방향에 ${objectPhrases.joinToString(", ")}이 보입니다."
            crosswalk.detected -> "카메라 방향 바닥에 횡단보도 패턴이 보입니다."
            else -> when (risk) {
                VlmWalkingRisk.CLEAR -> "카메라 방향의 전방 보행 공간이 대체로 열려 있습니다."
                VlmWalkingRisk.CAUTION -> "카메라 방향 전방에 주의할 장면이 있습니다."
                VlmWalkingRisk.BLOCKED -> "카메라 방향 전방 통로가 막혀 보입니다."
                VlmWalkingRisk.UNKNOWN -> "카메라 방향의 장면 정보가 부족합니다."
            }
        }

        val pathSentence = when {
            crosswalk.detected && objectPhrases.isNotEmpty() -> "바닥에는 횡단보도 패턴이 함께 감지됩니다."
            primaryAnalysis.pathMetrics?.pathClearMeters != null ->
                "전방 보행 가능 거리는 약 ${formatMeters(primaryAnalysis.pathMetrics.pathClearMeters)}로 추정됩니다."
            primaryAnalysis.floorSegmentation?.confidence != null &&
                primaryAnalysis.floorSegmentation.confidence >= 0.55f ->
                "바닥 영역은 비교적 안정적으로 감지됩니다."
            else -> "바닥과 통로 정보는 아직 제한적입니다."
        }

        val actionSentence = when (risk) {
            VlmWalkingRisk.BLOCKED -> "속도를 줄이고 잠시 멈추는 편이 좋습니다."
            VlmWalkingRisk.CAUTION -> "속도를 줄여 이동하는 편이 좋습니다."
            VlmWalkingRisk.CLEAR -> "천천히 직진해도 됩니다."
            VlmWalkingRisk.UNKNOWN -> "잠시 멈춰 주세요."
        }
        return "$firstSentence $pathSentence $actionSentence"
    }

    private fun objectScenePhrase(detection: DetectedObjectResult): String {
        val label = presentableObjectLabel(detection.label)
        if (label.isBlank()) return ""
        val centerX = ((detection.boundingBox.left + detection.boundingBox.right) * 0.5f) /
            detection.imageWidth.coerceAtLeast(1)
        val lane = when {
            centerX < 0.38f -> "왼쪽"
            centerX > 0.62f -> "오른쪽"
            else -> "중앙"
        }
        val distance = detection.distanceEstimate.distanceMeters
            ?.takeIf { it > 0f && it < 20f }
            ?.let { " 약 ${formatMeters(it)} 앞" }
            .orEmpty()
        return "$lane${distance}에 $label"
    }

    private fun formatMeters(distanceMeters: Float): String {
        return if (distanceMeters < 1f) {
            "${(distanceMeters * 100f).toInt()}cm"
        } else {
            String.format("%.1fm", distanceMeters)
        }
    }

    private fun presentableObjectLabel(label: String): String {
        return when (label.lowercase()) {
            "person" -> "사람"
            "bicycle" -> "자전거"
            "car" -> "자동차"
            "motorcycle" -> "오토바이"
            "bus" -> "버스"
            "truck" -> "트럭"
            "chair" -> "의자"
            "bench" -> "벤치"
            "traffic light" -> "신호등"
            "stop sign" -> "표지판"
            "crosswalk" -> "횡단보도"
            "door" -> "문"
            "stairs", "stair" -> "계단"
            "curb" -> "턱"
            "traffic cone", "cone" -> "안전콘"
            else -> label
        }
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
