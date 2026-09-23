package com.mytracksapp.ui.tracking

import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.gms.maps.model.LatLng
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.settings.SettingsRepository
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * In-memory [GpsPointDao] double: each session id gets its own [MutableStateFlow] the test can
 * push new emissions into, simulating Room's `Flow` re-running after a new point is persisted —
 * same fake-DAO pattern `TrackingViewModelTest` (T08, unit) uses, here driving a real
 * instrumented Compose UI test end to end.
 */
private class FakeGpsPointDao : GpsPointDao {
    private val pointsBySession = mutableMapOf<String, MutableStateFlow<List<GpsPointEntity>>>()

    private fun flowFor(sessionId: String): MutableStateFlow<List<GpsPointEntity>> =
        pointsBySession.getOrPut(sessionId) { MutableStateFlow(emptyList()) }

    override suspend fun insert(point: GpsPointEntity): Long {
        val flow = flowFor(point.sessionId)
        flow.value = flow.value + point
        return flow.value.size.toLong()
    }

    override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> = points.map { insert(it) }

    override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> = flowFor(sessionId)

    override suspend fun countForSession(sessionId: String): Int = flowFor(sessionId).value.size
}

/**
 * T09 — instrumented Compose UI test for [TrackingScreen] (UI-02, UI-03):
 *  - the 5 UI-03 metrics are visible simultaneously, with no extra navigation required;
 *  - the polyline [MapComponent] computes from [TrackingViewModel] state gains each new point as
 *    its last vertex.
 *
 * [MapComponent] genuinely instantiates the real `com.google.android.gms.maps.MapView`/
 * `GoogleMap` (see MapComponent.kt) — this test does not stub that away. What it deliberately
 * does NOT assert on is rendered map tiles/pixels, since that needs network access and a real
 * Google Cloud API key this prototype does not ship (docs/setup/google-maps-api-key.md); without
 * one, the underlying Play services renderer may log an authorization error and never finish
 * "readying" the map within a test's lifetime. Instead this test uses `MapComponent`'s
 * `onPolylineApplied` seam, which reports the point list it computed for the current ViewModel
 * state independently of whether the real map has finished initializing — exactly the
 * state-driven behavior UI-02 requires, without depending on Play Services' own timing.
 */
@RunWith(AndroidJUnit4::class)
class TrackingScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun uniqueDataStoreName() {
        dataStoreName = "test_tracking_screen_settings_${UUID.randomUUID()}"
    }

    @After
    fun deleteDataStoreFile() {
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun settingsRepository() = SettingsRepository(context, dataStoreName)

    @Test
    fun allSixUi03MetricsAreVisibleSimultaneouslyWithNoExtraNavigation() {
        val sessionId = "tracking-screen-test-metrics"
        val viewModel = TrackingViewModel(sessionId, FakeGpsPointDao(), settingsRepository())

        composeTestRule.setContent {
            TrackingScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag(TrackingScreenTestTags.INSTANT_SPEED).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TrackingScreenTestTags.AVERAGE_SPEED).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TrackingScreenTestTags.TOTAL_DISTANCE).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TrackingScreenTestTags.ELAPSED_TIME).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TrackingScreenTestTags.STOPPED_TIME).assertIsDisplayed()
        composeTestRule.onNodeWithTag(TrackingScreenTestTags.MOVING_TIME).assertIsDisplayed()
    }

    @Test
    fun mapComponentIsGenuinelyPresentInTheScreen() {
        val sessionId = "tracking-screen-test-map"
        val viewModel = TrackingViewModel(sessionId, FakeGpsPointDao(), settingsRepository())

        composeTestRule.setContent {
            TrackingScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag(MapComponentTestTags.MAP_VIEW).assertIsDisplayed()
    }

    @Test
    fun eachNewPointBecomesThePolylinesLastVertex() {
        val sessionId = "tracking-screen-test-polyline"
        val dao = FakeGpsPointDao()
        val viewModel = TrackingViewModel(sessionId, dao, settingsRepository())
        val appliedPolylines = Collections.synchronizedList(mutableListOf<List<LatLng>>())

        composeTestRule.setContent {
            TrackingScreen(
                viewModel = viewModel,
                onPolylineApplied = { appliedPolylines.add(it) },
            )
        }

        // First point: a single vertex can't form a line yet, but the ViewModel state and
        // MapComponent's computed point list both already reflect it.
        runBlocking {
            dao.insert(
                GpsPointEntity(sessionId = sessionId, timestamp = 0L, latitude = 10.0, longitude = 20.0, accuracy = 5f),
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.polyline.size == 1 }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            appliedPolylines.isNotEmpty() && appliedPolylines.last().size == 1
        }
        assertEquals(LatLng(10.0, 20.0), appliedPolylines.last().last())

        // Second point: becomes the new last vertex.
        runBlocking {
            dao.insert(
                GpsPointEntity(
                    sessionId = sessionId, timestamp = 10_000L,
                    latitude = 10.001, longitude = 20.0, accuracy = 5f,
                ),
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.polyline.size == 2 }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            appliedPolylines.isNotEmpty() && appliedPolylines.last().size == 2
        }

        var state = viewModel.uiState.value
        assertEquals(2, state.polyline.size)
        assertEquals(10.001, state.polyline.last().latitude, 0.0)
        assertEquals(20.0, state.polyline.last().longitude, 0.0)
        assertEquals(LatLng(10.001, 20.0), appliedPolylines.last().last())

        // Third point: last vertex advances again.
        runBlocking {
            dao.insert(
                GpsPointEntity(
                    sessionId = sessionId, timestamp = 20_000L,
                    latitude = 10.002, longitude = 20.0, accuracy = 5f,
                ),
            )
        }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { viewModel.uiState.value.polyline.size == 3 }
        composeTestRule.waitForIdle()
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            appliedPolylines.isNotEmpty() && appliedPolylines.last().size == 3
        }

        state = viewModel.uiState.value
        assertEquals(3, state.polyline.size)
        assertEquals(10.002, state.polyline.last().latitude, 0.0)
        assertEquals(LatLng(10.002, 20.0), appliedPolylines.last().last())
    }

    /**
     * T09 (RF-07, RNF-04) — the composed [View]'s `keepScreenOn` flag tracks
     * `TrackingUiState.keepScreenOnEnabled` (itself sourced from [SettingsRepository]) while
     * [TrackingScreen] is in composition, and is always reset to `false` once the screen leaves
     * composition — regardless of the preference's last value.
     */
    @Test
    fun keepScreenOnTracksThePreferenceWhileComposedAndIsResetOnDispose() {
        val sessionId = "tracking-screen-test-keep-screen-on"
        val repository = settingsRepository()
        runBlocking { repository.setKeepScreenOnEnabled(true) }
        val viewModel = TrackingViewModel(sessionId, FakeGpsPointDao(), repository)
        val appliedViews = Collections.synchronizedList(mutableListOf<View>())
        var showScreen by mutableStateOf(true)

        composeTestRule.setContent {
            if (showScreen) {
                TrackingScreen(
                    viewModel = viewModel,
                    onKeepScreenOnApplied = { appliedViews.add(it) },
                )
            }
        }

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            appliedViews.isNotEmpty() && appliedViews.last().keepScreenOn
        }
        assertTrue(appliedViews.last().keepScreenOn)

        // Disabling the preference while the screen stays composed turns it back off.
        runBlocking { repository.setKeepScreenOnEnabled(false) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { !appliedViews.last().keepScreenOn }
        assertFalse(appliedViews.last().keepScreenOn)

        // Re-enabling it while still composed turns it back on.
        runBlocking { repository.setKeepScreenOnEnabled(true) }
        composeTestRule.waitUntil(timeoutMillis = 5_000) { appliedViews.last().keepScreenOn }
        assertTrue(appliedViews.last().keepScreenOn)

        // Leaving the screen (composition disposed) always resets the flag to false, even though
        // the preference itself is still enabled.
        showScreen = false
        composeTestRule.waitForIdle()
        assertFalse(appliedViews.last().keepScreenOn)
    }
}
