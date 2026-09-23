package com.mytracksapp.ui.newsession

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.session.SessionController
import com.mytracksapp.domain.session.SessionStartOutcome
import com.mytracksapp.permission.LocationPermissionManager
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented Compose UI test for [NewSessionScreen] (follow-up phase — simplified).
 *
 * The original per-session [SamplingInterval] picker (UI-01) that used to live on this screen was
 * removed: Settings now owns the interval exclusively (`com.mytracksapp.ui.settings.SettingsScreen`).
 * This screen only reads the currently configured value from [SettingsRepository] read-only and
 * exposes a single "Iniciar sessão" action, still gated by the RF-03 permission check.
 *
 * Each test uses a uniquely-named DataStore file (same pattern as `SettingsRepositoryTest`) so
 * tests never see each other's persisted state.
 */
@RunWith(AndroidJUnit4::class)
class NewSessionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var dataStoreName: String

    @Before
    fun uniqueDataStoreName() {
        dataStoreName = "test_new_session_settings_${UUID.randomUUID()}"
    }

    @After
    fun deleteDataStoreFile() {
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun settingsRepository() = SettingsRepository(context, dataStoreName)

    private val fakeSessionController = object : SessionController {
        override suspend fun startSession(interval: SamplingInterval): SessionStartOutcome =
            SessionStartOutcome.Started(sessionId = "fake-session-id")

        override suspend fun stopSession(sessionId: String) = Unit
    }

    private fun newViewModel(settingsRepository: SettingsRepository): NewSessionViewModel =
        NewSessionViewModel(LocationPermissionManager(context), fakeSessionController, settingsRepository)

    @Test
    fun showsDefaultConfiguredInterval_whenNothingPersistedYet() {
        composeTestRule.setContent {
            NewSessionScreen(viewModel = newViewModel(settingsRepository()))
        }

        composeTestRule
            .onNodeWithTag(NewSessionScreenTestTags.CONFIGURED_INTERVAL_TEXT)
            .assertIsDisplayed()
            .assertTextContains("${SamplingInterval.ONE_SECOND.seconds} s", substring = true)
    }

    @Test
    fun showsConfiguredInterval_fromSettingsRepository() {
        val repository = settingsRepository()
        runBlocking { repository.setSamplingInterval(SamplingInterval.THIRTY_SECONDS) }

        composeTestRule.setContent {
            NewSessionScreen(viewModel = newViewModel(repository))
        }

        composeTestRule
            .onNodeWithTag(NewSessionScreenTestTags.CONFIGURED_INTERVAL_TEXT)
            .assertIsDisplayed()
            .assertTextContains("${SamplingInterval.THIRTY_SECONDS.seconds} s", substring = true)
    }

    @Test
    fun hasNoPickerUI_onlyAStartButton() {
        composeTestRule.setContent {
            NewSessionScreen(viewModel = newViewModel(settingsRepository()))
        }

        // No selectable interval option nodes exist anywhere in the tree (the picker was removed).
        SamplingInterval.entries.forEach { interval ->
            composeTestRule
                .onAllNodesWithTag("interval_option_${interval.seconds}")
                .assertCountEquals(0)
        }

        composeTestRule
            .onNodeWithTag(NewSessionScreenTestTags.CONFIRM_BUTTON)
            .assertIsDisplayed()
    }

    @Test
    fun confirmingWithoutBackgroundLocationPermission_showsPermissionMessage_perRf03() {
        composeTestRule.setContent {
            NewSessionScreen(viewModel = newViewModel(settingsRepository()))
        }

        composeTestRule
            .onNodeWithTag(NewSessionScreenTestTags.CONFIRM_BUTTON)
            .performClick()

        // The instrumented test app has not been granted ACCESS_BACKGROUND_LOCATION, so the
        // ViewModel must surface the permission message instead of ever calling SessionController.
        composeTestRule
            .onNodeWithTag(NewSessionScreenTestTags.PERMISSION_DENIED_MESSAGE)
            .assertIsDisplayed()
    }
}
