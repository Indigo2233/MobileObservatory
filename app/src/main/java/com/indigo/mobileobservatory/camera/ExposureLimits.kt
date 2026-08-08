package com.indigo.mobileobservatory.camera

/**
 * SharpCap-style LX Mode limits and presets.
 * Long-exposure toggle only changes UI range; software stacking is a separate capability.
 */
object ExposureLimits {
    const val SHORT_MAX_US = 5_000_000f
    const val LONG_MIN_US = 1_000_000f
    const val STACKING_MAX_US = 300_000_000f

    val SHORT_PRESETS = listOf(
        1_000f, 10_000f, 33_333f, 100_000f, 500_000f, 1_000_000f, 5_000_000f
    )
    val LONG_PRESETS = listOf(
        1_000_000f, 5_000_000f, 10_000_000f, 20_000_000f, 30_000_000f,
        60_000_000f, 120_000_000f, 300_000_000f, 600_000_000f
    )

    fun uiMinUs(cam: Camera, longExposure: Boolean): Float {
        val hwMin = cam.exposureRange.min.coerceAtLeast(1f)
        return if (longExposure) maxOf(hwMin, LONG_MIN_US) else hwMin
    }

    fun uiMaxUs(cam: Camera, longExposure: Boolean): Float {
        val hwMax = cam.hwExposureMaxUs.coerceAtLeast(1f)
        return if (longExposure) {
            if (cam.supportsSoftwareStacking) STACKING_MAX_US else hwMax
        } else {
            minOf(hwMax, SHORT_MAX_US)
        }
    }

    fun absoluteMaxUs(cam: Camera): Float =
        if (cam.supportsSoftwareStacking) STACKING_MAX_US else cam.hwExposureMaxUs

    fun presets(longExposure: Boolean, maxUs: Float): List<Float> {
        val all = if (longExposure) LONG_PRESETS else SHORT_PRESETS
        return all.filter { it <= maxUs + 1f }
    }
}
