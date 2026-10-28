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
import android.os.SystemClock
import com.indigo.mobileobservatory.pointing.PointingMountCapabilities
import android.util.Log
import com.indigo.mobileobservatory.util.FileLogger
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
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

data class MountTime(
    val epochMillis: Long
)

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

enum class SkyWatcherMountMode {
    EQUATORIAL,
    ALTAZ
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

class Lx200MountController(private val elapsedRealtime: () -> Long = { SystemClock.elapsedRealtime() }) {
    private val pointingAccess = MountPointingAccess()
    val accessMode get() = pointingAccess.accessMode
    val pointingCapabilities get() = pointingAccess.capabilities
    val modelRevision get() = pointingAccess.revision
    val axisObservation get() = pointingAccess.axisObservation
    fun setAccessMode(mode: MountAccessMode) = pointingAccess.setAccessMode(mode)
    private var injectedTestSession = false
    private var socket: Socket? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    @Volatile private var serialPort: UsbSerialPort? = null
    @Volatile private var serialConnection: UsbDeviceConnection? = null
    private var bluetoothSocket: BluetoothSocket? = null
    private var synScanSocket: DatagramSocket? = null
    private var skyWatcherAdapter: SkyWatcherMountSession? = null
    private val closeLock = Any()
    @Volatile private var permanentlyClosed = false

    var activeProtocol: MountProtocolType = MountProtocolType.AUTO
        private set
    var mountModel: String? = null
        private set
    private val isOnStepFirmware: Boolean
        get() = activeProtocol == MountProtocolType.LX200_ONSTEP &&
            mountModel?.filter(Char::isLetterOrDigit)?.contains("OnStep", ignoreCase = true) == true
    val supportsSync: Boolean
        get() = pointingCapabilities.value.sync
    val supportsTimeSync: Boolean
        get() = pointingCapabilities.value.writeTime

    val isConnected: Boolean
        get() = (socket?.isConnected == true && socket?.isClosed == false) ||
            serialPort != null ||
            (bluetoothSocket?.isConnected == true) ||
            (synScanSocket?.isClosed == false) || injectedTestSession

    private fun publishPointingConnection() {
        if (!isConnected || activeProtocol == MountProtocolType.AUTO) return
        val adapter = skyWatcherAdapter
        val capabilities = adapter?.pointingCapabilities ?: PointingMountCapabilities(readCoordinates = true,
            goto = true, sync = true, tracking = true, manualMove = true, home = true, setHome = true,
            readSite = true, writeSite = true, readTime = true, writeTime = true)
        pointingAccess.connected(capabilities, adapter?.coordinateMode ?: activeProtocol.name)
    }
    internal fun attachSessionForTesting(session: SkyWatcherMountSession) {
        skyWatcherAdapter = session; injectedTestSession = true; activeProtocol = MountProtocolType.SKYWATCHER
        mountModel = session.modelName; publishPointingConnection()
    }

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
            val port = driver.ports.firstOrNull()
                ?: error("USB serial device has no port.")
            val connection = usbManager.openDevice(driver.device)
                ?: error("Unable to open USB serial device.")
            serialConnection = connection
            serialPort = port
            try {
                check(!permanentlyClosed) { "Mount controller is closed." }
                FileLogger.i(
                    TAG,
                    "Opening USB serial mount ${driver.device.deviceName} " +
                        "vid=${driver.device.vendorId} pid=${driver.device.productId} " +
                        "id=${driver.device.deviceId} baud=$candidateBaud protocol=$protocol " +
                        "ports=${driver.ports.size}"
                )
                port.open(connection)
                port.setParameters(
                    candidateBaud,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                check(!permanentlyClosed) { "Mount controller is closed." }

                if (protocol == MountProtocolType.AUTO ||
                    protocol == MountProtocolType.LX200_ONSTEP
                ) {
                    if (handshakeLx200WithoutReset(port)) {
                        FileLogger.i(TAG, "USB OnStep ready baud=$candidateBaud model=$mountModel")
                        return@withContext readCoordinates()
                    }
                    FileLogger.i(TAG, "USB quick LX200 handshake missed baud=$candidateBaud")
                }

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
                        publishPointingConnection()
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
                FileLogger.w(TAG, "USB serial baud $candidateBaud failed: ${e.message}")
                runCatching { port.close() }
                    .onFailure { FileLogger.e(TAG, "USB serial port close after failed connect failed", it) }
                runCatching { connection.close() }
                    .onFailure { FileLogger.e(TAG, "USB connection close after failed connect failed", it) }
                serialPort = null
                serialConnection = null
            }
        }
        val summary = failures.joinToString("; ")
        FileLogger.e(TAG, "USB serial protocol detection failed. $summary")
        error("Mount USB protocol detection failed. $summary")
    }

    suspend fun connectSynScanWifi(
        host: String,
        port: Int = 11880,
        context: Context? = null,
        site: MountSite? = null,
        mode: SkyWatcherMountMode = SkyWatcherMountMode.EQUATORIAL
    ): MountCoordinates = withContext(Dispatchers.IO) {
        check(!permanentlyClosed) { "Mount controller is closed." }
        disconnect()
        val address = InetAddress.getByName(host)
        val udp = DatagramSocket()
        synScanSocket = udp
        try {
            context?.let { MountWifiNetwork.bindSocket(it, udp) }
            udp.soTimeout = HANDSHAKE_TIMEOUT_MS
            udp.connect(address, port)
            check(!permanentlyClosed) { "Mount controller is closed." }
            val adapter = openMotorSkyWatcherSession(site, mode)
            skyWatcherAdapter = adapter
            activeProtocol = MountProtocolType.SKYWATCHER
            mountModel = adapter.modelName
            udp.soTimeout = SERIAL_TIMEOUT_MS
            publishPointingConnection()
            adapter.readCoordinates()
        } catch (e: Throwable) {
            udp.close()
            synScanSocket = null
            skyWatcherAdapter = null
            throw IllegalStateException(
                "SynScan Wi-Fi connection failed. Connect the phone to the mount hotspot " +
                    "(SSID SynScan_xxxx) then use UDP 192.168.4.1:11880. ${e.message}",
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
        // Reject queued movement before sending the final emergency stop.
        synchronized(closeLock) {
            if (permanentlyClosed) return
            permanentlyClosed = true
        }
        runCatching {
            if (isConnected) emergencyStop()
        }.onFailure { FileLogger.e(TAG, "Emergency stop during mount close failed", it) }
        closeTransports()
    }

    private fun closeTransports() {
        val wasConnected = isConnected
        pointingAccess.disconnected()
        injectedTestSession = false
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
        val serialPortClose = runCatching { oldSerialPort?.close() }
            .onFailure { FileLogger.e(TAG, "USB serial port close failed", it) }
        val serialConnectionClose = runCatching { oldSerialConnection?.close() }
            .onFailure { FileLogger.e(TAG, "USB connection close failed", it) }
        runCatching { oldBluetoothSocket?.close() }
        runCatching { oldSynScanSocket?.close() }
        if (wasConnected || oldSerialPort != null || oldSerialConnection != null) {
            FileLogger.i(
                TAG,
                "Mount transport cleanup connected=$wasConnected usbPort=${oldSerialPort != null} " +
                    "usbPortClose=${serialPortClose.isSuccess} usbConnection=${oldSerialConnection != null} " +
                    "usbConnectionClose=${serialConnectionClose.isSuccess}"
            )
        }
    }

    suspend fun readCoordinates(): MountCoordinates = withContext(Dispatchers.IO) {
        publishPointingConnection()
        val revision = modelRevision.value
        val started = elapsedRealtime()
        skyWatcherAdapter?.let {
            val snapshot = it.readCoordinatesWithAxes()
            pointingAccess.observe(snapshot.axes, revision, started, elapsedRealtime())
            return@withContext snapshot.coordinates
        }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            val response = sendFixedCommand(":GEP#", 20)
            return@withContext MountProtocolCodec.parseIoptronCoordinates(response)
        }
        MountProtocolCodec.parseLx200Coordinates(
            raResponse = sendCommand(":GR#"),
            decResponse = sendCommand(":GD#")
        )
    }

    fun refreshTracking() {
        if (accessMode.value == MountAccessMode.READ_WRITE) skyWatcherAdapter?.refreshTracking()
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
        pointingAccess.requireWritable()
        check(pointingCapabilities.value.writeSite) { "This protocol uses an application site configuration, not a mount site write." }
        pointingAccess.modelChanged()
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

    suspend fun setLocalSiteConfiguration(site: MountSite) = withContext(Dispatchers.IO) {
        require(site.latitudeDeg.isFinite() && site.latitudeDeg in -90.0..90.0 &&
            site.longitudeDeg.isFinite() && site.longitudeDeg in -180.0..180.0) { "Invalid application site." }
        val adapter = skyWatcherAdapter ?: error("No local-site mount session.")
        check(adapter.pointingCapabilities.siteUsesLocalConfiguration) { "This protocol reads its site from the mount." }
        pointingAccess.modelChanged()
        adapter.setSite(site) // The direct motor adapter only changes its local projection model.
    }

    suspend fun readTime(): MountTime = withContext(Dispatchers.IO) {
        skyWatcherAdapter?.let { return@withContext it.readTime() }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            val response = sendFixedCommand(":GUT#", 18)
            require(response.matches(Regex("[+-]\\d{3}[01]\\d{13}"))) {
                "Invalid iOptron UTC time response: $response"
            }
            val millisSinceJ2000 = response.substring(5).toLongOrNull()
                ?: error("Invalid iOptron UTC time response: $response")
            return@withContext MountTime(IOPTRON_J2000_EPOCH_MILLIS + millisSinceJ2000)
        }
        val utcOffset = parseLx200UtcOffset(sendCommand(":GG#"))
        val date = sendCommand(":GC#")
        val time = sendCommand(":GL#")
        MountTime(parseLx200LocalTime(date, time, utcOffset))
    }

    suspend fun setTime(time: MountTime) = withContext(Dispatchers.IO) {
        pointingAccess.requireWritable(); check(pointingCapabilities.value.writeTime) { "Mount time write is unsupported." }
        pointingAccess.modelChanged()
        skyWatcherAdapter?.let {
            it.setTime(time)
            return@withContext
        }
        val instant = Instant.ofEpochMilli(time.epochMillis)
        if (activeProtocol == MountProtocolType.IOPTRON) {
            val millisSinceJ2000 = time.epochMillis - IOPTRON_J2000_EPOCH_MILLIS
            require(millisSinceJ2000 >= 0L) { "iOptron time must be after 2000-01-01 12:00 UTC." }
            if (!sendIoptronOk(":SUT%013d#".format(Locale.US, millisSinceJ2000))) {
                error("iOptron mount rejected UTC time.")
            }
            val zone = ZoneId.systemDefault()
            val offsetMinutes = zone.rules.getStandardOffset(instant).totalSeconds / 60
            val offsetCommand = ":SG${if (offsetMinutes < 0) '-' else '+'}%03d#"
                .format(Locale.US, kotlin.math.abs(offsetMinutes))
            if (!sendIoptronOk(offsetCommand) ||
                !sendIoptronOk(if (zone.rules.isDaylightSavings(instant)) ":SDS1#" else ":SDS0#")
            ) {
                error("iOptron mount rejected time-zone settings.")
            }
            return@withContext
        }
        val zoned = instant.atZone(ZoneId.systemDefault())
        val offsetMinutes = -zoned.offset.totalSeconds / 60 // LX200: UTC = local + offset.
        val offsetRemainder = kotlin.math.abs(offsetMinutes) % 60
        require(offsetRemainder in setOf(0, 30, 45)) { "LX200 UTC offset is unsupported." }
        val offsetCommand = ":SG%s%02d%s#".format(Locale.US, if (offsetMinutes < 0) "-" else "+",
            kotlin.math.abs(offsetMinutes) / 60,
            if (offsetRemainder == 0) "" else ":%02d".format(Locale.US, offsetRemainder))
        check(sendBooleanCommand(offsetCommand)) { "Mount rejected UTC offset." }
        val local = zoned.toLocalDateTime()
        val dateCommand = ":SC%02d/%02d/%02d#".format(
            Locale.US,
            local.monthValue,
            local.dayOfMonth,
            local.year % 100
        )
        val timeCommand = ":SL%02d:%02d:%02d#".format(
            Locale.US,
            local.hour,
            local.minute,
            local.second
        )
        // OnStep returns one byte; classic LX200 returns a terminated planetary-data message.
        val dateAccepted = if (isOnStepFirmware)
            sendBooleanCommand(dateCommand) else sendCommand(dateCommand).trim().startsWith("1")
        require(dateAccepted) {
            "Mount rejected date."
        }
        require(sendBooleanCommand(timeCommand)) {
            "Mount rejected time."
        }
    }

    /** Called before connection readiness; read-only sessions only query their clock. */
    internal suspend fun initializeTimeFromPhone(epochMillis: Long = System.currentTimeMillis()): MountTime? {
        val capabilities = pointingCapabilities.value
        if (!capabilities.readTime && !capabilities.writeTime) return null
        val started = System.currentTimeMillis()
        if (capabilities.writeTime) setTime(MountTime(epochMillis))
        val actual = if (capabilities.readTime) readTime() else MountTime(epochMillis)
        if (capabilities.writeTime && capabilities.readTime) {
            check(kotlin.math.abs(actual.epochMillis - epochMillis - (System.currentTimeMillis() - started)) <= 10_000) {
                "Mount clock verification failed after synchronization. Check mount date, time and UTC offset."
            }
        }
        return actual
    }

    suspend fun slewTo(coordinates: MountCoordinates) = withContext(Dispatchers.IO) {
        pointingAccess.requireWritable()
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
        FileLogger.i(
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
        pointingAccess.requireWritable(); check(supportsSync) { "Mount SYNC is unsupported." }
        pointingAccess.modelChanged()
        require(coordinates.raHours.isFinite()) { "Sync RA is invalid." }
        require(coordinates.decDeg.isFinite() && coordinates.decDeg in -90.0..90.0) {
            "Sync Dec is invalid."
        }
        skyWatcherAdapter?.let {
            it.syncTo(coordinates)
            return@withContext
        }
        FileLogger.i(
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
        pointingAccess.requireWritable()
        FileLogger.i(TAG, "Mount manual move start ${direction.name} command=${direction.command}")
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

    /** OnStep owns the pulse deadline even if the host or transport disappears. */
    suspend fun startGuidePulse(direction: MountDirection, durationMs: Int) = withContext(Dispatchers.IO) {
        require(durationMs in 1..5000)
        pointingAccess.requireWritable()
        if (isOnStepFirmware) {
            val axis = when (direction) {
                MountDirection.NORTH -> 'n'
                MountDirection.SOUTH -> 's'
                MountDirection.EAST -> 'e'
                MountDirection.WEST -> 'w'
            }
            FileLogger.i(TAG, "Mount timed guide ${direction.name} durationMs=$durationMs")
            check(sendBooleanCommand(":MG$axis$durationMs#")) { "OnStep rejected the timed guide pulse." }
        } else {
            startMove(direction)
        }
    }

    suspend fun stopGuidePulse(direction: MountDirection) = withContext(Dispatchers.IO) {
        stopMoveBlocking(direction, requireStop = true)
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

    private fun stopMoveBlocking(direction: MountDirection? = null, requireStop: Boolean = false) {
        FileLogger.i(TAG, "Mount manual move stop ${direction?.name ?: "ALL"}")
        skyWatcherAdapter?.let {
            if (accessMode.value == MountAccessMode.READ_ONLY) it.abortMotion() else it.stopMove(direction)
            return
        }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            val stopped = if (direction == MountDirection.NORTH || direction == MountDirection.SOUTH) {
                runCatching { sendIoptronOk(":qD#") }
            } else if (direction == MountDirection.EAST || direction == MountDirection.WEST) {
                runCatching { sendIoptronOk(":qR#") }
            } else {
                runCatching { sendIoptronOk(":Q#") }
            }
            if (requireStop) check(stopped.getOrThrow()) { "Mount rejected the guide stop." }
        } else {
            val axisStop = direction?.let { runCatching { sendNoReplyCommand(it.stopCommand) } }
            val allStop = runCatching { sendNoReplyCommand(":Q#") }
            if (requireStop && axisStop?.isSuccess != true) allStop.getOrThrow()
        }
    }

    suspend fun setMoveRate(rate: MountSlewRate) = withContext(Dispatchers.IO) {
        pointingAccess.requireWritable()
        FileLogger.i(TAG, "Mount move rate ${rate.name} command=${rate.command}")
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

    suspend fun setTracking(enabled: Boolean) = setTrackingRate(
        if (enabled) MountTrackingRate.SIDEREAL else MountTrackingRate.OFF
    )

    /** A safety stop, including READ_ONLY. Only successful protocol acknowledgements confirm OFF. */
    suspend fun stopTrackingForSafety() = withContext(Dispatchers.IO) {
        skyWatcherAdapter?.let { it.setTrackingRate(MountTrackingRate.OFF); return@withContext }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            check(sendIoptronOk(":ST0#")) { "Mount did not confirm tracking stop." }
        } else {
            check(sendBooleanCommand(":Td#")) { "Mount did not confirm tracking stop." }
        }
    }

    suspend fun setTrackingRate(rate: MountTrackingRate) = withContext(Dispatchers.IO) {
        pointingAccess.requireWritable()
        FileLogger.i(TAG, "Mount tracking $rate")
        skyWatcherAdapter?.let {
            it.setTrackingRate(rate)
            return@withContext
        }
        if (activeProtocol == MountProtocolType.IOPTRON) {
            if (rate == MountTrackingRate.OFF) {
                if (!sendIoptronOk(":ST0#")) error("iOptron mount rejected tracking off.")
            } else {
                val select = when (rate) {
                    MountTrackingRate.SIDEREAL -> ":RT0#"
                    MountTrackingRate.LUNAR -> ":RT1#"
                    MountTrackingRate.SOLAR -> ":RT2#"
                    MountTrackingRate.OFF -> ":ST0#"
                }
                if (!sendIoptronOk(select) || !sendIoptronOk(":ST1#")) {
                    error("iOptron mount rejected tracking rate.")
                }
            }
            return@withContext
        }
        if (rate == MountTrackingRate.OFF) {
            if (!sendBooleanCommand(":Td#")) error("Mount rejected tracking off.")
        } else {
            sendNoReplyCommand(rate.lx200SelectCommand)
            if (!sendBooleanCommand(":Te#")) error("Mount rejected tracking.")
        }
    }

    suspend fun goHome() = withContext(Dispatchers.IO) {
        pointingAccess.requireWritable()
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
        pointingAccess.requireWritable(); pointingAccess.modelChanged(zero = true)
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

    private fun openMotorSkyWatcherSession(
        site: MountSite?,
        mode: SkyWatcherMountMode
    ): SkyWatcherMountSession {
        drainSynScanInput()
        val probe = runCatching {
            exchangeSkyWatcher(SkyWatcherMotorCodec.command('e', SkyWatcherAxis.RA), null)
        }
        val reply = probe.getOrNull()
        require(reply != null && SkyWatcherMotorCodec.isMotorReply(reply)) {
            probe.exceptionOrNull()?.message ?: "No Sky-Watcher motor-controller reply"
        }
        val adapter = SkyWatcherMotorAdapter(
            exchange = { payload -> exchangeSkyWatcher(payload, null) },
            site = site ?: MountSite(0.0, 0.0),
            mode = mode,
            initializeMotors = accessMode.value == MountAccessMode.READ_WRITE
        )
        adapter.open()
        return adapter
    }

    private fun drainSynScanInput() {
        val udp = synScanSocket ?: return
        val previous = udp.soTimeout
        try {
            udp.soTimeout = 40
            val buffer = ByteArray(256)
            repeat(8) {
                runCatching { udp.receive(DatagramPacket(buffer, buffer.size)) }
                    .onFailure { return }
            }
        } finally {
            udp.soTimeout = previous
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
        pointingAccess.requireWireAllowed(payload)
        val udp = synScanSocket
        if (udp != null) {
            val outgoing = DatagramPacket(payload, payload.size)
            synchronized(udp) {
                check(!permanentlyClosed) { "Mount controller is closed." }
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
            check(!permanentlyClosed) { "Mount controller is closed." }
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
            check(!permanentlyClosed) { "Mount controller is closed." }
            pointingAccess.requireWireAllowed(command.toByteArray(Charsets.US_ASCII))
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
                FileLogger.w(TAG, "Unexpected byte after GOTO acknowledgement: ${buffer[0]}")
            }
            return
        }
        val tcpSocket = socket ?: return
        val previousTimeout = tcpSocket.soTimeout
        try {
            tcpSocket.soTimeout = OPTIONAL_REPLY_TIMEOUT_MS
            val value = runCatching { input?.read() ?: -1 }.getOrDefault(-1)
            if (value >= 0 && value != '#'.code) {
                FileLogger.w(TAG, "Unexpected byte after GOTO acknowledgement: $value")
            }
        } finally {
            tcpSocket.soTimeout = previousTimeout
        }
    }

    private fun emergencyStop() {
        val payloads = skyWatcherAdapter?.emergencyStopPayloads()
        if (payloads != null) {
            payloads.forEach(::emergencyWriteBytes)
        } else {
            emergencyWriteBytes(":Q#".toByteArray(Charsets.US_ASCII))
        }
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
                check(!permanentlyClosed) { "Mount controller is closed." }
                pointingAccess.requireWireAllowed(bytes)
                port.write(bytes, SERIAL_TIMEOUT_MS)
            }
            return
        }
        val out = output ?: error("Mount is not connected.")
        synchronized(out) {
            check(!permanentlyClosed) { "Mount controller is closed." }
            pointingAccess.requireWireAllowed(bytes)
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

    /**
     * OnStep USB boards reboot when DTR is toggled. Poll `:GR#` with DTR and RTS
     * held low so a reconnect is not killed by another reset, and so the first
     * connect does not wait out iOptron and SynScan probes.
     */
    private fun handshakeLx200WithoutReset(port: UsbSerialPort): Boolean {
        runCatching { port.setDTR(false) }
        runCatching { port.setRTS(false) }
        val deadline = System.currentTimeMillis() + USB_BOOT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            check(!permanentlyClosed) { "Mount controller is closed." }
            Thread.sleep(200)
            drainSerialInput(port)
            val reply = runCatching { sendCommand(":GR#", USB_POLL_TIMEOUT_MS) }.getOrNull()
            if (!reply.isNullOrBlank()) {
                activeProtocol = MountProtocolType.LX200_ONSTEP
                mountModel = runCatching { sendCommand(":GVP#", HANDSHAKE_TIMEOUT_MS) }
                    .getOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?: "LX200 / OnStep"
                FileLogger.i(TAG, "USB LX200 handshake ok reply=$reply model=$mountModel")
                return true
            }
        }
        FileLogger.w(TAG, "USB LX200 quick handshake timed out after ${USB_BOOT_WAIT_MS}ms")
        return false
    }

    private fun verifySerialHandshake(
        port: UsbSerialPort,
        preferred: MountProtocolType
    ) {
        val failures = ArrayList<String>()
        for (mode in SERIAL_LINE_MODES) {
            FileLogger.i(TAG, "USB serial handshake try mode=${mode.name}")
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
                FileLogger.i(TAG, "USB serial handshake send $command mode=${mode.name}")
                val result = runCatching { sendCommand(command, HANDSHAKE_TIMEOUT_MS) }
                if (result.isSuccess) {
                    FileLogger.i(TAG, "USB serial mount handshake ok mode=${mode.name} command=$command reply=${result.getOrNull()}")
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
            FileLogger.i(TAG, "USB serial drain rx ${chunk.toHexString()} ${chunk.toPrintableString()}")
            total += count
        }
        if (total > 0) {
            FileLogger.i(TAG, "USB serial drained $total bytes")
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

    private fun parseLx200LocalTime(dateResponse: String, timeResponse: String, offset: ZoneOffset): Long {
        val date = Regex("^(\\d{1,2})/(\\d{1,2})/(\\d{2,4})$")
            .matchEntire(dateResponse.trim())
            ?: error("Invalid LX200 date response: $dateResponse")
        val time = Regex("^(\\d{1,2}):(\\d{2}):(\\d{2})$")
            .matchEntire(timeResponse.trim())
            ?: error("Invalid LX200 time response: $timeResponse")
        val yearValue = date.groupValues[3].toInt()
        val year = if (yearValue < 100) 2000 + yearValue else yearValue
        val local = LocalDateTime.of(
            year,
            date.groupValues[1].toInt(),
            date.groupValues[2].toInt(),
            time.groupValues[1].toInt(),
            time.groupValues[2].toInt(),
            time.groupValues[3].toInt()
        )
        return local.toInstant(offset).toEpochMilli()
    }

    private fun parseLx200UtcOffset(response: String): ZoneOffset {
        val text = response.trim()
        val match = Regex("^([+-]?)(\\d{1,2})(?::(\\d{2})|(\\.\\d+))?$").matchEntire(text)
            ?: error("Invalid LX200 UTC offset: $response")
        val hours = match.groupValues[2].toInt()
        val minutes = match.groupValues[3].toIntOrNull()
            ?: (match.groupValues[4].toDoubleOrNull()?.times(60)?.roundToInt() ?: 0)
        require(minutes in 0..59 && hours * 60 + minutes <= 18 * 60) { "Invalid LX200 UTC offset: $response" }
        val lx200Minutes = (hours * 60 + minutes) * if (match.groupValues[1] == "-") -1 else 1
        return ZoneOffset.ofTotalSeconds(-lx200Minutes * 60)
    }

    companion object {
        private const val TAG = "Lx200Mount"
        private const val SERIAL_TIMEOUT_MS = 5000
        private const val EMERGENCY_STOP_TIMEOUT_MS = 500
        private const val BLUETOOTH_CONNECT_TIMEOUT_MS = 15_000L
        private const val OPTIONAL_REPLY_TIMEOUT_MS = 150
        private const val HANDSHAKE_TIMEOUT_MS = 1500
        private const val USB_BOOT_WAIT_MS = 5_000L
        private const val USB_POLL_TIMEOUT_MS = 400
        private const val SERIAL_DRAIN_MAX_MS = 700L
        private const val SERIAL_DRAIN_MAX_BYTES = 4096
        private val IOPTRON_J2000_EPOCH_MILLIS =
            Instant.parse("2000-01-01T12:00:00Z").toEpochMilli()
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
            val serialDevices = UsbSerialProber.getDefaultProber()
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
            val attachedDevices = runCatching {
                usbManager.deviceList.values.joinToString { device ->
                    "${device.deviceName} %04x:%04x id=%d".format(
                        Locale.US, device.vendorId, device.productId, device.deviceId
                    )
                }.ifBlank { "none" }
            }.getOrElse { "unavailable (${it.javaClass.simpleName})" }
            FileLogger.i(
                TAG,
                "USB scan serialDrivers=${serialDevices.size} attached=[$attachedDevices]"
            )
            return serialDevices
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
