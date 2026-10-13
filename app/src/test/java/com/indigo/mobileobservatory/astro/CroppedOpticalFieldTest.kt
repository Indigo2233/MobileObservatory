package com.indigo.mobileobservatory.astro

import com.indigo.mobileobservatory.camera.Roi
import com.indigo.mobileobservatory.pointing.VerificationField
import org.junit.Assert.*
import org.junit.Test

class CroppedOpticalFieldTest {
    private fun config(geometry: ConnectedOpticsGeometry) = PointingOpticsConfiguration(
        OpticsTrainConfig(OpticsTrainId.PRIMARY, FovInstrumentMode.SENSOR, telescopeFocalLengthOverrideMm = 500.0),
        OpticsTrainConfig(OpticsTrainId.SECONDARY, FovInstrumentMode.SENSOR), OpticsEquipment.defaultTelescopes,
        OpticsEquipment.defaultEyepieces, OpticsEquipment.defaultSensors, primaryGeometry = geometry,
        confirmedTrains = setOf(OpticsTrainId.PRIMARY))
    private val selection = PointingOpticsSelection(OpticsTrainId.PRIMARY, PointingOpticsEndpoint.SENSOR)

    @Test fun anOffsetCropCannotCertifyTheOldSymmetricQuarterFieldOutsideItsImage() {
        val geometry = ConnectedOpticsGeometry.fromSensorRoi(4f, 4000, 3000, Roi(1900, 1000, 1000, 1000), 1, "real")
        assertTrue(geometry.hasKnownAxis); assertEquals(99.5, geometry.axisPixelX!!, 0.0)
        assertEquals(499.5, geometry.axisPixelY!!, 0.0); assertFalse(geometry.axisCentered)
        val optics = config(geometry)
        val field = requireNotNull(optics.verificationField(selection, 0.0))
        val oldFull = requireNotNull(optics.computation(OpticsTrainId.PRIMARY))
        assertTrue(field.widthDeg!! < oldFull.rectWidthDeg!! * .21)
        val old = VerificationField(widthDeg = oldFull.rectWidthDeg, heightDeg = oldFull.rectHeightDeg, altitudeUpRollDeg = 0.0)
        // 200 raw pixels left of the full sensor axis falls outside this crop (only 100 exist).
        val outsideOffset = -Math.toDegrees(kotlin.math.atan(200 * .004 / 500))
        assertTrue(old.contains(0.0, 100.0, 0.0, 100.0 + outsideOffset, 0.0))
        assertFalse(field.contains(0.0, 100.0, 0.0, 100.0 + outsideOffset, 0.0))
        assertFalse(optics.currentFrameCentered(OpticsTrainId.PRIMARY))
    }

    @Test fun centeredFullFrameAndCenteredCropKeepTheirTrueFieldAndArrival() {
        val full = ConnectedOpticsGeometry.fromSensorRoi(4f, 4000, 3000, Roi(0, 0, 4000, 3000), 1, "real")
        val centered = ConnectedOpticsGeometry.fromSensorRoi(4f, 4000, 3000, Roi(1500, 1000, 1000, 1000), 1, "real")
        assertTrue(full.axisCentered); assertTrue(centered.axisCentered)
        for (geometry in listOf(full, centered)) {
            val optics = config(geometry)
            val field = requireNotNull(optics.verificationField(selection, 0.0))
            assertTrue(field.contains(20.0, 100.0, 20.0, 100.0, .0001))
            assertTrue(optics.currentFrameCentered(OpticsTrainId.PRIMARY))
            val old = requireNotNull(optics.computation(OpticsTrainId.PRIMARY))
            assertEquals(old.rectWidthDeg!!, field.widthDeg!!, old.rectWidthDeg!! * .001)
            assertEquals(old.rectHeightDeg!!, field.heightDeg!!, old.rectHeightDeg!! * .001)
        }
    }

    @Test fun unknownExcludedAndBoundaryAxisCannotTurnAPlanningPresetGreen() {
        val unknown = ConnectedOpticsGeometry(4f, 1000, 1000, deviceId = "real")
        assertNull(config(unknown).verificationField(selection, 0.0))
        assertFalse(config(unknown).currentFrameCentered(OpticsTrainId.PRIMARY))
        val excluded = ConnectedOpticsGeometry.fromSensorRoi(4f, 4000, 3000, Roi(2200, 1000, 1000, 1000), 1, "real")
        assertFalse(excluded.hasKnownAxis); assertNull(config(excluded).verificationField(selection, 0.0))
        val boundary = ConnectedOpticsGeometry.fromSensorRoi(4f, 4000, 3000, Roi(2000, 1000, 1000, 1000), 1, "real")
        assertTrue(boundary.hasKnownAxis); assertNull(config(boundary).verificationField(selection, 0.0))
        val planningCatalog = config(unknown).copy(primary = config(unknown).primary.copy(sensorId = "ccd_imx533"))
        assertNotNull(planningCatalog.computation(OpticsTrainId.PRIMARY))
        assertNull(planningCatalog.verificationField(selection, 0.0))
    }

    @Test fun hardwareBinnedAndUnbinnedRoiCoordinateContractsHaveTheSameAngularSafeField() {
        val fullCoordinates = ConnectedOpticsGeometry.fromSensorRoi(4f, 4000, 3000, Roi(1900, 1000, 1000, 1000), 1, "raw")
        val binCoordinates = ConnectedOpticsGeometry.fromSensorRoi(4f, 4000, 3000, Roi(950, 500, 500, 500), 2, "bin2")
        val rawField = requireNotNull(config(fullCoordinates).verificationField(selection, 0.0))
        val binField = requireNotNull(config(binCoordinates).verificationField(selection, 0.0))
        assertEquals(rawField.widthDeg!!, binField.widthDeg!!, 1e-12)
        assertEquals(rawField.heightDeg!!, binField.heightDeg!!, 1e-12)
        assertEquals(49.5, binCoordinates.axisPixelX!!, 0.0)
    }

    @Test fun planningPresetCannotReplaceActualCropOrChangeKnownDeviceInstallation() {
        val actual = ConnectedOpticsGeometry.fromSensorRoi(4f, 4000, 3000, Roi(1900, 1000, 1000, 1000), 1, "real")
        val configured = config(actual)
        val preset = configured.copy(primary = configured.primary.copy(sensorId = "ccd_imx533"))
        assertEquals(configured.verificationField(selection, 0.0), preset.verificationField(selection, 0.0))
        assertFalse(preset.currentFrameCentered(OpticsTrainId.PRIMARY))
        val recentered = configured.copy(primaryGeometry = ConnectedOpticsGeometry.fromSensorRoi(4f, 4000, 3000,
            Roi(1500, 1000, 1000, 1000), 1, "real"))
        assertNull(configured.changeTo(recentered, OpticsTrainId.PRIMARY))
    }
}
