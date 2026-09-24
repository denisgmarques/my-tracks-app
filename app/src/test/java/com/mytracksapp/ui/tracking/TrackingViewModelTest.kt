package com.mytracksapp.ui.tracking

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.stats.StatsEngine
import com.mytracksapp.domain.units.DistanceUnit
import com.mytracksapp.domain.units.SpeedUnit
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * In-memory [GpsPointDao] double for [TrackingViewModelTest]: each session id gets its own
 * [MutableStateFlow] the test can push new emissions into, simulating Room's `Flow` re-running
 * after a new point is persisted — no real Room/database needed for this unit test, following the
 * same fake-DAO pattern as `SessionControllerTest`/`HistoryViewModelTest`.
 */
private class FakeGpsPointDao : GpsPointDao {
    private val pointsBySession = mutableMapOf<String, MutableStateFlow<List<GpsPointEntity>>>()

    /** T11: when set, [getPointsForSession]'s returned flow throws this instead of ever emitting. */
    var throwOnGetPointsForSession: Throwable? = null

    private fun flowFor(sessionId: String): MutableStateFlow<List<GpsPointEntity>> =
        pointsBySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

    override suspend fun insert(point: GpsPointEntity): Long {
        emit(point.sessionId, flowFor(point.sessionId).value + point)
        return flowFor(point.sessionId).value.size.toLong()
    }

    override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> =
        points.map { insert(it) }

    override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> = flow {
        throwOnGetPointsForSession?.let { throw it }
        emitAll(flowFor(sessionId))
    }

    override suspend fun countForSession(sessionId: String): Int = flowFor(sessionId).value.size

    /** Simulates a new Room query result after a point is persisted for [sessionId]. */
    fun emit(sessionId: String, points: List<GpsPointEntity>) {
        flowFor(sessionId).value = points
    }
}

/** In-memory [Logger] double — records every logged entry for assertions (T11). */
private class FakeLogger : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/**
 * T08 — [TrackingViewModel] unit test (UI-02, UI-03, RNF-04): a simulated flow of
 * [GpsPointEntity] emissions updates the exposed polyline and the UI-03 metrics on every
 * emission, with all math coming from [StatsEngine]/`SegmentClassifier` rather than being
 * reimplemented in the test or the ViewModel.
 *
 * Phase C follow-up: [TrackingViewModel] now also depends on [SettingsRepository] (for
 * unit-aware display + configured stop thresholds), which requires a real Android [Context] under
 * the hood (Jetpack DataStore). Robolectric supplies that on the plain JVM test path — the same
 * pattern `LocationPermissionManagerTest` already established in this codebase — so this remains
 * a fast, non-instrumented unit test.
 *
 * These tests deliberately use a real [Dispatchers.Default]-backed `Main` dispatcher plus
 * real-time polling (`awaitState`) instead of `StandardTestDispatcher`/virtual time: DataStore's
 * `Flow<Preferences>` does its actual I/O on its own internal (real) dispatcher, so it can never
 * be driven to completion by `advanceUntilIdle()` on an unrelated virtual scheduler. Polling with
 * a real timeout is the same technique the instrumented Compose tests already use
 * (`composeTestRule.waitUntil`) for exactly this reason.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TrackingViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        dataStoreName = "test_tracking_vm_settings_${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun settingsRepository() = SettingsRepository(context, dataStoreName)

    private suspend fun awaitState(
        viewModel: TrackingViewModel,
        timeoutMillis: Long = 5_000,
        predicate: (TrackingUiState) -> Boolean,
    ): TrackingUiState = withTimeout(timeoutMillis) {
        var state = viewModel.uiState.value
        while (!predicate(state)) {
            delay(10)
            state = viewModel.uiState.value
        }
        state
    }

    @Test
    fun `initial state before any point is empty polyline and zeroed metrics`() = runBlocking {
        val dao = FakeGpsPointDao()
        val viewModel = TrackingViewModel(sessionId = "session-1", gpsPointDao = dao, settingsRepository = settingsRepository())

        // Defaults, per UserSettings(), until SettingsRepository ever persists anything — this
        // also doubles as the "the settings flow has emitted at least once" wait.
        val state = awaitState(viewModel) { it.speedUnit == SpeedUnit.KMH }

        assertEquals("session-1", state.sessionId)
        assertTrue(state.polyline.isEmpty())
        assertEquals(0.0, state.instantSpeedMetersPerSecond, 0.0)
        assertEquals(0.0, state.averageSpeedMetersPerSecond, 0.0)
        assertEquals(0.0, state.totalDistanceMeters, 0.0)
        assertEquals(0L, state.elapsedTimeMillis)
        assertEquals(0L, state.stoppedTimeMillis)
        assertEquals(0L, state.movingTimeMillis)
        assertEquals(DistanceUnit.KM, state.distanceUnit)
    }

    @Test
    fun `each new emission updates the polyline's last vertex and recalculates all metrics`() =
        runBlocking {
            val sessionId = "session-1"
            val dao = FakeGpsPointDao()
            val viewModel = TrackingViewModel(sessionId = sessionId, gpsPointDao = dao, settingsRepository = settingsRepository())

            // First point: no interval yet, so no instant speed and zero elapsed time.
            val point1 = GpsPointEntity(
                sessionId = sessionId, timestamp = 0L,
                latitude = 0.0, longitude = 0.0, accuracy = 5f,
            )
            dao.insert(point1)

            var state = awaitState(viewModel) { it.polyline.size == 1 }
            assertEquals(0.0, state.polyline.last().latitude, 0.0)
            assertEquals(0.0, state.polyline.last().longitude, 0.0)
            assertEquals(0.0, state.instantSpeedMetersPerSecond, 0.0)
            assertEquals(0L, state.elapsedTimeMillis)
            assertEquals(0.0, state.totalDistanceMeters, 0.0)

            // Second point: an interval now exists (RF-05) — polyline gains a last vertex and
            // metrics are recalculated from StatsEngine, not reimplemented here.
            val point2 = GpsPointEntity(
                sessionId = sessionId, timestamp = 10_000L,
                latitude = 0.0009, longitude = 0.0, accuracy = 5f,
            )
            dao.insert(point2)

            state = awaitState(viewModel) { it.polyline.size == 2 }
            assertEquals(point2.latitude, state.polyline.last().latitude, 0.0)
            assertEquals(point2.longitude, state.polyline.last().longitude, 0.0)

            val expectedInstantSpeed = StatsEngine.instantSpeedMetersPerSecond(point1, point2)
            assertEquals(expectedInstantSpeed, state.instantSpeedMetersPerSecond, 1e-9)
            // Only one interval exists so far: average speed equals the (only) instant speed.
            assertEquals(expectedInstantSpeed, state.averageSpeedMetersPerSecond, 1e-9)
            assertEquals(10_000L, state.elapsedTimeMillis)
            val expectedTotalDistance = StatsEngine.totalDistanceMeters(listOf(point1, point2))
            assertEquals(expectedTotalDistance, state.totalDistanceMeters, 1e-9)
            // RF-06 invariant: stopped + moving == total elapsed time, on every emission.
            assertEquals(10_000L, state.stoppedTimeMillis + state.movingTimeMillis)

            // Third point: polyline's last vertex advances again and metrics reflect all 3 points.
            val point3 = GpsPointEntity(
                sessionId = sessionId, timestamp = 20_000L,
                latitude = 0.0020, longitude = 0.0, accuracy = 5f,
            )
            dao.insert(point3)

            state = awaitState(viewModel) { it.polyline.size == 3 }
            assertEquals(point3.latitude, state.polyline.last().latitude, 0.0)
            assertEquals(point3.longitude, state.polyline.last().longitude, 0.0)
            assertEquals(20_000L, state.elapsedTimeMillis)
            assertEquals(20_000L, state.stoppedTimeMillis + state.movingTimeMillis)
            assertEquals(
                StatsEngine.totalDistanceMeters(listOf(point1, point2, point3)),
                state.totalDistanceMeters,
                1e-9,
            )
        }

    @Test
    fun `two ViewModels observing different session ids stay independent`() = runBlocking {
        val dao = FakeGpsPointDao()
        val viewModelA = TrackingViewModel(sessionId = "session-a", gpsPointDao = dao, settingsRepository = settingsRepository())
        val viewModelB = TrackingViewModel(sessionId = "session-b", gpsPointDao = dao, settingsRepository = settingsRepository())

        dao.insert(
            GpsPointEntity(sessionId = "session-a", timestamp = 0L, latitude = 1.0, longitude = 1.0, accuracy = 5f),
        )

        awaitState(viewModelA) { it.polyline.size == 1 }
        // Give viewModelB every chance to (incorrectly) pick up session-a's point too.
        delay(100)
        assertTrue(viewModelB.uiState.value.polyline.isEmpty())
    }

    @Test
    fun `uiState reflects the currently configured speed and distance units, and reacts to changes`() =
        runBlocking {
            val sessionId = "session-units"
            val dao = FakeGpsPointDao()
            val repository = settingsRepository()
            repository.setSpeedUnit(SpeedUnit.KNOTS)
            repository.setDistanceUnit(DistanceUnit.MILES)

            val viewModel = TrackingViewModel(sessionId = sessionId, gpsPointDao = dao, settingsRepository = repository)

            var state = awaitState(viewModel) { it.speedUnit == SpeedUnit.KNOTS }
            assertEquals(DistanceUnit.MILES, state.distanceUnit)

            // Changing settings while the "session" is active (same repository instance a
            // Settings screen would write through) must be reflected reactively, without
            // recreating the ViewModel.
            repository.setSpeedUnit(SpeedUnit.MS)

            state = awaitState(viewModel) { it.speedUnit == SpeedUnit.MS }
            assertEquals(DistanceUnit.MILES, state.distanceUnit)
        }

    @Test
    fun `uiState reflects the currently configured keepScreenOnEnabled, and reacts to changes`() =
        runBlocking {
            val sessionId = "session-keep-screen-on"
            val dao = FakeGpsPointDao()
            val repository = settingsRepository()
            repository.setKeepScreenOnEnabled(false)

            val viewModel = TrackingViewModel(sessionId = sessionId, gpsPointDao = dao, settingsRepository = repository)

            var state = awaitState(viewModel) { !it.keepScreenOnEnabled }
            assertEquals(false, state.keepScreenOnEnabled)

            // Changing settings while the "session" is active must be reflected reactively,
            // without recreating the ViewModel.
            repository.setKeepScreenOnEnabled(true)

            state = awaitState(viewModel) { it.keepScreenOnEnabled }
            assertEquals(true, state.keepScreenOnEnabled)
        }

    private suspend fun awaitLogEntry(logger: FakeLogger, timeoutMillis: Long = 5_000) {
        withTimeout(timeoutMillis) {
            while (logger.entries.isEmpty()) {
                delay(10)
            }
        }
    }

    @Test
    fun `init logs and swallows a getPointsForSession failure, leaving uiState at its last valid value`() =
        runBlocking {
            val sessionId = "session-failure"
            val dao = FakeGpsPointDao()
            val thrown = IllegalStateException("getPointsForSession boom")
            dao.throwOnGetPointsForSession = thrown
            val logger = FakeLogger()

            val viewModel = TrackingViewModel(
                sessionId = sessionId,
                gpsPointDao = dao,
                settingsRepository = settingsRepository(),
                logger = logger,
            )

            awaitLogEntry(logger)

            val errorEntries = logger.entries.filter { it.level == LogLevel.ERROR }
            assertEquals(1, errorEntries.size)
            assertEquals("TrackingViewModel", errorEntries.single().tag)
            // Compared by type+message, not instance: kotlinx.coroutines recovers the stack trace
            // of exceptions crossing suspension points by copying them, so the logged throwable is
            // not the same object identity as `thrown` even though it represents the same failure.
            assertEquals(thrown::class, errorEntries.single().throwable?.let { it::class })
            assertEquals(thrown.message, errorEntries.single().throwable?.message)

            // uiState stays at its last valid value: the constructor-seeded initial state, since
            // the combine never successfully emitted before failing.
            assertEquals(TrackingUiState(sessionId = sessionId), viewModel.uiState.value)
        }
}
