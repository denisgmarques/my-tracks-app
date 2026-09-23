package com.mytracksapp.ui.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.data.settings.UserSettings
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.units.DistanceUnit
import com.mytracksapp.domain.units.SpeedUnit
import java.util.UUID
import kotlinx.coroutines.flow.first
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

    @Test
    fun selectingSamplingInterval_persistsViaRepository() {
        val repository = repository()
        composeTestRule.setContent {
            SettingsScreen(viewModel = SettingsViewModel(repository))
        }

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
            SettingsScreen(viewModel = SettingsViewModel(repository))
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
            SettingsScreen(viewModel = SettingsViewModel(repository))
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
            SettingsScreen(viewModel = SettingsViewModel(repository))
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
            SettingsScreen(viewModel = SettingsViewModel(repository))
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
            SettingsScreen(viewModel = SettingsViewModel(repository))
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
            SettingsScreen(viewModel = SettingsViewModel(repository))
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
}
