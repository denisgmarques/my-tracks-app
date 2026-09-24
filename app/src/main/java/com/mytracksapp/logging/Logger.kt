package com.mytracksapp.logging

/**
 * Severity levels for a [Logger.log] entry, ordered least to most severe.
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
}

/**
 * A minimal, dependency-free logging seam (T01 of
 * `.spec/features/exception-handling-and-error-logging/PLAN.md` — "Key design decision").
 *
 * [FileLogger] is the production singleton implementation; every other RF-04..RF-10/RF-12 call
 * site takes a trailing, defaulted `logger: Logger = FileLogger` constructor parameter so tests
 * can inject a fake in place of real file I/O.
 *
 * [log] is a plain synchronous (non-`suspend`) function — it must be callable from any context,
 * including a plain background `Thread` (e.g. `Thread.setDefaultUncaughtExceptionHandler`, which
 * runs outside any coroutine). Implementations MUST NOT let [log] throw to its caller: every
 * call site across this app relies on being able to invoke [log] safely from inside a `catch`
 * block without risking a new, uncaught, secondary exception.
 */
interface Logger {
    fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null)
}
