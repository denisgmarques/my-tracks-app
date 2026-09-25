package com.mytracksapp.domain.geocoding

import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [ReverseGeocoder] fake: returns [result] or throws [exception]. */
private class FakeReverseGeocoderForGeocodeAndPersist(
    private val result: String? = null,
    private val exception: Throwable? = null,
) : ReverseGeocoder {
    override suspend fun reverseGeocode(latitude: Double, longitude: Double): String? {
        exception?.let { throw it }
        return result
    }
}

/** [TrackingSessionDao] fake recording every [updateLocationName] call. */
private class FakeTrackingSessionDaoForGeocodeAndPersist(private val updateLocationNameFailure: Throwable? = null) : TrackingSessionDao {
    val updateLocationNameCalls = mutableListOf<Pair<String, String?>>()

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
        updateLocationNameFailure?.let { throw it }
        updateLocationNameCalls += sessionId to locationName
    }
}

/** [Logger] fake recording every [log] call. */
private class FakeLoggerForGeocodeAndPersist : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/**
 * T02 — [GeocodeAndPersist] unit tests (RF-03, RF-06): migrated verbatim (per behavior, not
 * literal test-method shape — [attempt] is a plain `suspend fun`, not the fire-and-forget
 * `onFirstPointRecorded` this logic was extracted from, so no [CoroutineScope]/latch machinery is
 * needed) from `FirstPointGeocodingCoordinatorTest.kt`'s already-passing cases.
 */
class GeocodeAndPersistTest {

    @Test
    fun `successful geocode persists once and returns true`() = runBlocking {
        val dao = FakeTrackingSessionDaoForGeocodeAndPersist()
        val geocodeAndPersist = GeocodeAndPersist(
            reverseGeocoder = FakeReverseGeocoderForGeocodeAndPersist(result = "Sao Paulo"),
            trackingSessionDao = dao,
        )

        val result = geocodeAndPersist.attempt("session-1", 1.0, 2.0)

        assertTrue(result)
        assertEquals(listOf("session-1" to "Sao Paulo"), dao.updateLocationNameCalls)
    }

    @Test
    fun `exception from reverseGeocoder results in zero persists, no propagated exception, one logged WARN entry`() =
        runBlocking {
            val dao = FakeTrackingSessionDaoForGeocodeAndPersist()
            val logger = FakeLoggerForGeocodeAndPersist()
            val geocodeAndPersist = GeocodeAndPersist(
                reverseGeocoder = FakeReverseGeocoderForGeocodeAndPersist(exception = IOException("no network")),
                trackingSessionDao = dao,
                logger = logger,
            )

            // Must not throw to the caller even though the geocoder fails.
            val result = geocodeAndPersist.attempt("session-1", 1.0, 2.0)

            assertFalse(result)
            assertTrue(dao.updateLocationNameCalls.isEmpty())
            assertEquals(1, logger.entries.size)
            val entry = logger.entries.single()
            assertEquals(LogLevel.WARN, entry.level)
            assertEquals("GeocodeAndPersist", entry.tag)
            assertTrue(entry.throwable is IOException)
        }

    @Test
    fun `null result from reverseGeocoder results in zero persists`() = runBlocking {
        val dao = FakeTrackingSessionDaoForGeocodeAndPersist()
        val geocodeAndPersist = GeocodeAndPersist(
            reverseGeocoder = FakeReverseGeocoderForGeocodeAndPersist(result = null),
            trackingSessionDao = dao,
        )

        val result = geocodeAndPersist.attempt("session-null", 1.0, 2.0)

        assertFalse(result)
        assertTrue(dao.updateLocationNameCalls.isEmpty())
    }

    @Test
    fun `blank result from reverseGeocoder results in zero persists`() = runBlocking {
        val dao = FakeTrackingSessionDaoForGeocodeAndPersist()
        val geocodeAndPersist = GeocodeAndPersist(
            reverseGeocoder = FakeReverseGeocoderForGeocodeAndPersist(result = "   "),
            trackingSessionDao = dao,
        )

        val result = geocodeAndPersist.attempt("session-blank", 1.0, 2.0)

        assertFalse(result)
        assertTrue(dao.updateLocationNameCalls.isEmpty())
    }

    @Test
    fun `exception from updateLocationName is logged at ERROR, swallowed, and attempt returns false`() = runBlocking {
        val dao = FakeTrackingSessionDaoForGeocodeAndPersist(updateLocationNameFailure = IllegalStateException("db closed"))
        val logger = FakeLoggerForGeocodeAndPersist()
        val geocodeAndPersist = GeocodeAndPersist(
            reverseGeocoder = FakeReverseGeocoderForGeocodeAndPersist(result = "Sao Paulo"),
            trackingSessionDao = dao,
            logger = logger,
        )

        // Must not throw to the caller even though updateLocationName fails.
        val result = geocodeAndPersist.attempt("session-1", 1.0, 2.0)

        assertFalse(result)
        assertTrue(dao.updateLocationNameCalls.isEmpty())
        assertEquals(1, logger.entries.size)
        val entry = logger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("GeocodeAndPersist", entry.tag)
        assertTrue(entry.throwable is IllegalStateException)
    }
}
