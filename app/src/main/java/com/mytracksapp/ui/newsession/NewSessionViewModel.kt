package com.mytracksapp.ui.newsession

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.session.SessionController
import com.mytracksapp.domain.session.SessionStartOutcome
import com.mytracksapp.permission.LocationPermissionManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Message shown when background location permission (RF-03) blocks starting a session. */
const val BACKGROUND_LOCATION_PERMISSION_REQUIRED_MESSAGE =
    "Permissão de localização em segundo plano é necessária para iniciar a coleta."

/**
 * UI state for [NewSessionScreen].
 *
 * [availableIntervals] is always the full, fixed set of [SamplingInterval] values (RF-01/UI-01)
 * — there is no code path that can narrow or extend this list at runtime.
 */
data class NewSessionUiState(
    val availableIntervals: List<SamplingInterval> = SamplingInterval.entries,
    val selectedInterval: SamplingInterval = SamplingInterval.entries.first(),
    val isStarting: Boolean = false,
    val permissionDeniedMessage: String? = null,
    val startedSessionId: String? = null,
)

/**
 * Backs [NewSessionScreen] (T04): holds the user's [SamplingInterval] selection and, on confirm,
 * verifies background location permission (RF-03) BEFORE delegating to [SessionController]
 * (RF-01) — the permission gate happens here, not as an afterthought inside the controller call.
 *
 * [sessionController] is Phase 4's `SessionController` interface (T05 implements it); this
 * ViewModel is written entirely against that contract so it does not need to change when T05
 * lands.
 */
class NewSessionViewModel(
    private val permissionManager: LocationPermissionManager,
    private val sessionController: SessionController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NewSessionUiState())
    val uiState: StateFlow<NewSessionUiState> = _uiState.asStateFlow()

    /** Updates the selected interval. [interval] is always one of the 9 valid enum members. */
    fun onIntervalSelected(interval: SamplingInterval) {
        _uiState.update { it.copy(selectedInterval = interval, permissionDeniedMessage = null) }
    }

    /**
     * Confirms the current selection and attempts to start a session.
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

        val interval = _uiState.value.selectedInterval
        _uiState.update { it.copy(isStarting = true, permissionDeniedMessage = null) }

        viewModelScope.launch {
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
        }
    }

    /** Clears the one-shot "session started" event once the caller has navigated on it. */
    fun consumeStartedSessionEvent() {
        _uiState.update { it.copy(startedSessionId = null) }
    }
}
