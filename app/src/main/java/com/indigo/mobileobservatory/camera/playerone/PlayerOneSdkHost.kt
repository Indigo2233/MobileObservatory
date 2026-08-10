package com.indigo.mobileobservatory.camera.playerone

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbManager
import com.indigo.mobileobservatory.util.FileLogger
import com.playeroneastronomy.camera.CameraProperties
import com.playeroneastronomy.camera.PlayerOneCameraSdk
import com.playeroneastronomy.camera.PlayerOneUsbManager
import com.playeroneastronomy.camera.PoaException
import com.playeroneastronomy.camera.UsbCameraDevice

/**
 * Process-wide host for Player One USB + native registry.
 * Both main and guide [com.indigo.mobileobservatory.camera.DahengCameraManager] instances
 * must share this singleton — never create a second [PlayerOneUsbManager].
 */
object PlayerOneSdkHost {
    private const val TAG = "PlayerOneSdkHost"

    @Volatile
    private var usbManager: PlayerOneUsbManager? = null

    /**
     * [usb] is null when the camera could not be confidently paired with a USB device;
     * the camera is still listed, but permission cannot be requested for it.
     */
    data class EnumeratedCamera(
        val usb: UsbCameraDevice?,
        val properties: CameraProperties,
        val androidDevice: UsbDevice?
    )

    @Synchronized
    fun ensureStarted(context: Context) {
        if (usbManager != null) return
        try {
            usbManager = PlayerOneUsbManager(context.applicationContext)
            FileLogger.i(TAG, "PlayerOneUsbManager started, SDK=${PlayerOneCameraSdk.getSdkVersion()}")
        } catch (e: Throwable) {
            FileLogger.e(TAG, "Failed to start PlayerOneUsbManager: ${e.message}", e)
            throw e
        }
    }

    fun refreshDevices(): List<UsbCameraDevice> {
        val mgr = usbManager ?: return emptyList()
        return try {
            mgr.refreshDevices()
        } catch (e: Exception) {
            FileLogger.e(TAG, "refreshDevices failed: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Refresh USB + native registry and pair each camera with [CameraProperties]
     * (including serial number for persistent identity).
     */
    fun enumerate(context: Context): List<EnumeratedCamera> {
        val androidUsb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val androidDevices = androidUsb?.deviceList.orEmpty()
        ensureStarted(context)
        val devices = refreshDevices()
        if (devices.isEmpty()) return emptyList()

        // The vendor bridge only exposes cameras through PlayerOneCameraSdk after
        // an Android UsbDeviceConnection FD has been bound. When Android already
        // granted permission (for example through USB_DEVICE_ATTACHED), the
        // vendor callback is synchronous, so bind before querying camera count.
        for (device in devices) {
            val androidDevice = androidDevices[device.deviceName]
            when {
                androidDevice == null -> {
                    FileLogger.w(
                        TAG,
                        "Cannot bind registration=${device.registrationId}: " +
                            "Android device ${device.deviceName} is unavailable"
                    )
                }
                androidUsb?.hasPermission(androidDevice) != true -> {
                    FileLogger.i(
                        TAG,
                        "Bind deferred registration=${device.registrationId}: " +
                            "Android USB permission is pending"
                    )
                }
                device.isAuthorized -> Unit
                else -> {
                    var bindGranted: Boolean? = null
                    requestPermission(device) { granted -> bindGranted = granted }
                    if (bindGranted != true || !device.isAuthorized) {
                        FileLogger.w(
                            TAG,
                            "Bind failed registration=${device.registrationId}: " +
                                "granted=$bindGranted authorized=${device.isAuthorized}"
                        )
                    }
                }
            }
        }

        val count = try {
            PlayerOneCameraSdk.getCameraCount()
        } catch (e: Exception) {
            FileLogger.e(TAG, "getCameraCount failed: ${e.message}", e)
            return emptyList()
        }

        val cameras = mutableListOf<CameraProperties>()
        for (i in 0 until count) {
            val props = try {
                PlayerOneCameraSdk.getCameraProperties(i)
            } catch (e: Exception) {
                FileLogger.w(TAG, "getCameraProperties($i) failed: ${e.message}")
                continue
            }
            cameras += props
        }
        if (cameras.isEmpty()) return emptyList()

        val pairing = PoaDeviceMatcher.match(
            cameras = cameras.mapIndexed { index, props ->
                PoaDeviceMatcher.CameraKey(index, props.cameraId, props.localPath, props.productId)
            },
            devices = devices.mapIndexed { index, device ->
                PoaDeviceMatcher.DeviceKey(index, device.registrationId, device.deviceName, device.productId)
            }
        )

        val androidByPath = androidUsb?.deviceList ?: emptyMap()

        return cameras.mapIndexed { index, props ->
            val usb = pairing[index]?.let(devices::get)
            if (usb == null) {
                FileLogger.w(TAG, "Camera ${props.cameraModelName} id=${props.cameraId} has no matching USB device")
            }
            val androidDevice = usb?.deviceName?.let(androidByPath::get)
            logAndroidUsbDescriptor(props.cameraModelName, androidDevice)
            EnumeratedCamera(usb, props, androidDevice)
        }
    }

    private fun logAndroidUsbDescriptor(modelName: String?, device: UsbDevice?) {
        if (device == null) {
            FileLogger.w(TAG, "Android USB descriptor unavailable for $modelName")
            return
        }
        val bulkPackets = mutableListOf<Int>()
        for (interfaceIndex in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(interfaceIndex)
            for (endpointIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                if (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    bulkPackets += endpoint.maxPacketSize
                }
            }
        }
        FileLogger.i(
            TAG,
            "Android USB model=$modelName version=${device.version} " +
                "interfaces=${device.interfaceCount} bulkMaxPackets=$bulkPackets " +
                "linkEvidence=${classifyAndroidUsbLink(bulkPackets)}"
        )
    }

    fun requestPermission(device: UsbCameraDevice, onResult: (Boolean) -> Unit) {
        val mgr = usbManager
        if (mgr == null) {
            onResult(false)
            return
        }
        // Callback may run synchronously when permission is already granted.
        var delivered = false
        fun deliver(granted: Boolean) {
            if (delivered) return
            delivered = true
            onResult(granted)
        }
        try {
            mgr.requestPermission(device) { _, granted -> deliver(granted) }
        } catch (e: Exception) {
            FileLogger.e(TAG, "requestPermission failed: ${e.message}", e)
            deliver(false)
        }
    }

    fun findDeviceBySerial(context: Context, serialNumber: String): EnumeratedCamera? {
        return enumerate(context).firstOrNull {
            playerOneIdentityMatches(
                serialNumber = it.properties.serialNumber,
                cameraId = it.properties.cameraId,
                requestedIdentity = serialNumber
            )
        }
    }

    fun claim(cameraId: Int): Boolean {
        val ok = PlayerOneClaimRegistry.claim(cameraId)
        if (!ok) {
            FileLogger.w(TAG, "claim($cameraId) rejected — already claimed")
        } else {
            FileLogger.i(TAG, "claim($cameraId) ok, claimed=${PlayerOneClaimRegistry.snapshot()}")
        }
        return ok
    }

    fun release(cameraId: Int) {
        PlayerOneClaimRegistry.release(cameraId)
        FileLogger.i(TAG, "release($cameraId), claimed=${PlayerOneClaimRegistry.snapshot()}")
    }

    fun runSingleFrameDiagnostic(device: UsbCameraDevice) {
        val mgr = usbManager ?: throw IllegalStateException("PlayerOneSdkHost not started")
        try {
            mgr.runSingleFrameDiagnostic(device)
            FileLogger.i(TAG, "runSingleFrameDiagnostic OK for ${device.deviceName}")
        } catch (e: PoaException) {
            FileLogger.e(TAG, "runSingleFrameDiagnostic ${e.error}: ${e.message}", e)
            throw e
        }
    }
}
