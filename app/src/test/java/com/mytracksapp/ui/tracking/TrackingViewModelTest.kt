package com.mytracksapp.ui.tracking

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.domain.stats.StatsEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * In-memory [GpsPointDao] double for [TrackingViewModelTest]: each session id gets its own
 * [MutableStateFlow] the test can push new emissions into, simulating Room's `Flow` re-running
 * after a new point is persisted — no real Room/database needed for this unit test, following the
 * same fake-DAO pattern as `SessionControllerTest`/`HistoryViewModelTest`.
 */
private class FakeGpsPointDao : GpsPointDao {
    private val pointsBySession = mutableMapOf<String, MutableStateFlow<List<GpsPointEntity>>>()

    private fun flowFor(sessionId: String): MutableStateFlow<List<GpsPointEntity>> =
        pointsBySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

    override suspend fun insert(point: GpsPointEntity): Long {
        emit(point.sessionId, flowFor(point.sessionId).value + point)
        return flowFor(point.sessionId).value.size.toLong()
    }

    override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> =
        points.map { insert(it) }

    override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> = flowFor(sessionId)

    override suspend fun countForSession(sessionId: String): Int = flowFor(sessionId).value.size

    /** Simulates a new Room query result after a point is persisted for [sessionId]. */
    fun emit(sessionId: String, points: List<GpsPointEntity>) {
        flowFor(sessionId).value = points
    }
}

/**
 * T08 — [TrackingViewModel] unit test (UI-02, UI-03, RNF-04): a simulated flow of
 * [GpsPointEntity] emissions updates the exposed polyline and the 5 UI-03 metrics on every
 * emission, with all math coming from [StatsEngine]/`SegmentClassifier` rather than being
 * reimplemented in the test or the ViewModel.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrackingViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state before any point is empty polyline and zeroed metrics`() = runTest(testDispatcher) {
        val dao = FakeGpsPointDao()
        val viewModel = TrackingViewModel(sessionId = "session-1", gpsPointDao = dao)
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("session-1", state.sessionId)
        assertTrue(state.polyline.isEmpty())
        assertEquals(0.0, state.instantSpeedMetersPerSecond, 0.0)
        assertEquals(0.0, state.averageSpeedMetersPerSecond, 0.0)
        assertEquals(0L, state.elapsedTimeMillis)
        assertEquals(0L, state.stoppedTimeMillis)
        assertEquals(0L, state.movingTimeMillis)
    }

    @Test
    fun `each new emission updates the polyline's last vertex and recalculates all 5 metrics`() =
        runTest(testDispatcher) {
            val sessionId = "session-1"
            val dao = FakeGpsPointDao()
            val viewModel = TrackingViewModel(sessionId = sessionId, gpsPointDao = dao)
            testDispatcher.scheduler.advanceUntilIdle()

            // First point: no interval yet, so no instant speed and zero elapsed time.
            val point1 = GpsPointEntity(
                sessionId = sessionId, timestamp = 0L,
                latitude = 0.0, longitude = 0.0, accuracy = 5f,
            )
            dao.insert(point1)
            testDispatcher.scheduler.advanceUntilIdle()

            var state = viewModel.uiState.value
            assertEquals(1, state.polyline.size)
            assertEquals(0.0, state.polyline.last().latitude, 0.0)
            assertEquals(0.0, state.polyline.last().longitude, 0.0)
            assertEquals(0.0, state.instantSpeedMetersPerSecond, 0.0)
            assertEquals(0L, state.elapsedTimeMillis)

            // Second point: an interval now exists (RF-05) — polyline gains a last vertex and
            // metrics are recalculated from StatsEngine, not reimplemented here.
            val point2 = GpsPointEntity(
                sessionId = sessionId, timestamp = 10_000L,
                latitude = 0.0009, longitude = 0.0, accuracy = 5f,
            )
            dao.insert(point2)
            testDispatcher.scheduler.advanceUntilIdle()

            state = viewModel.uiState.value
            assertEquals(2, state.polyline.size)
            assertEquals(point2.latitude, state.polyline.last().latitude, 0.0)
            assertEquals(point2.longitude, state.polyline.last().longitude, 0.0)

            val expectedInstantSpeed = StatsEngine.instantSpeedMetersPerSecond(point1, point2)
            assertEquals(expectedInstantSpeed, state.instantSpeedMetersPerSecond, 1e-9)
            // Only one interval exists so far: average speed equals the (only) instant speed.
            assertEquals(expectedInstantSpeed, state.averageSpeedMetersPerSecond, 1e-9)
            assertEquals(10_000L, state.elapsedTimeMillis)
            // RF-06 invariant: stopped + moving == total elapsed time, on every emission.
            assertEquals(10_000L, state.stoppedTimeMillis + state.movingTimeMillis)

            // Third point: polyline's last vertex advances again and metrics reflect all 3 points.
            val point3 = GpsPointEntity(
                sessionId = sessionId, timestamp = 20_000L,
                latitude = 0.0020, longitude = 0.0, accuracy = 5f,
            )
            dao.insert(point3)
            testDispatcher.scheduler.advanceUntilIdle()

            state = viewModel.uiState.value
            assertEquals(3, state.polyline.size)
            assertEquals(point3.latitude, state.polyline.last().latitude, 0.0)
            assertEquals(point3.longitude, state.polyline.last().longitude, 0.0)
            assertEquals(20_000L, state.elapsedTimeMillis)
            assertEquals(20_000L, state.stoppedTimeMillis + state.movingTimeMillis)
        }

    @Test
    fun `two ViewModels observing different session ids stay independent`() = runTest(testDispatcher) {
        val dao = FakeGpsPointDao()
        val viewModelA = TrackingViewModel(sessionId = "session-a", gpsPointDao = dao)
        val viewModelB = TrackingViewModel(sessionId = "session-b", gpsPointDao = dao)
        testDispatcher.scheduler.advanceUntilIdle()

        dao.insert(
            GpsPointEntity(sessionId = "session-a", timestamp = 0L, latitude = 1.0, longitude = 1.0, accuracy = 5f),
        )
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1, viewModelA.uiState.value.polyline.size)
        assertTrue(viewModelB.uiState.value.polyline.isEmpty())
    }
}
