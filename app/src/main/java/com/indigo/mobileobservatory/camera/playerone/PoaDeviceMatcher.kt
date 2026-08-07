package com.indigo.mobileobservatory.camera.playerone

/**
 * Pairs SDK cameras with the USB devices that carry them.
 *
 * The SDK exposes two independent lists — `PlayerOneCameraSdk` cameras (identified by
 * `cameraId`) and `PlayerOneUsbManager` devices (identified by `registrationId`) — and
 * documents no relation between the two identifiers. Opening a camera needs the camera id,
 * but requesting USB permission needs the device, so the two must be paired heuristically.
 *
 * Pure functions, no Android or SDK dependency, so the fallback order stays unit-testable.
 */
object PoaDeviceMatcher {

    data class DeviceKey(
        val index: Int,
        val registrationId: Int,
        val deviceName: String?,
        val productId: Int
    )

    data class CameraKey(
        val index: Int,
        val cameraId: Int,
        val localPath: String?,
        val productId: Int
    )

    /** Returns camera index -> device index. Cameras with no confident match are absent. */
    fun match(cameras: List<CameraKey>, devices: List<DeviceKey>): Map<Int, Int> {
        val result = mutableMapOf<Int, Int>()
        if (cameras.isEmpty() || devices.isEmpty()) return result

        val pendingCameras = cameras.toMutableList()
        val usedDevices = mutableSetOf<Int>()

        fun pairBy(predicate: (CameraKey, DeviceKey) -> Boolean) {
            val iterator = pendingCameras.iterator()
            while (iterator.hasNext()) {
                val camera = iterator.next()
                val device = devices.firstOrNull {
                    it.index !in usedDevices && predicate(camera, it)
                } ?: continue
                result[camera.index] = device.index
                usedDevices += device.index
                iterator.remove()
            }
        }

        pairBy { camera, device ->
            !camera.localPath.isNullOrBlank() && camera.localPath == device.deviceName
        }
        pairBy { camera, device -> camera.cameraId == device.registrationId }

        // Same product id, unambiguous on both sides.
        pairBy { camera, device ->
            camera.productId == device.productId &&
                pendingCameras.count { it.productId == camera.productId } == 1 &&
                devices.count { it.index !in usedDevices && it.productId == camera.productId } == 1
        }

        // Last resort: exactly one camera and one device left over.
        val remainingDevices = devices.filter { it.index !in usedDevices }
        if (pendingCameras.size == 1 && remainingDevices.size == 1) {
            result[pendingCameras[0].index] = remainingDevices[0].index
        }

        return result
    }
}
