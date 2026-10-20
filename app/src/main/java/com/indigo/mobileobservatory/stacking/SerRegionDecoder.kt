package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.recording.SERReader

/** Tile only the native calibration/demosaic stage. Accumulation still receives one owned,
 * linear image. Software Bin averages reconstructed RGB, never the raw Bayer mosaic. */
internal object SerRegionDecoder {
    /**
     * Buffers belonging to one science worker: native band bytes, one sensor tile, the calibrated
     * tile, the demosaiced RGB tile and the DDFAPD scratch. The native band is read once per tile
     * row and every tile across that row is decoded from it, so a full-field frame issues one
     * read per native row instead of one per tile row. Nothing here is retained between frames.
     */
    class Workspace {
        internal val demosaic = DdfapdDemosaicer.Workspace()
        internal val sensor = LinearPixelDecoder.SensorWorkspace()
        internal val calibration = CalibrationSelection.Workspace()
        private var band = ByteArray(0)
        private var rgb = FloatArray(0)
        private var bandAllocations = 0L
        private var rgbAllocations = 0L

        /** Native row reads issued so far; a tiled read of the same frame would be far higher. */
        var rowReads = 0L
            private set

        internal fun countRowReads(count: Int) { rowReads += count }

        /** Cumulative buffer reallocations; a steady-state stack stops increasing these. */
        val arrayAllocations: Long
            get() = bandAllocations + rgbAllocations + sensor.arrayAllocations +
                calibration.arrayAllocations + demosaic.arrayAllocations

        /** Byte buffer this worker currently holds for native band reads. */
        val bandBytes: Long get() = band.size.toLong()

        /** Reusable RGB tile payload, zero until the first Bayer decode. */
        val borrowedOutputBytes: Long get() = rgb.size.toLong() * 4L

        internal fun band(size: Int): ByteArray {
            if (band.size < size) { band = ByteArray(size); bandAllocations++ }
            return band
        }

        internal fun rgb(size: Int): FloatArray {
            if (rgb.size < size) { rgb = FloatArray(size); rgbAllocations++ }
            return rgb
        }

        fun release() {
            demosaic.release()
            sensor.release()
            calibration.release()
            band = ByteArray(0); rgb = FloatArray(0)
        }
    }

    fun read(reader: SERReader, index: Int, plan: OfflineRegionPlan, calibration: CalibrationSelection,
             geometry: CalibrationGeometry, capture: CalibrationCapture,
             workspace: Workspace, cancelCheck: () -> Unit): LinearImage {
        require(plan.width >= 12 && plan.height >= 12 && plan.width.toLong() * plan.height <= LinearImage.MAX_PIXELS) { "processing_size" }
        cancelCheck()
        return readInto(reader, index, plan, calibration, geometry, capture, workspace,
            FloatArray(plan.width * plan.height * plan.channels), cancelCheck = cancelCheck)
    }

    /** Fills caller-owned full-resolution storage. Luminance follows the exact Float RGB
     * rounding after native calibration, demosaicing and Bin, without a full RGB image. */
    fun readInto(reader: SERReader, index: Int, plan: OfflineRegionPlan, calibration: CalibrationSelection,
                 geometry: CalibrationGeometry, capture: CalibrationCapture,
                 workspace: Workspace, destination: FloatArray,
                 luminance: Boolean = false, cancelCheck: () -> Unit): LinearImage {
        require(plan.width >= 12 && plan.height >= 12 && plan.width.toLong() * plan.height <= LinearImage.MAX_PIXELS) { "processing_size" }
        val outputChannels = if (luminance) 1 else plan.channels
        require(destination.size.toLong() == plan.width.toLong() * plan.height * outputChannels) { "Invalid decoder destination" }
        cancelCheck()
        val channels = plan.channels; val bin = plan.softwareBin
        for (oy in 0 until plan.height step OfflineRegionPlan.TILE) {
            val h = minOf(OfflineRegionPlan.TILE, plan.height - oy)
            // One native band covers every tile in this output row, so each native row is read
            // once for the whole frame width instead of once per tile.
            val band = plan.bandRegion(oy)
            val bandSamples = band.width * reader.sensorChannelCount
            val bandBytes = workspace.band(reader.sensorRegionByteSize(band))
            workspace.countRowReads(band.height)
            if (!reader.readSensorRegionInto(index, band, bandBytes, LinearImage.MAX_PIXELS, cancelCheck))
                error(reader.lastError ?: "read_failed")
            for (ox in 0 until plan.width step OfflineRegionPlan.TILE) {
                cancelCheck()
                val w = minOf(OfflineRegionPlan.TILE, plan.width - ox)
                val tile = PixelRoi(plan.region.left + ox * bin, plan.region.top + oy * bin, w * bin, h * bin)
                // Tiny tail tiles borrow enough neighbours for the reader's minimum extent.
                var read = plan.readRegion(tile)
                if (read.width < 12) read = read.copy(left = (read.right - 12).coerceAtLeast(0), width = 12)
                if (read.height < 12) read = read.copy(top = (read.bottom - 12).coerceAtLeast(0), height = 12)
                val sensor = LinearPixelDecoder.decodeSensorRegionBorrowed(bandBytes, bandSamples,
                    read.copy(left = read.left - band.left, top = read.top - band.top),
                    reader.sensorFormat, reader.sensorTraits, LinearImage.MAX_PIXELS,
                    workspace.sensor, cancelCheck)
                val calibrated = calibration.applyRegionBorrowed(sensor, geometry, capture, read,
                    workspace.calibration, cancelCheck)
                val traits = reader.sensorTraits.copy(roiX = read.left, roiY = read.top, bayerPatternAtOutput = false)
                // Consume the tile synchronously before the next invocation overwrites it.
                val rgb = if (reader.sensorFormat.isBayer)
                    LinearPixelDecoder.demosaicSensorInto(calibrated, reader.sensorFormat, plan.algorithm,
                        workspace.rgb(read.width * read.height * 3), workspace.demosaic, traits, cancelCheck)
                    else calibrated
                for (y in 0 until h) {
                    cancelCheck()
                    for (x in 0 until w) {
                        var gray = 0f
                        for (channel in 0 until channels) {
                            var sum = 0.0
                            for (by in 0 until bin) for (bx in 0 until bin) {
                                val sx = tile.left - read.left + x * bin + bx
                                val sy = tile.top - read.top + y * bin + by
                                sum += rgb.pixels[(sy * read.width + sx) * channels + channel]
                            }
                            val value = (sum / (bin * bin)).toFloat()
                            if (!luminance) destination[((oy + y) * plan.width + ox + x) * channels + channel] = value
                            else if (channels == 1) gray = value
                            else gray = when (channel) {
                                0 -> value * .2126f
                                1 -> gray + value * .7152f
                                else -> gray + value * .0722f
                            }
                        }
                        if (luminance) destination[(oy + y) * plan.width + ox + x] = gray
                    }
                }
            }
        }
        return LinearImage(plan.width, plan.height, outputChannels, destination)
    }

}
