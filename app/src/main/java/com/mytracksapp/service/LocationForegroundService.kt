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
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.permission.LocationPermissionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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
) : LocationSampleSource {

    private var activeCallback: LocationCallback? = null

    override fun start(intervalMillis: Long, onLocation: suspend (LocationSample) -> Unit) {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                coroutineScope.launch {
                    onLocation(
                        LocationSample(
                            latitude = location.latitude,
                            longitude = location.longitude,
                            accuracy = location.accuracy,
                            timestamp = location.time,
                        ),
                    )
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
    private val serviceScope = CoroutineScope(Dispatchers.Default + serviceJob)

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

        startForeground(NOTIFICATION_ID, buildNotification())

        val database = AppDatabase.getInstance(applicationContext)
        val trackingSessionDao = database.trackingSessionDao()
        val gpsPointDao = database.gpsPointDao()
        val permissionManager = LocationPermissionManager(applicationContext)
        val fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(applicationContext)
        val sampleSource = FusedLocationSampleSource(fusedLocationProviderClient, serviceScope)

        val newCollector = LocationCollector(
            sessionId = sessionId,
            interval = interval,
            gpsPointDao = gpsPointDao,
            locationSampleSource = sampleSource,
            isSessionActive = {
                trackingSessionDao.getSessionById(sessionId).first()?.status == SessionStatus.ACTIVE
            },
            isLocationPermissionGranted = permissionManager::isBackgroundLocationGranted,
        )
        collector = newCollector

        serviceScope.launch {
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
