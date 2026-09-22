package com.mytracksapp.service

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.domain.model.SamplingInterval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [GpsPointDao] double — records every inserted point for assertions. */
private class GuardTestFakeGpsPointDao : GpsPointDao {
    val insertedPoints = mutableListOf<GpsPointEntity>()

    override suspend fun insert(point: GpsPointEntity): Long {
        insertedPoints += point
        return insertedPoints.size.toLong()
    }

    override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> = points.map { insert(it) }

    override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> =
        flowOf(insertedPoints.filter { it.sessionId == sessionId })

    override suspend fun countForSession(sessionId: String): Int =
        insertedPoints.count { it.sessionId == sessionId }
}

/** A [LocationSampleSource] fake that records whether it was ever asked to start requesting fixes. */
private class RecordingLocationSampleSource : LocationSampleSource {
    var startCallCount = 0
        private set
    var lastOnLocation: (suspend (LocationSample) -> Unit)? = null
        private set

    override fun start(intervalMillis: Long, onLocation: suspend (LocationSample) -> Unit) {
        startCallCount++
        lastOnLocation = onLocation
    }

    override fun stop() = Unit
}

/**
 * T06 — [LocationForegroundService]'s start guard, exercised at the [LocationCollector] level
 * (the plain JVM class the service wires to the real `FusedLocationProviderClient` and delegates
 * all guard logic to — see LocationForegroundService.kt). Covers RF-02/RF-03's "nenhum ponto é
 * gravado" / "impedir o início da coleta" guarantees for the two ways collection must be refused:
 * no active session, and no (background) location permission granted. Pure JVM — no
 * Robolectric/instrumentation needed.
 */
class LocationForegroundServiceStartGuardTest {

    private val sessionId = "guarded-session"
    private val gpsPointDao = GuardTestFakeGpsPointDao()
    private val locationSampleSource = RecordingLocationSampleSource()

    private fun collector(sessionActive: Boolean, permissionGranted: Boolean) = LocationCollector(
        sessionId = sessionId,
        interval = SamplingInterval.TEN_SECONDS,
        gpsPointDao = gpsPointDao,
        locationSampleSource = locationSampleSource,
        isSessionActive = { sessionActive },
        isLocationPermissionGranted = { permissionGranted },
    )

    @Test
    fun `refuses to start collection when there is no active session`() = runBlocking {
        val started = collector(sessionActive = false, permissionGranted = true).start()

        assertFalse(started)
        assertEquals(0, locationSampleSource.startCallCount)
        assertTrue("no point may ever be written without an active session", gpsPointDao.insertedPoints.isEmpty())
    }

    @Test
    fun `refuses to start collection when background location permission is not granted`() = runBlocking {
        val started = collector(sessionActive = true, permissionGranted = false).start()

        assertFalse(started)
        assertEquals(0, locationSampleSource.startCallCount)
        assertTrue("no point may ever be written without permission", gpsPointDao.insertedPoints.isEmpty())
    }

    @Test
    fun `refuses to start collection when neither an active session nor permission are present`() = runBlocking {
        val started = collector(sessionActive = false, permissionGranted = false).start()

        assertFalse(started)
        assertEquals(0, locationSampleSource.startCallCount)
        assertTrue(gpsPointDao.insertedPoints.isEmpty())
    }

    @Test
    fun `a fix that arrives after the session becomes inactive is dropped, not persisted`() = runBlocking {
        var active = true
        val guardedCollector = LocationCollector(
            sessionId = sessionId,
            interval = SamplingInterval.TEN_SECONDS,
            gpsPointDao = gpsPointDao,
            locationSampleSource = locationSampleSource,
            isSessionActive = { active },
            isLocationPermissionGranted = { true },
        )

        assertTrue(guardedCollector.start())
        active = false // session ends concurrently with a fix already in flight

        locationSampleSource.lastOnLocation?.invoke(
            LocationSample(latitude = 1.0, longitude = 1.0, accuracy = 5f, timestamp = 1_000L),
        )

        assertTrue("no point may be written once the session is no longer active", gpsPointDao.insertedPoints.isEmpty())
    }
}
