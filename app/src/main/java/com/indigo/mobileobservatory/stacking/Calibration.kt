package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.PixelFormat
import org.json.JSONObject
import java.util.UUID
import kotlin.math.abs

enum class CalibrationType { BIAS, DARK, FLAT }

/** Layout describes the *output* sensor mosaic, independent of container endian/alignment. */
data class CalibrationGeometry(val width: Int, val height: Int, val layout: String, val validBits: Int) {
    init {
        // Geometry is metadata, also used while inspecting large SER headers. Allocation
        // budgets belong at processing/master-loading entry points, not in this value object.
        require(width > 0 && height > 0 && width.toLong() * height <= Int.MAX_VALUE / channels) { "calibration_dimensions" }
        require(layout in setOf("MONO", "RGB", "RGGB", "GRBG", "GBRG", "BGGR")) { "calibration_layout" }
        require(validBits in 1..16) { "calibration_depth" }
        if (isBayer) require(width >= 2 && height >= 2) { "calibration_dimensions" }
    }
    val channels: Int get() = if (layout == "RGB") 3 else 1
    val sampleCount: Int get() = width * height * channels
    val isBayer: Boolean get() = layout != "MONO" && layout != "RGB"
    internal fun group(sample: Int): Int = if (channels == 3) sample % 3 else if (isBayer)
        ((sample / width) and 1) * 2 + (sample % width and 1) else 0
    fun check(image: LinearImage) {
        require(image.width == width && image.height == height && image.channels == channels) { "calibration_geometry_mismatch" }
    }
    companion object {
        fun from(width: Int, height: Int, format: PixelFormat, traits: PixelTraits): CalibrationGeometry {
            require(traits.binning >= 1 && traits.roiX >= 0 && traits.roiY >= 0)
            require(!format.isBayer || traits.bayerPatternAtOutput || traits.binning % 2 == 1) { "calibration_unknown_cfa" }
            val layout = if (format.isRgb) "RGB" else if (format.isMono) "MONO" else {
                val x = (if (format.name.startsWith("BAYER_GR") || format.name.startsWith("BAYER_BG")) 1 else 0) xor
                    (if (traits.bayerPatternAtOutput) 0 else traits.roiX and 1)
                val y = (if (format.name.startsWith("BAYER_GB") || format.name.startsWith("BAYER_BG")) 1 else 0) xor
                    (if (traits.bayerPatternAtOutput) 0 else traits.roiY and 1)
                when { y == 0 && x == 0 -> "RGGB"; y == 0 -> "GRBG"; x == 0 -> "GBRG"; else -> "BGGR" }
            }
            return CalibrationGeometry(width, height, layout, traits.validBits ?: format.nativeBits)
        }
    }
}

/** Null means genuinely unknown, never a guessed camera setting. ROI is before software binning. */
data class CalibrationCapture(
    val camera: String? = null, val exposureUs: Double? = null, val gain: Double? = null,
    val offset: Double? = null, val roiX: Int? = null, val roiY: Int? = null,
    val hardwareBin: Int? = null, val filter: String? = null, val readout: String? = null,
    val temperatureC: Double? = null
) {
    init {
        require(exposureUs == null || exposureUs.isFinite() && exposureUs > 0) { "calibration_exposure" }
        require(listOf(gain, offset, temperatureC).all { it == null || it.isFinite() }) { "calibration_capture" }
        require(roiX == null || roiX >= 0); require(roiY == null || roiY >= 0)
        require(hardwareBin == null || hardwareBin >= 1)
    }
    companion object {
        fun from(key: StackSessionKey, temperatureC: Double? = null) = CalibrationCapture(key.camera, key.exposureUs, key.gain.toDouble(),
            key.offset?.toDouble(), key.roi.x, key.roi.y, key.hardwareBin, key.filter, key.readout, temperatureC)
    }
}

typealias CalibrationCaptureContext = CalibrationCapture

data class CalibrationDescriptor(
    val id: String, val name: String, val type: CalibrationType, val geometry: CalibrationGeometry,
    val capture: CalibrationCapture, val sourceIdentity: String, val frameCount: Long,
    val createdAtMillis: Long, val biasId: String? = null
) {
    init {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "calibration_id" }
        require(name.length <= 1024 && sourceIdentity.length <= 8192) { "calibration_metadata_size" }
        require(frameCount in 1..10_000_000L && createdAtMillis >= 0) { "calibration_metadata" }
        require(type == CalibrationType.FLAT || biasId == null) { "Dark masters must retain their bias" }
    }
}

/** Masters cannot expose mutable retained pixels. Library/builder transfer exclusive ownership. */
class CalibrationMaster internal constructor(val descriptor: CalibrationDescriptor, private val samples: FloatArray) {
    init {
        require(samples.size == descriptor.geometry.sampleCount) { "calibration_buffer" }
        require(samples.all { it.isFinite() && if (descriptor.type == CalibrationType.FLAT) it > 1e-6f else it in 0f..1f }) {
            "calibration_invalid_master"
        }
    }
    val memoryBytes: Long get() = samples.size * 4L
    internal fun sample(index: Int): Float = samples[index]
    fun imageCopy(): LinearImage = descriptor.geometry.let { LinearImage(it.width, it.height, it.channels, samples.copyOf()) }
}

/** Compares only science-relevant fields. Bias exposure and flat exposure need not equal lights;
 * dark exposure MUST equal lights because this implementation never rescales darks. */
private fun captureIssues(type: CalibrationType, master: CalibrationCapture, light: CalibrationCapture): Pair<List<String>, List<String>> {
    val mismatch = mutableListOf<String>(); val unknown = mutableListOf<String>()
    fun compare(field: String, a: Any?, b: Any?, required: Boolean = true) {
        val left = if (a is String) a.takeIf { it.isNotBlank() } else a
        val right = if (b is String) b.takeIf { it.isNotBlank() } else b
        if (left == null || right == null) { if (required || left != null || right != null) unknown += field; return }
        val same = if (left is Double && right is Double) abs(left - right) <= 1e-6 else left == right
        if (!same) mismatch += field
    }
    compare("camera", master.camera, light.camera)
    compare("gain", master.gain, light.gain)
    compare("offset", master.offset, light.offset, false)
    compare("roiX", master.roiX, light.roiX); compare("roiY", master.roiY, light.roiY)
    compare("hardwareBin", master.hardwareBin, light.hardwareBin)
    compare("readout", master.readout, light.readout)
    if (type == CalibrationType.DARK) {
        compare("exposureUs", master.exposureUs, light.exposureUs)
        // Unknown temperatures are explicitly acknowledged too; an uncooled camera has no guarantee.
        if (master.temperatureC == null || light.temperatureC == null) unknown += "temperatureC"
        else if (abs(master.temperatureC - light.temperatureC) > 2.0) mismatch += "temperatureC"
    }
    if (type == CalibrationType.FLAT) compare("filter", master.filter, light.filter)
    return mismatch to unknown
}

/** Optional immutable snapshot. Flat masters already contain a bias-corrected (when provided),
 * parity/channel-normalized response. Dark masters contain bias: subtract dark OR bias, never both.
 * Calibration precedes debayer, matching https://siril.readthedocs.io/en/stable/preprocessing/calibration.html . */
class CalibrationSelection(
    val bias: CalibrationMaster? = null, val dark: CalibrationMaster? = null, val flat: CalibrationMaster? = null,
    val allowUnknownCapture: Boolean = false
) {
    /** Worker-owned calibrated sensor output for the live synchronous pipeline. */
    class Workspace {
        private var pixels = FloatArray(0)
        private var inUse = false
        private var allocations = 0L
        val retainedBytes: Long @Synchronized get() = pixels.size * 4L
        val arrayAllocations: Long @Synchronized get() = allocations
        @Synchronized internal fun acquire(size: Int): FloatArray {
            check(!inUse) { "Calibration workspace is already in use" }
            if (pixels.size != size) { pixels = FloatArray(size); allocations++ }
            inUse = true
            return pixels
        }
        @Synchronized internal fun finish() { inUse = false }
        @Synchronized fun release() {
            check(!inUse) { "Cannot release calibration workspace while processing" }
            pixels = FloatArray(0)
        }
    }

    init {
        require(bias == null || bias.descriptor.type == CalibrationType.BIAS)
        require(dark == null || dark.descriptor.type == CalibrationType.DARK)
        require(flat == null || flat.descriptor.type == CalibrationType.FLAT)
    }
    val enabled: Boolean get() = bias != null || dark != null || flat != null
    val memoryBytes: Long get() = listOfNotNull(bias, dark, flat).sumOf { it.memoryBytes }
    fun unknownWarnings(geometry: CalibrationGeometry, capture: CalibrationCapture): List<String> =
        listOfNotNull(bias, dark, flat).flatMap { master ->
            require(master.descriptor.geometry == geometry) { "calibration_geometry_mismatch" }
            captureIssues(master.descriptor.type, master.descriptor.capture, capture).second.map { "${master.descriptor.type}:$it" }
        }.distinct()

    fun validate(geometry: CalibrationGeometry, capture: CalibrationCapture) {
        for (master in listOfNotNull(bias, dark, flat)) {
            require(master.descriptor.geometry == geometry) { "calibration_geometry_mismatch" }
            val (mismatch, unknown) = captureIssues(master.descriptor.type, master.descriptor.capture, capture)
            require(mismatch.isEmpty()) { "calibration_settings_mismatch: ${master.descriptor.type} ${mismatch.joinToString()}" }
            require(allowUnknownCapture || unknown.isEmpty()) { "calibration_unknown_settings: ${unknown.joinToString()}" }
        }
    }

    fun apply(sensor: LinearImage, geometry: CalibrationGeometry, capture: CalibrationCapture,
              cancelCheck: () -> Unit = {}): LinearImage =
        applyImpl(sensor, geometry, capture, null, cancelCheck)

    /** A file crop retains its original sensor coordinates. Read matching master samples
     * directly, including the demosaic border, without copying full-size masters or lights. */
    fun applyRegion(sensor: LinearImage, geometry: CalibrationGeometry, capture: CalibrationCapture,
                    region: PixelRoi, cancelCheck: () -> Unit = {}): LinearImage {
        region.checked(geometry.width, geometry.height)
        require(sensor.width == region.width && sensor.height == region.height && sensor.channels == geometry.channels) {
            "calibration_geometry_mismatch"
        }
        validate(geometry, capture)
        cancelCheck()
        if (!enabled) return sensor
        val subtraction = dark ?: bias
        val output = FloatArray(sensor.pixels.size)
        val rowSamples = region.width * sensor.channels
        for (y in 0 until region.height) {
            cancelCheck()
            val original = ((region.top + y) * geometry.width + region.left) * sensor.channels
            val start = y * rowSamples
            for (x in 0 until rowSamples) {
                val input = sensor.pixels[start + x]
                require(input.isFinite()) { "calibration_nonfinite_light" }
                val index = original + x
                val value = (input.toDouble() - (subtraction?.sample(index)?.toDouble() ?: 0.0)) / (flat?.sample(index) ?: 1f)
                output[start + x] = value.toFloat()
                require(output[start + x].isFinite()) { "calibration_nonfinite_output" }
            }
        }
        return sensor.copy(pixels = output)
    }

    /** Returns workspace-owned calibrated pixels. Disabled calibration passes [sensor] through. */
    internal fun applyBorrowed(sensor: LinearImage, geometry: CalibrationGeometry,
                               capture: CalibrationCapture, workspace: Workspace,
                               cancelCheck: () -> Unit = {}): LinearImage =
        applyImpl(sensor, geometry, capture, workspace, cancelCheck)

    private fun applyImpl(sensor: LinearImage, geometry: CalibrationGeometry,
                          capture: CalibrationCapture, workspace: Workspace?,
                          cancelCheck: () -> Unit): LinearImage {
        geometry.check(sensor)
        validate(geometry, capture)
        cancelCheck()
        if (!enabled) return sensor
        val subtraction = dark ?: bias
        val output = workspace?.acquire(sensor.pixels.size) ?: FloatArray(sensor.pixels.size)
        try {
            for (i in output.indices) {
                if (i % 16384 == 0) cancelCheck()
                val input = sensor.pixels[i]
                require(input.isFinite()) { "calibration_nonfinite_light" }
                val response = flat?.sample(i) ?: 1f
                val value = (input.toDouble() - (subtraction?.sample(i)?.toDouble() ?: 0.0)) / response
                output[i] = value.toFloat()
                require(output[i].isFinite()) { "calibration_nonfinite_output" }
            }
            return sensor.copy(pixels = output)
        } finally {
            workspace?.finish()
        }
    }

    fun metadata(): Map<String, String> = mapOf(
        "calibrated" to enabled.toString(),
        "calibration" to JSONObject().apply {
            put("schema", 1); put("domain", "sensor_before_demosaic")
            put("darkContainsBias", true); put("darkExposureScaling", false)
            put("darkTemperatureToleranceC", 2.0)
            put("allowUnknownCapture", allowUnknownCapture)
            put("subtraction", (dark ?: bias)?.descriptor?.id ?: JSONObject.NULL)
            for ((name, master) in listOf("bias" to bias, "dark" to dark, "flat" to flat))
                put(name, master?.let { CalibrationJson.descriptor(it.descriptor) } ?: JSONObject.NULL)
        }.toString()
    )
}

/** Streaming double-precision mean: memory does not grow with the number of frames. */
object CalibrationBuilder {
    fun estimatedBuildBytes(geometry: CalibrationGeometry, withBias: Boolean = false): Long =
        geometry.sampleCount * (16L + if (withBias) 4L else 0L) + geometry.width.toLong() * geometry.height * 8L + 4L * 1024 * 1024

    fun build(type: CalibrationType, geometry: CalibrationGeometry, capture: CalibrationCapture,
              frameCount: Long, readFrame: (Long) -> LinearImage, name: String = "", sourceIdentity: String = "",
              bias: CalibrationMaster? = null, allowUnknownCapture: Boolean = false,
              memoryBudgetBytes: Long = 256L * 1024 * 1024,
              cancelCheck: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }): CalibrationMaster {
        require(geometry.width.toLong() * geometry.height <= LinearImage.MAX_PIXELS &&
            estimatedBuildBytes(geometry, bias != null) <= memoryBudgetBytes) { "memory_budget" }
        require(frameCount in 1..10_000_000L) { "calibration_frame_count" }
        require(type == CalibrationType.FLAT || bias == null) { "Dark and bias masters are uncalibrated sensor means" }
        require(allowUnknownCapture || captureIssues(type, capture, capture).second.isEmpty()) { "calibration_unknown_settings" }
        if (bias != null) CalibrationSelection(bias = bias, allowUnknownCapture = allowUnknownCapture).validate(geometry, capture)
        cancelCheck()
        val mean = DoubleArray(geometry.sampleCount)
        for (frame in 0 until frameCount) {
            cancelCheck()
            val image = readFrame(frame)
            geometry.check(image)
            for (i in mean.indices) {
                if (i % 16384 == 0) cancelCheck()
                val value = image.pixels[i]
                require(value.isFinite() && value in 0f..1f) { "calibration_invalid_source" }
                if (type == CalibrationType.FLAT) require(value > 0f && value < 1f) { "calibration_clipped_flat" }
                mean[i] += (value.toDouble() - mean[i]) / (frame + 1).toDouble()
            }
            progress(frame + 1, frameCount)
        }
        if (type == CalibrationType.FLAT) {
            val sums = DoubleArray(if (geometry.isBayer) 4 else geometry.channels)
            val counts = IntArray(sums.size)
            for (i in mean.indices) {
                if (i % 16384 == 0) cancelCheck()
                mean[i] -= bias?.sample(i)?.toDouble() ?: 0.0
                require(mean[i].isFinite() && mean[i] > 1e-6) { "calibration_invalid_flat_response" }
                val group = geometry.group(i); sums[group] += mean[i]; counts[group]++
            }
            for (g in sums.indices) { require(counts[g] > 0); sums[g] /= counts[g].toDouble() }
            for (i in mean.indices) {
                if (i % 16384 == 0) cancelCheck()
                mean[i] /= sums[geometry.group(i)]
                require(mean[i].isFinite() && mean[i] > 1e-6) { "calibration_invalid_flat_response" }
            }
        }
        cancelCheck()
        val descriptor = CalibrationDescriptor(UUID.randomUUID().toString(), name, type, geometry, capture,
            sourceIdentity, frameCount, System.currentTimeMillis(), bias?.descriptor?.id)
        return CalibrationMaster(descriptor, FloatArray(mean.size) { mean[it].toFloat() })
    }
}
