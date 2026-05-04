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
                "construction sign ahead",
            ),
        )

        assertEquals(listOf("사람 감지", "construction sign ahead"), cues)
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
                pathSummary = "전방에 임시 표지판이 있어 통로가 좁습니다",
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
}
