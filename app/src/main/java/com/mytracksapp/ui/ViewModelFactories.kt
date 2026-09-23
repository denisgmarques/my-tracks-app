package com.mytracksapp.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.session.SessionController
import com.mytracksapp.permission.LocationPermissionManager
import com.mytracksapp.ui.history.HistoryViewModel
import com.mytracksapp.ui.history.SessionDetailViewModel
import com.mytracksapp.ui.newsession.NewSessionViewModel
import com.mytracksapp.ui.settings.SettingsViewModel
import com.mytracksapp.ui.tracking.TrackingViewModel

/**
 * Follow-up "wire it all together" task: this codebase uses no DI framework (no Hilt/Koin/etc.)
 * anywhere, so every ViewModel that needs constructor arguments gets its own plain
 * [ViewModelProvider.Factory] here, consistent with that existing style. [MainActivity]/the
 * navigation graph construct the real, Android-backed dependencies once and pass them into these
 * factories.
 */
class NewSessionViewModelFactory(
    private val permissionManager: LocationPermissionManager,
    private val sessionController: SessionController,
    private val settingsRepository: SettingsRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NewSessionViewModel::class.java)) {
            "Unknown ViewModel class $modelClass"
        }
        return NewSessionViewModel(permissionManager, sessionController, settingsRepository) as T
    }
}

/**
 * T12/T13: [SettingsViewModel] also depends on [TrackingSessionDao] now (for `clearHistory()`,
 * RF-12), alongside its existing [SettingsRepository] dependency.
 */
class SettingsViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val trackingSessionDao: TrackingSessionDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            "Unknown ViewModel class $modelClass"
        }
        return SettingsViewModel(settingsRepository, trackingSessionDao) as T
    }
}

/** [sessionId] is fixed per navigation destination (`tracking/{sessionId}`), so it is baked in here. */
class TrackingViewModelFactory(
    private val sessionId: String,
    private val gpsPointDao: GpsPointDao,
    private val settingsRepository: SettingsRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(TrackingViewModel::class.java)) {
            "Unknown ViewModel class $modelClass"
        }
        return TrackingViewModel(sessionId, gpsPointDao, settingsRepository) as T
    }
}

/** T11/T13: [HistoryViewModel] also depends on [SettingsRepository] now (unit-aware distance display, RF-13). */
class HistoryViewModelFactory(
    private val trackingSessionDao: TrackingSessionDao,
    private val settingsRepository: SettingsRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
            "Unknown ViewModel class $modelClass"
        }
        return HistoryViewModel(trackingSessionDao, settingsRepository) as T
    }
}

/** [sessionId] is fixed per navigation destination (`session_detail/{sessionId}`), baked in here. */
class SessionDetailViewModelFactory(
    private val sessionId: String,
    private val trackingSessionDao: TrackingSessionDao,
    private val gpsPointDao: GpsPointDao,
    private val settingsRepository: SettingsRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SessionDetailViewModel::class.java)) {
            "Unknown ViewModel class $modelClass"
        }
        return SessionDetailViewModel(sessionId, trackingSessionDao, gpsPointDao, settingsRepository) as T
    }
}
