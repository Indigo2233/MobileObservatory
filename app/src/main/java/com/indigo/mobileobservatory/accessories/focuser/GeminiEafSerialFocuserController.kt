package com.indigo.mobileobservatory.accessories.focuser

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.indigo.mobileobservatory.accessories.SerialAccessoryIdentity
import com.indigo.mobileobservatory.camera.toupcam.EAFInfo
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
 * USB-serial adapter for Gemini EAF (繁星电调).
 *
 * Uses the INDI MyFocuserPro2 wire protocol
 * (`indi_myfocuserpro2_focus` / ASCOM StarFocuserPro102ASCOM).
 *
 * Protocol uses `#`-terminated commands (`:03#` handshake → `F<ver>#`).
 */
class GeminiEafSerialFocuserController : FocuserController {
    companion object {
        private const val TAG = "GeminiEafSerial"
        private const val BAUD_RATE = 9600
        private const val COMMAND_TIMEOUT_MS = 3000
        private const val WRITE_SETTLE_MS = 20L
        private const val MAX_RESPONSE_BYTES = 256
        private val POSITION_PATTERN = Regex("^.(-?\\d+)$")
        private val MOVING_PATTERN = Regex("^I([01])$", RegexOption.IGNORE_CASE)
        private val MAX_POS_PATTERN = Regex("^M(\\d+)$", RegexOption.IGNORE_CASE)
        private val TEMP_PATTERN = Regex("^Z(-?\\d+(?:\\.\\d+)?)$", RegexOption.IGNORE_CASE)
        private val TEMP_AVAIL_PATTERN = Regex("^c(\\d+)$", RegexOption.IGNORE_CASE)
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var serialPort: UsbSerialPort? = null

    @Volatile
    var connectedDeviceId: Int? = null
        private set

    private val _isConnected = MutableStateFlow(false)
    override val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _eafInfo = MutableStateFlow<EAFInfo?>(null)
    override val eafInfo: StateFlow<EAFInfo?> = _eafInfo.asStateFlow()

    private val _currentPosition = MutableStateFlow(0)
    override val currentPosition: StateFlow<Int> = _currentPosition.asStateFlow()

    private val _isMoving = MutableStateFlow(false)
    override val isMoving: StateFlow<Boolean> = _isMoving.asStateFlow()

    private val _temperature = MutableStateFlow<Float?>(null)
    override val temperature: StateFlow<Float?> = _temperature.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var temperatureAvailable = false

    suspend fun open(context: Context, device: UsbDevice): Boolean = withContext(Dispatchers.IO) {
        close()
        _lastError.value = null
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull { it.device.deviceId == device.deviceId }
            ?: return@withContext fail("Unsupported USB serial adapter")
        val connection = usbManager.openDevice(device)
            ?: return@withContext fail("Unable to open USB serial device")
        val port = driver.ports.firstOrNull()
            ?: run {
                connection.close()
                return@withContext fail("USB serial device has no port")
            }

        try {
            port.open(connection)
            port.setParameters(
                BAUD_RATE,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            runCatching { port.setDTR(false) }
            runCatching { port.setRTS(false) }
            usbConnection = connection
            serialPort = port
            connectedDeviceId = device.deviceId

            delay(1000)
            drainInput(port)

            val identity = command(":03#")
            val version = SerialAccessoryIdentity.geminiEafVersion(identity)
                ?: error("Device did not identify as Gemini EAF: $identity")

            var maxSteps = 128000
            runCatching { command(":08#") }.getOrNull()?.let { response ->
                MAX_POS_PATTERN.matchEntire(response)?.groupValues?.getOrNull(1)
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                    ?.let { maxSteps = it }
            }

            temperatureAvailable = runCatching { command(":83#") }.getOrNull()
                ?.let { TEMP_AVAIL_PATTERN.matchEntire(it)?.groupValues?.getOrNull(1)?.toIntOrNull() }
                ?.let { it != 0 }
                ?: false
            if (temperatureAvailable) {
                refreshTemperature()
            }

            updatePosition(command(":00#"))
            updateMoving(command(":01#"))

            _eafInfo.value = EAFInfo(
                name = "Gemini EAF ver $version",
                minPosition = 0,
                maxPosition = maxSteps,
                maxStep = maxSteps,
                stepSize = 1,
                fineStep = 10,
                coarseStep = 50
            )
            _isConnected.value = true
            startPolling()
            Log.i(TAG, "Connected deviceId=${device.deviceId}: firmware $version")
            true
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Open failed", e)
            close()
            fail(e.message ?: "Gemini EAF connection failed")
        }
    }

    override fun moveTo(position: Int) {
        val info = _eafInfo.value ?: return
        val target = position.coerceIn(info.minPosition, info.maxPosition)
        launchFireAndForget(":05$target#") {
            _isMoving.value = true
        }
    }

    override fun moveRelative(steps: Int) {
        moveTo(_currentPosition.value + steps)
    }

    override fun halt() {
        launchFireAndForget(":27#") {
            _isMoving.value = false
        }
    }

    override fun setZero() {
        launchFireAndForget(":310#") {
            _currentPosition.value = 0
        }
    }

    override fun setDirection(direction: Int) {
        val reverse = if (direction == 0) 0 else 1
        launchFireAndForget(":14$reverse#") {
            _eafInfo.value = _eafInfo.value?.copy(direction = direction)
        }
    }

    override fun setFineStep(step: Int) {
        val fine = step.coerceAtLeast(1)
        _eafInfo.value = _eafInfo.value?.copy(
            fineStep = fine,
            coarseStep = fine * 5
        )
    }

    override fun setCoarseStep(step: Int) {
        _eafInfo.value = _eafInfo.value?.copy(coarseStep = step.coerceAtLeast(1))
    }

    override fun setMaxStep(maxStep: Int) {
        val value = maxStep.coerceAtLeast(100)
        launchFireAndForget(":07%06d#".format(value)) {
            _eafInfo.value = _eafInfo.value?.copy(
                maxStep = value,
                maxPosition = value
            )
        }
    }

    override fun setBacklash(steps: Int, direction: Int) {
        val value = steps.coerceAtLeast(0)
        _eafInfo.value = _eafInfo.value?.copy(
            backlashSteps = value,
            backlashDirection = direction
        )
        // Apply both in/out backlash to match common DIY defaults.
        launchFireAndForget(":77$value#") {}
        launchFireAndForget(":79$value#") {}
        launchFireAndForget(if (value > 0) ":731#" else ":730#") {}
        launchFireAndForget(if (value > 0) ":751#" else ":750#") {}
    }

    @Synchronized
    override fun close() {
        pollingJob?.cancel()
        pollingJob = null
        runCatching { serialPort?.close() }
        runCatching { usbConnection?.close() }
        serialPort = null
        usbConnection = null
        connectedDeviceId = null
        temperatureAvailable = false
        _isConnected.value = false
        _isMoving.value = false
        _eafInfo.value = null
        _currentPosition.value = 0
        _temperature.value = null
    }

    override fun destroy() {
        close()
        scope.cancel()
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && _isConnected.value) {
                try {
                    updatePosition(command(":00#"))
                    updateMoving(command(":01#"))
                    if (temperatureAvailable) {
                        refreshTemperature()
                    }
                } catch (e: Throwable) {
                    _lastError.value = e.message
                    Log.e(TAG, "Polling failed", e)
                    close()
                    break
                }
                delay(if (_isMoving.value) 200 else 1000)
            }
        }
    }

    private fun launchFireAndForget(cmd: String, onSent: () -> Unit) {
        if (!_isConnected.value) return
        scope.launch {
            try {
                writeOnly(cmd)
                onSent()
            } catch (e: Throwable) {
                _lastError.value = e.message
                Log.e(TAG, "Command failed: $cmd", e)
            }
        }
    }

    @Synchronized
    private fun command(rawCommand: String): String {
        val port = serialPort ?: error("Gemini EAF serial connection is closed")
        val framed = if (rawCommand.endsWith("#")) rawCommand else "$rawCommand#"
        port.write(framed.toByteArray(Charsets.US_ASCII), COMMAND_TIMEOUT_MS)
        Thread.sleep(WRITE_SETTLE_MS)

        val response = ArrayList<Byte>(64)
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val timeout = (deadline - System.currentTimeMillis()).toInt().coerceIn(50, 250)
            val count = port.read(buffer, timeout)
            if (count <= 0) continue
            for (index in 0 until count) {
                val byte = buffer[index]
                if ((byte.toInt() and 0xff) == '#'.code) {
                    return response.toByteArray().toString(Charsets.US_ASCII).trim()
                }
                response += byte
                if (response.size > MAX_RESPONSE_BYTES) {
                    error("Gemini EAF response exceeded $MAX_RESPONSE_BYTES bytes")
                }
            }
        }
        error("Gemini EAF serial response timeout")
    }

    @Synchronized
    private fun writeOnly(rawCommand: String) {
        val port = serialPort ?: error("Gemini EAF serial connection is closed")
        val framed = if (rawCommand.endsWith("#")) rawCommand else "$rawCommand#"
        port.write(framed.toByteArray(Charsets.US_ASCII), COMMAND_TIMEOUT_MS)
        Thread.sleep(WRITE_SETTLE_MS)
        drainInput(port)
    }

    private fun updatePosition(response: String) {
        val match = POSITION_PATTERN.matchEntire(response)
            ?: error("Invalid Gemini EAF position response: $response")
        _currentPosition.value = match.groupValues[1].toInt()
    }

    private fun updateMoving(response: String) {
        val match = MOVING_PATTERN.matchEntire(response)
            ?: error("Invalid Gemini EAF moving response: $response")
        _isMoving.value = match.groupValues[1] == "1"
    }

    private fun refreshTemperature() {
        val response = runCatching { command(":06#") }.getOrNull() ?: return
        TEMP_PATTERN.matchEntire(response)?.groupValues?.getOrNull(1)
            ?.toFloatOrNull()
            ?.let { _temperature.value = it }
    }

    private fun drainInput(port: UsbSerialPort) {
        val buffer = ByteArray(256)
        repeat(8) {
            val count = runCatching { port.read(buffer, 80) }.getOrDefault(0)
            if (count <= 0) return
        }
    }

    private fun fail(message: String): Boolean {
        _lastError.value = message
        return false
    }
}
