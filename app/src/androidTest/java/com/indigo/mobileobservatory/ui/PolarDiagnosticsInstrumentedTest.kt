package com.indigo.mobileobservatory.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import com.indigo.mobileobservatory.polar.*
import com.indigo.mobileobservatory.ui.screens.MeasurementCard
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PolarDiagnosticsInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun failedSampleExposesImageRetryAndRawSolverEvidenceAtLargeFont() {
        val image = File(context.cacheDir, "polar-ui-${UUID.randomUUID()}.png")
        Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(0xff446688.toInt())
            image.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        try {
            var opened = 0; var retried = 0; var recaptured = 0
            val currentPosition = mutableStateOf(true)
            val result = PlateSolveResult(false, "ASTAP failed", log = "Not enough stars. Found 7 stars. fixture-raw-log")
            val sample = PolarSample(PolarSamplePhase.FAILED, image, result, result.message, PolarSamplePhase.SOLVING)
            compose.setContent { MaterialTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        MeasurementCard(1, image, result, false, {}, { retried++ }, sample = sample,
                            canRecapture = currentPosition.value,
                            onViewImage = { opened++ }, onRecapture = { recaptured++ })
                    }
                }
            } }
            compose.onNodeWithTag("polar-view-1").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("polar-retry-1").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("polar-recapture-1").performScrollTo().assertIsEnabled().performClick()
            compose.onNodeWithTag("polar-log-1").performScrollTo().performClick()
            compose.onNodeWithText("fixture-raw-log", substring = true).performScrollTo().assertIsDisplayed()
            compose.runOnIdle {
                assertEquals(1, opened); assertEquals(1, retried); assertEquals(1, recaptured)
                currentPosition.value = false
            }
            compose.onNodeWithTag("polar-recapture-1").assertDoesNotExist()
            assertTrue(image.isFile)
        } finally { image.delete() }
    }

    @Test fun captureFailureExplainsWhyNoImageCanBeViewed() {
        val sample = PolarSample(phase = PolarSamplePhase.FAILED, failure = "USB disconnected", failedPhase = PolarSamplePhase.CAPTURING)
        compose.setContent { MaterialTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                MeasurementCard(0, null, null, false, {}, {}, sample = sample)
            }
        } }
        compose.onNodeWithText(context.getString(R.string.polar_no_image_captured)).assertIsDisplayed()
        compose.onNodeWithTag("polar-view-0").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("polar-recapture-0").performScrollTo().assertIsEnabled()
    }
}
