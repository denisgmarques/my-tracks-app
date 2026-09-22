package com.mytracksapp.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.domain.export.ExportFormat
import com.mytracksapp.domain.stats.SegmentClassifier
import com.mytracksapp.domain.stats.StatsEngine
import com.mytracksapp.ui.export.ExportFormatDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for [SessionDetailScreen] — the 5 UI-03 metrics for a (typically finished) session:
 * instant speed, average speed, total elapsed time, stopped time and moving time.
 */
data class SessionDetailUiState(
    val sessionId: String = "",
    val samplingIntervalSeconds: Int = 0,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val instantSpeedMetersPerSecond: Double = 0.0,
    val averageSpeedMetersPerSecond: Double = 0.0,
    val elapsedTimeMillis: Long = 0L,
    val stoppedTimeMillis: Long = 0L,
    val movingTimeMillis: Long = 0L,
    val isLoaded: Boolean = false,
) {
    /** UI-05/RF-08: the export action is only ever available for a session with status "encerrada". */
    val isExportAvailable: Boolean get() = status == SessionStatus.FINISHED
}

/**
 * Backs [SessionDetailScreen] (T10): recomputes the 5 UI-03 metrics for [sessionId] directly
 * from its persisted points, via [StatsEngine]/[SegmentClassifier] — the same engines the (not
 * yet implemented, Phase 6) live `TrackingViewModel` uses for an active session — so the detail
 * screen's numbers are always derived the same way regardless of whether the session is still
 * active or already finished.
 *
 * - `instantSpeedMetersPerSecond` = the instant speed of the session's LAST recorded interval
 *   (i.e. [StatsEngine.instantSpeeds] `.lastOrNull()`), since a finished/static session has no
 *   "current" GPS fix — the most recent one it ever had is the closest analogue. `0.0` for a
 *   single-point session (no interval exists yet).
 */
class SessionDetailViewModel(
    private val sessionId: String,
    trackingSessionDao: TrackingSessionDao,
    gpsPointDao: GpsPointDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionDetailUiState(sessionId = sessionId))
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                trackingSessionDao.getSessionById(sessionId),
                gpsPointDao.getPointsForSession(sessionId),
            ) { session, points -> session to points }
                .collect { (session, points) ->
                    if (session == null) return@collect
                    val classification = SegmentClassifier.classify(points)
                    _uiState.update {
                        it.copy(
                            samplingIntervalSeconds = session.samplingIntervalSeconds,
                            status = session.status,
                            instantSpeedMetersPerSecond = StatsEngine.instantSpeeds(points).lastOrNull() ?: 0.0,
                            averageSpeedMetersPerSecond = StatsEngine.averageSpeedMetersPerSecond(points),
                            elapsedTimeMillis = StatsEngine.elapsedTimeMillis(points),
                            stoppedTimeMillis = classification.stoppedTimeMillis,
                            movingTimeMillis = classification.movingTimeMillis,
                            isLoaded = true,
                        )
                    }
                }
        }
    }
}

/** Stable test tags for [SessionDetailScreen], used by SessionDetailScreenTest (UI-03) and ExportFormatDialogTest (UI-05). */
object SessionDetailScreenTestTags {
    const val SCREEN = "session_detail_screen"
    const val INSTANT_SPEED = "session_detail_instant_speed"
    const val AVERAGE_SPEED = "session_detail_average_speed"
    const val ELAPSED_TIME = "session_detail_elapsed_time"
    const val STOPPED_TIME = "session_detail_stopped_time"
    const val MOVING_TIME = "session_detail_moving_time"
    const val EXPORT_ACTION = "session_detail_export_action"
}

/**
 * Finished-session detail screen (T10, UI-03): displays all 5 required metrics — instant speed,
 * average speed, total elapsed time, stopped time, moving time — simultaneously, with no further
 * navigation needed to see any of them.
 *
 * T12/UI-05: also exposes an export action, visible ONLY when the session's status is
 * [SessionStatus.FINISHED] ("encerrada"). Triggering it shows [ExportFormatDialog]'s GPX/CSV
 * picker; picking either format invokes [onExport] with `(sessionId, format)` — production callers
 * wire this to `ExportService::export` (RF-08), e.g. `{ id, format -> exportService.export(id, format) }`.
 * Defaults to a no-op so existing callers/tests that don't care about export keep compiling.
 */
@Composable
fun SessionDetailScreen(
    viewModel: SessionDetailViewModel,
    modifier: Modifier = Modifier,
    onExport: suspend (String, ExportFormat) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsState()
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .testTag(SessionDetailScreenTestTags.SCREEN),
    ) {
        Text(text = "Sessão ${uiState.sessionId}", style = MaterialTheme.typography.titleLarge)
        Text(
            text = "Velocidade instantânea: ${"%.2f".format(uiState.instantSpeedMetersPerSecond)} m/s",
            modifier = Modifier.testTag(SessionDetailScreenTestTags.INSTANT_SPEED),
        )
        Text(
            text = "Velocidade média: ${"%.2f".format(uiState.averageSpeedMetersPerSecond)} m/s",
            modifier = Modifier.testTag(SessionDetailScreenTestTags.AVERAGE_SPEED),
        )
        Text(
            text = "Tempo total: ${uiState.elapsedTimeMillis} ms",
            modifier = Modifier.testTag(SessionDetailScreenTestTags.ELAPSED_TIME),
        )
        Text(
            text = "Tempo parado: ${uiState.stoppedTimeMillis} ms",
            modifier = Modifier.testTag(SessionDetailScreenTestTags.STOPPED_TIME),
        )
        Text(
            text = "Tempo em movimento: ${uiState.movingTimeMillis} ms",
            modifier = Modifier.testTag(SessionDetailScreenTestTags.MOVING_TIME),
        )

        // UI-05: export action available only for a finished ("encerrada") session.
        if (uiState.isExportAvailable) {
            Button(
                onClick = { showExportDialog = true },
                modifier = Modifier.testTag(SessionDetailScreenTestTags.EXPORT_ACTION),
            ) {
                Text("Exportar")
            }
        }
    }

    if (showExportDialog) {
        ExportFormatDialog(
            onDismissRequest = { showExportDialog = false },
            onFormatSelected = { format ->
                showExportDialog = false
                coroutineScope.launch { onExport(uiState.sessionId, format) }
            },
        )
    }
}
