package com.indigo.mobileobservatory.mount

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RfcommConnectionRunnerTest {
    private data class FakeSocket(val mode: RfcommMode, var closed: Boolean = false)

    @Test
    fun standardFailureFallsBackToCompatibleRfcomm() = runTest {
        val stages = mutableListOf<RfcommMode>()
        val sockets = mutableListOf<FakeSocket>()
        val runner = RfcommConnectionRunner(
            timeoutMs = 1_000,
            createSocket = { mode -> FakeSocket(mode).also(sockets::add) },
            connectSocket = { socket ->
                if (socket.mode == RfcommMode.STANDARD) error("standard failed")
            },
            closeSocket = { it.closed = true },
            onStage = stages::add
        )

        val connected = runner.connect()

        assertEquals(RfcommMode.COMPATIBLE, connected.mode)
        assertEquals(listOf(RfcommMode.STANDARD, RfcommMode.COMPATIBLE), stages)
        assertTrue(sockets.first().closed)
    }

    @Test
    fun socketCreationFailureFallsBackToCompatibleRfcomm() = runTest {
        val stages = mutableListOf<RfcommMode>()
        val runner = RfcommConnectionRunner(
            timeoutMs = 1_000,
            createSocket = { mode ->
                if (mode == RfcommMode.STANDARD) error("socket creation failed")
                FakeSocket(mode)
            },
            connectSocket = {},
            closeSocket = { it.closed = true },
            onStage = stages::add
        )

        val connected = runner.connect()

        assertEquals(RfcommMode.COMPATIBLE, connected.mode)
        assertEquals(listOf(RfcommMode.STANDARD, RfcommMode.COMPATIBLE), stages)
    }

    @Test
    fun standardTimeoutFallsBackToCompatibleRfcomm() = runTest {
        val sockets = mutableListOf<FakeSocket>()
        val runner = RfcommConnectionRunner(
            timeoutMs = 1_000,
            createSocket = { mode -> FakeSocket(mode).also(sockets::add) },
            connectSocket = { socket ->
                if (socket.mode == RfcommMode.STANDARD) awaitCancellation()
            },
            closeSocket = { it.closed = true }
        )

        val connected = runner.connect()

        assertEquals(RfcommMode.COMPATIBLE, connected.mode)
        assertTrue(sockets.first().closed)
    }
    @Test
    fun timedOutAttemptClosesSocket() = runTest {
        val sockets = mutableListOf<FakeSocket>()
        val runner = RfcommConnectionRunner(
            timeoutMs = 1_000,
            modes = listOf(RfcommMode.STANDARD),
            createSocket = { mode -> FakeSocket(mode).also(sockets::add) },
            connectSocket = { awaitCancellation() },
            closeSocket = { it.closed = true }
        )

        val result = runCatching { runner.connect() }

        assertTrue(result.exceptionOrNull() is RfcommConnectionTimeoutException)
        assertTrue(sockets.single().closed)
    }

    @Test
    fun cancellationClosesCurrentSocketWithoutFallback() = runTest {
        val stages = mutableListOf<RfcommMode>()
        val sockets = mutableListOf<FakeSocket>()
        val runner = RfcommConnectionRunner(
            timeoutMs = 10_000,
            createSocket = { mode -> FakeSocket(mode).also(sockets::add) },
            connectSocket = { awaitCancellation() },
            closeSocket = { it.closed = true },
            onStage = stages::add
        )

        val job = launch { runner.connect() }
        runCurrent()
        job.cancel()
        job.join()

        assertEquals(listOf(RfcommMode.STANDARD), stages)
        assertEquals(1, sockets.size)
        assertTrue(sockets.single().closed)
    }
}
