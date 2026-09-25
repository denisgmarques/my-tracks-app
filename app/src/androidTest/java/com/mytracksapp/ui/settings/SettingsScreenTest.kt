package com.mytracksapp.ui.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.data.settings.UserSettings
import com.mytracksapp.domain.export.ExportFormat
import com.mytracksapp.domain.model.GpsPrecision
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.units.DistanceUnit
import com.mytracksapp.domain.units.SpeedUnit
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI test for [SettingsScreen] (follow-up phase): the enum-backed selectors
 * (sampling interval, speed unit, distance unit) persist immediately on tap, and the two numeric
 * fields (stop radius/duration) persist only on the IME "Done" action, with invalid input
 * rejected and surfaced as an error instead of being written to [SettingsRepository].
 *
 * Follows `SettingsRepositoryTest`'s pattern: a uniquely-named DataStore file per test, cleaned up
 * in `@After`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun uniqueDataStoreName() {
        dataStoreName = "test_settings_screen_${UUID.randomUUID()}"
    }

    @After
    fun deleteDataStoreFile() {
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun repository() = SettingsRepository(context, dataStoreName)

    /**
     * T12 — [SettingsViewModel] now also takes a [TrackingSessionDao] (for [SettingsViewModel.clearHistory],
     * UI-08). This test file predates that control (still T15's job); a no-op fake keeps every
     * pre-existing test here compiling and passing unmodified.
     */
    private class NoOpTrackingSessionDao : TrackingSessionDao {
        override suspend fun insert(session: TrackingSessionEntity) = Unit
        override suspend fun update(session: TrackingSessionEntity) = Unit
        override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> = flowOf(null)
        override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = flowOf(emptyList())
        override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> = flowOf(emptyList())
        override suspend fun deleteById(sessionId: String) = Unit
        override suspend fun deleteAll() = Unit
        override suspend fun getFinishedSessionsWithoutLocationNameSince(
            status: SessionStatus,
            sinceTimestamp: Long,
        ): List<TrackingSessionEntity> = emptyList()
        override suspend fun updateLocationName(sessionId: String, locationName: String?) = Unit
    }

    private fun settingsViewModel(repository: SettingsRepository) =
        SettingsViewModel(repository, NoOpTrackingSessionDao())

    /** T15 (UI-08) — records how many times [deleteAll] was invoked, for the clear-history flow tests. */
    private class RecordingTrackingSessionDao : TrackingSessionDao {
        var deleteAllCallCount = 0
            private set

        override suspend fun insert(session: TrackingSessionEntity) = Unit
        override suspend fun update(session: TrackingSessionEntity) = Unit
        override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> = flowOf(null)
        override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = flowOf(emptyList())
        override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> = flowOf(emptyList())
        override suspend fun deleteById(sessionId: String) = Unit
        override suspend fun deleteAll() {
            deleteAllCallCount++
        }
        override suspend fun getFinishedSessionsWithoutLocationNameSince(
            status: SessionStatus,
            sinceTimestamp: Long,
        ): List<TrackingSessionEntity> = emptyList()
        override suspend fun updateLocationName(sessionId: String, locationName: String?) = Unit
    }

    @Test
    fun selectingSamplingInterval_persistsViaRepository() {
        val repository = repository()
        composeTestRule.setContent {
            SettingsScreen(viewModel = settingsViewModel(repository))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.INTERVAL_ROW)
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.intervalOption(SamplingInterval.SIXTY_SECONDS))
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { repository.userSettings.first().samplingInterval } == SamplingInterval.SIXTY_SECONDS
        }
        assertEquals(SamplingInterval.SIXTY_SECONDS, runBlocking { repository.userSettings.first().samplingInterval })
    }

    @Test
    fun selectingSpeedUnit_persistsViaRepository() {
        val repository = repository()
        composeTestRule.setContent {
            SettingsScreen(viewModel = settingsViewModel(repository))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.speedUnitOption(SpeedUnit.KNOTS))
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { repository.userSettings.first().speedUnit } == SpeedUnit.KNOTS
        }
        assertEquals(SpeedUnit.KNOTS, runBlocking { repository.userSettings.first().speedUnit })
    }

    @Test
    fun selectingDistanceUnit_persistsViaRepository() {
        val repository = repository()
        composeTestRule.setContent {
            SettingsScreen(viewModel = settingsViewModel(repository))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.distanceUnitOption(DistanceUnit.MILES))
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { repository.userSettings.first().distanceUnit } == DistanceUnit.MILES
        }
        assertEquals(DistanceUnit.MILES, runBlocking { repository.userSettings.first().distanceUnit })
    }

    @Test
    fun committingValidStopRadius_persistsAsMeters() {
        val repository = repository()
        composeTestRule.setContent {
            SettingsScreen(viewModel = settingsViewModel(repository))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_RADIUS_FIELD)
            .performTextClearance()
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_RADIUS_FIELD)
            .performTextInput("75.5")
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_RADIUS_FIELD)
            .performImeAction()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { repository.userSettings.first().stopRadiusMeters } == 75.5
        }
        assertEquals(75.5, runBlocking { repository.userSettings.first().stopRadiusMeters }, 0.0001)
    }

    @Test
    fun committingInvalidStopRadius_showsError_andDoesNotPersist() {
        val repository = repository()
        composeTestRule.setContent {
            SettingsScreen(viewModel = settingsViewModel(repository))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_RADIUS_FIELD)
            .performTextClearance()
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_RADIUS_FIELD)
            .performTextInput("not-a-number")
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_RADIUS_FIELD)
            .performImeAction()

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_RADIUS_ERROR)
            .performScrollTo()
            .assertIsDisplayed()

        // The default persisted value was never overwritten by the invalid input.
        val defaultRadius = UserSettings().stopRadiusMeters
        assertEquals(defaultRadius, runBlocking { repository.userSettings.first().stopRadiusMeters }, 0.0001)
    }

    @Test
    fun committingValidStopDurationMinutes_persistsAsMillis() {
        val repository = repository()
        composeTestRule.setContent {
            SettingsScreen(viewModel = settingsViewModel(repository))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_DURATION_FIELD)
            .performTextClearance()
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_DURATION_FIELD)
            .performTextInput("10")
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_DURATION_FIELD)
            .performImeAction()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { repository.userSettings.first().stopDurationMillis } == 600_000L
        }
        assertEquals(600_000L, runBlocking { repository.userSettings.first().stopDurationMillis })
    }

    @Test
    fun committingInvalidStopDuration_showsError_andDoesNotPersist() {
        val repository = repository()
        composeTestRule.setContent {
            SettingsScreen(viewModel = settingsViewModel(repository))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_DURATION_FIELD)
            .performTextClearance()
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_DURATION_FIELD)
            .performTextInput("-5")
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_DURATION_FIELD)
            .performImeAction()

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.STOP_DURATION_ERROR)
            .performScrollTo()
            .assertIsDisplayed()

        val defaultDurationMillis = UserSettings().stopDurationMillis
        assertEquals(defaultDurationMillis, runBlocking { repository.userSettings.first().stopDurationMillis })
    }

    /** T15 (UI-04): selecting the "Equilibrada" GPS-precision option persists immediately. */
    @Test
    fun selectingGpsPrecision_persistsViaRepository() {
        val repository = repository()
        composeTestRule.setContent {
            SettingsScreen(viewModel = settingsViewModel(repository))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.gpsPrecisionOption(GpsPrecision.BALANCED))
            .performScrollTo()
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { repository.userSettings.first().gpsPrecision } == GpsPrecision.BALANCED
        }
        assertEquals(GpsPrecision.BALANCED, runBlocking { repository.userSettings.first().gpsPrecision })
    }

    /** T15 (UI-05): toggling "Manter tela ativa durante a sessão" persists immediately. */
    @Test
    fun togglingKeepScreenOn_persistsViaRepository() {
        val repository = repository()
        composeTestRule.setContent {
            SettingsScreen(viewModel = settingsViewModel(repository))
        }

        // Default is `true` (RF-06) — toggling once should flip it to `false`.
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.KEEP_SCREEN_ON_SWITCH)
            .performScrollTo()
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { repository.userSettings.first().keepScreenOnEnabled } == false
        }
        assertEquals(false, runBlocking { repository.userSettings.first().keepScreenOnEnabled })
    }

    /** T15 (UI-07): selecting CSV in the export-format picker persists immediately. */
    @Test
    fun selectingExportFormat_persistsViaRepository() {
        val repository = repository()
        composeTestRule.setContent {
            SettingsScreen(viewModel = settingsViewModel(repository))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.EXPORT_FORMAT_ROW)
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.exportFormatOption(ExportFormat.CSV))
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            runBlocking { repository.userSettings.first().defaultExportFormat } == ExportFormat.CSV
        }
        assertEquals(ExportFormat.CSV, runBlocking { repository.userSettings.first().defaultExportFormat })
    }

    /** T15 (UI-08): tapping "Limpar histórico" opens the confirmation dialog and does NOT delete on its own. */
    @Test
    fun tappingClearHistory_opensConfirmationDialog_doesNotDeleteImmediately() {
        val repository = repository()
        val trackingSessionDao = RecordingTrackingSessionDao()
        composeTestRule.setContent {
            SettingsScreen(viewModel = SettingsViewModel(repository, trackingSessionDao))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.CLEAR_HISTORY_ACTION)
            .performScrollTo()
            .performClick()

        composeTestRule.onNodeWithTag(SettingsScreenTestTags.CLEAR_HISTORY_CONFIRM_DIALOG).assertIsDisplayed()
        assertEquals(0, trackingSessionDao.deleteAllCallCount)
    }

    /** T15 (UI-08): cancelling the confirmation dialog never deletes anything. */
    @Test
    fun cancellingClearHistoryDialog_doesNotDelete() {
        val repository = repository()
        val trackingSessionDao = RecordingTrackingSessionDao()
        composeTestRule.setContent {
            SettingsScreen(viewModel = SettingsViewModel(repository, trackingSessionDao))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.CLEAR_HISTORY_ACTION)
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.CLEAR_HISTORY_CANCEL_BUTTON)
            .performClick()

        composeTestRule.onAllNodesWithTag(SettingsScreenTestTags.CLEAR_HISTORY_CONFIRM_DIALOG).assertCountEquals(0)
        assertEquals(0, trackingSessionDao.deleteAllCallCount)
    }

    /** T15 (RF-12, UI-08): confirming the dialog calls `clearHistory()` (and thus `deleteAll()`) exactly once. */
    @Test
    fun confirmingClearHistoryDialog_deletesExactlyOnce() {
        val repository = repository()
        val trackingSessionDao = RecordingTrackingSessionDao()
        composeTestRule.setContent {
            SettingsScreen(viewModel = SettingsViewModel(repository, trackingSessionDao))
        }

        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.CLEAR_HISTORY_ACTION)
            .performScrollTo()
            .performClick()
        composeTestRule
            .onNodeWithTag(SettingsScreenTestTags.CLEAR_HISTORY_CONFIRM_BUTTON)
            .performClick()

        composeTestRule.waitUntil(timeoutMillis = 5_000) { trackingSessionDao.deleteAllCallCount == 1 }
        assertEquals(1, trackingSessionDao.deleteAllCallCount)
    }
}
