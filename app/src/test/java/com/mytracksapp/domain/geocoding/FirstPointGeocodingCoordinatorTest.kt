package com.mytracksapp.domain.geocoding

import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ReverseGeocoder] fake: returns [result], optionally after [delayMillis]. */
private class FakeReverseGeocoder(
    private val result: String? = null,
    private val delayMillis: Long = 0L,
) : ReverseGeocoder {
    override suspend fun reverseGeocode(latitude: Double, longitude: Double): String? {
        if (delayMillis > 0L) delay(delayMillis)
        return result
    }
}

/**
 * [TrackingSessionDao] fake recording every [updateLocationName] call, with a [latch] the test
 * can wait on to observe the background coroutine actually completing without depending on
 * virtual-time schedulers that can't drive a real background dispatcher.
 */
private class FakeTrackingSessionDao : TrackingSessionDao {
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
    override suspend fun getFinishedSessionsWithoutLocationNameSince(
        status: SessionStatus,
        sinceTimestamp: Long,
    ): List<TrackingSessionEntity> = error("not used in this test")

    override suspend fun updateLocationName(sessionId: String, locationName: String?) {
        updateLocationNameCalls += sessionId to locationName
        latch.countDown()
    }
}

/** [Logger] fake recording every [log] call — unused by these tests, kept for fake-compilation parity. */
private class FakeLogger : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = java.util.concurrent.CopyOnWriteArrayList<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/**
 * T03 — [FirstPointGeocodingCoordinator] unit tests (RF-03, RNF-01): now a thin delegation/wiring
 * test over a real [GeocodeAndPersist] backed by this file's fakes. The detailed failure-mode
 * cases (exception handling, null/blank results, persistence failure) are covered by
 * `GeocodeAndPersistTest.kt` and are intentionally not duplicated here — this file only asserts
 * the externally observable delegation contract.
 */
class FirstPointGeocodingCoordinatorTest {

    @Test
    fun `successful geocode results in exactly one updateLocationName call with the geocoded name`() {
        val dao = FakeTrackingSessionDao()
        val geocodeAndPersist = GeocodeAndPersist(
            reverseGeocoder = FakeReverseGeocoder(result = "Sao Paulo"),
            trackingSessionDao = dao,
            logger = FakeLogger(),
        )
        val coordinator = FirstPointGeocodingCoordinator(
            geocodeAndPersist = geocodeAndPersist,
            coroutineScope = CoroutineScope(Dispatchers.Default),
        )

        coordinator.onFirstPointRecorded("session-1", 1.0, 2.0)

        assertTrue("updateLocationName should eventually be called", dao.latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("session-1" to "Sao Paulo"), dao.updateLocationNameCalls)
    }

    @Test
    fun `onFirstPointRecorded returns before the geocodeAndPersist attempt completes`() {
        val dao = FakeTrackingSessionDao()
        val geocodeAndPersist = GeocodeAndPersist(
            reverseGeocoder = FakeReverseGeocoder(result = "Delayed City", delayMillis = 500L),
            trackingSessionDao = dao,
            logger = FakeLogger(),
        )
        val coordinator = FirstPointGeocodingCoordinator(
            geocodeAndPersist = geocodeAndPersist,
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
}
