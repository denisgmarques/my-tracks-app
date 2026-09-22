package com.mytracksapp.domain.stats

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geodesic distance between two lat/lon points, via the Haversine formula — SPEC.md's FLEXIBLE
 * suggestion ("Cálculo de distância geodésica sugerido: fórmula de Haversine ou
 * `Location.distanceTo()`").
 *
 * Deliberately pure Kotlin with no Android framework dependency (no `android.location.Location`),
 * so RF-05/RF-06 math can run in plain JVM unit tests (StatsEngineTest, SegmentClassifierTest)
 * without Robolectric or an Android device/emulator.
 */
object DistanceCalculator {

    /** Mean Earth radius in meters, per the standard Haversine formula. */
    const val EARTH_RADIUS_METERS: Double = 6_371_000.0

    /**
     * Geodesic distance in meters between (lat1, lon1) and (lat2, lon2), both in decimal
     * degrees.
     */
    fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)

        val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }
}
