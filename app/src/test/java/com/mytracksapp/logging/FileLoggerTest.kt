package com.mytracksapp.logging

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * T01 — [FileLogger] core behavior: append-only writes, newline escaping, durability, rotation,
 * and concurrency safety. Runs on the JVM via Robolectric (needed only to obtain a real
 * [android.content.Context]/`filesDir`; [FileLogger] itself has no other Android dependency).
 *
 * [FileLogger] is a singleton `object`, so [setUp] re-[FileLogger.init]s it against each test's
 * own fresh Robolectric `filesDir` and clears any stray `app.log`/`app.log.1` before every test,
 * keeping tests independent despite the shared static instance.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FileLoggerTest {

    private lateinit var logsDir: java.io.File
    private lateinit var logFile: java.io.File
    private lateinit var backupFile: java.io.File

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        FileLogger.init(context)
        logsDir = java.io.File(context.filesDir, "logs")
        logFile = java.io.File(logsDir, "app.log")
        backupFile = java.io.File(logsDir, "app.log.1")
        logFile.delete()
        backupFile.delete()
    }

    @Test
    fun `50 concurrent log calls from 4 or more dispatchers produce 50 complete non-interleaved lines`() {
        val customA = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val customB = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        try {
            val dispatchers = listOf(Dispatchers.Default, Dispatchers.IO, customA, customB)
            runBlocking {
                val jobs = (0 until 50).map { i ->
                    async(dispatchers[i % dispatchers.size]) {
                        FileLogger.log(LogLevel.INFO, "ConcurrencyTest", "message-$i")
                    }
                }
                jobs.awaitAll()
            }
        } finally {
            customA.close()
            customB.close()
        }

        val lines = logFile.readLines()
        assertEquals(50, lines.size)

        val linePattern = Regex("^\\S+ INFO \\[ConcurrencyTest\\] message-\\d+$")
        lines.forEach { line ->
            assertTrue("line was not a complete, well-formed, non-interleaved entry: $line", linePattern.matches(line))
        }

        val messageNumbers = lines.map { line -> line.substringAfterLast("message-").toInt() }.sorted()
        assertEquals((0 until 50).toList(), messageNumbers)
    }

    @Test
    fun `throwable with multi-line stack trace produces exactly one physical line with recoverable escapes`() {
        val throwable = try {
            throw IllegalStateException("boom\nwith an embedded newline\rand a carriage return")
        } catch (e: IllegalStateException) {
            e
        }

        FileLogger.log(LogLevel.ERROR, "StackTraceTest", "failure occurred", throwable)

        val lines = logFile.readLines()
        assertEquals(1, lines.size)
        val line = lines.single()

        val rawStackTrace = throwable.stackTraceToString()
        assertTrue("test setup sanity check: stack trace must actually be multi-line", rawStackTrace.contains('\n'))
        assertTrue("persisted entry must not contain a raw newline", !line.contains('\n'))
        assertTrue("persisted entry must not contain a raw carriage return", !line.contains('\r'))

        val recoveredStackTrace = unescape(line.substringAfter(" | "))
        assertEquals(rawStackTrace, recoveredStackTrace)
    }

    @Test
    fun `log entry is durable and readable immediately after log returns with no explicit close`() {
        FileLogger.log(LogLevel.INFO, "DurabilityTest", "durable entry")

        // A fresh, independent read — log() must have already flushed, fsync'd and closed its
        // own stream before returning; this test performs no close of its own.
        val content = logFile.readText()
        assertTrue(content.contains("durable entry"))
    }

    @Test
    fun `writing past the size threshold rotates before the active file exceeds threshold plus one entry`() {
        val fillerMessage = "x".repeat(1000)
        var previousActiveLength = 0L
        var lastEntryLength = 0L
        var i = 0

        while (!backupFile.exists()) {
            FileLogger.log(LogLevel.INFO, "RotationTest", "$fillerMessage-$i")
            if (!backupFile.exists()) {
                val currentActiveLength = logFile.length()
                lastEntryLength = currentActiveLength - previousActiveLength
                previousActiveLength = currentActiveLength
            }
            i++
            require(i < 5000) { "rotation never triggered within 5000 entries" }
        }

        assertTrue(
            "rotated file size ${backupFile.length()} should be >= threshold " +
                "(${FileLogger.MAX_LOG_FILE_SIZE_BYTES})",
            backupFile.length() >= FileLogger.MAX_LOG_FILE_SIZE_BYTES,
        )
        assertTrue(
            "rotated file size ${backupFile.length()} should be < threshold + one entry " +
                "(${FileLogger.MAX_LOG_FILE_SIZE_BYTES + lastEntryLength})",
            backupFile.length() < FileLogger.MAX_LOG_FILE_SIZE_BYTES + lastEntryLength,
        )

        val activeSizeAfterRotation = logFile.length()
        assertTrue(activeSizeAfterRotation < FileLogger.MAX_LOG_FILE_SIZE_BYTES)

        FileLogger.log(LogLevel.INFO, "RotationTest", "post-rotation-entry")
        assertTrue(logFile.length() > activeSizeAfterRotation)
        assertTrue(logFile.readText().contains("post-rotation-entry"))
    }

    @Test
    fun `two successive rotations leave exactly one backup holding only the second rotation's content`() {
        val fillerMessage = "x".repeat(2000)
        var i = 0

        fun driveUntilNextRotation(): String {
            val backupBefore = if (backupFile.exists()) backupFile.readText() else null
            while (true) {
                FileLogger.log(LogLevel.INFO, "RotationTest", "entry-$i-$fillerMessage")
                i++
                require(i < 20_000) { "rotation never triggered within 20000 entries" }
                if (backupFile.exists()) {
                    val backupNow = backupFile.readText()
                    if (backupNow != backupBefore) return backupNow
                }
            }
        }

        val firstBackupContent = driveUntilNextRotation()
        driveUntilNextRotation()

        val logsDirFileNames = logsDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        assertEquals(listOf("app.log", "app.log.1"), logsDirFileNames)

        val currentBackupContent = backupFile.readText()
        assertTrue(
            "second rotation must have replaced the backup's content",
            currentBackupContent != firstBackupContent,
        )
        assertTrue(
            "the first rotation's content must be entirely gone from the current backup",
            !currentBackupContent.contains(firstBackupContent.lineSequence().first()),
        )
    }

    @Test
    fun `concurrent writes across a rotation boundary preserve every entry with exactly one rotation`() {
        val fillerMessage = "x".repeat(1000)

        // Single-threaded, deterministic prefill to just under the rotation threshold.
        var i = 0
        while (logFile.length() < FileLogger.MAX_LOG_FILE_SIZE_BYTES - 5_000) {
            FileLogger.log(LogLevel.INFO, "PreFill", "prefill-$i-$fillerMessage")
            i++
            require(i < 5000) { "prefill loop ran away" }
        }
        val prefillCount = i

        // Push across the threshold with a concurrent burst from multiple threads/dispatchers.
        val concurrentCount = 20
        val dispatcher = Executors.newFixedThreadPool(8).asCoroutineDispatcher()
        try {
            runBlocking {
                val jobs = (0 until concurrentCount).map { j ->
                    async(dispatcher) {
                        FileLogger.log(LogLevel.INFO, "BurstTest", "burst-$j-$fillerMessage")
                    }
                }
                jobs.awaitAll()
            }
        } finally {
            dispatcher.close()
        }

        // Exactly one rotation occurred: exactly one backup file, no app.log.2/3/....
        val logsDirFileNames = logsDir.listFiles()?.map { it.name }?.sorted() ?: emptyList()
        assertEquals(listOf("app.log", "app.log.1"), logsDirFileNames)

        // No entry was lost or duplicated across the rotation boundary.
        val allLines = backupFile.readLines() + logFile.readLines()
        assertEquals(prefillCount + concurrentCount, allLines.size)

        val burstIndices = allLines
            .filter { it.contains("[BurstTest]") }
            .map { it.substringAfter("burst-").substringBefore("-x").toInt() }
            .sorted()
        assertEquals((0 until concurrentCount).toList(), burstIndices)
    }

    @Test
    fun `log swallows an internal write failure instead of throwing`() {
        // Force every write to fail regardless of OS-level file permissions (which a sandboxed
        // or root test process could otherwise ignore): replace the expected log file path with
        // a directory, so FileOutputStream(file, append = true) can never open it for writing.
        logFile.delete()
        assertTrue(logFile.mkdirs())

        // Must not throw.
        FileLogger.log(LogLevel.ERROR, "FailureTest", "this must not throw even though the write fails")

        assertTrue("the planted directory should be untouched by the swallowed failure", logFile.isDirectory)
    }

    /** Reverses [FileLogger]'s own escape scheme, for test assertions only. */
    private fun unescape(text: String): String {
        val builder = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                when (text[i + 1]) {
                    '\\' -> {
                        builder.append('\\')
                        i += 2
                    }
                    'n' -> {
                        builder.append('\n')
                        i += 2
                    }
                    'r' -> {
                        builder.append('\r')
                        i += 2
                    }
                    else -> {
                        builder.append(c)
                        i += 1
                    }
                }
            } else {
                builder.append(c)
                i += 1
            }
        }
        return builder.toString()
    }
}
