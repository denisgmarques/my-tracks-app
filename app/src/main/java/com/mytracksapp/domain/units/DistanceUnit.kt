package com.mytracksapp.domain.units

/**
 * Display units for distance. The app's internal canonical unit is meters (matching
 * [com.mytracksapp.domain.stats.DistanceCalculator]/`StatsEngine.totalDistanceMeters`) — these
 * are purely for presentation, and never used internally for computation.
 */
enum class DistanceUnit {
    /** Kilometers — the default display unit. */
    KM,

    /** Statute (land) miles. */
    MILES,

    /** Nautical miles. */
    NAUTICAL_MILES,
    ;

    /** Short, human-readable suffix for UI display next to a converted value (e.g. "3.2 km"). */
    val displaySuffix: String
        get() = when (this) {
            KM -> "km"
            MILES -> "mi"
            NAUTICAL_MILES -> "NM"
        }
}

/**
 * Converts a distance from the internal canonical unit (meters) to a [DistanceUnit] for display.
 *
 * Pure, stateless, plain-Kotlin — no Android framework dependency, so it is directly testable on
 * the JVM without Robolectric or a device/emulator.
 */
object DistanceFormatter {

    /** 1 km = 1000 m. */
    private const val METERS_PER_KM: Double = 1000.0

    /** 1 mile = 1609.344 m. */
    private const val METERS_PER_MILE: Double = 1609.344

    /** 1 nautical mile = 1852.0 m. */
    private const val METERS_PER_NAUTICAL_MILE: Double = 1852.0

    /** Converts [meters] to the given display [unit]. */
    fun toDisplayValue(meters: Double, unit: DistanceUnit): Double = when (unit) {
        DistanceUnit.KM -> meters / METERS_PER_KM
        DistanceUnit.MILES -> meters / METERS_PER_MILE
        DistanceUnit.NAUTICAL_MILES -> meters / METERS_PER_NAUTICAL_MILE
    }
}
