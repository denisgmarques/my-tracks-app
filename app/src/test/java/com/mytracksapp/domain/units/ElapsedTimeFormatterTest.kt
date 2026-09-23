package com.mytracksapp.domain.units

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Verifies [ElapsedTimeFormatter]'s documented rule: `m:ss` under 1 hour (unpadded minutes,
 * zero-padded seconds), `h:mm:ss` at/above 1 hour (unpadded hours, zero-padded minutes and
 * seconds), with the switch point at exactly 3_600_000ms tested explicitly.
 */
class ElapsedTimeFormatterTest {

    @Test
    fun `zero millis formats as 0-00`() {
        assertEquals("0:00", ElapsedTimeFormatter.format(0L))
    }

    @Test
    fun `sub-minute durations zero-pad seconds`() {
        assertEquals("0:05", ElapsedTimeFormatter.format(5_000L))
        assertEquals("0:45", ElapsedTimeFormatter.format(45_000L))
        assertEquals("0:09", ElapsedTimeFormatter.format(9_000L))
    }

    @Test
    fun `sub-hour durations render minutes unpadded`() {
        assertEquals("1:05", ElapsedTimeFormatter.format(65_000L))
        assertEquals("9:59", ElapsedTimeFormatter.format(599_000L))
        assertEquals("59:59", ElapsedTimeFormatter.format(3_599_000L))
    }

    @Test
    fun `exactly one hour boundary switches to h-mm-ss format`() {
        // The documented switch point: 3_600_000ms is the first value using h:mm:ss, not m:ss.
        assertEquals("1:00:00", ElapsedTimeFormatter.format(3_600_000L))
    }

    @Test
    fun `just under one hour boundary still uses m-ss format`() {
        assertEquals("59:59", ElapsedTimeFormatter.format(3_599_999L))
    }

    @Test
    fun `hour-and-above durations zero-pad minutes and seconds but not hours`() {
        assertEquals("1:01:01", ElapsedTimeFormatter.format(3_661_000L))
        assertEquals("2:02:05", ElapsedTimeFormatter.format(7_325_000L))
        assertEquals("10:00:00", ElapsedTimeFormatter.format(36_000_000L))
    }

    @Test
    fun `milliseconds are truncated down to whole seconds, not rounded`() {
        assertEquals("0:05", ElapsedTimeFormatter.format(5_999L))
        assertEquals("1:00:00", ElapsedTimeFormatter.format(3_600_999L))
    }

    @Test
    fun `negative input is treated as zero`() {
        assertEquals("0:00", ElapsedTimeFormatter.format(-5_000L))
    }
}
