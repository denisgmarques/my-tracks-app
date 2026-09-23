package com.mytracksapp.ui.history

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.mytracksapp.ui.theme.Accent2
import com.mytracksapp.ui.theme.Accent2800
import com.mytracksapp.ui.theme.Accent700
import com.mytracksapp.ui.theme.Accent800
import com.mytracksapp.ui.theme.ColorBg
import com.mytracksapp.ui.theme.Neutral300
import com.mytracksapp.ui.theme.Neutral400
import com.mytracksapp.ui.theme.Neutral700
import com.mytracksapp.ui.theme.PillShape
import com.mytracksapp.ui.theme.bodyFontFamily
import com.mytracksapp.ui.theme.elevMd
import com.mytracksapp.ui.theme.headingFontFamily
import com.mytracksapp.ui.tracking.MapComponent
import com.mytracksapp.ui.tracking.MapMarkerInfo
import com.mytracksapp.ui.tracking.TrackingPolylinePoint
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
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
 *
 * [startTimestamp] backs [formattedStartDate] (design handoff's "Hoje · {lugar}" context line,
 * adapted for a finished session — see [SessionDetailScreen]'s doc for why "Hoje" itself doesn't
 * fit here): reuses the exact `dd/MM/yyyy` formatting [HistoryListItem.formattedStartDate] already
 * established for this same underlying field, so a session's date reads identically whether seen
 * from the history list or from this detail screen.
 */
data class SessionDetailUiState(
    val sessionId: String = "",
    val samplingIntervalSeconds: Int = 0,
    val status: SessionStatus = SessionStatus.ACTIVE,
    val startTimestamp: Long = 0L,
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

    /** Start date only (no time-of-day) — same `dd/MM/yyyy` format as [HistoryListItem.formattedStartDate]. */
    val formattedStartDate: String
        get() = DISPLAY_DATE_FORMATTER.format(Instant.ofEpochMilli(startTimestamp).atZone(ZoneId.systemDefault()))

    private companion object {
        val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
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
                            startTimestamp = session.startTimestamp,
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
    const val MAP_EXPAND_BUTTON = "session_detail_map_expand_button"
}

/** Marker title used for every stop pin rendered on [SessionDetailScreen]'s map (RF-06). */
private const val STOP_MARKER_TITLE = "Parada"

private val MapCollapsedHeight = 220.dp
private val MapExpandedHeight = 420.dp

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
 *
 * Design-system pass (`design_handoff_my_tracks/`): restyled to match
 * [com.mytracksapp.ui.tracking.TrackingScreen]'s now-established "Organic" visual language — this
 * is the design's `isCollecting: false` state (its own doc: no instant-speed card, since a
 * finished session has no "current" reading). A few of the same judgment calls TrackingScreen's
 * doc records apply here too, adapted for a static/finished session instead of a live one:
 *  - **Header**: same as TrackingScreen — the shared `TopAppBar` (see [com.mytracksapp.ui.navigation.MyTracksApp])
 *    already renders the hamburger menu above this screen, so only the "Exportar" action is
 *    rendered here, right-aligned, at the same size/shape/position TrackingScreen used for its
 *    (there, non-functional) Exportar button. Unlike TrackingScreen's, this button is wired to a
 *    REAL export flow (this screen only ever shows a session eligible to export once
 *    [SessionDetailUiState.isExportAvailable] is true) — it is only rendered at all when that's
 *    true, exactly matching the prior behavior existing tests already depend on
 *    ([SessionDetailScreenTestTags.EXPORT_ACTION] absent entirely for a non-finished session).
 *  - **Context line + heading**: the design's small context line ("Hoje · Praia do Gravatá") is
 *    dishonest to reuse verbatim for a session that didn't happen "today" and that this app has no
 *    location-naming feature for (same reasoning as TrackingScreen's doc). Here the context line
 *    is the session's actual start date ([SessionDetailUiState.formattedStartDate], the same
 *    `dd/MM/yyyy` format already used in [HistoryListScreen]), and the heading slot — which
 *    TrackingScreen filled with "Sessão em andamento" — becomes "Sessão encerrada", the honest,
 *    parallel status for this screen's always-finished-or-inspectable session.
 *  - **Distance as a solo hero number**: the design's distance+instant-speed row assumed two
 *    blocks side by side; with no instant-speed card here, the distance number keeps its exact
 *    TrackingScreen treatment (48sp Caprasimo, [Accent700], unit suffix at baseline) but now fills
 *    its row alone rather than sharing it — kept left-aligned (not centered) so the "hero metric"
 *    still reads as the same visual object a returning user already recognizes from the live
 *    screen, just without a neighboring card.
 *  - **Map card**: same rounded-corner container, floating Início/Fim tags and 220dp↔420dp
 *    expand/collapse interaction as TrackingScreen's map card. The pattern is duplicated as a
 *    private composable in this file rather than extracted into a shared one:
 *    [com.mytracksapp.ui.tracking.MapComponent]'s call site in TrackingScreen has no `markers`
 *    parameter wired at all, so a shared extraction would need to change TrackingScreen's call
 *    site (and its test tags) too — a bigger, riskier touch to an already-shipped, reviewed screen
 *    than this visual-parity pass warrants. This screen's map card additionally keeps the
 *    EXISTING stop-location pins ([SessionDetailUiState.stopLocations] -> [MapMarkerInfo]) as a
 *    separate, additional marker set alongside the Início/Fim positional tags — the tags are a
 *    purely visual overlay (not real map markers), so both coexist without conflict.
 *  - **Footer**: this screen has no primary action analogous to TrackingScreen's "Encerrar
 *    sessão" (the session is already finished), so — unlike TrackingScreen, which repurposed the
 *    footer for that action — this screen renders no custom footer at all; the app-wide bottom bar
 *    (Histórico/Nova sessão, from [com.mytracksapp.ui.navigation.MyTracksApp]) already appears
 *    beneath it, matching the design's own reference footer with zero duplication.
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
            .background(MaterialTheme.colorScheme.background)
            .testTag(SessionDetailScreenTestTags.SCREEN),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Everything below depends on knowing the REAL session data (status, dates, points) —
            // until `isLoaded` flips true, `uiState` only holds SessionDetailUiState's placeholder
            // defaults (`status = ACTIVE`, `startTimestamp = 0L`, empty polyline, etc.), which do
            // NOT necessarily match this session's actual final values. Gating the whole body on
            // `isLoaded` avoids a first-frame flash where e.g. the export button, the Início/Fim
            // map tags or the start-date line would briefly render from those wrong defaults (button
            // hidden/date wrong) and then visibly "pop" to the correct state a moment later once the
            // first real Flow emission lands — rather than papering over it with an artificial delay,
            // this renders nothing here until the data backing these decisions is actually known.
            if (uiState.isLoaded) {
                // Header: only "Exportar" (only when actually available), same position/shape as
                // TrackingScreen's — see class doc's "Header" note. Real functionality, unlike there.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (uiState.isExportAvailable) {
                        OutlinedButton(
                            onClick = { showExportDialog = true },
                            shape = PillShape,
                            modifier = Modifier
                                .height(44.dp)
                                .testTag(SessionDetailScreenTestTags.EXPORT_ACTION),
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Exportar")
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                ) {
                    // Context line + session status (see class doc's "Context line + heading" note).
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = uiState.formattedStartDate,
                            fontSize = 14.sp,
                            color = Neutral700,
                        )
                        Text(
                            text = "Sessão encerrada",
                            style = TextStyle(
                                fontFamily = headingFontFamily,
                                fontWeight = FontWeight.Normal,
                                fontSize = 24.sp,
                                lineHeight = 26.sp,
                            ),
                            color = MaterialTheme.colorScheme.onBackground,
                        )
                    }

                    // Distance total: solo hero metric — no instant-speed card beside it (see class
                    // doc's "Distance as a solo hero number" note).
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Distância total",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Neutral700,
                        )
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "%.2f".format(
                                    DistanceFormatter.toDisplayValue(uiState.totalDistanceMeters, uiState.distanceUnit),
                                ),
                                style = TextStyle(
                                    fontFamily = headingFontFamily,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 48.sp,
                                    lineHeight = 48.sp,
                                ),
                                color = Accent700,
                                modifier = Modifier.testTag(SessionDetailScreenTestTags.TOTAL_DISTANCE),
                            )
                            Text(
                                text = uiState.distanceUnit.displaySuffix,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }

                    // Secondary metric cards: average speed + total elapsed time.
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SecondaryMetricCard(
                            modifier = Modifier.weight(1f),
                            label = "Velocidade média",
                            value = "%.2f".format(
                                SpeedFormatter.toDisplayValue(uiState.averageSpeedMetersPerSecond, uiState.speedUnit),
                            ),
                            suffix = uiState.speedUnit.displaySuffix,
                            valueTestTag = SessionDetailScreenTestTags.AVERAGE_SPEED,
                        )
                        SecondaryMetricCard(
                            modifier = Modifier.weight(1f),
                            label = "Tempo total",
                            value = ElapsedTimeFormatter.format(uiState.elapsedTimeMillis),
                            suffix = null,
                            valueTestTag = SessionDetailScreenTestTags.ELAPSED_TIME,
                        )
                    }

                    // Segmented time bar: moving (sage) vs. stopped (neutral), proportional to millis.
                    MovingStoppedBar(
                        movingTimeMillis = uiState.movingTimeMillis,
                        stoppedTimeMillis = uiState.stoppedTimeMillis,
                    )

                    // Map card: rounded corners, floating Início/Fim tags, expand/collapse toggle,
                    // PLUS the existing stop-location pins (see class doc's "Map card" note).
                    SessionMapCard(
                        polyline = uiState.polyline,
                        stopLocations = uiState.stopLocations,
                        onPolylineApplied = onPolylineApplied,
                        onMarkersApplied = onMarkersApplied,
                    )
                }
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

/**
 * Duplicated (deliberately — see [SessionDetailScreen]'s class doc "Map card" note) from
 * TrackingScreen's map-card pattern: same rounded container, floating Início/Fim tags and
 * 220dp<->420dp expand/collapse interaction, ADDITIONALLY passing [stopLocations] through to
 * [MapComponent] as real map markers (RF-06's stop pins), which TrackingScreen's live map has no
 * equivalent of.
 */
@Composable
private fun SessionMapCard(
    polyline: List<TrackingPolylinePoint>,
    stopLocations: List<StopLocation>,
    onPolylineApplied: (List<LatLng>) -> Unit,
    onMarkersApplied: (List<LatLng>) -> Unit,
) {
    var mapExpanded by rememberSaveable { mutableStateOf(false) }
    val mapHeight by animateDpAsState(
        targetValue = if (mapExpanded) MapExpandedHeight else MapCollapsedHeight,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "sessionDetailMapCardHeight",
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(mapHeight)
            .clip(MaterialTheme.shapes.large),
    ) {
        MapComponent(
            polyline = polyline,
            markers = stopLocations.map { stop ->
                MapMarkerInfo(
                    position = LatLng(stop.latitude, stop.longitude),
                    title = STOP_MARKER_TITLE,
                    snippet = ElapsedTimeFormatter.format(stop.durationMillis),
                )
            },
            modifier = Modifier.fillMaxSize(),
            onPolylineApplied = onPolylineApplied,
            onMarkersApplied = onMarkersApplied,
        )

        if (polyline.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MapTag(text = "Início", background = ColorBg, textColor = Accent2800)
                MapTag(text = "Fim", background = ColorBg, textColor = Accent800)
            }
        }

        IconButton(
            onClick = { mapExpanded = !mapExpanded },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
                .size(44.dp)
                .elevMd(shape = CircleShape)
                .clip(CircleShape)
                .background(ColorBg)
                .testTag(SessionDetailScreenTestTags.MAP_EXPAND_BUTTON),
            colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onBackground),
        ) {
            Icon(
                imageVector = if (mapExpanded) Icons.Filled.CloseFullscreen else Icons.Filled.OpenInFull,
                contentDescription = if (mapExpanded) "Recolher mapa" else "Expandir mapa",
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Duplicated from TrackingScreen's identical private composable — see class doc's "Map card" note. */
@Composable
private fun SecondaryMetricCard(
    label: String,
    value: String,
    suffix: String?,
    valueTestTag: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Neutral700,
        )
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = value,
                fontFamily = bodyFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.testTag(valueTestTag),
            )
            if (suffix != null) {
                Text(
                    text = suffix,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/**
 * Pill-shaped horizontal bar split into "moving" (sage, [Accent2]) and "stopped" (neutral)
 * segments proportional to their millis, plus the two-item legend below — matches
 * `my-tracks-design.html`'s segmented time bar section. Handles the zero-elapsed-time edge case
 * (no session data at all) by rendering a single full-width neutral bar instead of dividing by
 * zero. Duplicated from TrackingScreen's identical private composable — see class doc's "Map
 * card" note (same reasoning applies to this pattern).
 */
@Composable
private fun MovingStoppedBar(movingTimeMillis: Long, stoppedTimeMillis: Long) {
    val total = movingTimeMillis + stoppedTimeMillis
    val movingFraction = if (total > 0L) (movingTimeMillis.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (total <= 0L) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(PillShape)
                    .background(Neutral300),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (movingFraction > 0f) {
                    Box(
                        modifier = Modifier
                            .weight(movingFraction)
                            .fillMaxHeight()
                            .clip(PillShape)
                            .background(Accent2),
                    )
                }
                if (movingFraction < 1f) {
                    Box(
                        modifier = Modifier
                            .weight(1f - movingFraction)
                            .fillMaxHeight()
                            .clip(PillShape)
                            .background(Neutral300),
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            LegendItem(
                dotColor = Accent2,
                label = "Em movimento",
                value = ElapsedTimeFormatter.format(movingTimeMillis),
                valueTestTag = SessionDetailScreenTestTags.MOVING_TIME,
            )
            LegendItem(
                dotColor = Neutral400,
                label = "Parado",
                value = ElapsedTimeFormatter.format(stoppedTimeMillis),
                valueTestTag = SessionDetailScreenTestTags.STOPPED_TIME,
            )
        }
    }
}

@Composable
private fun LegendItem(
    dotColor: androidx.compose.ui.graphics.Color,
    label: String,
    value: String,
    valueTestTag: String,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(dotColor),
        )
        Text(text = label, fontSize = 14.sp, color = MaterialTheme.colorScheme.onBackground)
        Text(
            text = value,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.testTag(valueTestTag),
        )
    }
}

@Composable
private fun MapTag(text: String, background: androidx.compose.ui.graphics.Color, textColor: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(background)
            .padding(horizontal = 10.dp, vertical = 3.dp),
    ) {
        Text(text = text, fontSize = 11.sp, color = textColor)
    }
}
