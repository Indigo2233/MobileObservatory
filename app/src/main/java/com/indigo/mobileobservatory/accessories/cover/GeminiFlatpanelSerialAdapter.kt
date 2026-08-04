package com.indigo.mobileobservatory.accessories.cover

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.indigo.mobileobservatory.accessories.SerialAccessoryIdentity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * USB-serial adapter for Gemini motorized flat panels (繁星电动平场).
 *
 * Wire protocol from INDI `indi_gemini_flatpanel` (Rev1 / Rev2 / Lite / Pro).
 * Baud 9600. Commands are `>X…#`; Rev1 responses end with `\n`, others with `#`.
 */
class GeminiFlatpanelSerialAdapter : CoverCalibratorController {
    enum class Revision { REV1, REV2, LITE, PRO }

    companion object {
        private const val TAG = "GeminiFlatSerial"
        private const val BAUD_RATE = 9600
        private const val TIMEOUT_MS = 4000
        private const val LONG_TIMEOUT_MS = 30000
        private const val MAX_BRIGHTNESS = 255
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var pollingJob: Job? = null
    private var revision: Revision = Revision.REV2
    private var supportsDustCap = true

    var connectedDeviceId: Int? = null
        private set

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _coverState = MutableStateFlow(CoverState.UNKNOWN)
    override val coverState: StateFlow<CoverState> = _coverState.asStateFlow()
    private val _calibratorState = MutableStateFlow(CalibratorState.UNKNOWN)
    override val calibratorState: StateFlow<CalibratorState> = _calibratorState.asStateFlow()
    private val _brightness = MutableStateFlow(0)
    override val brightness: StateFlow<Int> = _brightness.asStateFlow()
    private val _maxBrightness = MutableStateFlow(MAX_BRIGHTNESS)
    override val maxBrightness: StateFlow<Int> = _maxBrightness.asStateFlow()
    private val _deviceInfo = MutableStateFlow<String?>(null)
    override val deviceInfo: StateFlow<String?> = _deviceInfo.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    suspend fun open(context: Context, device: UsbDevice): Boolean = withContext(Dispatchers.IO) {
        close()
        _lastError.value = null
        val manager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
            .firstOrNull { it.device.deviceId == device.deviceId }
            ?: return@withContext fail("Unsupported USB serial adapter")
        val usbConnection = manager.openDevice(device)
            ?: return@withContext fail("Unable to open USB serial device")
        val serialPort = driver.ports.firstOrNull() ?: run {
            usbConnection.close()
            return@withContext fail("USB serial device has no port")
        }
        try {
            serialPort.open(usbConnection)
            serialPort.setParameters(
                BAUD_RATE,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            runCatching { serialPort.setDTR(false) }
            runCatching { serialPort.setRTS(false) }
            connection = usbConnection
            port = serialPort
            connectedDeviceId = device.deviceId
            delay(1000)
            drain(serialPort)

            val detected = detectRevision(serialPort)
                ?: error("Device did not identify as Gemini flat panel")
            revision = detected
            supportsDustCap = detected != Revision.LITE

            val version = runCatching { readFirmwareVersion() }.getOrNull()
            _deviceInfo.value = buildString {
                append("Gemini Flat Panel ")
                append(
                    when (detected) {
                        Revision.REV1 -> "Rev1"
                        Revision.REV2 -> "Rev2"
                        Revision.LITE -> "Lite"
                        Revision.PRO -> "Pro"
                    }
                )
                if (version != null && version > 0) append(" fw$version")
            }
            _maxBrightness.value = MAX_BRIGHTNESS
            if (!supportsDustCap) {
                _coverState.value = CoverState.OPEN
            }
            poll()
            _isConnected.value = true
            startPolling()
            Log.i(TAG, "Connected deviceId=${device.deviceId}: ${_deviceInfo.value}")
            true
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Open failed", e)
            close()
            fail(e.message ?: "Gemini flat panel connection failed")
        }
    }

    override fun openCover() {
        if (!supportsDustCap) return
        launchLong { command('O', longTimeout = true) }
    }

    override fun closeCover() {
        if (!supportsDustCap) return
        launchLong { command('C', longTimeout = true) }
    }

    override fun halt() {
        // Protocol has no abort; next status poll reflects motion end.
    }

    override fun setBrightness(value: Int) {
        val clamped = value.coerceIn(0, _maxBrightness.value)
        launch {
            command('B', clamped)
            if (clamped > 0) command('L') else command('D')
            _brightness.value = clamped
        }
    }

    override fun calibratorOff() {
        launch { command('D') }
    }

    @Synchronized
    override fun close() {
        pollingJob?.cancel()
        pollingJob = null
        runCatching { port?.close() }
        runCatching { connection?.close() }
        port = null
        connection = null
        connectedDeviceId = null
        supportsDustCap = true
        revision = Revision.REV2
        _isConnected.value = false
        _coverState.value = CoverState.UNKNOWN
        _calibratorState.value = CalibratorState.UNKNOWN
        _brightness.value = 0
        _deviceInfo.value = null
    }

    override fun destroy() {
        close()
        scope.cancel()
    }

    private fun detectRevision(serialPort: UsbSerialPort): Revision? {
        drain(serialPort)
        // Prefer newer # -terminated handshakes first (INDI also tries Rev2 family).
        val hashPing = exchangeUntil(serialPort, ">H#", '#')
        when (SerialAccessoryIdentity.geminiFlatRevisionFromHandshake(hashPing)) {
            "PRO" -> return Revision.PRO
            "LITE" -> return Revision.LITE
            "REV2" -> return Revision.REV2
        }

        drain(serialPort)
        val rev1Ping = exchangeUntil(serialPort, ">P000#", '\n')
        if (SerialAccessoryIdentity.isGeminiFlatRev1Handshake(rev1Ping)) {
            return Revision.REV1
        }
        return null
    }

    private fun readFirmwareVersion(): Int? {
        if (revision == Revision.REV1) return null
        val response = command('V')
        // *Vnnn# → digits after *V
        val digits = response.drop(2).takeWhile { it.isDigit() }
        return digits.toIntOrNull()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && _isConnected.value) {
                runCatching { poll() }.onFailure {
                    _lastError.value = it.message
                    Log.e(TAG, "Polling failed", it)
                    close()
                }
                delay(if (_coverState.value == CoverState.MOVING) 250 else 1000)
            }
        }
    }

    private fun poll() {
        val status = command('S')
        parseStatus(status)
        val brightnessResponse = command('J')
        parseBrightness(brightnessResponse)?.let { _brightness.value = it }
    }

    private fun parseStatus(response: String) {
        // Lite: *SLMB… — cover forced open
        // Pro: *S<m> <l> <c>… (motor@2, light@4, cover@6)
        // Rev1/Rev2: *S<id2><motor><light><cover>
        if (!response.startsWith("*S")) return
        when (revision) {
            Revision.LITE -> {
                if (response.length < 4) return
                val light = response[3].digitToIntOrNull() ?: return
                _calibratorState.value =
                    if (light == 1) CalibratorState.READY else CalibratorState.OFF
                _coverState.value = CoverState.OPEN
            }
            Revision.PRO -> {
                if (response.length < 7) return
                val motor = response[2].digitToIntOrNull() ?: return
                val light = response[4].digitToIntOrNull() ?: return
                val cover = response[6].digitToIntOrNull() ?: return
                applyMotorLightCover(motor, light, cover)
            }
            Revision.REV1, Revision.REV2 -> {
                if (response.length < 7) return
                val id = response.substring(2, 4).toIntOrNull() ?: return
                if (id != 19 && id != 99) return
                val motor = response[4].digitToIntOrNull() ?: return
                val light = response[5].digitToIntOrNull() ?: return
                val cover = response[6].digitToIntOrNull() ?: return
                applyMotorLightCover(motor, light, cover)
            }
        }
    }

    private fun applyMotorLightCover(motor: Int, light: Int, cover: Int) {
        _calibratorState.value = when (light) {
            0 -> CalibratorState.OFF
            1 -> CalibratorState.READY
            else -> CalibratorState.UNKNOWN
        }
        _coverState.value = when {
            motor == 1 || cover == 0 -> CoverState.MOVING
            cover == 1 -> CoverState.CLOSED
            cover == 2 -> CoverState.OPEN
            cover == 3 -> CoverState.ERROR
            else -> CoverState.UNKNOWN
        }
    }

    private fun parseBrightness(response: String): Int? {
        if (!response.startsWith("*J")) return null
        val start = if (revision == Revision.REV1) 4 else 2
        if (response.length <= start) return null
        val digits = response.drop(start).takeWhile { it.isDigit() }
        return digits.toIntOrNull()?.coerceIn(0, MAX_BRIGHTNESS)
    }

    private fun launch(block: () -> Unit) {
        if (!_isConnected.value) return
        scope.launch {
            runCatching(block)
                .onFailure { _lastError.value = it.message }
            runCatching { poll() }.onFailure { _lastError.value = it.message }
        }
    }

    private fun launchLong(block: () -> Unit) = launch(block)

    @Synchronized
    private fun command(letter: Char, value: Int? = null, longTimeout: Boolean = false): String {
        val serialPort = port ?: error("Gemini flat panel serial connection is closed")
        val framed = formatCommand(letter, value)
        val timeout = if (longTimeout) LONG_TIMEOUT_MS else TIMEOUT_MS
        val terminator = if (revision == Revision.REV1) '\n' else '#'
        return exchangeUntil(serialPort, framed, terminator, timeout).also { response ->
            if (response.isEmpty() || response[0] != '*' || response.getOrNull(1) != letter) {
                error("Invalid Gemini flat panel response to $framed: $response")
            }
        }
    }

    private fun formatCommand(letter: Char, value: Int?): String = when (revision) {
        Revision.REV1 -> if (value == null) ">$letter%03d#".format(0) else ">$letter%03d#".format(value)
        else -> if (value == null) ">$letter#" else ">$letter$value#"
    }

    private fun exchangeUntil(
        serialPort: UsbSerialPort,
        command: String,
        terminator: Char,
        timeoutMs: Int = TIMEOUT_MS
    ): String {
        serialPort.write(command.toByteArray(Charsets.US_ASCII), timeoutMs)
        val response = ArrayList<Byte>(64)
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val wait = (deadline - System.currentTimeMillis()).toInt().coerceIn(50, 250)
            val count = serialPort.read(buffer, wait)
            if (count <= 0) continue
            for (i in 0 until count) {
                val b = buffer[i].toInt() and 0xff
                if (b == terminator.code) {
                    return response.toByteArray().toString(Charsets.US_ASCII).trim()
                }
                // Rev2 family includes '#' in some firmwares already handled by terminator.
                response += b.toByte()
                if (response.size > 256) error("Gemini flat panel response too long")
            }
        }
        return response.toByteArray().toString(Charsets.US_ASCII).trim()
    }

    private fun drain(serialPort: UsbSerialPort) {
        val buffer = ByteArray(256)
        repeat(10) {
            if (runCatching { serialPort.read(buffer, 80) }.getOrDefault(0) <= 0) return
        }
    }

    private fun fail(message: String): Boolean {
        _lastError.value = message
        return false
    }
}
