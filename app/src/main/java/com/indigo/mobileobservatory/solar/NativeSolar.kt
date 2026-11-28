package com.indigo.mobileobservatory.solar

import java.io.File
import com.indigo.mobileobservatory.camera.FrameData

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
    val physicalFlatApplied: Boolean,
    val additionalPaths: Array<String?>
)

internal object SolarScanNative {
    const val UNKNOWN_SYSTEM_MEMORY_HEADROOM = -1L
    private const val MAX_SER_FRAME_PIXELS = 8 * 1024 * 1024

    /** Reduce only the spatial axis to fit the MJSol'Ex SER reader's frame limit. */
    fun spectralRoiInput(frame: FrameData): FrameData {
        require(frame.pixelFormat.isMono && frame.width >= 32 && frame.height in 8..16_384)
        val maxColumns = minOf(MAX_SER_FRAME_PIXELS / frame.height, 16_384)
        if (frame.width <= maxColumns) return frame
        val step = (frame.width - 1) / maxColumns + 1
        val width = (frame.width - 1) / step + 1
        val bytesPerPixel = frame.bytesPerPixel
        val data = ByteArray(width * frame.height * bytesPerPixel)
        for (row in 0 until frame.height) {
            val sourceRow = row * frame.width * bytesPerPixel
            val outputRow = row * width * bytesPerPixel
            for (column in 0 until width) {
                val source = sourceRow + column * step * bytesPerPixel
                val output = outputRow + column * bytesPerPixel
                frame.data.copyInto(data, output, source, source + bytesPerPixel)
            }
        }
        return frame.copy(data = data, width = width)
    }

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

    fun analyze(source: File, observer: SolarNativeObserver, seed: SolarLineSeed? = null): NativeSolarAnalysis {
        check(loaded) { "Solar Rust library is unavailable." }
        return checkNotNull(NativeSolarBridge.analyze(source.absolutePath, seed?.column ?: -1, seed?.row ?: -1, observer)) {
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
        observer: SolarNativeObserver,
        seed: SolarLineSeed? = null
    ): NativeSolarReconstruction {
        check(loaded) { "Solar Rust library is unavailable." }
        val products = settings.products.fold(0) { mask, product -> mask or product.nativeMask }
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
                settings.correctDisk,
                settings.invertProminences,
                settings.prominenceStrength,
                settings.wavelengthAngstrom,
                settings.dispersionAngstromPerPixel,
                settings.wingOffsetPixels,
                settings.cropFactor ?: 0.0,
                seed?.column ?: -1,
                seed?.row ?: -1,
                chinese,
                observer
            )
        ) { "Solar Rust library returned no reconstruction." }
    }
}

private object NativeSolarBridge {
    external fun preview(sourcePath: String, observer: SolarNativeObserver): NativeSolarPreview?
    external fun analyze(sourcePath: String, column: Int, row: Int, observer: SolarNativeObserver): NativeSolarAnalysis?
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
        correctDisk: Boolean,
        invertProminences: Boolean,
        prominenceStrength: Int,
        wavelengthAngstrom: Double,
        dispersionAngstromPerPixel: Double,
        wingOffsetPixels: Double,
        cropFactor: Double,
        seedColumn: Int,
        seedRow: Int,
        chinese: Boolean,
        observer: SolarNativeObserver
    ): NativeSolarReconstruction?
}
