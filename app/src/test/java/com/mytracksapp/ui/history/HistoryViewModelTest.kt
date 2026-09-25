package com.mytracksapp.ui.history

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.units.DistanceUnit
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
import kotlinx.coroutines.flow.map
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

/** Mutable [TrackingSessionDao] double for [HistoryViewModelTest] — tracks [deletedIds]. */
private class FakeTrackingSessionDaoForHistory(initial: List<TrackingSessionEntity>) : TrackingSessionDao {
    private val state = MutableStateFlow(initial)
    val deletedIds = mutableListOf<String>()

    /** T10: when set, [getSessionsByStatus]'s returned flow throws this instead of ever emitting. */
    var throwOnGetSessionsByStatus: Throwable? = null

    /** T10: when set, [deleteById] throws this instead of mutating [state]. */
    var throwOnDeleteById: Throwable? = null

    override suspend fun insert(session: TrackingSessionEntity) = error("not used in this test")
    override suspend fun update(session: TrackingSessionEntity) = error("not used in this test")

    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> =
        state.map { sessions -> sessions.find { it.id == sessionId } }

    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = state

    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> = flow {
        throwOnGetSessionsByStatus?.let { throw it }
        emitAll(state.map { sessions -> sessions.filter { it.status == status } })
    }

    override suspend fun deleteById(sessionId: String) {
        throwOnDeleteById?.let { throw it }
        deletedIds += sessionId
        state.value = state.value.filterNot { it.id == sessionId }
    }

    override suspend fun deleteAll() {
        state.value = emptyList()
    }

    override suspend fun getFinishedSessionsWithoutLocationNameSince(
        status: SessionStatus,
        sinceTimestamp: Long,
    ): List<TrackingSessionEntity> = error("not used in this test")

    override suspend fun updateLocationName(sessionId: String, locationName: String?) {
        state.value = state.value.map { session ->
            if (session.id == sessionId) session.copy(locationName = locationName) else session
        }
    }
}

/** In-memory [Logger] double — records every logged entry for assertions (T10). */
private class FakeLoggerForHistory : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/**
 * T11 — [HistoryViewModel] unit test (UI-01, UI-02, RF-13): finished sessions are mapped to
 * [HistoryListItem]s carrying id (for navigation), start date, trip duration, location name (or
 * the generic placeholder), and unit-aware formatted distance.
 *
 * Now Robolectric-backed (`@RunWith(RobolectricTestRunner::class)`) rather than a plain JUnit
 * test with `StandardTestDispatcher`/`runTest`: [HistoryViewModel] depends on [SettingsRepository]
 * (T11), which needs a real Android `Context` under the hood for Jetpack DataStore — the same
 * pattern already established by `TrackingViewModelTest`. Real-time polling (`awaitState`) is used
 * instead of virtual time for the same reason documented there: DataStore's `Flow<Preferences>`
 * does its actual I/O on its own internal real dispatcher, which `advanceUntilIdle()` cannot drive.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HistoryViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        dataStoreName = "test_history_vm_settings_${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun settingsRepository() = SettingsRepository(context, dataStoreName)

    private suspend fun awaitState(
        viewModel: HistoryViewModel,
        timeoutMillis: Long = 5_000,
        predicate: (HistoryUiState) -> Boolean,
    ): HistoryUiState = withTimeout(timeoutMillis) {
        var state = viewModel.uiState.value
        while (!predicate(state)) {
            delay(10)
            state = viewModel.uiState.value
        }
        state
    }

    @Test
    fun `only finished sessions appear, mapped with id, start timestamp, duration, location and distance`() =
        runBlocking {
            val dao = FakeTrackingSessionDaoForHistory(
                listOf(
                    TrackingSessionEntity(
                        id = "finished-1",
                        samplingIntervalSeconds = 15,
                        startTimestamp = 1_700_000_000_000L,
                        endTimestamp = 1_700_000_060_000L,
                        status = SessionStatus.FINISHED,
                        locationName = "Parque Ibirapuera",
                        distanceMeters = 5_000.0,
                    ),
                    TrackingSessionEntity(
                        id = "finished-2",
                        samplingIntervalSeconds = 60,
                        startTimestamp = 1_700_001_000_000L,
                        endTimestamp = 1_700_001_500_000L,
                        status = SessionStatus.FINISHED,
                        locationName = null,
                        distanceMeters = 1_000.0,
                    ),
                    TrackingSessionEntity(
                        id = "still-active",
                        samplingIntervalSeconds = 30,
                        startTimestamp = 1_700_002_000_000L,
                        status = SessionStatus.ACTIVE,
                    ),
                ),
            )

            val viewModel = HistoryViewModel(dao, settingsRepository())
            val state = awaitState(viewModel) { it.sessions.size == 2 }

            val items = state.sessions
            assertTrue(items.none { it.sessionId == "still-active" })

            val first = items.first { it.sessionId == "finished-1" }
            assertEquals(1_700_000_000_000L, first.startTimestamp)
            assertEquals(60_000L, first.durationMillis)
            assertTrue(first.formattedStartDate.isNotBlank())
            assertEquals("1:00", first.formattedDuration)
            assertEquals("Parque Ibirapuera", first.formattedLocationName)
            assertEquals(DistanceUnit.KM, first.distanceUnit)
            assertEquals("5.00 km", first.formattedDistance)

            val second = items.first { it.sessionId == "finished-2" }
            assertEquals(1_700_001_000_000L, second.startTimestamp)
            assertEquals(500_000L, second.durationMillis)
            // UI-01: a null locationName maps to a non-blank, generic placeholder, never blank/null.
            assertEquals(GENERIC_LOCATION_NAME_PLACEHOLDER, second.formattedLocationName)
            assertTrue(second.formattedLocationName.isNotBlank())
        }

    @Test
    fun `no finished sessions yields an empty list`() = runBlocking {
        val dao = FakeTrackingSessionDaoForHistory(emptyList())
        val viewModel = HistoryViewModel(dao, settingsRepository())

        // Wait for at least one settings emission so we know the combine has actually run once,
        // not just that the initial (empty) default state happens to already match.
        val state = awaitState(viewModel) { true }
        delay(50)

        assertTrue(state.sessions.isEmpty())
        assertTrue(viewModel.uiState.value.sessions.isEmpty())
    }

    @Test
    fun `formatted distance reflects the currently configured distance unit, and reacts to changes`() =
        runBlocking {
            val dao = FakeTrackingSessionDaoForHistory(
                listOf(
                    TrackingSessionEntity(
                        id = "finished-1",
                        samplingIntervalSeconds = 15,
                        startTimestamp = 0L,
                        endTimestamp = 60_000L,
                        status = SessionStatus.FINISHED,
                        distanceMeters = 1_609.344,
                    ),
                ),
            )
            val repository = settingsRepository()
            val viewModel = HistoryViewModel(dao, repository)

            var state = awaitState(viewModel) { it.sessions.size == 1 }
            assertEquals(DistanceUnit.KM, state.sessions.single().distanceUnit)
            assertEquals("1.61 km", state.sessions.single().formattedDistance)

            repository.setDistanceUnit(DistanceUnit.MILES)

            state = awaitState(viewModel) { it.sessions.singleOrNull()?.distanceUnit == DistanceUnit.MILES }
            assertEquals("1.00 mi", state.sessions.single().formattedDistance)
        }

    private suspend fun awaitLogEntry(logger: FakeLoggerForHistory, timeoutMillis: Long = 5_000) {
        withTimeout(timeoutMillis) {
            while (logger.entries.isEmpty()) {
                delay(10)
            }
        }
    }

    @Test
    fun `init logs and swallows a getSessionsByStatus failure, leaving uiState at its last valid value`() =
        runBlocking {
            val dao = FakeTrackingSessionDaoForHistory(emptyList())
            val thrown = IllegalStateException("getSessionsByStatus boom")
            dao.throwOnGetSessionsByStatus = thrown
            val logger = FakeLoggerForHistory()

            val viewModel = HistoryViewModel(dao, settingsRepository(), logger)

            awaitLogEntry(logger)

            val errorEntries = logger.entries.filter { it.level == LogLevel.ERROR }
            assertEquals(1, errorEntries.size)
            assertEquals("HistoryViewModel", errorEntries.single().tag)
            // Compared by type+message, not instance: kotlinx.coroutines recovers the stack trace
            // of exceptions crossing suspension points by copying them, so the logged throwable is
            // not the same object identity as `thrown` even though it represents the same failure.
            assertEquals(thrown::class, errorEntries.single().throwable?.let { it::class })
            assertEquals(thrown.message, errorEntries.single().throwable?.message)

            // uiState stays at its last valid value: the initial default, since the combine never
            // successfully emitted before failing.
            assertEquals(HistoryUiState(), viewModel.uiState.value)
        }

    @Test
    fun `deleteSession logs and swallows a DAO failure, leaving the session unremoved`() = runBlocking {
        val session = TrackingSessionEntity(
            id = "finished-1",
            samplingIntervalSeconds = 15,
            startTimestamp = 0L,
            endTimestamp = 60_000L,
            status = SessionStatus.FINISHED,
        )
        val dao = FakeTrackingSessionDaoForHistory(listOf(session))
        val thrown = IllegalStateException("deleteById boom")
        dao.throwOnDeleteById = thrown
        val logger = FakeLoggerForHistory()

        val viewModel = HistoryViewModel(dao, settingsRepository(), logger)
        awaitState(viewModel) { it.sessions.size == 1 }

        viewModel.deleteSession(session.id)

        awaitLogEntry(logger)

        val errorEntries = logger.entries.filter { it.level == LogLevel.ERROR }
        assertEquals(1, errorEntries.size)
        assertEquals("HistoryViewModel", errorEntries.single().tag)
        assertEquals(thrown, errorEntries.single().throwable)

        assertTrue(dao.deletedIds.isEmpty())
        // uiState unchanged: the session is still present since the throw happened before any
        // state mutation.
        assertEquals(1, viewModel.uiState.value.sessions.size)
    }
}
