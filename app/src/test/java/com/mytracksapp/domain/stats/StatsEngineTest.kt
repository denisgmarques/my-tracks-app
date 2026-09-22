package com.mytracksapp.domain.stats

import com.mytracksapp.data.local.entity.GpsPointEntity
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * T07 — StatsEngine unit tests: known point pairs produce the expected instant/average speed
 * (RF-05) and elapsed time (RF-04).
 *
 * Expected distances are computed via an independently-written reference Haversine formula
 * (asin form below) rather than by calling [DistanceCalculator] itself, so this test can catch a
 * bug in [DistanceCalculator]'s own (atan2-form) implementation rather than merely checking it
 * against itself.
 */
class StatsEngineTest {

    /** Reference Haversine distance (asin form) — independent of DistanceCalculator's atan2 form. */
    private fun referenceHaversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val deltaPhi = Math.toRadians(lat2 - lat1)
        val deltaLambda = Math.toRadians(lon2 - lon1)
        val a = sin(deltaPhi / 2).let { it * it } +
            cos(phi1) * cos(phi2) * sin(deltaLambda / 2).let { it * it }
        val c = 2 * asin(sqrt(a).coerceIn(-1.0, 1.0))
        return r * c
    }

    private fun point(sessionId: String, timestamp: Long, lat: Double, lon: Double, accuracy: Float = 5.0f) =
        GpsPointEntity(
            sessionId = sessionId,
            timestamp = timestamp,
            latitude = lat,
            longitude = lon,
            accuracy = accuracy,
        )

    @Test
    fun `distanceMeters matches independent reference Haversine formula for a known pair`() {
        val lat1 = -23.550520
        val lon1 = -46.633308
        val lat2 = -23.561414
        val lon2 = -46.655881

        val expected = referenceHaversineMeters(lat1, lon1, lat2, lon2)
        val actual = DistanceCalculator.distanceMeters(lat1, lon1, lat2, lon2)

        assertEquals(expected, actual, 0.001)
    }

    @Test
    fun `distanceMeters matches known approximate real-world distance (NY to LA)`() {
        // Widely cited great-circle distance New York <-> Los Angeles is ~3936 km.
        val nyLat = 40.7128
        val nyLon = -74.0060
        val laLat = 34.0522
        val laLon = -118.2437

        val distanceKm = DistanceCalculator.distanceMeters(nyLat, nyLon, laLat, laLon) / 1000.0

        assertEquals(3936.0, distanceKm, 20.0)
    }

    @Test
    fun `distanceMeters is exactly zero for identical points`() {
        assertEquals(0.0, DistanceCalculator.distanceMeters(10.0, 20.0, 10.0, 20.0), 0.0)
    }

    @Test
    fun `instantSpeedMetersPerSecond matches distance over time for a known pair`() {
        val lat1 = -23.550520
        val lon1 = -46.633308
        val lat2 = -23.551520 // roughly 111m north
        val lon2 = -46.633308

        val previous = point("s1", timestamp = 1_000L, lat = lat1, lon = lon1)
        val current = point("s1", timestamp = 11_000L, lat = lat2, lon = lon2) // 10s later

        val expectedDistance = referenceHaversineMeters(lat1, lon1, lat2, lon2)
        val expectedSpeed = expectedDistance / 10.0

        val actualSpeed = StatsEngine.instantSpeedMetersPerSecond(previous, current)

        assertEquals(expectedSpeed, actualSpeed, 0.001)
    }

    @Test
    fun `instantSpeedMetersPerSecond is zero for non-positive elapsed time`() {
        val p1 = point("s1", timestamp = 5_000L, lat = 0.0, lon = 0.0)
        val p2 = point("s1", timestamp = 5_000L, lat = 1.0, lon = 1.0) // same timestamp

        assertEquals(0.0, StatsEngine.instantSpeedMetersPerSecond(p1, p2), 0.0)
    }

    @Test
    fun `instantSpeeds returns one value per consecutive pair, empty for less than 2 points`() {
        assertEquals(emptyList<Double>(), StatsEngine.instantSpeeds(emptyList()))
        assertEquals(
            emptyList<Double>(),
            StatsEngine.instantSpeeds(listOf(point("s1", 0L, 0.0, 0.0))),
        )

        val points = listOf(
            point("s1", 0L, 0.0, 0.0),
            point("s1", 10_000L, 0.001, 0.0),
            point("s1", 20_000L, 0.002, 0.0),
        )
        assertEquals(2, StatsEngine.instantSpeeds(points).size)
    }

    @Test
    fun `elapsedTimeMillis is last minus first timestamp, per RF-04`() {
        val points = listOf(
            point("s1", 1_000L, 0.0, 0.0),
            point("s1", 16_000L, 0.001, 0.0),
            point("s1", 46_000L, 0.002, 0.0),
        )

        assertEquals(45_000L, StatsEngine.elapsedTimeMillis(points))
    }

    @Test
    fun `elapsedTimeMillis is zero for empty or single-point sessions`() {
        assertEquals(0L, StatsEngine.elapsedTimeMillis(emptyList()))
        assertEquals(0L, StatsEngine.elapsedTimeMillis(listOf(point("s1", 5_000L, 0.0, 0.0))))
    }

    @Test
    fun `averageSpeedMetersPerSecond equals total distance over total elapsed time`() {
        val p0 = point("s1", 0L, 0.0, 0.0)
        val p1 = point("s1", 10_000L, 0.001, 0.0)
        val p2 = point("s1", 25_000L, 0.002, 0.0)
        val points = listOf(p0, p1, p2)

        val totalDistance = referenceHaversineMeters(p0.latitude, p0.longitude, p1.latitude, p1.longitude) +
            referenceHaversineMeters(p1.latitude, p1.longitude, p2.latitude, p2.longitude)
        val totalElapsedSeconds = (p2.timestamp - p0.timestamp) / 1000.0
        val expectedAverage = totalDistance / totalElapsedSeconds

        assertEquals(expectedAverage, StatsEngine.averageSpeedMetersPerSecond(points), 0.001)
    }

    @Test
    fun `averageSpeedMetersPerSecond is zero for fewer than two points`() {
        assertEquals(0.0, StatsEngine.averageSpeedMetersPerSecond(emptyList()), 0.0)
        assertEquals(
            0.0,
            StatsEngine.averageSpeedMetersPerSecond(listOf(point("s1", 0L, 0.0, 0.0))),
            0.0,
        )
    }

    @Test
    fun `averageSpeedMetersPerSecond recalculates correctly as new points are appended`() {
        val p0 = point("s1", 0L, 0.0, 0.0)
        val p1 = point("s1", 10_000L, 0.001, 0.0)

        val afterFirstSegment = StatsEngine.averageSpeedMetersPerSecond(listOf(p0, p1))

        val p2 = point("s1", 20_000L, 0.002, 0.0)
        val afterSecondSegment = StatsEngine.averageSpeedMetersPerSecond(listOf(p0, p1, p2))

        // Same lat step repeated at the same time cadence -> average speed stays the same.
        assertEquals(afterFirstSegment, afterSecondSegment, 0.001)
    }
}
