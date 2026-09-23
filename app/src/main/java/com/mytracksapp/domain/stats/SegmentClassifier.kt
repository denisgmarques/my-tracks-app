package com.mytracksapp.domain.stats

import com.mytracksapp.data.local.entity.GpsPointEntity

/** Classification of a time interval within a session, per RF-06. */
enum class SegmentStatus { STOPPED, MOVING }

/**
 * A classified time interval between two consecutive GPS points.
 *
 * Classification happens per INTERVAL (the gap between point `k` and point `k+1`), not per
 * point: this is what makes `stoppedTimeMillis + movingTimeMillis` trivially and always equal to
 * the session's total elapsed time — the intervals exactly partition
 * `[points.first().timestamp, points.last().timestamp)` with no gaps and no overlaps.
 */
data class ClassifiedInterval(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val status: SegmentStatus,
    /**
     * Latitude of the stop-detection window's anchor point (its first point), when [status] is
     * [SegmentStatus.STOPPED]; `null` for [SegmentStatus.MOVING] intervals, which have no single
     * representative location. Populated so a future caller (Phase C's map UI) can place a "stop"
     * pin without re-deriving the classification's stay-point windows itself. Multiple consecutive
     * STOPPED intervals belonging to the same contiguous stop share the same anchor coordinates —
     * see [SegmentClassificationResult.stopLocations] for one de-duplicated point per distinct stop.
     */
    val anchorLatitude: Double? = null,
    /** Longitude counterpart of [anchorLatitude] — see its doc for the full explanation. */
    val anchorLongitude: Double? = null,
) {
    val durationMillis: Long get() = endTimestamp - startTimestamp
}

/** One representative point for a contiguous run of [SegmentStatus.STOPPED] intervals. */
data class StopLocation(
    val startTimestamp: Long,
    val endTimestamp: Long,
    val latitude: Double,
    val longitude: Double,
) {
    val durationMillis: Long get() = endTimestamp - startTimestamp
}

/** Result of [SegmentClassifier.classify]: the full, gapless partition of a session's time. */
data class SegmentClassificationResult(val intervals: List<ClassifiedInterval>) {

    val stoppedTimeMillis: Long
        get() = intervals.filter { it.status == SegmentStatus.STOPPED }.sumOf { it.durationMillis }

    val movingTimeMillis: Long
        get() = intervals.filter { it.status == SegmentStatus.MOVING }.sumOf { it.durationMillis }

    /** Always equal to `stoppedTimeMillis + movingTimeMillis` — RF-06's invariant. */
    val totalTimeMillis: Long
        get() = intervals.sumOf { it.durationMillis }

    /**
     * One [StopLocation] per contiguous run of [SegmentStatus.STOPPED] intervals — i.e. one pin
     * per distinct stop, not one per interval (a single stop typically spans many consecutive
     * STOPPED intervals). The run's first interval's anchor point is used as the representative
     * coordinate for the whole run.
     *
     * This lets a future map-UI caller (Phase C) draw one marker per place the device stopped,
     * without re-running or re-deriving [classify]'s stay-point windows itself.
     */
    val stopLocations: List<StopLocation>
        get() {
            val result = mutableListOf<StopLocation>()
            var index = 0
            while (index < intervals.size) {
                val interval = intervals[index]
                if (interval.status == SegmentStatus.STOPPED) {
                    var endIndex = index
                    while (endIndex + 1 < intervals.size && intervals[endIndex + 1].status == SegmentStatus.STOPPED) {
                        endIndex++
                    }
                    result += StopLocation(
                        startTimestamp = interval.startTimestamp,
                        endTimestamp = intervals[endIndex].endTimestamp,
                        latitude = requireNotNull(interval.anchorLatitude),
                        longitude = requireNotNull(interval.anchorLongitude),
                    )
                    index = endIndex + 1
                } else {
                    index++
                }
            }
            return result
        }
}

/**
 * Classifies a session's continuous point stream into "parado" (stopped) / "em movimento"
 * (moving) time, per RF-06.
 *
 * RF-06's exact rule (SPEC.md): "classificar cada segmento contínuo em que o dispositivo
 * permanece dentro de um raio de 150 metros por mais de 5 minutos consecutivos como 'parado', e
 * classificar o tempo restante da sessão como 'em movimento'." Its AC spells out the boundary
 * semantics precisely: "um segmento só é 'parado' se todos os pontos nele estiverem contidos num
 * círculo de raio ≤150m por >300s consecutivos." This implementation follows that wording
 * literally:
 *  - **Radius boundary is INCLUSIVE at exactly 150.0m.** `≤150m` means a point exactly 150.0m
 *    from the segment's anchor still counts as "contido" — it does NOT break the stopped window.
 *  - **Duration boundary is EXCLUSIVE at exactly 300s (300_000ms).** `>300s` means a candidate
 *    window whose span is exactly 300_000ms does NOT qualify as "parado" — it must strictly
 *    exceed 300s. A window of exactly 300s is therefore classified as "em movimento" (it falls
 *    into "o tempo restante da sessão").
 *
 * "Contido num círculo de raio ≤150m" is operationalized as: every point in the candidate window
 * is within 150m (Haversine distance, [DistanceCalculator]) of the window's FIRST point (the
 * anchor). This is the classic "stay point detection" formulation (Li et al., trajectory mining
 * literature) — a deterministic O(n) check that is a valid witness for "some circle of radius
 * ≤150m contains all these points" (a circle centered on the anchor with radius 150m). SPEC.md
 * does not mandate a specific minimum-enclosing-circle algorithm; this is the operational
 * definition this implementation commits to, chosen for being deterministic, O(n), and exactly
 * matching the literal SPEC wording at the tested boundaries.
 */
object SegmentClassifier {

    /** Radius boundary, inclusive: `distance <= 150.0` counts as "contained". */
    const val STOP_RADIUS_METERS: Double = 150.0

    /** Duration boundary, exclusive: a window must have `duration > 300_000ms` to count. */
    const val STOP_DURATION_MILLIS: Long = 300_000L

    /**
     * Classifies every inter-point interval of [points] as [SegmentStatus.STOPPED] or
     * [SegmentStatus.MOVING].
     *
     * [stopRadiusMeters] and [stopDurationMillis] default to [STOP_RADIUS_METERS] /
     * [STOP_DURATION_MILLIS] (RF-06's literal thresholds), but callers may pass user-configured
     * values instead — e.g. a future Settings-driven UI (Phase C) that lets the user tune what
     * counts as "stopped". Every existing call site keeps compiling and behaving identically by
     * simply omitting these parameters.
     *
     * [points] MUST be ordered ascending by timestamp (as `GpsPointDao` guarantees). Sessions
     * with fewer than 2 points produce an empty result (there is no interval to classify, and
     * `elapsedTimeMillis` is `0` for such sessions too — the invariant holds trivially: `0 + 0
     * == 0`).
     */
    fun classify(
        points: List<GpsPointEntity>,
        stopRadiusMeters: Double = STOP_RADIUS_METERS,
        stopDurationMillis: Long = STOP_DURATION_MILLIS,
    ): SegmentClassificationResult {
        if (points.size < 2) return SegmentClassificationResult(emptyList())

        val intervalStatuses = MutableList(points.size - 1) { SegmentStatus.MOVING }
        val intervalAnchors = MutableList<GpsPointEntity?>(points.size - 1) { null }

        var anchorIndex = 0
        while (anchorIndex < points.size - 1) {
            var probeIndex = anchorIndex + 1
            while (
                probeIndex < points.size &&
                isWithinStopRadius(points[anchorIndex], points[probeIndex], stopRadiusMeters)
            ) {
                probeIndex++
            }
            // Window is points[anchorIndex .. probeIndex - 1]: all within stopRadiusMeters of
            // the anchor. probeIndex is either points.size (ran out of points) or the first
            // point that broke the radius.
            val windowEndIndex = probeIndex - 1

            if (windowEndIndex > anchorIndex) {
                val windowDurationMillis = points[windowEndIndex].timestamp - points[anchorIndex].timestamp
                if (windowDurationMillis > stopDurationMillis) {
                    for (k in anchorIndex until windowEndIndex) {
                        intervalStatuses[k] = SegmentStatus.STOPPED
                        intervalAnchors[k] = points[anchorIndex]
                    }
                    // Continue scanning with the window's last point as the new anchor, so a
                    // longer stay that this greedy pass split at a radius edge is still fully
                    // covered by contiguous STOPPED intervals.
                    anchorIndex = windowEndIndex
                    continue
                }
            }
            anchorIndex++
        }

        val intervals = (0 until points.size - 1).map { index ->
            ClassifiedInterval(
                startTimestamp = points[index].timestamp,
                endTimestamp = points[index + 1].timestamp,
                status = intervalStatuses[index],
                anchorLatitude = intervalAnchors[index]?.latitude,
                anchorLongitude = intervalAnchors[index]?.longitude,
            )
        }
        return SegmentClassificationResult(intervals)
    }

    private fun isWithinStopRadius(anchor: GpsPointEntity, candidate: GpsPointEntity, stopRadiusMeters: Double): Boolean {
        val distance = DistanceCalculator.distanceMeters(
            anchor.latitude, anchor.longitude,
            candidate.latitude, candidate.longitude,
        )
        return distance <= stopRadiusMeters
    }
}
