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
        SensorSpec("ccd_imx533", "IMX533 3008×3008", 3.76, 3008, 3008),
        SensorSpec("ccd_imx571", "IMX571 6224×4168", 3.76, 6224, 4168),
        SensorSpec("ccd_imx294", "IMX294 4144×2822", 4.63, 4144, 2822),
        SensorSpec("ccd_imx178", "IMX178 3096×2080", 2.4, 3096, 2080),
        SensorSpec("ccd_imx174", "IMX174 1936×1216", 5.86, 1936, 1216),
        SensorSpec("ccd_custom", "Custom", 3.75, 1920, 1080)
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
}
