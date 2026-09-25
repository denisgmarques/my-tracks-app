package com.mytracksapp.domain.geocoding

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * In-memory [TrackingSessionDao] double — same style as `OrphanedSessionRecoveryTest`'s
 * `RecoveryFakeTrackingSessionDao`. [getFinishedSessionsWithoutLocationNameSince] re-implements
 * T01's `@Query` predicate in-memory (`status == status && locationName == null && startTimestamp
 * >= sinceTimestamp`) so this stays a plain-JVM unit test of [GeocodingRetryOnStartup]'s own
 * orchestration logic; the SQL predicate itself is covered separately by
 * `TrackingSessionDaoTest.kt` (T01).
 */
private class RetryFakeTrackingSessionDao : TrackingSessionDao {
    private val sessions = mutableMapOf<String, TrackingSessionEntity>()
    val updateLocationNameCalls = mutableListOf<Pair<String, String?>>()

    fun seed(session: TrackingSessionEntity) {
        sessions[session.id] = session
    }

    fun sessionById(id: String): TrackingSessionEntity? = sessions[id]

    override suspend fun insert(session: TrackingSessionEntity) {
        sessions[session.id] = session
    }

    override suspend fun update(session: TrackingSessionEntity) {
        sessions[session.id] = session
    }

    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> =
        flowOf(sessions[sessionId])

    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> =
        flowOf(sessions.values.toList())

    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
        flowOf(sessions.values.filter { it.status == status })

    override suspend fun deleteById(sessionId: String) = error("not used in this test")
    override suspend fun deleteAll() = error("not used in this test")

    override suspend fun getFinishedSessionsWithoutLocationNameSince(
        status: SessionStatus,
        sinceTimestamp: Long,
    ): List<TrackingSessionEntity> =
        sessions.values.filter {
            it.status == status && it.locationName == null && it.startTimestamp >= sinceTimestamp
        }

    override suspend fun updateLocationName(sessionId: String, locationName: String?) {
        updateLocationNameCalls += sessionId to locationName
        sessions[sessionId]?.let { sessions[sessionId] = it.copy(locationName = locationName) }
    }
}

/**
 * In-memory [GpsPointDao] double. [failingSessionIds] simulates a session whose point lookup
 * throws (RF-06's "GpsPointDao throws" case) — the returned [Flow] throws on collection, exactly
 * like a real Room query failure would surface to a `.first()` caller.
 */
private class RetryFakeGpsPointDao(private val failingSessionIds: Set<String> = emptySet()) : GpsPointDao {
    private val pointsBySession = mutableMapOf<String, MutableList<GpsPointEntity>>()

    fun seed(sessionId: String, points: List<GpsPointEntity>) {
        pointsBySession.getOrPut(sessionId) { mutableListOf() }.addAll(points)
    }

    override suspend fun insert(point: GpsPointEntity): Long = error("not used in this test")
    override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> = error("not used in this test")

    override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> {
        if (sessionId in failingSessionIds) {
            return flow { throw RuntimeException("GpsPointDao failure for $sessionId") }
        }
        // Real DAO is `ORDER BY timestamp ASC` — mirror that ordering here too.
        return flowOf(pointsBySession[sessionId].orEmpty().sortedBy { it.timestamp })
    }

    override suspend fun countForSession(sessionId: String): Int = pointsBySession[sessionId]?.size ?: 0
}

/** [ReverseGeocoder] fake recording every lat/lon it was asked to resolve. */
private class FakeReverseGeocoderForRetry(private val result: String? = "Resolved Place") : ReverseGeocoder {
    val calls = mutableListOf<Pair<Double, Double>>()

    override suspend fun reverseGeocode(latitude: Double, longitude: Double): String? {
        calls += latitude to longitude
        return result
    }
}

/** [Logger] fake recording every [log] call. */
private class FakeLoggerForRetry : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/**
 * T05 — [GeocodingRetryOnStartup] unit tests (RF-01, RF-02, RF-04, RF-05, RF-06), using plain
 * in-memory doubles for [TrackingSessionDao]/[GpsPointDao]/[ReverseGeocoder] — same style as
 * `OrphanedSessionRecoveryTest.kt`. `now` is fixed and injected so the 24h-window boundary math is
 * deterministic.
 */
class GeocodingRetryOnStartupTest {

    private val fixedNow = 1_700_000_000_000L
    private val trackingSessionDao = RetryFakeTrackingSessionDao()

    private fun finishedSession(
        id: String,
        startTimestamp: Long,
        locationName: String? = null,
    ) = TrackingSessionEntity(
        id = id,
        samplingIntervalSeconds = 10,
        startTimestamp = startTimestamp,
        status = SessionStatus.FINISHED,
        locationName = locationName,
    )

    private fun point(sessionId: String, timestamp: Long, latitude: Double, longitude: Double) =
        GpsPointEntity(sessionId = sessionId, timestamp = timestamp, latitude = latitude, longitude = longitude, accuracy = 5f)

    @Test
    fun `eligible session is geocoded and persisted`() = runBlocking {
        trackingSessionDao.seed(finishedSession("session-1", startTimestamp = fixedNow - 3_600_000L))
        val gpsPointDao = RetryFakeGpsPointDao().apply {
            seed("session-1", listOf(point("session-1", timestamp = 0L, latitude = 10.0, longitude = 20.0)))
        }
        val reverseGeocoder = FakeReverseGeocoderForRetry(result = "Downtown")
        val geocodeAndPersist = GeocodeAndPersist(reverseGeocoder, trackingSessionDao)
        val retry = GeocodingRetryOnStartup(trackingSessionDao, gpsPointDao, geocodeAndPersist, now = { fixedNow })

        val count = retry.retry()

        assertEquals(1, count)
        assertEquals(listOf(10.0 to 20.0), reverseGeocoder.calls)
        assertEquals(listOf("session-1" to "Downtown"), trackingSessionDao.updateLocationNameCalls)
        assertEquals("Downtown", trackingSessionDao.sessionById("session-1")?.locationName)
    }

    @Test
    fun `multi-point session is geocoded using the smallest-timestamp point`() = runBlocking {
        trackingSessionDao.seed(finishedSession("session-2", startTimestamp = fixedNow - 3_600_000L))
        val gpsPointDao = RetryFakeGpsPointDao().apply {
            seed(
                "session-2",
                listOf(
                    point("session-2", timestamp = 5_000L, latitude = 2.0, longitude = 2.0),
                    point("session-2", timestamp = 1_000L, latitude = 1.0, longitude = 1.0),
                    point("session-2", timestamp = 9_000L, latitude = 3.0, longitude = 3.0),
                ),
            )
        }
        val reverseGeocoder = FakeReverseGeocoderForRetry(result = "Somewhere")
        val geocodeAndPersist = GeocodeAndPersist(reverseGeocoder, trackingSessionDao)
        val retry = GeocodingRetryOnStartup(trackingSessionDao, gpsPointDao, geocodeAndPersist, now = { fixedNow })

        val count = retry.retry()

        assertEquals(1, count)
        assertEquals(listOf(1.0 to 1.0), reverseGeocoder.calls)
    }

    @Test
    fun `session outside the 24h window is never touched`() = runBlocking {
        trackingSessionDao.seed(
            finishedSession("session-old", startTimestamp = fixedNow - 25 * 3_600_000L),
        )
        val gpsPointDao = RetryFakeGpsPointDao().apply {
            seed("session-old", listOf(point("session-old", timestamp = 0L, latitude = 5.0, longitude = 5.0)))
        }
        val reverseGeocoder = FakeReverseGeocoderForRetry()
        val geocodeAndPersist = GeocodeAndPersist(reverseGeocoder, trackingSessionDao)
        val retry = GeocodingRetryOnStartup(trackingSessionDao, gpsPointDao, geocodeAndPersist, now = { fixedNow })

        val count = retry.retry()

        assertEquals(0, count)
        assertTrue(reverseGeocoder.calls.isEmpty())
        assertTrue(trackingSessionDao.updateLocationNameCalls.isEmpty())
    }

    @Test
    fun `session with an existing locationName is left alone`() = runBlocking {
        trackingSessionDao.seed(
            finishedSession("session-named", startTimestamp = fixedNow - 3_600_000L, locationName = "Already Known"),
        )
        val gpsPointDao = RetryFakeGpsPointDao().apply {
            seed("session-named", listOf(point("session-named", timestamp = 0L, latitude = 5.0, longitude = 5.0)))
        }
        val reverseGeocoder = FakeReverseGeocoderForRetry()
        val geocodeAndPersist = GeocodeAndPersist(reverseGeocoder, trackingSessionDao)
        val retry = GeocodingRetryOnStartup(trackingSessionDao, gpsPointDao, geocodeAndPersist, now = { fixedNow })

        val count = retry.retry()

        assertEquals(0, count)
        assertTrue(reverseGeocoder.calls.isEmpty())
        assertTrue(trackingSessionDao.updateLocationNameCalls.isEmpty())
        assertEquals("Already Known", trackingSessionDao.sessionById("session-named")?.locationName)
    }

    @Test
    fun `session with no GPS points is logged and skipped`() = runBlocking {
        trackingSessionDao.seed(finishedSession("session-empty", startTimestamp = fixedNow - 3_600_000L))
        val gpsPointDao = RetryFakeGpsPointDao() // no points seeded for "session-empty"
        val reverseGeocoder = FakeReverseGeocoderForRetry()
        val geocodeAndPersist = GeocodeAndPersist(reverseGeocoder, trackingSessionDao)
        val logger = FakeLoggerForRetry()
        val retry = GeocodingRetryOnStartup(trackingSessionDao, gpsPointDao, geocodeAndPersist, logger, now = { fixedNow })

        val count = retry.retry()

        assertEquals(0, count)
        assertTrue(reverseGeocoder.calls.isEmpty())
        assertEquals(1, logger.entries.size)
        assertEquals(LogLevel.WARN, logger.entries.single().level)
    }

    @Test
    fun `a GpsPointDao failure for one session is logged and the rest of the batch still processes`() = runBlocking {
        trackingSessionDao.seed(finishedSession("bad-session", startTimestamp = fixedNow - 3_600_000L))
        trackingSessionDao.seed(finishedSession("good-session", startTimestamp = fixedNow - 3_600_000L))
        val gpsPointDao = RetryFakeGpsPointDao(failingSessionIds = setOf("bad-session")).apply {
            seed("good-session", listOf(point("good-session", timestamp = 0L, latitude = 7.0, longitude = 8.0)))
        }
        val reverseGeocoder = FakeReverseGeocoderForRetry(result = "Good Place")
        val geocodeAndPersist = GeocodeAndPersist(reverseGeocoder, trackingSessionDao)
        val logger = FakeLoggerForRetry()
        val retry = GeocodingRetryOnStartup(trackingSessionDao, gpsPointDao, geocodeAndPersist, logger, now = { fixedNow })

        val count = retry.retry()

        assertEquals(1, count)
        assertEquals(listOf(7.0 to 8.0), reverseGeocoder.calls)
        assertEquals(listOf("good-session" to "Good Place"), trackingSessionDao.updateLocationNameCalls)

        assertEquals(1, logger.entries.size)
        val entry = logger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("GeocodingRetryOnStartup", entry.tag)
        assertTrue(entry.message.contains("bad-session"))
    }

    @Test
    fun `retry returns the count of sessions successfully persisted, not the count scanned`() = runBlocking {
        // Three eligible sessions: one succeeds, one has no points (skipped), one throws (caught).
        trackingSessionDao.seed(finishedSession("succeeds", startTimestamp = fixedNow - 1_000L))
        trackingSessionDao.seed(finishedSession("no-points", startTimestamp = fixedNow - 1_000L))
        trackingSessionDao.seed(finishedSession("throws", startTimestamp = fixedNow - 1_000L))
        val gpsPointDao = RetryFakeGpsPointDao(failingSessionIds = setOf("throws")).apply {
            seed("succeeds", listOf(point("succeeds", timestamp = 0L, latitude = 1.0, longitude = 1.0)))
        }
        val reverseGeocoder = FakeReverseGeocoderForRetry(result = "Place")
        val geocodeAndPersist = GeocodeAndPersist(reverseGeocoder, trackingSessionDao)
        val retry = GeocodingRetryOnStartup(trackingSessionDao, gpsPointDao, geocodeAndPersist, now = { fixedNow })

        val count = retry.retry()

        assertEquals(1, count)
        assertFalse(trackingSessionDao.updateLocationNameCalls.map { it.first }.contains("no-points"))
        assertFalse(trackingSessionDao.updateLocationNameCalls.map { it.first }.contains("throws"))
    }
}
