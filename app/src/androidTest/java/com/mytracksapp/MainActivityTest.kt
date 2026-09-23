package com.mytracksapp

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.ui.history.HistoryListScreenTestTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Follow-up "wire it all together" task — smoke test proving the app is actually launchable:
 * [MainActivity] starts without crashing and lands on the History screen (this app's navigation
 * start destination, per `MyTracksApp` in `ui/navigation/AppNavigation.kt` — a later follow-up
 * phase changed the start destination from New Session to History so the hamburger-drawer
 * Settings entry point and the bottom bar are both reachable immediately on launch).
 *
 * Deliberately does NOT grant location permissions ahead of time (e.g. via
 * `androidx.test.rule.GrantPermissionRule`): revoking a runtime permission from a package whose
 * process is currently alive force-kills that process immediately (verified empirically here —
 * it took the whole instrumentation process down with it, since instrumented tests run inside the
 * app-under-test's process). `GrantPermissionRule` never revokes what it grants, so using it here
 * would permanently grant `ACCESS_BACKGROUND_LOCATION` for the rest of this `connectedAndroidTest`
 * invocation and silently break `NewSessionScreenTest`'s RF-03 denial-message test, which requires
 * the permission to still be ungranted.
 */
@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launchesWithoutCrashing_andShowsHistoryScreenAsStartDestination() {
        composeTestRule
            .onNodeWithTag(HistoryListScreenTestTags.SCREEN)
            .assertIsDisplayed()
    }
}
