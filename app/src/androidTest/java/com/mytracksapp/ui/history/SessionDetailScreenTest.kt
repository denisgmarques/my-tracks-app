package com.mytracksapp.ui.history

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T10 — instrumented Compose UI test for [SessionDetailScreen] (UI-03): all 5 required metrics
 * (instant speed, average speed, total elapsed time, stopped time, moving time) are visible
 * simultaneously, with no extra navigation.
 */
@RunWith(AndroidJUnit4::class)
class SessionDetailScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val sessionId = "session-detail-test"

    private val fakeTrackingSessionDao = object : TrackingSessionDao {
        private val session = TrackingSessionEntity(
            id = sessionId,
            samplingIntervalSeconds = 15,
            startTimestamp = 0L,
            endTimestamp = 40_000L,
            status = SessionStatus.FINISHED,
            stoppedTimeMillis = 20_000L,
            movingTimeMillis = 20_000L,
            averageSpeedMetersPerSecond = 1.2,
        )

        override suspend fun insert(session: TrackingSessionEntity) = Unit
        override suspend fun update(session: TrackingSessionEntity) = Unit
        override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> = flowOf(session)
        override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = flowOf(listOf(session))
        override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> =
            flowOf(listOf(session))
    }

    private val fakeGpsPointDao = object : GpsPointDao {
        private val points = listOf(
            GpsPointEntity(sessionId = sessionId, timestamp = 0L, latitude = 0.0, longitude = 0.0, accuracy = 5f),
            GpsPointEntity(sessionId = sessionId, timestamp = 20_000L, latitude = 0.01, longitude = 0.0, accuracy = 5f),
            GpsPointEntity(sessionId = sessionId, timestamp = 40_000L, latitude = 0.02, longitude = 0.0, accuracy = 5f),
        )

        override suspend fun insert(point: GpsPointEntity): Long = 0L
        override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> = emptyList()
        override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> = flowOf(points)
        override suspend fun countForSession(sessionId: String): Int = points.size
    }

    @Test
    fun allFiveUi03MetricsAreVisibleSimultaneouslyWithNoExtraNavigation() {
        composeTestRule.setContent {
            SessionDetailScreen(
                viewModel = SessionDetailViewModel(sessionId, fakeTrackingSessionDao, fakeGpsPointDao),
            )
        }

        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.INSTANT_SPEED).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.AVERAGE_SPEED).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.ELAPSED_TIME).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.STOPPED_TIME).assertIsDisplayed()
        composeTestRule.onNodeWithTag(SessionDetailScreenTestTags.MOVING_TIME).assertIsDisplayed()
    }
}
