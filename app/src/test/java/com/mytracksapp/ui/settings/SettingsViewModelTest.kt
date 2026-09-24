package com.mytracksapp.ui.settings

import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [Logger] fake recording every [log] call — same shape as `FirstPointGeocodingCoordinatorTest`'s. */
private class FakeLogger : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = CopyOnWriteArrayList<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/** [TrackingSessionDao] fake for [SettingsViewModelTest]: only [deleteAll] is ever exercised. */
private class FakeTrackingSessionDao(private val deleteAllFailure: Throwable? = null) : TrackingSessionDao {
    var deleteAllCalled = false

    override suspend fun insert(session: TrackingSessionEntity) = error("not used in this test")
    override suspend fun update(session: TrackingSessionEntity) = error("not used in this test")
    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> = error("not used in this test")
    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = error("not used in this test")
    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
        error("not used in this test")
    override suspend fun deleteById(sessionId: String) = error("not used in this test")
    override suspend fun updateLocationName(sessionId: String, locationName: String?) =
        error("not used in this test")

    override suspend fun deleteAll() {
        deleteAllFailure?.let { throw it }
        deleteAllCalled = true
    }
}

/**
 * T12 — [SettingsViewModel] failure-containment unit tests (RF-07): each of the 9
 * `viewModelScope.launch` sites is wrapped in its OWN try/catch, so a failure persisting one
 * preference can never block or conflate with another. Robolectric-backed (real Android `Context`)
 * because [SettingsRepository] needs it for Jetpack DataStore — the same pattern already
 * established by `HistoryViewModelTest`/`TrackingViewModelTest` for this codebase.
 *
 * This codebase uses no mocking framework (hand-written fakes only). [SettingsRepository] itself
 * is a plain final class (not an interface), so its own failures can't be injected via a fake —
 * instead, [corruptedSettingsRepository] points it at a preferences file that is deliberately not
 * valid protobuf, so DataStore's own (real) parse failure is what every read/write throws through.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SettingsViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dataStoreName: String

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        dataStoreName = "test_settings_vm_settings_${UUID.randomUUID()}"
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    /**
     * A [SettingsRepository] backed by a deliberately corrupted (non-protobuf) preferences file:
     * both a read (init's `collect`) and a write (any setter, which reads-then-merges under the
     * hood) throw a real [Exception] with no mocking framework needed.
     */
    private fun corruptedSettingsRepository(): SettingsRepository {
        val file = context.preferencesDataStoreFile(dataStoreName)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
        return SettingsRepository(context, dataStoreName)
    }

    private suspend fun awaitEntry(
        logger: FakeLogger,
        timeoutMillis: Long = 5_000,
        predicate: (FakeLogger.Entry) -> Boolean,
    ): FakeLogger.Entry = withTimeout(timeoutMillis) {
        var match = logger.entries.find(predicate)
        while (match == null) {
            delay(10)
            match = logger.entries.find(predicate)
        }
        match
    }

    @Test
    fun `init's settings-collect failure is logged and uiState stays at its last valid value`() = runBlocking {
        val logger = FakeLogger()
        val viewModel = SettingsViewModel(
            settingsRepository = corruptedSettingsRepository(),
            trackingSessionDao = FakeTrackingSessionDao(),
            logger = logger,
        )

        val entry = awaitEntry(logger) { it.message == "Failed to collect user settings" }
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("SettingsViewModel", entry.tag)
        assertTrue(entry.throwable != null)

        // No exception escaped construction/collection, and uiState remains at its initial default.
        assertEquals(SettingsUiState(), viewModel.uiState.value)
    }

    @Test
    fun `a setter failure is logged independently, distinct from init's own failure`() = runBlocking {
        val logger = FakeLogger()
        val viewModel = SettingsViewModel(
            settingsRepository = corruptedSettingsRepository(),
            trackingSessionDao = FakeTrackingSessionDao(),
            logger = logger,
        )
        // Let init's own (independently-caught) failure land first so the assertions below can't
        // accidentally be satisfied by it instead of the setter's own catch block.
        awaitEntry(logger) { it.message == "Failed to collect user settings" }

        viewModel.onSamplingIntervalSelected(SamplingInterval.FIVE_SECONDS)

        val entry = awaitEntry(logger) { it.message == "Failed to persist sampling interval" }
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("SettingsViewModel", entry.tag)
        assertTrue(entry.throwable != null)
        // uiState is only ever mutated from init's collect, which never got a valid emission here.
        assertEquals(SettingsUiState(), viewModel.uiState.value)
    }

    @Test
    fun `clearHistory failure is logged and does not throw, leaving uiState untouched`() = runBlocking {
        val logger = FakeLogger()
        val dao = FakeTrackingSessionDao(deleteAllFailure = IllegalStateException("db locked"))
        val viewModel = SettingsViewModel(
            settingsRepository = SettingsRepository(context, dataStoreName),
            trackingSessionDao = dao,
            logger = logger,
        )

        // Calling this must not throw to the caller even though deleteAll() will fail.
        viewModel.clearHistory()

        val entry = awaitEntry(logger) { it.message == "Failed to clear history" }
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("SettingsViewModel", entry.tag)
        assertTrue(entry.throwable is IllegalStateException)
        assertTrue(!dao.deleteAllCalled)
    }
}
