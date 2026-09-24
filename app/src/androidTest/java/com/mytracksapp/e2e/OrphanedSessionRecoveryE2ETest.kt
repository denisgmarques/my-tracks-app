package com.mytracksapp.e2e

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.MainActivity
import com.mytracksapp.data.local.AppDatabase
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.domain.stats.SegmentClassifier
import com.mytracksapp.domain.stats.StatsEngine
import com.mytracksapp.ui.history.HistoryListScreenTestTags
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T04 — real-device end-to-end regression for RF-01/RF-05/RF-06: seeds orphaned `ACTIVE` rows
 * directly into the SAME persistent [AppDatabase] singleton [MainActivity] uses in production
 * (`AppDatabase.getInstance(context)`, not an in-memory test database like
 * `TrackingSessionE2ETest`), launches a real cold start, and verifies the recovery routine wired
 * in T03 actually finalizes them through the full production stack: `MainActivity.onCreate` ->
 * `OrphanedSessionRecovery` -> `MyTracksApp`'s `LaunchedEffect` -> `HistoryListScreen`.
 *
 * `createAndroidComposeRule<MainActivity>()` (used by `MainActivityTest`) launches the Activity
 * as part of the rule's own setup, before any `@Before`/test-body code can run — too late to seed
 * orphaned rows ahead of the recovery routine's cold-start read. Instead, this test uses
 * [createEmptyComposeRule] (registers Compose's test synchronization without launching anything
 * itself) plus a manually-controlled [ActivityScenario.launch], invoked from inside the test body
 * strictly AFTER seeding.
 *
 * Because [AppDatabase]'s singleton is process-wide and persistent on the test device, every
 * seeded row is removed in [removeSeededSessions] regardless of test outcome, to avoid polluting
 * the shared on-device database or a future run of `MainActivityTest`/`HistoryListScreenTest`.
 */
@RunWith(AndroidJUnit4::class)
class OrphanedSessionRecoveryE2ETest {

    @get:Rule
    val composeTestRule = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val database = AppDatabase.getInstance(context)
    private val trackingSessionDao: TrackingSessionDao = database.trackingSessionDao()
    private val gpsPointDao: GpsPointDao = database.gpsPointDao()

    private val noPointsSessionId = "e2e-recovery-no-points-${UUID.randomUUID()}"
    private val withPointsSessionIdA = "e2e-recovery-with-points-a-${UUID.randomUUID()}"
    private val withPointsSessionIdB = "e2e-recovery-with-points-b-${UUID.randomUUID()}"
    private val seededSessionIds = listOf(noPointsSessionId, withPointsSessionIdA, withPointsSessionIdB)

    @After
    fun removeSeededSessions() = runBlocking {
        seededSessionIds.forEach { id ->
            runCatching { trackingSessionDao.deleteById(id) }
        }
    }

    @Test
    fun coldStart_finalizesAllOrphanedSessions_withIsolatedMetrics_andNoRecoveredBadge() {
        // Block body (not `= runBlocking { ... }`): the try/finally below ends in
        // assertCountEquals, which returns a SemanticsNodeInteractionCollection (fluent API), not
        // Unit — an expression-body function would infer THAT as the test method's return type,
        // which JUnit4 rejects ("should be void") via reflection. Wrapping in a block body forces
        // Unit regardless of the lambda's trailing expression type.
        runBlocking {
        // --- Seed BEFORE launching the Activity: these rows already exist as ACTIVE orphans
        // before this "cold start" (RF-01's premise). ---

        // (a) one ACTIVE session with zero points (RF-02).
        trackingSessionDao.insert(
            TrackingSessionEntity(
                id = noPointsSessionId,
                samplingIntervalSeconds = 10,
                startTimestamp = 1_800_000_000_000L,
                status = SessionStatus.ACTIVE,
            ),
        )

        // (b) one ACTIVE session with a handful of points.
        val pointsA = listOf(
            GpsPointEntity(sessionId = withPointsSessionIdA, timestamp = 1_800_000_100_000L, latitude = -23.550520, longitude = -46.633308, accuracy = 5f),
            GpsPointEntity(sessionId = withPointsSessionIdA, timestamp = 1_800_000_110_000L, latitude = -23.550070, longitude = -46.632858, accuracy = 5f),
            GpsPointEntity(sessionId = withPointsSessionIdA, timestamp = 1_800_000_120_000L, latitude = -23.549620, longitude = -46.632408, accuracy = 5f),
        )
        trackingSessionDao.insert(
            TrackingSessionEntity(
                id = withPointsSessionIdA,
                samplingIntervalSeconds = 10,
                startTimestamp = pointsA.first().timestamp,
                status = SessionStatus.ACTIVE,
            ),
        )
        gpsPointDao.insertAll(pointsA)

        // (c) a second, concurrent ACTIVE session with a completely distinct point set (RF-05:
        // per-session isolation must hold even when both orphans are recovered in the same pass).
        val pointsB = listOf(
            GpsPointEntity(sessionId = withPointsSessionIdB, timestamp = 1_800_000_200_000L, latitude = 10.0000, longitude = 20.0000, accuracy = 5f),
            GpsPointEntity(sessionId = withPointsSessionIdB, timestamp = 1_800_000_300_000L, latitude = 10.0050, longitude = 20.0050, accuracy = 5f),
            GpsPointEntity(sessionId = withPointsSessionIdB, timestamp = 1_800_000_400_000L, latitude = 10.0100, longitude = 20.0100, accuracy = 5f),
            GpsPointEntity(sessionId = withPointsSessionIdB, timestamp = 1_800_000_500_000L, latitude = 10.0150, longitude = 20.0150, accuracy = 5f),
        )
        trackingSessionDao.insert(
            TrackingSessionEntity(
                id = withPointsSessionIdB,
                samplingIntervalSeconds = 15,
                startTimestamp = pointsB.first().timestamp,
                status = SessionStatus.ACTIVE,
            ),
        )
        gpsPointDao.insertAll(pointsB)

        // Sanity: all three really are ACTIVE right before "cold start".
        seededSessionIds.forEach { id ->
            assertEquals(SessionStatus.ACTIVE, trackingSessionDao.getSessionById(id).first()!!.status)
        }

        // --- Cold start: launch MainActivity manually, strictly after seeding above. ---
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            // RNF-01: the History screen (start destination) renders immediately, not gated on
            // the recovery routine having finished yet.
            composeTestRule.onNodeWithTag(HistoryListScreenTestTags.SCREEN).assertIsDisplayed()

            // Now wait for the background recovery routine to actually finish finalizing all
            // three seeded rows.
            composeTestRule.waitUntil(timeoutMillis = 15_000) {
                seededSessionIds.all { id ->
                    runBlocking { trackingSessionDao.getSessionById(id).first()?.status == SessionStatus.FINISHED }
                }
            }

            // --- RF-01/RF-02: the zero-point session is finalized with endTimestamp ==
            // startTimestamp and every metric zeroed. ---
            val finishedNoPoints = trackingSessionDao.getSessionById(noPointsSessionId).first()!!
            assertEquals(SessionStatus.FINISHED, finishedNoPoints.status)
            assertEquals(finishedNoPoints.startTimestamp, finishedNoPoints.endTimestamp)
            assertEquals(0.0, finishedNoPoints.distanceMeters, 0.0)
            assertEquals(0.0, finishedNoPoints.averageSpeedMetersPerSecond, 0.0)
            assertEquals(0L, finishedNoPoints.movingTimeMillis)
            assertEquals(0L, finishedNoPoints.stoppedTimeMillis)

            // --- RF-04: recovery never touched the points table -- same count, same rows. ---
            val storedPointsA = gpsPointDao.getPointsForSession(withPointsSessionIdA).first()
            val storedPointsB = gpsPointDao.getPointsForSession(withPointsSessionIdB).first()
            assertEquals(pointsA.size, storedPointsA.size)
            assertEquals(pointsB.size, storedPointsB.size)
            assertEquals(pointsA.map { it.timestamp }, storedPointsA.map { it.timestamp })
            assertEquals(pointsB.map { it.timestamp }, storedPointsB.map { it.timestamp })

            // --- RF-03/RF-05: each with-points session's persisted metrics were computed
            // exclusively from its OWN points, matching an independent recomputation over those
            // same points -- and the two sessions' results do not leak into each other. ---
            val finishedA = trackingSessionDao.getSessionById(withPointsSessionIdA).first()!!
            val finishedB = trackingSessionDao.getSessionById(withPointsSessionIdB).first()!!
            assertEquals(SessionStatus.FINISHED, finishedA.status)
            assertEquals(SessionStatus.FINISHED, finishedB.status)
            assertEquals(pointsA.last().timestamp, finishedA.endTimestamp)
            assertEquals(pointsB.last().timestamp, finishedB.endTimestamp)

            val expectedClassificationA = SegmentClassifier.classify(storedPointsA)
            val expectedClassificationB = SegmentClassifier.classify(storedPointsB)
            assertEquals(expectedClassificationA.movingTimeMillis, finishedA.movingTimeMillis)
            assertEquals(expectedClassificationA.stoppedTimeMillis, finishedA.stoppedTimeMillis)
            assertEquals(expectedClassificationB.movingTimeMillis, finishedB.movingTimeMillis)
            assertEquals(expectedClassificationB.stoppedTimeMillis, finishedB.stoppedTimeMillis)

            assertEquals(StatsEngine.totalDistanceMeters(storedPointsA), finishedA.distanceMeters, 0.0001)
            assertEquals(StatsEngine.totalDistanceMeters(storedPointsB), finishedB.distanceMeters, 0.0001)
            assertEquals(
                StatsEngine.averageSpeedMetersPerSecond(storedPointsA),
                finishedA.averageSpeedMetersPerSecond,
                0.0001,
            )
            assertEquals(
                StatsEngine.averageSpeedMetersPerSecond(storedPointsB),
                finishedB.averageSpeedMetersPerSecond,
                0.0001,
            )
            // No cross-session leakage: A and B moved through very different coordinates, so
            // their independently-computed distances must not coincide.
            assertNotEquals(finishedA.distanceMeters, finishedB.distanceMeters, 0.0001)
            assertTrue("session A should have covered a positive distance", finishedA.distanceMeters > 0.0)
            assertTrue("session B should have covered a positive distance", finishedB.distanceMeters > 0.0)

            // --- RF-06: each recovered session renders in HistoryListScreen through the exact
            // same query/rendering path as a manually-finished one, with no "recovered" label. ---
            seededSessionIds.forEach { id ->
                composeTestRule.waitUntil(timeoutMillis = 5_000) {
                    composeTestRule.onAllNodesWithTag(HistoryListScreenTestTags.item(id))
                        .fetchSemanticsNodes().isNotEmpty()
                }
                composeTestRule.onNodeWithTag(HistoryListScreenTestTags.item(id)).assertIsDisplayed()
            }
            // Belt-and-suspenders textual check: no "recuperada"/"recovered" wording appears
            // anywhere onscreen -- recovered sessions are indistinguishable from manual ones.
            composeTestRule.onAllNodesWithText("recuperada", substring = true, ignoreCase = true)
                .assertCountEquals(0)
            composeTestRule.onAllNodesWithText("recovered", substring = true, ignoreCase = true)
                .assertCountEquals(0)
        } finally {
            scenario.close()
        }
        }
    }
}
