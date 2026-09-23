package com.mytracksapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import com.mytracksapp.data.local.AppDatabase
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.export.ExportService
import com.mytracksapp.domain.session.SessionControllerImpl
import com.mytracksapp.permission.LocationPermissionManager
import com.mytracksapp.service.LocationForegroundServiceController
import com.mytracksapp.ui.navigation.MyTracksApp

/**
 * Follow-up "wire it all together" task: the original PLAN.md phases (T01-T13) each shipped a
 * self-contained, independently testable piece (data layer, permission checks, session
 * orchestration, foreground service, stats engine, history/export/tracking UI, ...), but no task
 * ever assembled them into an actual launchable app — there was no launcher `Activity` and no
 * runtime permission request flow, so the app could not be opened on a device.
 *
 * This is that assembly point: a single-activity host that constructs the real, Android-backed
 * production dependencies exactly once and hands them to [MyTracksApp] (the Compose navigation
 * graph in `com.mytracksapp.ui.navigation`), which wires them into each screen's `ViewModel` via
 * the plain `ViewModelProvider.Factory` classes in `com.mytracksapp.ui.ViewModelFactories.kt` —
 * this project uses no DI framework anywhere, so this manual composition-root style stays
 * consistent with the rest of the codebase.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val database = AppDatabase.getInstance(applicationContext)
        val trackingSessionDao = database.trackingSessionDao()
        val gpsPointDao = database.gpsPointDao()

        val permissionManager = LocationPermissionManager(applicationContext)
        val locationServiceController = LocationForegroundServiceController(applicationContext)
        val sessionController = SessionControllerImpl(
            trackingSessionDao = trackingSessionDao,
            gpsPointDao = gpsPointDao,
            locationServiceController = locationServiceController,
            isBackgroundLocationGranted = permissionManager::isBackgroundLocationGranted,
        )
        val exportService = ExportService(trackingSessionDao, gpsPointDao)
        val settingsRepository = SettingsRepository(applicationContext)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MyTracksApp(
                        trackingSessionDao = trackingSessionDao,
                        gpsPointDao = gpsPointDao,
                        sessionController = sessionController,
                        permissionManager = permissionManager,
                        exportService = exportService,
                        settingsRepository = settingsRepository,
                    )
                }
            }
        }
    }
}
