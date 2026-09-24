package com.mytracksapp.ui.newsession

import android.Manifest
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.session.SessionController
import com.mytracksapp.domain.session.SessionStartOutcome
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import com.mytracksapp.permission.LocationPermissionManager
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** In-memory [Logger] double — records every logged entry for assertions (T09). */
private class FakeLogger : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/** In-memory [SessionController] double for [NewSessionViewModelTest] (T09). */
private class FakeSessionController(
    private val startSessionOutcome: SessionStartOutcome = SessionStartOutcome.Started("session-1"),
    private val startSessionThrows: Throwable? = null,
) : SessionController {

    var startSessionCallCount = 0
        private set

    override suspend fun startSession(interval: SamplingInterval): SessionStartOutcome {
        startSessionCallCount++
        startSessionThrows?.let { throw it }
        return startSessionOutcome
    }

    override suspend fun stopSession(sessionId: String) = error("not used in this test")
}

/**
 * T09 — [NewSessionViewModel] failure-containment unit test (RF-07): both the `init` block's
 * settings-collect and `onConfirm()`'s session-start `viewModelScope.launch` bodies are wrapped in
 * try/catch, so a thrown exception from either dependency is logged (not left to crash the app)
 * and never escapes the coroutine, leaving [NewSessionViewModel.uiState] at its last valid value.
 *
 * Robolectric-backed like `HistoryViewModelTest`/`TrackingViewModelTest`: [NewSessionViewModel]
 * depends on a real [LocationPermissionManager] and [SettingsRepository], both of which need a
 * real Android `Context` under the hood.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NewSessionViewModelTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private lateinit var dataStoreName: String

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        dataStoreName = "test_new_session_vm_settings_${UUID.randomUUID()}"
        shadowOf(context).grantPermissions(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.preferencesDataStoreFile(dataStoreName).delete()
    }

    private fun settingsRepository() = SettingsRepository(context, dataStoreName)

    private suspend fun awaitLogEntry(logger: FakeLogger, timeoutMillis: Long = 5_000) {
        withTimeout(timeoutMillis) {
            while (logger.entries.isEmpty()) {
                delay(10)
            }
        }
    }

    @Test
    fun `onConfirm logs and swallows a SessionController failure, leaving uiState at its last valid value`() =
        runBlocking {
            val logger = FakeLogger()
            val thrown = IllegalStateException("startSession boom")
            val viewModel = NewSessionViewModel(
                permissionManager = LocationPermissionManager(context),
                sessionController = FakeSessionController(startSessionThrows = thrown),
                settingsRepository = settingsRepository(),
                logger = logger,
            )

            viewModel.onConfirm()

            awaitLogEntry(logger)

            val errorEntries = logger.entries.filter { it.level == LogLevel.ERROR }
            assertEquals(1, errorEntries.size)
            assertEquals("NewSessionViewModel", errorEntries.single().tag)
            assertEquals(thrown, errorEntries.single().throwable)

            // No _uiState.update happens after the caught failure: isStarting stays at the
            // optimistic `true` set just before launch — it is never flipped back to false, and no
            // startedSessionId/permissionDeniedMessage is ever set from this failed attempt.
            val state = viewModel.uiState.value
            assertTrue(state.isStarting)
            assertNull(state.startedSessionId)
            assertNull(state.permissionDeniedMessage)
        }

    @Test
    fun `init logs and swallows a settings-collection failure, leaving uiState at its last valid value`() =
        runBlocking {
            val logger = FakeLogger()

            // Force the DataStore's backing "file" to actually be a directory BEFORE the
            // repository ever reads it, so opening it for read throws an IOException and the
            // first collection of `userSettings` fails (simulating a real-world DataStore I/O
            // failure) instead of ever emitting a value.
            val backingFile = context.preferencesDataStoreFile(dataStoreName)
            backingFile.parentFile?.mkdirs()
            backingFile.mkdir()

            val viewModel = NewSessionViewModel(
                permissionManager = LocationPermissionManager(context),
                sessionController = FakeSessionController(),
                settingsRepository = settingsRepository(),
                logger = logger,
            )

            awaitLogEntry(logger)

            val errorEntries = logger.entries.filter { it.level == LogLevel.ERROR }
            assertEquals(1, errorEntries.size)
            assertEquals("NewSessionViewModel", errorEntries.single().tag)

            // uiState stays at its last valid value: the initial default, since the collect never
            // successfully emitted before failing.
            assertEquals(NewSessionUiState(), viewModel.uiState.value)
        }
}
