package com.mytracksapp.service

import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [Logger] double — records every logged entry for assertions (T05). */
private class FakeLoggerForSampleDelivery : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/**
 * T05 (RF-05) — [deliverSample] unit tests, called directly rather than through a real Play
 * Services `LocationCallback`/`LocationResult` — matching the existing "not directly unit tested"
 * precedent noted in `AndroidReverseGeocoder.kt`'s doc for [FusedLocationSampleSource]. Pure JVM.
 */
class FusedLocationSampleSourceDeliveryTest {

    private val sample = LocationSample(latitude = 1.0, longitude = 2.0, accuracy = 5f, timestamp = 1_000L)

    @Test
    fun `a failing delivery is logged exactly once and does not throw out of deliverSample`() = runBlocking {
        val fakeLogger = FakeLoggerForSampleDelivery()
        val failure = IllegalStateException("delivery failed")

        deliverSample(
            sample = sample,
            onLocation = { throw failure },
            logger = fakeLogger,
        )

        assertEquals(1, fakeLogger.entries.size)
        val entry = fakeLogger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("FusedLocationSampleSource", entry.tag)
        assertSame(failure, entry.throwable)
    }

    @Test
    fun `a subsequent non-throwing delivery still completes and its effect is observed`() = runBlocking {
        val fakeLogger = FakeLoggerForSampleDelivery()
        val delivered = mutableListOf<LocationSample>()

        // First delivery fails and is contained inside deliverSample...
        deliverSample(
            sample = sample,
            onLocation = { throw IllegalStateException("delivery failed") },
            logger = fakeLogger,
        )

        // ...a second, non-throwing delivery still runs to completion and its effect is observed.
        val secondSample = sample.copy(timestamp = 2_000L)
        deliverSample(
            sample = secondSample,
            onLocation = { delivered += it },
            logger = fakeLogger,
        )

        assertEquals(1, fakeLogger.entries.size)
        assertEquals(listOf(secondSample), delivered)
    }

    @Test
    fun `a non-throwing delivery logs nothing`() = runBlocking {
        val fakeLogger = FakeLoggerForSampleDelivery()
        var delivered: LocationSample? = null

        deliverSample(
            sample = sample,
            onLocation = { delivered = it },
            logger = fakeLogger,
        )

        assertTrue(fakeLogger.entries.isEmpty())
        assertEquals(sample, delivered)
    }
}
