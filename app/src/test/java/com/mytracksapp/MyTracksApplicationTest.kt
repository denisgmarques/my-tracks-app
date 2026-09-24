package com.mytracksapp

import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T02 (RF-01) — [buildUncaughtExceptionHandler]'s delegation logic. Runs the handler under test
 * against a plain background [Thread] (not the JVM's real default uncaught-exception handler)
 * via [Thread.setUncaughtExceptionHandler] on that one instance, so the test never touches global
 * JVM state shared with other tests.
 */
class MyTracksApplicationTest {

    /** Records every [log] call, in order, alongside a shared [events] trail with [FakePreviousHandler]. */
    private class FakeLogger(private val events: MutableList<String>) : Logger {
        var shouldThrow = false
        val entries = mutableListOf<LogEntry>()

        data class LogEntry(
            val level: LogLevel,
            val tag: String,
            val message: String,
            val throwable: Throwable?,
        )

        override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
            events += "log"
            if (shouldThrow) throw RuntimeException("logger failure")
            entries += LogEntry(level, tag, message, throwable)
        }
    }

    /** Records invocations, in order, alongside a shared [events] trail with [FakeLogger]. */
    private class FakePreviousHandler(private val events: MutableList<String>) : Thread.UncaughtExceptionHandler {
        var invocationCount = 0
        var invokedThread: Thread? = null
        var invokedThrowable: Throwable? = null

        override fun uncaughtException(t: Thread, e: Throwable) {
            events += "previousHandler"
            invocationCount++
            invokedThread = t
            invokedThrowable = e
        }
    }

    private fun runOnBackgroundThread(
        handler: Thread.UncaughtExceptionHandler,
        throwable: Throwable,
    ): Thread {
        val thread = Thread { throw throwable }
        thread.setUncaughtExceptionHandler(handler)
        thread.start()
        thread.join()
        return thread
    }

    @Test
    fun `logs exactly one error entry before delegating to the previous handler`() {
        val events = mutableListOf<String>()
        val fakeLogger = FakeLogger(events)
        val fakePreviousHandler = FakePreviousHandler(events)
        val handler = buildUncaughtExceptionHandler(fakeLogger, fakePreviousHandler)

        val thrown = RuntimeException("boom")
        val thread = runOnBackgroundThread(handler, thrown)

        assertEquals(1, fakeLogger.entries.size)
        val entry = fakeLogger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertTrue(entry.message.contains("RuntimeException"))
        assertTrue(entry.message.contains("boom"))
        assertEquals(thrown, entry.throwable)

        assertEquals(1, fakePreviousHandler.invocationCount)
        assertEquals(thread, fakePreviousHandler.invokedThread)
        assertEquals(thrown, fakePreviousHandler.invokedThrowable)

        assertEquals(listOf("log", "previousHandler"), events)
    }

    @Test
    fun `still delegates to the previous handler with the same thread and throwable when logging itself throws`() {
        val events = mutableListOf<String>()
        val fakeLogger = FakeLogger(events).apply { shouldThrow = true }
        val fakePreviousHandler = FakePreviousHandler(events)
        val handler = buildUncaughtExceptionHandler(fakeLogger, fakePreviousHandler)

        val thrown = IllegalStateException("kaboom")
        val currentThread = Thread.currentThread()

        // Invoke the handler directly (not via a spawned Thread's crash-dispatch machinery) so a
        // failure to swallow the logger's exception fails this test with a thrown exception,
        // rather than being silently absorbed by the JVM's thread-crash handling.
        handler.uncaughtException(currentThread, thrown)

        assertEquals(1, fakePreviousHandler.invocationCount)
        assertEquals(currentThread, fakePreviousHandler.invokedThread)
        assertEquals(thrown, fakePreviousHandler.invokedThrowable)
    }
}
