package com.indigo.mobileobservatory.camera.playerone

import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.camera.ReadoutMode
import com.indigo.mobileobservatory.camera.Roi
import com.playeroneastronomy.camera.PoaBayerPattern
import com.playeroneastronomy.camera.PoaImageFormat
import kotlin.math.roundToInt

/**
 * Pure mapping helpers for Player One SDK types. No Android dependency — unit-testable on JVM.
 */
object PoaMapping {

    const val ROI_MIN = 16
    const val ROI_WIDTH_ALIGN = 4
    const val ROI_HEIGHT_ALIGN = 2

    fun gainToDb(gain: Int): Float = gain * 0.1f

    fun dbToGain(db: Float): Int = (db * 10f).roundToInt()

    fun toPixelFormat(format: PoaImageFormat, bayer: PoaBayerPattern): PixelFormat? {
        return when (format) {
            PoaImageFormat.RAW8 -> when (bayer) {
                PoaBayerPattern.MONO -> PixelFormat.MONO8
                PoaBayerPattern.RG -> PixelFormat.BAYER_RG8
                PoaBayerPattern.BG -> PixelFormat.BAYER_BG8
                PoaBayerPattern.GR -> PixelFormat.BAYER_GR8
                PoaBayerPattern.GB -> PixelFormat.BAYER_GB8
            }
            PoaImageFormat.RAW16 -> when (bayer) {
                PoaBayerPattern.MONO -> PixelFormat.MONO16
                PoaBayerPattern.RG -> PixelFormat.BAYER_RG16
                PoaBayerPattern.BG -> PixelFormat.BAYER_BG16
                PoaBayerPattern.GR -> PixelFormat.BAYER_GR16
                PoaBayerPattern.GB -> PixelFormat.BAYER_GB16
            }
            PoaImageFormat.MONO8 -> PixelFormat.MONO8
            PoaImageFormat.RGB24 -> null // project has no RGB24 (only RGB48); do not expose
        }
    }

    fun toPoaImageFormat(format: PixelFormat): PoaImageFormat = when (format) {
        PixelFormat.MONO8 -> PoaImageFormat.MONO8
        PixelFormat.MONO16,
        PixelFormat.BAYER_RG16, PixelFormat.BAYER_BG16,
        PixelFormat.BAYER_GR16, PixelFormat.BAYER_GB16 -> PoaImageFormat.RAW16
        PixelFormat.BAYER_RG8, PixelFormat.BAYER_BG8,
        PixelFormat.BAYER_GR8, PixelFormat.BAYER_GB8 -> PoaImageFormat.RAW8
        else -> if (format.is8bit) PoaImageFormat.RAW8 else PoaImageFormat.RAW16
    }

    /**
     * Align ROI: width down to multiple of 4, height down to multiple of 2,
     * clamp to min 16×16 and within [sensorW, sensorH] (already bin-scaled).
     */
    fun alignRoi(roi: Roi, sensorW: Int, sensorH: Int): Roi {
        val maxW = sensorW.coerceAtLeast(ROI_MIN)
        val maxH = sensorH.coerceAtLeast(ROI_MIN)

        var w = roi.width.coerceIn(ROI_MIN, maxW)
        var h = roi.height.coerceIn(ROI_MIN, maxH)
        w = (w / ROI_WIDTH_ALIGN) * ROI_WIDTH_ALIGN
        h = (h / ROI_HEIGHT_ALIGN) * ROI_HEIGHT_ALIGN
        if (w < ROI_MIN) w = ROI_MIN
        if (h < ROI_MIN) h = ROI_MIN
        w = w.coerceAtMost(maxW / ROI_WIDTH_ALIGN * ROI_WIDTH_ALIGN).coerceAtLeast(ROI_MIN)
        h = h.coerceAtMost(maxH / ROI_HEIGHT_ALIGN * ROI_HEIGHT_ALIGN).coerceAtLeast(ROI_MIN)

        val x = roi.x.coerceIn(0, (maxW - w).coerceAtLeast(0))
        val y = roi.y.coerceIn(0, (maxH - h).coerceAtLeast(0))
        return Roi(x, y, w, h)
    }

    fun mapSensorModeName(name: String?): ReadoutMode {
        val n = name?.lowercase().orEmpty()
        return when {
            "hcg" in n || "high conversion" in n -> ReadoutMode.HCG
            "lcg" in n || "low conversion" in n -> ReadoutMode.LCG
            "hdr" in n -> ReadoutMode.HDR
            "low noise" in n || "low-noise" in n || n == "ln" || "lowest read" in n ->
                ReadoutMode.LOW_NOISE
            else -> ReadoutMode.NORMAL
        }
    }

    /** Prefer RAW8/MONO8 for preview when available; fall back to first mappable format. */
    fun pickInitialFormat(
        formats: List<PoaImageFormat>,
        bayer: PoaBayerPattern
    ): Pair<PoaImageFormat, PixelFormat>? {
        val preferred = listOf(PoaImageFormat.RAW8, PoaImageFormat.MONO8, PoaImageFormat.RAW16)
        for (fmt in preferred) {
            if (fmt in formats) {
                val pf = toPixelFormat(fmt, bayer) ?: continue
                return fmt to pf
            }
        }
        for (fmt in formats) {
            val pf = toPixelFormat(fmt, bayer) ?: continue
            return fmt to pf
        }
        return null
    }
}
