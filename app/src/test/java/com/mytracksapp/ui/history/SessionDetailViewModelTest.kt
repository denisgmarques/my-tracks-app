package com.mytracksapp.ui.history

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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

/** [Logger] fake recording every [log] call — same shape as `FirstPointGeocodingCoordinatorTest`'s. */
private class FakeLoggerForSessionDetail : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = CopyOnWriteArrayList<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/**
 * [TrackingSessionDao] fake for [SessionDetailViewModelTest]: [getSessionById] either emits a
 * fixed session, or — when [failure] is set — throws from its `Flow` when collected, simulating a
 * Room query failure surfacing through the `init` block's `combine(...).collect { ... }`.
 */
private class FakeTrackingSessionDaoForSessionDetail(
    private val session: TrackingSessionEntity? = null,
    private val failure: Throwable? = null,
) : TrackingSessionDao {
    override suspend fun insert(session: TrackingSessionEntity) = error("not used in this test")
    override suspend fun update(session: TrackingSessionEntity) = error("not used in this test")

    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> =
        failure?.let { flow { throw it } } ?: flowOf(session)

    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = error("not used in this test")
    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
        error("not used in this test")
    override suspend fun deleteById(sessionId: String) = error("not used in this test")
    override suspend fun deleteAll() = error("not used in this test")
    override suspend fun getFinishedSessionsWithoutLocationNameSince(
        status: SessionStatus,
        sinceTimestamp: Long,
    ): List<TrackingSessionEntity> = error("not used in this test")
    override suspend fun updateLocationName(sessionId: String, locationName: String?) =
        error("not used in this test")
}

/** In-memory [GpsPointDao] fake with a couple of fixed points for a session. */
private class FakeGpsPointDao(sessionId: String) : GpsPointDao {
    private val points = listOf(
        GpsPointEntity(sessionId = sessionId, timestamp = 0L, latitude = 0.0, longitude = 0.0, accuracy = 5f),
        GpsPointEntity(sessionId = sessionId, timestamp = 10_000L, latitude = 0.001, longitude = 0.0, accuracy = 5f),
    )

    override suspend fun insert(point: GpsPointEntity): Long = 0L
    override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> = emptyList()
    override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> = flowOf(points)
    override suspend fun countForSession(sessionId: String): Int = points.size
}

/**
 * T13 — [SessionDetailViewModel] failure-containment unit test (RF-07): the `init` block's
 * `combine(...).collect { ... }` is wrapped in try/catch so a failing DAO `Flow` is logged and
 * never crashes the ViewModel, leaving `uiState` at its last valid value (here, the never-loaded
 * initial default). Robolectric-backed (real Android `Context`) since [SettingsRepository] needs
 * it for Jetpack DataStore, matching `HistoryViewModelTest`/`TrackingViewModelTest`'s established
 * pattern for this codebase.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SessionDetailViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        dataStoreName = "test_session_detail_vm_settings_${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun settingsRepository() = SettingsRepository(context, dataStoreName)

    private suspend fun awaitEntry(
        logger: FakeLoggerForSessionDetail,
        timeoutMillis: Long = 5_000,
        predicate: (FakeLoggerForSessionDetail.Entry) -> Boolean,
    ): FakeLoggerForSessionDetail.Entry = withTimeout(timeoutMillis) {
        var match = logger.entries.find(predicate)
        while (match == null) {
            delay(10)
            match = logger.entries.find(predicate)
        }
        match
    }

    @Test
    fun `a failing session Flow is logged and does not crash, leaving uiState at its initial default`() =
        runBlocking {
            val sessionId = "session-1"
            val logger = FakeLoggerForSessionDetail()
            val trackingSessionDao = FakeTrackingSessionDaoForSessionDetail(failure = IllegalStateException("query failed"))
            val gpsPointDao = FakeGpsPointDao(sessionId)

            val viewModel = SessionDetailViewModel(
                sessionId = sessionId,
                trackingSessionDao = trackingSessionDao,
                gpsPointDao = gpsPointDao,
                settingsRepository = settingsRepository(),
                logger = logger,
            )

            val entry = awaitEntry(logger) { it.message == "Failed to load session detail" }
            assertEquals(LogLevel.ERROR, entry.level)
            assertEquals("SessionDetailViewModel", entry.tag)
            assertTrue(entry.throwable is IllegalStateException)

            // No exception escaped construction/collection, and uiState never advanced past its
            // never-loaded initial default (isLoaded stays false; nothing was ever emitted).
            assertEquals(SessionDetailUiState(sessionId = sessionId), viewModel.uiState.value)
            assertTrue(!viewModel.uiState.value.isLoaded)
        }

    @Test
    fun `a successful session Flow still loads uiState normally (no regression from the try-catch)`() =
        runBlocking {
            val sessionId = "session-2"
            val logger = FakeLoggerForSessionDetail()
            val session = TrackingSessionEntity(
                id = sessionId,
                samplingIntervalSeconds = 10,
                startTimestamp = 0L,
                endTimestamp = 10_000L,
                status = SessionStatus.FINISHED,
            )
            val trackingSessionDao = FakeTrackingSessionDaoForSessionDetail(session = session)
            val gpsPointDao = FakeGpsPointDao(sessionId)

            val viewModel = SessionDetailViewModel(
                sessionId = sessionId,
                trackingSessionDao = trackingSessionDao,
                gpsPointDao = gpsPointDao,
                settingsRepository = settingsRepository(),
                logger = logger,
            )

            val state = withTimeout(5_000) {
                var current = viewModel.uiState.value
                while (!current.isLoaded) {
                    delay(10)
                    current = viewModel.uiState.value
                }
                current
            }

            assertEquals(SessionStatus.FINISHED, state.status)
            assertTrue(logger.entries.isEmpty())
        }
}
