package com.mytracksapp.ui.navigation

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.domain.export.ExportService
import com.mytracksapp.domain.session.SessionController
import com.mytracksapp.permission.LocationPermissionManager
import com.mytracksapp.ui.HistoryViewModelFactory
import com.mytracksapp.ui.NewSessionViewModelFactory
import com.mytracksapp.ui.SessionDetailViewModelFactory
import com.mytracksapp.ui.TrackingViewModelFactory
import com.mytracksapp.ui.history.HistoryListScreen
import com.mytracksapp.ui.history.HistoryViewModel
import com.mytracksapp.ui.history.SessionDetailScreen
import com.mytracksapp.ui.history.SessionDetailViewModel
import com.mytracksapp.ui.newsession.NewSessionScreen
import com.mytracksapp.ui.newsession.NewSessionViewModel
import com.mytracksapp.ui.tracking.TrackingScreen
import com.mytracksapp.ui.tracking.TrackingViewModel
import java.io.File

/**
 * Follow-up "wire it all together" task: PLAN.md never produced a task for a launcher
 * Activity/navigation graph, so every screen so far only exists as an isolated, independently
 * testable Composable. This is the single place all 4 screens are stitched into one
 * back-stack, with real, Android-backed dependencies (constructed once in [com.mytracksapp.MainActivity])
 * flowing down into each screen's [androidx.lifecycle.ViewModel] via the plain
 * `ViewModelProvider.Factory` classes in `com.mytracksapp.ui.ViewModelFactories.kt` — this
 * codebase uses no DI framework anywhere, so navigation-graph-level manual wiring is the
 * consistent choice here too.
 */
object Routes {
    const val NEW_SESSION = "new_session"
    const val HISTORY = "history"
    const val TRACKING = "tracking/{sessionId}"
    const val SESSION_DETAIL = "session_detail/{sessionId}"

    fun tracking(sessionId: String): String = "tracking/$sessionId"
    fun sessionDetail(sessionId: String): String = "session_detail/$sessionId"
}

/** The two top-level destinations reachable from the persistent bottom bar. */
private data class TopLevelDestination(val route: String, val label: String, val testTag: String)

private val topLevelDestinations = listOf(
    TopLevelDestination(Routes.NEW_SESSION, "Nova sessão", AppNavigationTestTags.NEW_SESSION_TAB),
    TopLevelDestination(Routes.HISTORY, "Histórico", AppNavigationTestTags.HISTORY_TAB),
)

/** Stable test tags for the bottom navigation bar built in [MyTracksApp]. */
object AppNavigationTestTags {
    const val NEW_SESSION_TAB = "app_nav_new_session_tab"
    const val HISTORY_TAB = "app_nav_history_tab"
}

/**
 * Root composable: builds the [NavHostController], the persistent bottom navigation bar, and the
 * full navigation graph. All real dependencies are constructed once by the caller (production:
 * [com.mytracksapp.MainActivity]; tests: whatever host they use) and threaded down from here.
 */
@Composable
fun MyTracksApp(
    trackingSessionDao: TrackingSessionDao,
    gpsPointDao: GpsPointDao,
    sessionController: SessionController,
    permissionManager: LocationPermissionManager,
    exportService: ExportService,
) {
    val navController = rememberNavController()

    Scaffold(
        bottomBar = {
            val backStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = backStackEntry?.destination
            Surface {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    topLevelDestinations.forEach { destination ->
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        Button(
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = if (selected) {
                                ButtonDefaults.buttonColors()
                            } else {
                                ButtonDefaults.outlinedButtonColors()
                            },
                            modifier = Modifier.testTag(destination.testTag),
                        ) {
                            Text(destination.label)
                        }
                    }
                }
            }
        },
    ) { paddingValues ->
        NavHost(
            navController = navController,
            startDestination = Routes.NEW_SESSION,
            modifier = Modifier.padding(paddingValues),
        ) {
            composable(Routes.NEW_SESSION) {
                NewSessionRoute(
                    permissionManager = permissionManager,
                    sessionController = sessionController,
                    onSessionStarted = { sessionId ->
                        navController.navigate(Routes.tracking(sessionId))
                    },
                )
            }

            composable(
                route = Routes.TRACKING,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val sessionId = requireNotNull(backStackEntry.arguments?.getString("sessionId"))
                TrackingRoute(
                    sessionId = sessionId,
                    gpsPointDao = gpsPointDao,
                    sessionController = sessionController,
                    onSessionFinished = {
                        navController.navigate(Routes.HISTORY) {
                            popUpTo(Routes.NEW_SESSION)
                        }
                    },
                )
            }

            composable(Routes.HISTORY) {
                HistoryRoute(
                    trackingSessionDao = trackingSessionDao,
                    onSessionClick = { sessionId -> navController.navigate(Routes.sessionDetail(sessionId)) },
                )
            }

            composable(
                route = Routes.SESSION_DETAIL,
                arguments = listOf(navArgument("sessionId") { type = NavType.StringType }),
            ) { backStackEntry ->
                val sessionId = requireNotNull(backStackEntry.arguments?.getString("sessionId"))
                SessionDetailRoute(
                    sessionId = sessionId,
                    trackingSessionDao = trackingSessionDao,
                    gpsPointDao = gpsPointDao,
                    exportService = exportService,
                )
            }
        }
    }
}

/**
 * Wraps [NewSessionScreen] with the real runtime-permission request flow.
 *
 * On API 29 (this SPEC's minSdk, and the connected physical test device's exact OS version),
 * `ACCESS_FINE_LOCATION` and `ACCESS_BACKGROUND_LOCATION` can both be requested together in a
 * single system dialog. Starting API 30, the platform requires background location to be
 * requested as a separate step (after foreground location is already granted), typically via a
 * Settings redirect — building that full two-step flow is out of scope for this prototype (see
 * task notes). To avoid crashing or silently no-oping on 30+, only `ACCESS_FINE_LOCATION` is
 * requested there; if background location is still missing afterward,
 * [NewSessionViewModel] already surfaces [com.mytracksapp.ui.newsession.BACKGROUND_LOCATION_PERMISSION_REQUIRED_MESSAGE]
 * (RF-03), so the user is still informed rather than left with a silent failure.
 */
@Composable
private fun NewSessionRoute(
    permissionManager: LocationPermissionManager,
    sessionController: SessionController,
    onSessionStarted: (String) -> Unit,
) {
    val viewModel: NewSessionViewModel = viewModel(
        factory = NewSessionViewModelFactory(permissionManager, sessionController),
    )
    val uiState by viewModel.uiState.collectAsState()

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* Results are ignored here by design: NewSessionViewModel re-checks the live permission
           state via LocationPermissionManager the next time onConfirm() runs, so there is a
           single source of truth for "is permission granted" rather than duplicating it from
           this callback's result map. */ }

    fun requestLocationPermissions() {
        val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        permissionLauncher.launch(permissionsToRequest)
    }

    // Triggered by the permission-denied state (RF-03's BACKGROUND_LOCATION_PERMISSION_REQUIRED_MESSAGE,
    // surfaced by NewSessionViewModel when the user taps "Iniciar sessão" without the required
    // permission) rather than proactively on first screen load: this keeps the real system
    // permission dialog scoped to an explicit user action instead of firing unconditionally the
    // instant this screen is composed, which would otherwise race arbitrary hosts of this screen
    // (e.g. instrumented smoke tests that never intend to interact with permissions at all) with
    // an unrelated system UI window appearing mid-composition.
    LaunchedEffect(uiState.permissionDeniedMessage) {
        if (uiState.permissionDeniedMessage != null) requestLocationPermissions()
    }

    LaunchedEffect(uiState.startedSessionId) {
        val startedSessionId = uiState.startedSessionId
        if (startedSessionId != null) {
            onSessionStarted(startedSessionId)
            viewModel.consumeStartedSessionEvent()
        }
    }

    NewSessionScreen(viewModel = viewModel)
}

@Composable
private fun TrackingRoute(
    sessionId: String,
    gpsPointDao: GpsPointDao,
    sessionController: SessionController,
    onSessionFinished: () -> Unit,
) {
    val viewModel: TrackingViewModel = viewModel(
        factory = TrackingViewModelFactory(sessionId, gpsPointDao),
    )

    TrackingScreen(
        viewModel = viewModel,
        onFinishSession = { finishedSessionId ->
            sessionController.stopSession(finishedSessionId)
            onSessionFinished()
        },
    )
}

@Composable
private fun HistoryRoute(
    trackingSessionDao: TrackingSessionDao,
    onSessionClick: (String) -> Unit,
) {
    val viewModel: HistoryViewModel = viewModel(
        factory = HistoryViewModelFactory(trackingSessionDao),
    )

    HistoryListScreen(viewModel = viewModel, onSessionClick = onSessionClick)
}

@Composable
private fun SessionDetailRoute(
    sessionId: String,
    trackingSessionDao: TrackingSessionDao,
    gpsPointDao: GpsPointDao,
    exportService: ExportService,
) {
    val viewModel: SessionDetailViewModel = viewModel(
        factory = SessionDetailViewModelFactory(sessionId, trackingSessionDao, gpsPointDao),
    )
    val context = LocalContext.current

    SessionDetailScreen(
        viewModel = viewModel,
        onExport = { exportedSessionId, format ->
            val exportedFile = exportService.export(exportedSessionId, format)
            exportedFile.writeTo(File(context.filesDir, "exports"))
        },
    )
}
