package com.example.walkassist

object Florence2KoreanSceneFormatter {
    fun format(
        florenceCaption: String,
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
    ): String {
        val caption = florenceCaption.cleanCaption()
        val sceneSentence = when {
            caption.containsHangul() -> caption.takeSentences(maxSentences = 2)
            else -> buildKoreanSceneSentence(caption, primaryAnalysis)
        }
        val pathSentence = buildPathSentence(primaryAnalysis, crosswalk)
        return listOf(sceneSentence, pathSentence)
            .filter { it.isNotBlank() }
            .joinToString(" ")
    }

    private fun buildKoreanSceneSentence(
        caption: String,
        primaryAnalysis: FrameAnalysis,
    ): String {
        val detectedCues = primaryAnalysis.detections
            .sortedWith(
                compareBy<DetectedObjectResult> { it.distanceEstimate.distanceMeters ?: Float.MAX_VALUE }
                    .thenByDescending { it.confidence },
            )
            .mapNotNull(::detectedObjectCue)
            .distinct()
            .take(3)
        val captionCues = CAPTION_KEYWORDS
            .filter { keyword -> keyword.matches(caption) }
            .map { keyword -> keyword.koreanLabel }
            .filterNot { label -> detectedCues.any { cue -> cue.contains(label) } }
            .distinct()
            .take(3)
        val cues = (detectedCues + captionCues).take(4)

        return when {
            cues.isNotEmpty() -> "카메라 방향에 ${cues.joinToString(", ")} 보입니다."
            caption.hasOutdoorCue() -> "카메라 방향에 도로나 보행 공간 같은 야외 장면이 보입니다."
            caption.hasIndoorCue() -> "카메라 방향에 실내 통로나 방 안쪽 장면이 보입니다."
            else -> "카메라 방향의 장면을 분석했습니다."
        }
    }

    private fun buildPathSentence(
        primaryAnalysis: FrameAnalysis,
        crosswalk: CrosswalkPatternResult,
    ): String {
        val metrics = primaryAnalysis.pathMetrics
        val collisionDistance = metrics?.collisionDistanceMeters ?: metrics?.centerObstacleMeters
        return when {
            crosswalk.detected -> "바닥에는 횡단보도 무늬가 보입니다."
            collisionDistance != null && collisionDistance < 1.2f -> "앞쪽 통로가 가까운 장애물로 좁아 보입니다."
            metrics?.likelyWallAhead == true -> "정면에 벽처럼 막힌 면이 가까이 보일 수 있습니다."
            metrics?.pathClearMeters != null && metrics.pathClearMeters >= 1.8f -> "앞쪽 보행 공간은 비교적 열려 있습니다."
            primaryAnalysis.floorSegmentation?.confidence != null &&
                primaryAnalysis.floorSegmentation.confidence >= 0.55f ->
                "바닥 영역이 비교적 안정적으로 감지됩니다."
            else -> "통로 상태는 아직 확실하지 않습니다."
        }
    }

    private fun detectedObjectCue(detection: DetectedObjectResult): String? {
        val label = objectLabelToKorean(detection.label) ?: return null
        val centerX = ((detection.boundingBox.left + detection.boundingBox.right) * 0.5f) /
            detection.imageWidth.coerceAtLeast(1)
        val lane = when {
            centerX < 0.38f -> "왼쪽"
            centerX > 0.62f -> "오른쪽"
            else -> "중앙"
        }
        return "$lane $label"
    }

    private fun objectLabelToKorean(label: String): String? {
        return when (label.lowercase()) {
            "person" -> "사람"
            "bicycle" -> "자전거"
            "car" -> "차량"
            "motorcycle" -> "오토바이"
            "bus" -> "버스"
            "truck" -> "트럭"
            "chair" -> "의자"
            "bench" -> "벤치"
            "traffic light" -> "신호등"
            "stop sign" -> "정지 표지판"
            "crosswalk" -> "횡단보도"
            "door" -> "문"
            "stairs", "stair" -> "계단"
            "curb" -> "턱"
            "traffic cone", "cone" -> "안전 콘"
            else -> null
        }
    }

    private fun String.cleanCaption(): String {
        return replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim('.', '!', '?', ',', ';', ':')
    }

    private fun String.takeSentences(maxSentences: Int): String {
        val normalized = replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return normalized
        val sentences = Regex("[^.!?。！？]+[.!?。！？]?")
            .findAll(normalized)
            .map { it.value.trim() }
            .filter { it.isNotBlank() }
            .take(maxSentences)
            .toList()
        return sentences.joinToString(" ").ifBlank { normalized }
    }

    private fun String.containsHangul(): Boolean {
        return any { it in '\uAC00'..'\uD7A3' }
    }

    private fun String.hasOutdoorCue(): Boolean {
        return OUTDOOR_REGEX.containsMatchIn(this)
    }

    private fun String.hasIndoorCue(): Boolean {
        return INDOOR_REGEX.containsMatchIn(this)
    }

    private data class CaptionKeyword(
        val pattern: Regex,
        val koreanLabel: String,
    ) {
        fun matches(text: String): Boolean = pattern.containsMatchIn(text)
    }

    private val CAPTION_KEYWORDS = listOf(
        CaptionKeyword(Regex("\\b(people|persons|person|man|woman|child|children)\\b", RegexOption.IGNORE_CASE), "사람"),
        CaptionKeyword(Regex("\\b(car|cars|vehicle|vehicles|taxi)\\b", RegexOption.IGNORE_CASE), "차량"),
        CaptionKeyword(Regex("\\b(bus|buses)\\b", RegexOption.IGNORE_CASE), "버스"),
        CaptionKeyword(Regex("\\b(bicycle|bike|cyclist)\\b", RegexOption.IGNORE_CASE), "자전거"),
        CaptionKeyword(Regex("\\b(motorcycle|scooter)\\b", RegexOption.IGNORE_CASE), "오토바이"),
        CaptionKeyword(Regex("\\b(crosswalk|zebra crossing|pedestrian crossing)\\b", RegexOption.IGNORE_CASE), "횡단보도"),
        CaptionKeyword(Regex("\\b(stair|stairs|steps|staircase)\\b", RegexOption.IGNORE_CASE), "계단"),
        CaptionKeyword(Regex("\\b(door|entrance|gate)\\b", RegexOption.IGNORE_CASE), "문"),
        CaptionKeyword(Regex("\\b(sign|signage|traffic sign)\\b", RegexOption.IGNORE_CASE), "표지판"),
        CaptionKeyword(Regex("\\b(traffic light|signal light)\\b", RegexOption.IGNORE_CASE), "신호등"),
        CaptionKeyword(Regex("\\b(sidewalk|pavement|walkway|path|hallway|corridor)\\b", RegexOption.IGNORE_CASE), "통로"),
        CaptionKeyword(Regex("\\b(road|street|lane)\\b", RegexOption.IGNORE_CASE), "도로"),
        CaptionKeyword(Regex("\\b(bench|chair|table)\\b", RegexOption.IGNORE_CASE), "가구"),
        CaptionKeyword(Regex("\\b(wall|fence|barrier)\\b", RegexOption.IGNORE_CASE), "막힌 면"),
        CaptionKeyword(Regex("\\b(cone|traffic cone|construction cone)\\b", RegexOption.IGNORE_CASE), "안전 콘"),
        CaptionKeyword(Regex("\\b(curb|kerb)\\b", RegexOption.IGNORE_CASE), "턱"),
    )
    private val OUTDOOR_REGEX = Regex("\\b(street|road|sidewalk|crosswalk|vehicle|traffic|building)\\b", RegexOption.IGNORE_CASE)
    private val INDOOR_REGEX = Regex("\\b(room|hallway|corridor|indoor|floor|door|wall|stairs)\\b", RegexOption.IGNORE_CASE)
}
