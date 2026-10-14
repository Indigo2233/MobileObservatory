package com.indigo.mobileobservatory.astro

import org.junit.Assert.*
import org.junit.Test

class PointingOpticsConfigurationTest {
    private val primary = OpticsTrainConfig(OpticsTrainId.PRIMARY, FovInstrumentMode.SENSOR)
    private val secondary = OpticsTrainConfig(OpticsTrainId.SECONDARY, FovInstrumentMode.SENSOR)
    private fun configuration() = PointingOpticsConfiguration(primary, secondary,
        OpticsEquipment.defaultTelescopes, OpticsEquipment.defaultEyepieces, OpticsEquipment.defaultSensors,
        ConnectedOpticsGeometry(3.76f, 3008, 3008, deviceId = "main", axisPixelX = 1503.5, axisPixelY = 1503.5),
        ConnectedOpticsGeometry(2.9f, 1920, 1080, deviceId = "guide", axisPixelX = 959.5, axisPixelY = 539.5),
        confirmedTrains = OpticsTrainId.entries.toSet())

    @Test fun mainAndGuideUseTheirActualIndependentSensorGeometry() {
        val config = configuration()
        val main = config.sensorsFor(OpticsTrainId.PRIMARY).first()
        val guide = config.sensorsFor(OpticsTrainId.SECONDARY).first()
        assertEquals(3008, main.widthPx); assertEquals(1920, guide.widthPx)
        assertEquals(3.76, main.pixelSizeUm, .00001); assertEquals(2.9, guide.pixelSizeUm, .00001)
        assertTrue(config.computation(OpticsTrainId.PRIMARY)!!.rectWidthDeg!! > config.computation(OpticsTrainId.SECONDARY)!!.rectWidthDeg!!)
        val disconnected = config.copy(secondaryGeometry = null)
        assertNull(disconnected.computation(OpticsTrainId.SECONDARY))
        assertNotNull(disconnected.computation(OpticsTrainId.PRIMARY))
        // A manually chosen catalog sensor remains usable without either camera connection.
        assertNotNull(disconnected.copy(secondary = secondary.copy(sensorId = "ccd_imx585")).computation(OpticsTrainId.SECONDARY))
    }

    @Test fun hardwareBinPreservesFullFieldAndRoiUpdatesOnlyThatField() {
        val config = configuration()
        val binned = config.copy(primaryGeometry = config.primaryGeometry!!.copy(widthPx = 1504, heightPx = 1504, binning = 2))
        assertEquals(config.computation(OpticsTrainId.PRIMARY)!!.rectWidthDeg!!,
            binned.computation(OpticsTrainId.PRIMARY)!!.rectWidthDeg!!, 1e-10)
        assertNull(config.changeTo(binned, OpticsTrainId.PRIMARY))
        val cropped = binned.copy(primaryGeometry = binned.primaryGeometry!!.copy(widthPx = 752))
        assertTrue(cropped.computation(OpticsTrainId.PRIMARY)!!.rectWidthDeg!! < binned.computation(OpticsTrainId.PRIMARY)!!.rectWidthDeg!!)
        assertNull(binned.changeTo(cropped, OpticsTrainId.PRIMARY))
        assertEquals(config.computation(OpticsTrainId.SECONDARY), cropped.computation(OpticsTrainId.SECONDARY))
    }

    @Test fun unknownDefaultsOrEndpointChoiceNeverCertifyArrival() {
        val config = configuration().copy(confirmedTrains = emptySet())
        val sensor = PointingOpticsSelection(OpticsTrainId.PRIMARY, PointingOpticsEndpoint.SENSOR)
        assertNull(config.verificationField(sensor, 0.0))
        assertNull(configuration().verificationField(null, 0.0))
        assertNull(configuration().verificationField(sensor.copy(endpoint = PointingOpticsEndpoint.EYEPIECE)))
        val unknownRoll = configuration().verificationField(sensor)!!
        assertFalse(unknownRoll.contains(40.0, 50.0, 40.0, 50.0, .001))
        assertTrue(configuration().verificationField(sensor, 0.0)!!.contains(40.0, 50.0, 40.0, 50.0, .001))
    }

    @Test fun eyepieceHasCircularFieldWithoutPretendingToKnowImageRoll() {
        val config = configuration().copy(primary = primary.copy(mode = FovInstrumentMode.EYEPIECE))
        val field = config.verificationField(PointingOpticsSelection(OpticsTrainId.PRIMARY, PointingOpticsEndpoint.EYEPIECE))!!
        assertNotNull(field.circleDiameterDeg); assertNull(field.widthDeg)
        assertTrue(field.contains(40.0, 50.0, 40.0, 50.0, .001))
        val changed = config.copy(primary = config.primary.copy(eyepieceId = "ep_7_82", customEyepieceFl = "7"))
        assertNull(config.changeTo(changed, OpticsTrainId.PRIMARY))
        assertNotEquals(config.computation(OpticsTrainId.PRIMARY), changed.computation(OpticsTrainId.PRIMARY))
    }

    @Test fun opticalInstallationChangesInvalidateOnlyAffectedPath() {
        val config = configuration()
        val telescope = config.copy(primary = primary.copy(telescopeId = "scope_203_2032"))
        assertEquals(PointingOpticsChange.TELESCOPE, config.changeTo(telescope, OpticsTrainId.PRIMARY))
        assertNull(config.changeTo(telescope, OpticsTrainId.SECONDARY))
        val swapped = config.copy(secondaryGeometry = config.secondaryGeometry!!.copy(deviceId = "new-guide"))
        assertEquals(PointingOpticsChange.IMAGE_INSTALLATION, config.changeTo(swapped, OpticsTrainId.SECONDARY))
        assertNull(config.changeTo(swapped, OpticsTrainId.PRIMARY))
        val endpoint = config.copy(primary = primary.copy(mode = FovInstrumentMode.EYEPIECE))
        assertEquals(PointingOpticsChange.IMAGE_INSTALLATION, config.changeTo(endpoint, OpticsTrainId.PRIMARY))
        assertNull(config.changeTo(config, OpticsTrainId.PRIMARY)) // Display rotation is not an input.
    }

    @Test fun mainReferenceFocalNeverReadsLegacyGuideKeyOrAssumedDefaults() {
        val legacy = MapOpticsPrefs(floats = mapOf(StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM to 250f))
        val unknown = PointingOpticsConfiguration.load(legacy)
        assertNull(unknown.primaryReferenceFocalLengthMm())
        assertEquals("500", unknown.primary.customTelescopeFl)
        val confirmed = MapOpticsPrefs(strings = mapOf("star_map_telescope_id" to "scope_custom", "star_map_custom_scope_fl" to "1200"),
            bools = mapOf(PointingOpticsConfiguration.confirmationKey(OpticsTrainId.PRIMARY) to true),
            floats = mapOf(StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM to 250f))
        assertEquals(1200f, PointingOpticsConfiguration.load(confirmed).primaryReferenceFocalLengthMm()!!, 0f)
        assertNull(configuration().copy(primary = primary.copy(telescopeId = "scope_custom", customTelescopeFl = "NaN")).primaryReferenceFocalLengthMm())
        assertNull(configuration().copy(primary = primary.copy(telescopeFocalLengthOverrideMm = Double.NaN)).primaryReferenceFocalLengthMm())
        assertEquals(OpticsTrainId.SECONDARY, StarMapOpticsPrefs.loadActive(legacy))
    }

    @Test fun legacySolveHintChangesOnlyWithExplicitSecondarySensorFocalEdit() {
        assertFalse(StarMapOpticsPrefs.shouldWritePlateFocalLength(secondary, secondary.copy(sensorId = "ccd_imx533")))
        assertFalse(StarMapOpticsPrefs.shouldWritePlateFocalLength(secondary.copy(mode = FovInstrumentMode.EYEPIECE), secondary))
        assertFalse(StarMapOpticsPrefs.shouldWritePlateFocalLength(primary, primary.copy(customTelescopeFl = "600")))
        assertTrue(StarMapOpticsPrefs.shouldWritePlateFocalLength(secondary, secondary.copy(customTelescopeFl = "600")))
        assertTrue(StarMapOpticsPrefs.shouldWritePlateFocalLength(secondary, secondary.copy(telescopeId = "scope_150_750")))
    }

    @Test fun invalidActualGeometryDoesNotTurnIntoCatalogOrMainFallback() {
        val config = configuration().copy(secondaryGeometry = ConnectedOpticsGeometry(Float.POSITIVE_INFINITY, 1920, 1080, deviceId = "guide"))
        assertNull(config.computation(OpticsTrainId.SECONDARY))
        assertNull(config.verificationField(PointingOpticsSelection(OpticsTrainId.SECONDARY, PointingOpticsEndpoint.SENSOR), 0.0))
    }

    @Test fun perTrainFocalOverridesSurviveSharedCatalogEditsAndPersistence() {
        val original = configuration()
        val changed = original.copy(primary = primary.copy(telescopeFocalLengthOverrideMm = 600.0),
            secondary = secondary.copy(telescopeFocalLengthOverrideMm = 500.0),
            telescopes = original.telescopes.map { if (it.id == "scope_80_500") it.copy(focalLengthMm = 600.0) else it })
        assertEquals(600.0, changed.primary.focalLengthMm(changed.telescopes)!!, 0.0)
        assertEquals(500.0, changed.secondary.focalLengthMm(changed.telescopes)!!, 0.0)
        assertEquals(original.computation(OpticsTrainId.SECONDARY), changed.computation(OpticsTrainId.SECONDARY))
        assertNull(original.changeTo(changed, OpticsTrainId.SECONDARY))
        assertEquals(PointingOpticsChange.TELESCOPE, original.changeTo(changed, OpticsTrainId.PRIMARY))
        val written = StarMapOpticsPrefs.snapshot(changed.primary, changed.secondary, OpticsTrainId.SECONDARY, true)
        val restored = PointingOpticsConfiguration.load(MapOpticsPrefs(strings = written.strings,
            bools = mapOf(PointingOpticsConfiguration.confirmationKey(OpticsTrainId.PRIMARY) to true)))
        assertEquals(600f, restored.primaryReferenceFocalLengthMm()!!, 0f)
        assertEquals(500.0, restored.secondary.focalLengthMm(restored.telescopes)!!, 0.0)
    }
}
