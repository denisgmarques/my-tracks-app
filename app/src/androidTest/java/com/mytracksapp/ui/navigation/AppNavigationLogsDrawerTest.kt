package com.mytracksapp.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.export.ExportService
import com.mytracksapp.domain.geocoding.GeocodeAndPersist
import com.mytracksapp.domain.geocoding.GeocodingRetryOnStartup
import com.mytracksapp.domain.geocoding.ReverseGeocoder
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.session.OrphanedSessionRecovery
import com.mytracksapp.domain.session.SessionController
import com.mytracksapp.domain.session.SessionStartOutcome
import com.mytracksapp.permission.LocationPermissionManager
import com.mytracksapp.ui.logs.LogViewerScreenTestTags
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T12 (UI-01, CT-01) — the "Ver logs" drawer item, exercised the same direct-composable way
 * [OrphanedSessionRecoverySnackbarTest] does: `composeTestRule.setContent { MyTracksApp(...) }`
 * with fake/no-op dependencies for every parameter this test doesn't care about, since
 * [OrphanedSessionRecovery]/[GeocodingRetryOnStartup] are kept concrete classes (not
 * interfaces/fake-doubles) per this codebase's "fake the DAO, not the domain class" convention
 * (`docs/agents/coding_guidelines.md` #3).
 *
 * Opens the drawer, taps [AppNavigationTestTags.DRAWER_LOGS_ITEM], and asserts the drawer closes
 * and [LogViewerScreenTestTags.SCREEN] becomes displayed (UI-01's AC: "tapping it closes the
 * drawer and navigates to the new log-viewer route").
 */
@RunWith(AndroidJUnit4::class)
class AppNavigationLogsDrawerTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun uniqueDataStoreName() {
        dataStoreName = "test_logs_drawer_settings_${UUID.randomUUID()}"
    }

    @After
    fun deleteDataStoreFile() {
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    /** No-op [TrackingSessionDao] — no test here relies on any seeded/persisted session state. */
    private class NoopTrackingSessionDao : TrackingSessionDao {
        private val state = MutableStateFlow<List<TrackingSessionEntity>>(emptyList())

        override suspend fun insert(session: TrackingSessionEntity) {
            state.value = state.value + session
        }

        override suspend fun update(session: TrackingSessionEntity) {
            state.value = state.value.map { if (it.id == session.id) session else it }
        }

        override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> =
            state.map { sessions -> sessions.find { it.id == sessionId } }

        override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = state

        override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
            state.map { sessions -> sessions.filter { it.status == status } }

        override suspend fun deleteById(sessionId: String) {
            state.value = state.value.filterNot { it.id == sessionId }
        }

        override suspend fun deleteAll() {
            state.value = emptyList()
        }

        override suspend fun getFinishedSessionsWithoutLocationNameSince(
            status: SessionStatus,
            sinceTimestamp: Long,
        ): List<TrackingSessionEntity> = emptyList()

        override suspend fun updateLocationName(sessionId: String, locationName: String?) = Unit
    }

    /** No-op [GpsPointDao] — no test here records/reads any real GPS point. */
    private class NoopGpsPointDao : GpsPointDao {
        override suspend fun insert(point: GpsPointEntity): Long = 0L
        override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> = emptyList()
        override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> = flowOf(emptyList())
        override suspend fun countForSession(sessionId: String): Int = 0
    }

    /** No-op [ReverseGeocoder] for [MyTracksApp]'s own `geocodingRetryOnStartup` param — never resolves a name. */
    private class NoopReverseGeocoder : ReverseGeocoder {
        override suspend fun reverseGeocode(latitude: Double, longitude: Double): String? = null
    }

    /** No-op [SessionController] — no test here ever starts/stops a real session. */
    private class NoopSessionController : SessionController {
        override suspend fun startSession(interval: SamplingInterval): SessionStartOutcome =
            SessionStartOutcome.PermissionDenied

        override suspend fun stopSession(sessionId: String) = Unit
    }

    @Test
    fun tappingLogsDrawerItem_closesDrawer_andShowsLogViewerScreen() {
        composeTestRule.setContent {
            MyTracksApp(
                trackingSessionDao = NoopTrackingSessionDao(),
                gpsPointDao = NoopGpsPointDao(),
                sessionController = NoopSessionController(),
                permissionManager = LocationPermissionManager(context),
                exportService = ExportService(NoopTrackingSessionDao(), NoopGpsPointDao()),
                settingsRepository = SettingsRepository(context, dataStoreName),
                orphanedSessionRecovery = OrphanedSessionRecovery(
                    trackingSessionDao = NoopTrackingSessionDao(),
                    gpsPointDao = NoopGpsPointDao(),
                ),
                geocodingRetryOnStartup = GeocodingRetryOnStartup(
                    trackingSessionDao = NoopTrackingSessionDao(),
                    gpsPointDao = NoopGpsPointDao(),
                    geocodeAndPersist = GeocodeAndPersist(NoopReverseGeocoder(), NoopTrackingSessionDao()),
                ),
            )
        }

        // Open the drawer via the top bar's menu button.
        composeTestRule.onNodeWithTag(AppNavigationTestTags.MENU_BUTTON).performClick()
        composeTestRule.onNodeWithTag(AppNavigationTestTags.DRAWER).assertIsDisplayed()

        // Tap "Ver logs".
        composeTestRule.onNodeWithTag(AppNavigationTestTags.DRAWER_LOGS_ITEM).performClick()

        // The drawer closes and the log viewer screen is shown (UI-01's AC).
        composeTestRule.onNodeWithTag(AppNavigationTestTags.DRAWER).assertIsNotDisplayed()
        composeTestRule.onNodeWithTag(LogViewerScreenTestTags.SCREEN).assertIsDisplayed()
    }
}
