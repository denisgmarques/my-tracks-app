package com.mytracksapp.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.units.DistanceFormatter
import com.mytracksapp.domain.units.DistanceUnit
import com.mytracksapp.domain.units.ElapsedTimeFormatter
import com.mytracksapp.logging.FileLogger
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** UI-01's generic, non-blank placeholder shown in place of a session's location name when it is absent. */
const val GENERIC_LOCATION_NAME_PLACEHOLDER = "Local não identificado"

/**
 * One row of the past-sessions list (UI-04): [sessionId] is kept for navigation (tapping a row)
 * but deliberately NOT rendered in [HistoryListScreen] — a raw UUID is not meaningful to a human
 * scanning their trip history. The row shows the start date and the trip's total duration instead
 * of the raw sampling-interval count, per the developer's explicit UX feedback.
 *
 * T11 (RF-13, UI-01, UI-02): also carries [locationName] (best-effort reverse-geocoded name, `null`
 * until T06's [com.mytracksapp.domain.geocoding.FirstPointGeocodingCoordinator] succeeds, or forever
 * for a session recorded before this feature — no backfill, per SPEC's Scope) and [distanceMeters]
 * (persisted once in `SessionControllerImpl.stopSession`, T08) plus [distanceUnit] — the CURRENTLY
 * CONFIGURED display unit (from [SettingsRepository]), captured per item so [formattedDistance]
 * reflects it without [HistoryListScreen] having to thread the unit through separately.
 */
data class HistoryListItem(
    val sessionId: String,
    val startTimestamp: Long,
    val durationMillis: Long,
    val locationName: String?,
    val distanceMeters: Double,
    val distanceUnit: DistanceUnit,
) {
    /** Start date only (no time-of-day), per the developer's requested `DD/MM/YYYY` format. */
    val formattedStartDate: String
        get() = DISPLAY_DATE_FORMATTER.format(Instant.ofEpochMilli(startTimestamp).atZone(ZoneId.systemDefault()))

    /** Total trip duration (`mm:ss` / `h:mm:ss`), replacing the raw sampling-interval display. */
    val formattedDuration: String
        get() = ElapsedTimeFormatter.format(durationMillis)

    /** UI-01: [locationName] when present and non-blank, otherwise a generic, never-blank placeholder. */
    val formattedLocationName: String
        get() = locationName?.takeIf { it.isNotBlank() } ?: GENERIC_LOCATION_NAME_PLACEHOLDER

    /** UI-02: total distance converted to the currently configured [distanceUnit], with its suffix. */
    val formattedDistance: String
        get() = "%.2f %s".format(
            DistanceFormatter.toDisplayValue(distanceMeters, distanceUnit),
            distanceUnit.displaySuffix,
        )

    private companion object {
        val DISPLAY_DATE_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}

private fun TrackingSessionEntity.toHistoryListItem(distanceUnit: DistanceUnit): HistoryListItem = HistoryListItem(
    sessionId = id,
    startTimestamp = startTimestamp,
    // endTimestamp is always non-null here: this list is scoped to FINISHED sessions only.
    durationMillis = (endTimestamp ?: startTimestamp) - startTimestamp,
    locationName = locationName,
    distanceMeters = distanceMeters,
    distanceUnit = distanceUnit,
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
 *
 * T11 (RF-13, UI-01, UI-02): combines the sessions flow with [settingsRepository]'s
 * `Flow<UserSettings>` — mirroring the exact pattern already used by `SessionDetailViewModel`/
 * `TrackingViewModel` — so [HistoryListItem.formattedDistance] stays reactive to a live
 * `distanceUnit` change while this screen is on screen, not just at first load.
 */
class HistoryViewModel(
    private val trackingSessionDao: TrackingSessionDao,
    private val settingsRepository: SettingsRepository,
    private val logger: Logger = FileLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                combine(
                    trackingSessionDao.getSessionsByStatus(SessionStatus.FINISHED),
                    settingsRepository.userSettings,
                ) { sessions, settings ->
                    sessions.map { session -> session.toHistoryListItem(settings.distanceUnit) }
                }.collect { items ->
                    _uiState.update { it.copy(sessions = items) }
                }
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "HistoryViewModel", "Failed to collect sessions", e)
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
            try {
                trackingSessionDao.deleteById(sessionId)
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "HistoryViewModel", "Failed to delete session $sessionId", e)
            }
        }
    }
}
