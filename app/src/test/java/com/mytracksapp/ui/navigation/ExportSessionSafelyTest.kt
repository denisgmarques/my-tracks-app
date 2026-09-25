package com.mytracksapp.ui.navigation

import com.mytracksapp.data.local.dao.GpsPointDao
import com.mytracksapp.data.local.dao.TrackingSessionDao
import com.mytracksapp.data.local.entity.GpsPointEntity
import com.mytracksapp.data.local.entity.SessionStatus
import com.mytracksapp.data.local.entity.TrackingSessionEntity
import com.mytracksapp.domain.export.ExportFormat
import com.mytracksapp.domain.export.ExportService
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [Logger] double — records every logged entry for assertions. */
private class FakeLoggerForExport : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/** [TrackingSessionDao] double whose session lookup is configurable per test. */
private class FakeTrackingSessionDao(
    private val sessionResult: () -> TrackingSessionEntity? = { null },
) : TrackingSessionDao {
    override suspend fun insert(session: TrackingSessionEntity) = Unit
    override suspend fun update(session: TrackingSessionEntity) = Unit
    override fun getSessionById(sessionId: String): Flow<TrackingSessionEntity?> = flowOf(sessionResult())
    override fun getAllSessions(): Flow<List<TrackingSessionEntity>> = flowOf(emptyList())
    override fun getSessionsByStatus(status: SessionStatus): Flow<List<TrackingSessionEntity>> = flowOf(emptyList())
    override suspend fun deleteById(sessionId: String) = Unit
    override suspend fun deleteAll() = Unit
    override suspend fun getFinishedSessionsWithoutLocationNameSince(
        status: SessionStatus,
        sinceTimestamp: Long,
    ): List<TrackingSessionEntity> = emptyList()
    override suspend fun updateLocationName(sessionId: String, locationName: String?) = Unit
}

/** [GpsPointDao] double whose points lookup is configurable per test (default: throws). */
private class FakeGpsPointDao(
    private val pointsResult: () -> List<GpsPointEntity> = { throw RuntimeException("Simulated generic export failure") },
) : GpsPointDao {
    override suspend fun insert(point: GpsPointEntity): Long = 0L
    override suspend fun insertAll(points: List<GpsPointEntity>): List<Long> = emptyList()
    override fun getPointsForSession(sessionId: String): Flow<List<GpsPointEntity>> = flowOf(pointsResult())
    override suspend fun countForSession(sessionId: String): Int = 0
}

private fun finishedSession(sessionId: String) = TrackingSessionEntity(
    id = sessionId,
    samplingIntervalSeconds = 10,
    startTimestamp = 0L,
    endTimestamp = 10_000L,
    status = SessionStatus.FINISHED,
)

private fun activeSession(sessionId: String) = TrackingSessionEntity(
    id = sessionId,
    samplingIntervalSeconds = 10,
    startTimestamp = 0L,
    endTimestamp = null,
    status = SessionStatus.ACTIVE,
)

/**
 * T08 — [exportSessionSafely] unit tests (RF-08): a real [ExportService] backed by fake DAOs is
 * used to trigger each of its documented thrown-exception types (plus a generic failure), since
 * [ExportService] itself has no seam to fake a throw directly. Pure JVM — no Compose rule needed,
 * matching PLAN.md's T08 test note.
 */
class ExportSessionSafelyTest {

    private val exportsDir = File("build/tmp/exportSessionSafelyTest")

    @Test
    fun `NoSuchElementException from ExportService is logged and does not escape`() = runBlocking {
        val logger = FakeLoggerForExport()
        val exportService = ExportService(FakeTrackingSessionDao(), FakeGpsPointDao())

        exportSessionSafely(
            exportService = exportService,
            sessionId = "missing-session",
            format = ExportFormat.GPX,
            exportsDir = exportsDir,
            logger = logger,
        )

        assertEquals(1, logger.entries.size)
        val entry = logger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("SessionDetailExport", entry.tag)
        assertTrue(entry.throwable is NoSuchElementException)
    }

    @Test
    fun `IllegalStateException from ExportService (session not finished) is logged and does not escape`() =
        runBlocking {
            val logger = FakeLoggerForExport()
            val sessionId = "unfinished-session"
            val exportService = ExportService(
                FakeTrackingSessionDao(sessionResult = { activeSession(sessionId) }),
                FakeGpsPointDao(),
            )

            exportSessionSafely(
                exportService = exportService,
                sessionId = sessionId,
                format = ExportFormat.CSV,
                exportsDir = exportsDir,
                logger = logger,
            )

            assertEquals(1, logger.entries.size)
            val entry = logger.entries.single()
            assertEquals(LogLevel.ERROR, entry.level)
            assertEquals("SessionDetailExport", entry.tag)
            assertTrue(entry.throwable is IllegalStateException)
        }

    @Test
    fun `a generic export failure is logged and does not escape`() = runBlocking {
        val logger = FakeLoggerForExport()
        val sessionId = "finished-session"
        val exportService = ExportService(
            FakeTrackingSessionDao(sessionResult = { finishedSession(sessionId) }),
            FakeGpsPointDao(), // default pointsResult throws a generic RuntimeException
        )

        exportSessionSafely(
            exportService = exportService,
            sessionId = sessionId,
            format = ExportFormat.GPX,
            exportsDir = exportsDir,
            logger = logger,
        )

        assertEquals(1, logger.entries.size)
        val entry = logger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("SessionDetailExport", entry.tag)
        assertTrue(entry.throwable is RuntimeException)
    }

    @Test
    fun `IllegalArgumentException raised from the export path is logged and does not escape`() = runBlocking {
        val logger = FakeLoggerForExport()
        val sessionId = "finished-session-bad-args"
        val exportService = ExportService(
            FakeTrackingSessionDao(
                sessionResult = { throw IllegalArgumentException("Simulated bad argument") },
            ),
            FakeGpsPointDao(),
        )

        exportSessionSafely(
            exportService = exportService,
            sessionId = sessionId,
            format = ExportFormat.CSV,
            exportsDir = exportsDir,
            logger = logger,
        )

        assertEquals(1, logger.entries.size)
        val entry = logger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("SessionDetailExport", entry.tag)
        assertTrue(entry.throwable is IllegalArgumentException)
    }
}
