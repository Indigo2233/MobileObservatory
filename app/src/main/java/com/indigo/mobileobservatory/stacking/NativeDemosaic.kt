package com.indigo.mobileobservatory.stacking

import java.util.concurrent.CancellationException

internal object NativeDemosaic {
    fun bilinear(
        src: FloatArray,
        width: Int,
        height: Int,
        redX: Int,
        redY: Int,
        destination: FloatArray,
        cancelCheck: () -> Unit
    ): Boolean = invoke(
        NativeDemosaicBridge::bilinear, src, width, height, redX, redY, destination, cancelCheck
    )

    fun vng(
        src: FloatArray,
        width: Int,
        height: Int,
        redX: Int,
        redY: Int,
        destination: FloatArray,
        cancelCheck: () -> Unit
    ): Boolean = invoke(
        NativeDemosaicBridge::vng, src, width, height, redX, redY, destination, cancelCheck
    )

    private fun invoke(
        operation: (
            FloatArray,
            FloatArray,
            Int,
            Int,
            Int,
            Int,
            StackCancelCheck
        ) -> Int,
        src: FloatArray,
        width: Int,
        height: Int,
        redX: Int,
        redY: Int,
        destination: FloatArray,
        cancelCheck: () -> Unit
    ): Boolean {
        if (!loaded) return false
        val status = operation(
            src,
            destination,
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
            -2 -> throw IllegalArgumentException("Nonfinite CFA sample")
            -3 -> throw CancellationException("Demosaic discarded")
            -4 -> throw IllegalArgumentException("Demosaic output exceeds Float range")
            else -> error("native_demosaic")
        }
    }

    private val loaded: Boolean = try {
        System.loadLibrary("demosaic_jni")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }
}

private object NativeDemosaicBridge {
    external fun bilinear(
        src: FloatArray,
        destination: FloatArray,
        width: Int,
        height: Int,
        redX: Int,
        redY: Int,
        cancelled: StackCancelCheck
    ): Int

    external fun vng(
        src: FloatArray,
        destination: FloatArray,
        width: Int,
        height: Int,
        redX: Int,
        redY: Int,
        cancelled: StackCancelCheck
    ): Int
}
