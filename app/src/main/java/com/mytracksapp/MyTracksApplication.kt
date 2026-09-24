package com.mytracksapp

import android.app.Application
import com.mytracksapp.logging.FileLogger
import com.mytracksapp.logging.LogLevel
import com.mytracksapp.logging.Logger

/**
 * T02 (RF-01) — the app's [Application] subclass. Its sole job is process-wide crash
 * containment: initialize [FileLogger] once (see `.spec/features/exception-handling-and-error-logging/PLAN.md`'s
 * "Key design decision") and install a global [Thread.UncaughtExceptionHandler] that logs the
 * crash locally before always delegating to whatever handler was previously installed (the
 * system default, unless something else set one earlier).
 */
class MyTracksApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        FileLogger.init(applicationContext)

        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(
            buildUncaughtExceptionHandler(FileLogger, previousHandler),
        )
    }
}

/**
 * Builds the [Thread.UncaughtExceptionHandler] installed by [MyTracksApplication.onCreate].
 * Factored out as a top-level, internal function so it is directly unit-testable without a real
 * [Application]/`Robolectric` instance (see `MyTracksApplicationTest.kt`).
 *
 * Logging the crash and delegating to [previousHandler] are two independent steps: a failure
 * while logging (e.g. [logger] itself throwing) is caught and swallowed here so it can never
 * suppress delegation — the call to `previousHandler?.uncaughtException(...)` sits outside the
 * try/catch and always runs, unconditionally, for every uncaught exception on every thread.
 */
internal fun buildUncaughtExceptionHandler(
    logger: Logger,
    previousHandler: Thread.UncaughtExceptionHandler?,
): Thread.UncaughtExceptionHandler =
    Thread.UncaughtExceptionHandler { thread, throwable ->
        try {
            logger.log(
                LogLevel.ERROR,
                "UncaughtException",
                "${throwable::class.java.name}: ${throwable.message}",
                throwable,
            )
        } catch (loggingFailure: Throwable) {
            // Swallowed — logging must never prevent delegation to previousHandler below.
        }
        previousHandler?.uncaughtException(thread, throwable)
    }
