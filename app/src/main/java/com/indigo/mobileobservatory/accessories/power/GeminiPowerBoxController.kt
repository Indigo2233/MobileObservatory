package com.indigo.mobileobservatory.accessories.power

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.indigo.mobileobservatory.util.FileLogger
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class GeminiPowerBoxController {
    companion object {
        private const val TAG = "GeminiPowerBox"
        private const val COMMAND_TIMEOUT_MS = 3000
        private const val FIRST_STATUS_TIMEOUT_MS = 5000
        private const val STREAM_RECOVERY_MS = 6000L
        private const val USB_MASTER_CONFIRM_TIMEOUT_MS = 4500L
        private const val USB_REENUMERATION_SETTLE_MS = 750L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connection: UsbDeviceConnection? = null
    private var port: UsbSerialPort? = null
    private var readerJob: Job? = null
    private val frameBuffer = StringBuilder()
    private var identity: String? = null
    private var firmware: Int? = null
    private var lastStatusAt = 0L
    private var lastLoggedControlState: String? = null
    @Volatile private var statusFramesToLog = 0
    private val dewModeLock = Any()
    private val usbStateLock = Any()
    private val commandMutex = Mutex()
    private val lastRequestedDewModes = mutableMapOf<Int, DewHeaterMode>()
    private val lastRequestedUsbStates = mutableMapOf<Int, Boolean>()

    private data class StatusExchangeResult(
        val telemetry: GeminiPowerTelemetry? = null,
        val rawFrames: List<String> = emptyList(),
        val parseError: String? = null
    )

    var connectedDeviceId: Int? = null
        private set

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    private val _capabilities = MutableStateFlow<GeminiPowerCapabilities?>(null)
    val capabilities: StateFlow<GeminiPowerCapabilities?> = _capabilities.asStateFlow()
    private val _telemetry = MutableStateFlow<GeminiPowerTelemetry?>(null)
    val telemetry: StateFlow<GeminiPowerTelemetry?> = _telemetry.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

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
                GeminiPowerProtocol.BAUD_RATE,
                UsbSerialPort.DATABITS_8,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE
            )
            runCatching { serialPort.setDTR(true) }
            runCatching { serialPort.setRTS(true) }
            connection = usbConnection
            port = serialPort
            connectedDeviceId = device.deviceId
            delay(800)
            drain(serialPort)

            val detectedIdentity = exchangeForPrefix(serialPort, ">H#", "*H", COMMAND_TIMEOUT_MS)
                ?.let(GeminiPowerProtocol::parseIdentity)
                ?: error("Device did not identify as a Gemini power box")
            val detectedFirmware = exchangeForPrefix(serialPort, ">V#", "*V", COMMAND_TIMEOUT_MS)
                ?.let(GeminiPowerProtocol::parseFirmware)
                ?: error("Gemini power box firmware response is invalid")
            drain(serialPort)
            val statusResult = exchangeForStatus(serialPort, FIRST_STATUS_TIMEOUT_MS)
            val firstStatus = statusResult.telemetry ?: error(
                buildStatusFailure(statusResult)
            )

            identity = detectedIdentity
            firmware = detectedFirmware
            applyTelemetry(firstStatus)
            val discovered = _capabilities.value
                ?: error("Gemini power box capabilities could not be discovered")
            if (!discovered.canControlOutputs) {
                error("Unsupported Gemini power box $detectedIdentity firmware $detectedFirmware")
            }

            lastStatusAt = System.currentTimeMillis()
            _isConnected.value = true
            startReader()
            FileLogger.i(
                TAG,
                "Connected ${discovered.identity} fw${discovered.firmware}, " +
                    "DC=${discovered.dcOutputs.size}, USB=${discovered.usbOutputs.size}, " +
                    "dew=${discovered.dewHeaters.size}"
            )
            true
        } catch (e: CancellationException) {
            close()
            throw e
        } catch (e: Throwable) {
            FileLogger.e(TAG, "Open failed", e)
            close()
            fail(e.message ?: "Gemini power box connection failed")
        }
    }

    fun setDcOutput(index: Int, enabled: Boolean) {
        val capability = _capabilities.value?.dcOutputs?.getOrNull(index) ?: return
        launchCommand(GeminiPowerProtocol.outputCommand(capability, enabled))
    }

    fun setUsbOutput(index: Int, enabled: Boolean) {
        val discovered = _capabilities.value ?: return
        val capability = discovered.usbOutputs.getOrNull(index) ?: return
        synchronized(usbStateLock) {
            lastRequestedUsbStates[index] = enabled
        }
        _telemetry.value = _telemetry.value?.let { current ->
            val states = List(current.usbControlCount) { stateIndex ->
                if (stateIndex == index) enabled
                else current.usbOutputs.getOrElse(stateIndex) { false }
            }
            current.copy(usbOutputs = states)
        }
        launchUsbOutputCommand(
            capability = capability,
            masterCapability = discovered.usbMasterOutput,
            enabled = enabled
        )
    }

    fun setUsbMasterEnabled(enabled: Boolean) {
        val capability = _capabilities.value?.usbMasterOutput ?: return
        _telemetry.value = _telemetry.value?.copy(usbMasterEnabled = enabled)
        launchCommand(GeminiPowerProtocol.outputCommand(capability, enabled))
    }

    fun setDewEnabled(index: Int, enabled: Boolean) {
        val capability = _capabilities.value?.dewHeaters?.getOrNull(index) ?: return
        if (!capability.supportsEnabledSwitch) return
        _telemetry.value = _telemetry.value?.let { current ->
            val heater = current.dewHeaters.getOrNull(index) ?: return@let current
            current.copy(
                dewHeaters = current.dewHeaters.toMutableList().apply {
                    this[index] = heater.copy(enabled = enabled)
                }
            )
        }
        launchCommand(GeminiPowerProtocol.dewEnabledCommand(capability, enabled))
    }

    fun setDewMode(index: Int, mode: DewHeaterMode) {
        val capability = _capabilities.value?.dewHeaters?.getOrNull(index) ?: return
        if (mode !in capability.supportedModes) return
        synchronized(dewModeLock) {
            lastRequestedDewModes[index] = mode
        }
        _telemetry.value = _telemetry.value?.let { current ->
            val heater = current.dewHeaters.getOrNull(index) ?: return@let current
            current.copy(
                dewHeaters = current.dewHeaters.toMutableList().apply {
                    this[index] = heater.copy(mode = mode)
                }
            )
        }
        launchCommand(GeminiPowerProtocol.dewModeCommand(capability, mode))
    }

    fun setDewPower(index: Int, value: Int) {
        val capability = _capabilities.value?.dewHeaters?.getOrNull(index) ?: return
        val boundedValue = value.coerceIn(0, capability.manualOutputMaximum)
        _telemetry.value = _telemetry.value?.let { current ->
            val heater = current.dewHeaters.getOrNull(index) ?: return@let current
            current.copy(
                dewHeaters = current.dewHeaters.toMutableList().apply {
                    this[index] = heater.copy(outputValue = boundedValue.toDouble())
                }
            )
        }
        launchCommand(GeminiPowerProtocol.dewPowerCommand(capability, boundedValue))
    }

    @Synchronized
    fun close() {
        if (_isConnected.value) {
            FileLogger.i(TAG, "Disconnected ${identity.orEmpty()} fw${firmware ?: 0}")
        }
        _isConnected.value = false
        readerJob?.cancel()
        readerJob = null
        runCatching { port?.close() }
        runCatching { connection?.close() }
        port = null
        connection = null
        connectedDeviceId = null
        identity = null
        firmware = null
        lastLoggedControlState = null
        statusFramesToLog = 0
        frameBuffer.clear()
        synchronized(dewModeLock) {
            lastRequestedDewModes.clear()
        }
        synchronized(usbStateLock) {
            lastRequestedUsbStates.clear()
        }
        _capabilities.value = null
        _telemetry.value = null
    }

    fun destroy() {
        close()
        scope.cancel()
    }

    private fun startReader() {
        readerJob?.cancel()
        readerJob = scope.launch {
            while (isActive && _isConnected.value) {
                val serialPort = port ?: break
                try {
                    readFrames(serialPort, 500).forEach { frame ->
                        if (frame.startsWith("*G")) {
                            val parsed = GeminiPowerProtocol.parseStatusDetailed(frame)
                            parsed.telemetry?.let { telemetry ->
                                logStatusFrameIfNeeded(frame, telemetry)
                                applyTelemetry(telemetry)
                            } ?: FileLogger.w(
                                TAG,
                                "Rejected status frame: ${parsed.error}; raw=${safeFrame(frame)}"
                            )
                        }
                    }
                    if (System.currentTimeMillis() - lastStatusAt > STREAM_RECOVERY_MS) {
                        FileLogger.d(TAG, "TX >G# reason=stream-recovery")
                        writeCommand(">G#")
                        lastStatusAt = System.currentTimeMillis()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    _lastError.value = e.message ?: "Gemini power box communication failed"
                    FileLogger.e(TAG, "Reader failed", e)
                    close()
                }
            }
        }
    }

    private fun applyTelemetry(value: GeminiPowerTelemetry) {
        val merged = value.copy(
            usbOutputs = synchronized(usbStateLock) {
                if (value.usbOutputs.size >= value.usbControlCount) {
                    lastRequestedUsbStates.clear()
                }
                GeminiPowerProtocol.mergeUsbOutputStates(
                    telemetry = value,
                    previous = _telemetry.value?.usbOutputs.orEmpty(),
                    requested = lastRequestedUsbStates
                )
            },
            dewHeaters = synchronized(dewModeLock) {
                value.dewHeaters.mapIndexed { index, heater ->
                    if (heater.mode != null) {
                        lastRequestedDewModes[index] = heater.mode
                        heater
                    } else {
                        heater.copy(mode = lastRequestedDewModes[index])
                    }
                }
            }
        )
        _telemetry.value = merged
        lastStatusAt = System.currentTimeMillis()
        val currentIdentity = identity ?: return
        val currentFirmware = firmware ?: return
        _capabilities.value = GeminiPowerProtocol.discoverCapabilities(
            currentIdentity,
            currentFirmware,
            merged
        )
    }

    private fun launchCommand(command: String) {
        if (!_isConnected.value || _capabilities.value?.canControlOutputs != true) return
        scope.launch {
            runCatching {
                commandMutex.withLock {
                    statusFramesToLog = 2
                    FileLogger.d(TAG, "TX $command")
                    writeCommand(command)
                }
            }
                .onFailure { _lastError.value = it.message }
        }
    }

    private fun launchUsbOutputCommand(
        capability: PowerOutputCapability,
        masterCapability: PowerOutputCapability?,
        enabled: Boolean
    ) {
        if (!_isConnected.value || _capabilities.value?.canControlOutputs != true) return
        scope.launch {
            runCatching {
                commandMutex.withLock {
                    val masterEnabled = _telemetry.value?.usbMasterEnabled
                    val commands = GeminiPowerProtocol.usbOutputCommandPlan(
                        capability = capability,
                        masterCapability = masterCapability,
                        masterEnabled = masterEnabled,
                        enabled = enabled
                    )
                    val masterCommand = masterCapability?.let {
                        GeminiPowerProtocol.outputCommand(it, true)
                    }
                    commands.forEach { command ->
                        val masterPrerequisite = command == masterCommand &&
                            enabled && masterEnabled != true && commands.size > 1
                        statusFramesToLog = 2
                        val reason = if (masterPrerequisite) {
                            "usb-master-prerequisite port=${capability.index + 1}"
                        } else {
                            "usb-port port=${capability.index + 1} enabled=$enabled"
                        }
                        FileLogger.d(TAG, "TX $command reason=$reason")
                        writeCommand(command)
                        if (masterPrerequisite) {
                            val confirmed = awaitUsbMasterEnabled()
                            if (confirmed) {
                                FileLogger.d(
                                    TAG,
                                    "USB master confirmed; settling " +
                                        "${USB_REENUMERATION_SETTLE_MS} ms before port " +
                                        "${capability.index + 1}"
                                )
                                delay(USB_REENUMERATION_SETTLE_MS)
                            } else {
                                FileLogger.w(
                                    TAG,
                                    "USB master confirmation timed out after " +
                                        "${USB_MASTER_CONFIRM_TIMEOUT_MS} ms; continuing with " +
                                        "port ${capability.index + 1}"
                                )
                            }
                        }
                    }
                }
            }.onFailure {
                _lastError.value = it.message
                FileLogger.e(
                    TAG,
                    "USB port ${capability.index + 1} command failed",
                    it
                )
            }
        }
    }

    private suspend fun awaitUsbMasterEnabled(): Boolean {
        val deadline = System.currentTimeMillis() + USB_MASTER_CONFIRM_TIMEOUT_MS
        while (isConnected.value && System.currentTimeMillis() < deadline) {
            if (_telemetry.value?.usbMasterEnabled == true) return true
            delay(100)
        }
        return _telemetry.value?.usbMasterEnabled == true
    }

    @Synchronized
    private fun writeCommand(command: String) {
        val serialPort = port ?: error("Gemini power box serial connection is closed")
        serialPort.write("$command\r\n".toByteArray(Charsets.US_ASCII), COMMAND_TIMEOUT_MS)
    }

    private fun exchangeForPrefix(
        serialPort: UsbSerialPort,
        command: String,
        expectedPrefix: String,
        timeoutMs: Int
    ): String? {
        FileLogger.d(TAG, "TX $command")
        writeCommand(command)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val wait = (deadline - System.currentTimeMillis()).toInt().coerceIn(50, 300)
            readFrames(serialPort, wait).firstOrNull { it.startsWith(expectedPrefix) }?.let {
                FileLogger.d(TAG, "RX ${safeFrame(it)}")
                return it
            }
        }
        return null
    }

    private fun exchangeForStatus(
        serialPort: UsbSerialPort,
        timeoutMs: Int
    ): StatusExchangeResult {
        FileLogger.d(TAG, "TX >G# reason=initial-status")
        writeCommand(">G#")
        val deadline = System.currentTimeMillis() + timeoutMs
        val observed = ArrayList<String>()
        var lastParseError: String? = null
        while (System.currentTimeMillis() < deadline) {
            val wait = (deadline - System.currentTimeMillis()).toInt().coerceIn(50, 300)
            val frames = readFrames(serialPort, wait)
            frames.forEach { frame ->
                if (observed.size < 8) observed += frame
                if (frame.startsWith("*G")) {
                    val parsed = GeminiPowerProtocol.parseStatusDetailed(frame)
                    parsed.telemetry?.let {
                        logStatusFrameIfNeeded(frame, it, force = true)
                        return StatusExchangeResult(it, observed, lastParseError)
                    }
                    lastParseError = parsed.error
                    FileLogger.w(
                        TAG,
                        "Rejected initial status frame: ${parsed.error}; raw=${safeFrame(frame)}"
                    )
                }
            }
        }
        return StatusExchangeResult(rawFrames = observed, parseError = lastParseError)
    }

    private fun buildStatusFailure(result: StatusExchangeResult): String {
        if (result.rawFrames.isEmpty()) {
            return "Gemini power box status timed out after ${FIRST_STATUS_TIMEOUT_MS} ms"
        }
        val raw = result.rawFrames.joinToString(" | ") { safeFrame(it) }
        return if (result.parseError != null) {
            "Gemini power box status parse failed: ${result.parseError}; raw=$raw"
        } else {
            "Gemini power box did not return a *G status frame; raw=$raw"
        }
    }

    private fun logStatusFrameIfNeeded(
        frame: String,
        telemetry: GeminiPowerTelemetry,
        force: Boolean = false
    ) {
        val controlState = buildString {
            append("dc=")
            telemetry.dcOutputs.forEach { append(if (it) '1' else '0') }
            append(",usb=")
            telemetry.usbMasterEnabled?.let { append(if (it) 'M' else 'm').append(':') }
            telemetry.usbOutputs.forEach { append(if (it) '1' else '0') }
            append('/').append(telemetry.usbControlCount)
            append(",dew=")
            telemetry.dewHeaters.forEachIndexed { index, heater ->
                if (index > 0) append(';')
                append(if (heater.enabled) '1' else '0')
                append(':').append(heater.mode?.name ?: "UNKNOWN")
                append(':').append(heater.outputValue)
            }
        }
        val pending = statusFramesToLog
        if (force || pending > 0 || controlState != lastLoggedControlState) {
            FileLogger.d(TAG, "RX ${safeFrame(frame)} parsed=$controlState")
            lastLoggedControlState = controlState
            if (pending > 0) statusFramesToLog = pending - 1
        }
    }

    private fun safeFrame(frame: String): String = frame
        .map { char -> if (char.code in 32..126) char else '?' }
        .joinToString("")
        .take(512)

    private fun readFrames(serialPort: UsbSerialPort, timeoutMs: Int): List<String> {
        val buffer = ByteArray(512)
        val count = serialPort.read(buffer, timeoutMs)
        if (count <= 0) return emptyList()
        val frames = ArrayList<String>()
        for (i in 0 until count) {
            val char = (buffer[i].toInt() and 0xff).toChar()
            when (char) {
                '#' -> {
                    val frame = frameBuffer.toString().trim()
                    frameBuffer.clear()
                    if (frame.isNotEmpty()) frames += frame
                }
                '\r', '\n' -> if (frameBuffer.isNotEmpty() && frameBuffer[0] != '*') frameBuffer.clear()
                else -> {
                    if (frameBuffer.length >= 4096) frameBuffer.clear()
                    frameBuffer.append(char)
                }
            }
        }
        return frames
    }

    private fun drain(serialPort: UsbSerialPort) {
        val buffer = ByteArray(256)
        repeat(10) {
            if (runCatching { serialPort.read(buffer, 80) }.getOrDefault(0) <= 0) return
        }
    }

    private fun fail(message: String): Boolean {
        _lastError.value = message
        FileLogger.w(TAG, message)
        return false
    }
}
