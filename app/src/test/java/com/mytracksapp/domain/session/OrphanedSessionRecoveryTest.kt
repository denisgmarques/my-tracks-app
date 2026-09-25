package com.mytracksapp.domain.session

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * In-memory [TrackingSessionDao] double — same style as `SessionControllerTest`'s
 * `RecoveryFakeTrackingSessionDao`, plus a `seed` helper so tests can put a row straight into `ACTIVE`
 * state without going through `SessionControllerImpl.startSession`.
 */
private class RecoveryFakeTrackingSessionDao(private val updateFailures: Map<String, Throwable> = emptyMap()) :
    TrackingSessionDao {
    val updatedSessions = mutableListOf<TrackingSessionEntity>()
    private val sessionsById = mutableMapOf<String, MutableStateFlow<TrackingSessionEntity?>>()

    fun seed(session: TrackingSessionEntity) {
        sessionsById.getOrPut(session.id) { MutableStateFlow(null) }.value = session
    }

    override suspend fun insert(session: TrackingSessionEntity) {
        sessionsById.getOrPut(session.id) { MutableStateFlow(null) }.value = session
    }

    override suspend fun update(session: TrackingSessionEntity) {
        updateFailures[session.id]?.let { throw it }
        updatedSessions += session
        sessionsById.getOrPut(session.id) { MutableStateFlow(null) }.value = session
    }

    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> =
        sessionsById.getOrPut(sessionId) { MutableStateFlow(null) }

    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> =
        flowOf(sessionsById.values.mapNotNull { it.value })

    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
        flowOf(sessionsById.values.mapNotNull { it.value }.filter { it.status == status })

    override suspend fun deleteById(sessionId: String) = error("not used in this test")
    override suspend fun deleteAll() = error("not used in this test")
    override suspend fun getFinishedSessionsWithoutLocationNameSince(
        status: SessionStatus,
        sinceTimestamp: Long,
    ): List<TrackingSessionEntity> = error("not used in this test")
    override suspend fun updateLocationName(sessionId: String, locationName: String?) = error("not used in this test")
}

/**
 * In-memory [GpsPointDao] double — `insert`/`insertAll` fail the test immediately if ever called
 * (RF-04: recovery must never touch the points table), proving the guarantee at every call site
 * that exercises this fake, not just in one dedicated test.
 */
private class RecoveryFakeGpsPointDao : GpsPointDao {
    private val pointsBySession = mutableMapOf<String, MutableList<GpsPointEntity>>()

    fun seed(sessionId: String, points: List<GpsPointEntity>) {
        pointsBySession.getOrPut(sessionId) { mutableListOf() }.addAll(points)
    }

    override suspend fun insert(point: GpsPointEntity): Long {
        fail("RF-04 violation: OrphanedSessionRecovery must never insert a GpsPointEntity")
        return -1L
    }

    override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> {
        fail("RF-04 violation: OrphanedSessionRecovery must never insertAll GpsPointEntity rows")
        return emptyList()
    }

    override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> =
        flowOf(pointsBySession[sessionId].orEmpty())

    override suspend fun countForSession(sessionId: String): Int =
        pointsBySession[sessionId]?.size ?: 0
}

/** [Logger] fake recording every [log] call — see T07. */
private class FakeLogger : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/**
 * T02 — [OrphanedSessionRecovery] unit tests (RF-01, RF-02, RF-03, RF-04, RF-05), using plain
 * in-memory doubles for [TrackingSessionDao]/[GpsPointDao] — no Room, no Robolectric, no
 * instrumentation needed, same style as `SessionControllerTest`.
 */
class OrphanedSessionRecoveryTest {

    private val trackingSessionDao = RecoveryFakeTrackingSessionDao()
    private val gpsPointDao = RecoveryFakeGpsPointDao()
    private val recovery = OrphanedSessionRecovery(trackingSessionDao, gpsPointDao)

    private fun activeSession(id: String, startTimestamp: Long) = TrackingSessionEntity(
        id = id,
        samplingIntervalSeconds = 10,
        startTimestamp = startTimestamp,
        status = SessionStatus.ACTIVE,
    )

    @Test
    fun `zero ACTIVE sessions returns 0 and never calls update`() = runBlocking {
        // RecoveryFakeTrackingSessionDao starts empty — nothing seeded.
        val count = recovery.recover()

        assertEquals(0, count)
        assertTrue(trackingSessionDao.updatedSessions.isEmpty())
    }

    @Test
    fun `ACTIVE session with no points is finalized with endTimestamp equal to startTimestamp and zeroed metrics`() =
        runBlocking {
            trackingSessionDao.seed(activeSession("orphan-1", startTimestamp = 5_000L))
            // No points seeded in gpsPointDao for "orphan-1".

            val count = recovery.recover()

            assertEquals(1, count)
            val stored = trackingSessionDao.getSessionById("orphan-1").first()!!
            assertEquals(SessionStatus.FINISHED, stored.status)
            assertEquals(5_000L, stored.endTimestamp)
            assertEquals(5_000L, stored.startTimestamp)
            assertEquals(0.0, stored.distanceMeters, 0.0)
            assertEquals(0.0, stored.averageSpeedMetersPerSecond, 0.0)
            assertEquals(0L, stored.movingTimeMillis)
            assertEquals(0L, stored.stoppedTimeMillis)
        }

    @Test
    fun `ACTIVE session with points is finalized with metrics identical to finalizeSession's own output`() =
        runBlocking {
            val points = listOf(
                GpsPointEntity(sessionId = "orphan-2", timestamp = 0L, latitude = 0.0, longitude = 0.0, accuracy = 5f),
                GpsPointEntity(sessionId = "orphan-2", timestamp = 10_000L, latitude = 0.001, longitude = 0.0, accuracy = 5f),
            )
            val session = activeSession("orphan-2", startTimestamp = 0L)
            trackingSessionDao.seed(session)
            gpsPointDao.seed("orphan-2", points)

            val count = recovery.recover()

            assertEquals(1, count)
            val stored = trackingSessionDao.getSessionById("orphan-2").first()!!

            // Same math T01 already exercises directly — assert numeric parity rather than
            // re-deriving expected values, so this test breaks if the two ever diverge.
            val expected = finalizeSession(session, points, endTimestampFallback = session.startTimestamp)
            assertEquals(SessionStatus.FINISHED, stored.status)
            assertEquals(expected.endTimestamp, stored.endTimestamp)
            assertEquals(expected.distanceMeters, stored.distanceMeters, 0.0001)
            assertEquals(expected.averageSpeedMetersPerSecond, stored.averageSpeedMetersPerSecond, 0.0001)
            assertEquals(expected.movingTimeMillis, stored.movingTimeMillis)
            assertEquals(expected.stoppedTimeMillis, stored.stoppedTimeMillis)
        }

    @Test
    fun `multiple concurrent ACTIVE sessions are all finalized in one call with isolated metrics`() = runBlocking {
        val sessionA = activeSession("session-a", startTimestamp = 0L)
        val pointsA = listOf(
            GpsPointEntity(sessionId = "session-a", timestamp = 0L, latitude = 0.0, longitude = 0.0, accuracy = 5f),
            GpsPointEntity(sessionId = "session-a", timestamp = 10_000L, latitude = 0.001, longitude = 0.0, accuracy = 5f),
        )
        val sessionB = activeSession("session-b", startTimestamp = 100L)
        val pointsB = listOf(
            GpsPointEntity(sessionId = "session-b", timestamp = 100L, latitude = 10.0, longitude = 10.0, accuracy = 5f),
            GpsPointEntity(sessionId = "session-b", timestamp = 5_100L, latitude = 10.01, longitude = 10.0, accuracy = 5f),
            GpsPointEntity(sessionId = "session-b", timestamp = 10_100L, latitude = 10.02, longitude = 10.0, accuracy = 5f),
        )

        trackingSessionDao.seed(sessionA)
        trackingSessionDao.seed(sessionB)
        gpsPointDao.seed("session-a", pointsA)
        gpsPointDao.seed("session-b", pointsB)

        val count = recovery.recover()

        assertEquals(2, count)
        val storedA = trackingSessionDao.getSessionById("session-a").first()!!
        val storedB = trackingSessionDao.getSessionById("session-b").first()!!

        assertEquals(SessionStatus.FINISHED, storedA.status)
        assertEquals(SessionStatus.FINISHED, storedB.status)

        val expectedA = finalizeSession(sessionA, pointsA, endTimestampFallback = sessionA.startTimestamp)
        val expectedB = finalizeSession(sessionB, pointsB, endTimestampFallback = sessionB.startTimestamp)

        assertEquals(expectedA.distanceMeters, storedA.distanceMeters, 0.0001)
        assertEquals(expectedB.distanceMeters, storedB.distanceMeters, 0.0001)
        // No cross-session leakage: A's distance must not equal B's (distinct point sets), and
        // each stored row must match only its own expected value, never the other session's.
        assertEquals(expectedA.movingTimeMillis, storedA.movingTimeMillis)
        assertEquals(expectedB.movingTimeMillis, storedB.movingTimeMillis)
        assertEquals(expectedA.stoppedTimeMillis, storedA.stoppedTimeMillis)
        assertEquals(expectedB.stoppedTimeMillis, storedB.stoppedTimeMillis)
        assertEquals(expectedA.averageSpeedMetersPerSecond, storedA.averageSpeedMetersPerSecond, 0.0001)
        assertEquals(expectedB.averageSpeedMetersPerSecond, storedB.averageSpeedMetersPerSecond, 0.0001)
    }

    @Test
    fun `recovery never calls GpsPointDao insert or insertAll`() = runBlocking {
        trackingSessionDao.seed(activeSession("orphan-3", startTimestamp = 0L))
        gpsPointDao.seed(
            "orphan-3",
            listOf(GpsPointEntity(sessionId = "orphan-3", timestamp = 0L, latitude = 0.0, longitude = 0.0, accuracy = 5f)),
        )

        // RecoveryFakeGpsPointDao.insert/insertAll call fail() internally — reaching here without a
        // failure already proves RF-04 for this run, but we assert the count too for clarity.
        val count = recovery.recover()
        assertEquals(1, count)
    }

    @Test
    fun `a session that throws during finalization is logged and skipped without aborting the rest of the batch`() =
        runBlocking {
            val failure = IllegalStateException("corrupt row")
            val logger = FakeLogger()
            val dao = RecoveryFakeTrackingSessionDao(updateFailures = mapOf("bad-session" to failure))
            val points = RecoveryFakeGpsPointDao()
            val recoveryWithLogger = OrphanedSessionRecovery(dao, points, logger)

            dao.seed(activeSession("bad-session", startTimestamp = 0L))
            dao.seed(activeSession("good-session", startTimestamp = 0L))

            val count = recoveryWithLogger.recover()

            // Only the good session is counted/updated; the bad one is skipped, not aborting the batch.
            assertEquals(1, count)
            assertEquals(listOf("good-session"), dao.updatedSessions.map { it.id })

            assertEquals(1, logger.entries.size)
            val entry = logger.entries.single()
            assertEquals(LogLevel.ERROR, entry.level)
            assertEquals("OrphanedSessionRecovery", entry.tag)
            assertEquals(failure, entry.throwable)
        }
}
