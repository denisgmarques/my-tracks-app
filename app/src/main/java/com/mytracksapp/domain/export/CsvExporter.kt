package com.mytracksapp.domain.export

import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.domain.stats.ClassifiedInterval
import com.mytracksapp.domain.stats.SegmentClassifier
import com.mytracksapp.domain.stats.SegmentStatus
import com.mytracksapp.domain.stats.StatsEngine
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Generates a CSV file for a session's collected points (RF-08, CT-02), matching
 * `csv-export-schema.json` exactly: header row followed by one data row per point, columns in
 * the specified order — `session_id, timestamp, latitude, longitude, accuracy, speed_instant,
 * segment_status` — comma-delimited, UTF-8.
 *
 * Per-point derived values:
 *  - `speed_instant` — csv-export-schema.json describes it as "calculada entre este ponto e o
 *    ponto anterior": for point `i >= 1`, this is [StatsEngine.instantSpeedMetersPerSecond]
 *    between point `i-1` and point `i`. Point `0` has no previous point, so its `speed_instant`
 *    is `0.0`.
 *  - `segment_status` — [SegmentClassifier] classifies inter-point INTERVALS, not points. Each
 *    point `i >= 1` inherits the status of the interval immediately preceding it (`[i-1, i]`),
 *    consistent with the same "relative to the previous point" convention as `speed_instant`.
 *    Point `0` (no preceding interval) borrows the status of the first interval `[0, 1]`
 *    (equivalent to treating the session's very start as already in whatever state it is about
 *    to spend time in); a single-point session (no intervals at all) defaults to `moving`, since
 *    RF-06's "parado" classification requires a sustained >300s window that cannot exist yet.
 *  - `segment_status` literals are fixed to the English `moving`/`stopped` strings (decision
 *    recorded 2026-09-22), mapped from [SegmentStatus]'s internal `MOVING`/`STOPPED` names.
 */
object CsvExporter {

    /** Column order, exactly as `csv-export-schema.json` specifies (CT-02). */
    private const val HEADER = "session_id,timestamp,latitude,longitude,accuracy,speed_instant,segment_status"

    /** `%Y-%m-%dT%H:%M:%S%z` per csv-export-schema.json, rendered in UTC. */
    private val TIMESTAMP_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)

    /**
     * @throws IllegalArgumentException if [points] is empty — a "sessão concluída" (RF-08) only
     *   exists if collection was active (RF-02), so it always has at least one point.
     */
    fun export(sessionId: String, points: List<GpsPointEntity>): String {
        require(points.isNotEmpty()) { "Cannot export CSV for a session with no collected points" }

        val instantSpeeds = StatsEngine.instantSpeeds(points) // size == points.size - 1
        val intervals = SegmentClassifier.classify(points).intervals // size == points.size - 1

        val rows = points.mapIndexed { index, point ->
            val speedInstant = if (index == 0) 0.0 else instantSpeeds[index - 1]
            val status = segmentStatusLiteral(pointStatus(index, intervals))
            listOf(
                sessionId,
                formatTimestamp(point.timestamp),
                point.latitude.toString(),
                point.longitude.toString(),
                point.accuracy.toString(),
                speedInstant.toString(),
                status,
            ).joinToString(",")
        }

        return (listOf(HEADER) + rows).joinToString(separator = "\n", postfix = "\n")
    }

    private fun formatTimestamp(epochMillis: Long): String =
        TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC))

    private fun pointStatus(pointIndex: Int, intervals: List<ClassifiedInterval>): SegmentStatus {
        if (intervals.isEmpty()) return SegmentStatus.MOVING
        return if (pointIndex == 0) intervals.first().status else intervals[pointIndex - 1].status
    }

    private fun segmentStatusLiteral(status: SegmentStatus): String = when (status) {
        SegmentStatus.MOVING -> "moving"
        SegmentStatus.STOPPED -> "stopped"
    }
}
