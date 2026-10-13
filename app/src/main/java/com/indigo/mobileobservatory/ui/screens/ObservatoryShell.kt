package com.indigo.mobileobservatory.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.EquatorialEpoch
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.sequence.SequenceFeature
import com.indigo.mobileobservatory.sequence.plannedFrames
import com.indigo.mobileobservatory.stacking.StackMode
import com.indigo.mobileobservatory.ui.AppOrientationMode
import com.indigo.mobileobservatory.ui.RememberAppOrientation
import com.indigo.mobileobservatory.ui.components.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.ui.viewmodel.UpdateViewModel
import java.io.File
import java.time.Instant

private val NavigationSaver = listSaver<ObservatoryNavigation, String>(
    save = { it.save() }, restore = { ObservatoryNavigation.restore(it) })
private val TargetSaver = listSaver<StarMapTarget?, String>(
    save = { target -> target?.let { listOf(it.name, it.raHours.toString(), it.decDegrees.toString(), it.frame, it.positionAngleDeg.toString()) } ?: emptyList() },
    restore = { if (it.isEmpty()) null else StarMapTarget(it[0], it[1].toDouble(), it[2].toDouble(), it[3], it[4].toDouble()) })

private fun frozenTaskTarget(target: StarMapTarget?): StarMapTarget? = target?.let {
    if (EquatorialEpoch.isJ2000(it.frame)) it else {
        val fixed = EquatorialEpoch.jnowToJ2000(EquatorialCoordinates(it.raHours * 15.0, it.decDegrees), Instant.now())
        it.copy(raHours = fixed.raDeg / 15.0, decDegrees = fixed.decDeg, frame = "J2000")
    }
}

/** One owner for workspaces, child pages and return sources. Device work stays in the VM. */
@Composable
fun CameraScreen(viewModel: CameraViewModel, redNightMode: Boolean = false,
                 onRedNightModeChange: (Boolean) -> Unit = {},
                 mediaNavigationRequest: com.indigo.mobileobservatory.media.MediaNavigationRequest? = null,
                 onMediaNavigationConsumed: (Long) -> Unit = {}) {
    var navigation by rememberSaveable(stateSaver = NavigationSaver) { mutableStateOf(ObservatoryNavigation()) }
    var target by rememberSaveable(stateSaver = TargetSaver) { mutableStateOf<StarMapTarget?>(null) }
    var quickDevice by rememberSaveable { mutableStateOf<String?>(null) }
    var quickSettings by rememberSaveable { mutableStateOf(false) }
    var playerFile by rememberSaveable { mutableStateOf<String?>(null) }
    var playerCategory by rememberSaveable { mutableStateOf(MediaCategory.RECENT) }
    var stackMode by rememberSaveable { mutableStateOf(StackMode.LIVE) }
    var pendingOpen by rememberSaveable { mutableStateOf<List<String>?>(null) }
    val savedScreens = rememberSaveableStateHolder()
    val settingsExits = remember { SettingsExitRegistry() }
    val immersion = remember { ObservatoryImmersion() }
    val stackDestination by viewModel.stacking.destination.collectAsState()
    val playerRequested by viewModel.showPlayer.collectAsState()
    val motion by viewModel.mountMotionState.collectAsState()
    LaunchedEffect(motion.isActive) { if (motion.isActive) viewModel.pointingRepository.invalidateCurrentDirections() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    DisposableEffect(lifecycleOwner, viewModel) {
        val observer = LifecycleEventObserver { _, event ->
            var host = context
            while (host is android.content.ContextWrapper && host !is android.app.Activity) host = host.baseContext
            if (event == Lifecycle.Event.ON_STOP && (host as? android.app.Activity)?.isChangingConfigurations != true) viewModel.stacking.onBackground()
            if (event == Lifecycle.Event.ON_START) viewModel.stacking.onForeground()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    fun performOpen(destination: ObservatoryDestination, argument: String?, category: String?) {
        focus.clearFocus()
        val previousPages = navigation.allPages
        navigation = navigation.open(destination, argument, category)
        if (navigation.current == ObservatoryDestination.STACK) {
            navigation.pages.last().argument?.takeIf { it != viewModel.stacking.destination.value?.name }
                ?.let { viewModel.stacking.open(StackMode.valueOf(it)) }
        }
        previousPages.filter { old -> navigation.allPages.none { it.id == old.id } }
            .forEach { savedScreens.removeState("page-${it.id}") }
    }
    val open: (ObservatoryDestination) -> Unit = { destination ->
        val argument = when (destination) {
            ObservatoryDestination.STACK -> viewModel.stacking.destination.value?.name ?: stackMode.name
            ObservatoryDestination.PLAYER -> playerFile
            else -> null
        }
        val category = if (destination == ObservatoryDestination.PLAYER) playerCategory.name else null
        if (navigation.open(destination, argument, category).workspace != navigation.workspace) {
            pendingOpen = listOf(destination.name, argument.orEmpty(), category.orEmpty())
            settingsExits.request("open-${destination.name}") { performOpen(destination, argument, category); pendingOpen = null }
        } else performOpen(destination, argument, category)
    }
    val back: () -> Unit = {
        val old = navigation.pages.lastOrNull()
        val parent = navigation.back()
        if (old?.destination == ObservatoryDestination.STACK && parent.allPages.none { it.destination == ObservatoryDestination.STACK }) viewModel.stacking.leave()
        if (parent.current == ObservatoryDestination.STACK) {
            parent.pages.last().argument?.let { viewModel.stacking.open(StackMode.valueOf(it)) }
        }
        navigation = parent
        old?.let { savedScreens.removeState("page-${it.id}") }
    }
    val selectNow: (ObservatoryWorkspace) -> Unit = { workspace ->
        focus.clearFocus()
        navigation = navigation.select(workspace)
        if (navigation.current == ObservatoryDestination.STACK) {
            navigation.pages.last().argument?.takeIf { it != viewModel.stacking.destination.value?.name }
                ?.let { viewModel.stacking.open(StackMode.valueOf(it)) }
        } else viewModel.stacking.leave()
    }
    SideEffect {
        ObservatoryWorkspace.entries.forEach { workspace ->
            settingsExits.registerAction("workspace-${workspace.name}") { selectNow(workspace) }
        }
        ObservatoryDestination.entries.forEach { destination ->
            settingsExits.registerAction("open-${destination.name}") {
                pendingOpen?.takeIf { it.firstOrNull() == destination.name }?.let { value ->
                    performOpen(destination, value[1].ifEmpty { null }, value[2].ifEmpty { null }); pendingOpen = null
                }
            }
        }
    }
    val select: (ObservatoryWorkspace) -> Unit = { workspace ->
        if (workspace != navigation.workspace) settingsExits.request("workspace-${workspace.name}") { selectNow(workspace) }
    }
    LaunchedEffect(mediaNavigationRequest?.id) {
        mediaNavigationRequest?.let { request ->
            playerFile = request.filePath
            playerCategory = MediaCategory.RECENT
            if (request.filePath == null && navigation.pages.isEmpty()) select(ObservatoryWorkspace.MEDIA)
            else open(ObservatoryDestination.PLAYER)
            onMediaNavigationConsumed(request.id)
        }
    }
    // Legacy capture controls publish requests; they now use the same destination as media/EAA.
    LaunchedEffect(playerRequested) {
        if (playerRequested) {
            playerFile = null; playerCategory = MediaCategory.RECENT
            open(ObservatoryDestination.PLAYER)
            viewModel.closePlayer()
        }
    }
    LaunchedEffect(stackDestination) {
        stackDestination?.let { mode ->
            stackMode = mode
            if (navigation.allPages.none { it.destination == ObservatoryDestination.STACK && it.argument == mode.name }) open(ObservatoryDestination.STACK)
        }
    }
    val openStack: (StackMode) -> Unit = { mode ->
        stackMode = mode; viewModel.stacking.open(mode); open(ObservatoryDestination.STACK)
    }
    val chooseTarget: (StarMapTarget) -> Unit = { selected ->
        target = selected
        // Browsing targets must not rename an active acquisition or rewrite its task target.
        val coordinates = EquatorialCoordinates(selected.raHours * 15.0, selected.decDegrees)
        val catalogDirection = if (EquatorialEpoch.isJ2000(selected.frame)) coordinates else EquatorialEpoch.jnowToJ2000(coordinates)
        viewModel.rememberSequenceSkyTarget(selected.name, catalogDirection.raDeg / 15.0, catalogDirection.decDeg, selected.positionAngleDeg)
        val livePhase = viewModel.stacking.live.state.value.phase
        val sequencePhase = viewModel.sequenceRuntime.state.value.phase
        if (!viewModel.isRecording.value && livePhase in setOf(com.indigo.mobileobservatory.stacking.LiveStackPhase.EMPTY,
                com.indigo.mobileobservatory.stacking.LiveStackPhase.CLOSED) &&
            sequencePhase !in setOf(com.indigo.mobileobservatory.sequence.SequencePhase.Running, com.indigo.mobileobservatory.sequence.SequencePhase.Paused)) {
            viewModel.setTargetName(selected.name)
        }
    }
    val pushTo: (StarMapTarget) -> Unit = { selected -> chooseTarget(selected); open(ObservatoryDestination.PUSH_TO) }
    val showRoot = navigation.current == null
    RememberAppOrientation(when {
        navigation.current in listOf(ObservatoryDestination.PLAYER, ObservatoryDestination.STACK) -> AppOrientationMode.SENSOR
        navigation.current == ObservatoryDestination.GUIDE -> AppOrientationMode.LANDSCAPE
        showRoot && navigation.workspace == ObservatoryWorkspace.CAPTURE -> AppOrientationMode.LANDSCAPE
        else -> AppOrientationMode.PORTRAIT
    })
    // Registered before child handlers: inner cards, modes, editors and drawers win first.
    BackHandler(enabled = navigation.canGoBack) { back() }
    val updateViewModel: UpdateViewModel = viewModel()
    val updateState by updateViewModel.state.collectAsState()
    UpdateAvailableDialog(updateState, updateViewModel::downloadAndInstall,
        updateViewModel::installOrRequestPermission, updateViewModel::dismissPrompt,
        updateViewModel::openInstallPermissionSettings)

    CompositionLocalProvider(LocalSettingsExitRegistry provides settingsExits, LocalObservatoryImmersion provides immersion) {
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        val rail = !immersion.active && (maxWidth >= 600.dp || (maxWidth >= 520.dp && maxWidth > maxHeight))
        val railLabels = maxWidth >= 600.dp
        val entries = listOf(
            ObservatoryNavEntry("STAR_MAP", stringResource(R.string.star_map), Icons.Default.Star),
            ObservatoryNavEntry("CAPTURE", stringResource(R.string.wf_capture), Icons.Default.CameraAlt),
            ObservatoryNavEntry("MEDIA", stringResource(R.string.wf_media), Icons.Default.PhotoLibrary),
            ObservatoryNavEntry("EQUIPMENT", stringResource(R.string.wf_equipment), Icons.Default.Build))
        val selectKey: (String) -> Unit = { select(ObservatoryWorkspace.valueOf(it)) }
        Row(Modifier.fillMaxSize()) {
            if (rail) ObservatoryNavRail(entries, navigation.workspace.name, selectKey, showLabels = railLabels)
            Column(Modifier.weight(1f).fillMaxSize()) {
                ObservatoryDeviceBar(viewModel, redNightMode, onRedNightModeChange,
                    onSettings = { open(ObservatoryDestination.SETTINGS) },
                    onDevice = { key -> if (key == "guide") open(ObservatoryDestination.GUIDE) else { quickSettings = false; quickDevice = key } })
                if (showRoot && navigation.workspace != ObservatoryWorkspace.CAPTURE) EaaSessionBar(viewModel) { openStack(StackMode.LIVE) }
                ObservatorySequenceSummary(viewModel, navigation.current, open)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    val workspaceStacks = navigation.workspaceStacks
                    ObservatoryWorkspace.entries.filter { it in workspaceStacks }.forEach { owner ->
                    key(owner) {
                    val stack = workspaceStacks.getValue(owner)
                    val retainedPages = listOf<ObservatoryPage?>(null) + stack
                    retainedPages.forEach { page ->
                        val screenKey = page?.let { "page-${it.id}" } ?: "workspace-${owner.name}"
                        key(screenKey) {
                            val pageNavigation = navigation.copy(workspace = owner,
                                pages = if (page == null) emptyList() else stack.takeWhile { it.id != page.id } + page,
                                suspendedPages = navigation.workspaceStacks - owner)
                            val visible = owner == navigation.workspace && page == navigation.pages.lastOrNull()
                            ObservatoryPageHost(visible) {
                                savedScreens.SaveableStateProvider(screenKey) {
                                    ObservatoryContent(viewModel, pageNavigation, target,
                                        if (page?.destination == ObservatoryDestination.STACK) page.argument?.let(StackMode::valueOf) ?: stackMode else stackMode,
                                        if (page?.destination == ObservatoryDestination.PLAYER) page.argument else null,
                                        if (page?.destination == ObservatoryDestination.PLAYER) page.category?.let(MediaCategory::valueOf) ?: MediaCategory.RECENT else MediaCategory.RECENT,
                                        redNightMode, onRedNightModeChange, open, back, select, chooseTarget, pushTo, openStack,
                                        onPlayer = { file, category -> playerFile = file?.absolutePath; playerCategory = category; open(ObservatoryDestination.PLAYER) },
                                        onDeviceSettings = { key -> quickSettings = true; quickDevice = key })
                                }
                            }
                        }
                    }
                    }
                    }
                }
                if (!immersion.active && !rail) ObservatoryBottomBar(entries, navigation.workspace.name, selectKey)
            }
        }
    }
    // Keep emergency motion control above every full-screen task and device sheet.
    if (!(showRoot && navigation.workspace == ObservatoryWorkspace.STAR_MAP && BuildConfig.STELLARIUM_ENABLED) || quickDevice != null) {
        MountMotionStopPopup(motion, viewModel::stopMountMotion)
    }
    quickDevice?.let { key ->
        DeviceQuickControlSheet(viewModel, key, onDismiss = { quickDevice = null },
            onOpenCamera = { quickDevice = null; select(ObservatoryWorkspace.CAPTURE) },
            onOpenMount = { quickDevice = null; select(ObservatoryWorkspace.EQUIPMENT) }, initialSettings = quickSettings)
    }
    }
}

@Composable
private fun ObservatoryContent(
    vm: CameraViewModel, navigation: ObservatoryNavigation, target: StarMapTarget?, stackMode: StackMode,
    playerFile: String?, playerCategory: MediaCategory, redNightMode: Boolean, onRedNightModeChange: (Boolean) -> Unit,
    open: (ObservatoryDestination) -> Unit, back: () -> Unit, select: (ObservatoryWorkspace) -> Unit,
    chooseTarget: (StarMapTarget) -> Unit, pushTo: (StarMapTarget) -> Unit, openStack: (StackMode) -> Unit,
    onPlayer: (File?, MediaCategory) -> Unit,
    onDeviceSettings: (String) -> Unit
) {
    val coordinates by vm.mountCoordinates.collectAsState()
    val mountCapabilities by vm.mountPointingCapabilities.collectAsState()
    val mountBusy by vm.mountBusy.collectAsState()
    val connection by vm.connectionState.collectAsState()
    val guideConnection by vm.guideConnectionState.collectAsState()
    val guideGeometry by vm.guideCameraGeometry.collectAsState()
    val opticsRevision by vm.pointingOpticsRevision.collectAsState()
    val roi by vm.roi.collectAsState()
    val binning by vm.binning.collectAsState()
    val opticsConfiguration = remember(opticsRevision, connection, guideConnection, guideGeometry, roi, binning) { vm.pointingOpticsConfiguration() }
    LaunchedEffect(connection, guideConnection) {
        if (connection is ConnectionState.Connected) vm.primaryReferenceCameraId()?.let { id ->
            vm.pointingRepository.observeConnectedCamera("primary", id)
        } else vm.pointingRepository.clearOpticalPathDirection("primary")
        if (guideConnection is ConnectionState.Connected) vm.auxiliaryReferenceCameraId()?.let { id ->
            vm.pointingRepository.observeConnectedCamera("secondary", id)
        } else vm.pointingRepository.clearOpticalPathDirection("secondary")
    }
    val cameraPixelSize = (connection as? ConnectionState.Connected)?.info?.pixelSizeUm
    val pointingEstimate by vm.pointingRepository.estimate.collectAsState()
    val directObservations by vm.pointingRepository.directObservations.collectAsState()
    val pointingSources = remember(pointingEstimate, directObservations) { listOfNotNull(pointingEstimate?.let { value ->
        com.indigo.mobileobservatory.astro.FovPointingCandidate(value.opticalPathId,
            com.indigo.mobileobservatory.astro.FovSkyAnchor(value.direction.raDeg / 15.0, value.direction.decDeg, "J2000"),
            value.source.name, if (value.propagated) com.indigo.mobileobservatory.astro.FovPointingKind.PROPAGATED
                else com.indigo.mobileobservatory.astro.FovPointingKind.CALIBRATED_OPTICAL,
            value.absoluteUtcMs, value.estimateUtcMs, value.uncertaintyDeg, true,
            value.relationshipVersion?.toString(), value.observationIds.sorted().joinToString(","), value.imageUpPositionAngleDeg)
    }) + directObservations.map { observation ->
        val pose = observation.pose
        val base = com.indigo.mobileobservatory.pointing.SkyPose.fromSolution(observation.direction, 0.0)
        val pa = pose?.let { Math.toDegrees(kotlin.math.atan2(it.up.dot(base.right), it.up.dot(base.up))) }
        com.indigo.mobileobservatory.astro.FovPointingCandidate(observation.identity.opticalPathId,
            com.indigo.mobileobservatory.astro.FovSkyAnchor(observation.direction.raDeg / 15.0, observation.direction.decDeg, "J2000"),
            observation.acquisitionDeviceId, com.indigo.mobileobservatory.astro.FovPointingKind.DIRECT_OPTICAL,
            observation.stamp.utcMidpointMs, observation.stamp.utcMidpointMs, observation.uncertaintyDeg,
            true, observationId = observation.id, positionAngleDeg = pa)
    } }
    when (navigation.current) {
        ObservatoryDestination.SETTINGS -> SettingsScreen(vm, back)
        ObservatoryDestination.PREPARATION -> ObservingPreparationScreen(open, back)
        ObservatoryDestination.POINTING_SETUP -> CalibrationWizardScreen(vm.pointingRepository, back,
            opticsConfiguration = opticsConfiguration,
            captureMainReference = if (connection is ConnectionState.Connected) ({ vm.capturePointingReference() }) else null,
            runAutomaticSampling = if (mountCapabilities.goto && !mountBusy && connection is ConnectionState.Connected)
                ({ targets, sample -> vm.runPointingCalibrationMoves(targets, sample) }) else null,
            automaticStartDirection = coordinates?.let { value ->
                val p = EquatorialEpoch.jnowToJ2000(EquatorialCoordinates(value.raDeg, value.decDeg), Instant.now())
                com.indigo.mobileobservatory.pointing.SkyDirection(p.raDeg, p.decDeg)
            },
            onAuxiliaryCalibration = { open(ObservatoryDestination.AUXILIARY_CALIBRATION) })
        ObservatoryDestination.AUXILIARY_CALIBRATION -> AuxiliaryCalibrationScreen(vm, back)
        ObservatoryDestination.PLATE_SOLVE -> PlateSolveScreen(mountCoordinates = coordinates,
            cameraPixelSizeUm = cameraPixelSize, onSolved = vm::recordPlateSolveResult, onBack = back)
        ObservatoryDestination.POLAR_ALIGNMENT -> {
            val site by vm.mountSite.collectAsState()
            val busy by vm.mountBusy.collectAsState()
            val moveStatus by vm.mountMoveStatus.collectAsState()
            PolarAlignmentScreen(mountCoordinates = coordinates, mountSite = site, mountBusy = busy,
                mountMoveStatus = moveStatus, cameraPixelSizeUm = cameraPixelSize,
                onReadMountSite = vm::readMountSite, onSyncPhoneSiteToMount = vm::syncPhoneSiteToMount,
                onMoveMountRaBy = vm::moveMountRaBy, onStopMountRaMove = vm::stopMountRaMove,
                onCaptureFits = { vm.captureTempFitsForPlateSolve() }, onBack = back)
        }
        ObservatoryDestination.GUIDE -> GuideScreen(vm, back)
        ObservatoryDestination.TARGETS -> TargetLibraryScreen(onBack = back,
            onGuideTo = { obj -> pushTo(StarMapTarget("${obj.id} · ${obj.name}", obj.raHours, obj.decDeg, "J2000")) },
            onSelectTarget = { obj -> chooseTarget(StarMapTarget("${obj.id} · ${obj.name}", obj.raHours, obj.decDeg, "J2000")); back() },
            onAddToSequence = { obj -> vm.addSequenceSkyTarget("${obj.id} · ${obj.name}", obj.raHours, obj.decDeg) },
            showSequenceActions = SequenceFeature.ENABLED)
        ObservatoryDestination.PUSH_TO -> {
            // A candidate selected elsewhere cannot silently retarget this retained task.
            var taskTarget by rememberSaveable(stateSaver = TargetSaver) { mutableStateOf(frozenTaskTarget(target)) }
            // The phone solver uses J2000. A moving mount target can instead be JNOW.
            val direction = remember(taskTarget) {
                taskTarget?.let { if (it.frame.equals("JNOW", true)) EquatorialEpoch.jnowToJ2000(
                    EquatorialCoordinates(it.raHours * 15.0, it.decDegrees), Instant.now()) else EquatorialCoordinates(it.raHours * 15.0, it.decDegrees) }
            }
            var phoneSelected by rememberSaveable { mutableStateOf(guideConnection !is ConnectionState.Connected) }
            if (direction != null && !phoneSelected) {
                PointingSourceChooser(onBack = back, onPhone = { phoneSelected = true },
                    onAuxiliary = { taskTarget?.let(chooseTarget); open(ObservatoryDestination.PUSH_TO_AUXILIARY) })
            } else if (direction != null) PushToScreen(onBack = back,
                pointingRepository = vm.pointingRepository,
                opticsConfiguration = opticsConfiguration,
                onCalibration = { open(ObservatoryDestination.POINTING_SETUP) },
                onAuxiliaryGuide = { taskTarget?.let(chooseTarget); open(ObservatoryDestination.PUSH_TO_AUXILIARY) },
                onOpenTargets = { open(ObservatoryDestination.TARGETS) },
                initialTargetName = taskTarget?.name, targetRaHours = direction.raDeg / 15.0, targetDecDeg = direction.decDeg,
                onTargetChanged = { obj ->
                    taskTarget = StarMapTarget("${obj.id} · ${obj.name}", obj.raHours, obj.decDeg, "J2000")
                    taskTarget?.let(chooseTarget)
                })
            else TargetLibraryScreen(onBack = back, onGuideTo = { obj ->
                taskTarget = StarMapTarget("${obj.id} · ${obj.name}", obj.raHours, obj.decDeg, "J2000")
                taskTarget?.let(chooseTarget)
            }, showSequenceActions = false)
        }
        ObservatoryDestination.PUSH_TO_AUXILIARY -> {
            var taskTarget by rememberSaveable(stateSaver = TargetSaver) { mutableStateOf(frozenTaskTarget(target)) }
            val direction = taskTarget?.let { selected ->
                val raw = EquatorialCoordinates(selected.raHours * 15.0, selected.decDegrees)
                val p = if (EquatorialEpoch.isJ2000(selected.frame)) raw else EquatorialEpoch.jnowToJ2000(raw, Instant.now())
                com.indigo.mobileobservatory.pointing.SkyDirection(p.raDeg, p.decDeg)
            }
            if (direction != null) AuxiliaryPushToScreen(vm, direction, taskTarget?.name.orEmpty(), back,
                onCalibration = { open(ObservatoryDestination.AUXILIARY_CALIBRATION) }, opticsConfiguration = opticsConfiguration)
            else TargetLibraryScreen(onBack = back, onGuideTo = { obj ->
                taskTarget = StarMapTarget("${obj.id} · ${obj.name}", obj.raHours, obj.decDeg, "J2000")
                taskTarget?.let(chooseTarget)
            }, showSequenceActions = false)
        }
        ObservatoryDestination.PHONE_DEBUG -> PhoneCameraDebugScreen(back)
        ObservatoryDestination.SEQUENCE -> if (SequenceFeature.ENABLED) SequenceScreen(vm, redNightMode,
            onOpenGuiding = { open(ObservatoryDestination.GUIDE) }, onOpenAccessories = { select(ObservatoryWorkspace.EQUIPMENT) })
        ObservatoryDestination.STACK -> StackingScreen(vm, stackMode, onBack = back,
            onCamera = { select(ObservatoryWorkspace.CAPTURE) },
            onOpenResult = { onPlayer(it, MediaCategory.STACK_RESULTS) })
        ObservatoryDestination.PLAYER -> PlayerScreen(vm.getRecordingsDir(), vm.getCapturesDir(), coordinates,
            onStackFile = { file -> vm.openPlanetaryStack(file); open(ObservatoryDestination.STACK) },
            initialFile = playerFile?.let(::File), initialCategory = playerCategory,
            excludeFile = vm.getActiveRecordingFile(), onBack = back)
        null -> when (navigation.workspace) {
            ObservatoryWorkspace.CAPTURE -> CaptureWorkspaceScreen(vm, redNightMode, onRedNightModeChange,
                onPlateSolve = { open(ObservatoryDestination.PLATE_SOLVE) },
                onTargets = { open(ObservatoryDestination.TARGETS) }, onGuide = { open(ObservatoryDestination.GUIDE) },
                onSequence = { open(ObservatoryDestination.SEQUENCE) }, selectedTarget = target?.name,
                onDeviceSettings = onDeviceSettings, onOpenLiveStack = { openStack(StackMode.LIVE) })
            ObservatoryWorkspace.STAR_MAP -> SkyWorkspaceScreen(vm, target, redNightMode, onRedNightModeChange,
                onTargets = { open(ObservatoryDestination.TARGETS) },
                onPreparation = { open(ObservatoryDestination.PREPARATION) }, onPushTo = pushTo,
                onTargetSelected = chooseTarget, onBack = back, pointingSources = pointingSources,
                opticalPathIds = mapOf(com.indigo.mobileobservatory.astro.OpticsTrainId.PRIMARY to "primary",
                    com.indigo.mobileobservatory.astro.OpticsTrainId.SECONDARY to "secondary"),
                onOpticalPathInvalidated = { train, reason -> vm.pointingRepository.invalidateOpticalPath(train.prefValue(), reason.name) })
            ObservatoryWorkspace.MEDIA -> PlayerScreen(vm.getRecordingsDir(), vm.getCapturesDir(), coordinates,
                onStackFile = { file -> vm.openPlanetaryStack(file); open(ObservatoryDestination.STACK) },
                initialCategory = MediaCategory.RECENT, excludeFile = vm.getActiveRecordingFile(),
                isWorkspace = true, onCamera = { select(ObservatoryWorkspace.CAPTURE) },
                onImport = { openStack(StackMode.PLANETARY) }, onBack = back)
            ObservatoryWorkspace.EQUIPMENT -> AccessoriesScreen(vm,
                onOpenCamera = { select(ObservatoryWorkspace.CAPTURE) }, onOpenMount = {}, onBack = back)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PointingSourceChooser(onBack: () -> Unit, onPhone: () -> Unit, onAuxiliary: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.wfp_source_title)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onPhone, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.wfp_source_phone)) }
            OutlinedButton(onClick = onAuxiliary, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.wfp_source_auxiliary)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ObservingPreparationScreen(open: (ObservatoryDestination) -> Unit, onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.wf_preparation)) },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) } }) }) { padding ->
        Column(Modifier.padding(padding).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { open(ObservatoryDestination.POLAR_ALIGNMENT) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.polar_alignment))
            }
            OutlinedButton(onClick = { open(ObservatoryDestination.POINTING_SETUP) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.wf_pointing_setup))
            }
        }
    }
}

@Composable
private fun ObservatorySequenceSummary(vm: CameraViewModel, current: ObservatoryDestination?,
                                       open: (ObservatoryDestination) -> Unit) {
    if (!SequenceFeature.ENABLED) return
    val state by vm.sequenceRuntime.state.collectAsState()
    val draft by vm.sequenceRuntime.draft.collectAsState()
    val paused = state.phase == com.indigo.mobileobservatory.sequence.SequencePhase.Paused
    if (current != ObservatoryDestination.SEQUENCE && (paused || state.phase == com.indigo.mobileobservatory.sequence.SequencePhase.Running)) {
        SequenceProgressStrip(title = state.currentClassName ?: stringResource(R.string.tab_sequence),
            detail = stringResource(R.string.sequence_progress, state.framesDone, draft.plannedFrames()), paused = paused,
            onOpen = { open(ObservatoryDestination.SEQUENCE) }, onPause = vm.sequenceRuntime::pause,
            onResume = vm.sequenceRuntime::resume, onStop = vm.sequenceRuntime::stop)
    }
}
