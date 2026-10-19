package com.indigo.mobileobservatory.stacking

import com.indigo.mobileobservatory.recording.SERReader

/** Tile only the native calibration/demosaic stage. Accumulation still receives one owned,
 * linear image. Software Bin averages reconstructed RGB, never the raw Bayer mosaic. */
internal object SerRegionDecoder {
    fun read(reader: SERReader, index: Int, plan: OfflineRegionPlan, calibration: CalibrationSelection,
             geometry: CalibrationGeometry, capture: CalibrationCapture,
             workspace: DdfapdDemosaicer.Workspace, cancelCheck: () -> Unit): LinearImage {
        require(plan.width >= 12 && plan.height >= 12 && plan.width.toLong() * plan.height <= LinearImage.MAX_PIXELS) { "processing_size" }
        cancelCheck()
        val channels = plan.channels; val bin = plan.softwareBin
        val output = FloatArray(plan.width * plan.height * channels)
        for (oy in 0 until plan.height step OfflineRegionPlan.TILE) {
            for (ox in 0 until plan.width step OfflineRegionPlan.TILE) {
                cancelCheck()
                val w = minOf(OfflineRegionPlan.TILE, plan.width - ox)
                val h = minOf(OfflineRegionPlan.TILE, plan.height - oy)
                val tile = PixelRoi(plan.region.left + ox * bin, plan.region.top + oy * bin, w * bin, h * bin)
                // Tiny tail tiles borrow enough neighbours for the reader's minimum extent.
                var read = plan.readRegion(tile)
                if (read.width < 12) read = read.copy(left = (read.right - 12).coerceAtLeast(0), width = 12)
                if (read.height < 12) read = read.copy(top = (read.bottom - 12).coerceAtLeast(0), height = 12)
                val sensor = reader.readSensorRegion(index, read, maxPixels = LinearImage.MAX_PIXELS, cancelCheck = cancelCheck)
                    ?: error(reader.lastError ?: "read_failed")
                val calibrated = calibration.applyRegion(sensor, geometry, capture, read, cancelCheck)
                val traits = reader.sensorTraits.copy(roiX = read.left, roiY = read.top, bayerPatternAtOutput = false)
                val rgb = LinearPixelDecoder.demosaicSensor(calibrated, reader.sensorFormat, traits, plan.algorithm, workspace, cancelCheck)
                for (y in 0 until h) {
                    cancelCheck()
                    for (x in 0 until w) for (channel in 0 until channels) {
                        var sum = 0.0
                        for (by in 0 until bin) for (bx in 0 until bin) {
                            val sx = tile.left - read.left + x * bin + bx
                            val sy = tile.top - read.top + y * bin + by
                            sum += rgb.pixels[(sy * read.width + sx) * channels + channel]
                        }
                        output[((oy + y) * plan.width + ox + x) * channels + channel] = (sum / (bin * bin)).toFloat()
                    }
                }
            }
        }
        return LinearImage(plan.width, plan.height, channels, output)
    }
}
