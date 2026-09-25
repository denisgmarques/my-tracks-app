package com.mytracksapp.ui.logs

import com.mytracksapp.logging.FileLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * T09 — [LogViewerViewModel] unit test: a real [com.mytracksapp.logging.LogFileReader] against a
 * real temp directory (same temp-dir setup pattern as `LogFileReaderTest`), covering the
 * content-present and no-files cases. Plain JVM test — neither [LogViewerViewModel] nor
 * [com.mytracksapp.logging.LogFileReader] touches `Context`, so no Robolectric is needed; only
 * `Dispatchers.setMain`/`resetMain` (same pattern as this repo's other ViewModel tests) is required
 * so `viewModelScope`'s `Dispatchers.Main.immediate` resolves in a plain JVM test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LogViewerViewModelTest {

    private lateinit var logsDir: File

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        val tempFile = File.createTempFile("log-viewer-vm-test", "")
        tempFile.delete()
        tempFile.mkdirs()
        logsDir = tempFile
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        logsDir.deleteRecursively()
    }

    private suspend fun awaitLoaded(viewModel: LogViewerViewModel, timeoutMillis: Long = 5_000): LogViewerUiState =
        withTimeout(timeoutMillis) {
            var state = viewModel.uiState.value
            while (state.isLoading) {
                delay(10)
                state = viewModel.uiState.value
            }
            state
        }

    @Test
    fun `files with content present are reflected in uiState once loading completes`() = runBlocking {
        val backupContent = "backup-line-1\n"
        val activeContent = "active-line-1\n"
        File(logsDir, FileLogger.BACKUP_FILE_NAME).writeText(backupContent)
        File(logsDir, FileLogger.LOG_FILE_NAME).writeText(activeContent)

        val viewModel = LogViewerViewModel(logsDir = logsDir)

        val state = awaitLoaded(viewModel)

        assertFalse(state.isLoading)
        assertEquals(backupContent + activeContent, state.content)
    }

    @Test
    fun `no files present results in empty content and isLoading becomes false without throwing`() = runBlocking {
        val viewModel = LogViewerViewModel(logsDir = logsDir)

        val state = awaitLoaded(viewModel)

        assertFalse(state.isLoading)
        assertEquals("", state.content)
    }
}
