package com.mytracksapp.ui.logs

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.printToString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.logging.FileLogger
import java.io.File
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T10 — instrumented Compose UI test for [LogViewerScreen] (UI-02, UI-03, UI-04). Same
 * direct-composable-with-a-real-[LogViewerViewModel] pattern as `LogViewerViewModelTest.kt` (T09)'s
 * temp-directory setup: a real temp dir stands in for `<filesDir>/logs`, with a log file
 * written (or not) before composition to control the resulting [LogViewerUiState].
 */
@RunWith(AndroidJUnit4::class)
class LogViewerScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var logsDir: File

    @Before
    fun setUp() {
        val tempFile = File.createTempFile("log-viewer-screen-test", "")
        tempFile.delete()
        tempFile.mkdirs()
        logsDir = tempFile
    }

    @After
    fun tearDown() {
        logsDir.deleteRecursively()
    }

    private fun awaitLoaded(viewModel: LogViewerViewModel) {
        composeTestRule.waitUntil(timeoutMillis = 5_000) { !viewModel.uiState.value.isLoading }
        composeTestRule.waitForIdle()
    }

    @Test
    fun nonBlankContentIsShownAndEmptyMessageIsAbsent() {
        val logContent = "2026-09-24 10:00:00 INFO Startup complete\n"
        File(logsDir, FileLogger.LOG_FILE_NAME).writeText(logContent)
        val viewModel = LogViewerViewModel(logsDir = logsDir)

        composeTestRule.setContent {
            LogViewerScreen(viewModel = viewModel)
        }
        awaitLoaded(viewModel)

        composeTestRule.onNodeWithTag(LogViewerScreenTestTags.LOG_CONTENT)
            .assertIsDisplayed()
            .assertTextEquals(logContent)
        composeTestRule.onAllNodesWithTag(LogViewerScreenTestTags.EMPTY_MESSAGE).assertCountEquals(0)
    }

    @Test
    fun blankContentShowsEmptyMessageAndHidesLogContentWithoutCrashing() {
        // No log file written at all — mirrors the fresh-install case (UI-03).
        val viewModel = LogViewerViewModel(logsDir = logsDir)

        composeTestRule.setContent {
            LogViewerScreen(viewModel = viewModel)
        }
        awaitLoaded(viewModel)

        composeTestRule.onNodeWithTag(LogViewerScreenTestTags.EMPTY_MESSAGE)
            .assertIsDisplayed()
            .assertTextEquals("Nenhum log registrado ainda")
        composeTestRule.onAllNodesWithTag(LogViewerScreenTestTags.LOG_CONTENT).assertCountEquals(0)
    }

    @Test
    fun noEditDeleteOrShareAffordanceIsEverRendered() {
        val logContent = "2026-09-24 10:00:00 INFO Startup complete\n"
        File(logsDir, FileLogger.LOG_FILE_NAME).writeText(logContent)
        val viewModel = LogViewerViewModel(logsDir = logsDir)

        composeTestRule.setContent {
            LogViewerScreen(viewModel = viewModel)
        }
        awaitLoaded(viewModel)

        // UI-04: no button/icon/menu item for edit/delete/share anywhere in the composed tree.
        val tree = composeTestRule.onNodeWithTag(LogViewerScreenTestTags.SCREEN).printToString(Int.MAX_VALUE).lowercase()
        val forbiddenKeywords = listOf(
            "share", "delete", "edit", "compartilhar", "excluir", "deletar", "editar", "menu",
        )
        forbiddenKeywords.forEach { keyword ->
            assertFalse(
                "expected no share/delete/edit affordance (found keyword \"$keyword\") in:\n$tree",
                tree.contains(keyword),
            )
        }
    }
}
