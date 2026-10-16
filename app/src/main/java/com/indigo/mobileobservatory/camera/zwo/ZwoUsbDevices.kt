package com.indigo.mobileobservatory.camera.zwo

/** Classifies ZWO USB devices before they are offered to the ASI camera SDK. */
object ZwoUsbDevices {
    const val VENDOR_ID = 0x03C3

    // ZWO reuses its vendor ID across cameras, mounts, focusers and filter wheels.
    private val NON_CAMERA_PRODUCT_IDS = setOf(
        0x4001 // AM5 USB CDC serial port
    )

    fun isCameraCandidate(vendorId: Int, productId: Int): Boolean =
        vendorId == VENDOR_ID && productId !in NON_CAMERA_PRODUCT_IDS
}
