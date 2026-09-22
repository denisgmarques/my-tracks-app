package com.mytracksapp.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T04 — verifies [SamplingInterval] represents EXACTLY the 9 values RF-01/UI-01 allow, no more
 * and no fewer (RF-01 AC: "qualquer valor fora do conjunto é rejeitado").
 */
class SamplingIntervalTest {

    @Test
    fun `exactly nine members exist`() {
        assertEquals(9, SamplingInterval.entries.size)
    }

    @Test
    fun `member seconds values are exactly the RF-01 allowed set`() {
        val expected = setOf(3, 5, 10, 15, 30, 45, 60, 120, 180)
        val actual = SamplingInterval.entries.map { it.seconds }.toSet()
        assertEquals(expected, actual)
    }

    @Test
    fun `ALLOWED_SECONDS matches the RF-01 set exactly`() {
        val expected = setOf(3, 5, 10, 15, 30, 45, 60, 120, 180)
        assertEquals(expected, SamplingInterval.ALLOWED_SECONDS)
    }

    @Test
    fun `no duplicate seconds values`() {
        val seconds = SamplingInterval.entries.map { it.seconds }
        assertEquals(seconds.size, seconds.toSet().size)
    }

    @Test
    fun `no member falls outside the allowed set`() {
        val allowed = setOf(3, 5, 10, 15, 30, 45, 60, 120, 180)
        SamplingInterval.entries.forEach { interval ->
            assertTrue(
                "Unexpected SamplingInterval value: ${interval.seconds}",
                interval.seconds in allowed,
            )
        }
    }
}
