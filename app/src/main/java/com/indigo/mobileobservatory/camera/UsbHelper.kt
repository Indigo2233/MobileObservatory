package com.indigo.mobileobservatory.camera

import android.content.Context
import android.hardware.usb.UsbDevice
import android.util.Log
import java.io.File

object UsbHelper {
    private const val TAG = "UsbHelper"

    private var available = false

    init {
        available = try {
            System.loadLibrary("usb_helper_jni")
            true
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "usb_helper_jni not available: ${e.message}")
            false
        }
    }

    fun registerUsbFd(context: Context, usbDevice: UsbDevice, fd: Int): Boolean {
        if (!available) return false
        val devPath = usbDevice.deviceName
        val parts = devPath.removePrefix("/dev/bus/usb/").split("/")
        if (parts.size < 2) {
            Log.e(TAG, "Cannot parse device path: $devPath")
            return false
        }
        val busNum = parts[0].toIntOrNull() ?: return false
        val devAddr = parts[1].toIntOrNull() ?: return false
        Log.i(TAG, "registerUsbFd: bus=$busNum dev=$devAddr fd=$fd path=$devPath")
        return try {
            val registered = nativeRegisterUsbFd(context.cacheDir.absolutePath, busNum, devAddr, fd)
            if (registered) {
                updateUsbSpeed(context, usbDevice, busNum, devAddr)
            }
            registered
        } catch (e: Throwable) {
            Log.e(TAG, "registerUsbFd failed: ${e.message}", e)
            false
        }
    }

    private fun updateUsbSpeed(context: Context, usbDevice: UsbDevice, busNum: Int, devAddr: Int) {
        val maxPacketSize = (0 until usbDevice.interfaceCount).maxOfOrNull { interfaceIndex ->
            val usbInterface = usbDevice.getInterface(interfaceIndex)
            (0 until usbInterface.endpointCount).maxOfOrNull { endpointIndex ->
                usbInterface.getEndpoint(endpointIndex).maxPacketSize
            } ?: 0
        } ?: 0
        val speedMbps = when {
            maxPacketSize >= 1024 -> 5000
            usbDevice.version.startsWith("3") || usbDevice.version.startsWith("4") -> 5000
            maxPacketSize >= 512 || usbDevice.version.startsWith("2") -> 480
            else -> 12
        }
        val speedFile = File(context.cacheDir, "fake_sysfs/$busNum-$devAddr/speed")
        runCatching {
            speedFile.writeText("$speedMbps\n")
            Log.i(
                TAG,
                "USB speed override: version=${usbDevice.version} maxPacket=$maxPacketSize speed=${speedMbps}Mbps"
            )
        }.onFailure { error ->
            Log.w(TAG, "USB speed override failed: ${error.message}")
        }
    }

    fun installHooks(): Int {
        if (!available) return 0
        return try {
            val result = nativeInstallHooks()
            Log.i(TAG, "installHooks: $result hooks installed")
            result
        } catch (e: Throwable) {
            Log.e(TAG, "installHooks failed: ${e.message}", e)
            0
        }
    }

    fun installZwoHooks(): Int {
        if (!available) return 0
        return try {
            val result = nativeInstallZwoHooks()
            Log.i(TAG, "installZwoHooks: $result hooks installed")
            result
        } catch (e: Throwable) {
            Log.e(TAG, "installZwoHooks failed: ${e.message}", e)
            0
        }
    }

    fun clearUsbFds() {
        if (!available) return
        try {
            nativeClearUsbFds()
        } catch (e: Throwable) {
            Log.e(TAG, "clearUsbFds failed: ${e.message}", e)
        }
    }

    private external fun nativeRegisterUsbFd(cacheDir: String, busNum: Int, devAddr: Int, fd: Int): Boolean
    private external fun nativeInstallHooks(): Int
    private external fun nativeInstallZwoHooks(): Int
    private external fun nativeClearUsbFds()
}
