package com.mytracksapp.domain.session

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.logging.FileLogger
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import kotlinx.coroutines.flow.first

/**
 * Cold-start orphaned-session recovery (orphaned-session-recovery feature, T02): finds every
 * `TrackingSessionEntity` still `ACTIVE` (necessarily orphaned — the foreground collection service
 * cannot be running at this point in the process lifecycle, since a fresh process just started) and
 * finalizes each one to `FINISHED` using [finalizeSession] (T01) — the exact same
 * `SegmentClassifier`/`StatsEngine` math [SessionControllerImpl.stopSession] uses (RF-03), so
 * recovery never becomes a second, divergent implementation.
 *
 * Android-free by design (no `Context`), same style as [SessionControllerImpl]: constructed with
 * only [TrackingSessionDao]/[GpsPointDao], so it is testable with plain in-memory fakes.
 *
 * Deliberately read-only with respect to [GpsPointDao] (RF-04): points are read via
 * [GpsPointDao.getPointsForSession] purely to feed [finalizeSession]'s pure computation —
 * `insert`/`insertAll` are never called, and no point row is ever touched.
 *
 * Unlike [SessionControllerImpl.stopSession] (which falls back to `clock()` — "now" — when a
 * session has zero points), this class passes the session's own `startTimestamp` as the
 * `endTimestampFallback` (RF-02's intentional divergence): an orphaned session's real end time is
 * unknowable (the process that would have recorded it is gone), so a fabricated "now" would be
 * misleading, while `startTimestamp` makes the zero-duration/zero-metrics result explicit and
 * deterministic.
 */
class OrphanedSessionRecovery(
    private val trackingSessionDao: TrackingSessionDao,
    private val gpsPointDao: GpsPointDao,
    private val logger: Logger = FileLogger,
) {

    /**
     * Finalizes every currently `ACTIVE` session and returns how many were recovered (`0` if none
     * were orphaned).
     *
     * Each session's finalization is wrapped in its own try/catch: a single malformed/corrupt row
     * throwing during classification or persistence must not abort recovery of the *other* orphans
     * in the same batch, and must never propagate to crash the caller's cold-start path (RF-05,
     * RNF-01's spirit) — a skipped session simply stays `ACTIVE` and is retried on the next cold
     * start.
     */
    suspend fun recover(): Int {
        val orphaned = trackingSessionDao.getSessionsByStatus(SessionStatus.ACTIVE).first()

        var recoveredCount = 0
        for (session in orphaned) {
            try {
                val points = gpsPointDao.getPointsForSession(session.id).first()
                val finalized = finalizeSession(
                    existing = session,
                    points = points,
                    endTimestampFallback = session.startTimestamp,
                )
                trackingSessionDao.update(finalized)
                recoveredCount++
            } catch (e: Exception) {
                // Catch-and-continue: isolate a bad row so the rest of the batch still finishes
                // (see class doc) — never rethrown, and never crashes cold start.
                logger.log(
                    LogLevel.ERROR,
                    "OrphanedSessionRecovery",
                    "Failed to finalize orphaned session ${session.id}",
                    e,
                )
            }
        }
        return recoveredCount
    }
}
