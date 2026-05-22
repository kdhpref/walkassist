package com.example.walkassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmWalkingAnnouncementFormatterTest {
    @Test
    fun hidesInternalEvidenceFromTtsCues() {
        val cues = VlmWalkingAnnouncementFormatter.userVisibleEvidence(
            listOf(
                "source=live_camera",
                "pathClear=2.0m",
                "aicore=unavailable",
                "person_detected",
                "construction sign",
            ),
        )

        assertEquals(listOf("사람 감지", "공사 표지판"), cues)
    }

    @Test
    fun announcementDoesNotSpeakDebugKeys() {
        val message = VlmWalkingAnnouncementFormatter.build(
            interpretation = VlmSceneInterpretation(
                modelName = "test",
                schemaVersion = 1,
                risk = VlmWalkingRisk.CAUTION,
                suggestedAction = VlmSuggestedAction.SLOW_DOWN,
                confidence = 0.8f,
                pathSummary = "앞쪽에 임시 표지판이 있어 통로가 좁습니다",
                evidence = listOf("source=live_camera", "pathClear=1.0m", "공사 표지판"),
            ),
            primaryAnalysis = FrameAnalysis(
                detections = emptyList(),
                nearestObstacle = null,
            ),
            crosswalk = CrosswalkPatternResult(
                detected = false,
                score = 0f,
                stripeCount = 0,
                yoloConfidence = 0f,
                modeLabel = "test",
            ),
        )

        assertTrue(message.contains("공사 표지판"))
        assertFalse(message.contains("source="))
        assertFalse(message.contains("pathClear="))
    }

    @Test
    fun announcementDoesNotTellUserToDirectlyCheckScene() {
        val message = VlmWalkingAnnouncementFormatter.build(
            interpretation = VlmSceneInterpretation(
                modelName = "test",
                schemaVersion = 1,
                risk = VlmWalkingRisk.UNKNOWN,
                suggestedAction = VlmSuggestedAction.UNKNOWN,
                confidence = 0.3f,
                pathSummary = "주변을 직접 확인하세요. 카메라 방향 앞쪽에 문이 있습니다.",
                evidence = listOf("직접 확인하세요", "door"),
            ),
            primaryAnalysis = FrameAnalysis(
                detections = emptyList(),
                nearestObstacle = null,
            ),
            crosswalk = CrosswalkPatternResult(
                detected = false,
                score = 0f,
                stripeCount = 0,
                yoloConfidence = 0f,
                modeLabel = "test",
            ),
        )

        assertTrue(message.contains("문"))
        assertFalse(message.contains("직접 확인"))
        assertFalse(message.contains("확인하세요"))
        assertFalse(message.contains("check yourself", ignoreCase = true))
        assertFalse(message.contains("look around", ignoreCase = true))
    }

    @Test
    fun announcementStartsWithCameraSceneDescription() {
        val message = VlmWalkingAnnouncementFormatter.build(
            interpretation = VlmSceneInterpretation(
                modelName = "test",
                schemaVersion = 1,
                risk = VlmWalkingRisk.CLEAR,
                suggestedAction = VlmSuggestedAction.PROCEED,
                confidence = 0.82f,
                pathSummary = "카메라 방향 앞쪽에 문이 있습니다",
                evidence = listOf("문"),
            ),
            primaryAnalysis = FrameAnalysis(
                detections = emptyList(),
                nearestObstacle = null,
            ),
            crosswalk = CrosswalkPatternResult(
                detected = false,
                score = 0f,
                stripeCount = 0,
                yoloConfidence = 0f,
                modeLabel = "test",
            ),
        )

        assertTrue(message.startsWith("카메라 방향 앞쪽에 문이 있습니다."))
    }
}
