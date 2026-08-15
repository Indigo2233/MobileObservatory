package com.indigo.mobileobservatory.camera

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
import com.indigo.mobileobservatory.camera.dslr.DslrCamera
import com.indigo.mobileobservatory.camera.dslr.DslrUsb
import com.indigo.mobileobservatory.camera.playerone.PlayerOneCamera
import com.indigo.mobileobservatory.camera.playerone.PlayerOneSdkHost
import com.indigo.mobileobservatory.camera.qhyccd.QhyCamera
import com.indigo.mobileobservatory.camera.qhyccd.QhyccdJni
import com.indigo.mobileobservatory.camera.toupcam.EAFController
import com.indigo.mobileobservatory.camera.toupcam.FilterWheelController
import com.indigo.mobileobservatory.camera.toupcam.ToupcamCamera
import com.indigo.mobileobservatory.camera.toupcam.ToupcamJni
import com.indigo.mobileobservatory.camera.zwo.ZwoAsiCamera
import com.zwo.ZwoCamera
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class CameraBrand { TOUPCAM, QHY, ZWO, PLAYERONE, NIKON, CANON, SONY }

enum class AccessoryType { FILTER_WHEEL, FOCUSER, EFUCOSER_FOCUSER, SERIAL_DEVICE }

data class AccessoryDeviceEntry(
    val name: String,
    val type: AccessoryType,
    val usbDevice: UsbDevice,
    /** null = not probed yet / show all roles; empty = probed, no match. */
    val serialRoles: Set<com.indigo.mobileobservatory.accessories.SerialAccessoryRole>? = null,
    val probing: Boolean = false
)

data class DeviceEntry(
    val index: Int,
    val name: String,
    val serialNumber: String,
    val brand: CameraBrand = CameraBrand.TOUPCAM,
    val usbDevice: UsbDevice? = null
)

class DahengCameraManager(
    private val context: Context,
    private val sessionName: String = "main",
    private val enableAccessories: Boolean = false
) {

    companion object {
        private const val TAG = "CameraMgr"
        private const val TOUPCAM_VENDOR_ID = 1351  // 0x0547
        private const val QHY_VENDOR_ID = 0x1618    // 5656 decimal
        private const val ZWO_VENDOR_ID = 0x03C3        // 963 decimal
        private const val PLAYERONE_VENDOR_ID = 0xA0A0  // 41120 decimal
    }

    private val actionUsbPermission = "com.indigo.mobileobservatory.USB_PERMISSION.$sessionName"
    private val actionUsbPermissionFw = "com.indigo.mobileobservatory.USB_PERMISSION_FW.$sessionName"
    private val actionUsbPermissionEaf = "com.indigo.mobileobservatory.USB_PERMISSION_EAF.$sessionName"
    private val actionUsbPermissionQhy = "com.indigo.mobileobservatory.USB_PERMISSION_QHY.$sessionName"
    private val actionUsbPermissionQhyFw = "com.indigo.mobileobservatory.USB_PERMISSION_QHY_FW.$sessionName"
    private val actionUsbPermissionZwo = "com.indigo.mobileobservatory.USB_PERMISSION_ZWO.$sessionName"
    private val actionUsbPermissionDslr = "com.indigo.mobileobservatory.USB_PERMISSION_DSLR.$sessionName"

    private val _devices = MutableStateFlow<List<DeviceEntry>>(emptyList())
    val devices: StateFlow<List<DeviceEntry>> = _devices.asStateFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _accessoryDevices = MutableStateFlow<List<AccessoryDeviceEntry>>(emptyList())
    val accessoryDevices: StateFlow<List<AccessoryDeviceEntry>> = _accessoryDevices.asStateFlow()

    var activeCamera: Camera? = null
        private set

    val filterWheelController = FilterWheelController()
    val eafController = EAFController()
    private var pendingFilterWheelDevice: UsbDevice? = null
    private var pendingEafDevice: UsbDevice? = null

    private var pendingToupcamDevice: UsbDevice? = null

    @Suppress("DEPRECATION")
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val usbDevice = intent.getParcelableExtra<UsbDevice>("device") ?: return
                    Log.i(TAG, "USB attached: VID=0x${usbDevice.vendorId.toString(16)} PID=0x${usbDevice.productId.toString(16)}")
                    if (usbDevice.vendorId == TOUPCAM_VENDOR_ID) {
                        val isAccessory = runCatching {
                            ToupcamJni.isFilterWheel(usbDevice.vendorId, usbDevice.productId) ||
                                ToupcamJni.isAutoFocuser(usbDevice.vendorId, usbDevice.productId)
                        }.getOrDefault(false)
                        if (isAccessory) return
                    }
                    if (usbDevice.vendorId == TOUPCAM_VENDOR_ID || usbDevice.vendorId == QHY_VENDOR_ID ||
                        usbDevice.vendorId == ZWO_VENDOR_ID || usbDevice.vendorId == PLAYERONE_VENDOR_ID ||
                        DslrUsb.brandForVendor(usbDevice.vendorId) == CameraBrand.NIKON)
                        enumerateDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val usbDevice = intent.getParcelableExtra<UsbDevice>("device") ?: return
                    if (usbDevice.vendorId == TOUPCAM_VENDOR_ID || usbDevice.vendorId == QHY_VENDOR_ID ||
                        usbDevice.vendorId == ZWO_VENDOR_ID || usbDevice.vendorId == PLAYERONE_VENDOR_ID ||
                        DslrUsb.brandForVendor(usbDevice.vendorId) == CameraBrand.NIKON) {
                        Log.i(TAG, "USB detached: VID=0x${usbDevice.vendorId.toString(16)}")
                        if (usbDevice.vendorId == TOUPCAM_VENDOR_ID &&
                            ToupcamJni.isFilterWheel(usbDevice.vendorId, usbDevice.productId)) {
                            filterWheelController.close()
                            _accessoryDevices.value = _accessoryDevices.value.filterNot {
                                it.usbDevice.deviceId == usbDevice.deviceId
                            }
                        } else if (usbDevice.vendorId == TOUPCAM_VENDOR_ID &&
                            ToupcamJni.isAutoFocuser(usbDevice.vendorId, usbDevice.productId)) {
                            eafController.close()
                            _accessoryDevices.value = _accessoryDevices.value.filterNot {
                                it.usbDevice.deviceId == usbDevice.deviceId
                            }
                        } else if (usbDevice.vendorId == QHY_VENDOR_ID) {
                            // Device is physically gone: do NOT go through closeCamera(),
                            // whose SDK teardown issues USB transfers to a dead fd and
                            // dirties SDK state ("connected but no frames" on replug).
                            val qhy = activeCamera as? QhyCamera
                            if (qhy != null) {
                                qhy.markDisconnected()
                                activeCamera = null
                            } else {
                                closeCamera()
                            }
                            closeQhyUsbConnection()
                            _connectionState.value = ConnectionState.Disconnected
                        } else if (usbDevice.vendorId == PLAYERONE_VENDOR_ID) {
                            // SDK already coordinates native cleanup on its own detach receiver.
                            // Only clear UI / claim state — do not issue further USB I/O.
                            val po = activeCamera as? PlayerOneCamera
                            if (po != null) {
                                po.markDisconnected()
                                activeCamera = null
                            } else {
                                activeCamera = null
                            }
                            _connectionState.value = ConnectionState.Disconnected
                        } else if (DslrUsb.brandForVendor(usbDevice.vendorId) == CameraBrand.NIKON) {
                            val dslr = activeCamera as? DslrCamera
                            if (dslr != null) {
                                dslr.markDisconnected()
                                activeCamera = null
                            } else {
                                activeCamera = null
                            }
                            _connectionState.value = ConnectionState.Disconnected
                        } else {
                            closeCamera()
                            _connectionState.value = ConnectionState.Disconnected
                        }
                        if (usbDevice.vendorId != TOUPCAM_VENDOR_ID ||
                            (!ToupcamJni.isFilterWheel(usbDevice.vendorId, usbDevice.productId) &&
                                !ToupcamJni.isAutoFocuser(usbDevice.vendorId, usbDevice.productId))) {
                            _devices.value = emptyList()
                        }
                    }
                }
                actionUsbPermission -> {
                    val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && usbDevice != null) {
                        Log.i(TAG, "USB permission granted for ${usbDevice.deviceName}")
                        openToupcamDevice(usbDevice)
                    } else {
                        Log.w(TAG, "USB permission denied")
                        _connectionState.value = ConnectionState.Error("USB permission denied")
                    }
                }
                actionUsbPermissionFw -> {
                    val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && usbDevice != null) {
                        Log.i(TAG, "USB permission granted for filter wheel ${usbDevice.deviceName}")
                        filterWheelController.open(ctx, usbDevice)
                    } else {
                        Log.w(TAG, "Filter wheel USB permission denied")
                    }
                }
                actionUsbPermissionEaf -> {
                    val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && usbDevice != null) {
                        Log.i(TAG, "USB permission granted for EAF ${usbDevice.deviceName}")
                        eafController.open(ctx, usbDevice)
                    } else {
                        Log.w(TAG, "EAF USB permission denied")
                    }
                }
                actionUsbPermissionQhy -> {
                    val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && usbDevice != null) {
                        Log.i(TAG, "USB permission granted for QHY ${usbDevice.deviceName}")
                        val pid = usbDevice.productId
                        val fwLoaded = QhyccdJni.isFirmwareLoaded(pid)
                        if (fwLoaded) {
                            openQhyDevice(usbDevice)
                        } else {
                            // Firmware not loaded, upload it first
                            Log.i(TAG, "QHY device in bootloader mode, uploading firmware")
                            uploadQhyFirmware(usbDevice)
                        }
                    } else {
                        Log.w(TAG, "QHY USB permission denied")
                        _connectionState.value = ConnectionState.Error("USB permission denied")
                    }
                }
                actionUsbPermissionQhyFw -> {
                    val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && usbDevice != null) {
                        Log.i(TAG, "USB permission granted for QHY firmware upload ${usbDevice.deviceName}")
                        uploadQhyFirmware(usbDevice)
                    } else {
                        Log.w(TAG, "QHY firmware upload USB permission denied")
                        _connectionState.value = ConnectionState.Error("USB permission denied for firmware upload")
                    }
                }
                actionUsbPermissionZwo -> {
                    val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && usbDevice != null) {
                        Log.i(TAG, "USB permission granted for ZWO ${usbDevice.deviceName}")
                        openZwoDevice(usbDevice)
                    } else {
                        Log.w(TAG, "ZWO USB permission denied")
                        _connectionState.value = ConnectionState.Error("USB permission denied")
                    }
                }
                actionUsbPermissionDslr -> {
                    val usbDevice = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted && usbDevice != null) {
                        Log.i(TAG, "USB permission granted for DSLR ${usbDevice.deviceName}")
                        openDslrDevice(usbDevice)
                    } else {
                        Log.w(TAG, "DSLR USB permission denied")
                        _connectionState.value = ConnectionState.Error("USB permission denied")
                    }
                }
            }
        }
    }

    fun register() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(actionUsbPermission)
            addAction(actionUsbPermissionFw)
            addAction(actionUsbPermissionEaf)
            addAction(actionUsbPermissionQhy)
            addAction(actionUsbPermissionQhyFw)
            addAction(actionUsbPermissionZwo)
            addAction(actionUsbPermissionDslr)
        }
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    fun unregister() {
        closeCamera()
        filterWheelController.destroy()
        eafController.destroy()
        try { context.unregisterReceiver(usbReceiver) } catch (_: Exception) {}
    }

    fun enumerateDevices() {
        val allDevices = mutableListOf<DeviceEntry>()

        // Enumerate ToupTek devices via USB (cameras and filter wheels)
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            Log.i(TAG, "USB device list: ${usbManager.deviceList.size} device(s)")
            for ((path, usbDev) in usbManager.deviceList) {
                val vid = usbDev.vendorId
                val pid = usbDev.productId
                Log.i(TAG, "USB device: path=$path VID=0x${vid.toString(16)} PID=0x${pid.toString(16)} name=${usbDev.deviceName}")

                if (vid == TOUPCAM_VENDOR_ID) {
                    val modelName = try { ToupcamJni.getModelName(vid, pid) } catch (e: Exception) {
                        Log.w(TAG, "getModelName failed for VID=0x${vid.toString(16)} PID=0x${pid.toString(16)}: ${e.message}")
                        null
                    }
                    val isfw = try { ToupcamJni.isFilterWheel(vid, pid) } catch (_: Exception) { false }
                    val iseaf = try { ToupcamJni.isAutoFocuser(vid, pid) } catch (_: Exception) { false }
                    val flag = try { ToupcamJni.getModelFlag(vid, pid) } catch (_: Exception) { 0L }

                    Log.i(TAG, "ToupTek device: model=$modelName isfw=$isfw iseaf=$iseaf flag=0x${flag.toString(16)}")

                    if (isfw) {
                        Log.i(TAG, "Found ToupTek filter wheel: VID=0x${vid.toString(16)} PID=0x${pid.toString(16)}")
                        if (enableAccessories && !filterWheelController.isConnected.value) {
                            pendingFilterWheelDevice = usbDev
                            connectFilterWheel(usbDev)
                        }
                    } else if (iseaf) {
                        Log.i(TAG, "Found ToupTek EAF: VID=0x${vid.toString(16)} PID=0x${pid.toString(16)}")
                        if (enableAccessories && !eafController.isConnected.value) {
                            pendingEafDevice = usbDev
                            connectEAF(usbDev)
                        }
                    } else if (modelName != null) {
                        allDevices.add(DeviceEntry(
                            index = allDevices.size,
                            name = modelName,
                            serialNumber = "TC-${vid.toString(16)}-${pid.toString(16)}-${usbDev.deviceId}",
                            brand = CameraBrand.TOUPCAM,
                            usbDevice = usbDev
                        ))
                        Log.i(TAG, "Found ToupTek camera: $modelName (flag=0x${flag.toString(16)})")
                    } else {
                        Log.w(TAG, "ToupTek VID but model unknown: PID=0x${pid.toString(16)}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ToupTek enumeration failed: ${e.message}")
        }

        // Enumerate QHY cameras via USB
        Log.i(TAG, "Starting QHY enumeration, nativeAvailable=${QhyccdJni.nativeAvailable}")
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            for ((_, usbDev) in usbManager.deviceList) {
                Log.d(TAG, "QHY check: VID=0x${usbDev.vendorId.toString(16)} vs QHY_VID=0x${QHY_VENDOR_ID.toString(16)}")
                if (usbDev.vendorId == QHY_VENDOR_ID) {
                    val pid = usbDev.productId
                    val fwLoaded = QhyccdJni.isFirmwareLoaded(pid)
                    val fwStatus = if (fwLoaded) "ready" else "bootloader"

                    // Always add to device list, regardless of firmware state
                    // The connection process will handle firmware upload if needed
                    allDevices.add(DeviceEntry(
                        index = allDevices.size,
                        name = "QHY Camera ($fwStatus)",
                        serialNumber = "QHY-${pid.toString(16)}-${usbDev.deviceId}",
                        brand = CameraBrand.QHY,
                        usbDevice = usbDev
                    ))
                    Log.i(TAG, "Found QHY device: VID=0x${QHY_VENDOR_ID.toString(16)} PID=0x${pid.toString(16)} ($fwStatus)")
                }
            }
            Log.i(TAG, "QHY enumeration complete, found ${allDevices.count { it.brand == CameraBrand.QHY }} QHY device(s)")
        } catch (e: Exception) {
            Log.e(TAG, "QHY enumeration failed: ${e.message}", e)
        }

        // Enumerate ZWO ASI cameras by USB VID (SDK calls deferred until permission granted)
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            for ((_, usbDev) in usbManager.deviceList) {
                if (usbDev.vendorId == ZWO_VENDOR_ID) {
                    val pid = usbDev.productId
                    val productName = usbDev.productName ?: "ZWO ASI Camera"
                    allDevices.add(DeviceEntry(
                        index = allDevices.size,
                        name = "ZWO $productName",
                        serialNumber = "ZWO-${pid.toString(16)}-${usbDev.deviceId}",
                        brand = CameraBrand.ZWO,
                        usbDevice = usbDev
                    ))
                    Log.i(TAG, "Found ZWO USB device: $productName VID=0x${ZWO_VENDOR_ID.toString(16)} PID=0x${pid.toString(16)}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ZWO USB enumeration failed: ${e.message}")
        }

        // Enumerate Player One cameras via process-wide SdkHost (includes unauthorized)
        try {
            val enumerated = PlayerOneSdkHost.enumerate(context)
            for (entry in enumerated) {
                val props = entry.properties
                val sn = props.serialNumber?.takeIf { it.isNotBlank() }
                    ?: "PO-${props.cameraId}"
                allDevices.add(DeviceEntry(
                    index = allDevices.size,
                    name = props.cameraModelName ?: "Player One Camera",
                    serialNumber = sn,
                    brand = CameraBrand.PLAYERONE,
                    usbDevice = entry.androidDevice
                ))
                Log.i(
                    TAG,
                    "Found Player One: ${props.cameraModelName} SN=$sn " +
                        "id=${props.cameraId} authorized=${entry.usb?.isAuthorized}"
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Player One enumeration failed: ${e.message}")
        }

        if (sessionName != "guide") {
            try {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                for ((_, usbDev) in usbManager.deviceList) {
                    if (!DslrUsb.isSupportedMainCamera(usbDev)) continue
                    val productName = usbDev.productName?.takeIf { it.isNotBlank() } ?: "Nikon PTP"
                    allDevices.add(
                        DeviceEntry(
                            index = allDevices.size,
                            name = productName,
                            serialNumber = "NIKON-${usbDev.productId.toString(16)}-${usbDev.deviceId}",
                            brand = CameraBrand.NIKON,
                            usbDevice = usbDev
                        )
                    )
                    Log.i(
                        TAG,
                        "Found Nikon PTP: $productName VID=0x${usbDev.vendorId.toString(16)} " +
                            "PID=0x${usbDev.productId.toString(16)}"
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Nikon PTP enumeration failed: ${e.message}")
            }
        }

        if (allDevices.isNotEmpty()) {
            _devices.value = allDevices
        }
        if (_connectionState.value !is ConnectionState.Connected) {
            _connectionState.value = ConnectionState.Enumerating
        }
    }

    fun scanAccessories() {
        val discovered = mutableListOf<AccessoryDeviceEntry>()
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            usbManager.deviceList.values.forEach { usbDevice ->
                if (usbDevice.vendorId != TOUPCAM_VENDOR_ID) return@forEach

                val isFilterWheel = runCatching {
                    ToupcamJni.isFilterWheel(usbDevice.vendorId, usbDevice.productId)
                }.getOrDefault(false)
                val isFocuser = runCatching {
                    ToupcamJni.isAutoFocuser(usbDevice.vendorId, usbDevice.productId)
                }.getOrDefault(false)
                val type = when {
                    isFilterWheel -> AccessoryType.FILTER_WHEEL
                    isFocuser -> AccessoryType.FOCUSER
                    else -> null
                } ?: return@forEach
                val fallbackName = when (type) {
                    AccessoryType.FILTER_WHEEL -> "ToupTek Filter Wheel"
                    AccessoryType.FOCUSER -> "ToupTek Focuser"
                    AccessoryType.EFUCOSER_FOCUSER -> "EFucoser"
                    AccessoryType.SERIAL_DEVICE -> "USB Serial Device"
                }
                val modelName = runCatching {
                    ToupcamJni.getModelName(usbDevice.vendorId, usbDevice.productId)
                }.getOrNull() ?: fallbackName
                discovered += AccessoryDeviceEntry(modelName, type, usbDevice)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Accessory scan failed", e)
        }
        _accessoryDevices.value = discovered
    }

    fun connectAccessory(device: AccessoryDeviceEntry) {
        when (device.type) {
            AccessoryType.FILTER_WHEEL -> connectFilterWheel(device.usbDevice)
            AccessoryType.FOCUSER -> connectEAF(device.usbDevice)
            AccessoryType.EFUCOSER_FOCUSER,
            AccessoryType.SERIAL_DEVICE -> Unit
        }
    }

    fun openCamera(deviceIndex: Int = 0): Boolean {
        if (_devices.value.isEmpty()) {
            enumerateDevices()
        }
        val devs = _devices.value
        if (devs.isEmpty()) return false
        val entry = devs.getOrNull(deviceIndex) ?: devs[0]
        return when (entry.brand) {
            CameraBrand.TOUPCAM -> {
                val usbDev = entry.usbDevice ?: return false
                requestToupcamPermission(usbDev)
                true
            }
            CameraBrand.QHY -> {
                val usbDev = entry.usbDevice ?: return false
                requestQhyPermission(usbDev)
                true
            }
            CameraBrand.ZWO -> {
                val usbDev = entry.usbDevice ?: return openZwoCamera(entry)
                requestZwoPermission(usbDev)
                true
            }
            CameraBrand.PLAYERONE -> {
                requestPlayerOnePermission(entry.serialNumber)
                true
            }
            CameraBrand.NIKON -> {
                val usbDev = entry.usbDevice ?: return false
                requestDslrPermission(usbDev)
                true
            }
            CameraBrand.CANON, CameraBrand.SONY -> {
                _connectionState.value = ConnectionState.Error("This camera brand is not implemented yet")
                false
            }
        }
    }

    fun openCameraBySn(sn: String): Boolean {
        val entry = _devices.value.firstOrNull { it.serialNumber == sn }
        if (entry == null) {
            _connectionState.value = ConnectionState.Error("Device $sn not found")
            return false
        }
        return when (entry.brand) {
            CameraBrand.TOUPCAM -> {
                val usbDev = entry.usbDevice ?: return false
                requestToupcamPermission(usbDev)
                true
            }
            CameraBrand.QHY -> {
                val usbDev = entry.usbDevice ?: return false
                requestQhyPermission(usbDev)
                true
            }
            CameraBrand.ZWO -> {
                val usbDev = entry.usbDevice ?: return openZwoCamera(entry)
                requestZwoPermission(usbDev)
                true
            }
            CameraBrand.PLAYERONE -> {
                requestPlayerOnePermission(sn)
                true
            }
            CameraBrand.NIKON -> {
                val usbDev = entry.usbDevice ?: return false
                requestDslrPermission(usbDev)
                true
            }
            CameraBrand.CANON, CameraBrand.SONY -> {
                _connectionState.value = ConnectionState.Error("This camera brand is not implemented yet")
                false
            }
        }
    }

    private fun requestToupcamPermission(usbDevice: UsbDevice) {
        if (activeCamera != null) closeCamera()
        _connectionState.value = ConnectionState.Connecting
        pendingToupcamDevice = usbDevice

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(usbDevice)) {
            openToupcamDevice(usbDevice)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, 0, Intent(actionUsbPermission).setPackage(context.packageName), flags)
            usbManager.requestPermission(usbDevice, pi)
        }
    }

    private fun openToupcamDevice(usbDevice: UsbDevice) {
        try {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val connection = usbManager.openDevice(usbDevice)
            if (connection == null) {
                Log.e(TAG, "UsbManager.openDevice returned null for ${usbDevice.deviceName}")
                _connectionState.value = ConnectionState.Error("Failed to open USB device")
                return
            }

            val fd = connection.fileDescriptor
            val vid = usbDevice.vendorId
            val pid = usbDevice.productId
            val modelName = ToupcamJni.getModelName(vid, pid) ?: "ToupTek Camera"
            val flag = ToupcamJni.getModelFlag(vid, pid)
            Log.i(TAG, "Opening ToupTek: fd=$fd VID=0x${vid.toString(16)} PID=0x${pid.toString(16)} model=$modelName flag=0x${flag.toString(16)}")

            val camera = ToupcamCamera()
            if (camera.open(fd, vid, pid, modelName)) {
                activeCamera = camera
                _connectionState.value = ConnectionState.Connected(camera.cameraInfo!!)
                Log.i(TAG, "ToupTek camera connected: $modelName")
            } else {
                connection.close()
                Log.e(TAG, "ToupcamCamera.open returned false for $modelName")
                _connectionState.value = ConnectionState.Error("Failed to initialize ToupTek camera")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "openToupcamDevice failed: ${e.message}", e)
            _connectionState.value = ConnectionState.Error("ToupTek open failed: ${e.message}")
        }
    }

    private var qhySdkInitialized = false
    private var qhyUsbConnection: android.hardware.usb.UsbDeviceConnection? = null
    private var qhyFirmwareUploading = false

    /**
     * Request permission to upload firmware to a QHY camera in bootloader mode.
     */
    private fun requestQhyFirmwareUpload(usbDevice: UsbDevice) {
        if (!QhyccdJni.nativeAvailable) {
            Log.w(TAG, "QHY native library not available, cannot upload firmware")
            return
        }
        if (qhyFirmwareUploading) {
            Log.i(TAG, "QHY firmware upload already in progress")
            return
        }

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(usbDevice)) {
            uploadQhyFirmware(usbDevice)
        } else {
            Log.i(TAG, "Requesting USB permission for QHY firmware upload")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, 4, Intent(actionUsbPermissionQhyFw).setPackage(context.packageName), flags)
            usbManager.requestPermission(usbDevice, pi)
        }
    }

    /**
     * Upload firmware to a QHY camera in bootloader mode.
     * After firmware upload, the camera will re-enumerate with a different PID.
     */
    private fun uploadQhyFirmware(usbDevice: UsbDevice) {
        qhyFirmwareUploading = true
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(usbDevice)
        if (connection == null) {
            Log.e(TAG, "Failed to open USB device for QHY firmware upload")
            qhyFirmwareUploading = false
            return
        }

        val fd = connection.fileDescriptor
        val vid = usbDevice.vendorId
        val pid = usbDevice.productId
        Log.i(TAG, "Uploading QHY firmware: fd=$fd VID=0x${vid.toString(16)} PID=0x${pid.toString(16)}")

        Thread(Runnable {
            try {
                if (!qhySdkInitialized) {
                    val initRet = QhyccdJni.initResource()
                    if (initRet != QhyccdJni.QHYCCD_SUCCESS) {
                        Log.e(TAG, "InitQHYCCDResource failed during firmware upload: $initRet")
                        connection.close()
                        qhyFirmwareUploading = false
                        return@Runnable
                    }
                    qhySdkInitialized = true
                }

                val fwRet = QhyccdJni.initFirmware(vid, pid, fd)
                Log.i(TAG, "QHY firmware upload result: $fwRet")

                connection.close()
                qhyFirmwareUploading = false

                if (fwRet != QhyccdJni.QHYCCD_SUCCESS) {
                    Log.e(TAG, "QHY firmware upload failed: ret=$fwRet PID=0x${pid.toString(16)}")
                    _connectionState.value = ConnectionState.Error(
                        "QHY firmware upload failed (ret=$fwRet) 鈥?SDK may lack firmware for this model")
                    return@Runnable
                }

                _connectionState.value = ConnectionState.Connecting

                // The camera re-enumerates with its firmware-loaded PID (0xCxxx).
                // Poll instead of a fixed 2.5s wait: faster on quick phones,
                // more tolerant on slow ones.
                var reloaded: UsbDevice? = null
                val deadline = System.currentTimeMillis() + 8000
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(300)
                    reloaded = usbManager.deviceList.values.firstOrNull {
                        it.vendorId == QHY_VENDOR_ID && QhyccdJni.isFirmwareLoaded(it.productId)
                    }
                    if (reloaded != null) break
                }

                if (reloaded == null) {
                    Log.e(TAG, "QHY camera did not re-enumerate after firmware upload")
                    _connectionState.value = ConnectionState.Error(
                        "QHY camera did not re-enumerate after firmware upload")
                    return@Runnable
                }

                Log.i(TAG, "QHY re-enumerated after firmware upload: PID=0x${reloaded.productId.toString(16)}")
                val device = reloaded
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    enumerateDevices()
                    requestQhyPermission(device)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "QHY firmware upload failed: ${e.message}", e)
                qhyFirmwareUploading = false
            }
        }, "QHY-FirmwareThread").start()
    }

    private fun requestQhyPermission(usbDevice: UsbDevice) {
        if (!QhyccdJni.nativeAvailable) {
            _connectionState.value = ConnectionState.Error("QHY native library not available on this device")
            return
        }
        if (activeCamera != null) closeCamera()
        _connectionState.value = ConnectionState.Connecting

        val pid = usbDevice.productId
        val fwLoaded = QhyccdJni.isFirmwareLoaded(pid)
        Log.i(TAG, "requestQhyPermission: PID=0x${pid.toString(16)} fwLoaded=$fwLoaded")

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(usbDevice)) {
            if (fwLoaded) {
                openQhyDevice(usbDevice)
            } else {
                // Need to upload firmware first, then re-enumerate
                uploadQhyFirmware(usbDevice)
            }
        } else {
            Log.i(TAG, "Requesting USB permission for QHY device")
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, 3, Intent(actionUsbPermissionQhy).setPackage(context.packageName), flags)
            usbManager.requestPermission(usbDevice, pi)
        }
    }

    private fun openQhyDevice(usbDevice: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(usbDevice)
        if (connection == null) {
            Log.e(TAG, "UsbManager.openDevice returned null for QHY ${usbDevice.deviceName}")
            _connectionState.value = ConnectionState.Error("Failed to open QHY USB device")
            return
        }
        qhyUsbConnection = connection

        val fd = connection.fileDescriptor
        val vid = usbDevice.vendorId
        val pid = usbDevice.productId
        Log.i(TAG, "Opening QHY: fd=$fd VID=0x${vid.toString(16)} PID=0x${pid.toString(16)}")

        Thread(Runnable {
            try {
                if (!qhySdkInitialized) {
                    val initRet = QhyccdJni.initResource()
                    if (initRet != QhyccdJni.QHYCCD_SUCCESS) {
                        Log.e(TAG, "InitQHYCCDResource failed: $initRet")
                        _connectionState.value = ConnectionState.Error("QHY SDK init failed")
                        closeQhyUsbConnection()
                        return@Runnable
                    }
                    qhySdkInitialized = true
                }

                val fwRet = QhyccdJni.initFirmware(vid, pid, fd)
                Log.i(TAG, "QHY firmware init: $fwRet")

                // Retry scan instead of a fixed 500ms sleep + single attempt
                var numCams = 0
                val scanDeadline = System.currentTimeMillis() + 5000
                while (true) {
                    numCams = QhyccdJni.scan()
                    if (numCams > 0 || System.currentTimeMillis() >= scanDeadline) break
                    Thread.sleep(200)
                }
                Log.i(TAG, "QHY scan found $numCams camera(s)")
                if (numCams <= 0) {
                    _connectionState.value = ConnectionState.Error("No QHY camera found after scan")
                    closeQhyUsbConnection()
                    return@Runnable
                }

                val cameraId = QhyccdJni.getId(0)
                Log.i(TAG, "QHY camera ID: $cameraId")

                val camera = QhyCamera()
                camera.setUsbInfo(vid, pid, fd)
                camera.setUsbContext(context, usbDevice, connection)
                if (camera.open(cameraId)) {
                    activeCamera = camera
                    _connectionState.value = ConnectionState.Connected(camera.cameraInfo!!)
                    Log.i(TAG, "QHY camera connected: ${camera.cameraInfo?.name}")
                } else {
                    Log.e(TAG, "QhyCamera.open failed for $cameraId")
                    _connectionState.value = ConnectionState.Error("Failed to initialize QHY camera")
                    closeQhyUsbConnection()
                }
            } catch (e: Throwable) {
                Log.e(TAG, "openQhyDevice failed: ${e.message}", e)
                _connectionState.value = ConnectionState.Error("QHY open failed: ${e.message}")
                closeQhyUsbConnection()
            }
        }, "QHY-OpenThread").start()
    }

    private fun closeQhyUsbConnection() {
        try { qhyUsbConnection?.close() } catch (_: Throwable) {}
        qhyUsbConnection = null
        if (qhySdkInitialized) {
            try { QhyccdJni.releaseResource() } catch (_: Throwable) {}
            qhySdkInitialized = false
            Log.i(TAG, "QHY SDK resource released for clean reconnect")
        }
    }

    private fun openZwoCamera(entry: DeviceEntry): Boolean {
        if (activeCamera != null) closeCamera()
        _connectionState.value = ConnectionState.Connecting

        try {
            if (!ZwoAsiCamera.sdkAvailable) {
                _connectionState.value = ConnectionState.Error("ZWO native library not available on this device")
                return false
            }

            val camera = ZwoAsiCamera()
            if (camera.open(entry.index)) {
                activeCamera = camera
                _connectionState.value = ConnectionState.Connected(camera.cameraInfo!!)
                Log.i(TAG, "ZWO camera connected: ${camera.cameraInfo?.name}")
                return true
            } else {
                _connectionState.value = ConnectionState.Error("Failed to initialize ZWO camera")
                return false
            }
        } catch (e: Throwable) {
            Log.e(TAG, "openZwoCamera failed: ${e.message}", e)
            _connectionState.value = ConnectionState.Error("ZWO open failed: ${e.message}")
            return false
        }
    }

    private fun requestZwoPermission(usbDevice: UsbDevice) {
        if (activeCamera != null) closeCamera()
        _connectionState.value = ConnectionState.Connecting

        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(usbDevice)) {
            openZwoDevice(usbDevice)
        } else {
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, 6, Intent(actionUsbPermissionZwo).setPackage(context.packageName), flags)
            usbManager.requestPermission(usbDevice, pi)
        }
    }

    private var zwoUsbConnection: android.hardware.usb.UsbDeviceConnection? = null

    private fun openZwoDevice(usbDevice: UsbDevice) {
        try {
            if (!ZwoAsiCamera.sdkAvailable) ZwoAsiCamera.initSdk()
            if (!ZwoAsiCamera.sdkAvailable) {
                _connectionState.value = ConnectionState.Error("ZWO SDK init failed")
                return
            }

            val vid = usbDevice.vendorId
            val pid = usbDevice.productId
            Log.i(TAG, "Opening ZWO: VID=0x${vid.toString(16)} PID=0x${pid.toString(16)} dev=${usbDevice.deviceName}")

            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            val connection = usbManager.openDevice(usbDevice)
            if (connection == null) {
                Log.e(TAG, "UsbManager.openDevice returned null for ZWO ${usbDevice.deviceName}")
                _connectionState.value = ConnectionState.Error("Failed to open ZWO USB device")
                return
            }
            zwoUsbConnection = connection

            val fd = connection.fileDescriptor
            Log.i(TAG, "ZWO USB fd=$fd, installing hooks for libusb redirection")

            UsbHelper.registerUsbFd(context, usbDevice, fd)
            UsbHelper.installZwoHooks()

            com.zwo.ASIUSBManager.initContext(context)

            val numCameras: Int
            try {
                numCameras = ZwoCamera.getNumOfConnectedCameras()
            } catch (e: Throwable) {
                Log.e(TAG, "ZWO getNumOfConnectedCameras crashed (SELinux/libusb?): ${e.message}", e)
                closeZwoUsbConnection()
                val pidHex = "0x${pid.toString(16).uppercase()}"
                _connectionState.value = ConnectionState.Error(
                    "ZWO SDK USB error (PID=$pidHex). ${e.message}"
                )
                return
            }

            val pidHex = "0x${pid.toString(16).uppercase()}"
            Log.i(TAG, "ZWO connected cameras: $numCameras")
            if (numCameras <= 0) {
                closeZwoUsbConnection()
                val productName = usbDevice.productName ?: "Unknown"
                _connectionState.value = ConnectionState.Error(
                    "ZWO camera not recognized by SDK (PID=$pidHex $productName). SDK may need update for this model."
                )
                return
            }

            val camera = ZwoAsiCamera()
            if (camera.open(0)) {
                activeCamera = camera
                _connectionState.value = ConnectionState.Connected(camera.cameraInfo!!)
                Log.i(TAG, "ZWO camera connected: ${camera.cameraInfo?.name}")
            } else {
                closeZwoUsbConnection()
                _connectionState.value = ConnectionState.Error(
                    "Failed to initialize ZWO camera (PID=$pidHex)"
                )
            }
        } catch (e: Throwable) {
            Log.e(TAG, "openZwoDevice failed: ${e.message}", e)
            closeZwoUsbConnection()
            val pidHex = "0x${usbDevice.productId.toString(16).uppercase()}"
            _connectionState.value = ConnectionState.Error("ZWO open failed (PID=$pidHex): ${e.message}")
        }
    }

    private fun closeZwoUsbConnection() {
        try { zwoUsbConnection?.close() } catch (_: Throwable) {}
        zwoUsbConnection = null
        UsbHelper.clearUsbFds()
    }

    private fun requestDslrPermission(usbDevice: UsbDevice) {
        if (activeCamera != null) closeCamera()
        _connectionState.value = ConnectionState.Connecting
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(usbDevice)) {
            openDslrDevice(usbDevice)
            return
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        else PendingIntent.FLAG_UPDATE_CURRENT
        val pi = PendingIntent.getBroadcast(
            context,
            7,
            Intent(actionUsbPermissionDslr).setPackage(context.packageName),
            flags
        )
        usbManager.requestPermission(usbDevice, pi)
    }

    private fun openDslrDevice(usbDevice: UsbDevice) {
        _connectionState.value = ConnectionState.Connecting
        Thread({
            try {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
                val connection = usbManager.openDevice(usbDevice)
                if (connection == null) {
                    _connectionState.value = ConnectionState.Error("Failed to open USB device")
                    return@Thread
                }
                val camera = DslrCamera()
                if (camera.open(usbDevice, connection)) {
                    activeCamera = camera
                    val info = camera.cameraInfo
                    if (info == null) {
                        camera.close()
                        _connectionState.value = ConnectionState.Error("Nikon PTP opened without device info")
                        return@Thread
                    }
                    _connectionState.value = ConnectionState.Connected(info)
                    Log.i(TAG, "Nikon PTP connected: ${info.name}")
                } else {
                    _connectionState.value = ConnectionState.Error(
                        "Nikon PTP OpenSession/GetDeviceInfo failed. Check USB mode is MTP/PTP."
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "openDslrDevice failed: ${e.message}", e)
                _connectionState.value = ConnectionState.Error("Nikon PTP open failed: ${e.message}")
            }
        }, "Dslr-OpenThread").start()
    }

    private fun requestPlayerOnePermission(serialNumber: String) {
        if (activeCamera != null) closeCamera()
        _connectionState.value = ConnectionState.Connecting

        try {
            PlayerOneSdkHost.ensureStarted(context)
            val found = PlayerOneSdkHost.findDeviceBySerial(context, serialNumber)
            if (found == null) {
                _connectionState.value = ConnectionState.Error("Player One camera $serialNumber not found")
                return
            }
            val usbDevice = found.usb
            if (usbDevice == null) {
                _connectionState.value = ConnectionState.Error(
                    "Player One camera $serialNumber has no USB device to authorize"
                )
                return
            }
            val cameraId = found.properties.cameraId
            PlayerOneSdkHost.requestPermission(usbDevice) { granted ->
                if (!granted) {
                    Log.w(TAG, "Player One USB permission denied for $serialNumber")
                    _connectionState.value = ConnectionState.Error("USB permission denied")
                    return@requestPermission
                }
                openPlayerOneDevice(cameraId, serialNumber)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "requestPlayerOnePermission failed: ${e.message}", e)
            _connectionState.value = ConnectionState.Error("Player One open failed: ${e.message}")
        }
    }

    private fun openPlayerOneDevice(cameraId: Int, serialNumber: String) {
        // open/initialize must not run on main thread
        Thread({
            try {
                val camera = PlayerOneCamera()
                if (camera.open(cameraId)) {
                    activeCamera = camera
                    val info = camera.cameraInfo
                    if (info != null) {
                        _connectionState.value = ConnectionState.Connected(info)
                        Log.i(TAG, "Player One connected: ${info.name} SN=${info.serialNumber}")
                    } else {
                        _connectionState.value = ConnectionState.Error("Player One opened without camera info")
                    }
                } else {
                    _connectionState.value = ConnectionState.Error(
                        "Failed to open Player One camera (SN=$serialNumber id=$cameraId)"
                    )
                }
            } catch (e: Throwable) {
                Log.e(TAG, "openPlayerOneDevice failed: ${e.message}", e)
                _connectionState.value = ConnectionState.Error("Player One open failed: ${e.message}")
            }
        }, "PlayerOne-Open").start()
    }

    fun closeCamera() {
        activeCamera?.close()
        activeCamera = null
        closeQhyUsbConnection()
        closeZwoUsbConnection()
        _connectionState.value = ConnectionState.Disconnected
    }

    fun connectFilterWheel(usbDevice: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(usbDevice)) {
            filterWheelController.open(context, usbDevice)
        } else {
            pendingFilterWheelDevice = usbDevice
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, 1, Intent(actionUsbPermissionFw).setPackage(context.packageName), flags)
            usbManager.requestPermission(usbDevice, pi)
        }
    }

    fun disconnectFilterWheel() {
        filterWheelController.close()
    }

    fun connectEAF(usbDevice: UsbDevice) {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        if (usbManager.hasPermission(usbDevice)) {
            eafController.open(context, usbDevice)
        } else {
            pendingEafDevice = usbDevice
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getBroadcast(context, 2, Intent(actionUsbPermissionEaf).setPackage(context.packageName), flags)
            usbManager.requestPermission(usbDevice, pi)
        }
    }

    fun disconnectEAF() {
        eafController.close()
    }
}

