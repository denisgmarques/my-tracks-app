package com.mytracksapp.ui.history

import androidx.compose.ui.test.assertCountEquals
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
import com.mytracksapp.ui.export.ExportFormatDialogTestTags
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory [TrackingSessionDao] double for a single finished session. */
private class FakeTrackingSessionDao(sessionId: String) : TrackingSessionDao {
    private val session = TrackingSessionEntity(
        id = sessionId,
        samplingIntervalSeconds = 10,
        startTimestamp = 0L,
        endTimestamp = 20_000L,
        status = SessionStatus.FINISHED,
    )

    override suspend fun insert(session: TrackingSessionEntity) = Unit
    override suspend fun update(session: TrackingSessionEntity) = Unit
    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> = flowOf(session)
    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = flowOf(listOf(session))
    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
        flowOf(listOf(session).filter { it.status == status })
    override suspend fun deleteById(sessionId: String) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun updateLocationName(sessionId: String, locationName: String?) = Unit
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
 * T10 — instrumented Compose UI test for [SessionDetailScreen]'s direct-export flow (RF-11):
 * tapping "Exportar" invokes [ExportService] with the CURRENTLY CONFIGURED default export format
 * (from [SettingsRepository]) and no intermediate format-choice dialog ever appears — replacing
 * the old GPX/CSV-picker flow that used to live in `ExportFormatDialogTest`.
 */
@RunWith(AndroidJUnit4::class)
class SessionDetailExportTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun uniqueDataStoreName() {
        dataStoreName = "test_session_detail_export_settings_${UUID.randomUUID()}"
    }

    @After
    fun deleteDataStoreFile() {
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun settingsRepository() = SettingsRepository(context, dataStoreName)

    @Test
    fun tappingExportWithCsvAsDefaultFormatExportsCsvDirectlyWithNoDialog() {
        val sessionId = "finished-session-direct-csv"
        val trackingSessionDao = FakeTrackingSessionDao(sessionId)
        val gpsPointDao = FakeGpsPointDao(sessionId)
        val exportService = ExportService(trackingSessionDao, gpsPointDao)
        val repository = settingsRepository()
        runBlocking { repository.setDefaultExportFormat(ExportFormat.CSV) }
        val viewModel = SessionDetailViewModel(sessionId, trackingSessionDao, gpsPointDao, repository)

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

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(SessionDetailScreenTestTags.EXPORT_ACTION).fetchSemanticsNodes().isNotEmpty()
        }
        // The dialog must never appear at any point before the tap.
        composeTestRule.onAllNodesWithTag(ExportFormatDialogTestTags.DIALOG).assertCountEquals(0)

        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.EXPORT_ACTION).performClick()
        composeTestRule.waitForIdle()

        assertEquals(ExportFormat.CSV, exportedFormat)
        assertEquals("session-$sessionId.csv", exportedFileName)
        assertTrue(
            "expected CSV content starting with the header row, got: $exportedContent",
            exportedContent.orEmpty().startsWith("session_id,timestamp,latitude,longitude,accuracy,speed_instant,segment_status"),
        )
        // No dialog ever appeared, before or after the tap.
        composeTestRule.onAllNodesWithTag(ExportFormatDialogTestTags.DIALOG).assertCountEquals(0)
    }

    @Test
    fun tappingExportWithGpxAsDefaultFormatExportsGpxDirectlyWithNoDialog() {
        val sessionId = "finished-session-direct-gpx"
        val trackingSessionDao = FakeTrackingSessionDao(sessionId)
        val gpsPointDao = FakeGpsPointDao(sessionId)
        val exportService = ExportService(trackingSessionDao, gpsPointDao)
        // GPX is UserSettings()'s default, but set it explicitly to make the test's intent clear.
        val repository = settingsRepository()
        runBlocking { repository.setDefaultExportFormat(ExportFormat.GPX) }
        val viewModel = SessionDetailViewModel(sessionId, trackingSessionDao, gpsPointDao, repository)

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

        composeTestRule.waitUntil(timeoutMillis = 5_000) {
            composeTestRule.onAllNodesWithTag(SessionDetailScreenTestTags.EXPORT_ACTION).fetchSemanticsNodes().isNotEmpty()
        }

        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.EXPORT_ACTION).performClick()
        composeTestRule.waitForIdle()

        assertEquals(ExportFormat.GPX, exportedFormat)
        assertEquals("session-$sessionId.gpx", exportedFileName)
        assertTrue("expected GPX content, got: $exportedContent", exportedContent.orEmpty().contains("<gpx"))
        composeTestRule.onAllNodesWithTag(ExportFormatDialogTestTags.DIALOG).assertCountEquals(0)
    }
}
