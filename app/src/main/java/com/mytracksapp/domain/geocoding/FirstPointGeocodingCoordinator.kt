package com.mytracksapp.domain.geocoding

import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.logging.FileLogger
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Orchestrates RF-01/RF-02/RF-03's "geocode the first point, once, best-effort" behavior.
 *
 * [onFirstPointRecorded] is a plain, non-suspend function so [com.mytracksapp.service.LocationCollector]
 * (T04) can call it directly from its own suspend point-handling code without needing to know
 * anything about geocoding, coroutines beyond "fire and forget", or persistence — it just calls
 * this once per session, and this class takes it from there entirely on [coroutineScope]:
 *
 *  - the actual geocoding attempt ([ReverseGeocoder.reverseGeocode]) runs asynchronously on
 *    [coroutineScope], so [onFirstPointRecorded] returns immediately without waiting for it
 *    (RNF-01: never blocks point collection or session start/stop);
 *  - any exception thrown by [reverseGeocoder] is caught via [runCatching] and never propagated —
 *    a network failure or missing geocoder service must never crash the caller (RF-02);
 *  - a `null` or blank result is treated exactly the same as a failure: no write happens, and the
 *    session's `locationName` simply stays at its persisted `null` default (RF-02: "o nome de
 *    local da sessão permanece vazio");
 *  - [trackingSessionDao].[TrackingSessionDao.updateLocationName] is only ever invoked when a
 *    real, non-blank name was resolved, and at most once per [onFirstPointRecorded] call — since
 *    [com.mytracksapp.service.LocationCollector] guarantees at most one call per session (T04),
 *    and nothing here or anywhere else re-invokes this for a session that already got its one
 *    attempt, RF-03's "nenhuma nova tentativa automática" holds structurally, with no extra
 *    tracking state needed in this class.
 */
class FirstPointGeocodingCoordinator(
    private val reverseGeocoder: ReverseGeocoder,
    private val trackingSessionDao: TrackingSessionDao,
    private val coroutineScope: CoroutineScope,
    private val logger: Logger = FileLogger,
) {

    /**
     * Fire-and-forget: launches the single reverse-geocoding attempt for [sessionId] on
     * [coroutineScope] and returns immediately, before that attempt (success, failure, or
     * `null`/blank result) has necessarily completed.
     */
    fun onFirstPointRecorded(sessionId: String, latitude: Double, longitude: Double) {
        coroutineScope.launch {
            val name = runCatching { reverseGeocoder.reverseGeocode(latitude, longitude) }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            if (name != null) {
                try {
                    trackingSessionDao.updateLocationName(sessionId, name)
                } catch (e: Exception) {
                    logger.log(
                        LogLevel.ERROR,
                        "FirstPointGeocodingCoordinator",
                        "Failed to persist geocoded location name",
                        e,
                    )
                }
            }
        }
    }
}
