package com.indigo.mobileobservatory.astro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserOpticsCatalogTest {
    @Test
    fun blankStorageFallsBackToBuiltInCatalogs() {
        assertEquals(OpticsEquipment.defaultTelescopes, UserOpticsCatalog.loadTelescopes(null))
        assertEquals(OpticsEquipment.defaultSensors, UserOpticsCatalog.loadCameras(""))
    }

    @Test
    fun telescopesRoundTripNameAndFocalLength() {
        val named = TelescopeSpec("user_scope_1", "C8", 2032.0, 203.0)
        val encoded = UserOpticsCatalog.formatTelescopes(listOf(named))
        val parsed = UserOpticsCatalog.parseTelescopes(encoded)
        assertEquals(listOf(named), parsed)
        assertEquals(listOf(named), UserOpticsCatalog.loadTelescopes(encoded))
    }

    @Test
    fun camerasRoundTripAndSkipConnectedId() {
        val named = SensorSpec("user_cam_1", "ASI533", 3.76, 3008, 3008)
        val encoded = UserOpticsCatalog.formatCameras(
            listOf(named, SensorSpec(OpticsEquipment.CONNECTED_SENSOR_ID, "Live", 2.9, 1920, 1080))
        )
        assertEquals(listOf(named), UserOpticsCatalog.parseCameras(encoded))
    }

    @Test
    fun addRenameAndDeleteTelescope() {
        val start = listOf(TelescopeSpec("scope_80_500", "80 mm f/6.3", 500.0, 80.0))
        val added = UserOpticsCatalog.addTelescope(start, "主镜", 800.0, 102.0, "user_scope_2")
        assertEquals(2, added.size)
        assertEquals("主镜", added.last().name)
        val renamed = UserOpticsCatalog.replaceTelescope(
            added,
            added.last().copy(name = "80ED")
        )
        assertEquals("80ED", renamed.last().name)
        val removed = UserOpticsCatalog.removeTelescope(renamed, "user_scope_2")
        assertEquals(start, removed)
        assertEquals(start, UserOpticsCatalog.removeTelescope(start, "scope_80_500"))
    }

    @Test
    fun uniqueNameAppendsNumber() {
        assertEquals("主镜", UserOpticsCatalog.uniqueName(emptyList(), "主镜"))
        assertEquals("主镜 2", UserOpticsCatalog.uniqueName(listOf("主镜"), "主镜"))
        assertEquals("主镜 3", UserOpticsCatalog.uniqueName(listOf("主镜", "主镜 2"), "主镜"))
    }

    @Test
    fun displayNameUsesNamedEquipment() {
        val scope = TelescopeSpec("scope_80_500", "C8", 500.0, 80.0)
        val camera = SensorSpec("ccd_imx533", "ASI533", 3.76, 3008, 3008)
        val eyepiece = OpticsTrainConfig(
            id = OpticsTrainId.PRIMARY,
            mode = FovInstrumentMode.EYEPIECE,
            telescopeId = "scope_80_500"
        )
        val sensor = OpticsTrainConfig(
            id = OpticsTrainId.SECONDARY,
            mode = FovInstrumentMode.SENSOR,
            sensorId = "ccd_imx533"
        )
        assertEquals("C8", UserOpticsCatalog.displayName(eyepiece, listOf(scope), listOf(camera), "主镜"))
        assertEquals("ASI533", UserOpticsCatalog.displayName(sensor, listOf(scope), listOf(camera), "导星"))
        assertEquals("主镜", UserOpticsCatalog.displayName(eyepiece, emptyList(), emptyList(), "主镜"))
    }

    @Test
    fun cannotDeleteLastCameraOrConnectedSlot() {
        val only = listOf(SensorSpec("ccd_imx533", "ASI533", 3.76, 3008, 3008))
        assertEquals(only, UserOpticsCatalog.removeCamera(only, "ccd_imx533"))
        val two = only + SensorSpec("user_cam_2", "290", 2.9, 1920, 1080)
        assertEquals(two, UserOpticsCatalog.removeCamera(two, OpticsEquipment.CONNECTED_SENSOR_ID))
        assertEquals(only, UserOpticsCatalog.removeCamera(two, "user_cam_2"))
        assertFalse(UserOpticsCatalog.removeCamera(two, "user_cam_2").any { it.id == "user_cam_2" })
        assertTrue(UserOpticsCatalog.newId("user_scope", 42L) == "user_scope_42")
    }
}
