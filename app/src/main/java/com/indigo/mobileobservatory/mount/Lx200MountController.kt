package com.indigo.mobileobservatory.mount

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.net.InetSocketAddress
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import java.util.Locale
import java.util.UUID
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
data class MountCoordinates(
    val raHours: Double,
    val decDeg: Double
) {
    val raDeg: Double get() = raHours * 15.0

    fun formatRa(): String {
        val totalSeconds = raHours * 3600.0
        val h = ((totalSeconds / 3600.0).toInt() % 24 + 24) % 24
        val m = ((totalSeconds - h * 3600.0) / 60.0).toInt()
        val s = totalSeconds - h * 3600.0 - m * 60.0
        return "%02dh %02dm %04.1fs".format(Locale.US, h, m, s)
    }

    fun formatDec(): String {
        val sign = if (decDeg < 0.0) "-" else "+"
        val abs = decDeg.absoluteValue
        val d = abs.toInt()
        val minutesFloat = (abs - d) * 60.0
        val m = minutesFloat.toInt()
        val s = (minutesFloat - m) * 60.0
        return "%s%02dd %02dm %04.1fs".format(Locale.US, sign, d, m, s)
    }
}

data class MountSite(
    val latitudeDeg: Double,
    val longitudeDeg: Double
) {
    fun format(): String {
        return "lat %.5f  lon %.5f".format(Locale.US, latitudeDeg, longitudeDeg)
    }
}

sealed class MountConnectionState {
    data object Disconnected : MountConnectionState()
    data object Connecting : MountConnectionState()
    data object Connected : MountConnectionState()
    data class Error(val message: String) : MountConnectionState()
}

enum class MountTransportType {
    TCP,
    USB_SERIAL,
    BLUETOOTH,
    SYNSCAN_WIFI
}

enum class MountProtocolType {
    AUTO,
    LX200_ONSTEP,
    IOPTRON,
    SKYWATCHER
}

enum class MountDirection(val command: String, val stopCommand: String) {
    NORTH(":Mn#", ":Qn#"),
    SOUTH(":Ms#", ":Qs#"),
    EAST(":Me#", ":Qe#"),
    WEST(":Mw#", ":Qw#")
}

/**
 * Manual move rates aligned with OnStep `:R0#`…`:R9#`.
 *
 * Stable OnStep: 0.25x, 0.5x, 1x, 2x, 4x, 8x, 16x, 24x, 40x, 60x(Max).
 * [classicCommand] maps each step to the nearest named LX200 alias
 * (`:RG#` / `:RC#` / `:RM#` / `:RS#`) for documentation and legacy prefs.
 */
enum class MountSlewRate(val label: String, val index: Int) {
    RATE_0_25X("0.25×", 0),
    RATE_0_5X("0.5×", 1),
    RATE_1X("1×", 2),
    RATE_2X("2×", 3),
    RATE_4X("4×", 4),
    RATE_8X("8×", 5),
    RATE_16X("16×", 6),
    RATE_24X("24×", 7),
    RATE_HALF("½ Max", 8),
    RATE_MAX("Max", 9);

    val command: String get() = ":R$index#"

    /** Nearest classic LX200 named rate (also OnStep aliases for R2/R4/R5/R7). */
    val classicCommand: String
        get() = when (index) {
            0, 1, 2 -> ":RG#"
            3, 4 -> ":RC#"
            5, 6 -> ":RM#"
            else -> ":RS#"
        }

    val ioptronValue: Int get() = (index + 1).coerceIn(1, 9)

    val skyWatcherRate: Int get() = index.coerceIn(0, 9)

    companion object {
        val DEFAULT = RATE_8X
        /** Pulse-guide / autoguide baseline; matches OnStep `:RG#` = 1×. */
        val GUIDE = RATE_1X

        fun fromStoredName(raw: String?): MountSlewRate {
            if (raw.isNullOrBlank()) return DEFAULT
            return when (raw) {
                "GUIDE" -> RATE_1X
                "CENTER" -> RATE_4X
                "MOVE" -> RATE_8X
                "SLEW" -> RATE_MAX
                else -> runCatching { valueOf(raw) }.getOrDefault(DEFAULT)
            }
        }
    }
}

data class MountUsbDevice(
    val deviceId: Int,
    val label: String,
    val vendorId: Int,
    val productId: Int
)

data class MountBluetoothDevice(
    val address: String,
    val label: String
)

private data class SerialLineMode(
    val name: String,
    val dtr: Boolean,
    val rts: Boolean,
    val delayMs: Long
)

class Lx200MountController {
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    private var serialPort: UsbSerialPort? = null
    private var serialConnection: UsbDeviceConnection? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var synScanSocket: DatagramSocket? = null
    private var skyWatcherAdapter: SkyWatcherAdapter? = null
    @Volatile private var permanentlyClosed = false

    var activeProtocol: MountProtocolType = MountProtocolType.AUTO
        private set
    var mountModel: String? = null
        private set

    val isConnected: Boolean
        get() = (socket?.isConnected == true && socket?.isClosed == false) ||
            serialPort != null ||
            (bluetoothSocket?.isConnected == true) ||
            (synScanSocket?.isClosed == false)

    suspend fun connect(
        host: String,
        port: Int,
        timeoutMs: Int = 5000,
        protocol: MountProtocolType = MountProtocolType.AUTO
    ): MountCoordinates = withContext(Dispatchers.IO) {
        connectTcp(host, port, timeoutMs, protocol)
    }

    suspend fun connectTcp(
        host: String,
        port: Int,
        timeoutMs: Int = 5000,
        protocol: MountProtocolType = MountProtocolType.AUTO
    ): MountCoordinates = withContext(Dispatchers.IO) {
        check(!permanentlyClosed) { "Mount controller is closed." }
        disconnect()
        val newSocket = Socket()
        socket = newSocket
        newSocket.tcpNoDelay = true
        newSocket.soTimeout = timeoutMs
        newSocket.connect(InetSocketAddress(host, port), timeoutMs)
        check(!permanentlyClosed) { "Mount controller is closed." }
        input = BufferedInputStream(newSocket.getInputStream())
        output = BufferedOutputStream(newSocket.getOutputStream())
        detectProtocol(protocol)
        readCoordinates()
    }

    suspend fun connectUsb(
        context: Context,
        deviceId: Int?,
        baudRate: Int = 9600,
        protocol: MountProtocolType = MountProtocolType.AUTO
    ): MountCoordinates = withContext(Dispatchers.IO) {
        check(!permanentlyClosed) { "Mount controller is closed." }
        disconnect()
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val driver = UsbSerialProber.getDefaultProber()
            .findAllDrivers(usbManager)
            .firstOrNull { deviceId == null || it.device.deviceId == deviceId }
            ?: error("No USB serial mount found.")
        if (!usbManager.hasPermission(driver.device)) {
            error("USB permission required for ${driver.device.deviceName}.")
        }
        val baudCandidates = if (protocol == MountProtocolType.LX200_ONSTEP) {
            listOf(baudRate)
        } else {
            listOf(baudRate, 115200, 9600).distinct()
        }
        val failures = mutableListOf<String>()
        for (candidateBaud in baudCandidates) {
            check(!permanentlyClosed) { "Mount controller is closed." }
            val connection = usbManager.openDevice(driver.device)
                ?: error("Unable to open USB serial device.")
            val port = driver.ports.firstOrNull()
                ?: error("USB serial device has no port.")
            serialConnection = connection
            serialPort = port
            try {
                Log.i(TAG, "Opening USB serial mount ${driver.device.deviceName} vid=${driver.device.vendorId} pid=${driver.device.productId} baud=$candidateBaud ports=${driver.ports.size}")
                port.open(connection)
                port.setParameters(
                    candidateBaud,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                check(!permanentlyClosed) { "Mount controller is closed." }

                if (protocol != MountProtocolType.LX200_ONSTEP &&
                    protocol != MountProtocolType.SKYWATCHER) {
                    runCatching { port.setDTR(false) }
                    runCatching { port.setRTS(false) }
                    Thread.sleep(500)
                    drainSerialInput(port)
                    val modelCode = runCatching { probeIoptron() }.getOrNull()
                    if (modelCode != null) {
                        activeProtocol = MountProtocolType.IOPTRON
                        mountModel = IOPTRON_MODELS[modelCode] ?: "iOptron ($modelCode)"
                        return@withContext readCoordinates()
                    }
                    drainSerialInput(port)
                }

                if (protocol != MountProtocolType.LX200_ONSTEP &&
                    protocol != MountProtocolType.IOPTRON) {
                    val adapter = runCatching {
                        SkyWatcherAdapter(::exchangeSkyWatcher).also { it.open() }
                    }.getOrNull()
                    if (adapter != null) {
                        skyWatcherAdapter = adapter
                        activeProtocol = MountProtocolType.SKYWATCHER
                        mountModel = adapter.modelName
                        return@withContext adapter.readCoordinates()
                    }
                    drainSerialInput(port)
                }

                if (protocol == MountProtocolType.IOPTRON ||
                    protocol == MountProtocolType.SKYWATCHER ||
                    candidateBaud != baudRate) {
                    error("No ${protocol.name} response at $candidateBaud baud.")
                }

                verifySerialHandshake(port, MountProtocolType.LX200_ONSTEP)
                return@withContext readCoordinates()
            } catch (e: Throwable) {
                failures += "$candidateBaud: ${e.message}"
                runCatching { port.close() }
                runCatching { connection.close() }
                serialPort = null
                serialConnection = null
            }
        }
        error("Mount USB protocol detection failed. ${failures.joinToString("; ")}")
    }

    suspend fun connectSynScanWifi(
        host: String,
        port: Int = 11882
    ): MountCoordinates = withContext(Dispatchers.IO) {
        check(!permanentlyClosed) { "Mount controller is closed." }
        disconnect()
        val address = InetAddress.getByName(host)
        val udp = DatagramSocket()
        synScanSocket = udp
        try {
            udp.soTimeout = SERIAL_TIMEOUT_MS
            udp.connect(address, port)
            check(!permanentlyClosed) { "Mount controller is closed." }
            val adapter = SkyWatcherAdapter(::exchangeSkyWatcher)
            val coordinates = adapter.open()
            skyWatcherAdapter = adapter
            activeProtocol = MountProtocolType.SKYWATCHER
            mountModel = adapter.modelName
            coordinates
        } catch (e: Throwable) {
            udp.close()
            synScanSocket = null
            skyWatcherAdapter = null
            throw IllegalStateException(
                "SynScan Wi-Fi connection failed. Keep SynScan App connected and aligned; use UDP 127.0.0.1:11882. ${e.message}",
                e
            )
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun connectBluetooth(
        context: Context,
        address: String,
        protocol: MountProtocolType = MountProtocolType.AUTO,
        onStage: (RfcommMode) -> Unit = {}
    ): MountCoordinates = withContext(Dispatchers.IO) {
        check(!permanentlyClosed) { "Mount controller is closed." }
        disconnect()
        requireBluetoothPermission(context, requireScan = true)
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: error("Bluetooth is unavailable on this phone.")
        require(adapter.isEnabled) { "Bluetooth is disabled." }
        val device = adapter.bondedDevices.firstOrNull { it.address == address }
            ?: error("Pair the mount Bluetooth module in Android settings first.")
        adapter.cancelDiscovery()

        val runner = RfcommConnectionRunner(
            timeoutMs = BLUETOOTH_CONNECT_TIMEOUT_MS,
            createSocket = { mode ->
                val created = when (mode) {
                    RfcommMode.STANDARD ->
                        device.createRfcommSocketToServiceRecord(SPP_UUID)
                    RfcommMode.COMPATIBLE ->
                        device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
                }
                bluetoothSocket = created
                created
            },
            connectSocket = { candidate ->
                connectBluetoothSocket(candidate)
                check(!permanentlyClosed) { "Mount controller is closed." }
                input = BufferedInputStream(candidate.inputStream)
                output = BufferedOutputStream(candidate.outputStream)
                Thread.sleep(300)
                drainTransportInput()
                detectProtocol(protocol)
            },
            closeSocket = { candidate -> closeBluetoothCandidate(candidate) },
            onStage = onStage
        )
        val connectedSocket = runner.connect()
        bluetoothSocket = connectedSocket
        readCoordinates()
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectBluetoothSocket(candidate: BluetoothSocket) {
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                runCatching { candidate.close() }
            }
            try {
                candidate.connect()
                if (continuation.isActive) continuation.resume(Unit)
                else runCatching { candidate.close() }
            } catch (failure: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }
    }

    private fun closeBluetoothCandidate(candidate: BluetoothSocket) {
        runCatching { candidate.close() }
        if (bluetoothSocket === candidate) {
            bluetoothSocket = null
            input = null
            output = null
            skyWatcherAdapter = null
            activeProtocol = MountProtocolType.AUTO
            mountModel = null
        }
    }

    fun cancelPendingBluetoothConnection() {
        val candidate = bluetoothSocket ?: return
        closeBluetoothCandidate(candidate)
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        closeTransports()
    }

    /** Permanently releases every mount transport. Safe to call repeatedly during teardown. */
    fun close() {
        if (permanentlyClosed) return
        runCatching {
            if (isConnected) emergencyStop()
        }
        permanentlyClosed = true
        closeTransports()
    }

    private fun closeTransports() {
        val oldInput = input
        val oldOutput = output
        val oldSocket = socket
        val oldSerialPort = serialPort
        val oldSerialConnection = serialConnection
        val oldBluetoothSocket = bluetoothSocket
        val oldSynScanSocket = synScanSocket

        input = null
        output = null
        socket = null
        serialPort = null
        serialConnection = null
        bluetoothSocket = null
        synScanSocket = null
        skyWatcherAdapter = null
        activeProtocol = MountProtocolType.AUTO
        mountModel = null

        runCatching { oldInput?.close() }
        runCatching { oldOutput?.close() }
        runCatching { oldSocket?.close() }
        runCatching { oldSerialPort?.close() }
        runCatching { oldSerialConnection?.close() }
        runCatching { oldBluetoothSocket?.close() }
        runCatching { oldSynScanSocket?.close() }
    }

    suspend fun readCoordinates(): MountCoordinates = withContext(Dispatchers.IO) {
        skyWatcherAdapter?.let { return@withContext it.readCoordinates() }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            val response = sendFixedCommand(":GEP#", 20)
            return@withContext MountProtocolCodec.parseIoptronCoordinates(response)
        }
        MountProtocolCodec.parseLx200Coordinates(
            raResponse = sendCommand(":GR#"),
            decResponse = sendCommand(":GD#")
        )
    }

    suspend fun readSite(): MountSite = withContext(Dispatchers.IO) {
        skyWatcherAdapter?.let { return@withContext it.readSite() }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            val response = sendFixedCommand(":GLS#", 23)
            require(response.length == 23) { "Invalid iOptron site response: $response" }
            val longitude = response.substring(0, 9).toInt() / 360000.0
            val latitude = response.substring(9, 17).toInt() / 360000.0 - 90.0
            return@withContext MountSite(latitude, longitude)
        }
        val latitude = MountProtocolCodec.parseDmsDegrees(sendCommand(":Gt#"))
        val lx200Longitude = MountProtocolCodec.parseDmsDegrees(sendCommand(":Gg#"))
        MountSite(latitude, -lx200Longitude)
    }

    suspend fun setSite(site: MountSite) = withContext(Dispatchers.IO) {
        skyWatcherAdapter?.let {
            it.setSite(site)
            return@withContext
        }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            val lon = (site.longitudeDeg.absoluteValue * 360000.0).roundToInt()
            val lat = (site.latitudeDeg.absoluteValue * 360000.0).roundToInt()
            val lonCommand = ":SLO${if (site.longitudeDeg < 0) "-" else "+"}%08d#"
                .format(Locale.US, lon)
            val latCommand = ":SLA${if (site.latitudeDeg < 0) "-" else "+"}%08d#"
                .format(Locale.US, lat)
            if (!sendIoptronOk(lonCommand) || !sendIoptronOk(latCommand)) {
                error("iOptron mount rejected site coordinates.")
            }
            return@withContext
        }
        val latOk = sendBooleanCommand(":St${formatLatitude(site.latitudeDeg)}#")
        val lonOk = sendBooleanCommand(":Sg${formatLongitude(-site.longitudeDeg)}#")
        if (!latOk || !lonOk) error("Mount rejected site coordinates.")
    }

    suspend fun slewTo(coordinates: MountCoordinates) = withContext(Dispatchers.IO) {
        require(coordinates.raHours.isFinite()) { "Target RA is invalid." }
        require(coordinates.decDeg.isFinite() && coordinates.decDeg in -90.0..90.0) {
            "Target Dec is invalid."
        }
        skyWatcherAdapter?.let {
            it.slewTo(coordinates)
            return@withContext
        }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            val raCas = (coordinates.raHours.mod(24.0) * 15.0 * 360000.0).roundToInt()
            val decCas = (coordinates.decDeg.absoluteValue * 360000.0).roundToInt()
            val raCommand = ":SRA%09d#".format(Locale.US, raCas)
            val decCommand = ":Sd${if (coordinates.decDeg < 0) "-" else "+"}%08d#"
                .format(Locale.US, decCas)
            if (!sendIoptronOk(raCommand) || !sendIoptronOk(decCommand) ||
                !sendIoptronOk(":MS1#")) {
                error("iOptron mount rejected GOTO.")
            }
            return@withContext
        }
        val commands = MountProtocolCodec.encodeLx200Goto(coordinates)
        val raCommand = commands.ra
        val decCommand = commands.dec
        Log.i(
            TAG,
            "Mount GOTO RA=${coordinates.formatRa()} Dec=${coordinates.formatDec()}"
        )
        if (!sendBooleanCommand(raCommand)) error("Mount rejected target RA.")
        if (!sendBooleanCommand(decCommand)) error("Mount rejected target Dec.")
        sendSlewCommand()
    }

    /**
     * Visual sync: tell the mount that its current pointing equals [coordinates].
     * Does not move the mount.
     */
    suspend fun syncTo(coordinates: MountCoordinates) = withContext(Dispatchers.IO) {
        require(coordinates.raHours.isFinite()) { "Sync RA is invalid." }
        require(coordinates.decDeg.isFinite() && coordinates.decDeg in -90.0..90.0) {
            "Sync Dec is invalid."
        }
        if (skyWatcherAdapter != null) {
            error("Sky-Watcher SynScan sync is not supported here. Align in SynScan first.")
        }
        Log.i(
            TAG,
            "Mount SYNC RA=${coordinates.formatRa()} Dec=${coordinates.formatDec()} " +
                "protocol=$activeProtocol"
        )
        if (activeProtocol == MountProtocolType.IOPTRON) {
            val raCas = (coordinates.raHours.mod(24.0) * 15.0 * 360000.0).roundToInt()
            val decCas = (coordinates.decDeg.absoluteValue * 360000.0).roundToInt()
            val raCommand = ":SRA%09d#".format(Locale.US, raCas)
            val decCommand = ":Sd${if (coordinates.decDeg < 0) "-" else "+"}%08d#"
                .format(Locale.US, decCas)
            if (!sendIoptronOk(raCommand) || !sendIoptronOk(decCommand) ||
                !sendIoptronOk(":CM#")) {
                error("iOptron mount rejected sync.")
            }
            return@withContext
        }
        val commands = MountProtocolCodec.encodeLx200Goto(coordinates)
        if (!sendBooleanCommand(commands.ra)) error("Mount rejected sync RA.")
        if (!sendBooleanCommand(commands.dec)) error("Mount rejected sync Dec.")
        val reply = sendCommand(":CM#").trim().trimEnd('#')
        if (reply.startsWith("E", ignoreCase = true) || reply == "0") {
            error("Mount rejected sync: $reply")
        }
    }

    suspend fun startRaMove(east: Boolean) = withContext(Dispatchers.IO) {
        startMove(if (east) MountDirection.EAST else MountDirection.WEST)
    }

    suspend fun stopRaMove() = withContext(Dispatchers.IO) {
        stopMove()
    }

    suspend fun startMove(direction: MountDirection) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Mount manual move start ${direction.name} command=${direction.command}")
        skyWatcherAdapter?.let {
            it.startMove(direction)
            return@withContext
        }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            val command = when (direction) {
                MountDirection.NORTH -> ":mn#"
                MountDirection.SOUTH -> ":ms#"
                MountDirection.EAST -> ":mw#"
                MountDirection.WEST -> ":me#"
            }
            sendNoReplyCommand(command)
        } else {
            sendNoReplyCommand(direction.command)
        }
    }

    suspend fun stopMove(direction: MountDirection? = null) = withContext(Dispatchers.IO) {
        stopMoveBlocking(direction)
    }

    /** Sends a physical stop immediately, then restores protocol synchronization. */
    suspend fun abortMotion() = withContext(Dispatchers.IO) {
        emergencyStop()
        synchronized(this@Lx200MountController) {
            drainTransportInput()
            stopMoveBlocking()
        }
    }

    private fun stopMoveBlocking(direction: MountDirection? = null) {
        Log.i(TAG, "Mount manual move stop ${direction?.name ?: "ALL"}")
        skyWatcherAdapter?.let {
            it.stopMove(direction)
            return
        }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            if (direction == MountDirection.NORTH || direction == MountDirection.SOUTH) {
                runCatching { sendIoptronOk(":qD#") }
            } else if (direction == MountDirection.EAST || direction == MountDirection.WEST) {
                runCatching { sendIoptronOk(":qR#") }
            } else {
                runCatching { sendIoptronOk(":Q#") }
            }
        } else {
            if (direction != null) runCatching { sendNoReplyCommand(direction.stopCommand) }
            runCatching { sendNoReplyCommand(":Q#") }
        }
    }

    suspend fun setMoveRate(rate: MountSlewRate) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Mount move rate ${rate.name} command=${rate.command}")
        skyWatcherAdapter?.let {
            it.setMoveRate(rate)
            return@withContext
        }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            if (!sendIoptronOk(":SR${rate.ioptronValue}#")) {
                error("iOptron mount rejected slew rate.")
            }
        } else {
            // OnStep / LX200-OnStep path: `:R0#`…`:R9#`.
            sendNoReplyCommand(rate.command)
        }
    }

    suspend fun setTracking(enabled: Boolean) = withContext(Dispatchers.IO) {
        Log.i(TAG, "Mount tracking ${if (enabled) "on" else "off"}")
        skyWatcherAdapter?.let {
            it.setTracking(enabled)
            return@withContext
        }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            sendFixedCommand(if (enabled) ":ST1#" else ":ST0#", 1)
        } else {
            sendNoReplyCommand(if (enabled) ":Te#" else ":Td#")
        }
    }

    suspend fun goHome() = withContext(Dispatchers.IO) {
        skyWatcherAdapter?.let {
            it.goHome()
            return@withContext
        }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            if (!sendIoptronOk(":MH#")) error("iOptron mount rejected home command.")
        } else {
            sendNoReplyCommand(":hC#")
        }
    }

    suspend fun setHomeHere() = withContext(Dispatchers.IO) {
        skyWatcherAdapter?.let {
            it.setHomeHere()
            return@withContext
        }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            if (!sendIoptronOk(":SZP#")) error("iOptron mount rejected set-home command.")
        } else {
            sendNoReplyCommand(":hF#")
        }
    }

    private fun detectProtocol(preferred: MountProtocolType) {
        if (preferred == MountProtocolType.SKYWATCHER) {
            error("Sky-Watcher SynScan protocol uses USB or SynScan Wi-Fi transport.")
        }
        drainTransportInput()
        if (preferred != MountProtocolType.LX200_ONSTEP) {
            val modelCode = runCatching { probeIoptron() }.getOrNull()
            if (modelCode != null) {
                activeProtocol = MountProtocolType.IOPTRON
                mountModel = IOPTRON_MODELS[modelCode] ?: "iOptron ($modelCode)"
                return
            }
            drainTransportInput()
        }
        if (preferred == MountProtocolType.IOPTRON) {
            error("No iOptron V3 protocol response.")
        }
        val handshake = runCatching { sendCommand(":GR#", HANDSHAKE_TIMEOUT_MS) }
            .recoverCatching { sendCommand(":GVP#", HANDSHAKE_TIMEOUT_MS) }
            .getOrElse { error("No LX200/OnStep protocol response.") }
        require(handshake.isNotBlank()) { "Empty LX200/OnStep handshake response." }
        activeProtocol = MountProtocolType.LX200_ONSTEP
        mountModel = runCatching { sendCommand(":GVP#", HANDSHAKE_TIMEOUT_MS) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: "LX200 / OnStep"
    }

    private fun probeIoptron(): String {
        val response = sendFixedCommand(":MountInfo#", 4, HANDSHAKE_TIMEOUT_MS)
        require(response.matches(Regex("\\d{4}"))) { "Invalid iOptron model response: $response" }
        return response
    }

    private fun sendIoptronOk(command: String): Boolean {
        return sendFixedCommand(command, 1) == "1"
    }

    @Synchronized
    private fun exchangeSkyWatcher(
        payload: ByteArray,
        fixedResponseLength: Int?
    ): ByteArray {
        val udp = synScanSocket
        if (udp != null) {
            val outgoing = DatagramPacket(payload, payload.size)
            synchronized(udp) {
                udp.send(outgoing)
            }
            val buffer = ByteArray(256)
            val incoming = DatagramPacket(buffer, buffer.size)
            udp.receive(incoming)
            return incoming.data.copyOf(incoming.length)
        }

        val port = serialPort ?: error("Sky-Watcher transport is closed.")
        drainSerialInput(port)
        synchronized(port) {
            port.write(payload, SERIAL_TIMEOUT_MS)
        }
        val response = ArrayList<Byte>(32)
        val buffer = ByteArray(64)
        val deadline = System.currentTimeMillis() + SERIAL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val count = runCatching { port.read(buffer, 200) }.getOrDefault(0)
            for (index in 0 until count) {
                val byte = buffer[index]
                response += byte
                if (fixedResponseLength != null && response.size >= fixedResponseLength) {
                    return response.take(fixedResponseLength).toByteArray()
                }
                if (fixedResponseLength == null && byte == '#'.code.toByte()) {
                    return response.toByteArray()
                }
            }
        }
        error("Sky-Watcher response timeout.")
    }

    @Synchronized
    private fun sendFixedCommand(
        command: String,
        count: Int,
        timeoutMs: Int = SERIAL_TIMEOUT_MS
    ): String {
        writeBytes(command.toByteArray(Charsets.US_ASCII))
        val bytes = ByteArray(count)
        var offset = 0
        val deadline = System.currentTimeMillis() + timeoutMs
        while (offset < count && System.currentTimeMillis() < deadline) {
            val port = serialPort
            if (port != null) {
                val chunk = ByteArray(count - offset)
                val read = runCatching { port.read(chunk, 200) }.getOrDefault(0)
                if (read > 0) {
                    System.arraycopy(chunk, 0, bytes, offset, read.coerceAtMost(count - offset))
                    offset += read.coerceAtMost(count - offset)
                }
            } else {
                val inp = input ?: error("Mount is not connected.")
                if (inp.available() > 0) {
                    val read = inp.read(bytes, offset, count - offset)
                    if (read > 0) offset += read
                } else {
                    Thread.sleep(10)
                }
            }
        }
        if (offset != count) error("Mount response timeout: expected $count bytes, received $offset.")
        return bytes.toString(Charsets.US_ASCII)
    }

    @Synchronized
    private fun sendCommand(command: String, timeoutMs: Int = SERIAL_TIMEOUT_MS): String {
        val port = serialPort
        if (port != null) {
            return sendSerialCommand(port, command, timeoutMs)
        }
        writeBytes(command.toByteArray(Charsets.US_ASCII))
        val bytes = ArrayList<Byte>(32)
        while (true) {
            val value = readByte(timeoutMs)
            if (value < 0) error("Mount closed the connection.")
            if (value == '#'.code) break
            bytes.add(value.toByte())
            if (bytes.size > 128) error("Mount response is too long.")
        }
        return bytes.toByteArray().toString(Charsets.US_ASCII).trim()
    }

    private fun sendSerialCommand(port: UsbSerialPort, command: String, timeoutMs: Int): String {
        synchronized(port) {
            port.write(command.toByteArray(Charsets.US_ASCII), SERIAL_TIMEOUT_MS)
        }
        val bytes = ArrayList<Byte>(32)
        val buffer = ByteArray(64)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val remaining = (deadline - System.currentTimeMillis()).toInt().coerceIn(50, 250)
            val count = runCatching { port.read(buffer, remaining) }.getOrDefault(0)
            if (count <= 0) continue
            val chunk = buffer.copyOf(count)
            Log.i(TAG, "USB serial rx ${chunk.toHexString()} ${chunk.toPrintableString()}")
            for (byte in chunk) {
                val value = byte.toInt() and 0xff
                if (value == '#'.code) {
                    return bytes.toByteArray().toString(Charsets.US_ASCII).trim()
                }
                bytes.add(byte)
                if (bytes.size > 128) error("Mount response is too long.")
            }
        }
        error("Mount serial read timeout.")
    }

    @Synchronized
    private fun sendBooleanCommand(command: String): Boolean {
        writeBytes(command.toByteArray(Charsets.US_ASCII))
        return when (val value = readByte()) {
            '1'.code -> true
            '0'.code -> false
            else -> error("Unexpected mount reply: $value")
        }
    }

    @Synchronized
    private fun sendNoReplyCommand(command: String) {
        writeBytes(command.toByteArray(Charsets.US_ASCII))
    }

    @Synchronized
    private fun sendSlewCommand() {
        writeBytes(":MS#".toByteArray(Charsets.US_ASCII))
        when (val code = readByte()) {
            '0'.code -> consumeOptionalTerminator()
            '1'.code -> error("Mount rejected GOTO: target is below the horizon.")
            '2'.code -> error("Mount rejected GOTO: target exceeds the slew limit.")
            else -> error("Unexpected mount GOTO reply: $code")
        }
    }

    private fun consumeOptionalTerminator() {
        val port = serialPort
        if (port != null) {
            val buffer = ByteArray(1)
            val count = runCatching { port.read(buffer, OPTIONAL_REPLY_TIMEOUT_MS) }.getOrDefault(0)
            if (count > 0 && buffer[0].toInt() != '#'.code) {
                Log.w(TAG, "Unexpected byte after GOTO acknowledgement: ${buffer[0]}")
            }
            return
        }
        val tcpSocket = socket ?: return
        val previousTimeout = tcpSocket.soTimeout
        try {
            tcpSocket.soTimeout = OPTIONAL_REPLY_TIMEOUT_MS
            val value = runCatching { input?.read() ?: -1 }.getOrDefault(-1)
            if (value >= 0 && value != '#'.code) {
                Log.w(TAG, "Unexpected byte after GOTO acknowledgement: $value")
            }
        } finally {
            tcpSocket.soTimeout = previousTimeout
        }
    }

    private fun emergencyStop() {
        if (activeProtocol == MountProtocolType.SKYWATCHER) {
            skyWatcherEmergencyStopPayloads().forEach(::emergencyWriteBytes)
        } else {
            emergencyWriteBytes(":Q#".toByteArray(Charsets.US_ASCII))
        }
    }

    private fun skyWatcherEmergencyStopPayloads(): List<ByteArray> {
        return listOf(
            byteArrayOf('P'.code.toByte(), 2, 16, 36, 0, 0, 0, 0),
            byteArrayOf('P'.code.toByte(), 2, 16, 37, 0, 0, 0, 0),
            byteArrayOf('P'.code.toByte(), 2, 17, 36, 0, 0, 0, 0),
            byteArrayOf('P'.code.toByte(), 2, 17, 37, 0, 0, 0, 0)
        )
    }

    private fun emergencyWriteBytes(bytes: ByteArray) {
        val udp = synScanSocket
        if (udp != null) {
            synchronized(udp) {
                udp.send(DatagramPacket(bytes, bytes.size))
            }
            return
        }
        val port = serialPort
        if (port != null) {
            synchronized(port) {
                port.write(bytes, EMERGENCY_STOP_TIMEOUT_MS)
            }
            return
        }
        val out = output ?: error("Mount is not connected.")
        synchronized(out) {
            out.write(bytes)
            out.flush()
        }
    }

    private fun writeBytes(bytes: ByteArray) {
        val port = serialPort
        if (port != null) {
            synchronized(port) {
                port.write(bytes, SERIAL_TIMEOUT_MS)
            }
            return
        }
        val out = output ?: error("Mount is not connected.")
        synchronized(out) {
            out.write(bytes)
            out.flush()
        }
    }

    private fun readByte(timeoutMs: Int = SERIAL_TIMEOUT_MS): Int {
        val port = serialPort
        if (port != null) {
            val buffer = ByteArray(1)
            val count = port.read(buffer, timeoutMs)
            if (count <= 0) error("Mount serial read timeout.")
            return buffer[0].toInt() and 0xff
        }
        val inp = input ?: error("Mount is not connected.")
        if (bluetoothSocket != null) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (inp.available() > 0) return inp.read()
                Thread.sleep(10)
            }
            error("Mount Bluetooth read timeout.")
        }
        return inp.read()
    }

    private fun verifySerialHandshake(
        port: UsbSerialPort,
        preferred: MountProtocolType
    ) {
        val failures = ArrayList<String>()
        for (mode in SERIAL_LINE_MODES) {
            Log.i(TAG, "USB serial handshake try mode=${mode.name}")
            runCatching { port.setDTR(mode.dtr) }
            runCatching { port.setRTS(mode.rts) }
            Thread.sleep(mode.delayMs)
            drainSerialInput(port)
            if (preferred != MountProtocolType.LX200_ONSTEP) {
                val modelCode = runCatching { probeIoptron() }.getOrNull()
                if (modelCode != null) {
                    activeProtocol = MountProtocolType.IOPTRON
                    mountModel = IOPTRON_MODELS[modelCode] ?: "iOptron ($modelCode)"
                    return
                }
                if (preferred == MountProtocolType.IOPTRON) {
                    failures.add("${mode.name} iOptron: no :MountInfo# response")
                    drainSerialInput(port)
                    continue
                }
                drainSerialInput(port)
            }
            for (command in HANDSHAKE_COMMANDS) {
                Log.i(TAG, "USB serial handshake send $command mode=${mode.name}")
                val result = runCatching { sendCommand(command, HANDSHAKE_TIMEOUT_MS) }
                if (result.isSuccess) {
                    Log.i(TAG, "USB serial mount handshake ok mode=${mode.name} command=$command reply=${result.getOrNull()}")
                    activeProtocol = MountProtocolType.LX200_ONSTEP
                    mountModel = if (command == ":GVP#") {
                        result.getOrNull()
                    } else {
                        runCatching { sendCommand(":GVP#", HANDSHAKE_TIMEOUT_MS) }
                            .getOrNull()
                    }?.takeIf { it.isNotBlank() } ?: "LX200 / OnStep"
                    return
                }
                failures.add("${mode.name} $command: ${result.exceptionOrNull()?.message ?: "failed"}")
                drainSerialInput(port)
            }
        }
        throw IllegalStateException("Mount serial no LX200 response. Tried ${failures.joinToString("; ")}")
    }

    private fun drainTransportInput() {
        serialPort?.let {
            drainSerialInput(it)
            return
        }
        val inp = input ?: return
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + SERIAL_DRAIN_MAX_MS
        while (System.currentTimeMillis() < deadline) {
            val available = runCatching { inp.available() }.getOrDefault(0)
            if (available <= 0) break
            val count = runCatching {
                inp.read(buffer, 0, available.coerceAtMost(buffer.size))
            }.getOrDefault(0)
            if (count <= 0) break
        }
    }

    private fun drainSerialInput(port: UsbSerialPort) {
        val buffer = ByteArray(256)
        val deadline = System.currentTimeMillis() + SERIAL_DRAIN_MAX_MS
        var total = 0
        while (System.currentTimeMillis() < deadline && total < SERIAL_DRAIN_MAX_BYTES) {
            val count = runCatching { port.read(buffer, 100) }.getOrDefault(0)
            if (count <= 0) break
            val chunk = buffer.copyOf(count)
            Log.i(TAG, "USB serial drain rx ${chunk.toHexString()} ${chunk.toPrintableString()}")
            total += count
        }
        if (total > 0) {
            Log.i(TAG, "USB serial drained $total bytes")
        }
    }

    private fun formatLatitude(latitudeDeg: Double): String {
        return formatSignedDms(latitudeDeg.coerceIn(-90.0, 90.0), 2)
    }

    private fun formatLongitude(lx200LongitudeDeg: Double): String {
        return formatSignedDms(lx200LongitudeDeg.coerceIn(-180.0, 180.0), 3)
    }

    private fun formatSignedDms(valueDeg: Double, degreeDigits: Int): String {
        val sign = if (valueDeg < 0.0) "-" else "+"
        val abs = valueDeg.absoluteValue
        val degrees = abs.toInt()
        val minutes = ((abs - degrees) * 60.0).toInt().coerceIn(0, 59)
        return "%s%0${degreeDigits}d*%02d".format(Locale.US, sign, degrees, minutes)
    }

    companion object {
        private const val TAG = "Lx200Mount"
        private const val SERIAL_TIMEOUT_MS = 5000
        private const val EMERGENCY_STOP_TIMEOUT_MS = 500
        private const val BLUETOOTH_CONNECT_TIMEOUT_MS = 15_000L
        private const val OPTIONAL_REPLY_TIMEOUT_MS = 150
        private const val HANDSHAKE_TIMEOUT_MS = 1500
        private const val SERIAL_DRAIN_MAX_MS = 700L
        private const val SERIAL_DRAIN_MAX_BYTES = 4096
        private val SPP_UUID: UUID =
            UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private val IOPTRON_MODELS = mapOf(
            "0010" to "SkyHunter EQ", "0011" to "SkyHunter AA",
            "0012" to "HAE16 EQ", "0013" to "HAE16 AA",
            "0014" to "HAE18 EQ", "0015" to "HEM15",
            "0022" to "HAE18 AA", "0025" to "HEM27",
            "0026" to "CEM26", "0027" to "CEM26-EC",
            "0028" to "GEM28", "0029" to "GEM28-EC",
            "0030" to "HEM27-EC", "0031" to "HAE29 EQ",
            "0032" to "HAE29-EC EQ", "0033" to "HAE29 AA",
            "0034" to "HAE29-EC AA", "0035" to "HAZ31",
            "0036" to "HAE29C EQ", "0037" to "HAE29C-EC EQ",
            "0038" to "HAE29C AA", "0039" to "HAE29C-EC AA",
            "0040" to "CEM40", "0041" to "CEM40-EC",
            "0043" to "GEM45", "0045" to "HEM44-EC",
            "0046" to "HEM44A", "0047" to "HEM44A-EC",
            "0048" to "HAE43 EQ", "0049" to "HAE43-EC EQ",
            "0050" to "HAE43 AA", "0051" to "HAE43-EC AA",
            "0052" to "HAZ46", "0053" to "HAE43C EQ",
            "0054" to "HAE43C-EC EQ", "0055" to "HAE43C AA",
            "0056" to "HAE43C-EC AA", "0060" to "CEM60",
            "0061" to "CEM60-EC", "0062" to "HAE69 EQ",
            "0063" to "HAE69-EC EQ", "0064" to "HAE69 AA",
            "0065" to "HAE69-EC AA", "0066" to "HAE69C EQ",
            "0067" to "HAE69C-EC EQ", "0068" to "HAE69C AA",
            "0069" to "HAE69C-EC AA", "0070" to "CEM70",
            "0071" to "CEM70-EC", "0072" to "CEM70-EC2",
            "0073" to "HAZ71", "0120" to "CEM120",
            "0121" to "CEM120-EC", "0122" to "CEM120-EC2",
            "5010" to "Cube II AA", "5035" to "AZ Mount Pro",
            "5045" to "iEQ45 Pro AA"
        )
        private val HANDSHAKE_COMMANDS = listOf(":GR#", ":GVP#")
        private val SERIAL_LINE_MODES = listOf(
            SerialLineMode("DTR0_RTS0", dtr = false, rts = false, delayMs = 800L),
            SerialLineMode("DTR1_RTS0", dtr = true, rts = false, delayMs = 3500L),
            SerialLineMode("DTR0_RTS1", dtr = false, rts = true, delayMs = 800L),
            SerialLineMode("DTR1_RTS1", dtr = true, rts = true, delayMs = 3500L)
        )

        fun listUsbDevices(context: Context): List<MountUsbDevice> {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            return UsbSerialProber.getDefaultProber()
                .findAllDrivers(usbManager)
                .map { driver ->
                    val device = driver.device
                    val label = buildString {
                        append(device.deviceName)
                        append("  ")
                        append("%04x:%04x".format(Locale.US, device.vendorId, device.productId))
                        if (driver.ports.size > 1) append("  ${driver.ports.size} ports")
                    }
                    MountUsbDevice(
                        deviceId = device.deviceId,
                        label = label,
                        vendorId = device.vendorId,
                        productId = device.productId
                    )
                }
        }

        @SuppressLint("MissingPermission")
        fun listBluetoothDevices(context: Context): List<MountBluetoothDevice> {
            requireBluetoothPermission(context)
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyList()
            return adapter.bondedDevices
                .sortedBy { it.name ?: it.address }
                .map {
                    MountBluetoothDevice(
                        address = it.address,
                        label = "${it.name ?: "Bluetooth device"}  ${it.address}"
                    )
                }
        }

        private fun requireBluetoothPermission(
            context: Context,
            requireScan: Boolean = false
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
            val connectDenied = context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
            val scanDenied = requireScan && context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
            if (connectDenied || scanDenied) {
                error("Nearby devices permission is required for Bluetooth mount access.")
            }
        }
    }
}

private fun ByteArray.toHexString(): String {
    return joinToString(" ") { "%02X".format(Locale.US, it.toInt() and 0xff) }
}

private fun ByteArray.toPrintableString(): String {
    return buildString {
        append('[')
        for (byte in this@toPrintableString) {
            val value = byte.toInt() and 0xff
            append(if (value in 32..126) value.toChar() else '.')
        }
        append(']')
    }
}
