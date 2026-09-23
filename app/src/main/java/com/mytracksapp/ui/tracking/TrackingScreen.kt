package com.mytracksapp.ui.tracking

import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
import com.mytracksapp.domain.units.DistanceFormatter
import com.mytracksapp.domain.units.ElapsedTimeFormatter
import com.mytracksapp.domain.units.SpeedFormatter
import com.mytracksapp.ui.theme.Accent2
import com.mytracksapp.ui.theme.Accent2100
import com.mytracksapp.ui.theme.Accent2700
import com.mytracksapp.ui.theme.Accent2800
import com.mytracksapp.ui.theme.Accent2900
import com.mytracksapp.ui.theme.Accent700
import com.mytracksapp.ui.theme.Accent800
import com.mytracksapp.ui.theme.ColorBg
import com.mytracksapp.ui.theme.Neutral300
import com.mytracksapp.ui.theme.Neutral700
import com.mytracksapp.ui.theme.PillShape
import com.mytracksapp.ui.theme.bodyFontFamily
import com.mytracksapp.ui.theme.elevMd
import com.mytracksapp.ui.theme.headingFontFamily
import kotlinx.coroutines.launch

/** Stable test tags for [TrackingScreen], used by TrackingScreenTest (UI-02, UI-03). */
object TrackingScreenTestTags {
    const val SCREEN = "tracking_screen"
    const val INSTANT_SPEED = "tracking_instant_speed"
    const val AVERAGE_SPEED = "tracking_average_speed"
    const val TOTAL_DISTANCE = "tracking_total_distance"
    const val ELAPSED_TIME = "tracking_elapsed_time"
    const val STOPPED_TIME = "tracking_stopped_time"
    const val MOVING_TIME = "tracking_moving_time"
    const val FINISH_BUTTON = "tracking_finish_button"
    const val EXPORT_BUTTON = "tracking_export_button"
    const val MAP_EXPAND_BUTTON = "tracking_map_expand_button"
}

private val MapCollapsedHeight = 220.dp
private val MapExpandedHeight = 420.dp

/**
 * T09 — active-session screen (UI-02, UI-03): hosts the live map ([MapComponent], polyline driven
 * by [viewModel]'s state) plus all 6 UI-03 metrics, all visible simultaneously with no extra
 * navigation needed to see any of them — mirroring how
 * [com.mytracksapp.ui.history.SessionDetailScreen] presents the same metrics for a finished
 * session.
 *
 * Visually restyled (design handoff `design_handoff_my_tracks/`) to match the "Sessão" screen
 * reference (`my-tracks-design.html`) — see the doc comments on individual sections below for how
 * each ambiguity called out in that handoff was resolved for this specific screen:
 *  - **Header**: the design's reference header has a hamburger menu button AND an "Exportar"
 *    button, `space-between`. This screen is always composed inside [com.mytracksapp.ui.navigation.MyTracksApp]'s
 *    `Scaffold`, whose shared `TopAppBar` already renders the hamburger menu (opens the same
 *    drawer) above this screen's content — duplicating a second menu button here would be a dead
 *    control with nothing new to do, so only the "Exportar" button is rendered, right-aligned.
 *  - **"My Tracks" title**: the design's content block pairs a small context line ("Hoje · Praia
 *    do Gravatá") with a big "My Tracks" heading. Repeating "My Tracks" here would be redundant
 *    with the app-wide `TopAppBar` title directly above it, and this app has no location-naming
 *    feature to fill in a real place name — inventing one ("Praia do Gravatá") would be dishonest
 *    UI. Kept only the small context line, and reused the heading slot for the session's actual
 *    status ("Sessão em andamento") instead of the redundant app name.
 *  - **Export**: [ExportService.export] (see its doc) explicitly rejects any session that isn't
 *    [com.mytracksapp.data.local.entity.SessionStatus.FINISHED] — an active session (this screen,
 *    always) can never satisfy that. Wiring a real export here would mean changing
 *    `ExportService`'s contract, which is out of scope for a visual redesign. The button is still
 *    rendered exactly per the design (so the visual element isn't silently dropped) but
 *    [onExportClick] defaults to a no-op; tapping it shows a short, honest explanation instead of
 *    silently doing nothing.
 *  - **Footer**: the design's reference footer shows "Histórico"/"Nova sessão" — global
 *    navigation this app already renders via `AppNavigation.kt`'s bottom bar on every screen
 *    (including this one). Duplicating those here would be two ways to do the same thing on
 *    screen at once. This screen's actual primary action — ending the session — has no place in
 *    the design's reference footer at all (that mockup depicts a screen with no live session), so
 *    it takes over the footer instead, restyled as the single full-width, pill-shaped 56dp
 *    `btn-primary` per the design's button spec.
 *
 * [onPolylineApplied] is an optional test seam forwarded verbatim to [MapComponent]; production
 * callers never set it (see [MapComponent]'s doc for why).
 *
 * [onFinishSession] is a suspend callback invoked with the current [TrackingUiState.sessionId];
 * production callers wire it to `SessionController.stopSession` and navigate away once it
 * completes. Defaults to a no-op so existing callers/tests that don't care about finishing a
 * session keep compiling.
 *
 * [onExportClick] is an optional extension seam for a real export flow, see "Export" above.
 * Defaults to a no-op so existing callers/tests keep compiling.
 */
@Composable
fun TrackingScreen(
    viewModel: TrackingViewModel,
    modifier: Modifier = Modifier,
    onPolylineApplied: (List<LatLng>) -> Unit = {},
    onFinishSession: suspend (String) -> Unit = {},
    onExportClick: () -> Unit = {},
    onKeepScreenOnApplied: (android.view.View) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    var isFinishing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    var mapExpanded by rememberSaveable { mutableStateOf(false) }

    // T09 (RF-07, RNF-04) — keeps the device screen on for exactly as long as this screen is
    // composed AND the preference is enabled, scoped to this composable's own View via LocalView
    // so no other screen (Histórico, Configurações, SessionDetailScreen) is ever affected.
    // Resetting to false in onDispose covers both leaving this screen and the preference flipping
    // off mid-session (the key recomposes the effect either way). [onKeepScreenOnApplied] is an
    // optional test seam (mirrors [onPolylineApplied]/[onExportClick] above) letting instrumented
    // tests observe the exact View instance this effect toggled, without depending on
    // Compose-internal test APIs; production callers never set it.
    val view = LocalView.current
    DisposableEffect(uiState.keepScreenOnEnabled) {
        view.keepScreenOn = uiState.keepScreenOnEnabled
        onKeepScreenOnApplied(view)
        onDispose {
            view.keepScreenOn = false
            onKeepScreenOnApplied(view)
        }
    }
    val mapHeight by animateDpAsState(
        targetValue = if (mapExpanded) MapExpandedHeight else MapCollapsedHeight,
        animationSpec = tween(durationMillis = 250, easing = FastOutSlowInEasing),
        label = "mapCardHeight",
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .testTag(TrackingScreenTestTags.SCREEN),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // Header: only "Exportar" — the hamburger menu already lives in the shared TopAppBar
            // above this screen (see the class doc's "Header" note).
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                OutlinedButton(
                    onClick = {
                        onExportClick()
                        Toast.makeText(
                            context,
                            "Exportação disponível ao encerrar a sessão",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                    shape = PillShape,
                    modifier = Modifier
                        .height(44.dp)
                        .testTag(TrackingScreenTestTags.EXPORT_BUTTON),
                ) {
                    Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Exportar")
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                // Context line + session status (see class doc's "My Tracks title" note).
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Hoje",
                        fontSize = 14.sp,
                        color = Neutral700,
                    )
                    Text(
                        text = "Sessão em andamento",
                        style = TextStyle(
                            fontFamily = headingFontFamily,
                            fontWeight = FontWeight.Normal,
                            fontSize = 24.sp,
                            lineHeight = 26.sp,
                        ),
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                }

                // Distance total + instant speed (always shown — this screen only ever displays
                // an active/collecting session, unlike SessionDetailScreen's finished sessions).
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                                modifier = Modifier.testTag(TrackingScreenTestTags.TOTAL_DISTANCE),
                            )
                            Text(
                                text = uiState.distanceUnit.displaySuffix,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onBackground,
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(MaterialTheme.shapes.large)
                            .background(Accent2100)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Accent2700),
                            )
                            Text(
                                text = "Velocidade instantânea",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Accent2900,
                            )
                        }
                        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "%.2f".format(
                                    SpeedFormatter.toDisplayValue(uiState.instantSpeedMetersPerSecond, uiState.speedUnit),
                                ),
                                fontFamily = bodyFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 24.sp,
                                color = Accent2900,
                                modifier = Modifier.testTag(TrackingScreenTestTags.INSTANT_SPEED),
                            )
                            Text(
                                text = uiState.speedUnit.displaySuffix,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Accent2900,
                            )
                        }
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
                        valueTestTag = TrackingScreenTestTags.AVERAGE_SPEED,
                    )
                    SecondaryMetricCard(
                        modifier = Modifier.weight(1f),
                        label = "Tempo total",
                        value = ElapsedTimeFormatter.format(uiState.elapsedTimeMillis),
                        suffix = null,
                        valueTestTag = TrackingScreenTestTags.ELAPSED_TIME,
                    )
                }

                // Segmented time bar: moving (sage) vs. stopped (neutral), proportional to millis.
                MovingStoppedBar(
                    movingTimeMillis = uiState.movingTimeMillis,
                    stoppedTimeMillis = uiState.stoppedTimeMillis,
                )

                // Map card: rounded corners, floating Início/Fim tags (only once the session has
                // at least one recorded point), expand/collapse toggle.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(mapHeight)
                        .clip(MaterialTheme.shapes.large),
                ) {
                    MapComponent(
                        polyline = uiState.polyline,
                        modifier = Modifier.fillMaxSize(),
                        onPolylineApplied = onPolylineApplied,
                    )

                    if (uiState.polyline.isNotEmpty()) {
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
                            .testTag(TrackingScreenTestTags.MAP_EXPAND_BUTTON),
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
        }

        // Footer: this screen's actual primary action (see class doc's "Footer" note) — not the
        // design's Histórico/Nova sessão pair, which is already the app-wide bottom bar.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 28.dp),
        ) {
            Button(
                onClick = {
                    if (!isFinishing) {
                        isFinishing = true
                        coroutineScope.launch {
                            onFinishSession(uiState.sessionId)
                            isFinishing = false
                        }
                    }
                },
                enabled = !isFinishing,
                shape = PillShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag(TrackingScreenTestTags.FINISH_BUTTON),
            ) {
                Text(
                    text = if (isFinishing) "Encerrando..." else "Encerrar sessão",
                    fontSize = 16.sp,
                )
            }
        }
    }
}

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
 * Pill-shaped horizontal bar split into "moving" (sage, [com.mytracksapp.ui.theme.Accent2]) and
 * "stopped" (neutral) segments proportional to their millis, plus the two-item legend below —
 * matches `my-tracks-design.html`'s segmented time bar section. Handles the zero-elapsed-time
 * edge case (no session data yet) by rendering a single full-width neutral bar instead of dividing
 * by zero.
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
                valueTestTag = TrackingScreenTestTags.MOVING_TIME,
            )
            LegendItem(
                dotColor = com.mytracksapp.ui.theme.Neutral400,
                label = "Parado",
                value = ElapsedTimeFormatter.format(stoppedTimeMillis),
                valueTestTag = TrackingScreenTestTags.STOPPED_TIME,
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
