package com.indigo.mobileobservatory.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.astrometry.ImagePoint
import com.indigo.mobileobservatory.polar.PolarCorrectionGeometry
import com.indigo.mobileobservatory.ui.screens.PolarCorrectionImage
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PolarOverlayScaleInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()

    @Test fun offFrameCorrectionLeavesThePhotographAtTheSameScale() {
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff707070.toInt()) }
        val guide = mutableStateOf<PolarCorrectionGeometry?>(null)
        compose.setContent { MobileObservatoryTheme {
            PolarCorrectionImage(bitmap, 400, 300, guide.value, Modifier.size(320.dp, 240.dp).testTag("photo"))
        } }
        val before = compose.onNodeWithTag("photo").captureToImage().toPixelMap()
        compose.runOnIdle {
            guide.value = PolarCorrectionGeometry(400, 300, ImagePoint(199.5, 149.5),
                ImagePoint(3000.0, 149.5), ImagePoint(3000.0, -2000.0), ImagePoint(199.5, -2000.0))
        }
        val after = compose.onNodeWithTag("photo").captureToImage().toPixelMap()
        // These points belong to the photograph and lie clear of all correction lines.
        for ((x, y) in listOf(.1f to .2f, .9f to .8f, .1f to .8f)) {
            val px = (before.width * x).toInt(); val py = (before.height * y).toInt()
            assertEquals(before[px, py], after[px, py])
        }
    }
}
