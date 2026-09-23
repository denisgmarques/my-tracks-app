package com.mytracksapp.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * T02 — Room roundtrip test (instrumented): insert a session with N points and confirm reading
 * back from the DB returns N points with all fields intact (RF-07 acceptance criterion).
 */
@RunWith(AndroidJUnit4::class)
class TrackingSessionDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var sessionDao: TrackingSessionDao
    private lateinit var pointDao: GpsPointDao

    @Before
    fun createDb() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        sessionDao = database.trackingSessionDao()
        pointDao = database.gpsPointDao()
    }

    @After
    fun closeDb() {
        database.close()
    }

    @Test
    fun insertSessionWithNPoints_readBack_returnsAllPointsWithFieldsIntact() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        val startTimestamp = 1_700_000_000_000L
        val session = TrackingSessionEntity(
            id = sessionId,
            samplingIntervalSeconds = 15,
            startTimestamp = startTimestamp,
            endTimestamp = null,
            status = SessionStatus.ACTIVE,
        )
        sessionDao.insert(session)

        val n = 25
        val points = (0 until n).map { index ->
            GpsPointEntity(
                sessionId = sessionId,
                timestamp = startTimestamp + index * 15_000L,
                latitude = -23.550520 + index * 0.0001,
                longitude = -46.633308 + index * 0.0001,
                accuracy = 5.0f + index,
                observedIntervalDriftMillis = if (index == 0) null else (index * 37L),
            )
        }
        pointDao.insertAll(points)

        val storedSession = sessionDao.getSessionById(sessionId).first()
        assertNotNull(storedSession)
        assertEquals(session.id, storedSession!!.id)
        assertEquals(session.samplingIntervalSeconds, storedSession.samplingIntervalSeconds)
        assertEquals(session.startTimestamp, storedSession.startTimestamp)
        assertNull(storedSession.endTimestamp)
        assertEquals(SessionStatus.ACTIVE, storedSession.status)

        val storedPoints = pointDao.getPointsForSession(sessionId).first()
        assertEquals(n, storedPoints.size)
        assertEquals(n, pointDao.countForSession(sessionId))

        storedPoints.forEachIndexed { index, stored ->
            val expected = points[index]
            assertEquals(sessionId, stored.sessionId)
            assertEquals(expected.timestamp, stored.timestamp)
            assertEquals(expected.latitude, stored.latitude, 0.0000001)
            assertEquals(expected.longitude, stored.longitude, 0.0000001)
            assertEquals(expected.accuracy, stored.accuracy, 0.0001f)
            assertEquals(expected.observedIntervalDriftMillis, stored.observedIntervalDriftMillis)
        }
    }

    @Test
    fun locationNameAndDistanceMeters_defaultToNullAndZero_whenOmitted() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        sessionDao.insert(
            TrackingSessionEntity(
                id = sessionId,
                samplingIntervalSeconds = 10,
                startTimestamp = 500L,
            ),
        )

        val stored = sessionDao.getSessionById(sessionId).first()!!
        assertNull(stored.locationName)
        assertEquals(0.0, stored.distanceMeters, 0.0)
    }

    @Test
    fun locationNameAndDistanceMeters_roundTripThroughRoom() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        sessionDao.insert(
            TrackingSessionEntity(
                id = sessionId,
                samplingIntervalSeconds = 10,
                startTimestamp = 500L,
                locationName = "Downtown",
                distanceMeters = 1234.5,
            ),
        )

        val stored = sessionDao.getSessionById(sessionId).first()!!
        assertEquals("Downtown", stored.locationName)
        assertEquals(1234.5, stored.distanceMeters, 0.0001)
    }

    @Test
    fun finishingSession_persistsEndMetadata() = runBlocking {
        val sessionId = UUID.randomUUID().toString()
        sessionDao.insert(
            TrackingSessionEntity(
                id = sessionId,
                samplingIntervalSeconds = 30,
                startTimestamp = 1_000L,
            ),
        )

        val finished = TrackingSessionEntity(
            id = sessionId,
            samplingIntervalSeconds = 30,
            startTimestamp = 1_000L,
            endTimestamp = 61_000L,
            status = SessionStatus.FINISHED,
            stoppedTimeMillis = 20_000L,
            movingTimeMillis = 40_000L,
            averageSpeedMetersPerSecond = 1.5,
        )
        sessionDao.update(finished)

        val stored = sessionDao.getSessionById(sessionId).first()!!
        assertEquals(SessionStatus.FINISHED, stored.status)
        assertEquals(61_000L, stored.endTimestamp)
        assertEquals(20_000L, stored.stoppedTimeMillis)
        assertEquals(40_000L, stored.movingTimeMillis)
        assertEquals(1.5, stored.averageSpeedMetersPerSecond, 0.0001)
    }
}
