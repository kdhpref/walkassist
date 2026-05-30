package com.example.walkassist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CrosswalkPatternDetectorTest {
    private val detector = CrosswalkPatternDetector()

    @Test
    fun mapCueBoostsWeakButVisibleStripePattern() {
        val result = detector.fuse(
            pattern = CrosswalkPatternResult(
                detected = false,
                score = 0.38f,
                stripeCount = 2,
                yoloConfidence = 0f,
                modeLabel = "image-pattern",
            ),
            mapCue = CrosswalkMapCue(
                active = true,
                confidence = 0.72f,
                distanceMeters = 12f,
                headingDeltaDegrees = 18f,
            ),
            previousScore = 0.45f,
        )

        assertTrue(result.detected)
        assertTrue(result.score >= 0.52f)
        assertEquals("map+image-pattern", result.modeLabel)
    }

    @Test
    fun mapCueAloneDoesNotConfirmCrosswalk() {
        val result = detector.fuse(
            pattern = CrosswalkPatternResult(
                detected = false,
                score = 0.08f,
                stripeCount = 0,
                yoloConfidence = 0f,
                modeLabel = "none",
            ),
            mapCue = CrosswalkMapCue(
                active = true,
                confidence = 0.85f,
                distanceMeters = 6f,
                headingDeltaDegrees = 5f,
            ),
            previousScore = 0f,
        )

        assertFalse(result.detected)
        assertEquals("map-candidate", result.modeLabel)
    }
}
