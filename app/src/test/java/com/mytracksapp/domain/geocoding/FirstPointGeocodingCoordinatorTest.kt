package com.mytracksapp.domain.geocoding

import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ReverseGeocoder] fake: returns [result] or throws [exception], optionally after [delayMillis]. */
private class FakeReverseGeocoder(
    private val result: String? = null,
    private val exception: Throwable? = null,
    private val delayMillis: Long = 0L,
) : ReverseGeocoder {
    override suspend fun reverseGeocode(latitude: Double, longitude: Double): String? {
        if (delayMillis > 0L) delay(delayMillis)
        exception?.let { throw it }
        return result
    }
}

/**
 * [TrackingSessionDao] fake recording every [updateLocationName] call, with a [latch] the test
 * can wait on to observe the background coroutine actually completing (or time out proving it
 * never will, for the "no write on failure" cases) without depending on virtual-time schedulers
 * that can't drive a real background dispatcher.
 */
private class FakeTrackingSessionDao(private val updateLocationNameFailure: Throwable? = null) : TrackingSessionDao {
    val updateLocationNameCalls = mutableListOf<Pair<String, String?>>()
    val latch = CountDownLatch(1)

    override suspend fun insert(session: TrackingSessionEntity) = error("not used in this test")
    override suspend fun update(session: TrackingSessionEntity) = error("not used in this test")
    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> = error("not used in this test")
    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = error("not used in this test")
    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
        error("not used in this test")
    override suspend fun deleteById(sessionId: String) = error("not used in this test")
    override suspend fun deleteAll() = error("not used in this test")

    override suspend fun updateLocationName(sessionId: String, locationName: String?) {
        try {
            updateLocationNameFailure?.let { throw it }
            updateLocationNameCalls += sessionId to locationName
        } finally {
            latch.countDown()
        }
    }
}

/** [Logger] fake recording every [log] call, with a [latch] to await the background coroutine's log. */
private class FakeLogger : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = java.util.concurrent.CopyOnWriteArrayList<Entry>()
    val latch = CountDownLatch(1)

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
        latch.countDown()
    }
}

/**
 * T06 — [FirstPointGeocodingCoordinator] unit tests (RF-01, RF-02, RF-03, RNF-01): plain JVM,
 * real background dispatcher ([Dispatchers.Default]) so "returns before the suspend function
 * completes" is a genuine timing assertion, not a virtual-time artifact.
 */
class FirstPointGeocodingCoordinatorTest {

    @Test
    fun `successful geocode results in exactly one updateLocationName call with the geocoded name`() {
        val dao = FakeTrackingSessionDao()
        val coordinator = FirstPointGeocodingCoordinator(
            reverseGeocoder = FakeReverseGeocoder(result = "Sao Paulo"),
            trackingSessionDao = dao,
            coroutineScope = CoroutineScope(Dispatchers.Default),
        )

        coordinator.onFirstPointRecorded("session-1", 1.0, 2.0)

        assertTrue("updateLocationName should eventually be called", dao.latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("session-1" to "Sao Paulo"), dao.updateLocationNameCalls)
    }

    @Test
    fun `exception from reverseGeocoder results in zero updateLocationName calls and no propagated exception`() {
        val dao = FakeTrackingSessionDao()
        val coordinator = FirstPointGeocodingCoordinator(
            reverseGeocoder = FakeReverseGeocoder(exception = IOException("no network")),
            trackingSessionDao = dao,
            coroutineScope = CoroutineScope(Dispatchers.Default),
        )

        // Calling this must not throw to the caller even though the geocoder will fail.
        coordinator.onFirstPointRecorded("session-1", 1.0, 2.0)

        // The latch never counts down because updateLocationName is never called; a timeout here
        // (rather than a hang) is the expected, asserted outcome.
        assertTrue(!dao.latch.await(300, TimeUnit.MILLISECONDS))
        assertTrue(dao.updateLocationNameCalls.isEmpty())
    }

    @Test
    fun `null or blank result from reverseGeocoder results in zero updateLocationName calls`() {
        val dao = FakeTrackingSessionDao()
        val coordinatorNull = FirstPointGeocodingCoordinator(
            reverseGeocoder = FakeReverseGeocoder(result = null),
            trackingSessionDao = dao,
            coroutineScope = CoroutineScope(Dispatchers.Default),
        )
        coordinatorNull.onFirstPointRecorded("session-null", 1.0, 2.0)
        assertTrue(!dao.latch.await(300, TimeUnit.MILLISECONDS))

        val blankDao = FakeTrackingSessionDao()
        val coordinatorBlank = FirstPointGeocodingCoordinator(
            reverseGeocoder = FakeReverseGeocoder(result = "   "),
            trackingSessionDao = blankDao,
            coroutineScope = CoroutineScope(Dispatchers.Default),
        )
        coordinatorBlank.onFirstPointRecorded("session-blank", 1.0, 2.0)
        assertTrue(!blankDao.latch.await(300, TimeUnit.MILLISECONDS))

        assertTrue(dao.updateLocationNameCalls.isEmpty())
        assertTrue(blankDao.updateLocationNameCalls.isEmpty())
    }

    @Test
    fun `onFirstPointRecorded returns before the reverseGeocoder suspend function completes`() {
        val dao = FakeTrackingSessionDao()
        val coordinator = FirstPointGeocodingCoordinator(
            reverseGeocoder = FakeReverseGeocoder(result = "Delayed City", delayMillis = 500L),
            trackingSessionDao = dao,
            coroutineScope = CoroutineScope(Dispatchers.Default),
        )

        val startedAtNanos = System.nanoTime()
        coordinator.onFirstPointRecorded("session-1", 1.0, 2.0)
        val elapsedMillis = (System.nanoTime() - startedAtNanos) / 1_000_000

        assertTrue(
            "onFirstPointRecorded should return immediately, took ${elapsedMillis}ms",
            elapsedMillis < 200L,
        )
        assertTrue("the geocode should still eventually complete in the background", dao.latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("session-1" to "Delayed City"), dao.updateLocationNameCalls)
    }

    @Test
    fun `exception from updateLocationName is logged and does not propagate`() {
        val dao = FakeTrackingSessionDao(updateLocationNameFailure = IllegalStateException("db closed"))
        val logger = FakeLogger()
        val coordinator = FirstPointGeocodingCoordinator(
            reverseGeocoder = FakeReverseGeocoder(result = "Sao Paulo"),
            trackingSessionDao = dao,
            coroutineScope = CoroutineScope(Dispatchers.Default),
            logger = logger,
        )

        // Calling this must not throw to the caller even though updateLocationName will fail.
        coordinator.onFirstPointRecorded("session-1", 1.0, 2.0)

        assertTrue("updateLocationName should eventually be attempted", dao.latch.await(2, TimeUnit.SECONDS))
        assertTrue("the failure should eventually be logged", logger.latch.await(2, TimeUnit.SECONDS))
        assertEquals(1, logger.entries.size)
        val entry = logger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("FirstPointGeocodingCoordinator", entry.tag)
        assertTrue(entry.throwable is IllegalStateException)
        assertTrue(dao.updateLocationNameCalls.isEmpty())
    }
}
