package com.mytracksapp.ui.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Stable test tags for [LogViewerScreen] (T10, `docs/agents/coding_guidelines.md` #4). */
object LogViewerScreenTestTags {
    const val SCREEN = "log_viewer_screen"
    const val LOG_CONTENT = "log_viewer_content"
    const val EMPTY_MESSAGE = "log_viewer_empty_message"
}

/**
 * T10 (UI-02, UI-03, UI-04) — the read-only "Ver logs" screen: renders [LogViewerViewModel]'s
 * combined `app.log`/`app.log.1` content in a small, scrollable monospace block, or an empty-state
 * message once loading has finished and there is nothing to show (fresh install / no log file yet,
 * UI-03). This screen is intentionally read-only — no edit/delete/share button, icon or menu item
 * is ever rendered here (UI-04).
 */
@Composable
fun LogViewerScreen(
    viewModel: LogViewerViewModel,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp)
            .testTag(LogViewerScreenTestTags.SCREEN),
    ) {
        if (!uiState.isLoading && uiState.content.isBlank()) {
            Text(
                text = "Nenhum log registrado ainda",
                modifier = Modifier.testTag(LogViewerScreenTestTags.EMPTY_MESSAGE),
            )
        } else {
            Text(
                text = uiState.content,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .testTag(LogViewerScreenTestTags.LOG_CONTENT),
            )
        }
    }
}
