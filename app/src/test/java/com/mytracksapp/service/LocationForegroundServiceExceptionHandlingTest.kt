package com.mytracksapp.service

import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory [Logger] double — records every logged entry for assertions (T04). */
private class FakeLoggerForServiceScope : Logger {
    data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    val entries = mutableListOf<Entry>()

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        entries += Entry(level, tag, message, throwable)
    }
}

/**
 * T04 (RF-06) — [serviceScopeExceptionHandler] unit tests. Constructs the exact same shape
 * [LocationForegroundService.serviceScope] is built with —
 * `CoroutineScope(Dispatchers.Default + SupervisorJob() + serviceScopeExceptionHandler(...))` —
 * directly, with no real `Service` instance needed. Pure JVM.
 */
class LocationForegroundServiceExceptionHandlingTest {

    @Test
    fun `an uncaught coroutine exception on serviceScope is logged exactly once`() {
        val fakeLogger = FakeLoggerForServiceScope()
        val serviceJob = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + serviceJob + serviceScopeExceptionHandler(fakeLogger))
        val failure = IllegalStateException("boom")

        val job = scope.launch { throw failure }
        runBlocking { job.join() }

        assertEquals(1, fakeLogger.entries.size)
        val entry = fakeLogger.entries.single()
        assertEquals(LogLevel.ERROR, entry.level)
        assertEquals("LocationForegroundService", entry.tag)
        assertSame(failure, entry.throwable)

        serviceJob.cancel()
    }

    @Test
    fun `the scope survives an uncaught child failure and keeps accepting work`() {
        val fakeLogger = FakeLoggerForServiceScope()
        val serviceJob = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.Default + serviceJob + serviceScopeExceptionHandler(fakeLogger))

        val firstJob = scope.launch { throw IllegalStateException("first coroutine fails") }
        runBlocking { firstJob.join() }

        // The hosting scope/process must not terminate: a coroutine launched afterwards on the
        // very same scope still runs to completion (backed by the SupervisorJob, unaffected by
        // the sibling's uncaught failure and this handler's non-rethrowing contract).
        var ran = false
        val secondJob = scope.launch { ran = true }
        runBlocking { secondJob.join() }

        assertTrue(ran)
        assertEquals(1, fakeLogger.entries.size)

        serviceJob.cancel()
    }
}
