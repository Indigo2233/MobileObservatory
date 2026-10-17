package com.indigo.mobileobservatory.stacking

/** Check acquisition geometry before constructing calibration metadata or allocating pixels. */
internal object LiveStackAdmission {
    fun issue(key: StackSessionKey, budget: Long, calibrationBytes: Long = 0, calibrated: Boolean = false): String? {
        if (key.width < 1 || key.height < 1 || key.softwareBin < 1 ||
            key.width < key.softwareBin || key.height < key.softwareBin) return "processing_size"
        val pixels = key.width.toLong() * key.height
        // Software binning happens AFTER native sensor calibration and demosaicing. It cannot
        // make an oversized native input safe by itself.
        if (pixels > LinearImage.MAX_PIXELS) return "processing_size"
        if (pixels * key.format.bytesPerPixel > LiveStackSession.MAX_INPUT_BYTES) return "input_budget"
        if (key.format.isBayer && key.hardwareBin % 2 == 0 && !key.pixelTraits.bayerPatternAtOutput) return "bayer_hardware_bin"
        return if (workingBytes(key, calibrationBytes, calibrated) > budget) "memory_budget" else null
    }

    fun workingBytes(key: StackSessionKey, calibrationBytes: Long, calibrated: Boolean): Long {
        val channels = if (key.format.isMono) 1 else 3
        val pixels = key.width.toLong() * key.height
        val width = key.width / key.softwareBin
        val height = key.height / key.softwareBin
        val inputExpansion = (pixels - width.toLong() * height).coerceAtLeast(0) *
            (key.format.bytesPerPixel * 3L + (channels + 1) * 4L)
        val engine = if (key.target == StackTarget.PLANETARY) LivePlanetaryEngine.estimateWorkingBytes(width, height, channels)
            else DeepSkyStacker.estimateWorkingBytes(width, height, channels)
        return engine + inputExpansion + calibrationBytes +
            LinearPixelDecoder.demosaicExtraBytes(key.width, key.height, key.format, key.demosaicAlgorithm) +
            if (calibrated) pixels * (if (key.format.isRgb) 3 else 1) * 4L else 0L
    }

    fun recommendHardwareBin(key: StackSessionKey, sensorWidth: Int, sensorHeight: Int,
                             supported: List<Int>, budget: Long): Int? = supported.distinct().sorted().firstOrNull { bin ->
        bin > key.hardwareBin && sensorWidth / bin > 0 && sensorHeight / bin > 0 &&
            issue(key.copy(width = sensorWidth / bin, height = sensorHeight / bin,
                hardwareBin = bin, softwareBin = 1), budget) == null
    }
}
