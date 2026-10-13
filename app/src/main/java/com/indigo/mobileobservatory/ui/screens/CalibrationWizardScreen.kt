package com.indigo.mobileobservatory.ui.screens

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.camera.PhoneCameraCapability
import com.indigo.mobileobservatory.camera.PhoneLensRole
import com.indigo.mobileobservatory.pointing.*
import kotlinx.coroutines.*
import java.time.Instant

/** Real observations only. Closing/canceling a draft never overwrites the saved relationship. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalibrationWizardScreen(
    repository: PointingRepository,
    onBack: () -> Unit,
    captureMainReference: (suspend () -> OpticalObservation)? = null,
    runAutomaticSampling: (suspend (List<PhoneCatalogStar>, suspend () -> Unit) -> Unit)? = null,
    automaticStartDirection: SkyDirection? = null,
    onAuxiliaryCalibration: (() -> Unit)? = null,
    opticsConfiguration: PointingOpticsConfiguration? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cameraPermissionGranted by remember { mutableStateOf(androidx.core.content.ContextCompat.checkSelfPermission(context,
        Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val source = remember { PhoneSkyAttitudeSource(context) }
    val targetIdentity = remember { OpticalIdentity("telescope", "axis", "primary") }
    var site by remember { mutableStateOf<ObserverSite?>(null) }
    var cameras by remember { mutableStateOf<List<PhoneCameraCapability>>(emptyList()) }
    var stars by remember { mutableStateOf<List<PhoneCatalogStar>>(emptyList()) }
    var automaticCatalog by remember { mutableStateOf<List<PhoneCatalogStar>>(emptyList()) }
    var lensId by rememberSaveable { mutableStateOf<String?>(null) }
    var starId by rememberSaveable { mutableStateOf<Int?>(null) }
    var setupComplete by rememberSaveable { mutableStateOf(false) }
    var fixed by rememberSaveable { mutableStateOf(false) }
    var cameraMode by rememberSaveable { mutableStateOf(false) }
    var showOptics by rememberSaveable { mutableStateOf(false) }
    val opticsPrefs = remember { context.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE) }
    var localOptics by remember { mutableStateOf(PointingOpticsConfiguration.load(AndroidOpticsPrefs(opticsPrefs))) }
    val workingOptics = opticsConfiguration ?: localOptics
    val sharedSite = rememberSavedObserverSite()
    LaunchedEffect(sharedSite?.site) { sharedSite?.site?.let { site = it } }
    val terminal = if (cameraMode) PointingOpticsEndpoint.SENSOR else PointingOpticsEndpoint.EYEPIECE
    val primarySelection = PointingOpticsSelection(OpticsTrainId.PRIMARY, terminal)
    val opticsReady = workingOptics.verificationField(primarySelection) != null
    var mirrored by rememberSaveable { mutableStateOf(false) }
    var tracking by rememberSaveable { mutableStateOf(false) }
    var centeringArcmin by rememberSaveable { mutableStateOf("5") }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var showStars by remember { mutableStateOf(false) }
    var showLens by remember { mutableStateOf(false) }
    var first by remember { mutableStateOf<CalibrationPair?>(null) }
    var trainingRevisions by remember { mutableStateOf<List<PointingRepository.CaptureRevision>>(emptyList()) }
    var validation by remember { mutableStateOf<CalibrationPair?>(null) }
    var fitted by remember { mutableStateOf<OpticalCalibration?>(null) }
    var mirrorTraining by remember { mutableStateOf<List<HandEyeSample>>(emptyList()) }
    var fittedHandEye by remember { mutableStateOf<HandEyeCalibration?>(null) }
    var busy by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var pendingAutomaticPlan by remember { mutableStateOf<AutomaticCalibrationPlan?>(null) }
    var automaticProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var planningAutomatic by remember { mutableStateOf(false) }
    val stored by repository.relationships.collectAsState()
    val storedHandEyes by repository.handEyes.collectAsState()
    val foreground = rememberPageForeground()
    val star = stars.firstOrNull { it.hip == starId }
    val lens = cameras.firstOrNull { it.cameraId == lensId }
    val primaryConfigurationSignature = workingOptics.primary.let { config ->
        "${config.telescopeId}/${config.focalLengthMm(workingOptics.telescopes)}/${config.mode}/" +
            if (config.mode == FovInstrumentMode.SENSOR) "${config.sensorId}/${workingOptics.primaryGeometry?.deviceId}" else "eyepiece-axis"
    }
    var previousPrimarySignature by remember { mutableStateOf(primaryConfigurationSignature) }
    LaunchedEffect(primaryConfigurationSignature) {
        if (previousPrimarySignature != primaryConfigurationSignature &&
            (first != null || validation != null || fitted != null || mirrorTraining.isNotEmpty() || busy)) {
            job?.cancel(); busy = false; planningAutomatic = false; pendingAutomaticPlan = null; automaticProgress = null
            first = null; trainingRevisions = emptyList(); validation = null; fitted = null; mirrorTraining = emptyList(); fittedHandEye = null
            saved = false; setupComplete = false; cameraMode = workingOptics.primary.mode == FovInstrumentMode.SENSOR
            error = context.getString(R.string.wf_optics_resample)
        }
        previousPrimarySignature = primaryConfigurationSignature
    }
    LaunchedEffect(lensId, lens) { lens?.let { source.prepareLens(it.cameraId, it.sensorOrientation) } }

    fun load() {
        scope.launch {
            try {
                site = PhoneSiteProvider.currentSite(context)
                cameras = withContext(Dispatchers.IO) { PhoneCameraCapability.enumerateBackCameras(context).filter { it.supportsManualSensor } }
                if (cameras.none { it.cameraId == lensId }) lensId = (cameras.firstOrNull { it.lensRole == PhoneLensRole.MAIN } ?: cameras.firstOrNull())?.cameraId
                val observer = site ?: return@launch
                val catalog = withContext(Dispatchers.IO) { PhoneBrightStarCatalog.load(context).stars }
                automaticCatalog = catalog
                stars = withContext(Dispatchers.Default) { catalog.filter {
                    it.hip != null && it.magnitude <= 2.5 && CoordinateTransform.j2000ToTopocentric(
                        EquatorialCoordinates(it.raDeg, it.decDeg), Instant.now(), observer, null).altitudeDeg in 20.0..80.0
                }.sortedBy { it.magnitude } }
            } catch (cancel: CancellationException) { throw cancel }
            catch (t: Throwable) { error = t.message }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        cameraPermissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
        load()
    }
    LaunchedEffect(Unit) { load() }
    DisposableEffect(source, foreground) {
        if (foreground) {
            cameraPermissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED
            source.start()
        }
        onDispose { source.stop(); job?.cancel(); busy = false }
    }
    fun back() {
        if (showOptics) showOptics = false
        else if (busy) job?.cancel()
        else if (fitted != null) { fitted = null; fittedHandEye = null; saved = false }
        else if (first != null) { first = null; mirrorTraining = emptyList(); fittedHandEye = null }
        else if (setupComplete) setupComplete = false
        else onBack()
    }
    BackHandler(onBack = ::back)

    suspend fun acquire() = withContext(Dispatchers.Main.immediate) {
        val samplingRevisions = listOf(repository.captureRevision("primary"), repository.captureRevision("phone"))
        if (first != null) check(trainingRevisions.size == 2 && trainingRevisions.all(repository::isSameInstallation)) {
            context.getString(R.string.wf_optics_resample)
        }
        val observer = requireNotNull(site)
        val camera = requireNotNull(lens)
        val referenceStar = if (!cameraMode) requireNotNull(star) else null
        val confirmedUtc = System.currentTimeMillis()
        val confirmedElapsed = SystemClock.elapsedRealtime()
        val (phoneResult, main) = coroutineScope {
            val external = if (cameraMode) async { requireNotNull(captureMainReference).invoke() } else null
            val phone = source.captureAndSolve(observer, cameraId = camera.cameraId,
                exposureSeconds = 1.0.coerceIn(camera.captureExposureRange().start.toDouble(), camera.captureExposureRange().endInclusive.toDouble()),
                iso = com.indigo.mobileobservatory.camera.PhoneManualExposure.ISO_AUTO,
                preferRaw = camera.supportsRaw, mirrored = mirrored)
            phone to external?.await()
        }
                val phone = phoneResult.observation ?: error(if (!phoneResult.success) phoneResult.message else context.getString(R.string.wfp_no_observation))
        val reference = main ?: run {
            val chosen = requireNotNull(referenceStar)
            val elapsed = phone.stamp.elapsedMidpointMs - confirmedElapsed
            require(elapsed in 0..30_000) { context.getString(R.string.wfp_recenter) }
            val movement = source.motionBetween(confirmedElapsed,
                phone.stamp.elapsedMidpointMs + phone.stamp.exposureDurationMs / 2)
            require(movement != null && movement <= 0.05) { context.getString(R.string.wfp_recenter) }
            val centered = SkyDirection(chosen.raDeg, chosen.decDeg)
            OpticalObservation("star-${chosen.hip}-${phone.id}", targetIdentity,
                ObservationStamp(confirmedUtc, confirmedElapsed, 0, 2),
                centered, null, null, requireNotNull(centeringArcmin.toDoubleOrNull()) / 60.0,
                OpticalObservationKind.CENTERED_STAR, "centering-${phone.id}")
        }
        val pair = if (main != null) {
            val start = minOf(phone.stamp.elapsedMidpointMs - phone.stamp.exposureDurationMs / 2,
                main.stamp.elapsedMidpointMs - main.stamp.clockUncertaintyMs)
            val end = maxOf(phone.stamp.elapsedMidpointMs + phone.stamp.exposureDurationMs / 2,
                main.stamp.elapsedMidpointMs + main.stamp.clockUncertaintyMs)
            val movement = source.motionBetween(start, end) ?: error(context.getString(R.string.wfp_recenter))
            ReferencePairing.pair(phone, main, observer, tracking, movement)
        } else ReferencePairing.pair(phone, reference, observer, tracking,
            requireNotNull(source.motionBetween(confirmedElapsed, phone.stamp.elapsedMidpointMs + phone.stamp.exposureDurationMs / 2)))
        pair.validate()
        currentCoroutineContext().ensureActive()
        check(samplingRevisions.all(repository::isSameInstallation)) { context.getString(R.string.wf_optics_resample) }
        val training = first
        val handEyeSample = if (mirrored) requireNotNull(phoneResult.handEyeSample) { context.getString(R.string.wfp_no_observation) } else null
        if (mirrored && mirrorTraining.size < 3) {
            if (training == null) { first = pair; trainingRevisions = samplingRevisions }
            mirrorTraining = mirrorTraining + requireNotNull(handEyeSample)
            starId = null
        }
        else if (training == null) { first = pair; trainingRevisions = samplingRevisions; starId = null }
        else {
            val handEye = if (mirrored) HandEyeCalibrationFitter.fit(mirrorTraining, requireNotNull(handEyeSample),
                previousVersion = storedHandEyes.filter { it.source == phone.identity && it.geometry == phone.geometry }.maxOfOrNull { it.version } ?: 0) else null
            fitted = OpticalCalibrationFitter.fit(training, pair,
                previousVersion = stored.filter { it.source == phone.identity && it.target == reference.identity }.maxOfOrNull { it.version } ?: 0)
            fittedHandEye = handEye
            validation = pair
        }
    }

    fun sample() {
        if (busy) return
        error = null; busy = true; fitted = null; saved = false
        job = scope.launch {
            try { acquire() }
            catch (cancel: CancellationException) { throw cancel }
            catch (t: Throwable) { error = t.message ?: context.getString(R.string.wfp_sample_failed) }
            finally { busy = false; job = null }
        }
    }

    fun previewAutomaticPlan() {
        if (busy || !cameraMode || !fixed || captureMainReference == null || runAutomaticSampling == null) return
        val observer = site ?: return
        error = null; busy = true; planningAutomatic = true
        job = scope.launch {
            try {
                pendingAutomaticPlan = withContext(Dispatchers.Default) {
                    AutomaticCalibrationPlanner.plan(automaticCatalog, observer, Instant.now(), if (mirrored) 4 else 2, automaticStartDirection)
                }
                if (pendingAutomaticPlan == null) error = context.getString(R.string.wfp_auto_unavailable)
            } catch (cancel: CancellationException) { throw cancel }
            catch (t: Throwable) { error = t.message ?: context.getString(R.string.wfp_auto_unavailable) }
            finally { busy = false; planningAutomatic = false; job = null }
        }
    }

    fun startAutomatic(plan: AutomaticCalibrationPlan) {
        val run = runAutomaticSampling ?: return
        val observer = site ?: return
        if (!cameraMode || !fixed || lens == null || captureMainReference == null || busy) return
        pendingAutomaticPlan = null
        if (!plan.canStart(observer, Instant.now(), automaticStartDirection)) {
            error = context.getString(R.string.wfp_auto_stale); return
        }
        error = null; busy = true; saved = false
        first = null; validation = null; fitted = null; fittedHandEye = null; mirrorTraining = emptyList()
        automaticProgress = 1 to plan.targets.size
        job = scope.launch {
            try {
                var completed = 0
                withTimeout(plan.constraints.executionWindowMs) {
                    run(plan.targets.map { it.star }) {
                        check(completed < plan.targets.size)
                        acquire() // Acquisition/fit exceptions escape to the motion owner, ending all later GOTO.
                        completed++
                        automaticProgress = minOf(completed + 1, plan.targets.size) to plan.targets.size
                    }
                }
                check(completed == plan.targets.size && fitted != null && (!mirrored || fittedHandEye != null)) {
                    context.getString(R.string.wfp_sample_failed)
                }
            } catch (timeout: TimeoutCancellationException) { error = context.getString(R.string.wfp_auto_timeout) }
            catch (cancel: CancellationException) { throw cancel }
            catch (t: Throwable) { error = t.message ?: context.getString(R.string.wfp_sample_failed) }
            finally { busy = false; automaticProgress = null; job = null }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.wf_pointing_setup)) }, navigationIcon = {
        IconButton(onClick = ::back) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                !setupComplete -> {
                    Text(stringResource(R.string.wfp_setup), style = MaterialTheme.typography.titleMedium)
                    Row { Checkbox(fixed, { fixed = it }); Text(stringResource(R.string.wfp_fixed)) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(!cameraMode, { cameraMode = false }, label = { Text(stringResource(R.string.wfp_eyepiece)) })
                        FilterChip(cameraMode, { cameraMode = true }, label = { Text(stringResource(R.string.wfp_main_camera)) })
                    }
                    OutlinedButton(onClick = { showOptics = true }) {
                        Text(stringResource(if (opticsReady) R.string.wf_optics_confirmed else R.string.wf_optics_title))
                    }
                    OutlinedButton(onClick = { showLens = true }) { Text(lens?.displayLabel ?: stringResource(R.string.wfp_select_lens)) }
                    if (site == null || lens == null || !cameraPermissionGranted) {
                        OutlinedButton(onClick = { permissions.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text(stringResource(R.string.wfp_permissions)) }
                    }
                    TextButton(onClick = { showOptions = !showOptions }) { Text(stringResource(R.string.wfp_other_options)) }
                    if (showOptions) {
                        Row { Checkbox(mirrored, { mirrored = it }); Text(stringResource(R.string.wfp_mirror)) }
                        Row { Checkbox(tracking, { tracking = it }); Text(stringResource(R.string.wfp_tracking)) }
                        if (!cameraMode) OutlinedTextField(centeringArcmin, { centeringArcmin = it },
                            label = { Text(stringResource(R.string.wfp_centering_error)) }, singleLine = true,
                            isError = centeringArcmin.toDoubleOrNull()?.let { it.isFinite() && it in .1..60.0 } != true)
                        TextButton(onClick = { repository.invalidateSource("phone", context.getString(R.string.wfp_install_changed)) }) { Text(stringResource(R.string.wfp_install_changed)) }
                        onAuxiliaryCalibration?.let { open -> TextButton(onClick = open) { Text(stringResource(R.string.wfa_title)) } }
                    }
                    if (cameraMode && captureMainReference == null) Text(stringResource(R.string.wfp_camera_unavailable))
                    Button(onClick = {
                        if (PointingOpticsConfiguration.load(AndroidOpticsPrefs(opticsPrefs), workingOptics.primaryGeometry,
                                workingOptics.secondaryGeometry).verificationField(primarySelection) == null) showOptics = true
                        else { setupComplete = true; first = null; trainingRevisions = emptyList(); validation = null; mirrorTraining = emptyList(); fittedHandEye = null }
                    }, enabled = cameraPermissionGranted && opticsReady && fixed && lens != null && site != null &&
                        (if (cameraMode) captureMainReference != null else centeringArcmin.toDoubleOrNull()?.let { it.isFinite() && it in .1..60.0 } == true)) { Text(stringResource(R.string.wfp_begin)) }
                }
                fitted != null -> {
                    val value = requireNotNull(fitted)
                    Text(stringResource(if (saved) R.string.wfp_saved else R.string.wfp_verified), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.wfp_residual, value.residualDeg * 60))
                    fittedHandEye?.let { Text(stringResource(R.string.wfp_sensor_residual, it.validationResidualDeg * 60)) }
                    Text(stringResource(if (value.model == RelationshipModel.AXIS_ONLY) R.string.wfp_axis_only else R.string.wfp_full_pose))
                    Button(onClick = { if (saved) onBack() else runCatching {
                        check(trainingRevisions.size == 2) { context.getString(R.string.wf_optics_resample) }
                        repository.saveValidated(value,
                        listOf(requireNotNull(first), requireNotNull(validation)), fittedHandEye, expectedRevisions = trainingRevisions) }.onSuccess { saved = true }.onFailure { error = it.message } }) {
                        Text(stringResource(if (saved) R.string.wfp_continue else R.string.wfp_save))
                    }
                }
                else -> {
                    Text(if (mirrored) stringResource(R.string.wfp_mirror_sample, mirrorTraining.size + 1)
                        else stringResource(if (first == null) R.string.wfp_first_sample else R.string.wfp_validation_sample), style = MaterialTheme.typography.titleMedium)
                    if (mirrored) Text(stringResource(R.string.wfp_mirror_directions))
                    Text(stringResource(if (cameraMode) R.string.wfp_camera_pair else R.string.wfp_center_star))
                    if (!cameraMode) OutlinedButton(onClick = { showStars = true }, enabled = !busy) {
                        Text(star?.let { it.name ?: "HIP ${it.hip}" } ?: stringResource(R.string.wfp_choose_star))
                    }
                    if (busy) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(if (planningAutomatic) stringResource(R.string.wfp_auto_planning) else automaticProgress?.let {
                            stringResource(R.string.wfp_auto_progress, it.first, it.second)
                        } ?: stringResource(R.string.wfp_sampling))
                    }
                    Button(onClick = { if (busy) job?.cancel() else sample() }, enabled = busy || cameraMode || star != null) {
                        Text(stringResource(if (busy && automaticProgress != null) R.string.wfp_auto_cancel else if (busy) R.string.wfp_cancel else if (cameraMode) R.string.wfp_capture_pair else R.string.wfp_centered_capture))
                    }
                    if (cameraMode && captureMainReference != null && runAutomaticSampling != null && fixed && site != null && lens != null && !busy) {
                        TextButton(onClick = ::previewAutomaticPlan) { Text(stringResource(R.string.wfp_auto_sampling)) }
                    }
                }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (error != null && mirrored && mirrorTraining.isNotEmpty() && !busy) {
                TextButton(onClick = { first = null; validation = null; mirrorTraining = emptyList(); fitted = null; fittedHandEye = null; error = null }) {
                    Text(stringResource(R.string.wfp_resample))
                }
            }
        }
    }
    if (showOptics) PointingOpticsConfigurationSheet(workingOptics.primaryGeometry, workingOptics.secondaryGeometry,
        onOpticalPathInvalidated = { train, reason -> repository.invalidateOpticalPath(train.prefValue(), reason.name) },
        onDismiss = { showOptics = false }, initialTrain = OpticsTrainId.PRIMARY, terminal = terminal,
        restrictToTrain = true, onConfirmed = { localOptics = it; showOptics = false })
    pendingAutomaticPlan?.let { plan -> AutomaticCalibrationConfirmation(plan,
        enabled = cameraMode && captureMainReference != null && runAutomaticSampling != null && fixed && site != null && lens != null && !busy,
        onConfirm = { startAutomatic(plan) }, onDismiss = { pendingAutomaticPlan = null }) }
    if (showLens) AlertDialog(onDismissRequest = { showLens = false }, title = { Text(stringResource(R.string.wfp_select_lens)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) { cameras.forEach { camera ->
            TextButton(onClick = { lensId = camera.cameraId; first = null; fitted = null; mirrorTraining = emptyList(); fittedHandEye = null; showLens = false }) { Text(camera.displayLabel) }
        } } }, confirmButton = { TextButton(onClick = { showLens = false }) { Text(stringResource(R.string.back)) } })
    if (showStars) AlertDialog(onDismissRequest = { showStars = false }, title = { Text(stringResource(R.string.wfp_choose_star)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (stars.isEmpty()) Text(stringResource(R.string.wfp_no_stars))
            stars.forEach { candidate -> TextButton(onClick = { starId = candidate.hip; showStars = false }) { Text(candidate.name ?: "HIP ${candidate.hip}") } }
        } }, confirmButton = { TextButton(onClick = { showStars = false }) { Text(stringResource(R.string.back)) } })
}

@Composable internal fun AutomaticCalibrationConfirmation(plan: AutomaticCalibrationPlan, enabled: Boolean = true,
    onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.wfp_auto_sampling)) },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.wfp_auto_confirmation))
            Text(stringResource(R.string.wfp_auto_range, plan.constraints.minimumAltitudeDeg, plan.constraints.maximumAltitudeDeg, plan.constraints.maximumStepDeg))
            plan.targets.forEachIndexed { index, target ->
                Text(stringResource(R.string.wfp_auto_target, index + 1, target.star.name ?: "HIP ${target.star.hip}", target.altitudeDeg, target.azimuthDeg))
            }
        } }, confirmButton = { TextButton(onClick = onConfirm, enabled = enabled) { Text(stringResource(R.string.wfp_auto_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
