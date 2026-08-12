package com.indigo.mobileobservatory.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

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
    var targetName by mutableStateOf<String?>("M42 · Orion Nebula")
    var targetRaHours by mutableDoubleStateOf(5.588)
    var targetDecDeg by mutableDoubleStateOf(-5.391)
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
                    nav.targetName = "${obj.id} · ${obj.name}"
                    nav.targetRaHours = obj.raHours
                    nav.targetDecDeg = obj.decDeg
                    nav.destination = PhonePlateSolveDestination.PUSH_TO
                }
            )

        PhonePlateSolveDestination.PUSH_TO ->
            PushToScreen(
                onBack = { nav.destination = null },
                onOpenCalibration = { nav.destination = PhonePlateSolveDestination.CALIBRATION },
                onOpenTargets = { nav.destination = PhonePlateSolveDestination.TARGET_LIBRARY },
                initialTargetName = nav.targetName,
                targetRaHours = nav.targetRaHours,
                targetDecDeg = nav.targetDecDeg
            )

        null -> Unit
    }
}
