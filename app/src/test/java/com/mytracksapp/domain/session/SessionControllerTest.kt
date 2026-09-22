package com.mytracksapp.domain.session

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.domain.model.SamplingInterval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [TrackingSessionDao] double — records every insert/update for assertions. */
private class FakeTrackingSessionDao : TrackingSessionDao {
    val insertedSessions = mutableListOf<TrackingSessionEntity>()
    val updatedSessions = mutableListOf<TrackingSessionEntity>()
    private val sessionsById = mutableMapOf<String, MutableStateFlow<TrackingSessionEntity?>>()

    override suspend fun insert(session: TrackingSessionEntity) {
        insertedSessions += session
        sessionsById.getOrPut(session.id) { MutableStateFlow(null) }.value = session
    }

    override suspend fun update(session: TrackingSessionEntity) {
        updatedSessions += session
        sessionsById.getOrPut(session.id) { MutableStateFlow(null) }.value = session
    }

    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> =
        sessionsById.getOrPut(sessionId) { MutableStateFlow(null) }

    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> =
        flowOf(sessionsById.values.mapNotNull { it.value })

    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
        flowOf(sessionsById.values.mapNotNull { it.value }.filter { it.status == status })
}

/** In-memory [GpsPointDao] double — records every inserted point for assertions. */
private class FakeGpsPointDao : GpsPointDao {
    val insertedPoints = mutableListOf<GpsPointEntity>()
    private val pointsBySession = mutableMapOf<String, MutableList<GpsPointEntity>>()

    override suspend fun insert(point: GpsPointEntity): Long {
        insertedPoints += point
        pointsBySession.getOrPut(point.sessionId) { mutableListOf() }.add(point)
        return insertedPoints.size.toLong()
    }

    override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> =
        points.map { insert(it) }

    override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> =
        flowOf(pointsBySession[sessionId].orEmpty())

    override suspend fun countForSession(sessionId: String): Int =
        pointsBySession[sessionId]?.size ?: 0
}

/** Records every start/stop call — a fake for T06's not-yet-implemented service. */
private class FakeLocationServiceController : LocationServiceController {
    val startedSessions = mutableListOf<Pair<String, SamplingInterval>>()
    val stoppedSessions = mutableListOf<String>()

    override fun start(sessionId: String, interval: SamplingInterval) {
        startedSessions += sessionId to interval
    }

    override fun stop(sessionId: String) {
        stoppedSessions += sessionId
    }
}

/**
 * T05 — [SessionControllerImpl] unit tests (RF-01, RF-03, RF-07), using plain in-memory doubles
 * for [TrackingSessionDao]/[GpsPointDao]/[LocationServiceController] — no Room, no Robolectric,
 * no instrumentation needed.
 */
class SessionControllerTest {

    private val trackingSessionDao = FakeTrackingSessionDao()
    private val gpsPointDao = FakeGpsPointDao()
    private val locationServiceController = FakeLocationServiceController()

    private fun controller(permissionGranted: Boolean) = SessionControllerImpl(
        trackingSessionDao = trackingSessionDao,
        gpsPointDao = gpsPointDao,
        locationServiceController = locationServiceController,
        isBackgroundLocationGranted = { permissionGranted },
    )

    @Test
    fun `starting without background location permission creates no session, starts no service, writes no point`() =
        runBlocking {
            val outcome = controller(permissionGranted = false).startSession(SamplingInterval.FIFTEEN_SECONDS)

            assertEquals(SessionStartOutcome.PermissionDenied, outcome)
            assertTrue("no session should be persisted", trackingSessionDao.insertedSessions.isEmpty())
            assertTrue("service must never be started", locationServiceController.startedSessions.isEmpty())
            assertTrue("no point may ever be written", gpsPointDao.insertedPoints.isEmpty())
        }

    @Test
    fun `starting with permission granted creates the session before starting the service`() = runBlocking {
        val outcome = controller(permissionGranted = true).startSession(SamplingInterval.THIRTY_SECONDS)

        assertTrue(outcome is SessionStartOutcome.Started)
        val sessionId = (outcome as SessionStartOutcome.Started).sessionId

        assertEquals(1, trackingSessionDao.insertedSessions.size)
        val persisted = trackingSessionDao.insertedSessions.single()
        assertEquals(sessionId, persisted.id)
        assertEquals(SamplingInterval.THIRTY_SECONDS.seconds, persisted.samplingIntervalSeconds)
        assertEquals(SessionStatus.ACTIVE, persisted.status)

        assertEquals(listOf(sessionId to SamplingInterval.THIRTY_SECONDS), locationServiceController.startedSessions)
        // RF-01: session created before any point collection — this controller never writes a
        // point itself, so at the moment startSession returns, none can possibly exist.
        assertTrue(gpsPointDao.insertedPoints.isEmpty())
    }

    @Test
    fun `defensive RF-01 guard rejects any seconds value outside the 9 allowed values`() {
        SamplingInterval.ALLOWED_SECONDS.forEach { assertTrue(isAllowedIntervalSeconds(it)) }

        assertFalse(isAllowedIntervalSeconds(0))
        assertFalse(isAllowedIntervalSeconds(7))
        assertFalse(isAllowedIntervalSeconds(-15))
        assertFalse(isAllowedIntervalSeconds(200))
    }

    @Test
    fun `stopping a session stops the service, computes final stats and persists them as FINISHED`() = runBlocking {
        val started = controller(permissionGranted = true)
            .startSession(SamplingInterval.TEN_SECONDS) as SessionStartOutcome.Started
        val sessionId = started.sessionId

        // Simulate points collected by the (not-yet-implemented) LocationForegroundService.
        gpsPointDao.insertAll(
            listOf(
                GpsPointEntity(sessionId = sessionId, timestamp = 0L, latitude = 0.0, longitude = 0.0, accuracy = 5f),
                GpsPointEntity(sessionId = sessionId, timestamp = 10_000L, latitude = 0.001, longitude = 0.0, accuracy = 5f),
            ),
        )

        controller(permissionGranted = true).stopSession(sessionId)

        assertEquals(listOf(sessionId), locationServiceController.stoppedSessions)

        val stored = trackingSessionDao.getSessionById(sessionId).first()!!
        assertEquals(SessionStatus.FINISHED, stored.status)
        assertEquals(10_000L, stored.endTimestamp)
        // stopped + moving must equal total elapsed time (RF-06 invariant), regardless of split.
        assertEquals(10_000L, stored.stoppedTimeMillis + stored.movingTimeMillis)
    }

    @Test
    fun `stopping an unknown session id is a no-op and does not throw`() = runBlocking {
        controller(permissionGranted = true).stopSession("unknown-session-id")
        assertEquals(listOf("unknown-session-id"), locationServiceController.stoppedSessions)
        assertTrue(trackingSessionDao.updatedSessions.isEmpty())
    }
}
