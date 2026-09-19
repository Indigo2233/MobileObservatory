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

    private fun read(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("missing $relative", file != null)
        return file!!.readText()
    }
}
