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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
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

class EFucoserSerialFocuserController : FocuserController {
    companion object {
        private const val TAG = "EFucoserSerial"
        private const val BAUD_RATE = 9600
        private const val COMMAND_TIMEOUT_MS = 3000
        private const val MAX_RESPONSE_BYTES = 8192
        private val MOTION_PATTERN = Regex(
            "^P\\s+(-?\\d+)\\s*;\\s*M\\s+(true|false)$",
            RegexOption.IGNORE_CASE
        )
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

            delay(2200)
            drainInput(port)

            // Prefer "##" (command '#') so CAA ESP does not treat a lone '#' as empty.
            val identity = runCatching { command("##") }.getOrNull()
                ?.takeIf { SerialAccessoryIdentity.isFocuserBanner(it) }
                ?: command("#")
            val version = SerialAccessoryIdentity.focuserVersion(identity)
                ?: error("Device did not identify as EFucoser: $identity")
            require(
                version in 1005..1099 ||
                    version in 1103..1199 ||
                    version in 1201..1299
            ) {
                "Unsupported EFucoser firmware version: $version"
            }

            var maxSteps = 20000
            runCatching { JSONObject(command("I#")) }.getOrNull()?.let { status ->
                maxSteps = status.optInt("maxSteps", maxSteps).coerceAtLeast(100)
                if (status.optBoolean("tempSensorPresent", false) || status.has("lastTemp")) {
                    _temperature.value = status.optDouble("lastTemp", Double.NaN)
                        .takeIf { it.isFinite() }
                        ?.toFloat()
                }
            }
            _eafInfo.value = EAFInfo(
                name = identity,
                minPosition = 0,
                maxPosition = maxSteps,
                maxStep = maxSteps,
                stepSize = 1,
                fineStep = 10,
                coarseStep = 50
            )
            updateMotionStatus(command("G#"))
            _isConnected.value = true
            startPolling()
            Log.i(TAG, "Connected deviceId=${device.deviceId}: $identity")
            true
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Open failed", e)
            close()
            fail(e.message ?: "EFucoser connection failed")
        }
    }

    override fun moveTo(position: Int) {
        val info = _eafInfo.value ?: return
        val target = position.coerceIn(info.minPosition, info.maxPosition)
        launchCommand("M $target#") { updateMotionStatus(it) }
    }

    override fun moveRelative(steps: Int) {
        moveTo(_currentPosition.value + steps)
    }

    override fun halt() {
        launchCommand("S#") {
            _isMoving.value = false
        }
    }

    override fun setZero() {
        launchCommand("P 0#") { updateMotionStatus(it) }
    }

    override fun setDirection(direction: Int) {
        launchCommand("R ${if (direction == 0) 0 else 1}#") {
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

    override fun setMaxStep(maxStep: Int) {
        val value = maxStep.coerceAtLeast(100)
        launchCommand("D $value#") {
            _eafInfo.value = _eafInfo.value?.copy(
                maxStep = value,
                maxPosition = value
            )
        }
    }

    override fun setBacklash(steps: Int, direction: Int) {
        _eafInfo.value = _eafInfo.value?.copy(
            backlashSteps = steps.coerceAtLeast(0),
            backlashDirection = direction
        )
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
                    updateMotionStatus(command("G#"))
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

    private fun launchCommand(command: String, onResponse: (String) -> Unit) {
        if (!_isConnected.value) return
        scope.launch {
            try {
                onResponse(command(command))
            } catch (e: Throwable) {
                _lastError.value = e.message
                Log.e(TAG, "Command failed: $command", e)
            }
        }
    }

    @Synchronized
    private fun command(rawCommand: String): String {
        val port = serialPort ?: error("EFucoser serial connection is closed")
        val framed = if (rawCommand.endsWith("#")) rawCommand else "$rawCommand#"
        port.write(framed.toByteArray(Charsets.US_ASCII), COMMAND_TIMEOUT_MS)

        val response = ArrayList<Byte>(128)
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val timeout = (deadline - System.currentTimeMillis()).toInt().coerceIn(50, 250)
            val count = port.read(buffer, timeout)
            if (count <= 0) continue
            for (index in 0 until count) {
                val byte = buffer[index]
                if ((byte.toInt() and 0xff) == '#'.code) {
                    val text = response.toByteArray().toString(Charsets.US_ASCII).trim()
                    if (text.startsWith("ERR:", ignoreCase = true)) {
                        error(text)
                    }
                    return text
                }
                response += byte
                if (response.size > MAX_RESPONSE_BYTES) {
                    error("EFucoser response exceeded $MAX_RESPONSE_BYTES bytes")
                }
            }
        }
        error("EFucoser serial response timeout")
    }

    private fun updateMotionStatus(response: String) {
        val match = MOTION_PATTERN.matchEntire(response)
            ?: error("Invalid EFucoser motion response: $response")
        _currentPosition.value = match.groupValues[1].toInt()
        _isMoving.value = match.groupValues[2].toBooleanStrict()
    }

    private fun drainInput(port: UsbSerialPort) {
        val buffer = ByteArray(256)
        repeat(8) {
            val count = runCatching { port.read(buffer, 100) }.getOrDefault(0)
            if (count <= 0) return
        }
    }

    private fun fail(message: String): Boolean {
        _lastError.value = message
        return false
    }
}
