package com.indigo.mobileobservatory.camera.toupcam

/**
 * ToupTek (图谱) USB 设备分类。SDK 型号表里没有的 PID 仍当相机枚举，
 * 把 fd 交给 SDK 的 `Toupcam_Open`；不要因为 getModelName 为空就从列表里丢掉。
 */
object ToupTekDevices {
    const val VENDOR_ID = 1351 // 0x0547

    enum class Kind { CAMERA, FILTER_WHEEL, FOCUSER }

    fun classify(isFilterWheel: Boolean, isAutoFocuser: Boolean): Kind = when {
        isFilterWheel -> Kind.FILTER_WHEEL
        isAutoFocuser -> Kind.FOCUSER
        else -> Kind.CAMERA
    }

    fun cameraDisplayName(modelName: String?): String =
        modelName?.takeIf { it.isNotBlank() } ?: "ToupTek Camera"
}
