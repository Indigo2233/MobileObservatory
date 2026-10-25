package com.indigo.mobileobservatory.util

/** Persist the complete cause chain, then retain Android's normal crash handling. */
internal class UncaughtCrashLogger(
    private val persist: (String) -> Unit,
    private val delegate: Thread.UncaughtExceptionHandler
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, failure: Throwable) {
        try {
            persist("Uncaught exception on thread ${thread.name}\n${failure.stackTraceToString()}")
        } catch (_: Throwable) {
            // Logging failure must not prevent Android from terminating the crashed process.
        }
        delegate.uncaughtException(thread, failure)
    }
}
