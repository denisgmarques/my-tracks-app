package com.mytracksapp.logging

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.time.Instant

/**
 * T01 (RF-02, RF-03, RNF-02, RNF-03) — a hand-rolled, append-only, size-rotated file logger.
 *
 * No external logging/crash-reporting dependency is used (RF-02/RF-11) — see
 * `NoExternalLoggingDependencyTest.kt` for the evidence test. Every log entry is written to
 * `<filesDir>/logs/app.log`; once that file reaches [MAX_LOG_FILE_SIZE_BYTES], it is rotated to
 * `<filesDir>/logs/app.log.1` (overwriting any previous backup) and a fresh `app.log` is started —
 * exactly one backup generation is ever kept.
 *
 * Concurrency (RNF-03): [log] is a plain synchronous (non-`suspend`) function guarded by a single
 * JVM monitor lock ([lock]) — the SAME critical section covers both the rotation-size check and
 * the write itself (RF-03: "not a separate, independently-locked check"), so no two callers can
 * ever observe/act on a stale pre-rotation size. This makes [log] safe to call from any thread,
 * including a plain background `Thread` with no coroutine context (e.g.
 * `Thread.setDefaultUncaughtExceptionHandler`, added in T02).
 *
 * [log] never throws to its caller: every internal failure (missing/failed [init], a rotation or
 * write I/O error, an unwritable target directory, ...) is caught and swallowed inside [log]
 * itself. This generalizes RF-01's explicit swallow rule for the `Application` uncaught-exception
 * handler to every other call site added under RF-04..RF-10/RF-12 — a `catch` block that calls
 * `logger.log(...)` can never turn into a *new* uncaught-throw site.
 *
 * Calling [log] before [init] (or if [init] itself failed) is a safe no-op — it neither writes
 * nor throws. [init] is expected to be called exactly once, from `MyTracksApplication.onCreate()`
 * (T02), but is safe to call again (e.g. from a test pointed at a fresh temp directory).
 */
object FileLogger : Logger {

    private const val LOG_DIR_NAME = "logs"
    private const val LOG_FILE_NAME = "app.log"
    private const val BACKUP_FILE_NAME = "app.log.1"

    /** Rotate once `app.log` reaches this size, per SPEC's "~1MB" sizing. */
    internal const val MAX_LOG_FILE_SIZE_BYTES = 1_048_576L

    /**
     * The single critical section guarding both the rotation-size check and the write (RF-03).
     * A plain `Any()` monitor (used via Kotlin's `synchronized`), NOT a `kotlinx.coroutines.sync.Mutex`
     * — this must be lockable from a plain `Thread`, not just from inside a coroutine.
     */
    private val lock = Any()

    private var logFile: File? = null
    private var backupFile: File? = null

    /**
     * Creates `context.filesDir/logs/` (if absent) and resolves the active/backup log file paths
     * inside it. Never throws — an unexpected I/O failure here leaves [logFile]/[backupFile]
     * unset, so subsequent [log] calls remain a safe no-op rather than crashing app startup.
     */
    fun init(context: Context) {
        try {
            val dir = File(context.filesDir, LOG_DIR_NAME)
            dir.mkdirs()
            synchronized(lock) {
                logFile = File(dir, LOG_FILE_NAME)
                backupFile = File(dir, BACKUP_FILE_NAME)
            }
        } catch (_: Throwable) {
            // Swallowed — see class doc: init() must not be able to crash app startup either.
        }
    }

    override fun log(level: LogLevel, tag: String, message: String, throwable: Throwable?) {
        try {
            val line = formatLine(level, tag, message, throwable)
            val bytes = line.toByteArray(Charsets.UTF_8)
            synchronized(lock) {
                val file = logFile ?: return
                if (file.exists() && file.length() >= MAX_LOG_FILE_SIZE_BYTES) {
                    rotate(file)
                }
                FileOutputStream(file, /* append = */ true).use { stream ->
                    stream.write(bytes)
                    stream.flush()
                    stream.fd.sync()
                }
            }
        } catch (_: Throwable) {
            // log() must never throw to its caller — see class doc.
        }
    }

    /**
     * `app.log` -> overwrite `app.log.1`, then remove `app.log` so the caller's subsequent
     * `FileOutputStream(file, append = true)` starts a fresh, empty active file. Must only ever
     * be called from inside [lock]'s critical section.
     */
    private fun rotate(file: File) {
        val backup = backupFile ?: return
        if (backup.exists()) {
            backup.delete()
        }
        file.copyTo(backup, overwrite = true)
        file.delete()
    }

    private fun formatLine(level: LogLevel, tag: String, message: String, throwable: Throwable?): String {
        val timestamp = Instant.now().toString()
        val builder = StringBuilder()
        builder.append(timestamp)
            .append(' ')
            .append(level.name)
            .append(' ')
            .append('[').append(escape(tag)).append(']')
            .append(' ')
            .append(escape(message))
        if (throwable != null) {
            builder.append(" | ").append(escape(throwable.stackTraceToString()))
        }
        builder.append('\n')
        return builder.toString()
    }

    /**
     * Escapes `\` -> `\\`, `\n` -> `\n` (literal backslash-n), `\r` -> `\r` (literal backslash-r)
     * so a [message]/stack trace containing embedded newlines is always emitted as exactly one
     * physical line, and is fully recoverable by reversing the same three substitutions.
     */
    private fun escape(text: String): String {
        val builder = StringBuilder(text.length)
        for (c in text) {
            when (c) {
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                else -> builder.append(c)
            }
        }
        return builder.toString()
    }
}
