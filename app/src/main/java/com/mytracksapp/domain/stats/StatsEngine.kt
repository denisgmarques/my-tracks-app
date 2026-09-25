package com.mytracksapp.domain.stats

import com.mytracksapp.data.local.entity.GpsPointEntity

/**
 * Speed and elapsed-time statistics (RF-04, RF-05) over a session's point stream.
 *
 * Every function here is pure and stateless: it recomputes its result from the full (or
 * relevant slice of the) point list passed in, rather than incrementally maintaining internal
 * state. RF-05's "ambos os valores são recalculados a cada novo ponto coletado" is satisfied by
 * callers (T08's `TrackingViewModel`) simply re-invoking these functions with the up-to-date
 * point list every time Room's `Flow<List<GpsPointEntity>>` emits — see PLAN.md T08.
 *
 * All functions assume [points] is ordered ascending by `timestamp` — true of
 * `GpsPointDao.getPointsForSession` ("ORDER BY timestamp ASC").
 */
object StatsEngine {

    /**
     * Instant speed (m/s) between two consecutive points (RF-05): geodesic distance divided by
     * the elapsed time between them.
     *
     * Returns `0.0` when [current] is not strictly after [previous] (Δt <= 0) — there is no
     * physically meaningful instantaneous speed for a zero or negative time delta, and this
     * avoids a division by zero.
     */
    fun instantSpeedMetersPerSecond(previous: GpsPointEntity, current: GpsPointEntity): Double {
        val deltaSeconds = (current.timestamp - previous.timestamp) / 1000.0
        if (deltaSeconds <= 0.0) return 0.0

        val distanceMeters = DistanceCalculator.distanceMeters(
            previous.latitude, previous.longitude,
            current.latitude, current.longitude,
        )
        return distanceMeters / deltaSeconds
    }

    /** Instant speed (m/s) for every consecutive pair in [points], in point order. */
    fun instantSpeeds(points: List<GpsPointEntity>): List<Double> {
        if (points.size < 2) return emptyList()
        return (1 until points.size).map { index ->
            instantSpeedMetersPerSecond(points[index - 1], points[index])
        }
    }

    /** Total geodesic distance (meters) across all consecutive point pairs in [points]. */
    fun totalDistanceMeters(points: List<GpsPointEntity>): Double {
        if (points.size < 2) return 0.0
        var total = 0.0
        for (index in 1 until points.size) {
            total += DistanceCalculator.distanceMeters(
                points[index - 1].latitude, points[index - 1].longitude,
                points[index].latitude, points[index].longitude,
            )
        }
        return total
    }

    /**
     * Total elapsed time (ms) of the session (RF-04): last point's timestamp minus the first
     * point's timestamp. `0` for sessions with 0 or 1 points.
     */
    fun elapsedTimeMillis(points: List<GpsPointEntity>): Long {
        if (points.isEmpty()) return 0L
        return points.last().timestamp - points.first().timestamp
    }

    /**
     * Average speed (m/s) while actually moving: [distanceMeters] divided by [movingTimeMillis]
     * (the [SegmentClassifier]-derived moving time, not the session's total elapsed time).
     *
     * Deliberately excludes stopped time — a session's total-elapsed-time average is dragged down
     * by every second spent stopped (traffic lights, parking, catching your breath), which made a
     * car trip's average appear slower than a bike ride's on days with a lot of stop-and-go, and
     * made the live value visibly drop while genuinely stationary. `0.0` when [movingTimeMillis]
     * is zero or negative (e.g. a session that never moved) to avoid a division by zero.
     */
    fun averageSpeedMetersPerSecond(distanceMeters: Double, movingTimeMillis: Long): Double {
        val movingSeconds = movingTimeMillis / 1000.0
        if (movingSeconds <= 0.0) return 0.0
        return distanceMeters / movingSeconds
    }
}
