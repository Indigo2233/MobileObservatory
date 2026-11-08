package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.camera.FrameData

/** One science worker owns the output and bounded native tiles. Calibration and CFA
 * reconstruction precede RGB binning, including when the camera itself stays at Bin 1. */
internal class LiveFrameDecoder {
    private val workspace = SerRegionDecoder.Workspace()
    private var output = FloatArray(0)
    private var compactOutput: CompactLinearImage16? = null
    private var compactTile = FloatArray(0)

    val retainedBytes: Long get() = output.size * 4L + (compactOutput?.storageBytes ?: 0L) +
        compactTile.size * 4L + workspace.retainedBytes
    val arrayAllocations: Long get() = workspace.arrayAllocations + outputAllocations
    private var outputAllocations = 0L

    fun decode(frame: FrameData, key: StackSessionKey, calibration: CalibrationSelection,
               capture: CalibrationCapture, cancelCheck: () -> Unit = {}): LinearImage =
        decodeImpl(frame, key, calibration, capture, compact = false, cancelCheck = cancelCheck) as LinearImage

    fun decodeInput(frame: FrameData, key: StackSessionKey, calibration: CalibrationSelection,
                    capture: CalibrationCapture, cancelCheck: () -> Unit = {}): LinearSamples =
        decodeImpl(frame, key, calibration, capture, compact = usesCompactInput(key), cancelCheck = cancelCheck)

    private fun decodeImpl(frame: FrameData, key: StackSessionKey, calibration: CalibrationSelection,
                           capture: CalibrationCapture, compact: Boolean, cancelCheck: () -> Unit): LinearSamples {
        require(frame.width == key.width && frame.height == key.height && frame.pixelFormat == key.format) {
            "source_geometry_changed"
        }
        val traits = key.pixelTraits.copy(roiX = key.roi.x, roiY = key.roi.y, binning = key.hardwareBin)
        require(!frame.pixelFormat.isBayer || traits.bayerPatternAtOutput || traits.binning % 2 == 1) {
            "bayer_hardware_bin"
        }
        val plan = plan(key, calibration.heapBytes, calibration.enabled)
        require(plan.width > 0 && plan.height > 0 &&
            frame.width.toLong() * frame.height <= LinearImage.MAX_PIXELS) { "processing_size" }
        require(frame.data.size.toLong() >= frame.width.toLong() * frame.height * frame.bytesPerPixel) {
            "Truncated raw image"
        }
        val geometry = CalibrationGeometry.from(frame.width, frame.height, frame.pixelFormat, traits)
        calibration.validate(geometry, capture)
        cancelCheck()
        val size = plan.width * plan.height * plan.channels
        val encoded = if (compact) {
            output = FloatArray(0)
            val previous = compactOutput
            val image = if (previous != null && previous.width == plan.width && previous.height == plan.height &&
                previous.channels == plan.channels) previous else {
                CompactLinearImage16(plan.width, plan.height, plan.channels).also {
                    compactOutput = it
                    outputAllocations += 3
                }
            }
            val tileSize = OfflineRegionPlan.TILE * OfflineRegionPlan.TILE * plan.channels
            if (compactTile.size != tileSize) {
                compactTile = FloatArray(tileSize)
                outputAllocations++
            }
            image.beginFrame()
            image
        } else {
            compactOutput = null
            compactTile = FloatArray(0)
            if (output.size != size) {
                output = FloatArray(size)
                outputAllocations++
            }
            null
        }
        val bin = plan.softwareBin
        val channels = plan.channels
        val sensorChannels = if (frame.pixelFormat.isRgb) 3 else 1
        for (oy in 0 until plan.height step OfflineRegionPlan.TILE) {
            val h = minOf(OfflineRegionPlan.TILE, plan.height - oy)
            for (ox in 0 until plan.width step OfflineRegionPlan.TILE) {
                cancelCheck()
                val w = minOf(OfflineRegionPlan.TILE, plan.width - ox)
                val tile = PixelRoi(ox * bin, oy * bin, w * bin, h * bin)
                val read = plan.readRegion(tile)
                val sensor = LinearPixelDecoder.decodeSensorRegionBorrowed(
                    frame.data, frame.width * sensorChannels, read, frame.pixelFormat, traits,
                    LinearImage.MAX_PIXELS, workspace.sensor, cancelCheck)
                val calibrated = calibration.applyRegionBorrowed(sensor, geometry, capture, read,
                    workspace.calibration, cancelCheck)
                // The tile is expressed on the delivered pixel grid. Sensor offsets apply
                // only when the adapter has not already supplied its output CFA.
                val tileTraits = traits.copy(
                    roiX = read.left + if (traits.bayerPatternAtOutput) 0 else traits.roiX,
                    roiY = read.top + if (traits.bayerPatternAtOutput) 0 else traits.roiY,
                    binning = 1, bayerPatternAtOutput = false)
                val rgb = if (frame.pixelFormat.isBayer)
                    LinearPixelDecoder.demosaicSensorInto(calibrated, frame.pixelFormat, key.demosaicAlgorithm,
                        workspace.rgb(read.width * read.height * 3), workspace.demosaic, tileTraits, cancelCheck)
                    else calibrated
                for (y in 0 until h) {
                    cancelCheck()
                    for (x in 0 until w) for (channel in 0 until channels) {
                        var sum = 0.0
                        for (by in 0 until bin) for (bx in 0 until bin) {
                            val sx = tile.left - read.left + x * bin + bx
                            val sy = tile.top - read.top + y * bin + by
                            sum += rgb.pixels[(sy * read.width + sx) * channels + channel]
                        }
                        val value = (sum / (bin * bin)).toFloat()
                        if (encoded == null) output[((oy + y) * plan.width + ox + x) * channels + channel] = value
                        else compactTile[(y * w + x) * channels + channel] = value
                    }
                }
                encoded?.writeBlock(ox, oy, w, h, compactTile, cancelCheck)
            }
        }
        return encoded ?: LinearImage(plan.width, plan.height, channels, output)
    }

    fun release() {
        output = FloatArray(0)
        compactOutput = null
        compactTile = FloatArray(0)
        workspace.release()
    }

    companion object {
        fun usesCompactInput(key: StackSessionKey): Boolean = key.target == StackTarget.DEEP_SKY &&
            !key.format.isMono && (key.hardwareBin > 1 || key.softwareBin > 1)

        fun outputStorageBytes(key: StackSessionKey): Long {
            val plan = plan(key)
            return if (usesCompactInput(key)) CompactLinearImage16.storageBytes(plan.width, plan.height, plan.channels)
            else plan.width.toLong() * plan.height * plan.channels * 4
        }

        fun compactScratchBytes(key: StackSessionKey): Long = if (usesCompactInput(key))
            OfflineRegionPlan.TILE.toLong() * OfflineRegionPlan.TILE * 3 * 4 else 0

        fun overheadBytes(key: StackSessionKey, calibrationBytes: Long = 0, calibrated: Boolean = false): Long =
            plan(key, calibrationBytes, calibrated).overheadBytes + compactScratchBytes(key)

        fun plan(key: StackSessionKey, calibrationBytes: Long = 0, calibrated: Boolean = false) =
            OfflineRegionPlan(key.width, key.height, key.format, PixelRoi(0, 0, key.width, key.height),
                key.demosaicAlgorithm, key.target, key.softwareBin, calibrationBytes, calibrated)
    }
}
