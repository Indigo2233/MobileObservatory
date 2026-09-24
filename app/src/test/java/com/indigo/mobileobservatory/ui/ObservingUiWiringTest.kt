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
        val tools = read(
            "src/main/java/com/indigo/mobileobservatory/ui/components/CameraPreviewTools.kt"
        )
        val transformIndex = preview.indexOf("withTransform")
        val markerIndex = preview.indexOf("if (showCenterMarker)")
        assertTrue(transformIndex >= 0)
        assertTrue(markerIndex > transformIndex)
        assertTrue(camera.contains("showCenterMarker = showCenterMarker"))
        assertTrue(tools.contains("R.string.image_center_marker"))
    }

    @Test
    fun cameraPreviewChromeUsesOverflowMenuInsteadOfVerticalFabStack() {
        val camera = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/CameraScreen.kt"
        )
        val tools = read(
            "src/main/java/com/indigo/mobileobservatory/ui/components/CameraPreviewTools.kt"
        )
        val focus = read(
            "src/main/java/com/indigo/mobileobservatory/ui/components/FocusAssistOverlay.kt"
        )
        assertTrue(camera.contains("CameraPreviewTools("))
        assertTrue(camera.contains(".align(Alignment.BottomEnd)"))
        assertTrue(tools.contains("R.string.camera_preview_tools"))
        assertTrue(tools.contains("Icons.Default.MoreVert"))
        assertTrue(tools.contains("onClick = onFitToView"))
        assertTrue(tools.contains("R.string.fit_to_view"))
        assertTrue(tools.contains("R.string.focus_assist"))
        assertTrue(tools.contains("R.string.image_center_marker"))
        assertTrue(tools.contains("R.string.plate_solve"))
        assertTrue(tools.contains("if (focusAssistEnabled) menuOpen = false"))
        assertTrue(focus.contains(".width(220.dp)"))
        assertTrue(focus.contains(".height(160.dp)"))
    }

    @Test
    fun starMapPushesOnlyTheActiveTrainOverlay() {
        val starMap = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapScreen.kt"
        )
        assertTrue(starMap.contains("StarMapFovOverlay.scripts"))
        assertTrue(starMap.contains("computation = activeComputation"))
        assertTrue(starMap.contains("currentLabel = overlayCurrentLabel"))
        assertTrue(starMap.contains("targetLabel = overlayTargetLabel"))
        assertTrue(starMap.contains("currentAnchor = currentAnchor"))
        assertTrue(!starMap.contains("targetAnchor = targetAnchor"))
        assertTrue(starMap.contains("FovSkyAnchor("))
        assertTrue(!starMap.contains("eyepieceFovDeg = eyepieceComputation"))
        assertTrue(!starMap.contains("sensorWidthDeg = sensorComputation"))
    }

    @Test
    fun starMapOpticsTrainsAreSwitchedFromTheObservingPanel() {
        val hud = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapHud.kt"
        )
        val sheet = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapFovSheet.kt"
        )
        val camera = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapScreen.kt"
        )
        assertTrue(hud.contains("R.string.star_map_train_primary"))
        assertTrue(hud.contains("R.string.star_map_train_secondary"))
        assertTrue(hud.contains("onActiveTrainChange"))
        assertTrue(sheet.contains("onEditingTrainChange"))
        assertTrue(sheet.contains("R.string.star_map_train_primary"))
        assertTrue(sheet.contains("R.string.star_map_train_secondary"))
        assertTrue(sheet.contains("R.string.fov_equipment_name"))
        assertTrue(sheet.contains("R.string.fov_named_telescope"))
        assertTrue(sheet.contains("R.string.fov_named_eyepiece"))
        assertTrue(sheet.contains("R.string.fov_add_equipment"))
        assertTrue(sheet.contains("R.string.fov_delete_equipment"))
        assertTrue(sheet.contains("onTelescopesChange"))
        assertTrue(sheet.contains("onEyepiecesChange"))
        assertTrue(sheet.contains("onCamerasChange"))
        assertTrue(camera.contains("UserOpticsCatalog.loadTelescopes"))
        assertTrue(camera.contains("UserOpticsCatalog.loadEyepieces"))
        assertTrue(camera.contains("UserOpticsCatalog.loadCameras"))
        assertTrue(camera.contains("UserOpticsCatalog.combinationName"))
        assertTrue(!hud.contains("primaryTrainLabel"))
        assertTrue(!sheet.contains("primaryTrainLabel"))
        assertTrue(!camera.contains("UserOpticsCatalog.displayName"))
        assertTrue(camera.contains("maybeWritePlateFocalLength("))
        assertTrue(camera.contains("StarMapOpticsPrefs.loadPrimary"))
        assertTrue(camera.contains("star_map_secondary_fov_mode"))
    }

    @Test
    fun starMapDoesNotFollowMountByDefaultAndSyncsPanPause() {
        val starMap = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapScreen.kt"
        )
        assertTrue(
            starMap.contains("prefs.getBoolean(\"star_map_follow_mount\", false)")
        )
        assertTrue(!starMap.contains("prefs.getBoolean(\"star_map_follow_mount\", true)"))
        assertTrue(starMap.contains("fun onFollowMountChanged(enabled: String)"))
        assertTrue(starMap.contains("onFollowChanged = { enabled -> setFollowMountEnabled(enabled) }"))
        assertTrue(starMap.contains("stringResource(R.string.center_on_mount)"))
    }

    @Test
    fun starMapChromeUsesTwoHideableCornerPanels() {
        val starMap = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapScreen.kt"
        )
        val hud = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapHud.kt"
        )
        assertTrue(starMap.contains("StarMapCornerControls("))
        assertTrue(starMap.contains("StarMapCornerPanel.NONE"))
        assertTrue(starMap.contains("if (!overlaysVisible) cornerPanel = StarMapCornerPanel.NONE"))
        assertTrue(starMap.contains("BoxWithConstraints("))
        assertTrue(starMap.contains("Arrangement.SpaceBetween"))
        assertTrue(starMap.contains("val panelMax = minOf(360.dp, maxWidth - 16.dp)"))
        assertTrue(starMap.contains("val showTargetCard = target != null && cornerPanel == StarMapCornerPanel.NONE"))
        assertTrue(!starMap.contains("R.string.select_celestial_target"))
        assertTrue(!starMap.contains("bottomSlotMax"))
        assertTrue(hud.contains("modifier: Modifier = Modifier"))
        assertTrue(hud.contains("StarMapCornerPanel.OBSERVING"))
        assertTrue(hud.contains("StarMapCornerPanel.SKY"))
        assertTrue(hud.contains("stringResource(R.string.star_map_equatorial_grid)"))
        assertTrue(hud.contains("stringResource(R.string.star_map_azimuthal_grid)"))
        assertTrue(hud.contains("stringResource(R.string.star_map_constellation_lines)"))
        assertTrue(!hud.contains("stringResource(R.string.red_night_mode)"))
        assertTrue(starMap.contains("redNightMode: Boolean"))
        assertTrue(starMap.contains("Icons.Default.Nightlight"))
        assertTrue(starMap.contains("onRedNightModeChange(!redNightMode)"))
        assertTrue(starMap.contains("applyNightVision()"))
        assertTrue(starMap.contains("ColorMatrixColorFilter"))
        assertTrue(starMap.contains("MercStarMap.setSkyAppearance("))
        assertTrue(starMap.contains("MercStarMap.setNightVision("))
        assertTrue(!starMap.contains("Icons.Default.MoreVert"))
    }

    @Test
    fun starMapDirectionPadCanGoHomeAndManualMoveDoesNotSpawnGlobalStop() {
        val starMap = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapScreen.kt"
        )
        val hud = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapHud.kt"
        )
        val camera = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/CameraScreen.kt"
        )
        val motion = read(
            "src/main/java/com/indigo/mobileobservatory/mount/MountMotionRunner.kt"
        )
        assertTrue(starMap.contains("onGoHome: () -> Unit"))
        assertTrue(hud.contains("stringResource(R.string.home)"))
        assertTrue(starMap.contains("onGoHome()"))
        assertTrue(camera.contains("onGoHome = viewModel::goMountHome"))
        assertTrue(camera.contains("if (!state.showsGlobalStop) return"))
        assertTrue(camera.contains("selectedTab != MainControlTab.STAR_MAP"))
        assertTrue(camera.contains("MountMotionStopBanner("))
        assertTrue(hud.contains("internal fun MountMotionStopBanner"))
        assertTrue(starMap.contains("mountMotionState.showsGlobalStop"))
        assertTrue(starMap.contains("MountMotionStopBanner("))
        assertTrue(starMap.contains("!showGlobalStop"))
        assertTrue(motion.contains("MountMotionType.MANUAL -> false"))
        assertTrue(motion.contains("holdStop || slewing"))
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
        assertTrue(persist.contains("maybeWritePlateFocalLength("))
        assertTrue(persist.contains("persistImagingFocalLength("))
        assertTrue(persist.contains("StarMapOpticsPrefs.shouldWritePlateFocalLength"))
    }

    @Test
    fun targetLibraryUsesTheStarMapCatalog() {
        val library = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/TargetLibraryScreen.kt"
        )
        assertTrue(library.contains("AssetDeepSkyCatalog("))
        assertTrue(library.contains("catalog.search(query)"))
        assertTrue(library.contains("catalog.suggest("))
        assertTrue(!library.contains("DemoCatalog"))
        assertTrue(library.contains("fun TargetLibraryContent("))
    }

    @Test
    fun pushToKeepsTheScreenOnAndMapsSolveFailures() {
        val screen = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/PushToExperienceScreen.kt"
        )
        assertTrue(screen.contains("FLAG_KEEP_SCREEN_ON"))
        assertTrue(screen.contains("PhoneSolveCaptureLadder.next"))
        assertTrue(screen.contains("ModalBottomSheet"))
        assertTrue(screen.contains("PushToSolveCopy.messageRes"))
        val copy = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/PushToSolveCopy.kt"
        )
        assertTrue(copy.contains("push_to_fail_few_stars"))
        assertTrue(copy.contains("push_to_fail_motion"))
    }

    @Test
    fun toupTekCameraKeepsTheUsbConnectionAlive() {
        val manager = read(
            "src/main/java/com/indigo/mobileobservatory/camera/DahengCameraManager.kt"
        )
        val openStart = manager.indexOf("private fun openToupcamDevice")
        val openEnd = manager.indexOf("private fun closeToupcamUsbConnection")
        assertTrue("openToupcamDevice missing", openStart >= 0 && openEnd > openStart)
        val open = manager.substring(openStart, openEnd)
        val successStart = open.indexOf("if (camera.open(")
        val elseStart = open.indexOf("} else {", successStart)
        assertTrue("ToupTek success branch missing", successStart >= 0 && elseStart > successStart)
        val success = open.substring(successStart, elseStart)
        assertTrue(
            "open() success must keep UsbDeviceConnection; dropping it lets GC close the fd",
            success.contains("toupcamUsbConnection = connection")
        )
        assertTrue(
            "open() success must not close the USB connection",
            !success.contains("connection.close()")
        )
        val closeStart = manager.indexOf("fun closeCamera()")
        val closeEnd = manager.indexOf("fun connectFilterWheel(")
        assertTrue("closeCamera missing", closeStart >= 0 && closeEnd > closeStart)
        assertTrue(
            manager.substring(closeStart, closeEnd).contains("closeToupcamUsbConnection()")
        )
        assertTrue(manager.contains("ToupTekDevices.classify(isfw, iseaf)"))
        assertTrue(manager.contains("ToupTekDevices.Kind.CAMERA"))
        assertTrue(
            "unknown ToupTek PIDs must still enumerate as cameras",
            !manager.contains("else if (modelName != null)")
        )
        assertTrue(
            "a finished scan must not leave the connect button under an endless spinner",
            !manager.contains("ConnectionState.Enumerating")
        )
        val requestStart = manager.indexOf("private fun requestToupcamPermission")
        val requestEnd = manager.indexOf("private fun openToupcamDevice")
        assertTrue(requestStart >= 0 && requestEnd > requestStart)
        assertTrue(
            "USB permission must be requested on the main thread",
            manager.substring(requestStart, requestEnd).contains("Looper.getMainLooper()")
        )
        val toupcam = read("src/main/java/com/indigo/mobileobservatory/camera/toupcam/ToupcamCamera.kt")
        assertTrue(
            "opening a ToupTek camera must not probe binning by writing the device",
            !toupcam.contains("probeSupportedBins")
        )
        val eaf = read(
            "src/main/java/com/indigo/mobileobservatory/camera/toupcam/EAFController.kt"
        )
        assertTrue(eaf.contains("usbConnection = connection"))
    }

    @Test
    fun scanListsCamerasWithoutStartingPlayerOne() {
        val manager = read(
            "src/main/java/com/indigo/mobileobservatory/camera/DahengCameraManager.kt"
        )
        val enumStart = manager.indexOf("fun enumerateDevices()")
        val enumEnd = manager.indexOf("fun scanAccessories()")
        assertTrue(enumStart >= 0 && enumEnd > enumStart)
        val scan = manager.substring(enumStart, enumEnd)
        assertTrue(
            "listing cameras must not start the Player One SDK",
            !scan.contains("PlayerOneSdkHost.enumerate") && !scan.contains("PlayerOneSdkHost.ensureStarted")
        )
        assertTrue(scan.contains("SDK deferred"))
        val viewModel = read(
            "src/main/java/com/indigo/mobileobservatory/ui/viewmodel/CameraViewModel.kt"
        )
        val requestStart = viewModel.indexOf("fun requestConnect()")
        val requestEnd = viewModel.indexOf("fun disconnectCamera()")
        assertTrue(requestStart >= 0 && requestEnd > requestStart)
        val request = viewModel.substring(requestStart, requestEnd)
        assertTrue(request.contains("enumerateDevices()"))
        assertTrue(request.contains("_showDevicePicker.value = true"))
        assertTrue(!request.contains("connectCameraBySn"))
    }

    @Test
    fun guideCameraDoesNotTakeTheUsbDeviceHeldByMain() {
        val manager = read(
            "src/main/java/com/indigo/mobileobservatory/camera/DahengCameraManager.kt"
        )
        assertTrue(manager.contains("fun holdsUsbDevice"))
        assertTrue(manager.contains("claimUsbDevice"))
        val detach = manager.substring(
            manager.indexOf("ACTION_USB_DEVICE_DETACHED"),
            manager.indexOf("actionUsbPermission ->")
        )
        assertTrue(
            "unplugging one camera must not disconnect the other session",
            detach.contains("!holdsUsbDevice(usbDevice)")
        )
        val viewModel = read(
            "src/main/java/com/indigo/mobileobservatory/ui/viewmodel/CameraViewModel.kt"
        )
        val guideStart = viewModel.indexOf("fun requestGuideConnect()")
        val guideEnd = viewModel.indexOf("fun disconnectGuideCamera()")
        assertTrue(guideStart >= 0 && guideEnd > guideStart)
        val guide = viewModel.substring(guideStart, guideEnd)
        assertTrue(guide.contains("camerasAvailableToGuide"))
        assertTrue(guide.contains("holdsUsbDevice"))
        assertTrue(
            "an empty guide list still scans so a guide camera can connect on its own",
            guide.contains("listed.isEmpty()") && guide.contains("enumerateDevices()")
        )
    }

    @Test
    fun wiredOnStepConnectIsLoggedAndDoesNotRaceThePreviousClose() {
        val module = read("src/main/java/com/indigo/mobileobservatory/mount/MountModule.kt")
        assertTrue(module.contains("transportLock.withLock"))
        assertTrue(module.contains("FileLogger.i(TAG, \"disconnectMount\")"))
        assertTrue(module.contains("FileLogger.e(TAG, \"connectUsb failed\", e)"))
        val controller = read(
            "src/main/java/com/indigo/mobileobservatory/mount/Lx200MountController.kt"
        )
        assertTrue(controller.contains("fun handshakeLx200WithoutReset"))
        assertTrue(controller.contains("port.setDTR(false)"))
        assertTrue(controller.contains("FileLogger.i"))
    }

    @Test
    fun previewDownsampleIsDisplayOnly() {
        val processor = read(
            "src/main/java/com/indigo/mobileobservatory/camera/FrameProcessor.kt"
        )
        val bitmapStart = processor.indexOf("fun frameToBitmap(")
        val downStart = processor.indexOf("private fun downsampleForPreview(")
        assertTrue(bitmapStart >= 0 && downStart > bitmapStart)
        assertTrue(
            processor.substring(bitmapStart, downStart).contains("downsampleForPreview(")
        )
        val capture = read(
            "src/main/java/com/indigo/mobileobservatory/recording/FITSWriter.kt"
        )
        assertTrue(!capture.contains("downsampleForPreview"))
        assertTrue(!capture.contains("PreviewScale"))
    }

    @Test
    fun precisionGotoUnifiesEpochsAndExposesSixArcminTolerance() {
        val camera = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/CameraScreen.kt"
        )
        val viewModel = read(
            "src/main/java/com/indigo/mobileobservatory/ui/viewmodel/CameraViewModel.kt"
        )
        val starMap = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapScreen.kt"
        )
        val hud = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapHud.kt"
        )
        val mount = read(
            "src/main/java/com/indigo/mobileobservatory/mount/MountModule.kt"
        )
        val math = read(
            "src/main/java/com/indigo/mobileobservatory/mount/PrecisionGoto.kt"
        )
        assertTrue(camera.contains("frame = target.frame"))
        assertTrue(camera.contains("toleranceArcmin = toleranceArcmin"))
        assertTrue(viewModel.contains("EquatorialEpoch.toJnowHours(raHours, decDeg, frame)"))
        assertTrue(viewModel.contains("EquatorialEpoch.j2000DegToJnowHours(raDeg, decDeg)"))
        assertTrue(starMap.contains("PrecisionGotoMath.PREFS_TOLERANCE_ARCMIN"))
        assertTrue(starMap.contains("centerOnRaDec(ra, dec, frame = \"JNOW\")"))
        assertTrue(starMap.contains("onPrecisionGoto(target, currentPrecisionToleranceArcmin())"))
        assertTrue(hud.contains("R.string.precision_goto_tolerance"))
        assertTrue(math.contains("const val TOLERANCE_ARCMIN = 6.0"))
        assertTrue(mount.contains("if (controller.supportsSync)"))
        assertTrue(mount.contains("controller.syncTo(solved)"))
        assertTrue(mount.contains("PrecisionGotoMath.withinTolerance(errorArcmin, stopArcmin)"))
    }

    @Test
    fun starMapExposesVisualMountSyncWithoutExpandingTarget() {
        val starMap = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/StarMapScreen.kt"
        )
        val camera = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/CameraScreen.kt"
        )
        val viewModel = read(
            "src/main/java/com/indigo/mobileobservatory/ui/viewmodel/CameraViewModel.kt"
        )
        val mount = read(
            "src/main/java/com/indigo/mobileobservatory/mount/MountModule.kt"
        )
        val gotoIndex = starMap.indexOf("R.string.goto_label")
        val syncIndex = starMap.indexOf("R.string.sync_label")
        val expandedIndex = starMap.indexOf("if (targetExpanded)")
        assertTrue(gotoIndex >= 0)
        assertTrue(syncIndex >= 0)
        assertTrue(expandedIndex >= 0)
        assertTrue(syncIndex < expandedIndex)
        assertTrue(starMap.contains("defaultMinSize(minWidth = 0.dp)"))
        assertTrue(starMap.contains("softWrap = false"))
        assertTrue(starMap.contains("val canSyncMount = canSlewMount && mountSupportsSync"))
        assertTrue(starMap.contains("syncConfirmation = target"))
        assertTrue(starMap.contains("R.string.star_map_visual_sync_hint"))
        assertTrue(starMap.contains("cornerPanel = StarMapCornerPanel.NONE"))
        assertTrue(!starMap.contains("R.string.star_map_sync_visual_caption"))
        assertTrue(camera.contains("mountSupportsSync = viewModel.mountSupportsSync"))
        assertTrue(camera.contains("frame = target.frame"))
        assertTrue(viewModel.contains("fun syncMountToTarget("))
        assertTrue(viewModel.contains("EquatorialEpoch.toJnowHours(raHours, decDeg, frame)"))
        assertTrue(mount.contains("if (!controller.supportsSync)"))
        assertTrue(mount.contains("controller.syncTo(target)"))
        assertTrue(mount.contains("Visual sync: mount stays put"))
    }

    @Test
    fun sequenceHasItsOwnEditAndStatusPages() {
        val camera = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/CameraScreen.kt"
        )
        val sequence = read(
            "src/main/java/com/indigo/mobileobservatory/ui/screens/SequenceScreen.kt"
        )
        assertTrue(camera.contains("SEQUENCE"))
        assertTrue(camera.contains("R.string.tab_sequence"))
        assertTrue(camera.contains("SequenceScreen("))
        assertTrue(camera.contains("SequenceProgressStrip("))
        assertTrue(sequence.contains("R.string.sequence_edit"))
        assertTrue(sequence.contains("R.string.sequence_status"))
        assertTrue(!camera.contains("SequenceScreen(\n") || camera.contains("MainControlTab.SEQUENCE"))
    }

    private fun read(relative: String): String {
        val candidates = listOf(File(relative), File("app/$relative"))
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("missing $relative", file != null)
        return file!!.readText()
    }
}
