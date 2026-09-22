package com.mytracksapp.ui.tracking

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.model.LatLng

/** Stable test tags for [TrackingScreen], used by TrackingScreenTest (UI-02, UI-03). */
object TrackingScreenTestTags {
    const val SCREEN = "tracking_screen"
    const val INSTANT_SPEED = "tracking_instant_speed"
    const val AVERAGE_SPEED = "tracking_average_speed"
    const val ELAPSED_TIME = "tracking_elapsed_time"
    const val STOPPED_TIME = "tracking_stopped_time"
    const val MOVING_TIME = "tracking_moving_time"
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
 */
@Composable
fun TrackingScreen(
    viewModel: TrackingViewModel,
    modifier: Modifier = Modifier,
    onPolylineApplied: (List<LatLng>) -> Unit = {},
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag(TrackingScreenTestTags.SCREEN),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(text = "Sessão ${uiState.sessionId}", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "Velocidade instantânea: ${"%.2f".format(uiState.instantSpeedMetersPerSecond)} m/s",
                modifier = Modifier.testTag(TrackingScreenTestTags.INSTANT_SPEED),
            )
            Text(
                text = "Velocidade média: ${"%.2f".format(uiState.averageSpeedMetersPerSecond)} m/s",
                modifier = Modifier.testTag(TrackingScreenTestTags.AVERAGE_SPEED),
            )
            Text(
                text = "Tempo total: ${uiState.elapsedTimeMillis} ms",
                modifier = Modifier.testTag(TrackingScreenTestTags.ELAPSED_TIME),
            )
            Text(
                text = "Tempo parado: ${uiState.stoppedTimeMillis} ms",
                modifier = Modifier.testTag(TrackingScreenTestTags.STOPPED_TIME),
            )
            Text(
                text = "Tempo em movimento: ${uiState.movingTimeMillis} ms",
                modifier = Modifier.testTag(TrackingScreenTestTags.MOVING_TIME),
            )
        }

        MapComponent(
            polyline = uiState.polyline,
            modifier = Modifier.weight(1f),
            onPolylineApplied = onPolylineApplied,
        )
    }
}
