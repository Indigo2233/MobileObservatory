package com.indigo.mobileobservatory.stacking

/** Check acquisition geometry before constructing calibration metadata or allocating pixels. */
internal object LiveStackAdmission {
    fun issue(key: StackSessionKey, budget: Long, calibrationBytes: Long = 0, calibrated: Boolean = false): String? {
        if (key.width < 1 || key.height < 1 || key.softwareBin !in 1..4 || key.hardwareBin < 1 ||
            key.width < key.softwareBin || key.height < key.softwareBin) return "processing_size"
        val pixels = key.width.toLong() * key.height
        // Native samples still need valid geometry and a bounded owned byte packet; only
        // calibration/demosaic scratch is tiled before RGB binning.
        if (pixels > LinearImage.MAX_PIXELS) return "processing_size"
        if (pixels * key.format.bytesPerPixel > LiveStackSession.MAX_INPUT_BYTES) return "input_budget"
        if (key.format.isBayer && key.hardwareBin % 2 == 0 && !key.pixelTraits.bayerPatternAtOutput) return "bayer_hardware_bin"
        return if (workingBytes(key, calibrationBytes, calibrated) > budget) "memory_budget" else null
    }

    fun workingBytes(key: StackSessionKey, calibrationBytes: Long, calibrated: Boolean): Long {
        return LiveStackMemory.workingBytes(key, calibrationBytes, calibrated)
    }

    fun recommendHardwareBin(key: StackSessionKey, sensorWidth: Int, sensorHeight: Int,
                             supported: List<Int>, budget: Long): Int? = supported.distinct().sorted().firstOrNull { bin ->
        bin > key.hardwareBin && sensorWidth / bin > 0 && sensorHeight / bin > 0 &&
            issue(key.copy(width = sensorWidth / bin, height = sensorHeight / bin,
                hardwareBin = bin, softwareBin = 1), budget) == null
    }
}
