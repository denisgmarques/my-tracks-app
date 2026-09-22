package com.mytracksapp.service

import android.content.Context
import android.content.Intent
import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.session.LocationServiceController

/**
 * Android-facing adapter bridging [LocationServiceController] — T05's deliberately Android-free
 * interface (see `SessionControllerImpl.kt`) — to the real [LocationForegroundService] (T06).
 *
 * This is the only place on the production start/stop path that touches [Context]/[Intent];
 * [com.mytracksapp.domain.session.SessionControllerImpl] itself stays plain and unit-testable
 * against a fake [LocationServiceController].
 */
class LocationForegroundServiceController(
    private val appContext: Context,
) : LocationServiceController {

    override fun start(sessionId: String, interval: SamplingInterval) {
        val intent = Intent(appContext, LocationForegroundService::class.java).apply {
            putExtra(LocationForegroundService.EXTRA_SESSION_ID, sessionId)
            putExtra(LocationForegroundService.EXTRA_INTERVAL_SECONDS, interval.seconds)
        }
        appContext.startForegroundService(intent)
    }

    override fun stop(sessionId: String) {
        // A single foreground service instance handles at most one active session at a time
        // (SessionControllerImpl never starts a second session while one is active), so stopping
        // it unconditionally is sufficient and safe even if [sessionId] doesn't match — the guard
        // against collecting for a stale/unknown session lives in LocationCollector itself.
        appContext.stopService(Intent(appContext, LocationForegroundService::class.java))
    }
}
