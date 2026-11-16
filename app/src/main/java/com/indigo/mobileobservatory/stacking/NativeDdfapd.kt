package com.indigo.mobileobservatory.stacking

import java.util.concurrent.CancellationException

internal object NativeDdfapd {
    fun demosaic(
        src: FloatArray,
        buffers: DdfapdDemosaicer.Buffers,
        destination: FloatArray,
        width: Int,
        height: Int,
        redX: Int,
        redY: Int,
        cancelCheck: () -> Unit
    ): Boolean {
        if (!loaded) return false
        val status = NativeDdfapdBridge.demosaic(
            src,
            destination,
            buffers.horizontal,
            buffers.vertical,
            buffers.deltaH,
            buffers.deltaV,
            buffers.direction,
            width,
            height,
            redX,
            redY,
            StackCancelCheck {
                cancelCheck()
                false
            }
        )
        when (status) {
            0 -> return true
            -2 -> throw IllegalArgumentException("Non-finite Bayer sample")
            -3 -> throw CancellationException("Demosaic discarded")
            else -> error("native_ddfapd")
        }
    }

    private val loaded: Boolean = try {
        System.loadLibrary("demosaic_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }
}

private object NativeDdfapdBridge {
    external fun demosaic(
        src: FloatArray,
        destination: FloatArray,
        horizontal: DoubleArray,
        vertical: DoubleArray,
        deltaH: DoubleArray,
        deltaV: DoubleArray,
        direction: ByteArray,
        width: Int,
        height: Int,
        redX: Int,
        redY: Int,
        cancelled: StackCancelCheck
    ): Int
}
