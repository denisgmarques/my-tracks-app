package com.mytracksapp.domain.units

/**
 * Formats an elapsed duration (milliseconds) as a compact clock string for display.
 *
 * **Exact rule:**
 *  - Milliseconds are always truncated down to whole seconds first (no rounding).
 *  - Seconds are ALWAYS rendered 2-digit, zero-padded (`"05"`, not `"5"`).
 *  - **Under 1 hour (`< 3_600_000`ms):** format is `m:ss` — minutes are rendered UNPADDED (no
 *    leading zero), since there is no larger unit for them to align under. Examples:
 *    `5_000ms -> "0:05"`, `45_000ms -> "0:45"`, `65_000ms -> "1:05"`, `3_599_000ms -> "59:59"`.
 *  - **At or above 1 hour (`>= 3_600_000`ms, the documented switch point):** format is `h:mm:ss` —
 *    hours are rendered UNPADDED, but minutes are now 2-digit, zero-padded (`"01"`, not `"1"`),
 *    since they sit between a larger (hours) and smaller (seconds) unit and must align
 *    unambiguously. Examples: `3_600_000ms -> "1:00:00"` (the exact boundary), `3_661_000ms ->
 *    "1:01:01"`, `7_325_000ms -> "2:02:05"`.
 *
 * This mirrors the everyday convention used by media players and stopwatches: only the outermost
 * (most significant) unit is ever left unpadded; every unit "nested" inside a larger one is
 * always 2-digit zero-padded so the string is unambiguous and sortable-by-eye at a glance.
 */
object ElapsedTimeFormatter {

    private const val MILLIS_PER_SECOND: Long = 1_000L
    private const val SECONDS_PER_MINUTE: Long = 60L
    private const val MINUTES_PER_HOUR: Long = 60L
    private const val MILLIS_PER_HOUR: Long = MILLIS_PER_SECOND * SECONDS_PER_MINUTE * MINUTES_PER_HOUR

    /** Formats [millis] per the rule documented on this object. Negative input is treated as 0. */
    fun format(millis: Long): String {
        val totalSeconds = (millis.coerceAtLeast(0L)) / MILLIS_PER_SECOND
        val seconds = totalSeconds % SECONDS_PER_MINUTE
        val totalMinutes = totalSeconds / SECONDS_PER_MINUTE

        return if (millis >= MILLIS_PER_HOUR) {
            val hours = totalMinutes / MINUTES_PER_HOUR
            val minutes = totalMinutes % MINUTES_PER_HOUR
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(totalMinutes, seconds)
        }
    }
}
