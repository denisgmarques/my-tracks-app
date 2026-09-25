package com.mytracksapp.domain.geocoding

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.logging.FileLogger
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Cold-start retry of RF-01's reverse-geocoding attempt for `FINISHED` sessions whose
 * `locationName` is still `null` (e.g. because the first-point geocode attempt made by
 * [FirstPointGeocodingCoordinator] failed/returned nothing), within a 24h window of the session's
 * `startTimestamp` (RF-04). Mirrors [com.mytracksapp.domain.session.OrphanedSessionRecovery]'s
 * style/testability: Android-free, constructor-injected DAOs, catch-and-continue per-session
 * isolation so a single bad row never aborts the batch or crashes cold start.
 *
 * `T05` of `.spec/features/startup-geocoding-retry-and-log-viewer/PLAN.md`.
 */
class GeocodingRetryOnStartup(
    private val trackingSessionDao: TrackingSessionDao,
    private val gpsPointDao: GpsPointDao,
    private val geocodeAndPersist: GeocodeAndPersist,
    private val logger: Logger = FileLogger,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /**
     * Retries geocoding for every eligible session and returns how many were successfully
     * persisted (`0` if none were eligible or none succeeded).
     *
     * Each session is processed in its own try/catch: a single session whose [GpsPointDao] lookup
     * (or anything else in this loop) throws is logged and skipped, never aborting the rest of the
     * batch and never propagating to the caller (RF-06).
     */
    suspend fun retry(): Int {
        val eligible = trackingSessionDao.getFinishedSessionsWithoutLocationNameSince(
            SessionStatus.FINISHED,
            now() - WINDOW_MILLIS,
        )

        var successCount = 0
        for (session in eligible) {
            try {
                // GpsPointDao.getPointsForSession is ORDER BY timestamp ASC (RF-02), so the list's
                // first element is already the smallest-timestamp point.
                val points = gpsPointDao.getPointsForSession(session.id).first()
                val firstPoint = points.firstOrNull()
                if (firstPoint == null) {
                    logger.log(
                        LogLevel.WARN,
                        TAG,
                        "No GPS points found for session ${session.id}",
                    )
                    continue
                }

                val persisted = geocodeAndPersist.attempt(
                    session.id,
                    firstPoint.latitude,
                    firstPoint.longitude,
                )
                if (persisted) {
                    successCount++
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Catch-and-continue: isolate a bad row so the rest of the batch still finishes
                // (see class doc) — never rethrown, and never crashes cold start.
                logger.log(
                    LogLevel.ERROR,
                    TAG,
                    "Failed to retry geocoding for session ${session.id}",
                    e,
                )
            }
        }
        return successCount
    }

    private companion object {
        const val TAG = "GeocodingRetryOnStartup"
        const val WINDOW_MILLIS = 86_400_000L
    }
}
