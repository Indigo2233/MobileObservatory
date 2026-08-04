package com.indigo.mobileobservatory.accessories.rotator

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.indigo.mobileobservatory.accessories.SerialAccessoryIdentity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
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
import org.json.JSONObject
import kotlin.math.roundToInt

class EcaaSerialRotatorAdapter : RotatorController {
    companion object {
        private const val TAG = "EcaaRotatorSerial"
        private const val TIMEOUT_MS = 3000
        private const val DEFAULT_STEPS_PER_DEGREE = 100
        private val STATUS = Regex("^P\\s+(-?\\d+)\\s*;\\s*M\\s+(true|false)$", RegexOption.IGNORE_CASE)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var pollingJob: Job? = null
    private var firmwareVersion = 0
    var connectedDeviceId: Int? = null
        private set

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _angle = MutableStateFlow(0.0)
    override val angle: StateFlow<Double> = _angle.asStateFlow()
    private val _positionSteps = MutableStateFlow(0)
    override val positionSteps: StateFlow<Int> = _positionSteps.asStateFlow()
    private val _isMoving = MutableStateFlow(false)
    override val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()
    private val _stepsPerDegree = MutableStateFlow(DEFAULT_STEPS_PER_DEGREE)
    override val stepsPerDegree: StateFlow<Int> = _stepsPerDegree.asStateFlow()
    private val _stepsPerDegreeFromBoard = MutableStateFlow(false)
    override val stepsPerDegreeFromBoard: StateFlow<Boolean> = _stepsPerDegreeFromBoard.asStateFlow()
    private val _reversed = MutableStateFlow(false)
    override val reversed: StateFlow<Boolean> = _reversed.asStateFlow()
    private val _hold = MutableStateFlow(true)
    override val hold: StateFlow<Boolean> = _hold.asStateFlow()
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
            serialPort.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { serialPort.setDTR(true) }
            runCatching { serialPort.setRTS(true) }
            connection = usbConnection
            port = serialPort
            connectedDeviceId = device.deviceId
            delay(4000)
            drain(serialPort)
            val banner = command("##")
            firmwareVersion = SerialAccessoryIdentity.rotatorVersion(banner)
                ?: error("Device did not identify as electric CAA: $banner")
            require(firmwareVersion in 1000..2999) {
                "Unsupported electric CAA firmware: $firmwareVersion"
            }
            syncBoardSettings()
            // Idle axis should stay locked unless the user explicitly turns hold off.
            if (!_hold.value) {
                command("C 1#")
                _hold.value = true
            }
            _deviceInfo.value = "electric CAA V$firmwareVersion"
            updateStatus(command("G#"))
            _isConnected.value = true
            startPolling()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Open failed", e)
            close()
            fail(e.message ?: "electric CAA connection failed")
        }
    }

    override fun moveTo(angleDegrees: Double) {
        val normalized = ((angleDegrees % 360.0) + 360.0) % 360.0
        val target = (200 * _stepsPerDegree.value + normalized * _stepsPerDegree.value).roundToInt()
        launch("M $target#")
    }

    override fun moveRelative(deltaDegrees: Double) = moveTo(_angle.value + deltaDegrees)
    override fun halt() = launch("S#")
    override fun home() = launch("H#")
    override fun setZero() = launch("P ${200 * _stepsPerDegree.value}#")
    override fun setReversed(reversed: Boolean) =
        launch("R ${if (reversed) 1 else 0}#") { _reversed.value = reversed }
    override fun setHold(enabled: Boolean) =
        launch("C ${if (enabled) 1 else 0}#") { _hold.value = enabled }
    override fun setStepsPerDegree(value: Int) {
        val scale = value.coerceAtLeast(1)
        launch("D $scale#") {
            _stepsPerDegree.value = scale
            _stepsPerDegreeFromBoard.value = true
        }
    }

    private fun syncBoardSettings() {
        _stepsPerDegree.value = DEFAULT_STEPS_PER_DEGREE
        _stepsPerDegreeFromBoard.value = false
        _reversed.value = false
        _hold.value = true
        // Prefer board JSON status (ESP8266 / firmware 2000+). Older Nano has no I#.
        val status = runCatching { JSONObject(command("I#")) }.getOrNull()
        if (status != null) {
            if (status.has("stepsPerDegree")) {
                val spd = status.optInt("stepsPerDegree", DEFAULT_STEPS_PER_DEGREE).coerceAtLeast(1)
                _stepsPerDegree.value = spd
                _stepsPerDegreeFromBoard.value = true
            }
            if (status.has("reversed")) {
                _reversed.value = status.optBoolean("reversed", false)
            }
            if (status.has("hold")) {
                _hold.value = status.optBoolean("hold", true)
            }
        }
        Log.i(
            TAG,
            "Board settings: steps/°=${_stepsPerDegree.value} " +
                "(fromBoard=${_stepsPerDegreeFromBoard.value}) " +
                "hold=${_hold.value} reversed=${_reversed.value}"
        )
    }

    private fun launch(code: String, success: (() -> Unit)? = null) {
        if (!_isConnected.value) return
        scope.launch {
            runCatching { command(code) }
                .onSuccess { success?.invoke() }
                .onFailure { _lastError.value = it.message }
            runCatching { updateStatus(command("G#")) }
                .onFailure { _lastError.value = it.message }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && _isConnected.value) {
                runCatching { updateStatus(command("G#")) }.onFailure {
                    _lastError.value = it.message
                    close()
                }
                delay(if (_isMoving.value) 250 else 1000)
            }
        }
    }

    private fun updateStatus(response: String) {
        val match = STATUS.matchEntire(response) ?: error("Invalid CAA status: $response")
        val position = match.groupValues[1].toInt()
        _positionSteps.value = position
        _isMoving.value = match.groupValues[2].toBooleanStrict()
        val raw = (position - 200.0 * _stepsPerDegree.value) / _stepsPerDegree.value
        _angle.value = ((raw % 360.0) + 360.0) % 360.0
    }

    @Synchronized
    private fun command(code: String): String {
        val serialPort = port ?: error("electric CAA serial connection is closed")
        val framed = if (code.endsWith("#")) code else "$code#"
        serialPort.write(framed.toByteArray(Charsets.US_ASCII), TIMEOUT_MS)
        val response = ArrayList<Byte>()
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val count = serialPort.read(buffer, 200)
            for (i in 0 until count) {
                val b = buffer[i].toInt() and 0xff
                if (b == '#'.code) {
                    val text = response.toByteArray().toString(Charsets.US_ASCII).trim()
                    if (text.startsWith("ERR:", true)) error(text)
                    if (text.isNotEmpty()) return text
                    response.clear()
                } else {
                    response += b.toByte()
                }
            }
        }
        error("electric CAA serial response timeout")
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
        _isConnected.value = false
        _isMoving.value = false
        _deviceInfo.value = null
        _stepsPerDegree.value = DEFAULT_STEPS_PER_DEGREE
        _stepsPerDegreeFromBoard.value = false
        _reversed.value = false
        _hold.value = true
    }

    override fun destroy() {
        close()
        scope.cancel()
    }

    private fun drain(serialPort: UsbSerialPort) {
        val buffer = ByteArray(256)
        repeat(20) {
            if (runCatching { serialPort.read(buffer, 100) }.getOrDefault(0) <= 0) return
        }
    }

    private fun fail(message: String): Boolean {
        _lastError.value = message
        return false
    }
}
