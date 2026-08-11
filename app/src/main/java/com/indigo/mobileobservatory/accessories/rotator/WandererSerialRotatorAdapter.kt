package com.indigo.mobileobservatory.accessories.rotator

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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

class WandererSerialRotatorAdapter : RotatorController {
    companion object {
        private const val TAG = "WandererRotator"
        private const val OPEN_SETTLE_MS = 500L
        private const val HANDSHAKE_TIMEOUT_MS = 3_000L
        private const val READ_TIMEOUT_MS = 250
        private const val DRAIN_LIMIT_MS = 800L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val serialLock = Any()
    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var motionJob: Job? = null
    @Volatile private var stopRequested = false
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
    private val _stepsPerDegree = MutableStateFlow(0)
    override val stepsPerDegree: StateFlow<Int> = _stepsPerDegree.asStateFlow()
    private val _stepsPerDegreeFromBoard = MutableStateFlow(true)
    override val stepsPerDegreeFromBoard: StateFlow<Boolean> = _stepsPerDegreeFromBoard.asStateFlow()
    private val _supportsStepConfiguration = MutableStateFlow(false)
    override val supportsStepConfiguration: StateFlow<Boolean> = _supportsStepConfiguration.asStateFlow()
    private val _reversed = MutableStateFlow(false)
    override val reversed: StateFlow<Boolean> = _reversed.asStateFlow()
    private val _hold = MutableStateFlow(false)
    override val hold: StateFlow<Boolean> = _hold.asStateFlow()
    private val _supportsHold = MutableStateFlow(false)
    override val supportsHold: StateFlow<Boolean> = _supportsHold.asStateFlow()
    private val _deviceInfo = MutableStateFlow<String?>(null)
    override val deviceInfo: StateFlow<String?> = _deviceInfo.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var model: WandererRotatorProtocol.Model? = null

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
                WandererRotatorProtocol.baudRate,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            // CH340-based Wanderer devices can reset on control-line changes.
            // Leave DTR and RTS at the driver's open-time defaults.
            connection = usbConnection
            port = serialPort
            connectedDeviceId = device.deviceId
            delay(OPEN_SETTLE_MS)

            val handshake = synchronized(serialLock) {
                drain(serialPort)
                writeCommand(serialPort, WandererRotatorProtocol.handshakeCommand)
                readHandshake(serialPort)
            }
            require(WandererRotatorProtocol.isFirmwareSupported(handshake)) {
                "${handshake.model.displayName} firmware ${handshake.firmware} is older than " +
                    "${handshake.model.minimumFirmware}"
            }
            applyHandshake(handshake)
            _isConnected.value = true
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Open failed", error)
            close()
            fail(error.message ?: "WandererRotator connection failed")
        }
    }

    override fun moveTo(angleDegrees: Double) {
        val currentModel = model ?: return
        val delta = WandererRotatorProtocol.shortestDelta(_angle.value, angleDegrees)
        if (runCatching { WandererRotatorProtocol.encodeMove(delta, currentModel) }.isFailure) return
        startMove(delta, currentModel)
    }

    override fun moveRelative(deltaDegrees: Double) {
        val currentModel = model ?: return
        if (runCatching { WandererRotatorProtocol.encodeMove(deltaDegrees, currentModel) }.isFailure) return
        startMove(deltaDegrees, currentModel)
    }

    override fun halt() {
        if (!_isConnected.value) return
        if (_isMoving.value) {
            stopRequested = true
        } else {
            sendWithoutReply(WandererRotatorProtocol.stopCommand)
        }
    }

    override fun home() = moveTo(0.0)

    override fun setZero() {
        sendWithoutReply(WandererRotatorProtocol.setZeroCommand) {
            _angle.value = 0.0
            _positionSteps.value = 0
        }
    }

    override fun setReversed(reversed: Boolean) {
        sendWithoutReply(WandererRotatorProtocol.encodeReverse(reversed)) {
            _reversed.value = reversed
        }
    }

    override fun setHold(enabled: Boolean) {
        _lastError.value = "WandererRotator does not expose motor-hold control."
    }

    override fun setStepsPerDegree(value: Int) {
        _lastError.value = "WandererRotator uses the fixed scale of its identified model."
    }

    private fun startMove(deltaDegrees: Double, currentModel: WandererRotatorProtocol.Model) {
        if (!_isConnected.value || _isMoving.value) return
        val command = runCatching {
            WandererRotatorProtocol.encodeMove(deltaDegrees, currentModel)
        }.getOrElse {
            _lastError.value = it.message
            return
        }
        _isMoving.value = true
        _lastError.value = null
        stopRequested = false
        motionJob?.cancel()
        motionJob = scope.launch {
            try {
                val completion = synchronized(serialLock) {
                    val serialPort = port ?: error("WandererRotator serial connection is closed")
                    drain(serialPort)
                    writeCommand(serialPort, command)
                    readMoveCompletion(serialPort, motionTimeoutMs(deltaDegrees))
                }
                _positionSteps.value = completion.mechanicalAngleMilliDegrees
                _angle.value = completion.angleDegrees
            } catch (error: Throwable) {
                _lastError.value = error.message ?: "WandererRotator movement failed"
            } finally {
                _isMoving.value = false
                stopRequested = false
                motionJob = null
            }
        }
    }

    private fun sendWithoutReply(command: String, success: (() -> Unit)? = null) {
        if (!_isConnected.value) return
        scope.launch {
            runCatching {
                synchronized(serialLock) {
                    val serialPort = port ?: error("WandererRotator serial connection is closed")
                    writeCommand(serialPort, command)
                }
            }.onSuccess {
                _lastError.value = null
                success?.invoke()
            }.onFailure {
                _lastError.value = it.message
            }
        }
    }

    private fun readHandshake(serialPort: UsbSerialPort): WandererRotatorProtocol.Handshake =
        readUntil(serialPort, HANDSHAKE_TIMEOUT_MS) { raw ->
            WandererRotatorProtocol.parseHandshake(raw)
        }

    private fun readMoveCompletion(
        serialPort: UsbSerialPort,
        timeoutMs: Long
    ): WandererRotatorProtocol.MoveCompletion = readUntil(serialPort, timeoutMs) { raw ->
        if (stopRequested) {
            writeCommand(serialPort, WandererRotatorProtocol.stopCommand)
            stopRequested = false
        }
        if (raw.contains("NP")) error("WandererRotator reported low input voltage (NP).")
        WandererRotatorProtocol.parseMoveCompletion(raw)
    }

    private fun <T> readUntil(
        serialPort: UsbSerialPort,
        timeoutMs: Long,
        parser: (String) -> T?
    ): T {
        val buffer = ByteArray(256)
        val raw = StringBuilder()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val count = serialPort.read(buffer, READ_TIMEOUT_MS)
            if (count <= 0) {
                parser(raw.toString())?.let { return it }
                continue
            }
            raw.append(String(buffer, 0, count, Charsets.US_ASCII))
            parser(raw.toString())?.let { return it }
            if (raw.length > 1024) raw.delete(0, raw.length - 512)
        }
        error("WandererRotator serial response timeout")
    }

    private fun writeCommand(serialPort: UsbSerialPort, command: String) {
        serialPort.write("$command\n".toByteArray(Charsets.US_ASCII), HANDSHAKE_TIMEOUT_MS.toInt())
    }

    private fun motionTimeoutMs(deltaDegrees: Double): Long =
        (abs(deltaDegrees) * 250.0 + 10_000.0).toLong().coerceIn(10_000L, 100_000L)

    private fun applyHandshake(handshake: WandererRotatorProtocol.Handshake) {
        model = handshake.model
        _stepsPerDegree.value = handshake.model.stepsPerDegree
        _positionSteps.value = handshake.mechanicalAngleMilliDegrees
        _angle.value = handshake.angleDegrees
        _reversed.value = handshake.reversed
        _deviceInfo.value = "${handshake.model.displayName} FW ${handshake.firmware}"
    }

    @Synchronized
    override fun close() {
        motionJob?.cancel()
        motionJob = null
        runCatching { port?.close() }
        runCatching { connection?.close() }
        connection = null
        port = null
        model = null
        connectedDeviceId = null
        stopRequested = false
        _isConnected.value = false
        _isMoving.value = false
        _angle.value = 0.0
        _positionSteps.value = 0
        _stepsPerDegree.value = 0
        _reversed.value = false
        _deviceInfo.value = null
    }

    override fun destroy() {
        close()
        scope.cancel()
    }

    private fun drain(serialPort: UsbSerialPort) {
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + DRAIN_LIMIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (runCatching { serialPort.read(buffer, 80) }.getOrDefault(0) <= 0) return
        }
    }

    private fun fail(message: String): Boolean {
        _lastError.value = message
        return false
    }
}
