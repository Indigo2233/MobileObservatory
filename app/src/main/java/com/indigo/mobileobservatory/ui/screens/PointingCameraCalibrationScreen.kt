package com.indigo.mobileobservatory.ui.screens

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.astrometry.D50Manager
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.pointing.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.*
import java.time.Instant
import java.util.UUID

private enum class CalibrationStep { SETUP, ALIGN, SAMPLE }

/** Shared setup, adjustment, training and holdout flow. Only capture adapters differ. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CalibrationWizardScreen(viewModel: CameraViewModel, onBack: () -> Unit,
    opticsConfiguration: PointingOpticsConfiguration? = null,
    initialSource: PointingCameraSource = PointingCameraSource.PHONE,
    onPlateSolve: () -> Unit = {}, onDeviceControl: (String) -> Unit = {}, onGuide: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    val repository = viewModel.pointingRepository
    val task: PointingCalibrationTask = androidx.lifecycle.viewmodel.compose.viewModel(key = "pointing-camera-calibration")
    val draft by task.draft.collectAsStateWithPageLifecycle()
    val relations by repository.relationships.collectAsStateWithPageLifecycle()
    val mainConnection by viewModel.connectionState.collectAsStateWithPageLifecycle()
    val auxConnection by viewModel.guideConnectionState.collectAsStateWithPageLifecycle()
    val devices by viewModel.guideDevices.collectAsStateWithPageLifecycle()
    val guiding by viewModel.guideRunning.collectAsStateWithPageLifecycle()
    val guideCalibrating by viewModel.guideCalibrating.collectAsStateWithPageLifecycle()
    val settingBusy by viewModel.guideGainWriteInProgress.collectAsStateWithPageLifecycle()
    val exposureUs by viewModel.guideExposureUs.collectAsStateWithPageLifecycle()
    val pixelFormat by viewModel.guidePixelFormat.collectAsStateWithPageLifecycle()
    val formats by viewModel.guideSupportedPixelFormats.collectAsStateWithPageLifecycle()
    val auxReady by viewModel.guideReferenceReady.collectAsStateWithPageLifecycle()
    val motion by viewModel.mountMotionState.collectAsStateWithPageLifecycle()
    val capabilities by viewModel.mountPointingCapabilities.collectAsStateWithPageLifecycle()
    val mountBusy by viewModel.mountBusy.collectAsStateWithPageLifecycle()
    val coordinates by viewModel.mountCoordinates.collectAsStateWithPageLifecycle()
    val foreground = rememberPageForeground()
    val prefs = remember { context.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE) }
    var selectedSource by rememberSaveable { mutableStateOf(initialSource) }
    var installation by rememberSaveable(selectedSource) { mutableStateOf(runCatching {
        PointingInstallation.valueOf(prefs.getString("pointing_installation_${if (selectedSource == PointingCameraSource.PHONE) "phone" else "secondary"}", "FIXED")!!)
    }.getOrDefault(PointingInstallation.FIXED)) }
    var locked by rememberSaveable { mutableStateOf(false) }
    var mainCamera by rememberSaveable { mutableStateOf(false) }
    var step by rememberSaveable { mutableStateOf(CalibrationStep.SETUP) }
    var lensId by rememberSaveable { mutableStateOf(prefs.getString("pointing_camera_lens_id", null)) }
    var starId by rememberSaveable { mutableStateOf<Int?>(null) }
    var mirrored by rememberSaveable { mutableStateOf(false) }
    var tracking by rememberSaveable { mutableStateOf(false) }
    var centeringArcmin by rememberSaveable { mutableStateOf("5") }
    var options by rememberSaveable { mutableStateOf(false) }
    var details by rememberSaveable { mutableStateOf(false) }
    var showSource by rememberSaveable { mutableStateOf(false) }
    var showStars by rememberSaveable { mutableStateOf(false) }
    var showDevices by rememberSaveable { mutableStateOf(false) }
    var showSite by rememberSaveable { mutableStateOf(false) }
    var opticsTrain by rememberSaveable { mutableStateOf<OpticsTrainId?>(null) }
    var localOptics by remember { mutableStateOf(PointingOpticsConfiguration.load(AndroidOpticsPrefs(prefs))) }
    val optics = opticsConfiguration ?: localOptics
    val phone = selectedSource == PointingCameraSource.PHONE
    val sourcePath = if (phone) "phone" else "secondary"
    val terminal = if (mainCamera) PointingOpticsEndpoint.SENSOR else PointingOpticsEndpoint.EYEPIECE
    val opticsReady = optics.verificationField(PointingOpticsSelection(OpticsTrainId.PRIMARY, terminal)) != null
    val secondaryReady = OpticsTrainId.SECONDARY in optics.confirmedTrains &&
        optics.secondary.focalLengthMm(optics.telescopes)?.let { it.isFinite() && it > 0 } == true
    val setup = PointingCalibrationSetup(selectedSource, installation, locked)
    val phoneSource = remember { PhoneSkyAttitudeSource(context) }
    var cameras by remember { mutableStateOf<List<PhoneCameraCapability>>(emptyList()) }
    var catalog by remember { mutableStateOf<List<PhoneCatalogStar>>(emptyList()) }
    val sharedSite = rememberSavedObserverSite()
    var site by remember { mutableStateOf(sharedSite?.site) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraPermission by remember { mutableStateOf(false) }
    val database = remember { D50Manager(context.applicationContext) }
    var databaseReady by remember { mutableStateOf(false) }
    var waitingForCenter by remember { mutableStateOf(false) }
    var centerConfirmation by remember { mutableStateOf<CompletableDeferred<ObservationStamp>?>(null) }
    var plan by remember { mutableStateOf<AutomaticCalibrationPlan?>(null) }
    var planningJob by remember { mutableStateOf<Job?>(null) }
    var planning by remember { mutableStateOf(false) }
    val lens = cameras.firstOrNull { it.cameraId == lensId }
    val effectiveMirror = phone && mirrored
    val sourceName = if (phone) lens?.let { calibrationLensLabel(context, it) } ?: stringResource(R.string.wfc_phone)
        else (auxConnection as? ConnectionState.Connected)?.info?.name ?: stringResource(R.string.wfc_auxiliary)
    // A transient disconnect preserves the draft; replacing either sensor changes its key.
    var lastAuxId by rememberSaveable { mutableStateOf<String?>(null) }
    var lastMainId by rememberSaveable { mutableStateOf<String?>(null) }
    val auxId = viewModel.auxiliaryReferenceCameraId()
    val mainId = viewModel.primaryReferenceCameraId()
    LaunchedEffect(auxId, mainId) {
        auxId?.let { lastAuxId = it; repository.observeConnectedCamera("secondary", it) }
        mainId?.let { lastMainId = it; repository.observeConnectedCamera("primary", it) }
    }
    val key = "$selectedSource|${if (phone) lensId else lastAuxId}|$mainCamera|${if (mainCamera) lastMainId else null}|$installation|$effectiveMirror|$tracking|$centeringArcmin"
    val currentKey by rememberUpdatedState(key)
    val currentSourceId by rememberUpdatedState(if (phone) lensId else auxId)
    val currentMainId by rememberUpdatedState(mainId)
    val paths = listOf(sourcePath, "primary")
    val centeringReady = centeringArcmin.toDoubleOrNull()?.let { it.isFinite() && it in .1..20.0 } == true
    val readiness = PointingCalibrationReadiness(source = selectedSource, mainCamera = mainCamera,
        mainOpticsReady = opticsReady, auxiliaryOpticsReady = secondaryReady, siteReady = site != null,
        cameraPermission = cameraPermission, phoneLensReady = lens != null,
        auxiliaryConnected = auxConnection is ConnectionState.Connected, auxiliaryCaptureReady = auxReady,
        guiding = guiding || guideCalibrating, auxiliarySettingsBusy = settingBusy,
        auxiliaryExposureReady = exposureUs in 500_000f..5_000_000f,
        mainConnected = mainConnection is ConnectionState.Connected, databaseReady = databaseReady,
        centeringReady = centeringReady, installation = installation, locked = locked)
    val ready = readiness.canAcquire
    val currentReady by rememberUpdatedState(ready)
    val automaticReady = ready && setup.canCalibrate && mainCamera && capabilities.goto && !mountBusy
    val startDirection = coordinates?.let {
        val p = EquatorialEpoch.jnowToJ2000(EquatorialCoordinates(it.raDeg, it.decDeg), Instant.now())
        SkyDirection(p.raDeg, p.decDeg)
    }
    val firstDirection = draft.first?.referenceDirection
    val stars = remember(catalog, site, firstDirection) { site?.let { observer -> catalog.filter {
        it.hip != null && it.magnitude <= 2.5 && CoordinateTransform.j2000ToTopocentric(
            EquatorialCoordinates(it.raDeg, it.decDeg), Instant.now(), observer, null).altitudeDeg in 20.0..80.0 &&
            auxiliaryValidationStarAllowed(it, firstDirection)
    }.sortedBy { it.magnitude } } ?: emptyList() }
    val star = stars.firstOrNull { it.hip == starId }
    val lensKey = lens?.let { "${it.logicalParentId ?: it.cameraId}/${it.cameraId}" }
    val relation = relations.filter { it.source.opticalPathId == sourcePath &&
        (if (phone) lens?.let { camera -> if (camera.isPhysicalSubCamera) it.source.physicalLensId == lensKey
            else it.source.physicalLensId.startsWith("${camera.openableCameraId}/") } == true else it.source.deviceId == auxId) && it.target.opticalPathId == "primary"
    }.maxByOrNull { it.validatedUtcMs }

    fun refresh() { scope.launch {
        cameraPermission = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        try {
            databaseReady = withContext(Dispatchers.IO) { database.status().installed }
            cameras = withContext(Dispatchers.IO) { runCatching { PhoneCameraCapability.enumerateBackCameras(context).filter { it.supportsManualSensor } }.getOrDefault(emptyList()) }
            if (lensId == null) lensId = (cameras.firstOrNull { it.lensRole == PhoneLensRole.MAIN } ?: cameras.firstOrNull())?.cameraId
            catalog = withContext(Dispatchers.IO) { PhoneBrightStarCatalog.load(context).stars }
            if (PhoneSiteProvider.canResolveSite(context)) site = PhoneSiteProvider.currentSite(context)
        } catch (cancel: CancellationException) { throw cancel }
        catch (t: Throwable) { error = t.message }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }
    LaunchedEffect(foreground, sharedSite?.site) { if (foreground) refresh() }
    LaunchedEffect(phone, lens) { if (phone) lens?.let { phoneSource.prepareLens(it.cameraId, it.sensorOrientation) } }
    DisposableEffect(phoneSource, foreground, phone) {
        if (foreground && phone) phoneSource.start()
        onDispose { phoneSource.stop(); task.cancel(); centerConfirmation?.cancel(); planningJob?.cancel() }
    }
    LaunchedEffect(key) {
        if (draft.configurationKey.isNotEmpty() && draft.configurationKey != key) {
            task.reset(); centerConfirmation?.cancel(); step = CalibrationStep.SETUP
            error = context.getString(R.string.wfp_configuration_changed)
        }
        plan = null
    }
    val opticalSignature = "${optics.primary.telescopeId}/${optics.primary.focalLengthMm(optics.telescopes)}/${optics.primary.mode}/${optics.primary.sensorId}|" +
        if (phone) "phone" else "${optics.secondary.telescopeId}/${optics.secondary.focalLengthMm(optics.telescopes)}"
    var previousOptics by remember { mutableStateOf(opticalSignature) }
    LaunchedEffect(opticalSignature) {
        if (previousOptics != opticalSignature && (draft.first != null || draft.busy || draft.alignmentPair != null)) {
            task.reset(); step = CalibrationStep.SETUP; locked = false; error = context.getString(R.string.wf_optics_resample)
        }
        previousOptics = opticalSignature
    }
    fun stop() { task.cancel(); centerConfirmation?.cancel(); planningJob?.cancel(); planning = false }
    fun back() { when {
        opticsTrain != null -> opticsTrain = null
        showSite -> showSite = false
        draft.busy || planning -> stop()
        details -> details = false
        step == CalibrationStep.SETUP && options -> options = false
        draft.fitted != null -> task.clearValidation()
        draft.first != null -> task.reset()
        step != CalibrationStep.SETUP -> { task.reset(); step = CalibrationStep.SETUP }
        else -> { task.reset(); onBack() }
    } }
    BackHandler(enabled = foreground, onBack = ::back)

    suspend fun capture(): PointingCalibrationCapture {
        val observer = requireNotNull(site)
        val chosenStar = if (!mainCamera) requireNotNull(star) else null
        val expectedKey = key
        val expectedSource = if (phone) lensId else auxId
        val expectedMain = mainId
        if (!phone) {
            val pair = viewModel.captureAuxiliaryCalibrationPair(observer, tracking) { onCaptured ->
                if (mainCamera) viewModel.capturePointingReference(onCaptured = onCaptured)
                else {
                    val confirmation = CompletableDeferred<ObservationStamp>()
                    centerConfirmation = confirmation; waitingForCenter = true
                    val stamp = try { confirmation.await() } finally { centerConfirmation = null; waitingForCenter = false }
                    onCaptured(stamp)
                    val chosen = requireNotNull(chosenStar)
                    val id = "centered-${chosen.hip}-${UUID.randomUUID()}"
                    OpticalObservation(id, OpticalIdentity("telescope", "axis", "primary"), stamp,
                        SkyDirection(chosen.raDeg, chosen.decDeg), null, null, requireNotNull(centeringArcmin.toDoubleOrNull()) / 60,
                        OpticalObservationKind.CENTERED_STAR, id)
                }
            }
            check(currentKey == expectedKey && currentSourceId == expectedSource && (!mainCamera || currentMainId == expectedMain))
            return PointingCalibrationCapture(pair)
        }
        val camera = requireNotNull(lens)
        val confirmedUtc = System.currentTimeMillis()
        val confirmedElapsed = SystemClock.elapsedRealtime()
        val (result, main) = coroutineScope {
            val external = if (mainCamera) async { viewModel.capturePointingReference() } else null
            val result = phoneSource.captureAndSolve(observer, cameraId = camera.cameraId,
                exposureSeconds = 1.0.coerceIn(camera.captureExposureRange().start.toDouble(), camera.captureExposureRange().endInclusive.toDouble()),
                iso = PhoneManualExposure.ISO_AUTO, preferRaw = camera.supportsRaw, mirrored = effectiveMirror)
            result to external?.await()
        }
        val observation = result.observation ?: error(if (!result.success) result.message else context.getString(R.string.wfp_no_observation))
        val reference = main ?: run {
            val chosen = requireNotNull(chosenStar)
            require(observation.stamp.elapsedMidpointMs - confirmedElapsed in 0..30_000) { context.getString(R.string.wfp_recenter) }
            OpticalObservation("star-${chosen.hip}-${observation.id}", OpticalIdentity("telescope", "axis", "primary"),
                ObservationStamp(confirmedUtc, confirmedElapsed, 0, 2), SkyDirection(chosen.raDeg, chosen.decDeg), null, null,
                requireNotNull(centeringArcmin.toDoubleOrNull()) / 60, OpticalObservationKind.CENTERED_STAR, "center-${observation.id}")
        }
        val start = if (main != null) minOf(observation.stamp.elapsedMidpointMs - observation.stamp.exposureDurationMs / 2,
            main.stamp.elapsedMidpointMs - main.stamp.clockUncertaintyMs) else confirmedElapsed
        val end = maxOf(observation.stamp.elapsedMidpointMs + observation.stamp.exposureDurationMs / 2,
            reference.stamp.elapsedMidpointMs + reference.stamp.clockUncertaintyMs)
        val movement = phoneSource.motionBetween(start, end) ?: error(context.getString(R.string.wfp_recenter))
        val pair = ReferencePairing.pair(observation, reference, observer, tracking, movement)
        check(currentKey == expectedKey && currentSourceId == expectedSource && (!mainCamera || currentMainId == expectedMain))
        return PointingCalibrationCapture(pair, result.handEyeSample)
    }
    fun sample() {
        error = null
        task.sample(repository, key, paths, effectiveMirror, alignmentOnly = step == CalibrationStep.ALIGN,
            onFailure = { if (phone) it.message ?: context.getString(R.string.wfp_sample_failed) else viewModel.auxiliaryCalibrationFailure(it) }, capture = ::capture)
    }
    fun beginAdjustment() {
        stop(); task.reset(); repository.invalidateOpticalPath(sourcePath, "pointing_bracket_adjusted")
        locked = false; step = CalibrationStep.ALIGN; details = false; error = null
    }
    fun beginCalibration() { task.reset(); step = CalibrationStep.SAMPLE; error = null; details = false }
    fun previewPlan() {
        if (!automaticReady || draft.busy || planning) return
        planning = true; error = null
        planningJob = scope.launch {
            try {
                plan = withContext(Dispatchers.Default) { AutomaticCalibrationPlanner.plan(catalog, requireNotNull(site), Instant.now(),
                    if (effectiveMirror) 4 else 2, startDirection) }
                if (plan == null) error = context.getString(R.string.wfp_auto_unavailable)
            } catch (cancel: CancellationException) { throw cancel }
            catch (t: Throwable) { error = t.message }
            finally { planning = false }
        }
    }
    fun resolve(issue: CalibrationBlocker) {
        when (issue) {
            CalibrationBlocker.UNFIXED, CalibrationBlocker.UNLOCKED -> scope.launch { scroll.animateScrollTo(0) }
            CalibrationBlocker.SITE -> showSite = true
            CalibrationBlocker.PHONE_PERMISSION -> permission.launch(arrayOf(Manifest.permission.CAMERA))
            CalibrationBlocker.PHONE_LENS -> showSource = true
            CalibrationBlocker.MAIN_OPTICS -> opticsTrain = OpticsTrainId.PRIMARY
            CalibrationBlocker.AUXILIARY_OPTICS -> opticsTrain = OpticsTrainId.SECONDARY
            CalibrationBlocker.MAIN_CAMERA -> onDeviceControl("camera")
            CalibrationBlocker.AUXILIARY_CAMERA -> { viewModel.requestGuideConnect(); showDevices = true }
            CalibrationBlocker.DATABASE -> onPlateSolve()
            CalibrationBlocker.GUIDING -> onGuide()
            CalibrationBlocker.AUXILIARY_SETTINGS -> Unit
            CalibrationBlocker.AUXILIARY_FORMAT, CalibrationBlocker.AUXILIARY_EXPOSURE, CalibrationBlocker.CENTERING -> {
                step = CalibrationStep.SETUP; options = true
                scope.launch { withFrameNanos { }; scroll.animateScrollTo(scroll.maxValue) }
            }
        }
    }
    // UI is split by task step; acquisition parameters and coordinate provenance stay collapsed.
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.wf_pointing_setup)) }, navigationIcon = {
        IconButton(onClick = ::back) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(scroll).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MountMotionStopBanner(motion, viewModel::stopMountMotion)
            when {
                step == CalibrationStep.SETUP -> {
                    Text(stringResource(R.string.wfc_source), style = MaterialTheme.typography.titleSmall)
                    val sourceBlocked = readiness.issues.any { it in setOf(CalibrationBlocker.PHONE_PERMISSION,
                        CalibrationBlocker.PHONE_LENS, CalibrationBlocker.AUXILIARY_CAMERA) }
                    OutlinedButton(onClick = { showSource = true }, modifier = Modifier.fillMaxWidth().testTag("pointing-source"),
                        border = BorderStroke(1.dp, if (sourceBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (sourceBlocked) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)) { Text(sourceName) }
                    Text(stringResource(R.string.wfc_reference), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!mainCamera, { mainCamera = false }, label = { Text(stringResource(R.string.wfp_eyepiece)) })
                        FilterChip(mainCamera, { mainCamera = true }, label = { Text(stringResource(R.string.wfp_main_camera)) })
                    }
                    PointingInstallationChoice(installation, onChange = { installation = it; locked = false; task.reset() })
                    if (installation == PointingInstallation.UNFIXED) Text(stringResource(R.string.wfc_unfixed_hint), color = MaterialTheme.colorScheme.error)
                    else {
                        Text(stringResource(R.string.wfc_offset_ok), style = MaterialTheme.typography.bodySmall)
                        Row { Checkbox(locked, { locked = it }, modifier = Modifier.testTag("pointing-locked"),
                            colors = CheckboxDefaults.colors(uncheckedColor = MaterialTheme.colorScheme.error))
                            Text(stringResource(R.string.wfc_locked), color = if (locked) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error) }
                    }
                    if (relation != null) PointingRelationshipSummary(requireNotNull(relation), sourceName)
                    PointingCalibrationBlockers(readiness.issues, ::resolve)
                    if (setup.canAdjust) TextButton(onClick = ::beginAdjustment, enabled = ready,
                        modifier = Modifier.testTag("pointing-adjust")) { Text(stringResource(R.string.wfc_adjust)) }
                    Button(onClick = ::beginCalibration, enabled = readiness.canStart,
                        modifier = Modifier.fillMaxWidth().testTag("pointing-begin")) { Text(stringResource(R.string.wfp_begin)) }
                    PointingAdvancedToggle(options) { options = !options }
                    if (options) {
                        TextButton(onClick = { showSite = true }) { Text(stringResource(R.string.wf_observer_site)) }
                        OutlinedButton(onClick = { opticsTrain = OpticsTrainId.PRIMARY }) { Text(stringResource(R.string.wfc_main_optics)) }
                        if (!phone) {
                            OutlinedButton(onClick = { opticsTrain = OpticsTrainId.SECONDARY }) { Text(stringResource(R.string.wfc_source_optics)) }
                            Text(stringResource(R.string.wfa_exposure))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { listOf(.5f, 1f, 2f, 5f).forEach { seconds ->
                                FilterChip(kotlin.math.abs(exposureUs / 1_000_000f - seconds) < .01f,
                                    { viewModel.setGuideExposure(seconds * 1_000_000f) }, enabled = !settingBusy, label = { Text("${seconds}s") })
                            } }
                            Text(stringResource(R.string.wfa_raw_format))
                            FlowRow { formats.forEach { format -> FilterChip(pixelFormat == format,
                                { viewModel.setGuidePixelFormat(format) }, enabled = !settingBusy, label = { Text(format.displayName) }) } }
                        }
                        if (phone) Row { Checkbox(mirrored, { mirrored = it }); Text(stringResource(R.string.wfp_mirror)) }
                        Row { Checkbox(tracking, { tracking = it }); Text(stringResource(R.string.wfp_tracking)) }
                        if (!mainCamera) OutlinedTextField(centeringArcmin, { centeringArcmin = it },
                            label = { Text(stringResource(R.string.wfp_centering_error)) }, isError = !centeringReady, singleLine = true)
                        Text(stringResource(if (capabilities.goto) R.string.wfc_mount_goto else if (capabilities.readCoordinates)
                            R.string.wfc_mount_read else R.string.wfc_mount_manual), style = MaterialTheme.typography.bodySmall)
                        Text(stringResource(R.string.wfc_no_sync), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { repository.invalidateOpticalPath(sourcePath, "pointing_installation_changed"); task.reset(); locked = false }) {
                            Text(stringResource(R.string.wfc_install_changed))
                        }
                    }
                }
                draft.fitted != null && !draft.busy -> {
                    val fit = requireNotNull(draft.fitted)
                    Text(stringResource(if (draft.saved) R.string.wfp_saved else R.string.wfp_verified), style = MaterialTheme.typography.titleMedium)
                    PointingRelationshipSummary(fit, sourceName)
                    Text(stringResource(R.string.wfp_residual, fit.residualDeg * 60))
                    Button(onClick = {
                        if (draft.saved) { task.reset(); onBack() }
                        else if (task.save(repository, key, context.getString(R.string.wfp_configuration_changed))) {
                            prefs.edit().putString("pointing_camera_source", selectedSource.name)
                                .putString("pointing_camera_lens_id", if (phone) lensId else prefs.getString("pointing_camera_lens_id", null))
                                .putString("pointing_installation_$sourcePath", installation.name).apply()
                        }
                    }, modifier = Modifier.testTag("pointing-save")) { Text(stringResource(if (draft.saved) R.string.wfp_continue else R.string.wfp_save)) }
                }
                else -> {
                    Text(stringResource(if (step == CalibrationStep.ALIGN) R.string.wfc_adjust_title else if (draft.first == null)
                        R.string.wfp_first_sample else R.string.wfp_validation_sample), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(if (step == CalibrationStep.ALIGN) R.string.wfc_adjust_hint else if (mainCamera)
                        R.string.wfp_camera_pair else R.string.wfp_center_star))
                    if (effectiveMirror && step != CalibrationStep.ALIGN) Text(stringResource(R.string.wfp_mirror_sample, draft.mirrorTraining.size + 1))
                    if (!mainCamera) OutlinedButton(onClick = { showStars = true }, enabled = !draft.busy) {
                        Text(star?.let { it.name ?: "HIP ${it.hip}" } ?: stringResource(R.string.wfp_choose_star))
                    }
                    draft.alignmentPair?.let { pair ->
                        val offset = CalibrationAlignmentOffset.fromPair(pair)
                        Text(stringResource(R.string.wfc_separation, offset.separationDeg), style = MaterialTheme.typography.headlineSmall)
                        Text(stringResource(R.string.wfc_adjust_repeat), style = MaterialTheme.typography.bodySmall)
                    }
                    if (draft.busy || planning) {
                        if (waitingForCenter) {
                            Text(stringResource(R.string.wfa_center_now))
                            Button(onClick = { centerConfirmation?.complete(ObservationStamp(System.currentTimeMillis(), SystemClock.elapsedRealtime(), 0, 2)) }) {
                                Text(stringResource(R.string.wfa_confirm_center))
                            }
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(if (planning) stringResource(R.string.wfp_auto_planning) else draft.automaticProgress?.let {
                                stringResource(R.string.wfp_auto_progress, minOf(it.first + 1, it.second), it.second)
                            } ?: stringResource(R.string.wfp_sampling))
                        }
                        OutlinedButton(onClick = ::stop) { Text(stringResource(R.string.wfp_cancel)) }
                    } else {
                        PointingCalibrationBlockers(readiness.acquisitionIssues, ::resolve)
                        Button(onClick = { if (step == CalibrationStep.ALIGN) locked = false; sample() },
                            enabled = ready && (mainCamera || star != null), modifier = Modifier.testTag("pointing-sample")) {
                            Text(stringResource(if (step == CalibrationStep.ALIGN) R.string.wfc_measure else if (mainCamera)
                                R.string.wfp_capture_pair else if (phone) R.string.wfp_centered_capture else R.string.wfa_sample))
                        }
                        if (step == CalibrationStep.ALIGN) {
                            Row { Checkbox(locked, { locked = it }); Text(stringResource(R.string.wfc_locked)) }
                            OutlinedButton(onClick = ::beginCalibration, enabled = setup.canCalibrate && ready) { Text(stringResource(R.string.wfc_lock_continue)) }
                        } else if (automaticReady) TextButton(onClick = ::previewPlan) { Text(stringResource(R.string.wfp_auto_sampling)) }
                        TextButton(onClick = { task.reset(); step = CalibrationStep.SETUP }) { Text(stringResource(R.string.wfc_change_setup)) }
                    }
                }
            }
            (draft.error ?: error)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            val pair = draft.validation ?: draft.alignmentPair ?: draft.first
            if (pair != null) {
                TextButton(onClick = { details = !details }) { Text(stringResource(R.string.wfc_details)) }
                if (details) PointingPairDetails(pair, coordinates?.takeIf { capabilities.readCoordinates }?.let { it.raDeg to it.decDeg }, draft.fitted)
            }
        }
    }
    if (showSite) ObserverSiteDialog(site) { showSite = false; refresh() }
    opticsTrain?.let { train -> PointingOpticsConfigurationSheet(optics.primaryGeometry, optics.secondaryGeometry,
        onOpticalPathInvalidated = { changed, reason -> repository.invalidateOpticalPath(changed.prefValue(), reason.name) },
        onDismiss = { opticsTrain = null }, initialTrain = train,
        terminal = if (train == OpticsTrainId.PRIMARY) terminal else PointingOpticsEndpoint.SENSOR,
        restrictToTrain = true, onConfirmed = { localOptics = it; opticsTrain = null }) }
    if (showSource) AlertDialog(onDismissRequest = { showSource = false }, title = { Text(stringResource(R.string.wfc_source)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.wfc_phone), style = MaterialTheme.typography.titleSmall)
            cameras.forEach { camera -> TextButton(onClick = { task.reset(); selectedSource = PointingCameraSource.PHONE;
                lensId = camera.cameraId; locked = false; showSource = false }, modifier = Modifier.testTag("pointing-lens-${camera.cameraId}")) { Text(calibrationLensLabel(context, camera)) } }
            if (cameras.isEmpty()) TextButton(onClick = { task.reset(); selectedSource = PointingCameraSource.PHONE; locked = false; showSource = false },
                modifier = Modifier.testTag("pointing-source-phone")) { Text(stringResource(R.string.wfp_select_lens)) }
            TextButton(onClick = { task.reset(); selectedSource = PointingCameraSource.AUXILIARY; locked = false; showSource = false },
                modifier = Modifier.testTag("pointing-source-auxiliary")) { Text(stringResource(R.string.wfc_auxiliary)) }
        } }, confirmButton = { TextButton(onClick = { showSource = false }) { Text(stringResource(R.string.back)) } })
    if (showStars) AlertDialog(onDismissRequest = { showStars = false }, title = { Text(stringResource(R.string.wfp_choose_star)) },
        text = { Column { MountMotionStopBanner(motion, viewModel::stopMountMotion)
            AuxiliaryReferenceStarChoices(stars, firstDirection != null) { starId = it.hip; showStars = false }
        } }, confirmButton = { TextButton(onClick = { showStars = false }) { Text(stringResource(R.string.back)) } })
    if (showDevices) AlertDialog(onDismissRequest = { showDevices = false; viewModel.hideGuideDevicePicker() },
        title = { Text(stringResource(R.string.select_guide_camera)) }, text = { Column {
            MountMotionStopBanner(motion, viewModel::stopMountMotion)
            val available = viewModel.camerasAvailableToGuide(devices)
            if (available.isEmpty()) Text(stringResource(if (devices.isEmpty()) R.string.no_camera_found else R.string.select_different_guide_camera))
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { available.forEach { device ->
                TextButton(onClick = { viewModel.connectGuideCameraBySn(device.serialNumber); viewModel.hideGuideDevicePicker(); showDevices = false }) { Text("${device.name}\n${device.serialNumber}") }
            } }
        } }, confirmButton = { TextButton(onClick = { showDevices = false; viewModel.hideGuideDevicePicker() }) { Text(stringResource(R.string.back)) } })
    plan?.let { pending -> AutomaticCalibrationConfirmation(pending, enabled = automaticReady && !draft.busy,
        onDismiss = { plan = null }, onConfirm = {
            plan = null
            if (!pending.canStart(requireNotNull(site), Instant.now(), startDirection)) error = context.getString(R.string.wfp_auto_stale)
            else task.runAutomatic(repository, key, paths, effectiveMirror, onFailure = { it.message ?: context.getString(R.string.wfp_sample_failed) },
                run = { acquire -> withTimeout(pending.constraints.executionWindowMs) {
                    viewModel.runPointingCalibrationMoves(pending.targets.map { it.star }, acquire, beforeMove = {
                        check(currentReady && currentKey == key && currentSourceId == (if (phone) lensId else auxId) && currentMainId == mainId &&
                            task.draft.value.revisions.all(repository::isSameInstallation)) { context.getString(R.string.wfp_configuration_changed) }
                        if (!phone) check(viewModel.auxiliaryReferenceCameraId() == auxId && viewModel.guideReferenceReady.value &&
                            !viewModel.guideRunning.value && !viewModel.guideCalibrating.value && !viewModel.guideGainWriteInProgress.value &&
                            viewModel.guideExposureUs.value in 500_000f..5_000_000f) { context.getString(R.string.wfp_configuration_changed) }
                    })
                } }, capture = ::capture)
        }) }
}

private fun calibrationLensLabel(context: android.content.Context, camera: PhoneCameraCapability): String {
    val name = context.getString(when (camera.lensRole) {
        PhoneLensRole.MAIN -> R.string.wfc_lens_main
        PhoneLensRole.ULTRA_WIDE -> R.string.wfc_lens_wide
        PhoneLensRole.TELEPHOTO -> R.string.wfc_lens_tele
        PhoneLensRole.UNKNOWN -> R.string.wfc_phone
    })
    return listOfNotNull(name, camera.equivalentFocalLengthMm?.let { "${it.toInt()}mm" }, "#${camera.cameraId}").joinToString(" · ")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable internal fun PointingInstallationChoice(value: PointingInstallation, onChange: (PointingInstallation) -> Unit) {
    Text(stringResource(R.string.wfc_installation), style = MaterialTheme.typography.titleSmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PointingInstallation.entries.forEach { choice ->
        FilterChip(value == choice, { onChange(choice) }, modifier = Modifier.testTag("pointing-installation-${choice.name}"),
            label = { Text(stringResource(when (choice) { PointingInstallation.FIXED -> R.string.wfc_fixed
                PointingInstallation.ADJUSTABLE -> R.string.wfc_adjustable; PointingInstallation.UNFIXED -> R.string.wfc_unfixed })) })
    } }
}

@Composable internal fun PointingRelationshipSummary(value: OpticalCalibration, sourceName: String) {
    Text(stringResource(R.string.wfc_relationship, sourceName), style = MaterialTheme.typography.titleSmall)
    Text(stringResource(if (value.valid) R.string.wfc_relationship_valid else R.string.wfc_relationship_invalid),
        color = if (value.valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
}

@Composable private fun PointingPairDetails(pair: CalibrationPair, mount: Pair<Double, Double>?, fit: OpticalCalibration?) {
    val offset = CalibrationAlignmentOffset.fromPair(pair)
    fit?.let { Text(stringResource(if (it.model == RelationshipModel.AXIS_ONLY) R.string.wfp_axis_only else R.string.wfp_full_pose)) }
    Text(stringResource(R.string.wfc_measurement_time, java.text.DateFormat.getDateTimeInstance().format(java.util.Date(pair.camera.stamp.utcMidpointMs))))
    Text("${pair.camera.identity.deviceId} / ${pair.camera.identity.physicalLensId}", style = MaterialTheme.typography.bodySmall)
    Text(stringResource(R.string.wfc_source_coordinates, pair.camera.direction.raDeg, pair.camera.direction.decDeg))
    Text(stringResource(R.string.wfc_reference_coordinates, pair.referenceDirection.raDeg, pair.referenceDirection.decDeg))
    fit?.estimate(pair.camera, pair.reference.identity)?.let { Text(stringResource(R.string.wfc_estimated_coordinates, it.direction.raDeg, it.direction.decDeg)) }
    if (mount != null) Text(stringResource(R.string.wfc_mount_coordinates, mount.first, mount.second))
    Text(stringResource(R.string.wfc_components, offset.horizontalDeg, offset.verticalDeg, offset.uncertaintyDeg))
    Text(stringResource(R.string.wfc_coordinate_hint), style = MaterialTheme.typography.bodySmall)
}
