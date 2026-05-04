package com.example.walkassist

object VlmWalkingAnnouncementFormatter {
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
            VlmWalkingRisk.UNKNOWN -> "앞쪽 장면을 확실히 판단하기 어렵습니다."
        }
        val summary = interpretation.pathSummary
            .replace("VLM stub:", "")
            .trim()
            .takeIf { it.isNotBlank() }
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

        return buildString {
            append(riskPrefix)
            append(' ')
            append(action)
            summary?.let {
                append(" 이유는 ")
                append(it)
                append('.')
            }
            if (cues.isNotEmpty()) {
                append(" 보이는 단서는 ")
                append(cues.joinToString(", "))
                append("입니다.")
            }
        }
    }

    internal fun userVisibleEvidence(evidence: List<String>): List<String> {
        return evidence
            .mapNotNull(::presentableEvidence)
            .distinct()
            .take(3)
    }

    private fun fallbackAction(risk: VlmWalkingRisk): String {
        return when (risk) {
            VlmWalkingRisk.BLOCKED -> "앞이 막혀 있을 수 있습니다. 잠시 멈추세요."
            VlmWalkingRisk.CAUTION -> "천천히 확인하며 이동하세요."
            VlmWalkingRisk.CLEAR -> "현재 보이는 길은 대체로 이동 가능합니다."
            VlmWalkingRisk.UNKNOWN -> "주변을 직접 확인하세요."
        }
    }

    private fun presentableEvidence(rawCue: String): String? {
        val cue = rawCue.trim()
        if (cue.isBlank()) return null
        val lower = cue.lowercase()
        return when {
            lower == "person_detected" -> "사람 감지"
            lower.startsWith("crosswalk=") -> "횡단보도 패턴 감지"
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
            else -> label
        }
    }
}
