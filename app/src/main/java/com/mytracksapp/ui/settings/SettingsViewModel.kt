package com.mytracksapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.data.settings.UserSettings
import com.mytracksapp.domain.export.ExportFormat
import com.mytracksapp.domain.model.GpsPrecision
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.units.DistanceUnit
import com.mytracksapp.domain.units.SpeedUnit
import com.mytracksapp.logging.FileLogger
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Error message shown when the stop-radius field does not parse to a positive number. */
const val STOP_RADIUS_INVALID_MESSAGE = "Informe um valor numérico positivo para o raio de parada."

/** Error message shown when the stop-duration field does not parse to a positive number. */
const val STOP_DURATION_INVALID_MESSAGE = "Informe um valor numérico positivo para a duração da parada."

private const val MILLIS_PER_MINUTE = 60_000L

/** Formats a [Double] without a trailing ".0" for whole numbers, otherwise as-is. */
private fun formatNumber(value: Double): String =
    if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

private fun millisToMinutesText(millis: Long): String = formatNumber(millis / MILLIS_PER_MINUTE.toDouble())

/**
 * UI state for [SettingsScreen].
 *
 * The enum-backed fields ([samplingInterval], [speedUnit], [distanceUnit]) always mirror
 * [SettingsRepository]'s persisted values exactly, since selecting one of their fixed options
 * persists immediately (no local-only draft state for these). The two numeric fields keep their
 * own draft text ([stopRadiusMetersText]/[stopDurationMinutesText]) so the user can type freely;
 * they are only validated and persisted on commit (Done/focus-loss), not on every keystroke —
 * otherwise every partially-typed keystroke (e.g. an empty field while replacing a value) would
 * either persist garbage or need to silently reject input mid-typing.
 */
data class SettingsUiState(
    val samplingInterval: SamplingInterval = UserSettings().samplingInterval,
    val speedUnit: SpeedUnit = UserSettings().speedUnit,
    val distanceUnit: DistanceUnit = UserSettings().distanceUnit,
    val stopRadiusMetersText: String = formatNumber(UserSettings().stopRadiusMeters),
    val stopRadiusError: String? = null,
    val stopDurationMinutesText: String = millisToMinutesText(UserSettings().stopDurationMillis),
    val stopDurationError: String? = null,
    val gpsPrecision: GpsPrecision = UserSettings().gpsPrecision,
    val keepScreenOnEnabled: Boolean = UserSettings().keepScreenOnEnabled,
    val defaultExportFormat: ExportFormat = UserSettings().defaultExportFormat,
)

/**
 * Backs [SettingsScreen] (follow-up phase): reads and writes [SettingsRepository] directly.
 *
 * The stop-duration control is exposed to the user in **minutes**, not seconds/millis — the
 * default (300_000ms = 5 minutes) reads far more naturally as "5 minutes" than "300 seconds", and
 * a stop is by definition a multi-minute affair, so minute granularity loses nothing meaningful.
 * [SettingsRepository] itself is unaffected: this ViewModel converts to/from millis at the
 * boundary ([onStopDurationCommit]/[millisToMinutesText]) and the repository keeps storing millis.
 */
class SettingsViewModel(
    private val settingsRepository: SettingsRepository,
    private val trackingSessionDao: TrackingSessionDao,
    private val logger: Logger = FileLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                settingsRepository.userSettings.collect { settings ->
                    _uiState.update {
                        it.copy(
                            samplingInterval = settings.samplingInterval,
                            speedUnit = settings.speedUnit,
                            distanceUnit = settings.distanceUnit,
                            stopRadiusMetersText = formatNumber(settings.stopRadiusMeters),
                            stopRadiusError = null,
                            stopDurationMinutesText = millisToMinutesText(settings.stopDurationMillis),
                            stopDurationError = null,
                            gpsPrecision = settings.gpsPrecision,
                            keepScreenOnEnabled = settings.keepScreenOnEnabled,
                            defaultExportFormat = settings.defaultExportFormat,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "SettingsViewModel", "Failed to collect user settings", e)
            }
        }
    }

    fun onSamplingIntervalSelected(interval: SamplingInterval) {
        viewModelScope.launch {
            try {
                settingsRepository.setSamplingInterval(interval)
                logger.log(LogLevel.INFO, "SettingsViewModel", "Sampling interval changed to ${interval.seconds}s")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "SettingsViewModel", "Failed to persist sampling interval", e)
            }
        }
    }

    fun onSpeedUnitSelected(unit: SpeedUnit) {
        viewModelScope.launch {
            try {
                settingsRepository.setSpeedUnit(unit)
                logger.log(LogLevel.INFO, "SettingsViewModel", "Speed unit changed to $unit")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "SettingsViewModel", "Failed to persist speed unit", e)
            }
        }
    }

    fun onDistanceUnitSelected(unit: DistanceUnit) {
        viewModelScope.launch {
            try {
                settingsRepository.setDistanceUnit(unit)
                logger.log(LogLevel.INFO, "SettingsViewModel", "Distance unit changed to $unit")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "SettingsViewModel", "Failed to persist distance unit", e)
            }
        }
    }

    /** Updates the stop-radius draft text only; nothing is persisted until [onStopRadiusCommit]. */
    fun onStopRadiusTextChanged(text: String) {
        _uiState.update { it.copy(stopRadiusMetersText = text, stopRadiusError = null) }
    }

    /** Validates and persists the current stop-radius draft text (Done action / focus loss). */
    fun onStopRadiusCommit() {
        val meters = _uiState.value.stopRadiusMetersText.toDoubleOrNull()
        if (meters == null || meters <= 0.0) {
            _uiState.update { it.copy(stopRadiusError = STOP_RADIUS_INVALID_MESSAGE) }
            return
        }
        _uiState.update { it.copy(stopRadiusError = null) }
        viewModelScope.launch {
            try {
                settingsRepository.setStopRadiusMeters(meters)
                logger.log(LogLevel.INFO, "SettingsViewModel", "Stop radius changed to ${meters}m")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "SettingsViewModel", "Failed to persist stop radius", e)
            }
        }
    }

    /** Updates the stop-duration draft text only; nothing is persisted until [onStopDurationCommit]. */
    fun onStopDurationTextChanged(text: String) {
        _uiState.update { it.copy(stopDurationMinutesText = text, stopDurationError = null) }
    }

    /** Validates and persists the current stop-duration draft text (Done action / focus loss). */
    fun onStopDurationCommit() {
        val minutes = _uiState.value.stopDurationMinutesText.toDoubleOrNull()
        if (minutes == null || minutes <= 0.0) {
            _uiState.update { it.copy(stopDurationError = STOP_DURATION_INVALID_MESSAGE) }
            return
        }
        _uiState.update { it.copy(stopDurationError = null) }
        val millis = (minutes * MILLIS_PER_MINUTE).toLong()
        viewModelScope.launch {
            try {
                settingsRepository.setStopDurationMillis(millis)
                logger.log(LogLevel.INFO, "SettingsViewModel", "Stop duration changed to ${millis}ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "SettingsViewModel", "Failed to persist stop duration", e)
            }
        }
    }

    /** UI-04: persists immediately, no "Save" button — same pattern as the other enum selectors. */
    fun onGpsPrecisionSelected(precision: GpsPrecision) {
        viewModelScope.launch {
            try {
                settingsRepository.setGpsPrecision(precision)
                logger.log(LogLevel.INFO, "SettingsViewModel", "GPS precision changed to $precision")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "SettingsViewModel", "Failed to persist GPS precision", e)
            }
        }
    }

    /** UI-05: persists immediately, no "Save" button. */
    fun onKeepScreenOnToggled(enabled: Boolean) {
        viewModelScope.launch {
            try {
                settingsRepository.setKeepScreenOnEnabled(enabled)
                logger.log(LogLevel.INFO, "SettingsViewModel", "Keep-screen-on changed to $enabled")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "SettingsViewModel", "Failed to persist keep-screen-on setting", e)
            }
        }
    }

    /** UI-07: persists immediately, no "Save" button. */
    fun onDefaultExportFormatSelected(format: ExportFormat) {
        viewModelScope.launch {
            try {
                settingsRepository.setDefaultExportFormat(format)
                logger.log(LogLevel.INFO, "SettingsViewModel", "Default export format changed to $format")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "SettingsViewModel", "Failed to persist default export format", e)
            }
        }
    }

    /**
     * RF-12/UI-08: permanently deletes every tracking session (and, via the DB's cascading
     * foreign key, every GPS point). The caller (the screen) is responsible for confirming with
     * the user BEFORE calling this — mirrors [com.mytracksapp.ui.history.HistoryViewModel.deleteSession]'s
     * existing "caller confirms first" contract; this function itself does not ask again.
     */
    fun clearHistory() {
        viewModelScope.launch {
            try {
                trackingSessionDao.deleteAll()
                logger.log(LogLevel.INFO, "SettingsViewModel", "History cleared (all sessions deleted)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "SettingsViewModel", "Failed to clear history", e)
            }
        }
    }
}
