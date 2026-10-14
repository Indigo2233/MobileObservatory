package com.indigo.mobileobservatory.ui.screens

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.astrometry.D50Manager
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.pointing.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.*
import java.time.Instant

/** The guide-role camera is a secondary optical sensor. No phone/IMU is required by this flow. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuxiliaryCalibrationScreen(viewModel: CameraViewModel, onBack: () -> Unit,
    onPlateSolve: () -> Unit = {}, onDeviceControl: (String) -> Unit = {}, onGuide: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val task: AuxiliaryCalibrationTask = androidx.lifecycle.viewmodel.compose.viewModel(key = "auxiliary-optical-calibration")
    val draft by task.draft.collectAsState()
    val connected by viewModel.guideConnectionState.collectAsState()
    val mainConnection by viewModel.connectionState.collectAsState()
    val status by viewModel.guideStatus.collectAsState()
    val devices by viewModel.guideDevices.collectAsState()
    val preview by viewModel.guidePreviewBitmap.collectAsState()
    val guiding by viewModel.guideRunning.collectAsState()
    val guideCalibration by viewModel.guideCalibrating.collectAsState()
    val settingBusy by viewModel.guideGainWriteInProgress.collectAsState()
    val exposureUs by viewModel.guideExposureUs.collectAsState()
    val pixelFormat by viewModel.guidePixelFormat.collectAsState()
    val formats by viewModel.guideSupportedPixelFormats.collectAsState()
    val referenceReady by viewModel.guideReferenceReady.collectAsState()
    val motion by viewModel.mountMotionState.collectAsState()
    var site by remember { mutableStateOf<ObserverSite?>(null) }
    var stars by remember { mutableStateOf<List<PhoneCatalogStar>>(emptyList()) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var fixed by rememberSaveable { mutableStateOf(false) }
    var mainCamera by rememberSaveable { mutableStateOf(false) }
    var tracking by rememberSaveable { mutableStateOf(false) }
    var setupComplete by rememberSaveable { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    var centeringArcmin by rememberSaveable { mutableStateOf("5") }
    var starId by rememberSaveable { mutableStateOf<Int?>(null) }
    var showStars by rememberSaveable { mutableStateOf(false) }
    var showDevices by rememberSaveable { mutableStateOf(false) }
    val firstReference = draft.first?.reference?.direction
    val selectableStars = remember(stars, firstReference) { stars.filter { auxiliaryValidationStarAllowed(it, firstReference) } }
    // The first star is never silently reused for the independent validation direction.
    val star = selectableStars.firstOrNull { it.hip == starId }
    val foreground = rememberPageForeground()
    val database = remember(context) { D50Manager(context.applicationContext) }
    var databaseReady by remember { mutableStateOf(false) }
    fun loadSite() { scope.launch {
        try {
            site = PhoneSiteProvider.currentSite(context)
            val observer = requireNotNull(site)
            stars = withContext(Dispatchers.IO) { PhoneBrightStarCatalog.load(context).stars.filter {
                it.hip != null && it.magnitude <= 2.5 && CoordinateTransform.j2000ToTopocentric(
                    EquatorialCoordinates(it.raDeg, it.decDeg), Instant.now(), observer, null).altitudeDeg in 20.0..80.0
            }.sortedBy { it.magnitude } }
            loadError = null
        } catch (cancel: CancellationException) { throw cancel }
        catch (t: Throwable) { loadError = t.message }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadSite() }
    LaunchedEffect(Unit) { loadSite() }
    LaunchedEffect(foreground) {
        if (foreground) databaseReady = withContext(Dispatchers.IO) { database.status().installed }
    }
    LaunchedEffect(connected, referenceReady, exposureUs) {
        if (connected is ConnectionState.Connected && (!referenceReady || exposureUs !in 500_000f..5_000_000f)) options = true
    }
    LaunchedEffect(foreground) { if (!foreground) task.cancel() }
    DisposableEffect(task) { onDispose { task.cancel() } }
    fun back() {
        when {
            draft.busy -> task.cancel()
            draft.fitted != null -> task.clearValidation()
            draft.first != null -> task.reset()
            setupComplete -> setupComplete = false
            else -> onBack()
        }
    }
    BackHandler(enabled = foreground, onBack = ::back)
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.wfa_title)) }, navigationIcon = {
        IconButton(onClick = ::back) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
    }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AuxiliaryCalibrationRecovery(databaseReady,
                auxiliaryMissing = setupComplete && connected !is ConnectionState.Connected,
                mainMissing = mainCamera && mainConnection !is ConnectionState.Connected,
                guiding = guiding || guideCalibration,
                onPlateSolve = onPlateSolve,
                onAuxiliaryConnect = { viewModel.requestGuideConnect(); showDevices = true },
                onMainConnect = { onDeviceControl("camera") }, onGuide = onGuide)
            if (site == null) OutlinedButton(onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text(stringResource(R.string.wfa_site_permission)) }
            if (!setupComplete) {
                Text(stringResource(R.string.wfa_setup), style = MaterialTheme.typography.titleMedium)
                Text((connected as? ConnectionState.Connected)?.info?.name ?: stringResource(R.string.wfa_no_camera))
                OutlinedButton(onClick = { viewModel.requestGuideConnect(); showDevices = true }, enabled = !draft.busy) {
                    Text(stringResource(R.string.select_guide_camera))
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (connected is ConnectionState.Connected && !referenceReady) Text(stringResource(R.string.wfa_choose_raw))
                Row { Checkbox(fixed, { fixed = it }); Text(stringResource(R.string.wfa_fixed)) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(!mainCamera, { mainCamera = false }, label = { Text(stringResource(R.string.wfp_eyepiece)) })
                    FilterChip(mainCamera, { mainCamera = true }, label = { Text(stringResource(R.string.wfp_main_camera)) })
                }
                TextButton(onClick = { options = !options }) { Text(stringResource(R.string.wfp_other_options)) }
                if (options) {
                    Text(stringResource(R.string.wfa_exposure))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(.5f, 1f, 2f, 5f).forEach { seconds ->
                            FilterChip(kotlin.math.abs(exposureUs / 1_000_000f - seconds) < .01f,
                                { viewModel.setGuideExposure(seconds * 1_000_000f) }, enabled = !settingBusy,
                                label = { Text("${seconds}s") })
                        }
                    }
                    Text(stringResource(R.string.wfa_raw_format))
                    Column { formats.forEach { format ->
                        FilterChip(pixelFormat == format, { viewModel.setGuidePixelFormat(format) },
                            enabled = !settingBusy, label = { Text(format.displayName) })
                    } }
                    Row { Checkbox(tracking, { tracking = it }); Text(stringResource(R.string.wfp_tracking)) }
                    if (!mainCamera) OutlinedTextField(centeringArcmin, { centeringArcmin = it },
                        label = { Text(stringResource(R.string.wfp_centering_error)) }, singleLine = true)
                }
                Button(onClick = { setupComplete = true }, enabled = fixed && site != null && databaseReady &&
                    connected is ConnectionState.Connected && (!mainCamera || mainConnection is ConnectionState.Connected) &&
                    referenceReady && !guiding && !guideCalibration && !settingBusy && exposureUs in 500_000f..5_000_000f) {
                    Text(stringResource(R.string.wfa_continue))
                }
            } else if (draft.fitted != null) {
                val fit = requireNotNull(draft.fitted)
                Text(stringResource(R.string.wfa_validated, fit.residualDeg), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.wfa_uncertainty, fit.uncertaintyDeg))
                Button(onClick = { task.save(viewModel.pointingRepository, context.getString(R.string.wfp_configuration_changed)) }, enabled = !draft.saved) {
                    Text(stringResource(if (draft.saved) R.string.wfp_saved else R.string.wfp_save))
                }
            } else {
                Text(stringResource(if (draft.first == null) R.string.wfa_first else R.string.wfa_second),
                    style = MaterialTheme.typography.titleMedium)
                if (!mainCamera) OutlinedButton(onClick = { showStars = true }, enabled = !draft.busy) {
                    Text(star?.name ?: star?.hip?.let { "HIP $it" } ?: stringResource(
                        if (firstReference == null) R.string.wfp_choose_star else R.string.wfa_choose_validation_star))
                }
                if (!draft.busy && (!referenceReady || settingBusy || exposureUs !in 500_000f..5_000_000f)) {
                    Text(stringResource(if (settingBusy) R.string.wfa_wait_settings else if (!referenceReady)
                        R.string.wfa_choose_raw else R.string.wfa_exposure_range))
                    TextButton(onClick = { setupComplete = false }) { Text(stringResource(R.string.wfa_adjust_capture)) }
                }
                if (!mainCamera && !draft.busy) Text(stringResource(R.string.wfa_precenter))
                preview?.let { Image(it.asImageBitmap(), stringResource(R.string.wfa_preview),
                    Modifier.fillMaxWidth().heightIn(max = 180.dp)) }
                if (draft.busy) {
                    if (draft.waitingForCenter) {
                        Text(stringResource(R.string.wfa_center_now))
                        Button(onClick = { task.confirmCentered(ObservationStamp(System.currentTimeMillis(),
                            SystemClock.elapsedRealtime(), 0, 2)) }) { Text(stringResource(R.string.wfa_confirm_center)) }
                    } else { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(stringResource(R.string.wfa_working)) }
                    TextButton(onClick = task::cancel) { Text(stringResource(R.string.wfp_cancel)) }
                } else {
                    Button(onClick = { task.sample(viewModel, requireNotNull(site), tracking, star,
                        (centeringArcmin.toDoubleOrNull() ?: 0.0) / 60, mainCamera) },
                        enabled = site != null && databaseReady && !guiding && !guideCalibration && !settingBusy && referenceReady &&
                            exposureUs in 500_000f..5_000_000f && connected is ConnectionState.Connected &&
                            (!mainCamera || mainConnection is ConnectionState.Connected) && (mainCamera || star != null) &&
                            (mainCamera || centeringArcmin.toDoubleOrNull()?.let { it > 0 && it <= 20 } == true)) {
                        Text(stringResource(R.string.wfa_sample))
                    }
                    if (draft.first != null) TextButton(onClick = { task.reset(); starId = null }) {
                        Text(stringResource(R.string.wfp_resample))
                    }
                }
            }
            (draft.error ?: loadError)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (showStars) AlertDialog(onDismissRequest = { showStars = false }, title = { Text(stringResource(R.string.wfp_choose_star)) },
        text = { Column {
            MountMotionStopBanner(motion, viewModel::stopMountMotion)
            AuxiliaryReferenceStarChoices(selectableStars, validation = firstReference != null) { candidate ->
                starId = candidate.hip; showStars = false
            }
        } }, confirmButton = { TextButton(onClick = { showStars = false }) { Text(stringResource(R.string.back)) } })
    if (showDevices) AlertDialog(onDismissRequest = { showDevices = false; viewModel.hideGuideDevicePicker() },
        title = { Text(stringResource(R.string.select_guide_camera)) }, text = { Column {
            MountMotionStopBanner(motion, viewModel::stopMountMotion)
            val available = viewModel.camerasAvailableToGuide(devices)
            if (available.isEmpty()) Text(stringResource(if (devices.isEmpty()) R.string.no_camera_found
                else R.string.select_different_guide_camera))
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                available.forEach { device -> TextButton(onClick = {
                    viewModel.connectGuideCameraBySn(device.serialNumber); viewModel.hideGuideDevicePicker(); showDevices = false
                }) { Text("${device.name}\n${device.serialNumber}") } }
            }
        } }, confirmButton = { TextButton(onClick = { showDevices = false; viewModel.hideGuideDevicePicker() }) {
            Text(stringResource(R.string.back))
        } })
}

@Composable
internal fun AuxiliaryReferenceStarChoices(stars: List<PhoneCatalogStar>, validation: Boolean,
    onChoose: (PhoneCatalogStar) -> Unit) {
    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
        if (stars.isEmpty()) Text(stringResource(if (validation) R.string.wfa_no_validation_stars else R.string.wfp_no_stars))
        stars.forEach { candidate -> TextButton(onClick = { onChoose(candidate) }) {
            Text(candidate.name ?: "HIP ${candidate.hip}")
        } }
    }
}

/** Conditions are recoverable from both setup and sampling, without erasing the first sample. */
@Composable
internal fun AuxiliaryCalibrationRecovery(databaseReady: Boolean, auxiliaryMissing: Boolean,
    mainMissing: Boolean, guiding: Boolean, onPlateSolve: () -> Unit, onAuxiliaryConnect: () -> Unit,
    onMainConnect: () -> Unit, onGuide: () -> Unit) {
    if (!databaseReady) {
        Text(stringResource(R.string.wfa_database_required))
        OutlinedButton(onClick = onPlateSolve, modifier = Modifier.testTag("auxiliary-install-database")) {
            Text(stringResource(R.string.wfa_install_database))
        }
    }
    if (auxiliaryMissing) {
        Text(stringResource(R.string.wfa_no_camera))
        OutlinedButton(onClick = onAuxiliaryConnect, modifier = Modifier.testTag("auxiliary-reconnect")) {
            Text(stringResource(R.string.select_guide_camera))
        }
    }
    if (mainMissing) {
        Text(stringResource(R.string.wfa_connect_main))
        OutlinedButton(onClick = onMainConnect, modifier = Modifier.testTag("auxiliary-connect-main")) {
            Text(stringResource(R.string.camera))
        }
    }
    if (guiding) {
        Text(stringResource(R.string.wfa_stop_guiding))
        OutlinedButton(onClick = onGuide) { Text(stringResource(R.string.wfa_open_guiding)) }
    }
}
