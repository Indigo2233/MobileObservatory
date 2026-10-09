package com.indigo.mobileobservatory.camera.toupcam

import android.hardware.usb.UsbDevice

/**
 * ToupTek (图谱) USB 设备分类。SDK 型号表里没有的 PID 仍当相机枚举，
 * 把 fd 交给 SDK 的 `Toupcam_Open`；不要因为 getModelName 为空就从列表里丢掉。
 *
 * 官方 GPM462C/M（USB 2.0 导星）在 Windows 上是 `0x0547:0x14FF` / `0x1500`。
 * 部分机子在 Android 上报 Cypress `0x04B4`（官方 udev 也认）或 USB3 Vision
 * 通用号 `0x2BA2:0x4D55`。消费版要把这几个 VID 都交给图谱 SDK。
 * 工业 overlay 的 `0x2BA2` 走大恒 Galaxy，只能用 [isProtocolVendor]。
 */
object ToupTekDevices {
    const val VENDOR_ID = 0x0547
    const val CYPRESS_VENDOR_ID = 0x04B4
    const val U3V_VENDOR_ID = 0x2BA2

    enum class Kind { CAMERA, FILTER_WHEEL, FOCUSER }

    fun isProtocolVendor(vid: Int): Boolean =
        vid == VENDOR_ID || vid == CYPRESS_VENDOR_ID

    fun isVendor(vid: Int): Boolean =
        isProtocolVendor(vid) || vid == U3V_VENDOR_ID

    fun classify(isFilterWheel: Boolean, isAutoFocuser: Boolean): Kind = when {
        isFilterWheel -> Kind.FILTER_WHEEL
        isAutoFocuser -> Kind.FOCUSER
        else -> Kind.CAMERA
    }

    data class OpenHint(val vendorId: Int, val productId: Int, val modelName: String)

    fun cameraDisplayName(modelName: String?): String =
        modelName?.takeIf { it.isNotBlank() } ?: "ToupTek Camera"

    fun usbIdentity(device: UsbDevice): String =
        "VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)} " +
            "product=${device.productName ?: "-"} mfr=${device.manufacturerName ?: "-"}"

    fun hintedModelName(productName: String?, manufacturerName: String? = null): String? {
        val compact = listOfNotNull(productName, manufacturerName)
            .joinToString("")
            .filter { !it.isWhitespace() && it != '-' && it != '_' }
            .lowercase()
        if (compact.isEmpty()) return null
        return when {
            "gpm462m" in compact -> "GPM462M"
            "gpm462c" in compact -> "GPM462C"
            "g3m462m" in compact -> "G3M462M"
            "g3m462c" in compact -> "G3M462C"
            "462m" in compact -> "GPM462M"
            "462c" in compact -> "GPM462C"
            else -> productName?.takeIf { it.isNotBlank() }
        }
    }

    fun displayNameFor(device: UsbDevice, sdkModelName: String?): String =
        cameraDisplayName(sdkModelName ?: hintedModelName(device.productName, device.manufacturerName))

    /**
     * Android 上报 `0x2BA2` 时，SDK 型号表对不上。先用真实 VID/PID 打开，
     * 失败再按 USB 字符串（或 462 默认）试官方 `0x0547` PID。
     */
    fun openHints(usbVid: Int, usbPid: Int, productName: String?, manufacturerName: String? = null): List<OpenHint> {
        val named = hintedModelName(productName, manufacturerName)
        val ids = linkedSetOf(usbVid to usbPid)
        when (named) {
            "GPM462M" -> ids += VENDOR_ID to 0x1500
            "GPM462C" -> ids += VENDOR_ID to 0x14FF
            "G3M462M" -> {
                ids += VENDOR_ID to 0x14C2
                ids += VENDOR_ID to 0x14C3
            }
            "G3M462C" -> {
                ids += VENDOR_ID to 0x12DE
                ids += VENDOR_ID to 0x12DF
            }
            else -> if (usbVid == U3V_VENDOR_ID || usbVid == CYPRESS_VENDOR_ID) {
                ids += VENDOR_ID to 0x1500
                ids += VENDOR_ID to 0x14FF
                ids += VENDOR_ID to 0x14C2
                ids += VENDOR_ID to 0x14C3
                ids += VENDOR_ID to 0x12DE
                ids += VENDOR_ID to 0x12DF
            }
        }
        return ids.map { (vid, pid) -> OpenHint(vid, pid, named ?: "ToupTek Camera") }
    }
}
