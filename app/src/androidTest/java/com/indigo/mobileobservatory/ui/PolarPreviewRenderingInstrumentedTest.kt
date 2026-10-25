package com.indigo.mobileobservatory.ui

import android.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.FrameProcessor
import com.indigo.mobileobservatory.camera.PixelFormat
import com.indigo.mobileobservatory.recording.FITSWriter
import com.indigo.mobileobservatory.ui.screens.PolarWorkflowPreview
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.math.min

class PolarPreviewRenderingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()

    @Test fun sparseStarsKeepTheContrastOfTheLiveCameraPreview() {
        val width = 384; val height = 256
        val bytes = ByteBuffer.allocate(width * height * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (y in 0 until height) for (x in 0 until width) {
            val star = kotlin.math.abs(x - width / 2) <= 1 && kotlin.math.abs(y - height / 2) <= 1
            bytes.putShort((if (star) 3000 else 1000).toShort())
        }
        val frame = FrameData(bytes.array(), width, height, PixelFormat.MONO16, 1, 0)
        val live = FrameProcessor().frameToBitmap(frame)
        assertTrue(Color.red(live.getPixel(width / 2, height / 2)) > 240)
        assertTrue(Color.red(live.getPixel(width / 2 - 20, height / 2)) < 20)
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "polar-contrast-${UUID.randomUUID()}.fits")
        try {
            FITSWriter().write(file, frame, 1f, 0f)
            compose.setContent { MobileObservatoryTheme {
                PolarWorkflowPreview(file, "Test image", null, null, "", null, false, {}, null, {})
            } }
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("polar-main-preview").fetchSemanticsNodes().isNotEmpty() }
            val image = compose.onNodeWithTag("polar-main-preview").captureToImage().toPixelMap()
            val scale = min(image.width.toFloat() / width, image.height.toFloat() / height)
            val offset = Offset((image.width - width * scale) / 2, (image.height - height * scale) / 2)
            fun level(x: Int, y: Int): Float = image[(offset.x + (x + .5f) * scale).toInt(), (offset.y + (y + .5f) * scale).toInt()].red
            assertTrue("Sparse-star background must remain dark, as it does in the live preview", level(width / 2 - 20, height / 2) < .2f)
            assertTrue("The captured star must remain visible", level(width / 2, height / 2) > .8f)
        } finally { file.delete(); live.recycle() }
    }
}
