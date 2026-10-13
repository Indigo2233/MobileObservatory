package com.indigo.mobileobservatory.mount

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.indigo.mobileobservatory.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.indigo.mobileobservatory.util.FileLogger

/** Owns mount state, transport lifecycle, permissions, and mutually exclusive motion. */
class MountModule(
    private val application: Application,
    private val scope: CoroutineScope
) {
    private companion object {
        const val ACTION_MOUNT_USB_PERMISSION = "com.indigo.mobileobservatory.MOUNT_USB_PERMISSION"
        const val DEFAULT_SYNSCAN_HOST = "192.168.4.1"
        const val DEFAULT_SYNSCAN_PORT = 11880
        const val MOTION_STABLE_TOLERANCE_DEG = GotoArrival.MOTION_STABLE_DEG
        const val MOTION_STABLE_SAMPLES = GotoArrival.MOTION_STABLE_SAMPLES
        const val TAG = "MountModule"

        internal fun migratedSynScanEndpoint(host: String, port: Int): Pair<String, Int> {
            return if (host == "127.0.0.1" && port == 11882) {
                DEFAULT_SYNSCAN_HOST to DEFAULT_SYNSCAN_PORT
            } else {
                host to port
            }
        }
    }

    private val prefs = application.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    private val controller = Lx200MountController()
    val mountAccessMode = controller.accessMode
    val pointingCapabilities = controller.pointingCapabilities
    val modelRevision = controller.modelRevision
    val axisObservation = controller.axisObservation
    init {
        controller.setAccessMode(runCatching {
            MountAccessMode.valueOf(prefs.getString("mount_access_mode", MountAccessMode.READ_WRITE.name) ?: MountAccessMode.READ_WRITE.name)
        }.getOrDefault(MountAccessMode.READ_WRITE))
    }
    private val motionRunner = MountMotionRunner(scope) { controller.abortMotion() }
    val motionState: StateFlow<MountMotionState> = motionRunner.state

    private val _statusMessage = MutableStateFlow("")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()
    private val _mountConnectionState = MutableStateFlow<MountConnectionState>(MountConnectionState.Disconnected)
    val mountConnectionState: StateFlow<MountConnectionState> = _mountConnectionState.asStateFlow()

    private val _mountHost = MutableStateFlow(prefs.getString("mount_lx200_host", "192.168.0.1") ?: "192.168.0.1")
    val mountHost: StateFlow<String> = _mountHost.asStateFlow()

    private val _mountPort = MutableStateFlow(prefs.getInt("mount_lx200_port", 9998).toString())
    val mountPort: StateFlow<String> = _mountPort.asStateFlow()

    private val synScanEndpoint = migratedSynScanEndpoint(
        prefs.getString("synscan_host", DEFAULT_SYNSCAN_HOST) ?: DEFAULT_SYNSCAN_HOST,
        prefs.getInt("synscan_port", DEFAULT_SYNSCAN_PORT)
    )
    private val _synScanHost = MutableStateFlow(synScanEndpoint.first)
    val synScanHost: StateFlow<String> = _synScanHost.asStateFlow()

    private val _synScanPort = MutableStateFlow(synScanEndpoint.second.toString())
    val synScanPort: StateFlow<String> = _synScanPort.asStateFlow()

    private val _skyWatcherMountMode = MutableStateFlow(
        runCatching {
            SkyWatcherMountMode.valueOf(
                prefs.getString("skywatcher_mount_mode", SkyWatcherMountMode.EQUATORIAL.name)
                    ?: SkyWatcherMountMode.EQUATORIAL.name
            )
        }.getOrDefault(SkyWatcherMountMode.EQUATORIAL)
    )
    val skyWatcherMountMode: StateFlow<SkyWatcherMountMode> = _skyWatcherMountMode.asStateFlow()

    private val _mountTransport = MutableStateFlow(
        runCatching { MountTransportType.valueOf(prefs.getString("mount_transport", MountTransportType.TCP.name) ?: MountTransportType.TCP.name) }
            .getOrDefault(MountTransportType.TCP)
    )
    val mountTransport: StateFlow<MountTransportType> = _mountTransport.asStateFlow()

    private val _mountUsbDevices = MutableStateFlow<List<MountUsbDevice>>(emptyList())
    val mountUsbDevices: StateFlow<List<MountUsbDevice>> = _mountUsbDevices.asStateFlow()

    private val _mountUsbDeviceId = MutableStateFlow(prefs.getInt("mount_usb_device_id", -1))
    val mountUsbDeviceId: StateFlow<Int> = _mountUsbDeviceId.asStateFlow()

    /** USB serial currently held by a live mount connection; null when mount uses another transport or is disconnected. */
    private val _activeUsbMountDeviceId = MutableStateFlow<Int?>(null)
    val activeUsbMountDeviceId: StateFlow<Int?> = _activeUsbMountDeviceId.asStateFlow()

    private val _mountBaudRate = MutableStateFlow(prefs.getInt("mount_usb_baud", 9600).toString())
    val mountBaudRate: StateFlow<String> = _mountBaudRate.asStateFlow()

    private val _mountBluetoothDevices =
        MutableStateFlow<List<MountBluetoothDevice>>(emptyList())
    val mountBluetoothDevices: StateFlow<List<MountBluetoothDevice>> =
        _mountBluetoothDevices.asStateFlow()

    private val _mountBluetoothAddress =
        MutableStateFlow(prefs.getString("mount_bluetooth_address", "") ?: "")
    val mountBluetoothAddress: StateFlow<String> = _mountBluetoothAddress.asStateFlow()

    private val _mountProtocol = MutableStateFlow(
        runCatching {
            MountProtocolType.valueOf(
                prefs.getString("mount_protocol", MountProtocolType.AUTO.name)
                    ?: MountProtocolType.AUTO.name
            )
        }.getOrDefault(MountProtocolType.AUTO)
    )
    val mountProtocol: StateFlow<MountProtocolType> = _mountProtocol.asStateFlow()

    private val _mountDetectedInfo = MutableStateFlow("")
    val mountDetectedInfo: StateFlow<String> = _mountDetectedInfo.asStateFlow()

    private val _mountCoordinates = MutableStateFlow<MountCoordinates?>(null)
    val mountCoordinates: StateFlow<MountCoordinates?> = _mountCoordinates.asStateFlow()

    private val _mountSite = MutableStateFlow<MountSite?>(null)
    val mountSite: StateFlow<MountSite?> = _mountSite.asStateFlow()

    private val _mountTime = MutableStateFlow<MountTime?>(null)
    val mountTime: StateFlow<MountTime?> = _mountTime.asStateFlow()

    private val _mountBusy = MutableStateFlow(false)
    val mountBusy: StateFlow<Boolean> = _mountBusy.asStateFlow()

    /** Protocol sync is available for LX200/OnStep/iOptron and SynScan motor/Wi‑Fi, not USB handset. */
    val supportsSync: Boolean
        get() = controller.isConnected && controller.supportsSync

    val supportsTimeSync: Boolean
        get() = controller.isConnected && controller.supportsTimeSync

    private val _mountConnectionMessage = MutableStateFlow("")
    val mountConnectionMessage: StateFlow<String> = _mountConnectionMessage.asStateFlow()

    private val _mountMoveStatus = MutableStateFlow("")
    val mountMoveStatus: StateFlow<String> = _mountMoveStatus.asStateFlow()

    private val _mountSlewRate = MutableStateFlow(
        MountSlewRate.fromStoredName(prefs.getString("mount_slew_rate", MountSlewRate.DEFAULT.name))
    )
    val mountSlewRate: StateFlow<MountSlewRate> = _mountSlewRate.asStateFlow()

    private val _mountTrackingRate = MutableStateFlow(MountTrackingRate.OFF)
    val mountTrackingRate: StateFlow<MountTrackingRate> = _mountTrackingRate.asStateFlow()

    private val _mountTrackingEnabled = MutableStateFlow(false)
    val mountTrackingEnabled: StateFlow<Boolean> = _mountTrackingEnabled.asStateFlow()

    private val _precisionGotoProgress = MutableStateFlow(PrecisionGotoProgress())
    val precisionGotoProgress: StateFlow<PrecisionGotoProgress> = _precisionGotoProgress.asStateFlow()

    @Volatile private var pendingMountUsbConnect = false
    private var mountCoordinatePollingJob: kotlinx.coroutines.Job? = null
    private var mountConnectJob: kotlinx.coroutines.Job? = null
    private val transportLock = Mutex()
    private var mountConnectionGeneration = 0L

    private val mountUsbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_MOUNT_USB_PERMISSION) return
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            if (granted) {
                scanMountUsbDevices()
                if (pendingMountUsbConnect) {
                    pendingMountUsbConnect = false
                    connectMount()
                }
            } else {
                pendingMountUsbConnect = false
                _mountBusy.value = false
                _mountConnectionState.value = MountConnectionState.Error("Mount USB permission denied. Tap Connect again and allow USB access.")
                _statusMessage.value = "Mount USB permission denied"
            }
        }
    }


    fun setMountHost(host: String) {
        _mountHost.value = host
    }

    fun setMountPort(port: String) {
        _mountPort.value = port.filter { it.isDigit() }.take(5)
    }

    fun setSynScanHost(host: String) {
        _synScanHost.value = host
    }

    fun setSynScanPort(port: String) {
        _synScanPort.value = port.filter { it.isDigit() }.take(5)
    }

    fun setSkyWatcherMountMode(mode: SkyWatcherMountMode) {
        if (controller.isConnected && mode != _skyWatcherMountMode.value) {
            _statusMessage.value = "Reconnect the mount before changing its coordinate mode."
            return
        }
        _skyWatcherMountMode.value = mode
        prefs.edit().putString("skywatcher_mount_mode", mode.name).apply()
    }

    fun setMountAccessMode(mode: MountAccessMode) {
        if (mode == mountAccessMode.value) return
        val ownedTracking = _mountTrackingEnabled.value
        controller.setAccessMode(mode) // Immediately deny all subsequent device writes.
        prefs.edit().putString("mount_access_mode", mode.name).apply()
        if (mode == MountAccessMode.READ_ONLY) {
            scope.launch {
                motionRunner.stop()
                if (ownedTracking && controller.isConnected) {
                    runCatching { controller.stopTrackingForSafety() }
                        .onSuccess { _mountTrackingRate.value = MountTrackingRate.OFF; _mountTrackingEnabled.value = false }
                        .onFailure { _statusMessage.value = "Read-only. Tracking stop was not confirmed: ${it.message}" }
                }
                _mountBusy.value = false
            }
        }
    }

    fun setMountLocalSite(latitudeDeg: Double, longitudeDeg: Double) {
        if (!controller.isConnected || !pointingCapabilities.value.siteUsesLocalConfiguration) return
        scope.launch {
            try {
                val site = MountSite(latitudeDeg, longitudeDeg)
                controller.setLocalSiteConfiguration(site)
                _mountSite.value = site
                _statusMessage.value = "Application site configuration updated; no location command sent to the mount."
                refreshMountCoordinatesAfterCommand()
            } catch (error: Throwable) { _statusMessage.value = "Application site configuration failed: ${error.message}" }
        }
    }

    fun setMountTransport(type: MountTransportType) {
        _mountTransport.value = type
        prefs.edit().putString("mount_transport", type.name).apply()
    }

    fun setMountProtocol(type: MountProtocolType) {
        _mountProtocol.value = type
        prefs.edit().putString("mount_protocol", type.name).apply()
    }

    fun setMountUsbDevice(deviceId: Int) {
        _mountUsbDeviceId.value = deviceId
        prefs.edit().putInt("mount_usb_device_id", deviceId).apply()
    }

    fun setMountBaudRate(baudRate: String) {
        _mountBaudRate.value = baudRate.filter { it.isDigit() }.take(7)
    }

    fun setMountBluetoothDevice(address: String) {
        _mountBluetoothAddress.value = address
        prefs.edit().putString("mount_bluetooth_address", address).apply()
    }

    fun scanMountUsbDevices() {
        val context = application
        val devices = runCatching { Lx200MountController.listUsbDevices(context) }.getOrDefault(emptyList())
        _mountUsbDevices.value = devices
        if (devices.isNotEmpty() && devices.none { it.deviceId == _mountUsbDeviceId.value }) {
            setMountUsbDevice(devices.first().deviceId)
        }
    }

    fun scanMountBluetoothDevices() {
        val context = application
        val devices = runCatching {
            Lx200MountController.listBluetoothDevices(context)
        }.onFailure {
            _mountConnectionState.value =
                MountConnectionState.Error(it.message ?: "Bluetooth scan failed")
        }.getOrDefault(emptyList())
        _mountBluetoothDevices.value = devices
        if (devices.isNotEmpty() &&
            devices.none { it.address == _mountBluetoothAddress.value }) {
            setMountBluetoothDevice(devices.first().address)
        }
    }

    fun connectMount() {
        when (_mountTransport.value) {
            MountTransportType.USB_SERIAL -> {
                connectUsbMount()
                return
            }
            MountTransportType.BLUETOOTH -> {
                connectBluetoothMount()
                return
            }
            MountTransportType.SYNSCAN_WIFI -> {
                connectSynScanWifiMount()
                return
            }
            MountTransportType.TCP -> Unit
        }
        val host = _mountHost.value.trim()
        val port = _mountPort.value.toIntOrNull() ?: 9998
        if (host.isBlank()) {
            _mountConnectionState.value = MountConnectionState.Error("Mount host is empty")
            return
        }
        scope.launch {
            transportLock.withLock {
            FileLogger.i(TAG, "connectTcp host=$host port=$port protocol=${_mountProtocol.value}")
            _mountBusy.value = true
            _mountConnectionState.value = MountConnectionState.Connecting
            try {
                prefs.edit()
                    .putString("mount_lx200_host", host)
                    .putInt("mount_lx200_port", port)
                    .apply()
                _activeUsbMountDeviceId.value = null
                val coordinates = controller.connect(
                    host,
                    port,
                    protocol = _mountProtocol.value
                )
                runCatching { controller.setMoveRate(_mountSlewRate.value) }
                _mountCoordinates.value = coordinates
                runCatching { controller.readSite() }
                    .onSuccess { _mountSite.value = it }
                _mountConnectionState.value = MountConnectionState.Connected
                updateDetectedMountInfo()
                _statusMessage.value = "Mount connected: ${coordinates.formatRa()} ${coordinates.formatDec()}"
            } catch (e: Throwable) {
                controller.disconnect()
                _activeUsbMountDeviceId.value = null
                _mountCoordinates.value = null
                _mountSite.value = null
                _mountTime.value = null
                _mountDetectedInfo.value = ""
                FileLogger.e(TAG, "connectTcp failed", e)
                _mountConnectionState.value = MountConnectionState.Error(e.message ?: "Mount connection failed")
                _statusMessage.value = "Mount error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
            }
        }


    }

    private fun connectUsbMount() {
        scanMountUsbDevices()
        val devices = _mountUsbDevices.value
        if (devices.isEmpty()) {
            FileLogger.w(TAG, "connectUsb found no serial device")
            _mountConnectionState.value = MountConnectionState.Error("No USB serial mount found")
            _statusMessage.value = "Mount error: no USB serial device"
            return
        }
        val selected = devices.firstOrNull { it.deviceId == _mountUsbDeviceId.value } ?: devices.first()
        val baudRate = _mountBaudRate.value.toIntOrNull() ?: 9600
        val context = application
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val usbDevice = usbManager.deviceList.values.firstOrNull { it.deviceId == selected.deviceId }
        if (usbDevice == null) {
            _mountConnectionState.value = MountConnectionState.Error("Selected USB mount is detached")
            _statusMessage.value = "Mount USB detached"
            return
        }
        if (!usbManager.hasPermission(usbDevice)) {
            pendingMountUsbConnect = true
            _mountBusy.value = true
            _mountConnectionState.value = MountConnectionState.Connecting
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = PendingIntent.getBroadcast(
                context,
                selected.deviceId,
                Intent(ACTION_MOUNT_USB_PERMISSION).setPackage(context.packageName),
                flags
            )
            usbManager.requestPermission(usbDevice, intent)
            _statusMessage.value = "Requesting mount USB permission..."
            return
        }
        scope.launch {
            transportLock.withLock {
            FileLogger.i(
                TAG,
                "connectUsb id=${selected.deviceId} name=${selected.label} baud=$baudRate protocol=${_mountProtocol.value}"
            )
            _mountBusy.value = true
            _mountConnectionState.value = MountConnectionState.Connecting
            try {
                prefs.edit()
                    .putString("mount_transport", MountTransportType.USB_SERIAL.name)
                    .putInt("mount_usb_device_id", selected.deviceId)
                    .putInt("mount_usb_baud", baudRate)
                    .apply()
                _activeUsbMountDeviceId.value = null
                val coordinates = controller.connectUsb(
                    context,
                    selected.deviceId,
                    baudRate,
                    _mountProtocol.value
                )
                _activeUsbMountDeviceId.value = selected.deviceId
                runCatching { controller.setMoveRate(_mountSlewRate.value) }
                _mountCoordinates.value = coordinates
                runCatching { controller.readSite() }
                    .onSuccess { _mountSite.value = it }
                _mountConnectionState.value = MountConnectionState.Connected
                updateDetectedMountInfo()
                _statusMessage.value = "Mount connected USB: ${coordinates.formatRa()} ${coordinates.formatDec()}"
                FileLogger.i(TAG, "connectUsb ok ${_statusMessage.value} model=${controller.mountModel}")
            } catch (e: Throwable) {
                FileLogger.e(TAG, "connectUsb failed", e)
                controller.disconnect()
                _activeUsbMountDeviceId.value = null
                _mountCoordinates.value = null
                _mountSite.value = null
                _mountTime.value = null
                _mountDetectedInfo.value = ""
                _mountConnectionState.value = MountConnectionState.Error(e.message ?: "Mount USB connection failed")
                _statusMessage.value = "Mount USB error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
            }
        }
    }

    private fun connectBluetoothMount() {
        scanMountBluetoothDevices()
        val address = _mountBluetoothAddress.value
        if (address.isBlank()) {
            _mountConnectionState.value = MountConnectionState.Error(
                "Pair the OnStep or iOptron Bluetooth module in Android settings first."
            )
            return
        }
        val context = application
        val generation = ++mountConnectionGeneration
        mountConnectJob?.cancel()
        controller.cancelPendingBluetoothConnection()
        mountConnectJob = scope.launch {
            transportLock.withLock {
            FileLogger.i(TAG, "connectBluetooth address=$address protocol=${_mountProtocol.value}")
            _mountBusy.value = true
            _mountConnectionState.value = MountConnectionState.Connecting
            _mountConnectionMessage.value = "\u6807\u51c6\u6a21\u5f0f\u8fde\u63a5\u4e2d"
            try {
                prefs.edit()
                    .putString("mount_transport", MountTransportType.BLUETOOTH.name)
                    .putString("mount_bluetooth_address", address)
                    .putString("mount_protocol", _mountProtocol.value.name)
                    .apply()
                _activeUsbMountDeviceId.value = null
                val coordinates = controller.connectBluetooth(
                    context,
                    address,
                    _mountProtocol.value
                ) { mode ->
                    if (generation == mountConnectionGeneration) {
                        _mountConnectionMessage.value = when (mode) {
                            RfcommMode.STANDARD ->
                                "\u6807\u51c6\u6a21\u5f0f\u8fde\u63a5\u4e2d"
                            RfcommMode.COMPATIBLE ->
                                "\u6b63\u5728\u5c1d\u8bd5\u517c\u5bb9\u6a21\u5f0f"
                        }
                    }
                }
                if (generation != mountConnectionGeneration) return@launch
                runCatching { controller.setMoveRate(_mountSlewRate.value) }
                _mountCoordinates.value = coordinates
                runCatching { controller.readSite() }
                    .onSuccess { _mountSite.value = it }
                _mountConnectionState.value = MountConnectionState.Connected
                _mountConnectionMessage.value = "\u84dd\u7259\u5df2\u8fde\u63a5"
                updateDetectedMountInfo()
                _statusMessage.value =
                    "Mount connected Bluetooth: ${coordinates.formatRa()} ${coordinates.formatDec()}"
            } catch (cancelled: CancellationException) {
                if (generation == mountConnectionGeneration) {
                    _mountConnectionState.value = MountConnectionState.Disconnected
                    _mountConnectionMessage.value = "\u5df2\u53d6\u6d88"
                    _statusMessage.value = _mountConnectionMessage.value
                }
                throw cancelled
            } catch (error: Throwable) {
                if (generation != mountConnectionGeneration) return@launch
                FileLogger.e(TAG, "connectBluetooth failed", error)
                controller.disconnect()
                _activeUsbMountDeviceId.value = null
                _mountCoordinates.value = null
                _mountSite.value = null
                _mountTime.value = null
                _mountDetectedInfo.value = ""
                val message = if (error is RfcommConnectionTimeoutException) {
                    "\u8fde\u63a5\u8d85\u65f6"
                } else {
                    error.message ?: "Mount Bluetooth connection failed"
                }
                _mountConnectionState.value = MountConnectionState.Error(message)
                _mountConnectionMessage.value = message
                _statusMessage.value = "Mount Bluetooth error: $message"
            } finally {
                if (generation == mountConnectionGeneration) {
                    _mountBusy.value = false
                    mountConnectJob = null
                }
            }
            }
        }
    }

    private fun connectSynScanWifiMount() {
        val host = _synScanHost.value.trim().ifBlank { DEFAULT_SYNSCAN_HOST }
        val port = _synScanPort.value.toIntOrNull() ?: DEFAULT_SYNSCAN_PORT
        val site = _mountSite.value ?: storedObservatorySite()
        scope.launch {
            transportLock.withLock {
            FileLogger.i(TAG, "connectSynScan host=$host port=$port")
            _mountBusy.value = true
            _mountConnectionState.value = MountConnectionState.Connecting
            try {
                prefs.edit()
                    .putString("mount_transport", MountTransportType.SYNSCAN_WIFI.name)
                    .putString("synscan_host", host)
                    .putInt("synscan_port", port)
                    .putString("mount_protocol", MountProtocolType.SKYWATCHER.name)
                    .putString("skywatcher_mount_mode", _skyWatcherMountMode.value.name)
                    .apply()
                _activeUsbMountDeviceId.value = null
                val coordinates = controller.connectSynScanWifi(
                    host = host,
                    port = port,
                    context = application,
                    site = site,
                    mode = _skyWatcherMountMode.value
                )
                _mountProtocol.value = MountProtocolType.SKYWATCHER
                runCatching { controller.setMoveRate(_mountSlewRate.value) }
                _mountCoordinates.value = coordinates
                runCatching { controller.readSite() }
                    .onSuccess { _mountSite.value = it }
                _mountConnectionState.value = MountConnectionState.Connected
                updateDetectedMountInfo()
                startMountCoordinatePolling()
                _statusMessage.value =
                    "Sky-Watcher connected: ${coordinates.formatRa()} ${coordinates.formatDec()}"
            } catch (e: Throwable) {
                FileLogger.e(TAG, "connectSynScan failed", e)
                controller.disconnect()
                _mountCoordinates.value = null
                _mountSite.value = null
                _mountTime.value = null
                _mountDetectedInfo.value = ""
                _mountConnectionState.value =
                    MountConnectionState.Error(e.message ?: "SynScan Wi-Fi failed")
                _statusMessage.value = "SynScan Wi-Fi error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
            }
        }
    }

    private fun storedObservatorySite(): MountSite? {
        if (!prefs.contains("polar_latitude_deg") || !prefs.contains("polar_longitude_deg")) {
            return null
        }
        return MountSite(
            latitudeDeg = prefs.getFloat("polar_latitude_deg", 0f).toDouble(),
            longitudeDeg = prefs.getFloat("polar_longitude_deg", 0f).toDouble()
        )
    }

    private fun updateDetectedMountInfo() {
        val protocol = when (controller.activeProtocol) {
            MountProtocolType.IOPTRON -> "iOptron V3"
            MountProtocolType.LX200_ONSTEP -> "LX200 / OnStep"
            MountProtocolType.SKYWATCHER -> "Sky-Watcher SynScan"
            MountProtocolType.AUTO -> "Auto"
        }
        _mountDetectedInfo.value =
            listOfNotNull(controller.mountModel, protocol).joinToString(" / ")
    }

    fun cancelMountConnection() {
        mountConnectionGeneration++
        mountConnectJob?.cancel()
        mountConnectJob = null
        controller.cancelPendingBluetoothConnection()
        _mountBusy.value = false
        _mountConnectionState.value = MountConnectionState.Disconnected
        _mountConnectionMessage.value = "\u5df2\u53d6\u6d88"
        _statusMessage.value = _mountConnectionMessage.value
    }

    fun disconnectMount() {
        if (mountConnectJob?.isActive == true) {
            cancelMountConnection()
            return
        }
        stopMountCoordinatePolling()
        scope.launch {
            transportLock.withLock {
            FileLogger.i(TAG, "disconnectMount")
            _mountBusy.value = true
            try {
                val motionStopped = motionRunner.stop()
                if (!motionStopped) runCatching { controller.abortMotion() }
                controller.disconnect()
            } finally {
                _activeUsbMountDeviceId.value = null
                _mountCoordinates.value = null
                _mountSite.value = null
                _mountTime.value = null
                _mountDetectedInfo.value = ""
                _mountConnectionState.value = MountConnectionState.Disconnected
                _mountBusy.value = false
                FileLogger.i(TAG, "disconnectMount done")
            }
            }
        }
    }

    fun readMountSite() {
        if (!controller.isConnected) return
        scope.launch {
            _mountBusy.value = true
            try {
                val site = controller.readSite()
                _mountSite.value = site
                _mountConnectionState.value = MountConnectionState.Connected
                _statusMessage.value = "Mount site: ${site.format()}"
            } catch (e: Throwable) {
                _mountConnectionState.value = MountConnectionState.Error(e.message ?: "Mount site read failed")
                _statusMessage.value = "Mount site error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
        }
    }

    fun syncPhoneSiteToMount(latitudeDeg: Double, longitudeDeg: Double) {
        if (!controller.isConnected) return
        scope.launch {
            _mountBusy.value = true
            try {
                val site = MountSite(latitudeDeg, longitudeDeg)
                controller.setSite(site)
                _mountSite.value = site
                _mountConnectionState.value = MountConnectionState.Connected
                _statusMessage.value = "Mount site updated: ${site.format()}"
            } catch (e: Throwable) {
                _mountConnectionState.value = MountConnectionState.Error(e.message ?: "Mount site sync failed")
                _statusMessage.value = "Mount site error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
        }
    }

    fun readMountTime() {
        if (!controller.isConnected) return
        scope.launch {
            _mountBusy.value = true
            try {
                val time = controller.readTime()
                _mountTime.value = time
                _mountConnectionState.value = MountConnectionState.Connected
                _statusMessage.value = "Mount time updated: ${time.epochMillis}"
            } catch (e: Throwable) {
                _mountConnectionState.value = MountConnectionState.Error(
                    e.message ?: "Mount time read failed"
                )
                _statusMessage.value = "Mount time error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
        }
    }

    fun syncPhoneTimeToMount(epochMillis: Long = System.currentTimeMillis()) {
        if (!controller.isConnected) return
        scope.launch {
            _mountBusy.value = true
            try {
                val time = MountTime(epochMillis)
                controller.setTime(time)
                _mountTime.value = time
                _mountConnectionState.value = MountConnectionState.Connected
                _statusMessage.value = "Mount time updated from phone"
            } catch (e: Throwable) {
                _mountConnectionState.value = MountConnectionState.Error(
                    e.message ?: "Mount time sync failed"
                )
                _statusMessage.value = "Mount time error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
        }
    }

    fun gotoMountTarget(name: String, raHours: Double, decDeg: Double) {
        if (!controller.isConnected) {
            _statusMessage.value = "Connect the mount before GOTO"
            return
        }
        val target = MountCoordinates(raHours = raHours, decDeg = decDeg)
        val started = motionRunner.start(
            state = MountMotionState(
                type = MountMotionType.GOTO,
                label = "GOTO $name",
                slewing = false,
                holdStop = false
            ),
            onError = { error ->
                _mountConnectionState.value =
                    MountConnectionState.Error(error.message ?: "Mount GOTO failed")
                _mountMoveStatus.value = "Mount GOTO error: ${error.message}"
                _statusMessage.value = _mountMoveStatus.value
                _mountBusy.value = false
            }
        ) {
            _mountBusy.value = true
            try {
                controller.slewTo(target)
                _mountMoveStatus.value =
                    "GOTO $name: ${target.formatRa()} ${target.formatDec()}"
                _statusMessage.value = _mountMoveStatus.value
                awaitGotoTarget(name, target)
                _mountMoveStatus.value = "GOTO $name complete"
                _statusMessage.value = _mountMoveStatus.value
            } finally {
                _mountBusy.value = false
            }
        }
        if (!started) {
            _statusMessage.value = "Stop the current mount motion before starting another GOTO"
        }
    }

    /** Visual sync: mount stays put; its reported pointing becomes [raHours]/[decDeg]. */
    fun syncMountToTarget(name: String, raHours: Double, decDeg: Double) {
        if (!controller.isConnected) {
            _statusMessage.value = application.getString(R.string.connect_mount_for_goto)
            return
        }
        if (!controller.supportsSync) {
            _statusMessage.value = application.getString(R.string.mount_sync_unsupported)
            return
        }
        val target = MountCoordinates(raHours = raHours, decDeg = decDeg)
        scope.launch {
            _mountBusy.value = true
            try {
                controller.syncTo(target)
                val coordinates = runCatching { controller.readCoordinates() }.getOrNull()
                if (coordinates != null) {
                    _mountCoordinates.value = coordinates
                }
                _mountConnectionState.value = MountConnectionState.Connected
                _statusMessage.value = application.getString(
                    R.string.mount_synced_to,
                    name,
                    target.formatRa(),
                    target.formatDec()
                )
            } catch (e: Throwable) {
                val detail = e.message ?: application.getString(R.string.mount_sync_failed)
                _mountConnectionState.value = MountConnectionState.Error(detail)
                _statusMessage.value = application.getString(R.string.mount_sync_error, detail)
            } finally {
                _mountBusy.value = false
            }
        }
    }

    /**
     * Closed-loop precision GOTO: slew → capture/solve → sync or corrective slew →
     * repeat until sky error ≤ [toleranceArcmin].
     *
     * [captureAndSolve] must wait for a fresh camera frame, plate-solve, and return the
     * solved sky pointing as mount coordinates (RA hours / Dec degrees).
     */
    suspend fun verifyCalibrationEnvironment(expected: com.indigo.mobileobservatory.astro.ObserverSite) {
        val capabilities = pointingCapabilities.value
        check(capabilities.goto && (capabilities.readSite || capabilities.siteUsesLocalConfiguration)) {
            application.getString(R.string.wfp_prepare_mount)
        }
        val site = controller.readSite()
        val longitudeDifference = ((site.longitudeDeg - expected.longitudeDeg + 540.0) % 360.0) - 180.0
        check(kotlin.math.abs(site.latitudeDeg - expected.latitudeDeg) <= .1 && kotlin.math.abs(longitudeDifference) <= .1) {
            application.getString(R.string.wfp_prepare_mount)
        }
        _mountSite.value = site
        if (capabilities.readTime) {
            val time = controller.readTime()
            check(kotlin.math.abs(time.epochMillis - System.currentTimeMillis()) <= 60_000) {
                application.getString(R.string.wfp_prepare_mount)
            }
            _mountTime.value = time
        }
    }

    suspend fun runCalibrationMotion(
        operation: suspend (slew: suspend (name: String, coordinates: MountCoordinates) -> Unit) -> Unit
    ) {
        check(pointingCapabilities.value.goto) { "GOTO is unavailable for this mount connection" }
        val owner = java.util.UUID.randomUUID().toString()
        val completion = kotlinx.coroutines.CompletableDeferred<Unit>()
        val started = motionRunner.start(MountMotionState(MountMotionType.GOTO,
            application.getString(R.string.wf_pointing_setup), holdStop = true, ownerToken = owner),
            onError = { completion.completeExceptionally(it) }) {
            _mountBusy.value = true
            try {
                operation { name, coordinates ->
                    check(motionRunner.state.value.ownerToken == owner && pointingCapabilities.value.goto)
                    _mountMoveStatus.value = application.getString(R.string.wfp_auto_moving, name)
                    controller.slewTo(coordinates)
                    awaitGotoTarget(name, coordinates)
                    delay(1_000)
                    _mountMoveStatus.value = application.getString(R.string.wfp_sampling)
                }
                completion.complete(Unit)
            } catch (cancelled: CancellationException) {
                completion.cancel(cancelled)
                throw cancelled
            } finally { _mountBusy.value = false }
        }
        check(started) { "Stop the current mount task before automatic calibration" }
        try {
            kotlinx.coroutines.coroutineScope {
                // Cancellation can finish the runner before its lazy operation body ever starts.
                // In that case the body's catch cannot resolve this caller's promise.
                val ownerWatcher = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                    motionState.first { it.ownerToken != owner }
                    if (!completion.isCompleted) {
                        completion.cancel(CancellationException("Automatic calibration was stopped"))
                    }
                }
                try { completion.await() } finally { ownerWatcher.cancel() }
            }
        }
        catch (cancelled: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { motionRunner.stop(owner) }
            throw cancelled
        }
    }

    /** Preflight failures belong to the pointing task, so its caller can display recovery. */
    fun rejectPrecisionGoto(name: String, message: String): Boolean {
        _statusMessage.value = message
        if (!_precisionGotoProgress.value.isActive) {
            _precisionGotoProgress.value = PrecisionGotoProgress(phase = PrecisionGotoPhase.FAILED,
                targetName = name, message = message)
        }
        return false
    }

    fun startPrecisionGoto(
        name: String,
        raHours: Double,
        decDeg: Double,
        maxIterations: Int = PrecisionGotoMath.MAX_ITERATIONS,
        toleranceArcmin: Double = PrecisionGotoMath.TOLERANCE_ARCMIN,
        captureAndSolve: suspend (hint: MountCoordinates?) -> MountCoordinates
    ): Boolean {
        if (!controller.isConnected) {
            return rejectPrecisionGoto(name, "Connect the mount before precision GOTO")
        }
        val target = MountCoordinates(raHours = raHours, decDeg = decDeg)
        val stopArcmin = PrecisionGotoMath.clampToleranceArcmin(toleranceArcmin)
        val started = motionRunner.start(
            state = MountMotionState(
                type = MountMotionType.GOTO,
                label = "Precision GOTO $name",
                holdStop = true
            ),
            onError = { error ->
                val message = error.message ?: "Precision GOTO failed"
                _mountConnectionState.value = MountConnectionState.Error(message)
                _mountMoveStatus.value = message
                _statusMessage.value = message
                _mountBusy.value = false
                _precisionGotoProgress.value = PrecisionGotoProgress(
                    phase = PrecisionGotoPhase.FAILED,
                    targetName = name,
                    maxIterations = maxIterations,
                    message = message
                )
            }
        ) {
            _mountBusy.value = true
            var command = target
            try {
                for (iteration in 1..maxIterations) {
                    publishPrecisionProgress(
                        phase = PrecisionGotoPhase.SLEWING,
                        name = name,
                        iteration = iteration,
                        maxIterations = maxIterations,
                        message = "Slewing (pass $iteration/$maxIterations)"
                    )
                    controller.slewTo(command)
                    awaitGotoTarget(name, command)

                    publishPrecisionProgress(
                        phase = PrecisionGotoPhase.SETTLING,
                        name = name,
                        iteration = iteration,
                        maxIterations = maxIterations,
                        message = "Settling for plate solve"
                    )
                    delay(1_000)

                    val hint = runCatching { controller.readCoordinates() }.getOrNull()
                    publishPrecisionProgress(
                        phase = PrecisionGotoPhase.CAPTURING,
                        name = name,
                        iteration = iteration,
                        maxIterations = maxIterations,
                        message = "Capturing frame"
                    )
                    publishPrecisionProgress(
                        phase = PrecisionGotoPhase.SOLVING,
                        name = name,
                        iteration = iteration,
                        maxIterations = maxIterations,
                        message = "Plate solving"
                    )
                    val solved = captureAndSolve(hint)
                    val errorDeg = PrecisionGotoMath.angularSeparationDeg(target, solved)
                    val errorArcmin = PrecisionGotoMath.degreesToArcmin(errorDeg)
                    publishPrecisionProgress(
                        phase = PrecisionGotoPhase.SOLVING,
                        name = name,
                        iteration = iteration,
                        maxIterations = maxIterations,
                        errorArcmin = errorArcmin,
                        solved = solved,
                        message = "Sky error %.1f′ (pass $iteration/$maxIterations)"
                            .format(java.util.Locale.US, errorArcmin)
                    )
                    if (PrecisionGotoMath.withinTolerance(errorArcmin, stopArcmin)) {
                        var verifiedSolved = solved
                        var verifiedErrorArcmin = errorArcmin
                        if (controller.supportsSync) {
                            publishPrecisionProgress(
                                phase = PrecisionGotoPhase.SYNCING,
                                name = name,
                                iteration = iteration,
                                maxIterations = maxIterations,
                                errorArcmin = errorArcmin,
                                solved = solved,
                                message = "Syncing mount to solved sky"
                            )
                            controller.syncTo(solved)
                            refreshMountCoordinatesAfterCommand()
                            publishPrecisionProgress(phase = PrecisionGotoPhase.SOLVING, name = name,
                                iteration = iteration, maxIterations = maxIterations,
                                message = "Verifying with a new optical exposure after sync")
                            verifiedSolved = captureAndSolve(target)
                            verifiedErrorArcmin = PrecisionGotoMath.angularSeparationDeg(target, verifiedSolved) * 60.0
                            check(PrecisionGotoMath.withinTolerance(verifiedErrorArcmin, stopArcmin)) {
                                "Independent optical verification after sync is outside tolerance (%.1f′)."
                                    .format(java.util.Locale.US, verifiedErrorArcmin)
                            }
                        }
                        publishPrecisionProgress(
                            phase = PrecisionGotoPhase.SUCCEEDED,
                            name = name,
                            iteration = iteration,
                            maxIterations = maxIterations,
                            errorArcmin = verifiedErrorArcmin,
                            solved = verifiedSolved,
                            message = "Precision GOTO complete (%.1f′)"
                                .format(java.util.Locale.US, verifiedErrorArcmin)
                        )
                        _mountMoveStatus.value = _precisionGotoProgress.value.message
                        _statusMessage.value = _mountMoveStatus.value
                        return@start
                    }

                    if (iteration == maxIterations) {
                        error(
                            "Precision GOTO did not converge below %.0f′ (last %.1f′)."
                                .format(java.util.Locale.US, stopArcmin, errorArcmin)
                        )
                    }

                    if (controller.supportsSync) {
                        publishPrecisionProgress(
                            phase = PrecisionGotoPhase.SYNCING,
                            name = name,
                            iteration = iteration,
                            maxIterations = maxIterations,
                            errorArcmin = errorArcmin,
                            solved = solved,
                            message = "Syncing mount to solved sky"
                        )
                        controller.syncTo(solved)
                        refreshMountCoordinatesAfterCommand()
                        command = target
                    } else {
                        val mountNow = controller.readCoordinates()
                        command = PrecisionGotoMath.correctiveCommand(mountNow, target, solved)
                        publishPrecisionProgress(
                            phase = PrecisionGotoPhase.CORRECTING,
                            name = name,
                            iteration = iteration,
                            maxIterations = maxIterations,
                            errorArcmin = errorArcmin,
                            solved = solved,
                            message = "Corrective slew (no mount sync)"
                        )
                    }
                }
            } finally {
                _mountBusy.value = false
            }
        }
        if (!started) {
            rejectPrecisionGoto(name, "Stop the current mount motion before precision GOTO")
        }
        return started
    }

    private fun publishPrecisionProgress(
        phase: PrecisionGotoPhase,
        name: String,
        iteration: Int,
        maxIterations: Int,
        errorArcmin: Double? = null,
        solved: MountCoordinates? = null,
        message: String
    ) {
        _precisionGotoProgress.value = PrecisionGotoProgress(
            phase = phase,
            targetName = name,
            iteration = iteration,
            maxIterations = maxIterations,
            errorArcmin = errorArcmin,
            solvedRaHours = solved?.raHours,
            solvedDecDeg = solved?.decDeg,
            message = message
        )
        _mountMoveStatus.value = message
        _statusMessage.value = message
    }

    private suspend fun awaitGotoTarget(name: String, target: MountCoordinates) {
        val deadline = System.currentTimeMillis() + 300_000L
        var arrival = GotoArrival.State()
        var previous: MountCoordinates? = null
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(750)
            val coordinates = controller.readCoordinates()
            _mountCoordinates.value = coordinates
            _mountConnectionState.value = MountConnectionState.Connected
            val angularError = PrecisionGotoMath.angularSeparationDeg(target, coordinates)
            _mountMoveStatus.value = "GOTO $name  error %.2f deg"
                .format(java.util.Locale.US, angularError)
            val moved = previous?.let { last ->
                PrecisionGotoMath.angularSeparationDeg(last, coordinates)
            }
            val (next, decision) = GotoArrival.step(arrival, angularError, moved)
            arrival = next
            previous = coordinates
            if (arrival.sawSlewMotion) {
                motionRunner.update { it.copy(slewing = true) }
            }
            if (decision == GotoArrival.Decision.ARRIVED) {
                restoreTrackingAfterSlew()
                return
            }
        }
        error("Mount GOTO timed out before reaching the target.")
    }

    fun moveMountRaBy(targetDistanceDeg: Double, east: Boolean, moveRateDegPerSec: Double) {
        if (!controller.isConnected || targetDistanceDeg <= 0.0) return
        val started = motionRunner.start(
            state = MountMotionState(MountMotionType.RA_MOVE, "RA move"),
            onError = { error ->
                _mountConnectionState.value =
                    MountConnectionState.Error(error.message ?: "Mount RA move failed")
                _mountMoveStatus.value = "RA move error: ${error.message}"
                _statusMessage.value = _mountMoveStatus.value
                _mountBusy.value = false
            }
        ) {
            _mountBusy.value = true
            try {
                val startRa = controller.readCoordinates().raDeg
                val timeoutMs = ((targetDistanceDeg /
                    moveRateDegPerSec.coerceAtLeast(0.1)) * 2.0 * 1000.0)
                    .toLong()
                    .coerceIn(10_000L, 180_000L)
                val deadline = System.currentTimeMillis() + timeoutMs
                controller.startRaMove(east)
                var distance = 0.0
                while (System.currentTimeMillis() < deadline && distance < targetDistanceDeg) {
                    kotlinx.coroutines.delay(500)
                    val coordinates = controller.readCoordinates()
                    _mountCoordinates.value = coordinates
                    distance = raDistanceDeg(startRa, coordinates.raDeg)
                    _mountMoveStatus.value = "RA move %.2f / %.2f deg"
                        .format(java.util.Locale.US, distance, targetDistanceDeg)
                }
                controller.stopRaMove()
                val finalCoordinates = controller.readCoordinates()
                _mountCoordinates.value = finalCoordinates
                val finalDistance = raDistanceDeg(startRa, finalCoordinates.raDeg)
                _mountMoveStatus.value = "RA move done %.2f / %.2f deg"
                    .format(java.util.Locale.US, finalDistance, targetDistanceDeg)
                _statusMessage.value = _mountMoveStatus.value
            } finally {
                _mountBusy.value = false
            }
        }
        if (!started) {
            _statusMessage.value = "Stop the current mount motion before starting an RA move"
        }
    }

    fun stopMountRaMove() {
        stopMountMotion()
    }

    fun stopMountMotion() {
        scope.launch {
            val active = motionState.value.isActive
            if (active) {
                _mountMoveStatus.value = "Stopping mount..."
                _statusMessage.value = _mountMoveStatus.value
            }
            val stopped = motionRunner.stop()
            if (!stopped && controller.isConnected) {
                runCatching { controller.abortMotion() }
            }
            _mountBusy.value = false
            if (_precisionGotoProgress.value.isActive) {
                _precisionGotoProgress.value = _precisionGotoProgress.value.copy(
                    phase = PrecisionGotoPhase.CANCELLED, message = "Pointing cancelled")
            }
            _mountMoveStatus.value = "Mount stopped"
            _statusMessage.value = _mountMoveStatus.value
            refreshMountCoordinatesAfterCommand()
        }
    }
    fun startMountManualMove(direction: MountDirection) {
        if (!controller.isConnected) return
        val started = motionRunner.start(
            state = MountMotionState(
                MountMotionType.MANUAL,
                "Manual ${direction.name.lowercase(java.util.Locale.US)}",
                manualDirection = direction
            ),
            onError = { error ->
                _mountConnectionState.value =
                    MountConnectionState.Error(error.message ?: "Mount move failed")
                _mountMoveStatus.value = "Mount move error: ${error.message}"
                _statusMessage.value = _mountMoveStatus.value
            }
        ) {
            controller.startMove(direction)
            startMountCoordinatePolling()
            _mountMoveStatus.value =
                "Moving ${direction.name.lowercase(java.util.Locale.US)}"
            _statusMessage.value = _mountMoveStatus.value
            kotlinx.coroutines.awaitCancellation()
        }
        if (!started) {
            _statusMessage.value = "Stop the current mount motion before manual movement"
        }
    }

    fun stopMountManualMove(direction: MountDirection? = null) {
        if (motionState.value.permitsManualRelease(direction)) {
            stopMountMotion()
        }
    }

    private fun startMountCoordinatePolling() {
        mountCoordinatePollingJob?.cancel()
        mountCoordinatePollingJob = scope.launch {
            while (controller.isConnected) {
                refreshMountCoordinatesAfterCommand()
                runCatching { controller.refreshTracking() }
                kotlinx.coroutines.delay(500)
            }
        }
    }

    private fun stopMountCoordinatePolling() {
        mountCoordinatePollingJob?.cancel()
        mountCoordinatePollingJob = null
    }

    fun setMountSlewRate(rate: MountSlewRate) {
        if (!controller.isConnected) {
            _mountSlewRate.value = rate
            prefs.edit().putString("mount_slew_rate", rate.name).apply()
            return
        }
        scope.launch {
            try {
                controller.setMoveRate(rate)
                _mountSlewRate.value = rate
                prefs.edit().putString("mount_slew_rate", rate.name).apply()
                _mountMoveStatus.value = "Rate ${rate.label}"
                _statusMessage.value = _mountMoveStatus.value
            } catch (e: Throwable) {
                _mountConnectionState.value = MountConnectionState.Error(e.message ?: "Mount rate failed")
                _statusMessage.value = "Mount rate error: ${e.message}"
            }
        }
    }

    fun setMountTracking(enabled: Boolean) {
        setMountTrackingRate(if (enabled) MountTrackingRate.SIDEREAL else MountTrackingRate.OFF)
    }

    fun setMountTrackingRate(rate: MountTrackingRate) {
        if (!controller.isConnected) return
        scope.launch {
            _mountBusy.value = true
            try {
                controller.setTrackingRate(rate)
                _mountTrackingRate.value = rate
                _mountTrackingEnabled.value = rate.tracks
                _mountMoveStatus.value = if (rate.tracks) {
                    "Tracking ${rate.name.lowercase(java.util.Locale.US)}"
                } else {
                    "Tracking stopped"
                }
                _statusMessage.value = _mountMoveStatus.value
                refreshMountCoordinatesAfterCommand()
            } catch (e: Throwable) {
                _mountConnectionState.value = MountConnectionState.Error(e.message ?: "Mount tracking failed")
                _statusMessage.value = "Mount tracking error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
        }
    }

    private suspend fun restoreTrackingAfterSlew() {
        val rate = _mountTrackingRate.value
        if (!rate.tracks) return
        runCatching { controller.setTrackingRate(rate) }
    }

    fun goMountHome() {
        if (!controller.isConnected) return
        val started = motionRunner.start(
            state = MountMotionState(MountMotionType.HOME, "Go home"),
            onError = { error ->
                _mountConnectionState.value =
                    MountConnectionState.Error(error.message ?: "Mount home failed")
                _mountMoveStatus.value = "Mount home error: ${error.message}"
                _statusMessage.value = _mountMoveStatus.value
                _mountBusy.value = false
            }
        ) {
            _mountBusy.value = true
            try {
                controller.goHome()
                _mountMoveStatus.value = "Mount going home"
                _statusMessage.value = _mountMoveStatus.value
                awaitMountStopped()
                _mountMoveStatus.value = "Mount home complete"
                _statusMessage.value = _mountMoveStatus.value
            } finally {
                _mountBusy.value = false
            }
        }
        if (!started) {
            _statusMessage.value = "Stop the current mount motion before going home"
        }
    }

    private suspend fun awaitMountStopped() {
        val deadline = System.currentTimeMillis() + 300_000L
        var previous: MountCoordinates? = null
        var stableSamples = 0
        kotlinx.coroutines.delay(1_500)
        while (System.currentTimeMillis() < deadline) {
            val coordinates = controller.readCoordinates()
            _mountCoordinates.value = coordinates
            val last = previous
            if (last != null) {
                val raChange = raDistanceDeg(last.raDeg, coordinates.raDeg)
                val decChange = kotlin.math.abs(last.decDeg - coordinates.decDeg)
                stableSamples = if (raChange <= MOTION_STABLE_TOLERANCE_DEG &&
                    decChange <= MOTION_STABLE_TOLERANCE_DEG) {
                    stableSamples + 1
                } else {
                    0
                }
                if (stableSamples >= MOTION_STABLE_SAMPLES) return
            }
            previous = coordinates
            kotlinx.coroutines.delay(750)
        }
        error("Mount home motion timed out.")
    }

    fun setMountHomeHere() {
        if (!controller.isConnected) return
        scope.launch {
            _mountBusy.value = true
            try {
                controller.setHomeHere()
                _mountMoveStatus.value = "Mount home reset"
                _statusMessage.value = _mountMoveStatus.value
                refreshMountCoordinatesAfterCommand()
            } catch (e: Throwable) {
                _mountConnectionState.value = MountConnectionState.Error(e.message ?: "Mount home reset failed")
                _statusMessage.value = "Mount home reset error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
        }
    }

    private fun raDistanceDeg(a: Double, b: Double): Double =
        PrecisionGotoMath.raDistanceDeg(a, b)

    private suspend fun refreshMountCoordinatesAfterCommand() {
        runCatching { controller.readCoordinates() }
            .onSuccess {
                _mountCoordinates.value = it
                _mountConnectionState.value = MountConnectionState.Connected
            }
    }

    fun readMountCoordinates() {
        if (!controller.isConnected) return
        scope.launch {
            _mountBusy.value = true
            try {
                val coordinates = controller.readCoordinates()
                _mountCoordinates.value = coordinates
                _mountConnectionState.value = MountConnectionState.Connected
                _statusMessage.value = "Mount: ${coordinates.formatRa()} ${coordinates.formatDec()}"
            } catch (e: Throwable) {
                _mountConnectionState.value = MountConnectionState.Error(e.message ?: "Mount read failed")
                _statusMessage.value = "Mount error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
        }
    }

    val isConnected: Boolean get() = controller.isConnected

    suspend fun setGuideRate() {
        controller.setMoveRate(MountSlewRate.GUIDE)
    }

    suspend fun startGuidePulse(direction: MountDirection) {
        controller.startMove(direction)
    }

    suspend fun stopGuidePulse(direction: MountDirection) {
        controller.stopMove(direction)
    }

    suspend fun stopAllMotion() {
        controller.stopMove()
    }

    fun reportError(message: String) {
        _mountConnectionState.value = MountConnectionState.Error(message)
    }

    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter(ACTION_MOUNT_USB_PERMISSION)
        ContextCompat.registerReceiver(
            application,
            mountUsbPermissionReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        receiverRegistered = true
        scanMountUsbDevices()
    }

    fun cancelJobs() {
        stopMountCoordinatePolling()
        mountConnectionGeneration++
        mountConnectJob?.cancel()
        mountConnectJob = null
        controller.cancelPendingBluetoothConnection()
        pendingMountUsbConnect = false
    }

    fun unregisterReceiver() {
        if (!receiverRegistered) return
        runCatching { application.unregisterReceiver(mountUsbPermissionReceiver) }
        receiverRegistered = false
    }

    fun closeController() {
        controller.close()
    }

    private var receiverRegistered = false
}
