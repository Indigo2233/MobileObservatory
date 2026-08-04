package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

enum class RfcommMode {
    STANDARD,
    COMPATIBLE
}

class RfcommConnectionTimeoutException(
    timeoutMs: Long,
    cause: Throwable? = null
) : Exception("Bluetooth connection timed out after ${timeoutMs / 1_000} seconds.", cause)

/** Tries RFCOMM modes in order. Failed, timed-out, or cancelled sockets always close. */
class RfcommConnectionRunner<S>(
    private val timeoutMs: Long,
    private val modes: List<RfcommMode> = RfcommMode.entries,
    private val createSocket: (RfcommMode) -> S,
    private val connectSocket: suspend (S) -> Unit,
    private val closeSocket: (S) -> Unit,
    private val onStage: (RfcommMode) -> Unit = {}
) {
    suspend fun connect(): S {
        var lastFailure: Throwable? = null
        for (mode in modes) {
            onStage(mode)
            val socket = try {
                createSocket(mode)
            } catch (failure: Throwable) {
                lastFailure = failure
                continue
            }
            try {
                withTimeout(timeoutMs) { connectSocket(socket) }
                return socket
            } catch (cancelled: CancellationException) {
                closeSocket(socket)
                if (cancelled is TimeoutCancellationException) {
                    lastFailure = RfcommConnectionTimeoutException(timeoutMs, cancelled)
                    continue
                }
                throw cancelled
            } catch (failure: Throwable) {
                closeSocket(socket)
                lastFailure = failure
            }
        }
        throw lastFailure ?: IllegalStateException("No Bluetooth RFCOMM mode is available.")
    }
}
