package com.indigo.mobileobservatory.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservingUiWiringTest {
    @Test
    fun mountScreensHideThePickerWhileConnected() {
        val mountScreen = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/MountControlScreen.kt"
        )
        val controlPanel = read(
            "src/main/java/com/indigo/mobileobservatory/ui/components/ControlPanel.kt"
        )
        assertTrue(mountScreen.contains("connectionUi.showSetupPanel"))
        assertTrue(controlPanel.contains("if (!mountConnected)"))
    }

    @Test
    fun cameraPreviewDrawsTheCenterMarkerInImageSpace() {
        val preview = read(
            "src/main/java/com/indigo/mobileobservatory/ui/components/LivePreview.kt"
        )
        val camera = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/CameraScreen.kt"
        )
        val transformIndex = preview.indexOf("withTransform")
        val markerIndex = preview.indexOf("if (showCenterMarker)")
        assertTrue(transformIndex >= 0)
        assertTrue(markerIndex > transformIndex)
        assertTrue(camera.contains("showCenterMarker = showCenterMarker"))
        assertTrue(camera.contains("R.string.image_center_marker"))
    }

    @Test
    fun starMapPushesBothFovLayersTogether() {
        val starMap = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapScreen.kt"
        )
        assertTrue(starMap.contains("StarMapFovOverlay.scripts"))
        assertTrue(starMap.contains("eyepieceFovDeg = eyepieceComputation?.circleDeg"))
        assertTrue(starMap.contains("sensorWidthDeg = sensorComputation?.rectWidthDeg"))
    }

    @Test
    fun plateSolveRecomputesJpegFovFromUserFocalLength() {
        val screen = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/PlateSolveScreen.kt"
        )
        assertTrue(screen.contains("SolveOpticsFields("))
        assertTrue(screen.contains("PlateSolveOptics.astapFovDeg"))
        assertTrue(screen.contains("measuredFocalLengthMm"))
        assertTrue(screen.contains("solved_focal_length_mm"))
        assertTrue(!screen.contains("estimated_field_height_deg"))
        val optics = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/SolveOpticsFields.kt"
        )
        assertTrue(optics.contains("fun pixelSizeForSensor"))
        assertTrue(optics.contains("OpticsEquipment.CUSTOM_SENSOR_ID"))
        val polar = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/PolarAlignmentScreen.kt"
        )
        assertTrue(polar.contains("SolveOpticsFields("))
        assertTrue(!polar.contains("estimated_field_height_deg"))
        val catalog = read(
            "src/main/java/com/indigo/mobileobservatory/astro/OpticsEquipment.kt"
        )
        assertTrue(catalog.contains("Nikon D5100 (IMX071)"))
        assertTrue(catalog.contains("IMX455"))
        assertTrue(catalog.contains("IMX571"))
        assertTrue(catalog.contains("IMX585"))
        val persist = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapScreen.kt"
        )
        val persistStart = persist.indexOf("fun persistFovPrefs()")
        val persistEnd = persist.indexOf("fun persistImagingFocalLength(")
        assertTrue(persistStart >= 0 && persistEnd > persistStart)
        assertTrue(
            !persist.substring(persistStart, persistEnd).contains("plate_focal_length_mm")
        )
        assertTrue(persist.contains("onTelescopeSelected"))
        assertTrue(persist.contains("persistImagingFocalLength("))
    }

    private fun read(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("missing $relative", file != null)
        return file!!.readText()
    }
}
