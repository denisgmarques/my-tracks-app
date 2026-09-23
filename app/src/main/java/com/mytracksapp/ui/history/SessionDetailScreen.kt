package com.mytracksapp.ui.history

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
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
import com.google.android.gms.maps.model.LatLng
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.export.ExportFormat
import com.mytracksapp.domain.stats.SegmentClassifier
import com.mytracksapp.domain.stats.StatsEngine
import com.mytracksapp.domain.stats.StopLocation
import com.mytracksapp.domain.units.DistanceFormatter
import com.mytracksapp.domain.units.DistanceUnit
import com.mytracksapp.domain.units.ElapsedTimeFormatter
import com.mytracksapp.domain.units.SpeedFormatter
import com.mytracksapp.domain.units.SpeedUnit
import com.mytracksapp.ui.export.ExportFormatDialog
import com.mytracksapp.ui.tracking.MapComponent
import com.mytracksapp.ui.tracking.MapMarkerInfo
import com.mytracksapp.ui.tracking.TrackingPolylinePoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for [SessionDetailScreen] — the route (for the map) plus 5 metrics for a finished
 * session: average speed, total distance, total elapsed time, stopped time and moving time.
 * Instant speed is deliberately NOT shown here (unlike the live [com.mytracksapp.ui.tracking.TrackingScreen]):
 * this screen only ever displays an already-finished session, and "the speed at the last recorded
 * instant" is not a meaningful thing to highlight for a static, completed trip — average speed and
 * total distance tell the real story instead. [stopLocations] (Phase C follow-up) are the pins
 * [MapComponent] renders, derived using the CURRENTLY CONFIGURED stop-detection thresholds
 * ([SettingsRepository]), not RF-06's hardcoded defaults, so reviewing an old session reflects
 * today's settings.
 *
 * As with [com.mytracksapp.ui.tracking.TrackingUiState], speed/distance values remain in their
 * internal canonical units; [speedUnit]/[distanceUnit] are the currently configured display
 * units, read reactively, so the screen re-renders correctly if the user changes units while
 * viewing this screen.
 */
data class SessionDetailUiState(
    val sessionId: String = "",
    val samplingIntervalSeconds: Int = 0,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val polyline: List<TrackingPolylinePoint> = emptyList(),
    val averageSpeedMetersPerSecond: Double = 0.0,
    val totalDistanceMeters: Double = 0.0,
    val elapsedTimeMillis: Long = 0L,
    val stoppedTimeMillis: Long = 0L,
    val movingTimeMillis: Long = 0L,
    val stopLocations: List<StopLocation> = emptyList(),
    val speedUnit: SpeedUnit = SpeedUnit.KMH,
    val distanceUnit: DistanceUnit = DistanceUnit.KM,
    val isLoaded: Boolean = false,
) {
    /** UI-05/RF-08: the export action is only ever available for a session with status "encerrada". */
    val isExportAvailable: Boolean get() = status == SessionStatus.FINISHED
}

/**
 * Backs [SessionDetailScreen] (T10): recomputes the finished-session metrics for [sessionId]
 * directly from its persisted points, via [StatsEngine]/[SegmentClassifier] — the same engines
 * `TrackingViewModel` uses for an active session — so the numbers are always derived the same way;
 * this screen just doesn't surface instant speed (see [SessionDetailUiState]'s doc for why).
 *
 * Phase C follow-up: also combines [settingsRepository]'s `Flow<UserSettings>` so (a) the
 * exposed display units stay live if the user changes them while viewing this screen, and (b)
 * [SegmentClassifier.classify] runs with the CURRENTLY CONFIGURED stop-radius/stop-duration
 * thresholds rather than RF-06's hardcoded defaults — the stop pins reflect today's settings even
 * for a session recorded under different ones.
 */
class SessionDetailViewModel(
    private val sessionId: String,
    trackingSessionDao: TrackingSessionDao,
    gpsPointDao: GpsPointDao,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionDetailUiState(sessionId = sessionId))
    val uiState: StateFlow<SessionDetailUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            combine(
                trackingSessionDao.getSessionById(sessionId),
                gpsPointDao.getPointsForSession(sessionId),
                settingsRepository.userSettings,
            ) { session, points, settings -> Triple(session, points, settings) }
                .collect { (session, points, settings) ->
                    if (session == null) return@collect
                    val classification = SegmentClassifier.classify(
                        points,
                        settings.stopRadiusMeters,
                        settings.stopDurationMillis,
                    )
                    _uiState.update {
                        it.copy(
                            samplingIntervalSeconds = session.samplingIntervalSeconds,
                            status = session.status,
                            polyline = points.map { point ->
                                TrackingPolylinePoint(latitude = point.latitude, longitude = point.longitude)
                            },
                            averageSpeedMetersPerSecond = StatsEngine.averageSpeedMetersPerSecond(points),
                            totalDistanceMeters = StatsEngine.totalDistanceMeters(points),
                            elapsedTimeMillis = StatsEngine.elapsedTimeMillis(points),
                            stoppedTimeMillis = classification.stoppedTimeMillis,
                            movingTimeMillis = classification.movingTimeMillis,
                            stopLocations = classification.stopLocations,
                            speedUnit = settings.speedUnit,
                            distanceUnit = settings.distanceUnit,
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
    const val AVERAGE_SPEED = "session_detail_average_speed"
    const val TOTAL_DISTANCE = "session_detail_total_distance"
    const val ELAPSED_TIME = "session_detail_elapsed_time"
    const val STOPPED_TIME = "session_detail_stopped_time"
    const val MOVING_TIME = "session_detail_moving_time"
    const val EXPORT_ACTION = "session_detail_export_action"
}

/** Marker title used for every stop pin rendered on [SessionDetailScreen]'s map (RF-06). */
private const val STOP_MARKER_TITLE = "Parada"

/**
 * Finished-session detail screen (T10, UI-03): displays 5 metrics — average speed, total
 * distance, total elapsed time, stopped time, moving time (no instant speed, no session id — see
 * [SessionDetailUiState]'s doc) — simultaneously, with no further navigation needed to see any of
 * them, plus (Phase C follow-up) the session's
 * route and stop-location pins on a [MapComponent], mirroring
 * [com.mytracksapp.ui.tracking.TrackingScreen]'s live layout but for a static, already-recorded
 * route — no camera-follow logic is needed here beyond [MapComponent]'s existing bounds-fit,
 * which already frames a static list (route + pins) just as well as a live-growing one.
 *
 * T12/UI-05: also exposes an export action, visible ONLY when the session's status is
 * [SessionStatus.FINISHED] ("encerrada"). Triggering it shows [ExportFormatDialog]'s GPX/CSV
 * picker; picking either format invokes [onExport] with `(sessionId, format)` — production callers
 * wire this to `ExportService::export` (RF-08), e.g. `{ id, format -> exportService.export(id, format) }`.
 * Defaults to a no-op so existing callers/tests that don't care about export keep compiling.
 *
 * [onPolylineApplied]/[onMarkersApplied] are optional test seams forwarded verbatim to
 * [MapComponent]; production callers never set them (see [MapComponent]'s doc for why).
 */
@Composable
fun SessionDetailScreen(
    viewModel: SessionDetailViewModel,
    modifier: Modifier = Modifier,
    onExport: suspend (String, ExportFormat) -> Unit = { _, _ -> },
    onPolylineApplied: (List<LatLng>) -> Unit = {},
    onMarkersApplied: (List<LatLng>) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    var showExportDialog by rememberSaveable { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(SessionDetailScreenTestTags.SCREEN),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "Velocidade média: ${"%.2f".format(
                    SpeedFormatter.toDisplayValue(uiState.averageSpeedMetersPerSecond, uiState.speedUnit),
                )} ${uiState.speedUnit.displaySuffix}",
                modifier = Modifier.testTag(SessionDetailScreenTestTags.AVERAGE_SPEED),
            )
            Text(
                text = "Distância total: ${"%.2f".format(
                    DistanceFormatter.toDisplayValue(uiState.totalDistanceMeters, uiState.distanceUnit),
                )} ${uiState.distanceUnit.displaySuffix}",
                modifier = Modifier.testTag(SessionDetailScreenTestTags.TOTAL_DISTANCE),
            )
            Text(
                text = "Tempo total: ${ElapsedTimeFormatter.format(uiState.elapsedTimeMillis)}",
                modifier = Modifier.testTag(SessionDetailScreenTestTags.ELAPSED_TIME),
            )
            Text(
                text = "Tempo parado: ${ElapsedTimeFormatter.format(uiState.stoppedTimeMillis)}",
                modifier = Modifier.testTag(SessionDetailScreenTestTags.STOPPED_TIME),
            )
            Text(
                text = "Tempo em movimento: ${ElapsedTimeFormatter.format(uiState.movingTimeMillis)}",
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

        MapComponent(
            polyline = uiState.polyline,
            markers = uiState.stopLocations.map { stop ->
                MapMarkerInfo(
                    position = LatLng(stop.latitude, stop.longitude),
                    title = STOP_MARKER_TITLE,
                    snippet = ElapsedTimeFormatter.format(stop.durationMillis),
                )
            },
            modifier = Modifier.weight(1f),
            onPolylineApplied = onPolylineApplied,
            onMarkersApplied = onMarkersApplied,
        )
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
