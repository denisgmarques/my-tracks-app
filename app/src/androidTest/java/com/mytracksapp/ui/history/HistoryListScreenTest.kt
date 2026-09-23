package com.mytracksapp.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.data.settings.SettingsRepository
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for a real bug found on-device: [HistoryListScreen]'s row `Card` had no
 * `clickable` modifier at all, so tapping a session in the history list did nothing — the
 * `onSessionClick` callback was accepted as a parameter but never invoked anywhere. This test
 * exercises the actual tap, not just that the callback exists.
 *
 * Also covers the developer's follow-up display feedback: the raw session id is no longer shown,
 * the row shows the trip's duration instead of the raw sampling interval, and the date renders as
 * `DD/MM/YYYY`.
 */
@RunWith(AndroidJUnit4::class)
class HistoryListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sessionId = "history-click-test-session"

    private val session = TrackingSessionEntity(
        id = sessionId,
        samplingIntervalSeconds = 15,
        // 2024-03-05T00:00:00Z, ends 90s later — deterministic, timezone-independent date.
        startTimestamp = 1_709_596_800_000L,
        endTimestamp = 1_709_596_890_000L,
        status = SessionStatus.FINISHED,
    )

    /** Reactive/mutable so the delete-flow tests can assert the row disappears after confirming. */
    private class FakeMutableTrackingSessionDao(initial: List<TrackingSessionEntity>) : TrackingSessionDao {
        private val state = MutableStateFlow(initial)
        val deletedIds = mutableListOf<String>()

        override suspend fun insert(session: TrackingSessionEntity) = Unit
        override suspend fun update(session: TrackingSessionEntity) = Unit
        override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> =
            state.map { sessions -> sessions.find { it.id == sessionId } }

        override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = state
        override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
            state.map { sessions -> sessions.filter { it.status == status } }

        override suspend fun deleteById(sessionId: String) {
            deletedIds += sessionId
            state.value = state.value.filterNot { it.id == sessionId }
        }

        override suspend fun deleteAll() {
            state.value = emptyList()
        }

        override suspend fun updateLocationName(sessionId: String, locationName: String?) {
            state.value = state.value.map { session ->
                if (session.id == sessionId) session.copy(locationName = locationName) else session
            }
        }
    }

    private val fakeTrackingSessionDao = FakeMutableTrackingSessionDao(listOf(session))

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun uniqueDataStoreName() {
        dataStoreName = "test_history_list_screen_settings_${UUID.randomUUID()}"
    }

    @After
    fun deleteDataStoreFile() {
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun historyViewModel() = HistoryViewModel(fakeTrackingSessionDao, SettingsRepository(context, dataStoreName))

    /**
     * [HistoryViewModel] (T11) now combines the sessions flow with [SettingsRepository]'s
     * `Flow<UserSettings>`, which does its real I/O on DataStore's own dispatcher — the item may
     * not be in the tree on the very first frame. Waiting for it explicitly (like other
     * DataStore-backed screens in this suite already do) avoids flaky touch-input injection races.
     */
    private fun waitForItem() {
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(HistoryListScreenTestTags.item(sessionId)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun tappingASessionRowInvokesOnSessionClickWithItsId() {
        var clickedId: String? = null

        composeTestRule.setContent {
            HistoryListScreen(
                viewModel = historyViewModel(),
                onSessionClick = { clickedId = it },
            )
        }

        waitForItem()
        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.item(sessionId)).performClick()

        assertEquals(sessionId, clickedId)
    }

    @Test
    fun rowShowsDurationAndFormattedDateButNeverTheRawSessionId() {
        composeTestRule.setContent {
            HistoryListScreen(
                viewModel = historyViewModel(),
                onSessionClick = {},
            )
        }

        waitForItem()

        // useUnmergedTree = true: the row's clickable Card merges its descendants' semantics into
        // itself (needed for the click test above to treat the whole row as one target), which
        // makes the child Text nodes' own testTags fail a MERGED-tree lookup even though they're
        // genuinely on screen — a well-known Compose-testing gotcha for anything inside a
        // clickable container.
        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.itemStartDate(sessionId), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.itemDuration(sessionId), useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("Duração: 1:30", useUnmergedTree = true).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(sessionId).assertCountEquals(0)
    }

    @Test
    fun swipingLeftThenConfirmingDeletesTheSessionAndRemovesTheRow() {
        composeTestRule.setContent {
            HistoryListScreen(
                viewModel = historyViewModel(),
                onSessionClick = {},
            )
        }

        waitForItem()
        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.item(sessionId))
            .performTouchInput { swipeLeft() }

        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.DELETE_CONFIRM_DIALOG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.DELETE_CONFIRM_BUTTON).performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            fakeTrackingSessionDao.deletedIds.contains(sessionId)
        }
        assertEquals(listOf(sessionId), fakeTrackingSessionDao.deletedIds)
        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.EMPTY_MESSAGE).assertIsDisplayed()
    }

    @Test
    fun swipingLeftThenCancellingDoesNotDeleteAndKeepsTheRow() {
        composeTestRule.setContent {
            HistoryListScreen(
                viewModel = historyViewModel(),
                onSessionClick = {},
            )
        }

        waitForItem()
        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.item(sessionId))
            .performTouchInput { swipeLeft() }
        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.DELETE_CANCEL_BUTTON).performClick()

        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.item(sessionId)).assertIsDisplayed()
        assertEquals(emptyList<String>(), fakeTrackingSessionDao.deletedIds)
    }

    @Test
    fun swipingRightDoesNothing() {
        composeTestRule.setContent {
            HistoryListScreen(
                viewModel = historyViewModel(),
                onSessionClick = {},
            )
        }

        waitForItem()
        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.item(sessionId))
            .performTouchInput { swipeRight() }

        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.item(sessionId)).assertIsDisplayed()
        composeTestRule.onAllNodesWithText("Excluir sessão?").assertCountEquals(0)
    }
}
