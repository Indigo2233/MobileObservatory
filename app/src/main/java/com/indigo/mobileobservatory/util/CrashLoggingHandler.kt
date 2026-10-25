package com.indigo.mobileobservatory.util

/** Retains the full cause chain before Android's existing crash handler terminates the process. */
internal class CrashLoggingHandler(
    private val delegate: Thread.UncaughtExceptionHandler,
    private val write: (String) -> Unit
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, error: Throwable) {
        try { write("Uncaught exception on thread=${thread.name}\n${error.stackTraceToString()}") }
        catch (_: Throwable) { /* Reporting failure must not replace the original crash. */ }
        delegate.uncaughtException(thread, error)
    }
}
