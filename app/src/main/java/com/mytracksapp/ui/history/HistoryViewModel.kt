package com.mytracksapp.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.domain.units.ElapsedTimeFormatter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One row of the past-sessions list (UI-04): [sessionId] is kept for navigation (tapping a row)
 * but deliberately NOT rendered in [HistoryListScreen] — a raw UUID is not meaningful to a human
 * scanning their trip history. The row shows the start date and the trip's total duration instead
 * of the raw sampling-interval count, per the developer's explicit UX feedback.
 */
data class HistoryListItem(
    val sessionId: String,
    val startTimestamp: Long,
    val durationMillis: Long,
) {
    /** Start date only (no time-of-day), per the developer's requested `DD/MM/YYYY` format. */
    val formattedStartDate: String
        get() = DISPLAY_DATE_FORMATTER.format(Instant.ofEpochMilli(startTimestamp).atZone(ZoneId.systemDefault()))

    /** Total trip duration (`mm:ss` / `h:mm:ss`), replacing the raw sampling-interval display. */
    val formattedDuration: String
        get() = ElapsedTimeFormatter.format(durationMillis)

    private companion object {
        val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}

private fun TrackingSessionEntity.toHistoryListItem(): HistoryListItem = HistoryListItem(
    sessionId = id,
    startTimestamp = startTimestamp,
    // endTimestamp is always non-null here: this list is scoped to FINISHED sessions only.
    durationMillis = (endTimestamp ?: startTimestamp) - startTimestamp,
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
    private val trackingSessionDao: TrackingSessionDao,
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

    /**
     * Permanently deletes a session (and, via the DB's cascading foreign key, every GPS point
     * recorded for it). The caller (the screen) is responsible for confirming with the user
     * BEFORE calling this — this function itself does not ask again.
     */
    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            trackingSessionDao.deleteById(sessionId)
        }
    }
}
