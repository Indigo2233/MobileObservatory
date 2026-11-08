package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.PixelFormat
import org.json.JSONObject
import java.nio.ByteBuffer
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

internal interface CalibrationSamples {
    val size: Int
    val heapBytes: Long
    val nativeBytes: Long
    fun get(index: Int): Float
    fun copy(): FloatArray
}

private class HeapCalibrationSamples(private val values: FloatArray) : CalibrationSamples {
    override val size: Int get() = values.size
    override val heapBytes: Long get() = values.size * 4L
    override val nativeBytes: Long get() = 0L
    override fun get(index: Int): Float = values[index]
    override fun copy(): FloatArray = values.copyOf()
}

private class NativeCalibrationSamples(
    private val buffer: ByteBuffer,
    private val offset: Int,
    override val size: Int
) : CalibrationSamples {
    override val heapBytes: Long get() = 0L
    override val nativeBytes: Long get() = size * 4L
    override fun get(index: Int): Float = buffer.getFloat(offset + index * 4)
    override fun copy(): FloatArray = FloatArray(size) { get(it) }
}

/** Masters cannot expose mutable retained pixels. Library masters use read-only mapped Float32
 * samples, so selecting calibration does not consume the Java heap used by the stack engines. */
class CalibrationMaster internal constructor(
    val descriptor: CalibrationDescriptor,
    private val samples: CalibrationSamples
) {
    internal constructor(descriptor: CalibrationDescriptor, samples: FloatArray) :
        this(descriptor, HeapCalibrationSamples(samples))

    init {
        require(samples.size == descriptor.geometry.sampleCount) { "calibration_buffer" }
        require((0 until samples.size).all {
            val value = samples.get(it)
            value.isFinite() && if (descriptor.type == CalibrationType.FLAT) value > 1e-6f else value in 0f..1f
        }) {
            "calibration_invalid_master"
        }
    }
    /** Logical Float32 payload size, including read-only mapped file data. */
    val memoryBytes: Long get() = samples.size * 4L
    val heapBytes: Long get() = samples.heapBytes
    val nativeBytes: Long get() = samples.nativeBytes
    internal fun sample(index: Int): Float = samples.get(index)
    fun imageCopy(): LinearImage = descriptor.geometry.let {
        LinearImage(it.width, it.height, it.channels, samples.copy())
    }

    companion object {
        internal fun native(descriptor: CalibrationDescriptor, buffer: ByteBuffer) =
            CalibrationMaster(descriptor, NativeCalibrationSamples(buffer, 0, descriptor.geometry.sampleCount))
    }
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

internal fun unknownCaptureFields(type: CalibrationType, capture: CalibrationCapture): List<String> =
    captureIssues(type, capture, capture).second.distinct()

/** Optional immutable snapshot. Flat masters already contain a bias-corrected (when provided),
 * parity/channel-normalized response. Dark masters contain bias: subtract dark OR bias, never both.
 * Calibration precedes debayer, matching https://siril.readthedocs.io/en/stable/preprocessing/calibration.html . */
class CalibrationSelection(
    val bias: CalibrationMaster? = null, val dark: CalibrationMaster? = null, val flat: CalibrationMaster? = null
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
    val heapBytes: Long get() = listOfNotNull(bias, dark, flat).sumOf { it.heapBytes }
    val nativeBytes: Long get() = listOfNotNull(bias, dark, flat).sumOf { it.nativeBytes }
    fun unknownWarnings(geometry: CalibrationGeometry, capture: CalibrationCapture): List<String> =
        listOfNotNull(bias, dark, flat).flatMap { master ->
            require(master.descriptor.geometry == geometry) { "calibration_geometry_mismatch" }
            captureIssues(master.descriptor.type, master.descriptor.capture, capture).second.map { "${master.descriptor.type}:$it" }
        }.distinct()

    fun validate(geometry: CalibrationGeometry, capture: CalibrationCapture) {
        for (master in listOfNotNull(bias, dark, flat)) {
            require(master.descriptor.geometry == geometry) { "calibration_geometry_mismatch" }
            val (mismatch, _) = captureIssues(master.descriptor.type, master.descriptor.capture, capture)
            require(mismatch.isEmpty()) { "calibration_settings_mismatch: ${master.descriptor.type} ${mismatch.joinToString()}" }
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

    /** Region form of [applyBorrowed]: [sensor] is a sub-rectangle of the master geometry, and the
     * calibrated tile is written into [workspace] so a tiled worker keeps one output buffer. */
    internal fun applyRegionBorrowed(sensor: LinearImage, geometry: CalibrationGeometry,
                                     capture: CalibrationCapture, region: PixelRoi,
                                     workspace: Workspace,
                                     cancelCheck: () -> Unit = {}): LinearImage {
        region.checked(geometry.width, geometry.height)
        require(sensor.width == region.width && sensor.height == region.height &&
            sensor.channels == geometry.channels) { "calibration_geometry_mismatch" }
        validate(geometry, capture)
        cancelCheck()
        if (!enabled) return sensor
        val subtraction = dark ?: bias
        val output = workspace.acquire(sensor.pixels.size)
        try {
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
        } finally {
            workspace.finish()
        }
    }

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
            put("subtraction", (dark ?: bias)?.descriptor?.id ?: JSONObject.NULL)
            for ((name, master) in listOf("bias" to bias, "dark" to dark, "flat" to flat))
                put(name, master?.let { CalibrationJson.descriptor(it.descriptor) } ?: JSONObject.NULL)
        }.toString()
    )
}

/** Streaming arithmetic mean: one float accumulator and the decoded frame, independent of
 * frame count. Flat normalization reuses the accumulator after the mean pass. */
object CalibrationBuilder {
    private const val MAX_FLAT_CLIPPED_FRACTION = .01
    private const val MAX_FLAT_INVALID_FRACTION = .01

    /** Two decoded frames and their native byte copies may overlap while the next frame is
     * decoded, plus the mean accumulator, which becomes the output master. The optional bias
     * is owned by CalibrationSelection and counted there. */
    fun estimatedBuildBytes(geometry: CalibrationGeometry, @Suppress("UNUSED_PARAMETER") withBias: Boolean = false,
                            withSampleCounts: Boolean = false): Long {
        val frame = geometry.sampleCount * 4L
        val rawBytes = geometry.sampleCount * 2L
        val accumulator = geometry.sampleCount * 4L
        val sampleCounts = if (withSampleCounts) geometry.sampleCount * 2L else 0L
        return 2L * (frame + rawBytes) + accumulator + sampleCounts + 4L * 1024 * 1024
    }

    fun build(type: CalibrationType, geometry: CalibrationGeometry, capture: CalibrationCapture,
              frameCount: Long, readFrame: (Long) -> LinearImage, name: String = "", sourceIdentity: String = "",
              bias: CalibrationMaster? = null,
              memoryBudgetBytes: Long = 256L * 1024 * 1024,
              cancelCheck: () -> Unit = {}, progress: (Long, Long) -> Unit = { _, _ -> }): CalibrationMaster {
        require(geometry.width.toLong() * geometry.height <= LinearImage.MAX_PIXELS &&
            estimatedBuildBytes(geometry, bias != null, type == CalibrationType.FLAT) <= memoryBudgetBytes) { "memory_budget" }
        require(frameCount in 1..10_000_000L) { "calibration_frame_count" }
        require(type != CalibrationType.FLAT || frameCount <= 65_535L) { "calibration_frame_count" }
        require(type == CalibrationType.FLAT || bias == null) { "Dark and bias masters are uncalibrated sensor means" }
        if (bias != null) CalibrationSelection(bias = bias).validate(geometry, capture)
        cancelCheck()
        val output = FloatArray(geometry.sampleCount)
        val flatSamples = if (type == CalibrationType.FLAT) ShortArray(geometry.sampleCount) else null
        for (frame in 0 until frameCount) {
            cancelCheck()
            val image = readFrame(frame)
            geometry.check(image)
            val divisor = (frame + 1).toDouble()
            var clipped = 0
            for (index in output.indices) {
                if (index % 16384 == 0) cancelCheck()
                val value = image.pixels[index]
                require(value.isFinite() && value in 0f..1f) { "calibration_invalid_source" }
                if (flatSamples != null) {
                    if (value == 0f || value == 1f) {
                        clipped++
                        continue
                    }
                    val count = flatSamples[index].toInt() and 0xffff
                    check(count < 65_535) { "calibration_frame_count" }
                    val next = count + 1
                    output[index] += (value - output[index]) / next
                    flatSamples[index] = next.toShort()
                } else {
                    output[index] = (output[index] + (value - output[index]) / divisor).toFloat()
                }
            }
            require(clipped.toDouble() <= geometry.sampleCount * MAX_FLAT_CLIPPED_FRACTION) {
                "calibration_clipped_flat"
            }
            progress(frame + 1, frameCount)
        }
        if (type == CalibrationType.FLAT) {
            val groupCount = if (geometry.isBayer) 4 else geometry.channels
            val sums = DoubleArray(groupCount)
            val counts = LongArray(groupCount)
            val valid = BooleanArray(output.size)
            var invalid = 0
            for (index in output.indices) {
                if (index % 16384 == 0) cancelCheck()
                val corrected = output[index].toDouble() - (bias?.sample(index)?.toDouble() ?: 0.0)
                val usable = flatSamples!![index].toInt() and 0xffff != 0 && corrected.isFinite() && corrected > 1e-6
                if (!usable) {
                    invalid++
                    continue
                }
                valid[index] = true
                val group = geometry.group(index)
                sums[group] += corrected
                counts[group]++
                output[index] = corrected.toFloat()
            }
            require(invalid.toDouble() <= geometry.sampleCount * MAX_FLAT_INVALID_FRACTION) {
                "calibration_invalid_flat_response"
            }
            for (group in 0 until groupCount) {
                require(counts[group] > 0) { "calibration_invalid_flat_response" }
                sums[group] /= counts[group].toDouble()
            }
            for (index in output.indices) {
                if (index % 16384 == 0) cancelCheck()
                if (valid[index]) output[index] =
                    (output[index].toDouble() / sums[geometry.group(index)]).toFloat()
                else output[index] = Float.NaN
            }
            // A few dead or hot pixels are common. Repair them only from valid samples of the
            // same CFA/colour group, and only when the unusable area stays below the safety cap.
            for (index in output.indices) if (!valid[index]) {
                if (index % 16384 == 0) cancelCheck()
                val repaired = sameGroupNeighborMedian(geometry, output, valid, index)
                    ?: error("calibration_invalid_flat_response")
                require(repaired.isFinite() && repaired > 1e-6f) { "calibration_invalid_flat_response" }
                output[index] = repaired
            }
        }
        cancelCheck()
        val descriptor = CalibrationDescriptor(UUID.randomUUID().toString(), name, type, geometry, capture,
            sourceIdentity, frameCount, System.currentTimeMillis(), bias?.descriptor?.id)
        return CalibrationMaster(descriptor, output)
    }

    private fun sameGroupNeighborMedian(geometry: CalibrationGeometry, normalized: FloatArray,
                                        valid: BooleanArray, index: Int): Float? {
        val channels = geometry.channels
        val pixel = index / channels
        val channel = index % channels
        val x = pixel % geometry.width
        val y = pixel / geometry.width
        val group = geometry.group(index)
        val values = FloatArray(24)
        var count = 0
        for (dy in -2..2) for (dx in -2..2) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx
            val ny = y + dy
            if (nx !in 0 until geometry.width || ny !in 0 until geometry.height) continue
            val candidate = (ny * geometry.width + nx) * channels + channel
            if (geometry.group(candidate) != group || !valid[candidate]) continue
            values[count++] = normalized[candidate]
        }
        if (count == 0) return null
        values.sort(0, count)
        return if (count % 2 == 1) values[count / 2]
        else (values[count / 2 - 1] + values[count / 2]) / 2f
    }
}
