package com.mytracksapp.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One row of the past-sessions list (UI-04): the 3 minimum required fields — id, start
 * date/time, configured sampling interval — plus a display-ready formatted date/time string.
 */
data class HistoryListItem(
    val sessionId: String,
    val startTimestamp: Long,
    val samplingIntervalSeconds: Int,
) {
    /** Human-readable start date/time, derived from [startTimestamp] in the device's zone. */
    val formattedStartDateTime: String
        get() = DISPLAY_FORMATTER.format(Instant.ofEpochMilli(startTimestamp).atZone(ZoneId.systemDefault()))

    private companion object {
        val DISPLAY_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    }
}

private fun TrackingSessionEntity.toHistoryListItem(): HistoryListItem = HistoryListItem(
    sessionId = id,
    startTimestamp = startTimestamp,
    samplingIntervalSeconds = samplingIntervalSeconds,
)

/** UI state for [HistoryListScreen]. */
data class HistoryUiState(val sessions: List<HistoryListItem> = emptyList())

/**
 * Backs [HistoryListScreen] (T10, UI-04): lists every persisted, finished session with its id,
 * start date/time, and configured sampling interval.
 *
 * Only [SessionStatus.FINISHED] sessions are shown — UI-04's wording ("sessão encerrada") and
 * RF-07's ("persistir ... ao encerrar a sessão, tornando-os disponíveis na lista de sessões
 * passadas") both scope this list to sessions that have actually finished, not ones still
 * in-flight (an [SessionStatus.ACTIVE] session doesn't yet have final metadata to show).
 */
class HistoryViewModel(
    trackingSessionDao: TrackingSessionDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            trackingSessionDao.getSessionsByStatus(SessionStatus.FINISHED).collect { sessions ->
                _uiState.update { it.copy(sessions = sessions.map { session -> session.toHistoryListItem() }) }
            }
        }
    }
}
