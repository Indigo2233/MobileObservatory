package com.indigo.mobileobservatory.accessories

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.delay

enum class SerialAccessoryRole { FOCUSER, COVER, ROTATOR }

/**
 * Lightweight identity probes for USB-serial accessories.
 * Stops after the first positive match to avoid resetting the device with
 * further DTR/baud changes (important for electric CAA).
 */
object SerialAccessoryProbe {
    private const val TAG = "SerialAccessoryProbe"
    private const val CAA_SETTLE_MS = 4000L
    private const val FOCUSER_SETTLE_MS = 2200L
    private const val COVER_SETTLE_MS = 2200L
    private const val READ_TIMEOUT_MS = 2500
    private val FOCUSER_IDENTITY = Regex(
        "^EFucoser (?:ESP8266(?: ULN2003)?|Arduino Nano ULN2003) " +
            "Focuser ver (\\d+)$"
    )
    private val ROTATOR_IDENTITY = Regex("^V\\s+(\\d+)$")

    suspend fun probe(context: Context, device: UsbDevice): Set<SerialAccessoryRole> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (!usbManager.hasPermission(device)) return emptySet()
        val driver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == device.deviceId }
            ?: return emptySet()
        val connection = usbManager.openDevice(device) ?: return emptySet()
        val port = driver.ports.firstOrNull() ?: run {
            connection.close()
            return emptySet()
        }
        try {
            port.open(connection)

            // CAA first: same line mode as EcaaSerialRotatorAdapter (DTR/RTS high, long settle).
            port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { port.setDTR(true) }
            runCatching { port.setRTS(true) }
            delay(CAA_SETTLE_MS)
            drain(port)
            val rotatorId = exchangeHashTerminated(port, "V#")
            ROTATOR_IDENTITY.matchEntire(rotatorId)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?.takeIf { it in 1000..2999 }
                ?.let {
                    Log.i(TAG, "Probed CAA V$it on ${device.deviceName}")
                    return setOf(SerialAccessoryRole.ROTATOR)
                }

            // EFucoser uses DTR/RTS low after boot.
            runCatching { port.setDTR(false) }
            runCatching { port.setRTS(false) }
            delay(FOCUSER_SETTLE_MS)
            drain(port)
            val focuserId = exchangeHashTerminated(port, "#")
            if (FOCUSER_IDENTITY.matches(focuserId)) {
                Log.i(TAG, "Probed EFucoser on ${device.deviceName}")
                return setOf(SerialAccessoryRole.FOCUSER)
            }

            // DLCoverCalibrator uses 115200 framed commands.
            port.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { port.setDTR(false) }
            runCatching { port.setRTS(false) }
            delay(COVER_SETTLE_MS)
            drain(port)
            if (exchangeFrame(port, "Z") == "?") {
                Log.i(TAG, "Probed DLCoverCalibrator on ${device.deviceName}")
                return setOf(SerialAccessoryRole.COVER)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "Probe failed for ${device.deviceName}: ${e.message}")
        } finally {
            runCatching { port.close() }
            runCatching { connection.close() }
        }
        return emptySet()
    }

    private fun exchangeHashTerminated(port: UsbSerialPort, command: String): String {
        val framed = if (command.endsWith("#")) command else "$command#"
        port.write(framed.toByteArray(Charsets.US_ASCII), READ_TIMEOUT_MS)
        val response = ArrayList<Byte>()
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val count = port.read(buffer, 200)
            for (i in 0 until count) {
                val b = buffer[i].toInt() and 0xff
                if (b == '#'.code) {
                    val text = response.toByteArray().toString(Charsets.US_ASCII).trim()
                    if (text.isNotEmpty()) return text
                    response.clear()
                } else {
                    response += b.toByte()
                }
            }
        }
        return response.toByteArray().toString(Charsets.US_ASCII).trim()
    }

    private fun exchangeFrame(port: UsbSerialPort, code: String): String {
        port.write("<$code>".toByteArray(Charsets.US_ASCII), READ_TIMEOUT_MS)
        val response = ArrayList<Byte>()
        val buffer = ByteArray(256)
        var inFrame = false
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val count = port.read(buffer, 200)
            for (i in 0 until count) {
                when (val b = buffer[i].toInt() and 0xff) {
                    '<'.code -> {
                        response.clear()
                        inFrame = true
                    }
                    '>'.code -> if (inFrame) {
                        return response.toByteArray().toString(Charsets.US_ASCII).trim()
                    }
                    else -> if (inFrame) response += b.toByte()
                }
            }
        }
        return ""
    }

    private fun drain(port: UsbSerialPort) {
        val buffer = ByteArray(256)
        repeat(12) {
            if (runCatching { port.read(buffer, 80) }.getOrDefault(0) <= 0) return
        }
    }
}
