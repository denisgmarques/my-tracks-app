package com.mytracksapp.ui.tracking

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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng
import com.mytracksapp.domain.units.DistanceFormatter
import com.mytracksapp.domain.units.ElapsedTimeFormatter
import com.mytracksapp.domain.units.SpeedFormatter
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
}

/**
 * T09 — active-session screen (UI-02, UI-03): hosts the live map ([MapComponent], polyline driven
 * by [viewModel]'s state) plus all 5 UI-03 metrics, all visible simultaneously with no extra
 * navigation needed to see any of them — mirroring how
 * [com.mytracksapp.ui.history.SessionDetailScreen] presents the same 5 metrics for a finished
 * session.
 *
 * [onPolylineApplied] is an optional test seam forwarded verbatim to [MapComponent]; production
 * callers never set it (see [MapComponent]'s doc for why).
 *
 * Follow-up "wire it all together" task: SPEC.md/PLAN.md never gave "stop the active session"
 * button a home on any screen — without one, a session started via [com.mytracksapp.ui.newsession.NewSessionScreen]
 * could never reach [com.mytracksapp.data.local.entity.SessionStatus.FINISHED] through the UI, so
 * RF-04/RF-07/UI-04/UI-05 (all of which require a finished session) would be unreachable. This
 * screen is the only place an active session is displayed, so the minimal "Encerrar sessão"
 * button lives here. [onFinishSession] is a suspend callback invoked with the current
 * [TrackingUiState.sessionId]; production callers wire it to `SessionController.stopSession` and
 * navigate away once it completes. Defaults to a no-op so existing callers/tests that don't care
 * about finishing a session keep compiling.
 */
@Composable
fun TrackingScreen(
    viewModel: TrackingViewModel,
    modifier: Modifier = Modifier,
    onPolylineApplied: (List<LatLng>) -> Unit = {},
    onFinishSession: suspend (String) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()
    var isFinishing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(TrackingScreenTestTags.SCREEN),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = "Velocidade instantânea: ${"%.2f".format(
                    SpeedFormatter.toDisplayValue(uiState.instantSpeedMetersPerSecond, uiState.speedUnit),
                )} ${uiState.speedUnit.displaySuffix}",
                modifier = Modifier.testTag(TrackingScreenTestTags.INSTANT_SPEED),
            )
            Text(
                text = "Velocidade média: ${"%.2f".format(
                    SpeedFormatter.toDisplayValue(uiState.averageSpeedMetersPerSecond, uiState.speedUnit),
                )} ${uiState.speedUnit.displaySuffix}",
                modifier = Modifier.testTag(TrackingScreenTestTags.AVERAGE_SPEED),
            )
            Text(
                text = "Distância total: ${"%.2f".format(
                    DistanceFormatter.toDisplayValue(uiState.totalDistanceMeters, uiState.distanceUnit),
                )} ${uiState.distanceUnit.displaySuffix}",
                modifier = Modifier.testTag(TrackingScreenTestTags.TOTAL_DISTANCE),
            )
            Text(
                text = "Tempo total: ${ElapsedTimeFormatter.format(uiState.elapsedTimeMillis)}",
                modifier = Modifier.testTag(TrackingScreenTestTags.ELAPSED_TIME),
            )
            Text(
                text = "Tempo parado: ${ElapsedTimeFormatter.format(uiState.stoppedTimeMillis)}",
                modifier = Modifier.testTag(TrackingScreenTestTags.STOPPED_TIME),
            )
            Text(
                text = "Tempo em movimento: ${ElapsedTimeFormatter.format(uiState.movingTimeMillis)}",
                modifier = Modifier.testTag(TrackingScreenTestTags.MOVING_TIME),
            )
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
                modifier = Modifier.testTag(TrackingScreenTestTags.FINISH_BUTTON),
            ) {
                Text(text = if (isFinishing) "Encerrando..." else "Encerrar sessão")
            }
        }

        MapComponent(
            polyline = uiState.polyline,
            modifier = Modifier.weight(1f),
            onPolylineApplied = onPolylineApplied,
        )
    }
}
