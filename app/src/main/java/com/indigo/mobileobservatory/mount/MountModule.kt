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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Owns mount state, transport lifecycle, permissions, and mutually exclusive motion. */
class MountModule(
    private val application: Application,
    private val scope: CoroutineScope
) {
    private companion object {
        const val ACTION_MOUNT_USB_PERMISSION = "com.indigo.mobileobservatory.MOUNT_USB_PERMISSION"
        const val GOTO_TOLERANCE_DEG = 0.05
        const val GOTO_STABLE_SAMPLES = 2
        const val MOTION_STABLE_TOLERANCE_DEG = 0.01
        const val MOTION_STABLE_SAMPLES = 3
    }

    private val prefs = application.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
    private val controller = Lx200MountController()
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

    private val _synScanHost =
        MutableStateFlow(prefs.getString("synscan_host", "127.0.0.1") ?: "127.0.0.1")
    val synScanHost: StateFlow<String> = _synScanHost.asStateFlow()

    private val _synScanPort =
        MutableStateFlow(prefs.getInt("synscan_port", 11882).toString())
    val synScanPort: StateFlow<String> = _synScanPort.asStateFlow()

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

    private val _mountBusy = MutableStateFlow(false)
    val mountBusy: StateFlow<Boolean> = _mountBusy.asStateFlow()

    private val _mountConnectionMessage = MutableStateFlow("")
    val mountConnectionMessage: StateFlow<String> = _mountConnectionMessage.asStateFlow()

    private val _mountMoveStatus = MutableStateFlow("")
    val mountMoveStatus: StateFlow<String> = _mountMoveStatus.asStateFlow()

    private val _mountSlewRate = MutableStateFlow(
        MountSlewRate.fromStoredName(prefs.getString("mount_slew_rate", MountSlewRate.DEFAULT.name))
    )
    val mountSlewRate: StateFlow<MountSlewRate> = _mountSlewRate.asStateFlow()

    private val _mountTrackingEnabled = MutableStateFlow(false)
    val mountTrackingEnabled: StateFlow<Boolean> = _mountTrackingEnabled.asStateFlow()

    private val _precisionGotoProgress = MutableStateFlow(PrecisionGotoProgress())
    val precisionGotoProgress: StateFlow<PrecisionGotoProgress> = _precisionGotoProgress.asStateFlow()

    @Volatile private var pendingMountUsbConnect = false
    private var mountCoordinatePollingJob: kotlinx.coroutines.Job? = null
    private var mountConnectJob: kotlinx.coroutines.Job? = null
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
                _mountDetectedInfo.value = ""
                _mountConnectionState.value = MountConnectionState.Error(e.message ?: "Mount connection failed")
                _statusMessage.value = "Mount error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
        }


    }

    private fun connectUsbMount() {
        scanMountUsbDevices()
        val devices = _mountUsbDevices.value
        if (devices.isEmpty()) {
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
            val intent = PendingIntent.getBroadcast(context, selected.deviceId, Intent(ACTION_MOUNT_USB_PERMISSION), flags)
            usbManager.requestPermission(usbDevice, intent)
            _statusMessage.value = "Requesting mount USB permission..."
            return
        }
        scope.launch {
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
            } catch (e: Throwable) {
                controller.disconnect()
                _activeUsbMountDeviceId.value = null
                _mountCoordinates.value = null
                _mountSite.value = null
                _mountDetectedInfo.value = ""
                _mountConnectionState.value = MountConnectionState.Error(e.message ?: "Mount USB connection failed")
                _statusMessage.value = "Mount USB error: ${e.message}"
            } finally {
                _mountBusy.value = false
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
                controller.disconnect()
                _activeUsbMountDeviceId.value = null
                _mountCoordinates.value = null
                _mountSite.value = null
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

    private fun connectSynScanWifiMount() {
        val host = _synScanHost.value.trim().ifBlank { "127.0.0.1" }
        val port = _synScanPort.value.toIntOrNull() ?: 11882
        scope.launch {
            _mountBusy.value = true
            _mountConnectionState.value = MountConnectionState.Connecting
            try {
                prefs.edit()
                    .putString("mount_transport", MountTransportType.SYNSCAN_WIFI.name)
                    .putString("synscan_host", host)
                    .putInt("synscan_port", port)
                    .putString("mount_protocol", MountProtocolType.SKYWATCHER.name)
                    .apply()
                _activeUsbMountDeviceId.value = null
                val coordinates = controller.connectSynScanWifi(host, port)
                _mountProtocol.value = MountProtocolType.SKYWATCHER
                runCatching { controller.setMoveRate(_mountSlewRate.value) }
                _mountCoordinates.value = coordinates
                runCatching { controller.readSite() }
                    .onSuccess { _mountSite.value = it }
                _mountConnectionState.value = MountConnectionState.Connected
                updateDetectedMountInfo()
                _statusMessage.value =
                    "Sky-Watcher connected: ${coordinates.formatRa()} ${coordinates.formatDec()}"
            } catch (e: Throwable) {
                controller.disconnect()
                _mountCoordinates.value = null
                _mountSite.value = null
                _mountDetectedInfo.value = ""
                _mountConnectionState.value =
                    MountConnectionState.Error(e.message ?: "SynScan Wi-Fi failed")
                _statusMessage.value = "SynScan Wi-Fi error: ${e.message}"
            } finally {
                _mountBusy.value = false
            }
        }
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
            _mountBusy.value = true
            try {
                val motionStopped = motionRunner.stop()
                if (!motionStopped) runCatching { controller.abortMotion() }
                controller.disconnect()
            } finally {
                _activeUsbMountDeviceId.value = null
                _mountCoordinates.value = null
                _mountSite.value = null
                _mountDetectedInfo.value = ""
                _mountConnectionState.value = MountConnectionState.Disconnected
                _mountBusy.value = false
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

    fun gotoMountTarget(name: String, raHours: Double, decDeg: Double) {
        if (!controller.isConnected) {
            _statusMessage.value = "Connect the mount before GOTO"
            return
        }
        val target = MountCoordinates(raHours = raHours, decDeg = decDeg)
        val started = motionRunner.start(
            state = MountMotionState(MountMotionType.GOTO, "GOTO $name"),
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
            _statusMessage.value = "Connect the mount before sync"
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
                _statusMessage.value =
                    "Synced mount to $name (${target.formatRa()} ${target.formatDec()})"
            } catch (e: Throwable) {
                _mountConnectionState.value =
                    MountConnectionState.Error(e.message ?: "Mount sync failed")
                _statusMessage.value = "Mount sync error: ${e.message}"
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
    fun startPrecisionGoto(
        name: String,
        raHours: Double,
        decDeg: Double,
        maxIterations: Int = PrecisionGotoMath.MAX_ITERATIONS,
        toleranceArcmin: Double = PrecisionGotoMath.TOLERANCE_ARCMIN,
        captureAndSolve: suspend (hint: MountCoordinates?) -> MountCoordinates
    ): Boolean {
        if (!controller.isConnected) {
            _statusMessage.value = "Connect the mount before precision GOTO"
            _precisionGotoProgress.value = PrecisionGotoProgress(
                phase = PrecisionGotoPhase.FAILED,
                targetName = name,
                message = "Connect the mount before precision GOTO"
            )
            return false
        }
        val target = MountCoordinates(raHours = raHours, decDeg = decDeg)
        val supportsSync = controller.activeProtocol != MountProtocolType.SKYWATCHER
        val started = motionRunner.start(
            state = MountMotionState(MountMotionType.GOTO, "Precision GOTO $name"),
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
                    if (errorArcmin <= toleranceArcmin) {
                        publishPrecisionProgress(
                            phase = PrecisionGotoPhase.SUCCEEDED,
                            name = name,
                            iteration = iteration,
                            maxIterations = maxIterations,
                            errorArcmin = errorArcmin,
                            solved = solved,
                            message = "Precision GOTO complete (%.1f′)"
                                .format(java.util.Locale.US, errorArcmin)
                        )
                        _mountMoveStatus.value = _precisionGotoProgress.value.message
                        _statusMessage.value = _mountMoveStatus.value
                        return@start
                    }

                    if (iteration == maxIterations) {
                        error(
                            "Precision GOTO did not converge below %.0f′ (last %.1f′)."
                                .format(java.util.Locale.US, toleranceArcmin, errorArcmin)
                        )
                    }

                    if (supportsSync) {
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
            _statusMessage.value = "Stop the current mount motion before precision GOTO"
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
        var stableSamples = 0
        while (System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(750)
            val coordinates = controller.readCoordinates()
            _mountCoordinates.value = coordinates
            _mountConnectionState.value = MountConnectionState.Connected
            val angularError = PrecisionGotoMath.angularSeparationDeg(target, coordinates)
            _mountMoveStatus.value = "GOTO $name  error %.2f deg"
                .format(java.util.Locale.US, angularError)
            stableSamples = if (angularError <= GOTO_TOLERANCE_DEG) {
                stableSamples + 1
            } else {
                0
            }
            if (stableSamples >= GOTO_STABLE_SAMPLES) return
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
            stopMountCoordinatePolling()
            if (!stopped && controller.isConnected) {
                runCatching { controller.abortMotion() }
            }
            _mountBusy.value = false
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
                "Manual ${direction.name.lowercase(java.util.Locale.US)}"
            ),
            onError = { error ->
                _mountConnectionState.value =
                    MountConnectionState.Error(error.message ?: "Mount move failed")
                _mountMoveStatus.value = "Mount move error: ${error.message}"
                _statusMessage.value = _mountMoveStatus.value
                stopMountCoordinatePolling()
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

    fun stopMountManualMove(@Suppress("UNUSED_PARAMETER") direction: MountDirection? = null) {
        if (motionState.value.type == MountMotionType.MANUAL) {
            stopMountMotion()
        }
    }

    private fun startMountCoordinatePolling() {
        mountCoordinatePollingJob?.cancel()
        mountCoordinatePollingJob = scope.launch {
            while (controller.isConnected) {
                refreshMountCoordinatesAfterCommand()
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
        if (!controller.isConnected) return
        scope.launch {
            _mountBusy.value = true
            try {
                controller.setTracking(enabled)
                _mountTrackingEnabled.value = enabled
                _mountMoveStatus.value = if (enabled) "Tracking enabled" else "Tracking disabled"
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            application.registerReceiver(mountUsbPermissionReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            application.registerReceiver(mountUsbPermissionReceiver, filter)
        }
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