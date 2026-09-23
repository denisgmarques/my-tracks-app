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

    // ---- Parameterized thresholds (Phase A follow-up: user-configurable stop detection) ----

    @Test
    fun `custom stopRadiusMeters is honored instead of the default constant`() {
        val anchor = point("s1", timestamp = 0L, lat = 0.0)
        // 150.5m: NOT stopped under the default 150.0m radius, but IS stopped under a wider 200m radius.
        val other = pointAtDistanceNorth("s1", timestamp = 301_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 150.5)

        val defaultResult = SegmentClassifier.classify(listOf(anchor, other))
        assertEquals(SegmentStatus.MOVING, defaultResult.intervals.single().status)

        val widenedResult = SegmentClassifier.classify(listOf(anchor, other), stopRadiusMeters = 200.0)
        assertEquals(SegmentStatus.STOPPED, widenedResult.intervals.single().status)
    }

    @Test
    fun `custom stopDurationMillis is honored instead of the default constant`() {
        val anchor = point("s1", timestamp = 0L, lat = 0.0)
        // 50m apart (well within radius), 60s elapsed: NOT stopped under the default 300s
        // duration, but IS stopped under a shorter 30s duration threshold.
        val other = pointAtDistanceNorth("s1", timestamp = 60_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 50.0)

        val defaultResult = SegmentClassifier.classify(listOf(anchor, other))
        assertEquals(SegmentStatus.MOVING, defaultResult.intervals.single().status)

        val shortenedResult = SegmentClassifier.classify(listOf(anchor, other), stopDurationMillis = 30_000L)
        assertEquals(SegmentStatus.STOPPED, shortenedResult.intervals.single().status)
    }

    @Test
    fun `default parameter values equal the STOP_RADIUS_METERS and STOP_DURATION_MILLIS constants`() {
        val anchor = point("s1", timestamp = 0L, lat = 0.0)
        val other = pointAtDistanceNorth("s1", timestamp = 300_001L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 150.0)

        val implicitDefaults = SegmentClassifier.classify(listOf(anchor, other))
        val explicitDefaults = SegmentClassifier.classify(
            listOf(anchor, other),
            stopRadiusMeters = SegmentClassifier.STOP_RADIUS_METERS,
            stopDurationMillis = SegmentClassifier.STOP_DURATION_MILLIS,
        )

        assertEquals(explicitDefaults.intervals.single().status, implicitDefaults.intervals.single().status)
        assertEquals(SegmentStatus.STOPPED, implicitDefaults.intervals.single().status)
    }

    // ---- Anchor coordinates / stop pin locations (Phase A follow-up: map pins for Phase C) ----

    @Test
    fun `STOPPED intervals expose the stop window's anchor coordinates`() {
        val points = (0..10).map { index ->
            pointAtDistanceNorth("s1", timestamp = index * 60_000L, lat0 = 10.0, lon0 = 20.0, distanceMeters = 10.0)
        }

        val result = SegmentClassifier.classify(points)

        result.intervals.forEach { interval ->
            assertEquals(SegmentStatus.STOPPED, interval.status)
            assertEquals(points[0].latitude, interval.anchorLatitude!!, 0.0000001)
            assertEquals(points[0].longitude, interval.anchorLongitude!!, 0.0000001)
        }
    }

    @Test
    fun `MOVING intervals expose no anchor coordinates`() {
        val points = (0..5).map { index ->
            pointAtDistanceNorth("s1", timestamp = index * 400_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = index * 1000.0)
        }

        val result = SegmentClassifier.classify(points)

        result.intervals.forEach { interval ->
            assertEquals(SegmentStatus.MOVING, interval.status)
            assertEquals(null, interval.anchorLatitude)
            assertEquals(null, interval.anchorLongitude)
        }
    }

    @Test
    fun `stopLocations returns one entry for a single contiguous stop`() {
        val points = (0..10).map { index ->
            pointAtDistanceNorth("s1", timestamp = index * 60_000L, lat0 = 5.0, lon0 = 5.0, distanceMeters = 10.0)
        }

        val result = SegmentClassifier.classify(points)

        assertEquals(1, result.stopLocations.size)
        val stop = result.stopLocations.single()
        assertEquals(points[0].latitude, stop.latitude, 0.0000001)
        assertEquals(points[0].longitude, stop.longitude, 0.0000001)
        assertEquals(points.first().timestamp, stop.startTimestamp)
        assertEquals(points.last().timestamp, stop.endTimestamp)
    }

    @Test
    fun `stopLocations is empty when the whole session is moving`() {
        val points = (0..5).map { index ->
            pointAtDistanceNorth("s1", timestamp = index * 400_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = index * 1000.0)
        }

        val result = SegmentClassifier.classify(points)

        assertTrue(result.stopLocations.isEmpty())
    }

    @Test
    fun `stopLocations returns two entries for two separate stops with movement in between`() {
        val points = mutableListOf<GpsPointEntity>()
        // Stop #1: stationary near the origin for 10 minutes.
        for (i in 0..10) {
            points += pointAtDistanceNorth("s1", timestamp = i * 60_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 10.0)
        }
        // Move far away.
        points += pointAtDistanceNorth("s1", timestamp = 660_000L + 30_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 5_000.0)
        // Stop #2: stationary near that new spot for another 10 minutes.
        val stop2Start = 660_000L + 60_000L
        for (i in 0..10) {
            points += pointAtDistanceNorth("s1", timestamp = stop2Start + i * 60_000L, lat0 = 0.0, lon0 = 0.0, distanceMeters = 5_010.0 + i * 0.001)
        }

        val result = SegmentClassifier.classify(points)

        assertEquals(2, result.stopLocations.size)
        assertTrue(result.stopLocations[0].endTimestamp < result.stopLocations[1].startTimestamp)
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
