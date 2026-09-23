package com.mytracksapp.ui.history

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.maps.model.LatLng
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.ui.tracking.MapComponentTestTags
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T10 — instrumented Compose UI test for [SessionDetailScreen] (UI-03): all 5 metrics (average
 * speed, total distance, total elapsed time, stopped time, moving time) are visible
 * simultaneously, with no extra navigation, the raw session id is never rendered, and instant
 * speed is intentionally absent (this screen only ever shows a finished session — see
 * [SessionDetailUiState]'s doc), plus (Phase C follow-up) the session's route and stop-location
 * pins reach the underlying `MapComponent`.
 *
 * Mirrors `TrackingScreenTest`'s approach for asserting on map content without depending on real
 * Play Services tile rendering: `MapComponent`'s `onPolylineApplied`/`onMarkersApplied` seams
 * report exactly what the screen computed, independent of whether the real `GoogleMap` finished
 * initializing.
 */
@RunWith(AndroidJUnit4::class)
class SessionDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sessionId = "session-detail-test"

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun uniqueDataStoreName() {
        dataStoreName = "test_session_detail_settings_${UUID.randomUUID()}"
    }

    @After
    fun deleteDataStoreFile() {
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun settingsRepository() = SettingsRepository(context, dataStoreName)

    private val fakeTrackingSessionDao = object : TrackingSessionDao {
        private val session = TrackingSessionEntity(
            id = sessionId,
            samplingIntervalSeconds = 15,
            startTimestamp = 0L,
            endTimestamp = 40_000L,
            status = SessionStatus.FINISHED,
            stoppedTimeMillis = 20_000L,
            movingTimeMillis = 20_000L,
            averageSpeedMetersPerSecond = 1.2,
        )

        override suspend fun insert(session: TrackingSessionEntity) = Unit
        override suspend fun update(session: TrackingSessionEntity) = Unit
        override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> = flowOf(session)
        override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = flowOf(listOf(session))
        override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
            flowOf(listOf(session))
        override suspend fun deleteById(sessionId: String) = Unit
    }

    private val fakeGpsPointDao = object : GpsPointDao {
        private val points = listOf(
            GpsPointEntity(sessionId = sessionId, timestamp = 0L, latitude = 0.0, longitude = 0.0, accuracy = 5f),
            GpsPointEntity(sessionId = sessionId, timestamp = 20_000L, latitude = 0.01, longitude = 0.0, accuracy = 5f),
            GpsPointEntity(sessionId = sessionId, timestamp = 40_000L, latitude = 0.02, longitude = 0.0, accuracy = 5f),
        )

        override suspend fun insert(point: GpsPointEntity): Long = 0L
        override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> = emptyList()
        override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> = flowOf(points)
        override suspend fun countForSession(sessionId: String): Int = points.size
    }

    @Test
    fun allFiveMetricsAreVisibleSimultaneouslyWithNoExtraNavigationAndNoSessionIdOrInstantSpeed() {
        composeTestRule.setContent {
            SessionDetailScreen(
                viewModel = SessionDetailViewModel(sessionId, fakeTrackingSessionDao, fakeGpsPointDao, settingsRepository()),
            )
        }

        // The screen intentionally renders nothing until its ViewModel's first real Flow
        // emission lands (SessionDetailUiState.isLoaded) — this avoids a first-frame flash where
        // elements gated on placeholder defaults (e.g. the export button, the Início/Fim map
        // tags) would briefly render in the wrong state. The settings DataStore Flow this depends
        // on reads from disk asynchronously, so this wait is a genuine (not flaky) requirement,
        // not a timing workaround.
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(SessionDetailScreenTestTags.TOTAL_DISTANCE).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.AVERAGE_SPEED).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.TOTAL_DISTANCE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.ELAPSED_TIME).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.STOPPED_TIME).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.MOVING_TIME).assertIsDisplayed()
        composeTestRule.onAllNodesWithText(sessionId).assertCountEquals(0)
    }

    @Test
    fun mapComponentIsGenuinelyPresentAndReceivesTheSessionsRoute() {
        val appliedPolylines = Collections.synchronizedList(mutableListOf<List<LatLng>>())

        composeTestRule.setContent {
            SessionDetailScreen(
                viewModel = SessionDetailViewModel(sessionId, fakeTrackingSessionDao, fakeGpsPointDao, settingsRepository()),
                onPolylineApplied = { appliedPolylines.add(it) },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(MapComponentTestTags.MAP_VIEW).fetchSemanticsNodes().isNotEmpty()
        }
        composeTestRule.onNodeWithTag(MapComponentTestTags.MAP_VIEW).assertIsDisplayed()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            appliedPolylines.isNotEmpty() && appliedPolylines.last().size == 3
        }
        assertEquals(LatLng(0.0, 0.0), appliedPolylines.last().first())
        assertEquals(LatLng(0.02, 0.0), appliedPolylines.last().last())
    }

    @Test
    fun stopLocationsFromCurrentlyConfiguredSettingsReachTheMapAsMarkers() {
        // A tight stop-duration threshold (well below the default 5 minutes) makes the first
        // interval (10s at the exact same coordinate) qualify as a "stop" (RF-06), so this test
        // exercises SessionDetailViewModel's use of the CURRENTLY CONFIGURED settings, not RF-06's
        // hardcoded defaults.
        val stopSessionId = "session-detail-test-stops"
        val stopPoints = listOf(
            GpsPointEntity(sessionId = stopSessionId, timestamp = 0L, latitude = 5.0, longitude = 5.0, accuracy = 5f),
            GpsPointEntity(sessionId = stopSessionId, timestamp = 10_000L, latitude = 5.0, longitude = 5.0, accuracy = 5f),
            GpsPointEntity(sessionId = stopSessionId, timestamp = 20_000L, latitude = 5.1, longitude = 5.1, accuracy = 5f),
        )
        val stopGpsPointDao = object : GpsPointDao {
            override suspend fun insert(point: GpsPointEntity): Long = 0L
            override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> = emptyList()
            override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> = flowOf(stopPoints)
            override suspend fun countForSession(sessionId: String): Int = stopPoints.size
        }
        val stopSessionDao = object : TrackingSessionDao {
            private val session = TrackingSessionEntity(
                id = stopSessionId,
                samplingIntervalSeconds = 10,
                startTimestamp = 0L,
                endTimestamp = 20_000L,
                status = SessionStatus.FINISHED,
                stoppedTimeMillis = 10_000L,
                movingTimeMillis = 10_000L,
                averageSpeedMetersPerSecond = 1.0,
            )

            override suspend fun insert(session: TrackingSessionEntity) = Unit
            override suspend fun update(session: TrackingSessionEntity) = Unit
            override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> = flowOf(session)
            override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = flowOf(listOf(session))
            override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
                flowOf(listOf(session))
            override suspend fun deleteById(sessionId: String) = Unit
        }

        val repository = settingsRepository()
        runBlocking { repository.setStopDurationMillis(1_000L) }

        val appliedMarkers = Collections.synchronizedList(mutableListOf<List<LatLng>>())

        composeTestRule.setContent {
            SessionDetailScreen(
                viewModel = SessionDetailViewModel(stopSessionId, stopSessionDao, stopGpsPointDao, repository),
                onMarkersApplied = { appliedMarkers.add(it) },
            )
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            appliedMarkers.isNotEmpty() && appliedMarkers.last().isNotEmpty()
        }
        assertEquals(listOf(LatLng(5.0, 5.0)), appliedMarkers.last())
    }
}
