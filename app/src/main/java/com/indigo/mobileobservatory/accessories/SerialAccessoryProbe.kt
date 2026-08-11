package com.indigo.mobileobservatory.accessories

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.indigo.mobileobservatory.accessories.rotator.WandererRotatorProtocol
import kotlinx.coroutines.delay

enum class SerialAccessoryRole {
    FOCUSER,
    GEMINI_EAF,
    COVER,
    GEMINI_FLAT,
    ROTATOR,
    WANDERER_ROTATOR
}

/**
 * Lightweight identity probes for USB-serial accessories.
 * Stops after the first positive match to avoid resetting the device with
 * further DTR/baud changes (important for electric CAA).
 */
object SerialAccessoryProbe {
    private const val TAG = "SerialAccessoryProbe"
    private const val CAA_SETTLE_MS = 4000L
    private const val FOCUSER_SETTLE_MS = 2200L
    private const val GEMINI_EAF_SETTLE_MS = 1000L
    private const val COVER_SETTLE_MS = 2200L
    private const val WANDERER_VENDOR_ID = 0x1a86
    private const val WANDERER_SETTLE_MS = 500L
    private const val WANDERER_READ_TIMEOUT_MS = 3000
    private const val READ_TIMEOUT_MS = 2500
    private const val DRAIN_LIMIT_MS = 1500L
    private const val DRAIN_QUIET_READS = 3

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

            // WandererRotator devices commonly use CH340 USB serial adapters.
            // Changing CH340 control lines can reset the controller, so preserve
            // the open-time DTR/RTS state throughout this exchange.
            if (device.vendorId == WANDERER_VENDOR_ID) {
                port.setParameters(
                    WandererRotatorProtocol.baudRate,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                delay(WANDERER_SETTLE_MS)
                drain(port)
                val handshake = exchangeWandererHandshake(port)
                if (handshake != null) {
                    Log.i(TAG, "Probed ${handshake.model.displayName} on ${device.deviceName}")
                    return setOf(SerialAccessoryRole.WANDERER_ROTATOR)
                }
            }

            // CAA first: same line mode as EcaaSerialRotatorAdapter (DTR/RTS high, long settle).
            port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { port.setDTR(true) }
            runCatching { port.setRTS(true) }
            delay(CAA_SETTLE_MS)
            drain(port)
            // The empty command separates the two families: EFucoser answers its
            // banner, the CAA answers an error. Rule the focuser out first so the
            // shared `V#` / `G#` replies below can only come from a CAA.
            val emptyReply = exchangeHashTerminated(port, "#")
            if (SerialAccessoryIdentity.isFocuserBanner(emptyReply)) {
                Log.i(TAG, "Probed EFucoser on ${device.deviceName}")
                return setOf(SerialAccessoryRole.FOCUSER)
            }
            drain(port)
            val rotatorVersion = SerialAccessoryIdentity.versionReply(
                exchangeHashTerminated(port, "V#")
            )
            if (rotatorVersion != null && rotatorVersion in 1000..2999) {
                drain(port)
                if (SerialAccessoryIdentity.isRotatorStatus(exchangeHashTerminated(port, "G#"))) {
                    Log.i(TAG, "Probed CAA V$rotatorVersion on ${device.deviceName}")
                    return setOf(SerialAccessoryRole.ROTATOR)
                }
            }

            // EFucoser uses DTR/RTS low after boot.
            runCatching { port.setDTR(false) }
            runCatching { port.setRTS(false) }
            delay(FOCUSER_SETTLE_MS)
            drain(port)
            val focuserBanner = exchangeHashTerminated(port, "#")
            if (SerialAccessoryIdentity.isFocuserBanner(focuserBanner)) {
                Log.i(TAG, "Probed EFucoser on ${device.deviceName}")
                return setOf(SerialAccessoryRole.FOCUSER)
            }

            // Gemini EAF (繁星电调): MyFocuserPro2 handshake at 9600.
            delay(GEMINI_EAF_SETTLE_MS)
            drain(port)
            val geminiFirmware = exchangeHashTerminated(port, ":03#")
            if (SerialAccessoryIdentity.isGeminiEafFirmware(geminiFirmware)) {
                val version = SerialAccessoryIdentity.geminiEafVersion(geminiFirmware)
                Log.i(TAG, "Probed Gemini EAF F$version on ${device.deviceName}")
                return setOf(SerialAccessoryRole.GEMINI_EAF)
            }

            // Gemini flat panel (繁星电动平场) at 9600 before DLC's 115200.
            drain(port)
            val flatHash = exchangeHashTerminated(port, ">H#")
            if (SerialAccessoryIdentity.geminiFlatRevisionFromHandshake(flatHash) != null) {
                Log.i(TAG, "Probed Gemini flat panel on ${device.deviceName}: $flatHash")
                return setOf(SerialAccessoryRole.GEMINI_FLAT)
            }
            drain(port)
            val flatRev1 = exchangeNewlineTerminated(port, ">P000#")
            if (SerialAccessoryIdentity.isGeminiFlatRev1Handshake(flatRev1)) {
                Log.i(TAG, "Probed Gemini flat panel Rev1 on ${device.deviceName}")
                return setOf(SerialAccessoryRole.GEMINI_FLAT)
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
                when (val b = buffer[i].toInt() and 0xff) {
                    '#'.code -> {
                        val text = response.toByteArray().toString(Charsets.US_ASCII).trim()
                        if (text.isNotEmpty()) return text
                        response.clear()
                    }
                    // Replies never span lines, so a newline can only precede
                    // leftover boot chatter. Drop whatever came before it.
                    '\r'.code, '\n'.code -> response.clear()
                    else -> response += b.toByte()
                }
            }
        }
        return response.toByteArray().toString(Charsets.US_ASCII).trim()
    }

    private fun exchangeNewlineTerminated(port: UsbSerialPort, command: String): String {
        port.write(command.toByteArray(Charsets.US_ASCII), READ_TIMEOUT_MS)
        val response = ArrayList<Byte>()
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + READ_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val count = port.read(buffer, 200)
            for (i in 0 until count) {
                val b = buffer[i].toInt() and 0xff
                if (b == '\n'.code || b == '\r'.code) {
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

    private fun exchangeWandererHandshake(
        port: UsbSerialPort
    ): WandererRotatorProtocol.Handshake? {
        port.write(
            "${WandererRotatorProtocol.handshakeCommand}\n".toByteArray(Charsets.US_ASCII),
            WANDERER_READ_TIMEOUT_MS
        )
        val raw = StringBuilder()
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + WANDERER_READ_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val count = port.read(buffer, 200)
            if (count <= 0) continue
            raw.append(String(buffer, 0, count, Charsets.US_ASCII))
            WandererRotatorProtocol.parseHandshake(raw.toString())?.let { return it }
            if (raw.length > 1024) raw.delete(0, raw.length - 512)
        }
        return null
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

    /** Reads until the line stays quiet, so slow boot chatter cannot leak into a reply. */
    private fun drain(port: UsbSerialPort) {
        val buffer = ByteArray(256)
        var quiet = 0
        val deadline = System.currentTimeMillis() + DRAIN_LIMIT_MS
        while (quiet < DRAIN_QUIET_READS && System.currentTimeMillis() < deadline) {
            if (runCatching { port.read(buffer, 80) }.getOrDefault(0) <= 0) quiet++ else quiet = 0
        }
    }
}
