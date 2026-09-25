package com.mytracksapp.ui.newsession

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.session.SessionController
import com.mytracksapp.domain.session.SessionStartOutcome
import com.mytracksapp.logging.FileLogger
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import com.mytracksapp.permission.LocationPermissionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Message shown when background location permission (RF-03) blocks starting a session. */
const val BACKGROUND_LOCATION_PERMISSION_REQUIRED_MESSAGE =
    "Permissão de localização em segundo plano é necessária para iniciar a coleta."

/**
 * UI state for [NewSessionScreen].
 *
 * Follow-up phase: [configuredInterval] is now a read-only reflection of whatever
 * [SettingsRepository] currently holds — Settings owns the sampling interval exclusively, this
 * screen no longer lets the user pick one per session (that picker moved to `SettingsScreen`).
 */
data class NewSessionUiState(
    val configuredInterval: SamplingInterval = SamplingInterval.ONE_SECOND,
    val isStarting: Boolean = false,
    val permissionDeniedMessage: String? = null,
    val startedSessionId: String? = null,
)

/**
 * Backs [NewSessionScreen]: verifies background location permission (RF-03) before delegating to
 * [SessionController] (RF-01), and reads the sampling interval to use from [settingsRepository]
 * rather than from any local selection.
 *
 * [configuredInterval] shown in [uiState] is kept live (via collecting
 * [SettingsRepository.userSettings]) purely for display. The actual value passed to
 * [SessionController.startSession] on [onConfirm] is re-read fresh from [settingsRepository] at
 * that exact moment (not the possibly-stale value cached in [uiState]), so a setting changed on
 * the Settings screen takes effect on the very next session start even if this screen was already
 * composed before that change happened.
 */
class NewSessionViewModel(
    private val permissionManager: LocationPermissionManager,
    private val sessionController: SessionController,
    private val settingsRepository: SettingsRepository,
    private val logger: Logger = FileLogger,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewSessionUiState())
    val uiState: StateFlow<NewSessionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                settingsRepository.userSettings.collect { settings ->
                    _uiState.update { it.copy(configuredInterval = settings.samplingInterval) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "NewSessionViewModel", "Failed to collect user settings", e)
            }
        }
    }

    /**
     * Confirms starting a session with the currently configured interval.
     *
     * Per RF-03, permission is verified here first: if background location is not granted, no
     * call to [SessionController.startSession] is made at all, and a message is surfaced instead.
     */
    fun onConfirm() {
        if (!permissionManager.isBackgroundLocationGranted()) {
            _uiState.update {
                it.copy(permissionDeniedMessage = BACKGROUND_LOCATION_PERMISSION_REQUIRED_MESSAGE)
            }
            return
        }

        _uiState.update { it.copy(isStarting = true, permissionDeniedMessage = null) }

        viewModelScope.launch {
            try {
                val interval = settingsRepository.userSettings.first().samplingInterval
                when (val outcome = sessionController.startSession(interval)) {
                    is SessionStartOutcome.Started -> _uiState.update {
                        it.copy(isStarting = false, startedSessionId = outcome.sessionId)
                    }

                    SessionStartOutcome.PermissionDenied -> _uiState.update {
                        it.copy(
                            isStarting = false,
                            permissionDeniedMessage = BACKGROUND_LOCATION_PERMISSION_REQUIRED_MESSAGE,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.log(LogLevel.ERROR, "NewSessionViewModel", "Failed to start session", e)
            }
        }
    }

    /** Clears the one-shot "session started" event once the caller has navigated on it. */
    fun consumeStartedSessionEvent() {
        _uiState.update { it.copy(startedSessionId = null) }
    }
}
