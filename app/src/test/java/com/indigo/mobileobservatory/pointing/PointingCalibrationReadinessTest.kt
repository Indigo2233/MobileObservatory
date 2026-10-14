package com.indigo.mobileobservatory.pointing

import org.junit.Assert.*
import org.junit.Test

class PointingCalibrationReadinessTest {
    private val phoneEyepiece = PointingCalibrationReadiness(
        source = PointingCameraSource.PHONE,
        mainCamera = false,
        mainOpticsReady = true,
        auxiliaryOpticsReady = false,
        siteReady = true,
        cameraPermission = true,
        phoneLensReady = true,
        auxiliaryConnected = false,
        auxiliaryCaptureReady = false,
        guiding = false,
        auxiliarySettingsBusy = false,
        auxiliaryExposureReady = false,
        mainConnected = false,
        databaseReady = false,
        centeringReady = true,
        installation = PointingInstallation.FIXED,
        locked = true
    )
    private val auxiliaryEyepiece = phoneEyepiece.copy(
        source = PointingCameraSource.AUXILIARY,
        auxiliaryOpticsReady = true,
        cameraPermission = false,
        phoneLensReady = false,
        auxiliaryConnected = true,
        auxiliaryCaptureReady = true,
        auxiliaryExposureReady = true,
        databaseReady = true
    )

    private fun readiness(state: PointingCalibrationReadiness) = state

    @Test fun phoneAndEyepieceStartWithoutExternalCamerasOrAstapDatabase() {
        val result = readiness(phoneEyepiece)
        assertTrue(result.canStart)
        assertTrue(result.canAcquire)
        assertTrue(result.issues.isEmpty())
        // Even stale external device states do not affect a phone-only capture.
        assertTrue(readiness(phoneEyepiece.copy(guiding = true, auxiliarySettingsBusy = true)).canStart)
    }

    @Test fun selectedPhoneNeedsPermissionAndUsableLens() {
        val result = readiness(phoneEyepiece.copy(cameraPermission = false, phoneLensReady = false))
        assertEquals(listOf(CalibrationBlocker.PHONE_PERMISSION, CalibrationBlocker.PHONE_LENS), result.issues)
        assertFalse(result.canStart)
        assertFalse(result.canAcquire)
    }

    @Test fun hiddenCenteringAndUnconfirmedMainOpticsAreReportedForEyepiece() {
        assertEquals(listOf(CalibrationBlocker.MAIN_OPTICS, CalibrationBlocker.CENTERING),
            readiness(phoneEyepiece.copy(mainOpticsReady = false, centeringReady = false)).issues)
        assertEquals(listOf(CalibrationBlocker.SITE), readiness(phoneEyepiece.copy(siteReady = false)).issues)
    }

    @Test fun auxiliaryCameraUsesItsOwnConditionsWithoutPhonePermission() {
        assertTrue(readiness(auxiliaryEyepiece).canStart)
        val result = readiness(auxiliaryEyepiece.copy(auxiliaryOpticsReady = false,
            auxiliaryCaptureReady = false, guiding = true, auxiliarySettingsBusy = true,
            auxiliaryExposureReady = false))
        assertEquals(setOf(CalibrationBlocker.AUXILIARY_OPTICS, CalibrationBlocker.AUXILIARY_FORMAT,
            CalibrationBlocker.GUIDING, CalibrationBlocker.AUXILIARY_SETTINGS,
            CalibrationBlocker.AUXILIARY_EXPOSURE), result.issues.toSet())
        assertFalse(result.canStart)
    }

    @Test fun auxiliarySolvingRequiresDatabaseEvenWithAnEyepieceReference() {
        assertEquals(listOf(CalibrationBlocker.DATABASE),
            readiness(auxiliaryEyepiece.copy(databaseReady = false)).issues)
    }

    @Test fun disconnectedAuxiliaryCameraDoesNotInventUnverifiableSettingsFailures() {
        val disconnected = auxiliaryEyepiece.copy(auxiliaryConnected = false,
            auxiliaryCaptureReady = false, auxiliarySettingsBusy = true, auxiliaryExposureReady = false)
        assertEquals(listOf(CalibrationBlocker.AUXILIARY_CAMERA), readiness(disconnected).issues)
        // A genuinely unconfigured optical train is still independently actionable.
        assertEquals(setOf(CalibrationBlocker.AUXILIARY_CAMERA, CalibrationBlocker.AUXILIARY_OPTICS),
            readiness(disconnected.copy(auxiliaryOpticsReady = false)).issues.toSet())
    }

    @Test fun mainCameraReferenceRequiresConnectionAndDatabaseWithoutEyepieceCentering() {
        val disconnected = phoneEyepiece.copy(mainCamera = true, mainOpticsReady = false,
            mainConnected = false, centeringReady = false)
        assertEquals(listOf(CalibrationBlocker.MAIN_CAMERA, CalibrationBlocker.DATABASE),
            readiness(disconnected).issues)
        assertEquals(listOf(CalibrationBlocker.MAIN_OPTICS),
            readiness(disconnected.copy(mainConnected = true, databaseReady = true)).issues)
        assertTrue(readiness(disconnected.copy(mainConnected = true, mainOpticsReady = true,
            databaseReady = true)).canStart)
    }

    @Test fun adjustableUnlockedBracketCanAcquireForRoughAdjustmentButCannotStartCalibration() {
        val result = readiness(phoneEyepiece.copy(installation = PointingInstallation.ADJUSTABLE, locked = false))
        assertEquals(listOf(CalibrationBlocker.UNLOCKED), result.issues)
        assertTrue(result.acquisitionIssues.isEmpty())
        assertTrue(result.canAcquire)
        assertFalse(result.canStart)
    }

    @Test fun handheldInstallationCannotBeSavedRegardlessOfCheckbox() {
        for (locked in listOf(false, true)) {
            val result = readiness(phoneEyepiece.copy(installation = PointingInstallation.UNFIXED, locked = locked))
            assertEquals(listOf(CalibrationBlocker.UNFIXED), result.issues)
            assertFalse(result.canStart)
            assertTrue(result.canAcquire)
        }
    }

    @Test fun returningToPhoneAndEyepieceRemovesBlockersFromPreviousExternalPath() {
        val external = phoneEyepiece.copy(source = PointingCameraSource.AUXILIARY, mainCamera = true,
            cameraPermission = true, phoneLensReady = true)
        assertFalse(readiness(external).canStart)
        val phone = readiness(external.copy(source = PointingCameraSource.PHONE, mainCamera = false))
        assertTrue(phone.canStart)
        assertTrue(phone.issues.isEmpty())
    }

    @Test fun acquisitionAndStartRemainAsStrictAsOriginalGateAcrossAllConditionCombinations() {
        // Exhausting the boolean inputs catches accidental weakening while the displayed issues evolve.
        for (source in PointingCameraSource.values()) {
            for (installation in PointingInstallation.values()) {
                for (mask in 0 until (1 shl 15)) {
                    fun flag(bit: Int) = mask and (1 shl bit) != 0
                    val state = phoneEyepiece.copy(source = source, installation = installation,
                        mainCamera = flag(0), mainOpticsReady = flag(1), auxiliaryOpticsReady = flag(2),
                        siteReady = flag(3), cameraPermission = flag(4), phoneLensReady = flag(5),
                        auxiliaryConnected = flag(6), auxiliaryCaptureReady = flag(7), guiding = flag(8),
                        auxiliarySettingsBusy = flag(9), auxiliaryExposureReady = flag(10),
                        mainConnected = flag(11), databaseReady = flag(12), centeringReady = flag(13),
                        locked = flag(14))
                    val sourceReady = if (source == PointingCameraSource.PHONE)
                        state.cameraPermission && state.phoneLensReady
                    else state.auxiliaryConnected && state.auxiliaryCaptureReady && !state.guiding &&
                        !state.auxiliarySettingsBusy && state.auxiliaryExposureReady && state.auxiliaryOpticsReady
                    val originalAcquire = state.siteReady && state.mainOpticsReady && sourceReady &&
                        (if (state.mainCamera) state.mainConnected && state.databaseReady else state.centeringReady) &&
                        (source == PointingCameraSource.PHONE || state.databaseReady)
                    val originalStart = originalAcquire && installation != PointingInstallation.UNFIXED && state.locked
                    val result = readiness(state)
                    assertEquals("acquire source=$source installation=$installation mask=$mask", originalAcquire, result.canAcquire)
                    assertEquals("start source=$source installation=$installation mask=$mask", originalStart, result.canStart)
                    assertEquals(result.issues.isEmpty(), result.canStart)
                    assertEquals(result.acquisitionIssues.isEmpty(), result.canAcquire)
                }
            }
        }
    }
}
