package com.mytracksapp.ui.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytracksapp.logging.LogFileReader
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the "Ver logs" screen (T09). */
data class LogViewerUiState(
    val isLoading: Boolean = true,
    val content: String = "",
)

/**
 * T09 (RF-07) — backs the read-only "Ver logs" screen: reads [logsDir]'s log files via
 * [logFileReader] once on init, publishing the result into [uiState].
 *
 * The read is launched on [viewModelScope] (which defaults to `Dispatchers.Main.immediate`), but
 * the actual file I/O happens on `Dispatchers.IO` inside [LogFileReader.read] itself, so the UI
 * thread is never blocked (RF-07). The call is wrapped in [runCatching] so an unreadable/corrupt
 * log file never crashes the screen — the empty-state string is published instead.
 */
class LogViewerViewModel(
    private val logsDir: File,
    private val logFileReader: LogFileReader = LogFileReader(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(LogViewerUiState())
    val uiState: StateFlow<LogViewerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val content = runCatching { logFileReader.read(logsDir) }.getOrDefault("")
            _uiState.value = LogViewerUiState(isLoading = false, content = content)
        }
    }
}
