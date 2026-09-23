package com.mytracksapp.ui.export

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
import com.mytracksapp.domain.export.ExportFormat
import com.mytracksapp.domain.export.ExportService
import com.mytracksapp.ui.history.SessionDetailScreen
import com.mytracksapp.ui.history.SessionDetailScreenTestTags
import com.mytracksapp.ui.history.SessionDetailViewModel
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory [TrackingSessionDao] double whose single session's [status] is configurable per test. */
private class FakeTrackingSessionDao(sessionId: String, status: SessionStatus) : TrackingSessionDao {
    private val session = TrackingSessionEntity(
        id = sessionId,
        samplingIntervalSeconds = 10,
        startTimestamp = 0L,
        endTimestamp = if (status == SessionStatus.FINISHED) 20_000L else null,
        status = status,
    )

    override suspend fun insert(session: TrackingSessionEntity) = Unit
    override suspend fun update(session: TrackingSessionEntity) = Unit
    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> = flowOf(session)
    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = flowOf(listOf(session))
    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
        flowOf(listOf(session).filter { it.status == status })
}

/** In-memory [GpsPointDao] double with a couple of fixed points for a session. */
private class FakeGpsPointDao(sessionId: String) : GpsPointDao {
    private val points = listOf(
        GpsPointEntity(sessionId = sessionId, timestamp = 0L, latitude = 0.0, longitude = 0.0, accuracy = 5f),
        GpsPointEntity(sessionId = sessionId, timestamp = 10_000L, latitude = 0.001, longitude = 0.0, accuracy = 5f),
        GpsPointEntity(sessionId = sessionId, timestamp = 20_000L, latitude = 0.002, longitude = 0.0, accuracy = 5f),
    )

    override suspend fun insert(point: GpsPointEntity): Long = 0L
    override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> = emptyList()
    override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> = flowOf(points)
    override suspend fun countForSession(sessionId: String): Int = points.size
}

/**
 * T12 — instrumented Compose UI test for the export action + [ExportFormatDialog] on
 * [SessionDetailScreen] (UI-05, RF-08):
 *  - the export action is visible ONLY for a session with status "encerrada" (FINISHED);
 *  - picking GPX or CSV in the resulting dialog invokes the real [ExportService] with that exact
 *    format argument (verified by asserting the format/content of the [ExportService]-produced
 *    file, proving the argument that actually reached it).
 */
@RunWith(AndroidJUnit4::class)
class ExportFormatDialogTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun uniqueDataStoreName() {
        dataStoreName = "test_export_dialog_settings_${UUID.randomUUID()}"
    }

    @After
    fun deleteDataStoreFile() {
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun settingsRepository() = SettingsRepository(context, dataStoreName)

    @Test
    fun exportActionIsHiddenForAnActiveNonFinishedSession() {
        val sessionId = "active-session"
        val viewModel = SessionDetailViewModel(
            sessionId,
            FakeTrackingSessionDao(sessionId, SessionStatus.ACTIVE),
            FakeGpsPointDao(sessionId),
            settingsRepository(),
        )

        composeTestRule.setContent {
            SessionDetailScreen(viewModel = viewModel)
        }

        composeTestRule
            .onAllNodesWithTag(SessionDetailScreenTestTags.EXPORT_ACTION)
            .assertCountEquals(0)
    }

    @Test
    fun exportActionIsVisibleForAFinishedSession() {
        val sessionId = "finished-session"
        val viewModel = SessionDetailViewModel(
            sessionId,
            FakeTrackingSessionDao(sessionId, SessionStatus.FINISHED),
            FakeGpsPointDao(sessionId),
            settingsRepository(),
        )

        composeTestRule.setContent {
            SessionDetailScreen(viewModel = viewModel)
        }

        composeTestRule
            .onNodeWithTag(SessionDetailScreenTestTags.EXPORT_ACTION)
            .assertIsDisplayed()
    }

    @Test
    fun triggeringExportShowsGpxAndCsvOptions() {
        val sessionId = "finished-session-picker"
        val viewModel = SessionDetailViewModel(
            sessionId,
            FakeTrackingSessionDao(sessionId, SessionStatus.FINISHED),
            FakeGpsPointDao(sessionId),
            settingsRepository(),
        )

        composeTestRule.setContent {
            SessionDetailScreen(viewModel = viewModel)
        }

        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.EXPORT_ACTION).performClick()

        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.DIALOG).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.GPX_OPTION).assertIsDisplayed()
        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.CSV_OPTION).assertIsDisplayed()
    }

    @Test
    fun selectingGpxInvokesExportServiceWithGpxFormat() {
        val sessionId = "finished-session-gpx"
        val trackingSessionDao = FakeTrackingSessionDao(sessionId, SessionStatus.FINISHED)
        val gpsPointDao = FakeGpsPointDao(sessionId)
        val exportService = ExportService(trackingSessionDao, gpsPointDao)
        val viewModel = SessionDetailViewModel(sessionId, trackingSessionDao, gpsPointDao, settingsRepository())

        var exportedFileName: String? = null
        var exportedFormat: ExportFormat? = null
        var exportedContent: String? = null

        composeTestRule.setContent {
            SessionDetailScreen(
                viewModel = viewModel,
                onExport = { id, format ->
                    val result = exportService.export(id, format)
                    exportedFileName = result.fileName
                    exportedFormat = result.format
                    exportedContent = result.content
                },
            )
        }

        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.EXPORT_ACTION).performClick()
        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.GPX_OPTION).performClick()
        composeTestRule.waitForIdle()

        assertEquals(ExportFormat.GPX, exportedFormat)
        assertEquals("session-$sessionId.gpx", exportedFileName)
        assertTrue("expected GPX content, got: $exportedContent", exportedContent.orEmpty().contains("<gpx"))
        // The dialog dismisses itself once a format is picked.
        composeTestRule.onAllNodesWithTag(ExportFormatDialogTestTags.DIALOG).assertCountEquals(0)
    }

    @Test
    fun selectingCsvInvokesExportServiceWithCsvFormat() {
        val sessionId = "finished-session-csv"
        val trackingSessionDao = FakeTrackingSessionDao(sessionId, SessionStatus.FINISHED)
        val gpsPointDao = FakeGpsPointDao(sessionId)
        val exportService = ExportService(trackingSessionDao, gpsPointDao)
        val viewModel = SessionDetailViewModel(sessionId, trackingSessionDao, gpsPointDao, settingsRepository())

        var exportedFileName: String? = null
        var exportedFormat: ExportFormat? = null
        var exportedContent: String? = null

        composeTestRule.setContent {
            SessionDetailScreen(
                viewModel = viewModel,
                onExport = { id, format ->
                    val result = exportService.export(id, format)
                    exportedFileName = result.fileName
                    exportedFormat = result.format
                    exportedContent = result.content
                },
            )
        }

        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.EXPORT_ACTION).performClick()
        composeTestRule.onNodeWithTag(ExportFormatDialogTestTags.CSV_OPTION).performClick()
        composeTestRule.waitForIdle()

        assertEquals(ExportFormat.CSV, exportedFormat)
        assertEquals("session-$sessionId.csv", exportedFileName)
        assertTrue(
            "expected CSV content starting with the header row, got: $exportedContent",
            exportedContent.orEmpty().startsWith("session_id,timestamp,latitude,longitude,accuracy,speed_instant,segment_status"),
        )
        composeTestRule.onAllNodesWithTag(ExportFormatDialogTestTags.DIALOG).assertCountEquals(0)
    }
}
