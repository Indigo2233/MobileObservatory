package com.indigo.mobileobservatory.stacking

import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

internal object NativeCalibration {
    val loaded: Boolean = try {
        System.loadLibrary("calibration_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    fun apply(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        channels: Int,
        startOffset: Long,
        masterRowPitch: Long,
        subtraction: ByteBuffer?,
        flat: ByteBuffer?,
        cancelCheck: () -> Unit
    ): Boolean {
        if (!loaded) return false
        when (NativeCalibrationBridge.apply(
            src,
            dst,
            width,
            height,
            channels,
            startOffset,
            masterRowPitch,
            subtraction,
            flat,
            StackCancelCheck {
                cancelCheck()
                false
            }
        )) {
            0 -> return true
            -1 -> error("native_calibration_invalid")
            -2 -> throw IllegalArgumentException("calibration_nonfinite_light")
            -3 -> throw CancellationException("Calibration discarded")
            -4 -> throw IllegalArgumentException("calibration_nonfinite_output")
            else -> error("native_calibration")
        }
    }
}

private object NativeCalibrationBridge {
    external fun apply(
        src: FloatArray,
        dst: FloatArray,
        width: Int,
        height: Int,
        channels: Int,
        startOffset: Long,
        masterRowPitch: Long,
        subtraction: ByteBuffer?,
        flat: ByteBuffer?,
        cancelled: StackCancelCheck
    ): Int
}
