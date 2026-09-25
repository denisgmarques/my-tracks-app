package com.mytracksapp.ui.navigation

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import com.mytracksapp.ui.history.HistoryListScreenTestTags
import com.mytracksapp.ui.newsession.NewSessionScreenTestTags
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T03 — [MyTracksApp]'s [OrphanedSessionRecovery] wiring (RF-01 trigger point, RNF-01, UI-01),
 * exercised the same direct-composable way [com.mytracksapp.ui.history.HistoryListScreenTest]
 * does: `composeTestRule.setContent { MyTracksApp(...) }` with fake dependencies.
 *
 * [OrphanedSessionRecovery] is kept a concrete class (not turned into an interface/fake-double)
 * per this codebase's established "fake the DAO, not the domain class" convention (see
 * `OrphanedSessionRecoveryTest`/`SessionControllerTest`): every case below constructs a REAL
 * [OrphanedSessionRecovery] backed by a small in-memory [TrackingSessionDao]/[GpsPointDao] double
 * scoped to this test file, so the exact same production `recover()` logic runs in all three
 * cases — only the seeded rows differ:
 *  - "never completes" is simulated by a DAO whose `getSessionsByStatus` flow never emits
 *    (`awaitCancellation()`), which is the DAO-level equivalent of the PHASES.md-suggested
 *    `delay(Long.MAX_VALUE)` fake: either way, `recover()` never returns.
 *  - "returns 2" seeds two ACTIVE rows with no points, which `recover()` finalizes and counts.
 *  - "returns 0" seeds no ACTIVE rows.
 *
 * The [OrphanedSessionRecovery] instance under test is deliberately backed by DAOs completely
 * separate from the ones passed as [MyTracksApp]'s own `trackingSessionDao`/`gpsPointDao`
 * parameters (used by the History/Settings/Session-detail routes) — those two parameters are
 * independent per T03's signature, so this test can freely control recovery's outcome without
 * needing the recovered rows to actually surface in the History list (that end-to-end path is
 * T04's job).
 */
@RunWith(AndroidJUnit4::class)
class OrphanedSessionRecoverySnackbarTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun uniqueDataStoreName() {
        dataStoreName = "test_orphaned_recovery_snackbar_settings_${UUID.randomUUID()}"
    }

    @After
    fun deleteDataStoreFile() {
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    /**
     * In-memory [TrackingSessionDao] double for the [OrphanedSessionRecovery] under test.
     * `hangForever = true` makes `getSessionsByStatus` return a flow that never emits, so
     * `recover()`'s `.first()` call suspends indefinitely — the DAO-level double of a `recover()`
     * fake that does `delay(Long.MAX_VALUE)`.
     */
    private class RecoveryDao(
        seeded: List<TrackingSessionEntity>,
        private val hangForever: Boolean = false,
    ) : TrackingSessionDao {
        private val sessionsById = mutableMapOf<String, MutableStateFlow<TrackingSessionEntity?>>().apply {
            seeded.forEach { put(it.id, MutableStateFlow(it)) }
        }

        override suspend fun insert(session: TrackingSessionEntity) {
            sessionsById.getOrPut(session.id) { MutableStateFlow(null) }.value = session
        }

        override suspend fun update(session: TrackingSessionEntity) {
            sessionsById.getOrPut(session.id) { MutableStateFlow(null) }.value = session
        }

        override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> =
            sessionsById.getOrPut(sessionId) { MutableStateFlow(null) }

        override fun getAllSessions(): Flow<List<TrackingSessionEntity>> =
            flowOf(sessionsById.values.mapNotNull { it.value })

        override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> {
            if (hangForever) return flow { awaitCancellation() }
            return flowOf(sessionsById.values.mapNotNull { it.value }.filter { it.status == status })
        }

        override suspend fun deleteById(sessionId: String) = error("not used in this test")
        override suspend fun deleteAll() = error("not used in this test")
        override suspend fun getFinishedSessionsWithoutLocationNameSince(
            status: SessionStatus,
            sinceTimestamp: Long,
        ): List<TrackingSessionEntity> = error("not used in this test")
        override suspend fun updateLocationName(sessionId: String, locationName: String?) = error("not used in this test")
    }

    /** In-memory [GpsPointDao] double — every seeded session in this test file has zero points. */
    private class RecoveryPointsDao : GpsPointDao {
        override suspend fun insert(point: GpsPointEntity): Long = error("not used in this test")
        override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> = error("not used in this test")
        override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> = flowOf(emptyList())
        override suspend fun countForSession(sessionId: String): Int = 0
    }

    /** No-op [TrackingSessionDao] for [MyTracksApp]'s own params (History/Settings/Detail routes). */
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

    /** No-op [GpsPointDao] for [MyTracksApp]'s own `gpsPointDao` param (only used by Tracking route). */
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

    private fun activeSession(id: String, startTimestamp: Long) = TrackingSessionEntity(
        id = id,
        samplingIntervalSeconds = 10,
        startTimestamp = startTimestamp,
        status = SessionStatus.ACTIVE,
    )

    /**
     * Composes [MyTracksApp] with [orphanedSessionRecovery] under test and otherwise-inert real
     * dependencies for its remaining parameters.
     */
    private fun setContentWithRecovery(orphanedSessionRecovery: OrphanedSessionRecovery) {
        composeTestRule.setContent {
            MyTracksApp(
                trackingSessionDao = NoopTrackingSessionDao(),
                gpsPointDao = NoopGpsPointDao(),
                sessionController = NoopSessionController(),
                permissionManager = LocationPermissionManager(context),
                exportService = ExportService(NoopTrackingSessionDao(), NoopGpsPointDao()),
                settingsRepository = SettingsRepository(context, dataStoreName),
                orphanedSessionRecovery = orphanedSessionRecovery,
                geocodingRetryOnStartup = GeocodingRetryOnStartup(
                    trackingSessionDao = NoopTrackingSessionDao(),
                    gpsPointDao = NoopGpsPointDao(),
                    geocodeAndPersist = GeocodeAndPersist(NoopReverseGeocoder(), NoopTrackingSessionDao()),
                ),
            )
        }
    }

    @Test
    fun recoveryThatNeverCompletes_stillShowsHistoryScreenImmediately() {
        val neverCompletingRecovery = OrphanedSessionRecovery(
            trackingSessionDao = RecoveryDao(emptyList(), hangForever = true),
            gpsPointDao = RecoveryPointsDao(),
        )

        setContentWithRecovery(neverCompletingRecovery)

        // RNF-01: the start-destination's screen renders regardless of recover() ever finishing.
        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun recoveryOfTwoSessions_showsSnackbar_andBottomBarNavigationStillWorks() {
        val recoveryDao = RecoveryDao(
            listOf(
                activeSession("orphan-a", startTimestamp = 1_000L),
                activeSession("orphan-b", startTimestamp = 2_000L),
            ),
        )
        val recovery = OrphanedSessionRecovery(
            trackingSessionDao = recoveryDao,
            gpsPointDao = RecoveryPointsDao(),
        )

        setContentWithRecovery(recovery)

        val expectedMessage =
            "2 sessões anteriores foram encerradas automaticamente porque o app foi interrompido antes da finalização."
        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithText(expectedMessage).fetchSemanticsNodes().isNotEmpty()
        }

        // Navigation keeps working while the Snackbar is visible (UI-01's non-modal guarantee).
        composeTestRule.onNodeWithTag(AppNavigationTestTags.NEW_SESSION_TAB).performClick()
        composeTestRule.onNodeWithTag(NewSessionScreenTestTags.SCREEN).assertIsDisplayed()
    }

    @Test
    fun recoveryOfZeroSessions_neverShowsASnackbar() {
        val recovery = OrphanedSessionRecovery(
            trackingSessionDao = RecoveryDao(emptyList()),
            gpsPointDao = RecoveryPointsDao(),
        )

        setContentWithRecovery(recovery)

        // Give recover()'s (synchronous, in-memory) LaunchedEffect chain a chance to run, then
        // assert the History screen is up and no recovery Snackbar text ever appeared.
        composeTestRule.onNodeWithTag(HistoryListScreenTestTags.SCREEN).assertIsDisplayed()
        val recoveryMessageFragment = "encerradas automaticamente"
        assertEquals(
            0,
            composeTestRule.onAllNodesWithText(recoveryMessageFragment, substring = true)
                .fetchSemanticsNodes().size,
        )
    }
}
