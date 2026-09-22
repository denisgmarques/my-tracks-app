package com.mytracksapp.ui.newsession

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.session.SessionController
import com.mytracksapp.domain.session.SessionStartOutcome
import com.mytracksapp.permission.LocationPermissionManager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T04 — instrumented Compose UI test for [NewSessionScreen] (UI-01):
 *  - all 9 [SamplingInterval] options are rendered;
 *  - no free numeric/text input is exposed anywhere on the screen for the interval.
 */
@RunWith(AndroidJUnit4::class)
class NewSessionScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val fakeSessionController = object : SessionController {
        override suspend fun startSession(interval: SamplingInterval): SessionStartOutcome =
            SessionStartOutcome.Started(sessionId = "fake-session-id")

        override suspend fun stopSession(sessionId: String) = Unit
    }

    private fun newViewModel(): NewSessionViewModel {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return NewSessionViewModel(LocationPermissionManager(context), fakeSessionController)
    }

    @Test
    fun rendersExactlyTheNineIntervalOptions() {
        composeTestRule.setContent {
            NewSessionScreen(viewModel = newViewModel())
        }

        // Exactly 9 selectable rows exist as children of the options list.
        composeTestRule
            .onNodeWithTag(NewSessionScreenTestTags.INTERVAL_OPTIONS_LIST)
            .onChildren()
            .assertCountEquals(SamplingInterval.entries.size)

        // Every one of the 9 allowed values is individually present and displayed.
        SamplingInterval.entries.forEach { interval ->
            composeTestRule
                .onNodeWithTag(NewSessionScreenTestTags.intervalOption(interval))
                .assertIsDisplayed()
        }

        assertEquals(9, SamplingInterval.entries.size)
    }

    @Test
    fun exposesNoFreeTextOrNumericInputForInterval() {
        composeTestRule.setContent {
            NewSessionScreen(viewModel = newViewModel())
        }

        // No node anywhere in the tree exposes a SetText semantics action — i.e. there is no
        // text field / numeric input a user could type an arbitrary interval value into.
        composeTestRule
            .onAllNodes(hasSetTextAction())
            .assertCountEquals(0)
    }

    @Test
    fun selectingAnOption_updatesSelection() {
        composeTestRule.setContent {
            NewSessionScreen(viewModel = newViewModel())
        }

        composeTestRule
            .onNodeWithTag(NewSessionScreenTestTags.intervalOption(SamplingInterval.SIXTY_SECONDS))
            .performClick()

        composeTestRule
            .onNodeWithTag(NewSessionScreenTestTags.intervalOption(SamplingInterval.SIXTY_SECONDS))
            .assertIsDisplayed()
    }

    @Test
    fun confirmingWithoutBackgroundLocationPermission_showsPermissionMessage_perRf03() {
        composeTestRule.setContent {
            NewSessionScreen(viewModel = newViewModel())
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
