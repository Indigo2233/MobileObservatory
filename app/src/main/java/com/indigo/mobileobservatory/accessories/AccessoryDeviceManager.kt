package com.indigo.mobileobservatory.accessories

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.indigo.mobileobservatory.accessories.cover.CoverCalibratorControllerRouter
import com.indigo.mobileobservatory.accessories.cover.DlcSerialCoverCalibratorAdapter
import com.indigo.mobileobservatory.accessories.cover.GeminiFlatpanelSerialAdapter
import com.indigo.mobileobservatory.accessories.filterwheel.FilterWheelControllerRouter
import com.indigo.mobileobservatory.accessories.filterwheel.OasisHidFilterWheelController
import com.indigo.mobileobservatory.accessories.filterwheel.ToupTekFilterWheelAdapter
import com.indigo.mobileobservatory.accessories.focuser.EFucoserSerialFocuserController
import com.indigo.mobileobservatory.accessories.focuser.FocuserControllerRouter
import com.indigo.mobileobservatory.accessories.focuser.GeminiEafSerialFocuserController
import com.indigo.mobileobservatory.accessories.focuser.OasisHidFocuserController
import com.indigo.mobileobservatory.accessories.focuser.ToupTekFocuserAdapter
import com.indigo.mobileobservatory.accessories.oasis.OasisUsbIds
import com.indigo.mobileobservatory.accessories.rotator.EcaaSerialRotatorAdapter
import com.indigo.mobileobservatory.accessories.rotator.RotatorAdapterKind
import com.indigo.mobileobservatory.accessories.rotator.RotatorControllerRouter
import com.indigo.mobileobservatory.accessories.rotator.WandererSerialRotatorAdapter
import com.indigo.mobileobservatory.camera.AccessoryDeviceEntry
import com.indigo.mobileobservatory.camera.AccessoryType
import com.indigo.mobileobservatory.camera.toupcam.EAFController
import com.indigo.mobileobservatory.camera.toupcam.ToupcamJni
import com.indigo.mobileobservatory.util.FileLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

class AccessoryDeviceManager(context: Context) {
    companion object {
        private const val TAG = "AccessoryMgr"
        private const val TOUPCAM_VENDOR_ID = 0x0547
        private const val ACTION_FILTER_WHEEL_PERMISSION =
            "com.indigo.mobileobservatory.ACCESSORY_FILTER_WHEEL_PERMISSION"
        private const val ACTION_FOCUSER_PERMISSION =
            "com.indigo.mobileobservatory.ACCESSORY_FOCUSER_PERMISSION"
        private const val ACTION_EFUCOSER_PERMISSION =
            "com.indigo.mobileobservatory.ACCESSORY_EFUCOSER_PERMISSION"
        private const val ACTION_COVER_PERMISSION =
            "com.indigo.mobileobservatory.ACCESSORY_COVER_PERMISSION"
        private const val ACTION_ROTATOR_PERMISSION =
            "com.indigo.mobileobservatory.ACCESSORY_ROTATOR_PERMISSION"
        private const val ACTION_SERIAL_AUTO_PERMISSION =
            "com.indigo.mobileobservatory.ACCESSORY_SERIAL_AUTO_PERMISSION"
    }

    private val appContext = context.applicationContext
    private val usbManager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var registered = false
    private val probeMutex = Mutex()
    private var probeJob: Job? = null
    private var probeGeneration = 0
    @Volatile
    private var excludedUsbDeviceIds: Set<Int> = emptySet()

    private val toupTekFilterWheel = com.indigo.mobileobservatory.camera.toupcam.FilterWheelController()
    private val oasisFilterWheel = OasisHidFilterWheelController()
    val filterWheelController = FilterWheelControllerRouter(
        scope = scope,
        toupTek = ToupTekFilterWheelAdapter(toupTekFilterWheel),
        oasis = oasisFilterWheel
    )
    private val toupTekFocuser = EAFController()
    private val efucoserFocuser = EFucoserSerialFocuserController()
    private val geminiEafFocuser = GeminiEafSerialFocuserController()
    private val oasisFocuser = OasisHidFocuserController()
    val focuserController = FocuserControllerRouter(
        scope = scope,
        toupTek = ToupTekFocuserAdapter(toupTekFocuser),
        efucoser = efucoserFocuser,
        geminiEaf = geminiEafFocuser,
        oasis = oasisFocuser
    )
    private val dlcCover = DlcSerialCoverCalibratorAdapter()
    private val geminiFlat = GeminiFlatpanelSerialAdapter()
    val coverController = CoverCalibratorControllerRouter(
        scope = scope,
        dlc = dlcCover,
        gemini = geminiFlat
    )
    private val ecaaRotator = EcaaSerialRotatorAdapter()
    private val wandererRotator = WandererSerialRotatorAdapter()
    val rotatorController = RotatorControllerRouter(
        scope = scope,
        ecaa = ecaaRotator,
        wanderer = wandererRotator
    )
    private val _activeFocuserDeviceId = MutableStateFlow<Int?>(null)
    val activeFocuserDeviceId: StateFlow<Int?> = _activeFocuserDeviceId.asStateFlow()
    private val _activeCoverDeviceId = MutableStateFlow<Int?>(null)
    val activeCoverDeviceId: StateFlow<Int?> = _activeCoverDeviceId.asStateFlow()
    private val _activeRotatorDeviceId = MutableStateFlow<Int?>(null)
    val activeRotatorDeviceId: StateFlow<Int?> = _activeRotatorDeviceId.asStateFlow()
    private var pendingFilterWheelDeviceId: Int? = null
    private var pendingFocuserDeviceId: Int? = null
    private var pendingEfucoserDeviceId: Int? = null
    private var pendingCoverDeviceId: Int? = null
    private var pendingRotatorDeviceId: Int? = null
    private var pendingSerialAutoDeviceId: Int? = null
    private var connectingFocuserDeviceId: Int? = null
    private var serialAutoJob: Job? = null
    private var focuserConnectJob: Job? = null
    private var focuserConnectGeneration = 0
    private var coverConnectJob: Job? = null
    private var rotatorConnectJob: Job? = null

    private val _devices = MutableStateFlow<List<AccessoryDeviceEntry>>(emptyList())
    val devices: StateFlow<List<AccessoryDeviceEntry>> = _devices.asStateFlow()

    private val _scanError = MutableStateFlow<String?>(null)
    val scanError: StateFlow<String?> = _scanError.asStateFlow()

    @Suppress("DEPRECATION")
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> scan()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE) ?: return
                    val detached = _devices.value.firstOrNull {
                        it.usbDevice.deviceId == device.deviceId
                    } ?: return
                    when (detached.type) {
                        AccessoryType.FILTER_WHEEL -> filterWheelController.close()
                        AccessoryType.FOCUSER,
                        AccessoryType.EFUCOSER_FOCUSER -> {
                            if (connectingFocuserDeviceId == device.deviceId) {
                                focuserConnectJob?.cancel()
                                focuserConnectGeneration++
                                focuserConnectJob = null
                                connectingFocuserDeviceId = null
                                efucoserFocuser.close()
                                geminiEafFocuser.close()
                                oasisFocuser.close()
                            }
                            if (_activeFocuserDeviceId.value == device.deviceId) {
                                focuserController.close()
                                _activeFocuserDeviceId.value = null
                            }
                        }
                        AccessoryType.SERIAL_DEVICE -> {
                            if (_activeFocuserDeviceId.value == device.deviceId) disconnectFocuser()
                            if (_activeCoverDeviceId.value == device.deviceId) disconnectCover()
                            if (_activeRotatorDeviceId.value == device.deviceId) disconnectRotator()
                        }
                    }
                    _devices.value = _devices.value.filterNot {
                        it.usbDevice.deviceId == device.deviceId
                    }
                }
                ACTION_FILTER_WHEEL_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null &&
                        pendingFilterWheelDeviceId == device.deviceId) {
                        connectFilterWheelGranted(device)
                    } else {
                        _scanError.value = "Filter wheel USB permission denied"
                    }
                    pendingFilterWheelDeviceId = null
                }
                ACTION_FOCUSER_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null &&
                        pendingFocuserDeviceId == device.deviceId) {
                        connectFocuserGranted(device)
                    } else {
                        _scanError.value = "Focuser USB permission denied"
                    }
                    pendingFocuserDeviceId = null
                }
                ACTION_EFUCOSER_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null &&
                        pendingEfucoserDeviceId == device.deviceId) {
                        connectSerialFocuserByRole(device)
                    } else {
                        _scanError.value = "EFucoser USB permission denied"
                    }
                    pendingEfucoserDeviceId = null
                }
                ACTION_COVER_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null && pendingCoverDeviceId == device.deviceId) {
                        connectSerialCoverByRole(device)
                    } else {
                        _scanError.value = "Cover / flat panel USB permission denied"
                    }
                    pendingCoverDeviceId = null
                }
                ACTION_ROTATOR_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && device != null && pendingRotatorDeviceId == device.deviceId) {
                        connectRotatorGranted(device)
                    } else {
                        _scanError.value = "electric CAA USB permission denied"
                    }
                    pendingRotatorDeviceId = null
                }
                ACTION_SERIAL_AUTO_PERMISSION -> {
                    val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val pendingId = pendingSerialAutoDeviceId
                    pendingSerialAutoDeviceId = null
                    if (granted && device != null && pendingId == device.deviceId) {
                        runSerialAutoConnect(device)
                    } else {
                        setDeviceProbing(pendingId, false)
                        _scanError.value = "USB serial permission denied"
                    }
                }
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_FILTER_WHEEL_PERMISSION)
            addAction(ACTION_FOCUSER_PERMISSION)
            addAction(ACTION_EFUCOSER_PERMISSION)
            addAction(ACTION_COVER_PERMISSION)
            addAction(ACTION_ROTATOR_PERMISSION)
            addAction(ACTION_SERIAL_AUTO_PERMISSION)
        }
        ContextCompat.registerReceiver(
            appContext,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
        registered = true
        scan()
    }

    fun setExcludedUsbDeviceIds(deviceIds: Set<Int>) {
        if (excludedUsbDeviceIds == deviceIds) return
        excludedUsbDeviceIds = deviceIds
        scan()
    }

    fun scan() {
        val discovered = mutableListOf<AccessoryDeviceEntry>()
        _scanError.value = null
        val excluded = excludedUsbDeviceIds
        try {
            usbManager.deviceList.values.forEach { device ->
                if (device.deviceId in excluded) return@forEach
                if (device.vendorId == OasisUsbIds.vendorId) {
                    val entry = when {
                        OasisUsbIds.isFilterWheel(device.productId) ->
                            AccessoryDeviceEntry("Oasis Filter Wheel", AccessoryType.FILTER_WHEEL, device)
                        OasisUsbIds.isFocuser(device.productId) ->
                            AccessoryDeviceEntry("Oasis Focuser", AccessoryType.FOCUSER, device)
                        else -> null
                    }
                    if (entry != null) discovered += entry
                    return@forEach
                }
                if (device.vendorId == TOUPCAM_VENDOR_ID) {
                    val type = when {
                        runCatching {
                            ToupcamJni.isFilterWheel(device.vendorId, device.productId)
                        }.getOrDefault(false) -> AccessoryType.FILTER_WHEEL
                        runCatching {
                            ToupcamJni.isAutoFocuser(device.vendorId, device.productId)
                        }.getOrDefault(false) -> AccessoryType.FOCUSER
                        else -> null
                    } ?: return@forEach
                    val fallbackName = when (type) {
                        AccessoryType.FILTER_WHEEL -> "ToupTek Filter Wheel"
                        AccessoryType.FOCUSER -> "ToupTek Focuser"
                        AccessoryType.EFUCOSER_FOCUSER -> "EFucoser"
                        AccessoryType.SERIAL_DEVICE -> "USB Serial Device"
                    }
                    val name = runCatching {
                        ToupcamJni.getModelName(device.vendorId, device.productId)
                    }.getOrNull() ?: fallbackName
                    discovered += AccessoryDeviceEntry(name, type, device)
                }
            }
            UsbSerialProber.getDefaultProber().findAllDrivers(usbManager).forEach { driver ->
                val device = driver.device
                if (device.deviceId in excluded ||
                    device.vendorId == TOUPCAM_VENDOR_ID ||
                    device.vendorId == OasisUsbIds.vendorId ||
                    discovered.any { it.usbDevice.deviceId == device.deviceId }) {
                    return@forEach
                }
                val product = device.productName?.takeIf { it.isNotBlank() }
                    ?: "USB Serial ${device.vendorId.toString(16)}:${device.productId.toString(16)}"
                val knownRoles = knownConnectedRoles(device.deviceId)
                discovered += AccessoryDeviceEntry(
                    name = product,
                    type = AccessoryType.SERIAL_DEVICE,
                    usbDevice = device,
                    serialRoles = knownRoles,
                    probing = knownRoles == null && usbManager.hasPermission(device)
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Accessory scan failed", e)
            _scanError.value = e.message ?: "Accessory scan failed"
        }
        _devices.value = discovered
        startProtocolProbe(discovered)
    }

    fun connect(device: AccessoryDeviceEntry) {
        if (device.type == AccessoryType.FOCUSER ||
            device.type == AccessoryType.EFUCOSER_FOCUSER) {
            focuserConnectJob?.cancel()
            focuserConnectGeneration++
            focuserConnectJob = null
            connectingFocuserDeviceId = null
        }
        when (device.type) {
            AccessoryType.FILTER_WHEEL -> {
                pendingFilterWheelDeviceId = device.usbDevice.deviceId
                requestPermission(
                    device.usbDevice,
                    ACTION_FILTER_WHEEL_PERMISSION,
                    20
                ) {
                    pendingFilterWheelDeviceId = null
                    connectFilterWheelGranted(device.usbDevice)
                }
            }
            AccessoryType.FOCUSER -> {
                pendingFocuserDeviceId = device.usbDevice.deviceId
                requestPermission(
                    device.usbDevice,
                    ACTION_FOCUSER_PERMISSION,
                    21
                ) {
                    pendingFocuserDeviceId = null
                    connectFocuserGranted(device.usbDevice)
                }
            }
            AccessoryType.EFUCOSER_FOCUSER -> {
                pendingEfucoserDeviceId = device.usbDevice.deviceId
                requestPermission(
                    device.usbDevice,
                    ACTION_EFUCOSER_PERMISSION,
                    22
                ) {
                    pendingEfucoserDeviceId = null
                    connectEfucoser(device.usbDevice)
                }
            }
            AccessoryType.SERIAL_DEVICE -> connectEfucoser(device)
        }
    }

    fun connectEfucoser(device: AccessoryDeviceEntry) {
        if (!claimable(device.usbDevice.deviceId, "电调焦")) return
        pendingEfucoserDeviceId = device.usbDevice.deviceId
        requestPermission(device.usbDevice, ACTION_EFUCOSER_PERMISSION, 22) {
            pendingEfucoserDeviceId = null
            connectSerialFocuserByRole(device.usbDevice, device.serialRoles)
        }
    }

    fun connectCover(device: AccessoryDeviceEntry) {
        if (!claimable(device.usbDevice.deviceId, "镜头盖")) return
        pendingCoverDeviceId = device.usbDevice.deviceId
        requestPermission(device.usbDevice, ACTION_COVER_PERMISSION, 23) {
            pendingCoverDeviceId = null
            connectSerialCoverByRole(device.usbDevice, device.serialRoles)
        }
    }

    fun connectRotator(device: AccessoryDeviceEntry) {
        if (!claimable(device.usbDevice.deviceId, "CAA")) return
        pendingRotatorDeviceId = device.usbDevice.deviceId
        requestPermission(device.usbDevice, ACTION_ROTATOR_PERMISSION, 24) {
            pendingRotatorDeviceId = null
            connectRotatorGranted(device.usbDevice)
        }
    }

    /**
     * Request USB permission if needed, probe CAA / focuser / cover identity,
     * then connect the uniquely matched role.
     */
    fun connectSerialAuto(device: AccessoryDeviceEntry) {
        if (device.type != AccessoryType.SERIAL_DEVICE &&
            device.type != AccessoryType.EFUCOSER_FOCUSER
        ) {
            connect(device)
            return
        }
        if (!claimable(device.usbDevice.deviceId, "串口器材")) return
        setDeviceProbing(device.usbDevice.deviceId, true)
        pendingSerialAutoDeviceId = device.usbDevice.deviceId
        requestPermission(device.usbDevice, ACTION_SERIAL_AUTO_PERMISSION, 25) {
            pendingSerialAutoDeviceId = null
            runSerialAutoConnect(device.usbDevice)
        }
    }

    private fun connectFilterWheelGranted(device: UsbDevice) {
        if (device.vendorId == OasisUsbIds.vendorId && OasisUsbIds.isFilterWheel(device.productId)) {
            if (oasisFilterWheel.open(appContext, device)) {
                filterWheelController.useOasis()
            } else {
                _scanError.value = "Failed to connect Oasis filter wheel"
            }
            return
        }
        if (toupTekFilterWheel.open(appContext, device)) {
            filterWheelController.useToupTek()
        } else {
            _scanError.value = "Failed to connect ToupTek filter wheel"
        }
    }

    private fun connectFocuserGranted(device: UsbDevice) {
        if (device.vendorId == OasisUsbIds.vendorId && OasisUsbIds.isFocuser(device.productId)) {
            connectOasisFocuser(device)
            return
        }
        val connected = toupTekFocuser.open(appContext, device).also { opened ->
            if (opened) focuserController.useToupTek()
        }
        if (connected) {
            _activeFocuserDeviceId.value = device.deviceId
        } else {
            _scanError.value = "Failed to connect focuser"
        }
    }

    private fun connectOasisFocuser(device: UsbDevice) {
        focuserConnectJob?.cancel()
        val generation = ++focuserConnectGeneration
        connectingFocuserDeviceId = device.deviceId
        focuserConnectJob = scope.launch {
            try {
                val connected = try {
                    oasisFocuser.open(appContext, device)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    FileLogger.e(TAG, "Oasis focuser connection crashed", error)
                    false
                }
                if (!isActive || focuserConnectGeneration != generation) {
                    if (connected) oasisFocuser.close()
                    return@launch
                }
                if (connected) {
                    focuserController.useOasis()
                    _activeFocuserDeviceId.value = device.deviceId
                } else {
                    _scanError.value = "Failed to connect Oasis focuser"
                }
            } finally {
                if (focuserConnectGeneration == generation) {
                    connectingFocuserDeviceId = null
                    focuserConnectJob = null
                }
            }
        }
    }

    fun disconnectFilterWheel() = filterWheelController.close()

    fun disconnectFocuser() {
        focuserConnectJob?.cancel()
        focuserConnectGeneration++
        focuserConnectJob = null
        connectingFocuserDeviceId = null
        focuserController.close()
        efucoserFocuser.close()
        geminiEafFocuser.close()
        oasisFocuser.close()
        _activeFocuserDeviceId.value = null
    }

    fun disconnectCover() {
        coverConnectJob?.cancel()
        coverConnectJob = null
        coverController.close()
        dlcCover.close()
        geminiFlat.close()
        _activeCoverDeviceId.value = null
    }

    fun disconnectRotator() {
        rotatorConnectJob?.cancel()
        rotatorConnectJob = null
        rotatorController.close()
        _activeRotatorDeviceId.value = null
    }

    fun unregister() {
        if (registered) {
            runCatching { appContext.unregisterReceiver(usbReceiver) }
            registered = false
        }
        probeJob?.cancel()
        probeGeneration++
        serialAutoJob?.cancel()
        serialAutoJob = null
        focuserConnectJob?.cancel()
        focuserConnectGeneration++
        focuserConnectJob = null
        connectingFocuserDeviceId = null
        filterWheelController.destroy()
        focuserController.destroy()
        coverController.destroy()
        rotatorController.destroy()
        scope.cancel()
        _devices.value = emptyList()
    }

    private fun knownConnectedRoles(deviceId: Int): Set<SerialAccessoryRole>? {
        val roles = linkedSetOf<SerialAccessoryRole>()
        if (geminiEafFocuser.connectedDeviceId == deviceId) {
            roles += SerialAccessoryRole.GEMINI_EAF
        } else if (_activeFocuserDeviceId.value == deviceId ||
            efucoserFocuser.connectedDeviceId == deviceId) {
            roles += SerialAccessoryRole.FOCUSER
        }
        if (geminiFlat.connectedDeviceId == deviceId) {
            roles += SerialAccessoryRole.GEMINI_FLAT
        } else if (_activeCoverDeviceId.value == deviceId ||
            dlcCover.connectedDeviceId == deviceId) {
            roles += SerialAccessoryRole.COVER
        }
        if (_activeRotatorDeviceId.value == deviceId ||
            rotatorController.connectedDeviceId == deviceId) {
            roles += if (rotatorController.activeKind == RotatorAdapterKind.WANDERER) {
                SerialAccessoryRole.WANDERER_ROTATOR
            } else {
                SerialAccessoryRole.ROTATOR
            }
        }
        return roles.takeIf { it.isNotEmpty() }
    }

    private fun startProtocolProbe(discovered: List<AccessoryDeviceEntry>) {
        val targets = discovered.filter {
            it.type == AccessoryType.SERIAL_DEVICE &&
                it.probing &&
                it.serialRoles == null &&
                usbManager.hasPermission(it.usbDevice) &&
                it.usbDevice.deviceId !in excludedUsbDeviceIds &&
                !isOccupied(it.usbDevice.deviceId)
        }
        probeJob?.cancel()
        val generation = ++probeGeneration
        if (targets.isEmpty()) return
        probeJob = scope.launch {
            val autoConnect = mutableListOf<Pair<UsbDevice, SerialAccessoryRole>>()
            probeMutex.withLock {
                for (entry in targets) {
                    if (generation != probeGeneration) return@withLock
                    if (entry.usbDevice.deviceId in excludedUsbDeviceIds) continue
                    if (isOccupied(entry.usbDevice.deviceId)) continue
                    // Skip devices the user is already trying to connect.
                    if (pendingEfucoserDeviceId == entry.usbDevice.deviceId ||
                        pendingCoverDeviceId == entry.usbDevice.deviceId ||
                        pendingRotatorDeviceId == entry.usbDevice.deviceId ||
                        pendingSerialAutoDeviceId == entry.usbDevice.deviceId ||
                        connectingFocuserDeviceId == entry.usbDevice.deviceId
                    ) {
                        updateDeviceRoles(entry.usbDevice.deviceId, emptySet())
                        continue
                    }
                    val roles = try {
                        SerialAccessoryProbe.probe(appContext, entry.usbDevice)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (e: Throwable) {
                        Log.d(TAG, "Probe error: ${e.message}")
                        emptySet()
                    }
                    ensureActive()
                    if (generation != probeGeneration) return@withLock
                    updateDeviceRoles(entry.usbDevice.deviceId, roles)
                    if (roles.size == 1) {
                        autoConnect += entry.usbDevice to roles.first()
                    }
                }
            }
            // Drop self-reference so connect jobs' awaitProbeIdle() won't cancel us.
            probeJob = null
            for ((device, role) in autoConnect) {
                if (generation != probeGeneration) return@launch
                if (isOccupied(device.deviceId)) continue
                Log.i(TAG, "Auto-connecting probed role $role on ${device.deviceName}")
                connectProbedRole(device, role)
            }
        }
    }

    private fun runSerialAutoConnect(device: UsbDevice) {
        serialAutoJob?.cancel()
        serialAutoJob = scope.launch {
            try {
                setDeviceProbing(device.deviceId, true)
                awaitProbeIdle()
                if (device.deviceId in excludedUsbDeviceIds || isOccupied(device.deviceId)) {
                    setDeviceProbing(device.deviceId, false)
                    return@launch
                }
                val roles = try {
                    SerialAccessoryProbe.probe(appContext, device)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Throwable) {
                    Log.d(TAG, "Auto probe error: ${e.message}")
                    emptySet()
                }
                updateDeviceRoles(device.deviceId, roles)
                when {
                    roles.size == 1 -> connectProbedRole(device, roles.first())
                    roles.isEmpty() -> {
                        _scanError.value =
                            "未识别到电调 / Gemini 电调 / Gemini 平场 / 镜头盖 / CAA，请手动选择角色连接"
                    }
                }
            } finally {
                serialAutoJob = null
            }
        }
    }

    private fun connectProbedRole(device: UsbDevice, role: SerialAccessoryRole) {
        when (role) {
            SerialAccessoryRole.FOCUSER -> {
                if (!claimable(device.deviceId, "电调焦")) return
                connectEfucoser(device)
            }
            SerialAccessoryRole.GEMINI_EAF -> {
                if (!claimable(device.deviceId, "Gemini 电调")) return
                connectGeminiEaf(device)
            }
            SerialAccessoryRole.COVER -> {
                if (!claimable(device.deviceId, "镜头盖")) return
                connectDlcCover(device)
            }
            SerialAccessoryRole.GEMINI_FLAT -> {
                if (!claimable(device.deviceId, "Gemini 平场")) return
                connectGeminiFlat(device)
            }
            SerialAccessoryRole.ROTATOR -> {
                if (!claimable(device.deviceId, "CAA")) return
                connectRotatorGranted(device)
            }
            SerialAccessoryRole.WANDERER_ROTATOR -> {
                if (!claimable(device.deviceId, "Wanderer CAA")) return
                connectRotatorGranted(device)
            }
        }
    }

    private fun setDeviceProbing(deviceId: Int?, probing: Boolean) {
        if (deviceId == null) return
        _devices.value = _devices.value.map { entry ->
            if (entry.usbDevice.deviceId != deviceId) entry
            else entry.copy(probing = probing)
        }
    }

    private fun updateDeviceRoles(deviceId: Int, roles: Set<SerialAccessoryRole>) {
        _devices.value = _devices.value.map { entry ->
            if (entry.usbDevice.deviceId != deviceId) entry
            else entry.copy(serialRoles = roles, probing = false)
        }
    }

    private fun isOccupied(deviceId: Int): Boolean {
        return (_activeFocuserDeviceId.value == deviceId) ||
            (_activeCoverDeviceId.value == deviceId) ||
            (_activeRotatorDeviceId.value == deviceId) ||
            (connectingFocuserDeviceId == deviceId) ||
            (pendingEfucoserDeviceId == deviceId) ||
            (pendingCoverDeviceId == deviceId) ||
            (pendingRotatorDeviceId == deviceId) ||
            (pendingSerialAutoDeviceId == deviceId) ||
            (efucoserFocuser.connectedDeviceId == deviceId) ||
            (geminiEafFocuser.connectedDeviceId == deviceId) ||
            (dlcCover.connectedDeviceId == deviceId) ||
            (geminiFlat.connectedDeviceId == deviceId) ||
            (rotatorController.connectedDeviceId == deviceId)
    }

    /** Cancel scan-time probing and wait until the USB serial port is released. */
    private suspend fun awaitProbeIdle() {
        probeGeneration++
        probeJob?.cancel()
        probeJob = null
        probeMutex.withLock { }
        delay(250)
    }

    private fun connectSerialFocuserByRole(
        device: UsbDevice,
        roles: Set<SerialAccessoryRole>? = _devices.value
            .firstOrNull { it.usbDevice.deviceId == device.deviceId }
            ?.serialRoles
    ) {
        when {
            roles == setOf(SerialAccessoryRole.GEMINI_EAF) -> connectGeminiEaf(device)
            roles == setOf(SerialAccessoryRole.FOCUSER) -> connectEfucoser(device)
            else -> connectSerialFocuserAuto(device)
        }
    }

    private fun connectEfucoser(device: UsbDevice) {
        focuserConnectJob?.cancel()
        val generation = ++focuserConnectGeneration
        connectingFocuserDeviceId = device.deviceId
        focuserConnectJob = scope.launch {
            try {
                awaitProbeIdle()
                geminiEafFocuser.close()
                if (efucoserFocuser.open(appContext, device)) {
                    focuserController.useEfucoser()
                    _activeFocuserDeviceId.value = device.deviceId
                } else {
                    _scanError.value = efucoserFocuser.lastError.value
                        ?: "EFucoser identification failed"
                }
            } finally {
                if (focuserConnectGeneration == generation) {
                    connectingFocuserDeviceId = null
                    focuserConnectJob = null
                }
            }
        }
    }

    private fun connectGeminiEaf(device: UsbDevice) {
        focuserConnectJob?.cancel()
        val generation = ++focuserConnectGeneration
        connectingFocuserDeviceId = device.deviceId
        focuserConnectJob = scope.launch {
            try {
                awaitProbeIdle()
                efucoserFocuser.close()
                if (geminiEafFocuser.open(appContext, device)) {
                    focuserController.useGeminiEaf()
                    _activeFocuserDeviceId.value = device.deviceId
                } else {
                    _scanError.value = geminiEafFocuser.lastError.value
                        ?: "Gemini EAF identification failed"
                }
            } finally {
                if (focuserConnectGeneration == generation) {
                    connectingFocuserDeviceId = null
                    focuserConnectJob = null
                }
            }
        }
    }

    private fun connectSerialFocuserAuto(device: UsbDevice) {
        focuserConnectJob?.cancel()
        val generation = ++focuserConnectGeneration
        connectingFocuserDeviceId = device.deviceId
        focuserConnectJob = scope.launch {
            try {
                awaitProbeIdle()
                efucoserFocuser.close()
                geminiEafFocuser.close()
                when {
                    // EFucoser first: its empty-command banner is unambiguous,
                    // whereas trying Gemini first makes its handshake failure the
                    // reported error even when the device is an EFucoser.
                    efucoserFocuser.open(appContext, device) -> {
                        focuserController.useEfucoser()
                        _activeFocuserDeviceId.value = device.deviceId
                    }
                    geminiEafFocuser.open(appContext, device) -> {
                        focuserController.useGeminiEaf()
                        _activeFocuserDeviceId.value = device.deviceId
                    }
                    else -> {
                        _scanError.value = listOfNotNull(
                            efucoserFocuser.lastError.value,
                            geminiEafFocuser.lastError.value
                        ).joinToString(" / ")
                            .ifEmpty { "Serial focuser identification failed" }
                    }
                }
            } finally {
                if (focuserConnectGeneration == generation) {
                    connectingFocuserDeviceId = null
                    focuserConnectJob = null
                }
            }
        }
    }

    private fun connectSerialCoverByRole(
        device: UsbDevice,
        roles: Set<SerialAccessoryRole>? = _devices.value
            .firstOrNull { it.usbDevice.deviceId == device.deviceId }
            ?.serialRoles
    ) {
        when {
            roles == setOf(SerialAccessoryRole.GEMINI_FLAT) -> connectGeminiFlat(device)
            roles == setOf(SerialAccessoryRole.COVER) -> connectDlcCover(device)
            else -> connectSerialCoverAuto(device)
        }
    }

    private fun connectDlcCover(device: UsbDevice) {
        coverConnectJob?.cancel()
        coverConnectJob = scope.launch {
            try {
                awaitProbeIdle()
                geminiFlat.close()
                if (dlcCover.open(appContext, device)) {
                    coverController.useDlc()
                    _activeCoverDeviceId.value = device.deviceId
                } else {
                    _scanError.value = dlcCover.lastError.value
                        ?: "DLCoverCalibrator identification failed"
                }
            } finally {
                coverConnectJob = null
            }
        }
    }

    private fun connectGeminiFlat(device: UsbDevice) {
        coverConnectJob?.cancel()
        coverConnectJob = scope.launch {
            try {
                awaitProbeIdle()
                dlcCover.close()
                if (geminiFlat.open(appContext, device)) {
                    coverController.useGemini()
                    _activeCoverDeviceId.value = device.deviceId
                } else {
                    _scanError.value = geminiFlat.lastError.value
                        ?: "Gemini flat panel identification failed"
                }
            } finally {
                coverConnectJob = null
            }
        }
    }

    /** Try Gemini flat panel first, then fall back to DLCoverCalibrator. */
    private fun connectSerialCoverAuto(device: UsbDevice) {
        coverConnectJob?.cancel()
        coverConnectJob = scope.launch {
            try {
                awaitProbeIdle()
                dlcCover.close()
                geminiFlat.close()
                when {
                    geminiFlat.open(appContext, device) -> {
                        coverController.useGemini()
                        _activeCoverDeviceId.value = device.deviceId
                    }
                    dlcCover.open(appContext, device) -> {
                        coverController.useDlc()
                        _activeCoverDeviceId.value = device.deviceId
                    }
                    else -> {
                        _scanError.value = geminiFlat.lastError.value
                            ?: dlcCover.lastError.value
                            ?: "Serial cover / flat panel identification failed"
                    }
                }
            } finally {
                coverConnectJob = null
            }
        }
    }

    private fun connectRotatorGranted(device: UsbDevice) {
        rotatorConnectJob?.cancel()
        rotatorConnectJob = scope.launch {
            try {
                awaitProbeIdle()
                val roles = _devices.value
                    .firstOrNull { it.usbDevice.deviceId == device.deviceId }
                    ?.serialRoles
                val connected = when {
                    roles == setOf(SerialAccessoryRole.WANDERER_ROTATOR) -> {
                        connectWandererRotator(device)
                    }
                    device.vendorId == 0x1a86 -> {
                        connectWandererRotator(device) || connectEcaaRotator(device)
                    }
                    else -> connectEcaaRotator(device)
                }
                if (!connected) {
                    _scanError.value = wandererRotator.lastError.value
                        ?: ecaaRotator.lastError.value
                        ?: "CAA identification failed"
                }
            } finally {
                rotatorConnectJob = null
            }
        }
    }

    private suspend fun connectWandererRotator(device: UsbDevice): Boolean {
        ecaaRotator.close()
        if (!wandererRotator.open(appContext, device)) return false
        rotatorController.useWanderer()
        _activeRotatorDeviceId.value = device.deviceId
        return true
    }

    private suspend fun connectEcaaRotator(device: UsbDevice): Boolean {
        wandererRotator.close()
        if (!ecaaRotator.open(appContext, device)) return false
        rotatorController.useEcaa()
        _activeRotatorDeviceId.value = device.deviceId
        return true
    }

    private fun claimable(deviceId: Int, role: String): Boolean {
        if (deviceId in excludedUsbDeviceIds) {
            _scanError.value = "该 USB 串口已作为赤道仪连接，请先断开赤道仪"
            return false
        }
        val occupied = isOccupied(deviceId)
        if (occupied) {
            _scanError.value = "该 USB 串口已被其他器材占用，断开后再连接$role"
        }
        return !occupied
    }

    private fun requestPermission(
        device: UsbDevice,
        action: String,
        requestCode: Int,
        onGranted: () -> Unit
    ) {
        if (usbManager.hasPermission(device)) {
            onGranted()
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = PendingIntent.getBroadcast(
            appContext,
            requestCode,
            Intent(action).setPackage(appContext.packageName),
            flags
        )
        usbManager.requestPermission(device, intent)
    }
}
