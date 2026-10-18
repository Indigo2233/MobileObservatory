package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.Roi
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

enum class StackRoiAspect { SENSOR, SQUARE, FOUR_THREE, SIXTEEN_NINE }

/** Native camera ROI coordinates, after hardware binning and before software binning. */
data class StackRoiContext(
    val key: StackSessionKey,
    val sensorWidth: Int,
    val sensorHeight: Int,
    val budgetBytes: Long,
    val calibrationBytes: Long = 0,
    val calibrated: Boolean = false,
    val minWidth: Int = 8,
    val minHeight: Int = 8,
    val widthStep: Int = 8,
    val heightStep: Int = 8,
    val blockedReason: String? = null
) {
    val layoutIdentity: String get() = "${key.camera}|${key.roi}|${key.format}|${key.hardwareBin}|${key.softwareBin}|${key.pixelTraits}|${key.target}|${key.demosaicAlgorithm}|$sensorWidth|$sensorHeight"
    fun withRoi(roi: Roi) = key.copy(width = roi.width, height = roi.height, roi = roi)
    fun issue(roi: Roi = key.roi): String? = LiveStackAdmission.issue(withRoi(roi), budgetBytes, calibrationBytes, calibrated)
    fun estimatedBytes(roi: Roi = key.roi): Long = LiveStackAdmission.workingBytes(withRoi(roi), calibrationBytes, calibrated)
}

object StackRoiPlanner {
    /** Largest admitted rectangle on the SDK/UI alignment grid for the chosen aspect.
     * No pixels are allocated. The same admission check is used when actually starting. */
    fun maximum(context: StackRoiContext, aspect: StackRoiAspect,
                centerX: Double = context.key.roi.x + context.key.roi.width / 2.0,
                centerY: Double = context.key.roi.y + context.key.roi.height / 2.0): Roi? {
        val sw = context.sensorWidth; val sh = context.sensorHeight
        val ws = context.widthStep; val hs = context.heightStep
        if (sw < 1 || sh < 1 || ws < 1 || hs < 1 || context.key.softwareBin < 1) return null
        val ratio = when (aspect) {
            StackRoiAspect.SENSOR -> sw.toDouble() / sh
            StackRoiAspect.SQUARE -> 1.0
            StackRoiAspect.FOUR_THREE -> 4.0 / 3
            StackRoiAspect.SIXTEEN_NINE -> 16.0 / 9
        }
        val minH = maxOf(context.minHeight, context.key.softwareBin, 2)
        val minW = maxOf(context.minWidth, context.key.softwareBin, 2)
        val alignedMinH = ((minH + hs - 1) / hs) * hs
        var lo = ceil(maxOf(minW.toDouble(), alignedMinH * ratio) / ws).toInt().coerceAtLeast(1)
        var hi = floor(minOf(sw.toDouble(), sh * ratio) / ws).toInt()
        var result: Roi? = null
        while (lo <= hi) {
            val mid = lo + (hi - lo) / 2
            val w = mid * ws
            val h = (floor(w / ratio / hs + 1e-9).toInt() * hs).coerceAtMost(sh)
            if (h < minH) { lo = mid + 1; continue }
            // Eight-pixel placement preserves CFA parity and existing camera-control steps.
            val x = ((centerX - w / 2.0).roundToInt().coerceIn(0, sw - w) / 8) * 8
            val y = ((centerY - h / 2.0).roundToInt().coerceIn(0, sh - h) / 8) * 8
            val candidate = Roi(x, y, w, h)
            if (context.issue(candidate) == null) { result = candidate; lo = mid + 1 }
            else hi = mid - 1
        }
        return result
    }
}
