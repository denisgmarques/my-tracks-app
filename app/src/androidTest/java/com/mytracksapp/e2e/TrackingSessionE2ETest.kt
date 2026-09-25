package com.mytracksapp.e2e

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.data.local.AppDatabase
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.session.LocationServiceController
import com.mytracksapp.domain.session.SessionControllerImpl
import com.mytracksapp.domain.session.SessionStartOutcome
import com.mytracksapp.domain.stats.SegmentClassifier
import com.mytracksapp.domain.stats.StatsEngine
import com.mytracksapp.service.LocationCollector
import com.mytracksapp.service.LocationSample
import com.mytracksapp.service.LocationSampleSource
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T13 — full-session integration test (RF-02, RNF-02, RNF-03, RNF-04): create session -> collect
 * N simulated points -> finish session -> verify persistence, driving the REAL production classes
 * end to end against a real (in-memory) Room database on-device:
 *
 *  - [SessionControllerImpl] (T05) — unmodified production class, exactly as wired in the app;
 *  - [TrackingSessionDao]/[GpsPointDao] against a real [AppDatabase] (Room, in-memory, on-device —
 *    not a fake map like the plain-JVM `SessionControllerTest`);
 *  - [LocationCollector] (T06) — unmodified production class: the same guard/drift/persistence
 *    logic that runs inside `LocationForegroundService` in production;
 *  - [StatsEngine]/[SegmentClassifier] (T07) — invoked exactly as production code invokes them,
 *    inside [SessionControllerImpl.stopSession]'s real final-metrics computation.
 *
 * The ONLY thing faked is the GPS radio itself, which an emulator cannot drive deterministically:
 *  - [LocationServiceController] — production's real implementation
 *    (`LocationForegroundServiceController`) starts an actual Android `Service` that binds to
 *    `FusedLocationProviderClient`. Here it is replaced by [SimulatedLocationServiceController], a
 *    thin test double that constructs the REAL [LocationCollector] (same class, same
 *    constructor) wired to the REAL DAOs, but feeds it a [LocationSampleSource] fake
 *    ([FakeLocationSampleSource]) that synchronously replays a canned list of [LocationSample]
 *    values instead of registering with Play Services. This is exactly the seam T06 was built
 *    with ([LocationSampleSource]) for this purpose (see `LocationCollector.kt`'s doc).
 *  - `isLocationPermissionGranted` is stubbed to `{ true }` — permission-gating itself is already
 *    covered end-to-end by `SessionControllerTest`/`LocationForegroundServiceStartGuardTest`; this
 *    test's job is to prove the collect/persist/finish pipeline, not re-prove permission gating.
 *
 * Everything downstream of "a [LocationSample] arrived" — guarding against an inactive session,
 * computing observed interval drift, writing the `gps_points` row, reading it back, classifying
 * moving/stopped segments, computing average speed, and persisting the finished session's
 * metadata — is the real, unmodified production code path.
 */
@RunWith(AndroidJUnit4::class)
class TrackingSessionE2ETest {

    private lateinit var database: AppDatabase
    private lateinit var trackingSessionDao: TrackingSessionDao
    private lateinit var gpsPointDao: GpsPointDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        trackingSessionDao = database.trackingSessionDao()
        gpsPointDao = database.gpsPointDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    /**
     * A [LocationSampleSource] fake that, when started, synchronously replays [samples] through
     * [onLocation] (the exact same suspend callback [LocationCollector] would register with a
     * real `FusedLocationProviderClient`) and then returns. No timers, no threads — deterministic
     * by construction, which is the whole point of driving this via the [LocationSampleSource]
     * seam instead of trying to make an emulator's GPS emit fixes on a schedule.
     */
    private class FakeLocationSampleSource(private val samples: List<LocationSample>) : LocationSampleSource {
        override fun start(intervalMillis: Long, onLocation: suspend (LocationSample) -> Unit) {
            runBlocking {
                samples.forEach { sample -> onLocation(sample) }
            }
        }

        override fun stop() {
            // Samples are replayed synchronously and fully inside start(); nothing left running.
        }
    }

    /**
     * Test double for [LocationServiceController] — the only faked seam (see class doc). On
     * [start], it builds a REAL [LocationCollector] (T06's production class, unmodified) against
     * the REAL [trackingSessionDao]/[gpsPointDao] of this test's in-memory Room database, and
     * drives it with [FakeLocationSampleSource] instead of Play Services.
     */
    private inner class SimulatedLocationServiceController(
        private val simulatedSamples: List<LocationSample>,
    ) : LocationServiceController {

        var collector: LocationCollector? = null
            private set

        override fun start(sessionId: String, interval: SamplingInterval) {
            val newCollector = LocationCollector(
                sessionId = sessionId,
                interval = interval,
                gpsPointDao = gpsPointDao,
                locationSampleSource = FakeLocationSampleSource(simulatedSamples),
                isSessionActive = {
                    trackingSessionDao.getSessionById(sessionId).first()?.status == SessionStatus.ACTIVE
                },
                isLocationPermissionGranted = { true },
            )
            collector = newCollector
            // LocationCollector.start() is suspend; SimulatedLocationServiceController.start()
            // mirrors LocationForegroundService's real onStartCommand, which likewise kicks off
            // collection from a synchronous entry point (Android's Service lifecycle callback).
            runBlocking { newCollector.start() }
        }

        override fun stop(sessionId: String) {
            collector?.stop()
        }
    }

    @Test
    fun fullSessionLifecycle_create_collectNPoints_finish_persistsEverything() = runBlocking {
        val interval = SamplingInterval.TEN_SECONDS
        val configuredIntervalMillis = interval.seconds * 1_000L
        val baseTimestamp = 1_700_000_000_000L

        // 12 simulated fixes, each ~50m north-east of the previous one, spaced at the configured
        // 10s interval -- except point index 5, which is deliberately 4s late, so this test also
        // proves RNF-03's per-point observed drift is computed and persisted correctly end to end.
        val pointCount = 12
        val driftedIndex = 5
        val extraDelayMillisAtDriftedIndex = 4_000L
        val simulatedSamples = mutableListOf<LocationSample>()
        var runningTimestamp = baseTimestamp
        for (index in 0 until pointCount) {
            if (index == driftedIndex) {
                runningTimestamp += configuredIntervalMillis + extraDelayMillisAtDriftedIndex
            } else if (index > 0) {
                runningTimestamp += configuredIntervalMillis
            }
            simulatedSamples += LocationSample(
                latitude = -23.550520 + index * 0.00045,
                longitude = -46.633308 + index * 0.00045,
                accuracy = 5.0f,
                timestamp = runningTimestamp,
            )
        }

        val locationServiceController = SimulatedLocationServiceController(simulatedSamples)
        val sessionController = SessionControllerImpl(
            trackingSessionDao = trackingSessionDao,
            gpsPointDao = gpsPointDao,
            locationServiceController = locationServiceController,
            isBackgroundLocationGranted = { true },
        )

        // 1) Create the session.
        val outcome = sessionController.startSession(interval)
        assertTrue("session should start successfully", outcome is SessionStartOutcome.Started)
        val sessionId = (outcome as SessionStartOutcome.Started).sessionId

        val activeSession = trackingSessionDao.getSessionById(sessionId).first()
        assertNotNull("session must be persisted in the real Room database", activeSession)
        assertEquals(SessionStatus.ACTIVE, activeSession!!.status)
        assertEquals(interval.seconds, activeSession.samplingIntervalSeconds)

        // 2) Collection already happened synchronously as part of startSession() above (the fake
        // LocationSampleSource replays all fixes inline) -- confirm all N points landed in the
        // real database via the real LocationCollector guard/persistence path.
        val collectedPoints = gpsPointDao.getPointsForSession(sessionId).first()
        assertEquals(pointCount, collectedPoints.size)
        assertEquals(pointCount, gpsPointDao.countForSession(sessionId))

        // Every point belongs to this session and carries a real, monotonically increasing timestamp.
        collectedPoints.forEachIndexed { index, point ->
            assertEquals(sessionId, point.sessionId)
            assertEquals(simulatedSamples[index].timestamp, point.timestamp)
            assertEquals(simulatedSamples[index].latitude, point.latitude, 0.0000001)
            assertEquals(simulatedSamples[index].longitude, point.longitude, 0.0000001)
        }

        // First point has no predecessor to diff against.
        assertEquals(null, collectedPoints.first().observedIntervalDriftMillis)
        // The deliberately delayed point's drift is exactly the extra delay injected above
        // (RNF-03: "o desvio observado ... é registrado junto ao ponto").
        assertEquals(
            extraDelayMillisAtDriftedIndex,
            collectedPoints[driftedIndex].observedIntervalDriftMillis,
        )
        // Every other point was replayed exactly on the configured interval -> zero drift.
        collectedPoints.forEachIndexed { index, point ->
            if (index != 0 && index != driftedIndex) {
                assertEquals(0L, point.observedIntervalDriftMillis)
            }
        }

        // 3) Finish the session.
        sessionController.stopSession(sessionId)

        // 4) Verify persistence: the finished session's metadata was computed by the REAL
        // StatsEngine/SegmentClassifier over the REAL persisted points, and persisted to the
        // REAL Room database.
        val finishedSession = trackingSessionDao.getSessionById(sessionId).first()
        assertNotNull(finishedSession)
        assertEquals(SessionStatus.FINISHED, finishedSession!!.status)
        assertEquals(simulatedSamples.last().timestamp, finishedSession.endTimestamp)

        val finalPoints = gpsPointDao.getPointsForSession(sessionId).first()
        assertEquals(pointCount, finalPoints.size)

        val expectedClassification = SegmentClassifier.classify(finalPoints)
        val expectedAverageSpeed = StatsEngine.averageSpeedMetersPerSecond(
            StatsEngine.totalDistanceMeters(finalPoints),
            expectedClassification.movingTimeMillis,
        )
        val expectedElapsedMillis = StatsEngine.elapsedTimeMillis(finalPoints)

        assertEquals(expectedClassification.stoppedTimeMillis, finishedSession.stoppedTimeMillis)
        assertEquals(expectedClassification.movingTimeMillis, finishedSession.movingTimeMillis)
        assertEquals(expectedAverageSpeed, finishedSession.averageSpeedMetersPerSecond, 0.0001)

        // RF-06 invariant, re-checked against what actually got persisted (not just recomputed
        // in isolation): stopped + moving == total elapsed time for the whole session.
        assertEquals(
            expectedElapsedMillis,
            finishedSession.stoppedTimeMillis + finishedSession.movingTimeMillis,
        )
        // Points moved a real, non-zero distance -> a finished session with clearly separated
        // points must report a positive average speed.
        assertTrue("average speed should be positive for a session with moving points", finishedSession.averageSpeedMetersPerSecond > 0.0)

        // T08/RF-13: total distance is persisted from the real, final point set, matching an
        // independent recomputation over the same points read back from the database.
        val expectedDistanceMeters = StatsEngine.totalDistanceMeters(finalPoints)
        assertEquals(expectedDistanceMeters, finishedSession.distanceMeters, 0.0001)
        assertTrue("distance should be positive for a session with moving points", finishedSession.distanceMeters > 0.0)
    }

    @Test
    fun startingWithoutPermission_collectsNothing_realDatabaseStaysEmpty() = runBlocking {
        val locationServiceController = SimulatedLocationServiceController(emptyList())
        val sessionController = SessionControllerImpl(
            trackingSessionDao = trackingSessionDao,
            gpsPointDao = gpsPointDao,
            locationServiceController = locationServiceController,
            isBackgroundLocationGranted = { false },
        )

        val outcome = sessionController.startSession(SamplingInterval.FIVE_SECONDS)

        assertEquals(SessionStartOutcome.PermissionDenied, outcome)
        assertTrue(
            "no session may be persisted in the real database without permission",
            trackingSessionDao.getAllSessions().first().isEmpty(),
        )
    }
}
