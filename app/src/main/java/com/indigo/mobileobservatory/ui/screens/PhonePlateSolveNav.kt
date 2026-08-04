package com.indigo.mobileobservatory.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.indigo.mobileobservatory.catalog.CatalogObject
import com.indigo.mobileobservatory.pointing.PushToGuidance
import kotlin.math.abs

/**
 * Full-screen destinations of the phone plate-solve WIP flow. Kept in a
 * dedicated host so CameraScreen only carries one extra state slot —
 * inlining these blocks pushed CameraScreen over ART's method register
 * limits and produced a VerifyError on some devices.
 */
enum class PhonePlateSolveDestination {
    PHONE_CAMERA_DEBUG,
    PUSH_TO,
    CALIBRATION,
    TARGET_LIBRARY
}

class PhonePlateSolveNavState {
    var destination by mutableStateOf<PhonePlateSolveDestination?>(null)
    var targetName by mutableStateOf<String?>(null)
    var targetAlt by mutableFloatStateOf(55f)
    var targetAz by mutableFloatStateOf(180f)
}

@Composable
fun rememberPhonePlateSolveNavState(): PhonePlateSolveNavState =
    remember { PhonePlateSolveNavState() }

@Composable
fun PhonePlateSolveScreens(nav: PhonePlateSolveNavState) {
    when (nav.destination) {
        PhonePlateSolveDestination.PHONE_CAMERA_DEBUG ->
            PhoneCameraDebugScreen(onBack = { nav.destination = null })

        PhonePlateSolveDestination.CALIBRATION ->
            CalibrationWizardScreen(onBack = { nav.destination = null })

        PhonePlateSolveDestination.TARGET_LIBRARY ->
            TargetLibraryScreen(
                onBack = { nav.destination = null },
                onGuideTo = { obj ->
                    val (alt, az) = demoAltAzFromEquatorial(obj)
                    nav.targetName = "${obj.id} · ${obj.name}"
                    nav.targetAlt = alt
                    nav.targetAz = az
                    nav.destination = PhonePlateSolveDestination.PUSH_TO
                }
            )

        PhonePlateSolveDestination.PUSH_TO ->
            PushToScreen(
                onBack = { nav.destination = null },
                onOpenCalibration = { nav.destination = PhonePlateSolveDestination.CALIBRATION },
                onOpenTargets = { nav.destination = PhonePlateSolveDestination.TARGET_LIBRARY },
                initialTargetName = nav.targetName,
                initialTargetAlt = nav.targetAlt,
                initialTargetAz = nav.targetAz
            )

        null -> Unit
    }
}

/**
 * Placeholder equatorial→horizontal for demo linking only.
 * Not for real sky; replaced when site + LST available.
 */
private fun demoAltAzFromEquatorial(obj: CatalogObject): Pair<Float, Float> {
    val alt = (abs(obj.decDeg) * 0.6 + 25.0).coerceIn(5.0, 85.0).toFloat()
    val az = PushToGuidance.normalizeAzimuth(obj.raHours * 15.0).toFloat()
    return alt to az
}
