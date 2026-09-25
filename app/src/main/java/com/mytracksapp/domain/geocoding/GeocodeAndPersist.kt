package com.mytracksapp.domain.geocoding

import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.logging.FileLogger
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger

/**
 * RF-03's single implementation of "geocode one lat/lon fix and, on success, persist it as a
 * session's `locationName` — best effort, never throws." Extracted out of
 * [FirstPointGeocodingCoordinator] (T02 of
 * `.spec/features/startup-geocoding-retry-and-log-viewer/PLAN.md`) so both the existing
 * first-point path ([FirstPointGeocodingCoordinator]) and the new startup-retry path
 * (`GeocodingRetryOnStartup`) share exactly one copy of this behavior.
 *
 * [attempt] must never throw to its caller (mirrors [ReverseGeocoder]'s and [Logger.log]'s own
 * "never throws" contracts):
 *
 *  - any exception thrown by [reverseGeocoder] is caught via [runCatching], logged at [LogLevel.WARN]
 *    (RF-06's "ReverseGeocoder exception" case), and treated exactly like "no result" — no
 *    persistence call is made;
 *  - a `null` or blank result is treated identically to a failure: no persistence call, [attempt]
 *    returns `false`;
 *  - a non-blank result is persisted via [trackingSessionDao].[TrackingSessionDao.updateLocationName];
 *    an exception from that call is caught, logged at [LogLevel.ERROR] (RF-06's "TrackingSessionDao
 *    persistence exception" case), and swallowed — never rethrown;
 *  - [attempt] returns `true` only when the persist call actually succeeds.
 */
class GeocodeAndPersist(
    private val reverseGeocoder: ReverseGeocoder,
    private val trackingSessionDao: TrackingSessionDao,
    private val logger: Logger = FileLogger,
) {

    /**
     * Attempts to resolve a place name for ([latitude], [longitude]) and, if resolved, persist it
     * as [sessionId]'s `locationName`. Returns `true` only if that persist call succeeded.
     */
    suspend fun attempt(sessionId: String, latitude: Double, longitude: Double): Boolean {
        val name = runCatching { reverseGeocoder.reverseGeocode(latitude, longitude) }
            .onFailure { e ->
                logger.log(
                    LogLevel.WARN,
                    "GeocodeAndPersist",
                    "Reverse geocoding failed for session $sessionId",
                    e,
                )
            }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: return false

        return try {
            trackingSessionDao.updateLocationName(sessionId, name)
            true
        } catch (e: Exception) {
            logger.log(
                LogLevel.ERROR,
                "GeocodeAndPersist",
                "Failed to persist geocoded location name for session $sessionId",
                e,
            )
            false
        }
    }
}
