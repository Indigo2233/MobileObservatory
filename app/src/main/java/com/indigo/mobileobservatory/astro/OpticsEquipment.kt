package com.indigo.mobileobservatory.astro

/**
 * Telescope / eyepiece / sensor combos for star-map FOV overlays
 * (Stellarium Plus–style Field of View Simulator).
 *
 * Eyepiece true FOV (°) ≈ apparentFOV × eyepieceFL / telescopeFL
 * Magnification ≈ telescopeFL / eyepieceFL
 * Sensor FOV uses [OpticsFov.rectangleDegrees].
 */
data class TelescopeSpec(
    val id: String,
    val name: String,
    val focalLengthMm: Double,
    val apertureMm: Double? = null
)

data class EyepieceSpec(
    val id: String,
    val name: String,
    val focalLengthMm: Double,
    val apparentFovDeg: Double
)

data class SensorSpec(
    val id: String,
    val name: String,
    val pixelSizeUm: Double,
    val widthPx: Int,
    val heightPx: Int
)

enum class FovInstrumentMode {
    EYEPIECE,
    SENSOR
}

data class FovComputation(
    val mode: FovInstrumentMode,
    val circleDeg: Double? = null,
    val rectWidthDeg: Double? = null,
    val rectHeightDeg: Double? = null,
    val magnification: Double? = null
) {
    val hasOverlay: Boolean
        get() = when (mode) {
            FovInstrumentMode.EYEPIECE -> circleDeg != null && circleDeg > 0
            FovInstrumentMode.SENSOR ->
                rectWidthDeg != null && rectHeightDeg != null &&
                    rectWidthDeg > 0 && rectHeightDeg > 0
        }
}

object OpticsEquipment {
    const val CUSTOM_SENSOR_ID = "ccd_custom"

    val defaultTelescopes: List<TelescopeSpec> = listOf(
        TelescopeSpec("scope_80_500", "80 mm f/6.3", 500.0, 80.0),
        TelescopeSpec("scope_102_660", "102 mm f/6.5", 660.0, 102.0),
        TelescopeSpec("scope_150_750", "150 mm f/5", 750.0, 150.0),
        TelescopeSpec("scope_200_1000", "200 mm f/5", 1000.0, 200.0),
        TelescopeSpec("scope_203_2032", "203 mm f/10 SCT", 2032.0, 203.0),
        TelescopeSpec("scope_custom", "Custom", 500.0, null)
    )

    val defaultEyepieces: List<EyepieceSpec> = listOf(
        EyepieceSpec("ep_40_68", "40 mm · 68°", 40.0, 68.0),
        EyepieceSpec("ep_32_52", "32 mm · 52°", 32.0, 52.0),
        EyepieceSpec("ep_25_50", "25 mm · 50°", 25.0, 50.0),
        EyepieceSpec("ep_20_68", "20 mm · 68°", 20.0, 68.0),
        EyepieceSpec("ep_13_82", "13 mm · 82°", 13.0, 82.0),
        EyepieceSpec("ep_10_60", "10 mm · 60°", 10.0, 60.0),
        EyepieceSpec("ep_7_82", "7 mm · 82°", 7.0, 82.0),
        EyepieceSpec("ep_custom", "Custom", 25.0, 50.0)
    )

    val defaultSensors: List<SensorSpec> = listOf(
        SensorSpec("ccd_imx071", "Nikon D5100 (IMX071)", 4.78, 4928, 3264),
        SensorSpec("ccd_imx455", "IMX455 9576×6388", 3.76, 9576, 6388),
        SensorSpec("ccd_imx571", "IMX571 6224×4168", 3.76, 6224, 4168),
        SensorSpec("ccd_imx533", "IMX533 3008×3008", 3.76, 3008, 3008),
        SensorSpec("ccd_imx585", "IMX585 3840×2160", 2.9, 3840, 2160),
        SensorSpec("ccd_imx678", "IMX678 3840×2160", 2.0, 3840, 2160),
        SensorSpec("ccd_imx183", "IMX183 5472×3648", 2.4, 5472, 3648),
        SensorSpec("ccd_imx294", "IMX294 4144×2822", 4.63, 4144, 2822),
        SensorSpec("ccd_imx464", "IMX464 2712×1538", 2.9, 2712, 1538),
        SensorSpec("ccd_imx662", "IMX662 1920×1080", 2.9, 1920, 1080),
        SensorSpec("ccd_imx676", "IMX676 3552×3552", 2.0, 3552, 3552),
        SensorSpec("ccd_imx715", "IMX715 3864×2192", 1.45, 3864, 2192),
        SensorSpec("ccd_imx174", "IMX174 1936×1216", 5.86, 1936, 1216),
        SensorSpec("ccd_imx178", "IMX178 3096×2080", 2.4, 3096, 2080),
        SensorSpec("ccd_mn34230", "MN34230 (ASI1600)", 3.8, 4656, 3520),
        SensorSpec(CUSTOM_SENSOR_ID, "Custom", 3.76, 1920, 1080)
    )

    const val CONNECTED_SENSOR_ID = "ccd_connected"

    fun eyepieceTrueFovDeg(
        telescopeFocalLengthMm: Double,
        eyepieceFocalLengthMm: Double,
        apparentFovDeg: Double
    ): Double? {
        if (!telescopeFocalLengthMm.isFinite() || telescopeFocalLengthMm <= 0.0) return null
        if (!eyepieceFocalLengthMm.isFinite() || eyepieceFocalLengthMm <= 0.0) return null
        if (!apparentFovDeg.isFinite() || apparentFovDeg <= 0.0) return null
        return apparentFovDeg * eyepieceFocalLengthMm / telescopeFocalLengthMm
    }

    fun magnification(
        telescopeFocalLengthMm: Double,
        eyepieceFocalLengthMm: Double
    ): Double? {
        if (!telescopeFocalLengthMm.isFinite() || telescopeFocalLengthMm <= 0.0) return null
        if (!eyepieceFocalLengthMm.isFinite() || eyepieceFocalLengthMm <= 0.0) return null
        return telescopeFocalLengthMm / eyepieceFocalLengthMm
    }

    fun computeEyepiece(
        telescopeFocalLengthMm: Double,
        eyepiece: EyepieceSpec
    ): FovComputation {
        val fov = eyepieceTrueFovDeg(
            telescopeFocalLengthMm,
            eyepiece.focalLengthMm,
            eyepiece.apparentFovDeg
        )
        val mag = magnification(telescopeFocalLengthMm, eyepiece.focalLengthMm)
        return FovComputation(
            mode = FovInstrumentMode.EYEPIECE,
            circleDeg = fov,
            magnification = mag
        )
    }

    fun computeSensor(
        telescopeFocalLengthMm: Double,
        sensor: SensorSpec
    ): FovComputation {
        val rect = OpticsFov.rectangleDegrees(
            pixelSizeUm = sensor.pixelSizeUm,
            focalLengthMm = telescopeFocalLengthMm,
            widthPx = sensor.widthPx,
            heightPx = sensor.heightPx
        )
        return FovComputation(
            mode = FovInstrumentMode.SENSOR,
            rectWidthDeg = rect?.first,
            rectHeightDeg = rect?.second
        )
    }

    fun connectedSensor(
        pixelSizeUm: Float?,
        widthPx: Int,
        heightPx: Int,
        displayName: String
    ): SensorSpec? {
        val px = pixelSizeUm?.toDouble() ?: return null
        if (!(px > 0.0) || widthPx <= 0 || heightPx <= 0) return null
        return SensorSpec(CONNECTED_SENSOR_ID, displayName, px, widthPx, heightPx)
    }

    fun catalogPresets(): List<SensorSpec> =
        defaultSensors.filter { it.id != CUSTOM_SENSOR_ID }

    /**
     * Match a saved image / pixel size to a catalog chip. Same pixel pitch
     * (IMX455 vs IMX571) is disambiguated by frame size, including a 90° rotation.
     */
    fun matchCatalogSensor(
        widthPx: Int = 0,
        heightPx: Int = 0,
        pixelSizeUm: Double? = null
    ): SensorSpec? {
        val presets = catalogPresets()
        val sizeHits = if (widthPx > 0 && heightPx > 0) {
            presets.filter { sensor ->
                (sensor.widthPx == widthPx && sensor.heightPx == heightPx) ||
                    (sensor.widthPx == heightPx && sensor.heightPx == widthPx)
            }
        } else {
            emptyList()
        }
        val pixelHits = if (pixelSizeUm != null && pixelSizeUm > 0.0) {
            val pool = sizeHits.ifEmpty { presets }
            pool.filter { kotlin.math.abs(it.pixelSizeUm - pixelSizeUm) <= 0.03 }
        } else {
            emptyList()
        }
        return when {
            pixelHits.size == 1 -> pixelHits.first()
            sizeHits.size == 1 -> sizeHits.first()
            else -> null
        }
    }
}
