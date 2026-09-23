package com.mytracksapp.ui.history

import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Mutable [TrackingSessionDao] double for [HistoryViewModelTest] — tracks [deletedIds]. */
private class FakeTrackingSessionDao(initial: List<TrackingSessionEntity>) : TrackingSessionDao {
    private val state = MutableStateFlow(initial)
    val deletedIds = mutableListOf<String>()

    override suspend fun insert(session: TrackingSessionEntity) = error("not used in this test")
    override suspend fun update(session: TrackingSessionEntity) = error("not used in this test")

    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> =
        state.map { sessions -> sessions.find { it.id == sessionId } }

    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = state

    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
        state.map { sessions -> sessions.filter { it.status == status } }

    override suspend fun deleteById(sessionId: String) {
        deletedIds += sessionId
        state.value = state.value.filterNot { it.id == sessionId }
    }
}

/**
 * T10 — [HistoryViewModel] unit test (UI-04): finished sessions are mapped to
 * [HistoryListItem]s carrying id (for navigation), start date, and trip duration.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModelTest {

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
    fun `only finished sessions appear, mapped with id, start timestamp and interval`() = runTest(testDispatcher) {
        val dao = FakeTrackingSessionDao(
            listOf(
                TrackingSessionEntity(
                    id = "finished-1",
                    samplingIntervalSeconds = 15,
                    startTimestamp = 1_700_000_000_000L,
                    endTimestamp = 1_700_000_060_000L,
                    status = SessionStatus.FINISHED,
                ),
                TrackingSessionEntity(
                    id = "finished-2",
                    samplingIntervalSeconds = 60,
                    startTimestamp = 1_700_001_000_000L,
                    endTimestamp = 1_700_001_500_000L,
                    status = SessionStatus.FINISHED,
                ),
                TrackingSessionEntity(
                    id = "still-active",
                    samplingIntervalSeconds = 30,
                    startTimestamp = 1_700_002_000_000L,
                    status = SessionStatus.ACTIVE,
                ),
            ),
        )

        val viewModel = HistoryViewModel(dao)
        testDispatcher.scheduler.advanceUntilIdle()

        val items = viewModel.uiState.value.sessions
        assertEquals(2, items.size)
        assertTrue(items.none { it.sessionId == "still-active" })

        val first = items.first { it.sessionId == "finished-1" }
        assertEquals(1_700_000_000_000L, first.startTimestamp)
        assertEquals(60_000L, first.durationMillis)
        assertTrue(first.formattedStartDate.isNotBlank())
        assertEquals("1:00", first.formattedDuration)

        val second = items.first { it.sessionId == "finished-2" }
        assertEquals(1_700_001_000_000L, second.startTimestamp)
        assertEquals(500_000L, second.durationMillis)
    }

    @Test
    fun `no finished sessions yields an empty list`() = runTest(testDispatcher) {
        val dao = FakeTrackingSessionDao(emptyList())
        val viewModel = HistoryViewModel(dao)
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue(viewModel.uiState.value.sessions.isEmpty())
    }
}
