package com.mytracksapp.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mytracksapp.data.local.AppDatabase
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.settings.SettingsRepository
import com.mytracksapp.domain.geocoding.FirstPointGeocodingCoordinator
import com.mytracksapp.domain.model.GpsPrecision
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.logging.FileLogger
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import com.mytracksapp.permission.LocationPermissionManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * T07 (RF-05) — pure mapping from the user-configurable [GpsPrecision] preference to the
 * corresponding Play Services `Priority` constant used to build a session's `LocationRequest`.
 */
fun GpsPrecision.toLocationRequestPriority(): Int = when (this) {
    GpsPrecision.HIGH_ACCURACY -> Priority.PRIORITY_HIGH_ACCURACY
    GpsPrecision.BALANCED -> Priority.PRIORITY_BALANCED_POWER_ACCURACY
}

/**
 * Production [LocationSampleSource]: a thin adapter around a real [FusedLocationProviderClient].
 * All collection/guard/drift logic lives in [LocationCollector] (the actual unit under test in
 * `LocationCollectorTest`/`LocationForegroundServiceStartGuardTest`) — this class does nothing but
 * translate Play Services' callback shape into [LocationSample]/[LocationSampleSource].
 *
 * `ACCESS_FINE_LOCATION`/`ACCESS_BACKGROUND_LOCATION` are guaranteed granted by the time this is
 * ever asked to [start] — [LocationCollector.start] checks
 * [LocationPermissionManager.isBackgroundLocationGranted] first (RF-03) — hence the suppression.
 */
@SuppressLint("MissingPermission")
class FusedLocationSampleSource(
    private val fusedLocationProviderClient: FusedLocationProviderClient,
    private val coroutineScope: CoroutineScope,
    private val priority: Int = Priority.PRIORITY_HIGH_ACCURACY,
    private val logger: Logger = FileLogger,
) : LocationSampleSource {

    private var activeCallback: LocationCallback? = null

    override fun start(intervalMillis: Long, onLocation: suspend (LocationSample) -> Unit) {
        val request = LocationRequest.Builder(priority, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                val sample = LocationSample(
                    latitude = location.latitude,
                    longitude = location.longitude,
                    accuracy = location.accuracy,
                    timestamp = location.time,
                )
                coroutineScope.launch {
                    deliverSample(sample, onLocation, logger)
                }
            }
        }
        activeCallback = callback
        fusedLocationProviderClient.requestLocationUpdates(request, callback, Looper.getMainLooper())
    }

    override fun stop() {
        activeCallback?.let { fusedLocationProviderClient.removeLocationUpdates(it) }
        activeCallback = null
    }
}

/**
 * T05 (RF-05) — delivers a single [LocationSample] to [onLocation], containing any failure inside
 * this coroutine's own body via try/catch. Because this function runs entirely inside the
 * `coroutineScope.launch { ... }` block started by [FusedLocationSampleSource.onLocationResult],
 * a caught exception here is fully contained and never reaches `serviceScope`'s
 * `CoroutineExceptionHandler` ([serviceScopeExceptionHandler], T04) — it is never rethrown, so it
 * never propagates to the launching scope and never cancels it.
 */
internal suspend fun deliverSample(
    sample: LocationSample,
    onLocation: suspend (LocationSample) -> Unit,
    logger: Logger,
) {
    try {
        onLocation(sample)
    } catch (e: Exception) {
        logger.log(LogLevel.ERROR, "FusedLocationSampleSource", "Failed to deliver location sample", e)
    }
}

/**
 * T04 (RF-06) — builds the [CoroutineExceptionHandler] installed on [LocationForegroundService]'s
 * `serviceScope`. Logs any coroutine exception that escapes uncaught out of a coroutine launched
 * on that scope, then lets [CoroutineExceptionHandler]'s normal (non-rethrowing) contract stand —
 * it never rethrows itself.
 *
 * RF-05's [deliverSample] try/catch sits fully inside its own launched coroutine's body, so a
 * contained exception there is never uncaught and never reaches this handler (no double-logging
 * between T04 and T05 — see PLAN.md's T04 Risk note).
 */
internal fun serviceScopeExceptionHandler(logger: Logger = FileLogger): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, throwable ->
        logger.log(LogLevel.ERROR, "LocationForegroundService", "Uncaught coroutine exception on serviceScope", throwable)
    }

/**
 * T06 — foreground service that performs the actual periodic GPS point collection for an active
 * tracking session (RF-02, RF-03, RNF-01, RNF-03), compatible with Android 10+ (API 29+)
 * background location restrictions via `foregroundServiceType="location"` (declared in the
 * manifest).
 *
 * Started/stopped exclusively through [LocationForegroundServiceController] (the
 * [com.mytracksapp.domain.session.LocationServiceController] adapter [com.mytracksapp.domain.session.SessionControllerImpl]
 * drives), with the session id and configured [SamplingInterval] passed as [Intent] extras. All
 * actual collection/guard/drift logic is delegated to [LocationCollector] — this class only wires
 * that logic to the real [FusedLocationProviderClient] and to the `Service` lifecycle
 * (`startForeground`, `onDestroy`).
 */
class LocationForegroundService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob + serviceScopeExceptionHandler())

    private var collector: LocationCollector? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID)
        val intervalSeconds = intent?.getIntExtra(EXTRA_INTERVAL_SECONDS, -1) ?: -1
        val interval = SamplingInterval.entries.firstOrNull { it.seconds == intervalSeconds }

        if (sessionId == null || interval == null) {
            // Defensive guard: an invocation without a valid session id/interval can never
            // legitimately collect anything (RF-02) — refuse immediately, write nothing.
            stopSelf()
            return START_NOT_STICKY
        }

        // RNF-02/T07: startForeground stays the first synchronous call, unconditionally, before any
        // settings read — never delayed behind a DataStore/coroutine hop (ANR-adjacent risk).
        startForeground(NOTIFICATION_ID, buildNotification())

        val database = AppDatabase.getInstance(applicationContext)
        val trackingSessionDao = database.trackingSessionDao()
        val gpsPointDao = database.gpsPointDao()
        val permissionManager = LocationPermissionManager(applicationContext)
        val settingsRepository = SettingsRepository(applicationContext)

        serviceScope.launch {
            // RNF-02: gpsPrecision is read exactly once here, per session start, before any
            // LocationRequest is built for this session — a later in-session preference change
            // never mutates an already-running session's LocationRequest.
            val gpsPrecision = settingsRepository.userSettings.first().gpsPrecision
            val fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(applicationContext)
            val sampleSource = FusedLocationSampleSource(
                fusedLocationProviderClient = fusedLocationProviderClient,
                coroutineScope = serviceScope,
                priority = gpsPrecision.toLocationRequestPriority(),
            )

            val reverseGeocoder = AndroidReverseGeocoder(applicationContext)
            val geocodingCoordinator = FirstPointGeocodingCoordinator(
                reverseGeocoder = reverseGeocoder,
                trackingSessionDao = trackingSessionDao,
                coroutineScope = serviceScope,
            )

            val newCollector = LocationCollector(
                sessionId = sessionId,
                interval = interval,
                gpsPointDao = gpsPointDao,
                locationSampleSource = sampleSource,
                isSessionActive = {
                    trackingSessionDao.getSessionById(sessionId).first()?.status == SessionStatus.ACTIVE
                },
                isLocationPermissionGranted = permissionManager::isBackgroundLocationGranted,
                onFirstPointRecorded = { latitude, longitude ->
                    geocodingCoordinator.onFirstPointRecorded(sessionId, latitude, longitude)
                },
            )
            collector = newCollector

            val started = newCollector.start()
            if (!started) {
                // RF-03: no active session and/or no background location permission — refuse to
                // collect and tear the service down without ever requesting a single location fix.
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        collector?.stop()
        collector = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Rastreamento GPS",
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Rastreamento GPS ativo")
            .setContentText("Coletando pontos de localização em segundo plano")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val EXTRA_SESSION_ID = "com.mytracksapp.service.EXTRA_SESSION_ID"
        const val EXTRA_INTERVAL_SECONDS = "com.mytracksapp.service.EXTRA_INTERVAL_SECONDS"
        private const val NOTIFICATION_CHANNEL_ID = "location_tracking_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
