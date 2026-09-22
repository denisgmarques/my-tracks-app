package com.mytracksapp.ui.tracking

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.domain.stats.SegmentClassifier
import com.mytracksapp.domain.stats.StatsEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A single polyline vertex (UI-02): a GPS point's position only, in the order it was collected.
 */
data class TrackingPolylinePoint(val latitude: Double, val longitude: Double)

/**
 * UI state for [TrackingViewModel] — the live polyline (UI-02) plus the 5 UI-03 metrics for the
 * active session: instant speed, average speed, total elapsed time, stopped time, moving time.
 *
 * This deliberately mirrors [com.mytracksapp.ui.history.SessionDetailUiState]'s 5-metric shape:
 * SPEC.md UI-03 requires the exact same 5 values, displayed the same way, whether the session is
 * still active (this ViewModel) or already finished (`SessionDetailViewModel`) — "WHILE a sessão
 * está ativa OU em tela de detalhe de uma sessão encerrada, THE SYSTEM SHALL exibir
 * simultaneamente [as 5 métricas]".
 */
data class TrackingUiState(
    val sessionId: String = "",
    val polyline: List<TrackingPolylinePoint> = emptyList(),
    val instantSpeedMetersPerSecond: Double = 0.0,
    val averageSpeedMetersPerSecond: Double = 0.0,
    val elapsedTimeMillis: Long = 0L,
    val stoppedTimeMillis: Long = 0L,
    val movingTimeMillis: Long = 0L,
)

/**
 * T08 — reactive live-tracking state for an active session (UI-02, UI-03, RNF-04).
 *
 * Observes [gpsPointDao]'s `Flow<List<GpsPointEntity>>` for [sessionId] (Room's
 * `GpsPointDao.getPointsForSession`, ordered ascending by timestamp). On every emission — i.e.
 * every time a new point is persisted to Room and the query re-runs (RNF-04: "gravação no Room DB
 * seguida de atualização do ViewModel/UI") — ALL recalculation is delegated to [StatsEngine] and
 * [SegmentClassifier], the same engines [com.mytracksapp.ui.history.SessionDetailViewModel] uses
 * for a finished session, so the math is never duplicated between the live and historical paths.
 * Only the polyline projection (point -> lat/lon) happens locally here, since it is pure
 * presentation shaping, not a stats computation.
 *
 * Per RNF-04/UI-02's AC, after a new point is persisted the polyline's last vertex and all 5
 * metrics reflect it on the Flow's next collection cycle — this class does nothing but forward
 * that cycle into [uiState].
 */
class TrackingViewModel(
    private val sessionId: String,
    private val gpsPointDao: GpsPointDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TrackingUiState(sessionId = sessionId))
    val uiState: StateFlow<TrackingUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            gpsPointDao.getPointsForSession(sessionId).collect { points ->
                val classification = SegmentClassifier.classify(points)
                _uiState.update { current ->
                    current.copy(
                        polyline = points.map { point ->
                            TrackingPolylinePoint(latitude = point.latitude, longitude = point.longitude)
                        },
                        instantSpeedMetersPerSecond = StatsEngine.instantSpeeds(points).lastOrNull() ?: 0.0,
                        averageSpeedMetersPerSecond = StatsEngine.averageSpeedMetersPerSecond(points),
                        elapsedTimeMillis = StatsEngine.elapsedTimeMillis(points),
                        stoppedTimeMillis = classification.stoppedTimeMillis,
                        movingTimeMillis = classification.movingTimeMillis,
                    )
                }
            }
        }
    }
}
