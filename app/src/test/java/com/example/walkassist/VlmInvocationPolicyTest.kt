package com.example.walkassist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VlmInvocationPolicyTest {
    @Test
    fun invokesPeriodicallyWithoutPriorityCue() {
        val policy = VlmInvocationPolicy(
            minIntervalMillis = 1_500L,
            periodicIntervalMillis = 5_000L,
        )

        assertTrue(policy.shouldInvoke(timestampMillis = 1_000L, hasPriorityCue = false))
        assertFalse(policy.shouldInvoke(timestampMillis = 2_000L, hasPriorityCue = false))
        assertFalse(policy.shouldInvoke(timestampMillis = 5_000L, hasPriorityCue = false))
        assertTrue(policy.shouldInvoke(timestampMillis = 6_000L, hasPriorityCue = false))
    }

    @Test
    fun priorityCueBypassesPeriodicIntervalAfterMinimumGap() {
        val policy = VlmInvocationPolicy(
            minIntervalMillis = 1_500L,
            periodicIntervalMillis = 5_000L,
        )

        assertTrue(policy.shouldInvoke(timestampMillis = 1_000L, hasPriorityCue = false))
        assertFalse(policy.shouldInvoke(timestampMillis = 2_000L, hasPriorityCue = true))
        assertTrue(policy.shouldInvoke(timestampMillis = 2_600L, hasPriorityCue = true))
    }
}
