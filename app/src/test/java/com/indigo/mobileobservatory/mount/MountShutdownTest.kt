package com.indigo.mobileobservatory.mount

import com.hoho.android.usbserial.driver.UsbSerialPort
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

class MountShutdownTest {
    @Test fun shutdownStopsMountBeforeReleasingPortAndRejectsQueuedMovement() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val port = Proxy.newProxyInstance(
            UsbSerialPort::class.java.classLoader,
            arrayOf(UsbSerialPort::class.java)
        ) { _, method, args ->
            when (method.name) {
                "write" -> events.add((args!![0] as ByteArray).toString(Charsets.US_ASCII))
                "close" -> events.add("close")
            }
            null
        } as UsbSerialPort
        val controller = Lx200MountController { 0L }
        Lx200MountController::class.java.getDeclaredField("serialPort").apply {
            isAccessible = true
            set(controller, port)
        }
        val closed = Lx200MountController::class.java.getDeclaredField("permanentlyClosed").apply {
            isAccessible = true
        }
        val write = Lx200MountController::class.java.getDeclaredMethod("writeBytes", ByteArray::class.java).apply {
            isAccessible = true
        }
        val writeFailure = AtomicReference<Throwable>()
        val closeFailure = AtomicReference<Throwable>()
        val writer = Thread {
            try {
                write.invoke(controller, ":Me#".toByteArray(Charsets.US_ASCII))
            } catch (failure: InvocationTargetException) {
                writeFailure.set(failure.targetException)
            } catch (failure: Throwable) {
                writeFailure.set(failure)
            }
        }.apply { isDaemon = true }
        val closer = Thread {
            try { controller.close() } catch (failure: Throwable) { closeFailure.set(failure) }
        }.apply { isDaemon = true }

        var writerBlocked = false
        var shutdownStarted = false
        synchronized(port) {
            writer.start()
            writerBlocked = awaitCondition { writer.state == Thread.State.BLOCKED }
            closer.start()
            shutdownStarted = awaitCondition { closed.getBoolean(controller) }
        }
        writer.join(5000)
        closer.join(5000)

        assertTrue("movement must be queued on the port", writerBlocked)
        assertTrue("shutdown must reject writes before sending STOP", shutdownStarted)
        assertFalse("movement writer did not finish", writer.isAlive)
        assertFalse("shutdown did not finish", closer.isAlive)
        assertNull(closeFailure.get())
        assertTrue(writeFailure.get() is IllegalStateException)
        assertEquals(listOf(":Q#", "close"), events)
    }

    private fun awaitCondition(condition: () -> Boolean): Boolean {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (System.nanoTime() < deadline) {
            if (condition()) return true
            Thread.sleep(5)
        }
        return condition()
    }
}
