package com.indigo.mobileobservatory.accessories.cover

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
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

class DlcSerialCoverCalibratorAdapter : CoverCalibratorController {
    companion object {
        private const val TAG = "DlcCoverSerial"
        private const val TIMEOUT_MS = 2500
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var pollingJob: Job? = null
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
    private val _maxBrightness = MutableStateFlow(255)
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
            serialPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            runCatching { serialPort.setDTR(false) }
            runCatching { serialPort.setRTS(false) }
            connection = usbConnection
            port = serialPort
            connectedDeviceId = device.deviceId
            delay(2200)
            drain(serialPort)
            require(command("Z") == "?") { "Device did not identify as DLCoverCalibrator" }
            val version = command("V")
            require(Regex("^v1\\.2\\..+").matches(version)) {
                "Unsupported DLCoverCalibrator firmware: $version"
            }
            _deviceInfo.value = "DLCoverCalibrator $version"
            _maxBrightness.value = command("M").toInt().coerceAtLeast(1)
            poll()
            _isConnected.value = true
            startPolling()
            true
        } catch (e: Throwable) {
            Log.e(TAG, "Open failed", e)
            close()
            fail(e.message ?: "DLCoverCalibrator connection failed")
        }
    }

    override fun openCover() = launch("O")
    override fun closeCover() = launch("C")
    override fun halt() = launch("H")
    override fun setBrightness(value: Int) =
        launch("T${value.coerceIn(0, _maxBrightness.value)}")
    override fun calibratorOff() = launch("F")

    private fun launch(code: String) {
        if (!_isConnected.value) return
        scope.launch {
            runCatching { command(code) }
                .onFailure { _lastError.value = it.message }
            runCatching { poll() }.onFailure { _lastError.value = it.message }
        }
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            while (isActive && _isConnected.value) {
                runCatching { poll() }.onFailure {
                    _lastError.value = it.message
                    close()
                }
                delay(if (_coverState.value == CoverState.MOVING) 250 else 1000)
            }
        }
    }

    private fun poll() {
        _coverState.value = CoverState.entries.getOrElse(command("P").toInt()) {
            CoverState.UNKNOWN
        }
        _calibratorState.value =
            CalibratorState.entries.getOrElse(command("L").toInt()) {
                CalibratorState.UNKNOWN
            }
        _brightness.value = command("B").toInt().coerceIn(0, _maxBrightness.value)
    }

    @Synchronized
    private fun command(code: String): String {
        val serialPort = port ?: error("DLCoverCalibrator serial connection is closed")
        serialPort.write("<$code>".toByteArray(Charsets.US_ASCII), TIMEOUT_MS)
        val response = ArrayList<Byte>()
        val buffer = ByteArray(256)
        var inFrame = false
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val count = serialPort.read(buffer, 200)
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
        error("DLCoverCalibrator serial response timeout")
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
        _coverState.value = CoverState.UNKNOWN
        _calibratorState.value = CalibratorState.UNKNOWN
        _deviceInfo.value = null
    }

    override fun destroy() {
        close()
        scope.cancel()
    }

    private fun drain(serialPort: UsbSerialPort) {
        val buffer = ByteArray(256)
        repeat(10) {
            if (runCatching { serialPort.read(buffer, 100) }.getOrDefault(0) <= 0) return
        }
    }

    private fun fail(message: String): Boolean {
        _lastError.value = message
        return false
    }
}
