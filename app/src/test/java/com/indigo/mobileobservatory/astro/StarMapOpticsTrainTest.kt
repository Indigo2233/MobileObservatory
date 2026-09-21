package com.indigo.mobileobservatory.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarMapOpticsTrainTest {
    @Test
    fun legacyPrefsBecomeThePrimaryTrain() {
        val prefs = MapOpticsPrefs(
            strings = mapOf(
                "star_map_fov_mode" to "EYEPIECE",
                "star_map_telescope_id" to "scope_200_1000",
                "star_map_eyepiece_id" to "ep_13_82",
                "star_map_sensor_id" to "ccd_imx585",
                "star_map_custom_scope_fl" to "800"
            )
        )
        val primary = StarMapOpticsPrefs.loadPrimary(prefs)
        assertEquals(OpticsTrainId.PRIMARY, primary.id)
        assertEquals(FovInstrumentMode.EYEPIECE, primary.mode)
        assertEquals("scope_200_1000", primary.telescopeId)
        assertEquals("ep_13_82", primary.eyepieceId)
        assertEquals("ccd_imx585", primary.sensorId)
        assertEquals("800", primary.customTelescopeFl)
    }

    @Test
    fun missingSecondaryDefaultsToGuiderCameraWhenConnected() {
        val secondary = StarMapOpticsPrefs.loadSecondary(
            MapOpticsPrefs(),
            hasConnectedCamera = true,
            plateFocalLengthMm = 250f
        )
        assertEquals(OpticsTrainId.SECONDARY, secondary.id)
        assertEquals(FovInstrumentMode.SENSOR, secondary.mode)
        assertEquals(OpticsEquipment.CONNECTED_SENSOR_ID, secondary.sensorId)
        assertEquals("scope_custom", secondary.telescopeId)
        assertEquals("250.0", secondary.customTelescopeFl)
    }

    @Test
    fun missingSecondaryDefaultsToWideEyepieceWithoutCamera() {
        val secondary = StarMapOpticsPrefs.loadSecondary(
            MapOpticsPrefs(),
            hasConnectedCamera = false
        )
        assertEquals(FovInstrumentMode.EYEPIECE, secondary.mode)
        assertEquals("ep_40_68", secondary.eyepieceId)
    }

    @Test
    fun defaultActiveTrainIsSecondary() {
        assertEquals(
            OpticsTrainId.SECONDARY,
            StarMapOpticsPrefs.loadActive(MapOpticsPrefs())
        )
        assertEquals(
            OpticsTrainId.PRIMARY,
            StarMapOpticsPrefs.loadActive(
                MapOpticsPrefs(strings = mapOf("star_map_active_train" to "primary"))
            )
        )
    }

    @Test
    fun snapshotKeepsPrimaryAndSecondaryIndependent() {
        val primary = OpticsTrainConfig(
            id = OpticsTrainId.PRIMARY,
            mode = FovInstrumentMode.EYEPIECE,
            telescopeId = "scope_203_2032",
            eyepieceId = "ep_7_82"
        )
        val secondary = OpticsTrainConfig(
            id = OpticsTrainId.SECONDARY,
            mode = FovInstrumentMode.SENSOR,
            telescopeId = "scope_80_500",
            sensorId = "ccd_imx585"
        )
        val write = StarMapOpticsPrefs.snapshot(
            primary,
            secondary,
            OpticsTrainId.SECONDARY,
            showOverlay = true
        )
        assertEquals("EYEPIECE", write.strings["star_map_fov_mode"])
        assertEquals("scope_203_2032", write.strings["star_map_telescope_id"])
        assertEquals("SENSOR", write.strings["star_map_secondary_fov_mode"])
        assertEquals("ccd_imx585", write.strings["star_map_secondary_sensor_id"])
        assertEquals("secondary", write.strings["star_map_active_train"])
        assertFalse(write.strings.containsKey("plate_focal_length_mm"))
        assertTrue(write.bools.getValue("star_map_show_fov_overlay"))
    }

    @Test
    fun plateFocalLengthFollowsGuiderCameraOnly() {
        assertTrue(
            StarMapOpticsPrefs.shouldWritePlateFocalLength(
                OpticsTrainId.SECONDARY,
                FovInstrumentMode.SENSOR
            )
        )
        assertFalse(
            StarMapOpticsPrefs.shouldWritePlateFocalLength(
                OpticsTrainId.PRIMARY,
                FovInstrumentMode.SENSOR
            )
        )
        assertFalse(
            StarMapOpticsPrefs.shouldWritePlateFocalLength(
                OpticsTrainId.SECONDARY,
                FovInstrumentMode.EYEPIECE
            )
        )
    }

    @Test
    fun trainsCanCrossInstrumentShapes() {
        val sensors = OpticsEquipment.defaultSensors
        val primary = OpticsTrainConfig(
            id = OpticsTrainId.PRIMARY,
            mode = FovInstrumentMode.SENSOR,
            telescopeId = "scope_200_1000",
            sensorId = "ccd_imx585"
        ).compute(sensors = sensors)
        val secondary = OpticsTrainConfig(
            id = OpticsTrainId.SECONDARY,
            mode = FovInstrumentMode.EYEPIECE,
            telescopeId = "scope_80_500",
            eyepieceId = "ep_40_68"
        ).compute(sensors = sensors)
        assertEquals(FovInstrumentMode.SENSOR, primary?.mode)
        assertTrue(primary?.hasOverlay == true)
        assertTrue((primary?.rectWidthDeg ?: 0.0) > 0.0)
        assertEquals(FovInstrumentMode.EYEPIECE, secondary?.mode)
        assertTrue(secondary?.hasOverlay == true)
        assertTrue((secondary?.circleDeg ?: 0.0) > 0.0)
    }
}
