package com.mytracksapp.ui.navigation

import com.mytracksapp.domain.model.SamplingInterval
import com.mytracksapp.domain.session.SessionController
import com.mytracksapp.domain.session.SessionStartOutcome
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [Logger] double — records every logged entry for assertions. */
private class FakeLoggerForFinishSession : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/** In-memory [SessionController] double whose [stopSession] can be made to throw on demand. */
private class FakeSessionController : SessionController {
    var stopSessionCalls = mutableListOf<String>()
    var exceptionToThrow: Exception? = null

    override suspend fun startSession(interval: SamplingInterval): SessionStartOutcome =
        error("not used by this test")

    override suspend fun stopSession(sessionId: String) {
        stopSessionCalls += sessionId
        exceptionToThrow?.let { throw it }
    }
}

/**
 * T14 — [finishSessionSafely] unit tests (RF-12): pure JVM, no Compose rule needed, same shape as
 * [ExportSessionSafelyTest].
 */
class FinishSessionSafelyTest {

    @Test
    fun `a stopSession failure is logged, does not escape, and does not invoke onSessionFinished`() = runBlocking {
        val logger = FakeLoggerForFinishSession()
        val sessionController = FakeSessionController().apply {
            exceptionToThrow = IllegalStateException("Simulated stopSession failure")
        }
        var onSessionFinishedCallCount = 0

        finishSessionSafely(
            sessionController = sessionController,
            sessionId = "session-under-test",
            onSessionFinished = { onSessionFinishedCallCount++ },
            logger = logger,
        )

        assertEquals(1, logger.entries.size)
        val entry = logger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("TrackingSessionFinish", entry.tag)
        assertTrue(entry.throwable is IllegalStateException)
        assertEquals(0, onSessionFinishedCallCount)
    }

    @Test
    fun `a successful stopSession invokes onSessionFinished exactly once and logs nothing`() = runBlocking {
        val logger = FakeLoggerForFinishSession()
        val sessionController = FakeSessionController()
        var onSessionFinishedCallCount = 0

        finishSessionSafely(
            sessionController = sessionController,
            sessionId = "session-under-test",
            onSessionFinished = { onSessionFinishedCallCount++ },
            logger = logger,
        )

        assertTrue(logger.entries.isEmpty())
        assertEquals(1, onSessionFinishedCallCount)
        assertEquals(listOf("session-under-test"), sessionController.stopSessionCalls)
    }
}
