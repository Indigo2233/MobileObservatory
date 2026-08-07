package com.indigo.mobileobservatory.camera.playerone

import android.content.Context
import android.hardware.usb.UsbDevice
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
        ensureStarted(context)
        val devices = refreshDevices()
        if (devices.isEmpty()) return emptyList()

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

        val androidUsb = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val androidByPath = androidUsb?.deviceList ?: emptyMap()

        return cameras.mapIndexed { index, props ->
            val usb = pairing[index]?.let(devices::get)
            if (usb == null) {
                FileLogger.w(TAG, "Camera ${props.cameraModelName} id=${props.cameraId} has no matching USB device")
            }
            EnumeratedCamera(usb, props, usb?.deviceName?.let(androidByPath::get))
        }
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
            it.properties.serialNumber == serialNumber
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
