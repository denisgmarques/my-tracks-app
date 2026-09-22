package com.mytracksapp.domain.stats

import com.mytracksapp.data.local.entity.GpsPointEntity
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T07 — SegmentClassifier unit tests: exact boundary cases for RF-06's "raio ≤150m por >300s"
 * rule, plus the "stopped + moving == total elapsed time" invariant for arbitrary sessions.
 *
 * Boundary points are constructed with pure north-south (same-longitude) offsets, for which the
 * Haversine formula reduces to an EXACT identity (`distance = EARTH_RADIUS_METERS * deltaPhiRad`,
 * since `deltaLambda == 0` collapses the cross term to zero) — see [pointAtDistanceNorth]. This
 * lets tests target the 150.0m radius boundary precisely (to floating-point rounding, which is
 * on the order of 1e-9m here — negligible next to the meter-scale boundary).
 */
class SegmentClassifierTest {

    private fun point(sessionId: String, timestamp: Long, lat: Double, lon: Double = 0.0) =
        GpsPointEntity(sessionId = sessionId, timestamp = timestamp, latitude = lat, longitude = lon, accuracy = 5f)

    /** A point exactly [distanceMeters] north of (lat0, lon0) — exact via the Haversine identity. */
    private fun pointAtDistanceNorth(sessionId: String, timestamp: Long, lat0: Double, lon0: Double, distanceMeters: Double): GpsPointEntity {
        val deltaPhiRadians = distanceMeters / DistanceCalculator.EARTH_RADIUS_METERS
        val lat1 = lat0 + Math.toDegrees(deltaPhiRadians)
        return point(sessionId, timestamp, lat1, lon0)
    }

    // ---- Radius boundary: exactly 150.0m ----

    @Test
    fun `exactly 150m apart is treated as contained (radius boundary inclusive)`() {
        val anchor = point("s1", timestamp = 0L, lat = 0.0)
        val other = pointAtDistanceNorth("s1", timestamp = 301_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 150.0)

        // Sanity: the constructed pair really is ~150.0m apart.
        val actualDistance = DistanceCalculator.distanceMeters(anchor.latitude, anchor.longitude, other.latitude, other.longitude)
        assertEquals(150.0, actualDistance, 0.0001)

        val result = SegmentClassifier.classify(listOf(anchor, other))

        assertEquals(SegmentStatus.STOPPED, result.intervals.single().status)
    }

    @Test
    fun `just over 150m apart is NOT contained (radius boundary exclusive above)`() {
        val anchor = point("s1", timestamp = 0L, lat = 0.0)
        val other = pointAtDistanceNorth("s1", timestamp = 301_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 150.5)

        val result = SegmentClassifier.classify(listOf(anchor, other))

        assertEquals(SegmentStatus.MOVING, result.intervals.single().status)
    }

    // ---- Duration boundary: exactly 300s ----

    @Test
    fun `exactly 300s within radius is NOT stopped (duration boundary exclusive)`() {
        val anchor = point("s1", timestamp = 0L, lat = 0.0)
        // Well within the radius (50m), duration exactly 300_000ms.
        val other = pointAtDistanceNorth("s1", timestamp = 300_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 50.0)

        val result = SegmentClassifier.classify(listOf(anchor, other))

        assertEquals(SegmentStatus.MOVING, result.intervals.single().status)
    }

    @Test
    fun `one millisecond over 300s within radius IS stopped (duration boundary strictly exceeded)`() {
        val anchor = point("s1", timestamp = 0L, lat = 0.0)
        val other = pointAtDistanceNorth("s1", timestamp = 300_001L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 50.0)

        val result = SegmentClassifier.classify(listOf(anchor, other))

        assertEquals(SegmentStatus.STOPPED, result.intervals.single().status)
    }

    @Test
    fun `combined boundary - exactly 150m AND exactly 300s is NOT stopped`() {
        val anchor = point("s1", timestamp = 0L, lat = 0.0)
        val other = pointAtDistanceNorth("s1", timestamp = 300_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 150.0)

        val result = SegmentClassifier.classify(listOf(anchor, other))

        // Neither condition is strictly satisfied at its own exact boundary in a way that fails
        // the rule; duration == 300s fails ">300s", so the whole segment is "em movimento".
        assertEquals(SegmentStatus.MOVING, result.intervals.single().status)
    }

    @Test
    fun `combined boundary - exactly 150m AND just over 300s IS stopped`() {
        val anchor = point("s1", timestamp = 0L, lat = 0.0)
        val other = pointAtDistanceNorth("s1", timestamp = 300_001L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 150.0)

        val result = SegmentClassifier.classify(listOf(anchor, other))

        assertEquals(SegmentStatus.STOPPED, result.intervals.single().status)
    }

    // ---- General behavior ----

    @Test
    fun `fewer than 2 points yields empty classification`() {
        assertEquals(0, SegmentClassifier.classify(emptyList()).intervals.size)
        assertEquals(0, SegmentClassifier.classify(listOf(point("s1", 0L, 0.0))).intervals.size)
    }

    @Test
    fun `a session entirely far apart is fully classified as moving`() {
        val points = (0..5).map { index ->
            pointAtDistanceNorth("s1", timestamp = index * 400_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = index * 1000.0)
        }

        val result = SegmentClassifier.classify(points)

        assertEquals(0L, result.stoppedTimeMillis)
        assertEquals(StatsEngine.elapsedTimeMillis(points), result.movingTimeMillis)
    }

    @Test
    fun `a session entirely stationary for well over 300s is fully classified as stopped`() {
        val points = (0..10).map { index ->
            pointAtDistanceNorth("s1", timestamp = index * 60_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 10.0)
        }

        val result = SegmentClassifier.classify(points)

        assertEquals(StatsEngine.elapsedTimeMillis(points), result.stoppedTimeMillis)
        assertEquals(0L, result.movingTimeMillis)
    }

    @Test
    fun `stopped plus moving time equals total elapsed time - fixed mixed scenario`() {
        // Stationary cluster (within 50m) for 10 minutes, then a fast departure far away.
        val points = mutableListOf<GpsPointEntity>()
        for (i in 0..10) {
            points += pointAtDistanceNorth("s1", timestamp = i * 60_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 10.0)
        }
        // Departure: big jump, well outside 150m, shortly after the stationary window.
        points += pointAtDistanceNorth("s1", timestamp = 660_000L + 30_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 5_000.0)
        points += pointAtDistanceNorth("s1", timestamp = 660_000L + 60_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 10_000.0)

        val result = SegmentClassifier.classify(points)
        val expectedTotal = StatsEngine.elapsedTimeMillis(points)

        assertEquals(expectedTotal, result.stoppedTimeMillis + result.movingTimeMillis)
        assertEquals(expectedTotal, result.totalTimeMillis)
    }

    @Test
    fun `stopped plus moving time equals total elapsed time - randomized sessions`() {
        val random = Random(42)

        repeat(50) { scenarioIndex ->
            val pointCount = random.nextInt(2, 40)
            var timestamp = 0L
            var distanceFromOrigin = 0.0
            val points = mutableListOf<GpsPointEntity>()
            repeat(pointCount) {
                timestamp += random.nextLong(1_000L, 120_000L)
                // Randomly either stay near the current spot or jump far away.
                distanceFromOrigin = if (random.nextBoolean()) {
                    distanceFromOrigin + random.nextDouble(0.0, 30.0)
                } else {
                    distanceFromOrigin + random.nextDouble(200.0, 2_000.0)
                }
                points += pointAtDistanceNorth("s$scenarioIndex", timestamp, 0.0, 0.0, distanceFromOrigin)
            }

            val result = SegmentClassifier.classify(points)
            val expectedTotal = StatsEngine.elapsedTimeMillis(points)

            assertEquals(
                "scenario $scenarioIndex: stopped(${result.stoppedTimeMillis}) + moving(${result.movingTimeMillis}) != total($expectedTotal)",
                expectedTotal,
                result.stoppedTimeMillis + result.movingTimeMillis,
            )
            assertTrue(result.stoppedTimeMillis >= 0L)
            assertTrue(result.movingTimeMillis >= 0L)
        }
    }

    @Test
    fun `intervals partition the session with no gaps or overlaps`() {
        val points = (0..6).map { index ->
            pointAtDistanceNorth("s1", timestamp = index * 90_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = index * 80.0)
        }

        val result = SegmentClassifier.classify(points)

        assertEquals(points.size - 1, result.intervals.size)
        result.intervals.forEachIndexed { index, interval ->
            assertEquals(points[index].timestamp, interval.startTimestamp)
            assertEquals(points[index + 1].timestamp, interval.endTimestamp)
        }
    }
}
