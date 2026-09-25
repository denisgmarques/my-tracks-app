package com.mytracksapp.logging

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T08 (RF-07, RF-08, RNF-03) — reads `FileLogger`'s on-disk log files for display in the
 * "Ver logs" screen.
 *
 * [read] runs entirely on [Dispatchers.IO], regardless of the caller's own dispatcher, so a
 * caller on `Dispatchers.Main` (e.g. a `ViewModel`'s `viewModelScope`) never blocks the UI thread
 * on file I/O (RF-07/RNF-03).
 *
 * Reuses [FileLogger]'s own [FileLogger.BACKUP_FILE_NAME]/[FileLogger.LOG_FILE_NAME] constants so
 * the reader can never drift from the writer's actual file names.
 */
class LogFileReader {

    suspend fun read(logsDir: File): String = withContext(Dispatchers.IO) {
        val backupFile = File(logsDir, FileLogger.BACKUP_FILE_NAME)
        val activeFile = File(logsDir, FileLogger.LOG_FILE_NAME)

        val backupContent = if (backupFile.exists()) backupFile.readText() else ""
        val activeContent = if (activeFile.exists()) activeFile.readText() else ""

        backupContent + activeContent
    }
}
