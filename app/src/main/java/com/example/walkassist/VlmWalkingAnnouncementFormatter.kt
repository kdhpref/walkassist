package com.example.walkassist

object VlmWalkingAnnouncementFormatter {
    private val unsafeGuidancePatterns = listOf(
        Regex("직접\\s*(확인|판단|둘러보|살펴보)[^.!?。]*[.!?。]?"),
        Regex("(주변|앞쪽|경로|길|장면).{0,12}(직접\\s*)?(확인|검사|살펴보)[^.!?。]*[.!?。]?"),
        Regex("(확인하세요|확인해 주세요|살펴보세요|둘러보세요)"),
        Regex("(look\\s+around|check\\s+yourself|verify\\s+yourself|inspect\\s+yourself)", RegexOption.IGNORE_CASE),
    )
    private val sentenceEndPunctuation = setOf('.', '!', '?', '。')

    fun build(
        interpretation: VlmSceneInterpretation,
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
    ): String {
        val action = when (interpretation.suggestedAction) {
            VlmSuggestedAction.PROCEED -> "천천히 직진해도 됩니다."
            VlmSuggestedAction.SLOW_DOWN -> "속도를 줄이고 조심해서 이동하세요."
            VlmSuggestedAction.VEER_LEFT -> "왼쪽으로 살짝 피해서 이동하세요."
            VlmSuggestedAction.VEER_RIGHT -> "오른쪽으로 살짝 피해서 이동하세요."
            VlmSuggestedAction.STOP -> "앞이 위험합니다. 잠시 멈추세요."
            VlmSuggestedAction.UNKNOWN -> fallbackAction(interpretation.risk)
        }
        val riskPrefix = when (interpretation.risk) {
            VlmWalkingRisk.BLOCKED -> "이동이 어려워 보입니다."
            VlmWalkingRisk.CAUTION -> "주의가 필요합니다."
            VlmWalkingRisk.CLEAR -> "앞쪽 경로가 비교적 열려 있습니다."
            VlmWalkingRisk.UNKNOWN -> "앞쪽 장면을 확실하게 판단하기 어렵습니다."
        }
        val cues = userVisibleEvidence(interpretation.evidence).ifEmpty {
            val detectedObjects = primaryAnalysis.detections
                .map { presentableObjectLabel(it.label) }
                .distinct()
                .take(3)
            when {
                detectedObjects.isNotEmpty() -> detectedObjects.map { "$it 감지" }
                crosswalk.detected -> listOf("횡단보도 패턴 감지")
                else -> emptyList()
            }
        }
        val summary = sanitizeForWalkingTts(
            text = interpretation.pathSummary,
            fallback = fallbackSceneSummary(cues, interpretation.risk, crosswalk.detected),
        ).takeIf { it.isNotBlank() }

        return buildString {
            summary?.let {
                appendSentence(it)
                append(' ')
            }
            append(riskPrefix)
            append(' ')
            append(action)
            if (cues.isNotEmpty()) {
                append(" 보이는 단서는 ")
                append(cues.joinToString(", "))
                append("입니다.")
            }
        }.replace(Regex("\\s+"), " ").trim()
    }

    internal fun userVisibleEvidence(evidence: List<String>): List<String> {
        return evidence
            .mapNotNull(::presentableEvidence)
            .mapNotNull {
                sanitizeForWalkingTts(text = it, fallback = "")
                    .takeIf { cue -> cue.isNotBlank() }
            }
            .distinct()
            .take(3)
    }

    internal fun sanitizeForWalkingTts(text: String, fallback: String): String {
        val initial = text
            .replace("VLM stub:", "")
            .trim()
        if (initial.isBlank()) return fallback

        val cleaned = unsafeGuidancePatterns
            .fold(initial) { current, pattern -> pattern.replace(current, "") }
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([.!?。])"), "$1")
            .trim()
            .trim('.', '!', '?', '。')
            .trim()

        return if (cleaned.isNotBlank() && !containsUnsafeGuidance(cleaned)) {
            cleaned
        } else {
            fallback
        }
    }

    private fun fallbackAction(risk: VlmWalkingRisk): String {
        return when (risk) {
            VlmWalkingRisk.BLOCKED -> "앞이 막혀 있을 수 있습니다. 잠시 멈추세요."
            VlmWalkingRisk.CAUTION -> "천천히 이동하세요."
            VlmWalkingRisk.CLEAR -> "현재 보이는 길은 대체로 이동 가능합니다."
            VlmWalkingRisk.UNKNOWN -> "속도를 줄이고 잠시 멈추는 편이 안전합니다."
        }
    }

    private fun fallbackSceneSummary(
        cues: List<String>,
        risk: VlmWalkingRisk,
        crosswalkDetected: Boolean,
    ): String {
        if (cues.isNotEmpty()) {
            return "카메라 방향에 ${cues.joinToString(", ") { it.toSceneCue() }}이 보입니다"
        }
        if (crosswalkDetected) {
            return "카메라 방향 바닥에 횡단보도 패턴이 보입니다"
        }
        return when (risk) {
            VlmWalkingRisk.BLOCKED -> "카메라 방향 앞쪽 통로가 막혀 보입니다"
            VlmWalkingRisk.CAUTION -> "카메라 방향 앞쪽에 주의할 장면이 있습니다"
            VlmWalkingRisk.CLEAR -> "카메라 방향 앞쪽 보행 공간이 대체로 열려 있습니다"
            VlmWalkingRisk.UNKNOWN -> "카메라 방향의 장면 정보가 부족합니다"
        }
    }

    private fun presentableEvidence(rawCue: String): String? {
        val cue = rawCue.trim()
        if (cue.isBlank()) return null
        val lower = cue.lowercase()
        return when {
            lower == "person_detected" -> "사람 감지"
            lower.startsWith("crosswalk=") -> "횡단보도 패턴 감지"
            lower.startsWith("yolo-seg:") -> cue.removePrefix("YOLO-seg:").trim()
            lower.startsWith("arcore:") -> cue
            lower.startsWith("imu:") -> cue
            lower.startsWith("moondream:") -> cue
            lower.startsWith("qwen2-vlm:") -> cue
            lower.startsWith("vlm:") -> cue
            lower == "door" -> "문"
            lower == "stairs" || lower == "stair" -> "계단"
            lower == "curb" -> "턱"
            lower == "traffic cone" || lower == "cone" -> "안전 콘"
            lower == "construction sign" -> "공사 표지판"
            lower == "vehicle" -> "차량"
            lower == "wall" -> "벽"
            lower == "hole" -> "구멍"
            lower.startsWith("aicore=") -> null
            lower.startsWith("source=") -> null
            lower.startsWith("pathclear=") -> null
            lower.startsWith("floorconfidence=") -> null
            lower.startsWith("collisiondistancemeters=") -> null
            lower.startsWith("centerobstaclemeters=") -> null
            lower.contains("=") -> null
            lower.contains("stub") -> null
            else -> cue.replace('_', ' ')
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
            "traffic cone", "cone" -> "안전 콘"
            else -> label
        }
    }

    private fun String.toSceneCue(): String {
        return removeSuffix(" 패턴 감지")
            .removeSuffix(" 감지")
            .trim()
    }

    private fun StringBuilder.appendSentence(text: String) {
        val sentence = text.trim()
        append(sentence)
        val last = sentence.lastOrNull()
        if (last == null || last !in sentenceEndPunctuation) {
            append('.')
        }
    }

    private fun containsUnsafeGuidance(text: String): Boolean {
        return unsafeGuidancePatterns.any { it.containsMatchIn(text) }
    }
}
