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
     * [points] MUST be ordered ascending by timestamp (as `GpsPointDao` guarantees). Sessions
     * with fewer than 2 points produce an empty result (there is no interval to classify, and
     * `elapsedTimeMillis` is `0` for such sessions too — the invariant holds trivially: `0 + 0
     * == 0`).
     */
    fun classify(points: List<GpsPointEntity>): SegmentClassificationResult {
        if (points.size < 2) return SegmentClassificationResult(emptyList())

        val intervalStatuses = MutableList(points.size - 1) { SegmentStatus.MOVING }

        var anchorIndex = 0
        while (anchorIndex < points.size - 1) {
            var probeIndex = anchorIndex + 1
            while (probeIndex < points.size && isWithinStopRadius(points[anchorIndex], points[probeIndex])) {
                probeIndex++
            }
            // Window is points[anchorIndex .. probeIndex - 1]: all within STOP_RADIUS_METERS of
            // the anchor. probeIndex is either points.size (ran out of points) or the first
            // point that broke the radius.
            val windowEndIndex = probeIndex - 1

            if (windowEndIndex > anchorIndex) {
                val windowDurationMillis = points[windowEndIndex].timestamp - points[anchorIndex].timestamp
                if (windowDurationMillis > STOP_DURATION_MILLIS) {
                    for (k in anchorIndex until windowEndIndex) {
                        intervalStatuses[k] = SegmentStatus.STOPPED
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
            )
        }
        return SegmentClassificationResult(intervals)
    }

    private fun isWithinStopRadius(anchor: GpsPointEntity, candidate: GpsPointEntity): Boolean {
        val distance = DistanceCalculator.distanceMeters(
            anchor.latitude, anchor.longitude,
            candidate.latitude, candidate.longitude,
        )
        return distance <= STOP_RADIUS_METERS
    }
}
