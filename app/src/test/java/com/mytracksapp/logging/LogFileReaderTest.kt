package com.mytracksapp.logging

import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * T08 — [LogFileReader] behavior: backup-then-active concatenation (RF-08's chronological-ascending
 * order), single-file fallback, and the empty-state case. Plain JVM test — [LogFileReader] never
 * touches `Context`, so a real temp directory stands in for `<filesDir>/logs`.
 */
class LogFileReaderTest {

    private lateinit var logsDir: File
    private val reader = LogFileReader()

    @Before
    fun setUp() {
        val tempFile = File.createTempFile("log-file-reader-test", "")
        tempFile.delete()
        tempFile.mkdirs()
        logsDir = tempFile
    }

    @Test
    fun `both files present returns backup content followed by active content, nothing omitted`() = runBlocking {
        val backupContent = "backup-line-1\nbackup-line-2\n"
        val activeContent = "active-line-1\nactive-line-2\n"
        File(logsDir, FileLogger.BACKUP_FILE_NAME).writeText(backupContent)
        File(logsDir, FileLogger.LOG_FILE_NAME).writeText(activeContent)

        val result = reader.read(logsDir)

        assertTrue("result must start with the backup's content", result.startsWith(backupContent))
        assertTrue("result must end with the active file's content", result.endsWith(activeContent))
        assertEquals(backupContent + activeContent, result)
    }

    @Test
    fun `only active file present returns only its content`() = runBlocking {
        val activeContent = "only-active-content\n"
        File(logsDir, FileLogger.LOG_FILE_NAME).writeText(activeContent)

        val result = reader.read(logsDir)

        assertEquals(activeContent, result)
    }

    @Test
    fun `neither file present returns empty string`() = runBlocking {
        val result = reader.read(logsDir)

        assertEquals("", result)
    }
}
