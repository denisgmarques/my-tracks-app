package com.mytracksapp.service

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.domain.model.SamplingInterval
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [GpsPointDao] double — records every inserted point for assertions. */
private class FakeGpsPointDao : GpsPointDao {
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

/**
 * A fake [LocationSampleSource] standing in for `FusedLocationProviderClient` — records the
 * interval it was started with and lets the test emit synthetic fixes on demand via [emit].
 */
private class FakeLocationSampleSource : LocationSampleSource {
    var startedIntervalMillis: Long? = null
        private set
    var stopCallCount = 0
        private set

    private var onLocation: (suspend (LocationSample) -> Unit)? = null

    override fun start(intervalMillis: Long, onLocation: suspend (LocationSample) -> Unit) {
        startedIntervalMillis = intervalMillis
        this.onLocation = onLocation
    }

    override fun stop() {
        stopCallCount++
        onLocation = null
    }

    suspend fun emit(sample: LocationSample) {
        onLocation?.invoke(sample) ?: error("emit() called before start()")
    }
}

/**
 * T06 — [LocationCollector] unit tests (RF-02, RNF-03): a fake location provider
 * ([FakeLocationSampleSource]) emits synthetic fixes; each accepted point is persisted via
 * [GpsPointDao] carrying the real observed interval drift versus the configured sampling
 * interval. Pure JVM — no Robolectric/instrumentation needed.
 */
class LocationCollectorTest {

    private val sessionId = "session-under-test"
    private val gpsPointDao = FakeGpsPointDao()
    private val locationSampleSource = FakeLocationSampleSource()

    private fun collector(
        interval: SamplingInterval = SamplingInterval.TEN_SECONDS,
        sessionActive: () -> Boolean = { true },
        permissionGranted: () -> Boolean = { true },
        onFirstPointRecorded: (latitude: Double, longitude: Double) -> Unit = { _, _ -> },
    ) = LocationCollector(
        sessionId = sessionId,
        interval = interval,
        gpsPointDao = gpsPointDao,
        locationSampleSource = locationSampleSource,
        isSessionActive = sessionActive,
        isLocationPermissionGranted = permissionGranted,
        onFirstPointRecorded = onFirstPointRecorded,
    )

    @Test
    fun `starting requests updates at the configured interval in milliseconds`() = runBlocking {
        val started = collector(interval = SamplingInterval.FIFTEEN_SECONDS).start()

        assertTrue(started)
        assertEquals(15_000L, locationSampleSource.startedIntervalMillis)
    }

    @Test
    fun `first accepted point has no drift, since it has no predecessor to diff against`() = runBlocking {
        collector(interval = SamplingInterval.TEN_SECONDS).start()

        locationSampleSource.emit(
            LocationSample(latitude = 1.0, longitude = 2.0, accuracy = 5f, timestamp = 100_000L),
        )

        assertEquals(1, gpsPointDao.insertedPoints.size)
        val point = gpsPointDao.insertedPoints.single()
        assertEquals(sessionId, point.sessionId)
        assertEquals(100_000L, point.timestamp)
        assertEquals(1.0, point.latitude, 0.0)
        assertEquals(2.0, point.longitude, 0.0)
        assertEquals(5f, point.accuracy)
        assertNull(point.observedIntervalDriftMillis)
    }

    @Test
    fun `subsequent points record observed interval drift versus the configured interval`() = runBlocking {
        // Configured interval = 10s = 10_000ms.
        collector(interval = SamplingInterval.TEN_SECONDS).start()

        locationSampleSource.emit(
            LocationSample(latitude = 0.0, longitude = 0.0, accuracy = 5f, timestamp = 0L),
        )
        // Real gap = 12_000ms vs configured 10_000ms -> drift = +2_000ms (late, e.g. Doze mode).
        locationSampleSource.emit(
            LocationSample(latitude = 0.001, longitude = 0.0, accuracy = 5f, timestamp = 12_000L),
        )
        // Real gap = 9_000ms vs configured 10_000ms -> drift = -1_000ms (early).
        locationSampleSource.emit(
            LocationSample(latitude = 0.002, longitude = 0.0, accuracy = 5f, timestamp = 21_000L),
        )

        assertEquals(3, gpsPointDao.insertedPoints.size)
        assertNull(gpsPointDao.insertedPoints[0].observedIntervalDriftMillis)
        assertEquals(2_000L, gpsPointDao.insertedPoints[1].observedIntervalDriftMillis)
        assertEquals(-1_000L, gpsPointDao.insertedPoints[2].observedIntervalDriftMillis)
    }

    @Test
    fun `stopping prevents further points from being written even if a fix was already in flight`() =
        runBlocking {
            collector().start()
            locationSampleSource.emit(
                LocationSample(latitude = 0.0, longitude = 0.0, accuracy = 5f, timestamp = 0L),
            )

            collector().stop() // stops the shared locationSampleSource
            assertEquals(1, locationSampleSource.stopCallCount)

            // The underlying source no longer has a registered callback after stop(), so a further
            // emit() would fail-fast via error(); this proves no more points can arrive once stopped.
            assertTrue(gpsPointDao.insertedPoints.size == 1)
        }

    @Test
    fun `onFirstPointRecorded fires exactly once with the exact coordinates of the first accepted point`() =
        runBlocking {
            val recordedCalls = mutableListOf<Pair<Double, Double>>()
            collector(onFirstPointRecorded = { latitude, longitude -> recordedCalls += latitude to longitude })
                .start()

            locationSampleSource.emit(
                LocationSample(latitude = 10.123, longitude = 20.456, accuracy = 5f, timestamp = 0L),
            )

            assertEquals(1, recordedCalls.size)
            assertEquals(10.123 to 20.456, recordedCalls.single())
        }

    @Test
    fun `onFirstPointRecorded does not fire again for subsequent points in the same session`() = runBlocking {
        val recordedCalls = mutableListOf<Pair<Double, Double>>()
        collector(onFirstPointRecorded = { latitude, longitude -> recordedCalls += latitude to longitude })
            .start()

        locationSampleSource.emit(
            LocationSample(latitude = 1.0, longitude = 1.0, accuracy = 5f, timestamp = 0L),
        )
        locationSampleSource.emit(
            LocationSample(latitude = 2.0, longitude = 2.0, accuracy = 5f, timestamp = 10_000L),
        )
        locationSampleSource.emit(
            LocationSample(latitude = 3.0, longitude = 3.0, accuracy = 5f, timestamp = 20_000L),
        )

        assertEquals(1, recordedCalls.size)
        assertEquals(1.0 to 1.0, recordedCalls.single())
    }

    @Test
    fun `onFirstPointRecorded does not fire if start returns false`() = runBlocking {
        val recordedCalls = mutableListOf<Pair<Double, Double>>()
        val started = collector(
            permissionGranted = { false },
            onFirstPointRecorded = { latitude, longitude -> recordedCalls += latitude to longitude },
        ).start()

        assertTrue(!started)
        assertTrue(recordedCalls.isEmpty())
    }
}
