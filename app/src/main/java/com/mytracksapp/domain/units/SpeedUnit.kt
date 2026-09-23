package com.mytracksapp.domain.units

/**
 * Display units for speed. The app's internal canonical unit is meters/second (matching
 * [com.mytracksapp.domain.stats.StatsEngine]'s speed functions) — these are purely for
 * presentation, and never used internally for computation.
 */
enum class SpeedUnit {
    /** Kilometers per hour — the default display unit. */
    KMH,

    /** Meters per second — the internal canonical unit, exposed unconverted. */
    MS,

    /** Knots (nautical miles per hour). */
    KNOTS,
    ;

    /** Short, human-readable suffix for UI display next to a converted value (e.g. "5.4 km/h"). */
    val displaySuffix: String
        get() = when (this) {
            KMH -> "km/h"
            MS -> "m/s"
            KNOTS -> "nós"
        }
}

/**
 * Converts a speed from the internal canonical unit (meters/second) to a [SpeedUnit] for display.
 *
 * Pure, stateless, plain-Kotlin — no Android framework dependency, so it is directly testable on
 * the JVM without Robolectric or a device/emulator.
 */
object SpeedFormatter {

    /** 1 m/s = 3.6 km/h. */
    private const val KMH_PER_MS: Double = 3.6

    /** 1 m/s = 1.9438444924406 knots. */
    private const val KNOTS_PER_MS: Double = 1.9438444924406

    /** Converts [metersPerSecond] to the given display [unit]. */
    fun toDisplayValue(metersPerSecond: Double, unit: SpeedUnit): Double = when (unit) {
        SpeedUnit.KMH -> metersPerSecond * KMH_PER_MS
        SpeedUnit.MS -> metersPerSecond
        SpeedUnit.KNOTS -> metersPerSecond * KNOTS_PER_MS
    }
}
