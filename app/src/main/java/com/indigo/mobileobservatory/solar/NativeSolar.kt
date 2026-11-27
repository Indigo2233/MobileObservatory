package com.indigo.mobileobservatory.solar

import java.io.File

interface SolarNativeObserver {
    fun isCancelled(): Boolean
    fun onProgress(value: Float)
}

data class NativeSolarPreview(
    val width: Int,
    val height: Int,
    val pixels: ByteArray
)

data class NativeSolarAnalysis(
    val width: Int,
    val height: Int,
    val traceRows: DoubleArray,
    val previewPixels: ByteArray,
    val traceConstant: Double,
    val traceLinear: Double,
    val traceQuadratic: Double,
    val confidence: Double,
    val lineDepth: Double,
    val validColumns: Int,
    val residualPixels: Double,
    val illuminatedFirst: Int,
    val illuminatedLast: Int,
    val roiTop: Int,
    val roiHeight: Int
)

data class NativeSolarReconstruction(
    val displayPng: String?,
    val linearPng: String?,
    val linearFits: String?,
    val width: Int,
    val height: Int,
    val frameStride: Int,
    val sourceFrames: Int,
    val warnings: Array<String>,
    val geometryCorrectionApplied: Boolean,
    val scanBandingApplied: Int,
    val scanBandingWidthPixels: Int,
    val scanRowsAligned: Boolean,
    val oscillationDetected: Boolean,
    val oscillationPeriodPixels: Double,
    val oscillationAmplitudePixels: Double,
    val syntheticFlatStrength: Int,
    val physicalFlatApplied: Boolean
)

internal object SolarScanNative {
    const val UNKNOWN_SYSTEM_MEMORY_HEADROOM = -1L

    private val loaded: Boolean = runCatching {
        System.loadLibrary("solar_scan_jni")
        true
    }.getOrDefault(false)

    fun preview(source: File, observer: SolarNativeObserver): NativeSolarPreview {
        check(loaded) { "Solar Rust library is unavailable." }
        return checkNotNull(NativeSolarBridge.preview(source.absolutePath, observer)) {
            "Solar Rust library returned no preview."
        }
    }

    fun analyze(source: File, observer: SolarNativeObserver): NativeSolarAnalysis {
        check(loaded) { "Solar Rust library is unavailable." }
        return checkNotNull(NativeSolarBridge.analyze(source.absolutePath, observer)) {
            "Solar Rust library returned no analysis."
        }
    }

    fun trialCoverage(source: File, observer: SolarNativeObserver): SolarTrialCoverage {
        check(loaded) { "Solar Rust library is unavailable." }
        val values = checkNotNull(NativeSolarBridge.trialCoverage(source.absolutePath, observer))
        check(values.size == 6)
        return SolarTrialCoverage(values[0], values[1], values[2], values[3], values[4], values[5])
    }

    fun reconstruct(
        source: File,
        outputDirectory: File,
        settings: SolarReconstructionSettings,
        memoryHeadroomBytes: Long,
        chinese: Boolean,
        observer: SolarNativeObserver
    ): NativeSolarReconstruction {
        check(loaded) { "Solar Rust library is unavailable." }
        val products = settings.products.fold(0) { mask, product ->
            mask or when (product) {
                SolarOutputProduct.DISPLAY_PNG -> 1
                SolarOutputProduct.LINEAR_PNG -> 2
                SolarOutputProduct.LINEAR_FITS -> 4
            }
        }
        return checkNotNull(
            NativeSolarBridge.reconstruct(
                source.absolutePath,
                outputDirectory.absolutePath,
                settings.lineShiftPixels,
                settings.frameStride,
                settings.flipHorizontal,
                settings.flipVertical,
                settings.rotationDegrees.toDouble(),
                products,
                memoryHeadroomBytes,
                when (settings.scanCorrection.bandingAxis) {
                    SolarBandingAxis.OFF -> 0
                    SolarBandingAxis.VERTICAL -> 1
                    SolarBandingAxis.HORIZONTAL -> 2
                    SolarBandingAxis.AUTO -> 3
                },
                settings.scanCorrection.bandingWidthPixels ?: 0,
                settings.scanCorrection.correctPeriodicOscillation,
                settings.scanCorrection.alignScanRows,
                if (settings.flat.synthetic) settings.flat.syntheticStrength else 0,
                settings.flat.physicalSourcePath.orEmpty(),
                chinese,
                observer
            )
        ) { "Solar Rust library returned no reconstruction." }
    }
}

private object NativeSolarBridge {
    external fun preview(sourcePath: String, observer: SolarNativeObserver): NativeSolarPreview?
    external fun analyze(sourcePath: String, observer: SolarNativeObserver): NativeSolarAnalysis?
    external fun trialCoverage(sourcePath: String, observer: SolarNativeObserver): IntArray?
    external fun reconstruct(
        sourcePath: String,
        outputDirectory: String,
        lineShift: Double,
        frameStride: Int,
        flipHorizontal: Boolean,
        flipVertical: Boolean,
        rotationDegrees: Double,
        products: Int,
        memoryHeadroom: Long,
        scanBanding: Int,
        scanBandingWidth: Int,
        scanOscillation: Boolean,
        scanJagged: Boolean,
        syntheticFlatStrength: Int,
        flatSourcePath: String,
        chinese: Boolean,
        observer: SolarNativeObserver
    ): NativeSolarReconstruction?
}
