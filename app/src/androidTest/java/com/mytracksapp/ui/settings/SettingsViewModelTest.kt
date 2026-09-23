package com.mytracksapp.ui.settings

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.export.ExportFormat
import com.mytracksapp.domain.model.GpsPrecision
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory [TrackingSessionDao] double for [SettingsViewModelTest] — records [deleteAll] calls. */
private class FakeTrackingSessionDao(initial: List<TrackingSessionEntity> = emptyList()) : TrackingSessionDao {
    private val state = MutableStateFlow(initial)
    var deleteAllCallCount = 0
        private set

    override suspend fun insert(session: TrackingSessionEntity) {
        state.value = state.value + session
    }

    override suspend fun update(session: TrackingSessionEntity) = error("not used in this test")

    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> =
        state.map { sessions -> sessions.find { it.id == sessionId } }

    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = state

    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
        state.map { sessions -> sessions.filter { it.status == status } }

    override suspend fun deleteById(sessionId: String) = error("not used in this test")

    override suspend fun deleteAll() {
        deleteAllCallCount++
        state.value = emptyList()
    }

    override suspend fun updateLocationName(sessionId: String, locationName: String?) = error("not used in this test")
}

/**
 * T12 — [SettingsViewModel] instrumented test (RF-04, RF-06, RF-10, RF-12): the 3 new
 * enum/boolean-backed settings persist immediately (no "Save" button, exactly like the
 * pre-existing sampling-interval/speed/distance-unit selectors), and [SettingsViewModel.clearHistory]
 * calls [TrackingSessionDao.deleteAll] exactly once per invocation.
 *
 * Needs a real [SettingsRepository] (Jetpack DataStore, a real `Context`), so this is an
 * instrumented test rather than a plain JVM one — same pattern as `SettingsScreenTest`/
 * `SettingsRepositoryTest`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun uniqueDataStoreName() {
        dataStoreName = "test_settings_vm_${UUID.randomUUID()}"
    }

    @After
    fun deleteDataStoreFile() {
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun settingsRepository() = SettingsRepository(context, dataStoreName)

    @Test
    fun selectingGpsPrecisionPersistsImmediatelyViaSettingsRepository() = runBlocking {
        val repository = settingsRepository()
        val viewModel = SettingsViewModel(repository, FakeTrackingSessionDao())

        viewModel.onGpsPrecisionSelected(GpsPrecision.BALANCED)

        waitForCondition { repository.userSettings.first().gpsPrecision == GpsPrecision.BALANCED }
        assertEquals(GpsPrecision.BALANCED, repository.userSettings.first().gpsPrecision)
    }

    @Test
    fun togglingKeepScreenOnPersistsImmediatelyViaSettingsRepository() = runBlocking {
        val repository = settingsRepository()
        val viewModel = SettingsViewModel(repository, FakeTrackingSessionDao())

        viewModel.onKeepScreenOnToggled(false)

        waitForCondition { !repository.userSettings.first().keepScreenOnEnabled }
        assertEquals(false, repository.userSettings.first().keepScreenOnEnabled)

        viewModel.onKeepScreenOnToggled(true)
        waitForCondition { repository.userSettings.first().keepScreenOnEnabled }
        assertEquals(true, repository.userSettings.first().keepScreenOnEnabled)
    }

    @Test
    fun selectingDefaultExportFormatPersistsImmediatelyViaSettingsRepository() = runBlocking {
        val repository = settingsRepository()
        val viewModel = SettingsViewModel(repository, FakeTrackingSessionDao())

        viewModel.onDefaultExportFormatSelected(ExportFormat.CSV)

        waitForCondition { repository.userSettings.first().defaultExportFormat == ExportFormat.CSV }
        assertEquals(ExportFormat.CSV, repository.userSettings.first().defaultExportFormat)
    }

    @Test
    fun clearHistoryCallsDeleteAllExactlyOnce() = runBlocking {
        val dao = FakeTrackingSessionDao(
            listOf(
                TrackingSessionEntity(
                    id = "session-1",
                    samplingIntervalSeconds = 10,
                    startTimestamp = 0L,
                    status = SessionStatus.FINISHED,
                ),
            ),
        )
        val viewModel = SettingsViewModel(settingsRepository(), dao)

        viewModel.clearHistory()

        waitForCondition { dao.deleteAllCallCount == 1 }
        assertEquals(1, dao.deleteAllCallCount)
        assertEquals(emptyList<TrackingSessionEntity>(), dao.getAllSessions().first())
    }

    /** Polls [predicate] with a real (non-virtual) timeout, mirroring this codebase's other DataStore-backed tests. */
    private suspend fun waitForCondition(timeoutMillis: Long = 5_000, predicate: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            kotlinx.coroutines.delay(10)
        }
        error("condition never became true within ${timeoutMillis}ms")
    }
}
