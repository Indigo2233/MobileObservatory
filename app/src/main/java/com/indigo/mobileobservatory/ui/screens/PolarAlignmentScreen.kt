package com.indigo.mobileobservatory.ui.screens

import com.indigo.mobileobservatory.R

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.graphics.Bitmap
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.indigo.mobileobservatory.astrometry.AstapRunner
import com.indigo.mobileobservatory.astrometry.FitsHeaderReader
import com.indigo.mobileobservatory.astrometry.FitsSolveHintReader
import com.indigo.mobileobservatory.astrometry.FitsSolveHints
import com.indigo.mobileobservatory.astrometry.PlateSolveOptics
import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import com.indigo.mobileobservatory.astrometry.ImagePlateSolver
import com.indigo.mobileobservatory.astrometry.retrySolveCapture
import com.indigo.mobileobservatory.astrometry.canRetryCapture
import com.indigo.mobileobservatory.astrometry.solveWithFallback
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.OpticsEquipment
import com.indigo.mobileobservatory.astro.RefractionParameters
import com.indigo.mobileobservatory.astro.SensorSpec
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.mount.MountSite
import com.indigo.mobileobservatory.permissions.AppSettingsNavigator
import com.indigo.mobileobservatory.polar.ContinuousPolarErrorEstimator
import com.indigo.mobileobservatory.polar.CorrectionFieldInfo
import com.indigo.mobileobservatory.polar.PolarAlignmentCalculator
import com.indigo.mobileobservatory.polar.PolarAlignmentResult
import com.indigo.mobileobservatory.polar.PolarErrorDetermination
import com.indigo.mobileobservatory.polar.PolarSolvePoint
import com.indigo.mobileobservatory.polar.PolarSample
import com.indigo.mobileobservatory.polar.PolarSamplePhase
import com.indigo.mobileobservatory.polar.PolarWorkflowTask
import com.indigo.mobileobservatory.polar.PolarSolveConfiguration
import com.indigo.mobileobservatory.polar.PolarSampleAdvice
import com.indigo.mobileobservatory.polar.PolarExposure
import com.indigo.mobileobservatory.polar.PolarSampleSession
import com.indigo.mobileobservatory.polar.PolarAdjustmentSnapshot
import com.indigo.mobileobservatory.polar.runPolarAlignmentWorkflow
import com.indigo.mobileobservatory.polar.runPolarCorrectionPhase
import com.indigo.mobileobservatory.polar.advice
import com.indigo.mobileobservatory.ui.viewmodel.PolarSamplesViewModel
import com.indigo.mobileobservatory.util.FileLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.pow

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PolarAlignmentScreen(
    mountCoordinates: MountCoordinates? = null,
    mountSite: MountSite? = null,
    mountBusy: Boolean = false,
    mountMoveStatus: String = "",
    mountStopSignal: Long = 0L,
    cameraPixelSizeUm: Float? = null,
    cameraConnected: Boolean = false,
    cameraPreviewBitmap: Bitmap? = null,
    cameraExposureUs: Float? = null,
    cameraExposureMinUs: Float = 1_000f,
    cameraExposureMaxUs: Float = 30_000_000f,
    cameraPreviewSettings: com.indigo.mobileobservatory.camera.CameraPreviewSettings = com.indigo.mobileobservatory.camera.CameraPreviewSettings(),
    solver: ImagePlateSolver? = null,
    simulation: Boolean = false,
    onReadMountSite: () -> Unit = {},
    onSyncPhoneSiteToMount: (Double, Double) -> Unit = { _, _ -> },
    onMoveMountRaBy: (Double, Boolean, Double) -> Unit = { _, _, _ -> },
    onStopMountRaMove: () -> Unit = {},
    onCaptureFits: suspend (Float) -> File? = { null },
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val sampleModel: PolarSamplesViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = if (simulation) "virtual-polar-${System.identityHashCode(solver)}" else "polar-samples")
    val sampleSession = sampleModel.session
    val samples by sampleSession.samples.collectAsStateWithPageLifecycle()
    val stopMotion = rememberUpdatedState(onStopMountRaMove)
    val workflowTask = remember(scope) { PolarWorkflowTask(scope) { stopMotion.value() } }
    val workflowBusy by workflowTask.busy.collectAsStateWithPageLifecycle()
    var importJob by remember { mutableStateOf<Job?>(null) }
    val taskBusy = workflowBusy || importJob != null
    var pendingAutomaticRefresh by remember { mutableStateOf(false) }
    var adjustmentLoopActive by remember { mutableStateOf(false) }
    fun stopTask() { pendingAutomaticRefresh = false; importJob?.cancel(); workflowTask.stop() }
    var inspectFile by remember { mutableStateOf<File?>(null) }
    val pageForeground = rememberPageForeground()
    DisposableEffect(workflowTask) { onDispose { stopTask() } }
    LaunchedEffect(mountStopSignal) { pendingAutomaticRefresh = false; workflowTask.stop() }
    // Document results can arrive before onResume; the bounded file import is independent of device acquisition.
    LaunchedEffect(pageForeground) { if (!pageForeground) { pendingAutomaticRefresh = false; workflowTask.stop() } }
    BackHandler(enabled = taskBusy && pageForeground && inspectFile == null) { stopTask(); onBack() }
    BackHandler(enabled = inspectFile != null && pageForeground) { inspectFile = null }
    val phoneLocationFailed = stringResource(R.string.phone_location_failed)
    val plateSolveFailed = stringResource(R.string.plate_solve_failed)
    val tppaFailed = stringResource(R.string.tppa_failed)
    val noCameraFrame = stringResource(R.string.no_camera_frame)
    val threeSolvesRequired = stringResource(R.string.three_solves_required)
    val runner = remember { AstapRunner(context.applicationContext) }
    val solveEngine = solver ?: runner
    val prefs = remember(simulation) { context.applicationContext.getSharedPreferences(if (simulation) "virtual_observation" else "mobile_observatory", android.content.Context.MODE_PRIVATE) }

    // The workflow runs inside a coroutine, so it needs the freshest mount readings, not the ones
    // captured when the coroutine was launched.
    val latestMountCoordinates = rememberUpdatedState(mountCoordinates)
    val latestMountBusy = rememberUpdatedState(mountBusy)

    // Restore input drafts only; an interrupted solve/movement must not resume as a valid result.
    var latitudeText by rememberSaveable { mutableStateOf(prefs.getFloat("polar_latitude_deg", if (simulation) 30f else 0f).toString()) }
    var longitudeText by rememberSaveable { mutableStateOf(prefs.getFloat("polar_longitude_deg", if (simulation) 120f else 0f).toString()) }
    var elevationText by rememberSaveable { mutableStateOf(prefs.getFloat("polar_elevation_m", 0f).toString()) }
    var temperatureText by rememberSaveable { mutableStateOf(prefs.getFloat("polar_temperature_c", 15f).toString()) }
    var pressureText by rememberSaveable { mutableStateOf(prefs.getFloat("polar_pressure_hpa", 0f).takeIf { it > 0f }?.toString() ?: "") }
    var focalLengthText by rememberSaveable { mutableStateOf(prefs.getFloat("plate_focal_length_mm", if (simulation) 200f else 0f).takeIf { it > 0f }?.toString() ?: "") }
    var pixelSizeText by rememberSaveable {
        mutableStateOf(
            prefs.getFloat("plate_pixel_size_um", 0f).takeIf { it > 0f }?.let { formatPixelSizeUm(it.toDouble()) }
                ?: cameraPixelSizeUm?.takeIf { it > 0f }?.let { formatPixelSizeUm(it.toDouble()) }
                ?: ""
        )
    }
    var selectedSensorId by rememberSaveable { mutableStateOf(prefs.getString("plate_sensor_id", "") ?: "") }
    var computedFovDeg by remember { mutableStateOf<Double?>(null) }
    var manualMode by rememberSaveable { mutableStateOf(prefs.getBoolean("polar_manual_mode", false)) }
    var automaticRefresh by rememberSaveable { mutableStateOf(prefs.getBoolean("polar_automatic_refresh", true)) }
    var startFromCurrent by rememberSaveable { mutableStateOf(prefs.getBoolean("polar_start_current", true)) }
    var eastDirection by rememberSaveable { mutableStateOf(prefs.getBoolean("polar_east_direction", true)) }
    var targetDistanceText by rememberSaveable { mutableStateOf(prefs.getInt("polar_target_distance_deg", 10).toString()) }
    var moveRateText by rememberSaveable { mutableStateOf(prefs.getFloat("polar_move_rate_deg_sec", 3f).toString()) }
    var searchRadiusText by rememberSaveable { mutableStateOf(prefs.getFloat("polar_search_radius_deg", 30f).toString()) }
    var alignmentToleranceText by rememberSaveable { mutableStateOf(prefs.getFloat("polar_alignment_tolerance_arcmin", 0f).toString()) }
    var refractionAdjustment by rememberSaveable { mutableStateOf(prefs.getBoolean("polar_refraction_adjustment", true)) }
    var blindFallback by rememberSaveable { mutableStateOf(prefs.getBoolean("polar_blind_fallback", true)) }
    var monochromePreview by rememberSaveable { mutableStateOf(prefs.getBoolean("polar_monochrome_preview", true)) }
    var showSamples by rememberSaveable { mutableStateOf(false) }
    val alignmentPreviewSettings = cameraPreviewSettings.copy(monochrome = monochromePreview)
    var useMountHint by rememberSaveable { mutableStateOf(prefs.getBoolean("polar_use_mount_hint", true)) }
    var selectedSlot by rememberSaveable { mutableIntStateOf(0) }
    var imagePickerSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    var solvingSlot by remember { mutableStateOf<Int?>(null) }
    var runningAuto by remember { mutableStateOf(false) }
    var workflowStatus by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var exposureSeconds by rememberSaveable {
        mutableStateOf(prefs.getString("polar_exposure_seconds", if (simulation) "0.5" else null)
            ?: PolarExposure.secondsText(cameraExposureUs?.takeIf { it > 0f } ?: 5_000_000f))
    }
    val requestedExposureUs = PolarExposure.parseUs(exposureSeconds, cameraExposureMinUs, cameraExposureMaxUs)
    var locationPermissionDeniedForever by remember { mutableStateOf(false) }
    var determination by remember { mutableStateOf(sampleModel.adjustment?.determination) }
    var currentResult by remember { mutableStateOf(sampleModel.adjustment?.current) }
    var correctionField by remember { mutableStateOf<CorrectionFieldInfo?>(null) }
    var awaitingManualAdvance by remember { mutableStateOf(false) }
    var manualAdvanceRequested by remember { mutableStateOf(false) }
    var phoneSite by remember { mutableStateOf<MountSite?>(null) }
    val files = samples.take(3).map { it.file }
    val solves = samples.take(3).map { it.result }
    val solvedAt = samples.take(3).map { it.capturedAt }
    var showParameters by rememberSaveable { mutableStateOf(!prefs.contains("polar_latitude_deg") ||
        prefs.getFloat("plate_focal_length_mm", 0f) <= 0f || (pixelSizeText.toDoubleOrNull() ?: 0.0) <= 0.0) }

    val connectedSensorName = stringResource(R.string.connected_camera_sensor)
    val sensors = remember(cameraPixelSizeUm, connectedSensorName) {
        buildList {
            cameraPixelSizeUm?.toDouble()?.takeIf { it > 0.0 }?.let { px ->
                add(SensorSpec(OpticsEquipment.CONNECTED_SENSOR_ID, connectedSensorName, px, 0, 0))
            }
            addAll(OpticsEquipment.defaultSensors)
        }
    }

    fun reportFailure(message: String, exposeSettings: Boolean = false) {
        error = message
        if (exposeSettings) showParameters = true
        FileLogger.w("PolarAlign", message)
    }

    fun persistSolveOptics() {
        prefs.edit().apply {
            focalLengthText.toFloatOrNull()?.takeIf { it > 0f }?.let { putFloat("plate_focal_length_mm", it) }
            pixelSizeText.toFloatOrNull()?.takeIf { it > 0f }?.let { putFloat("plate_pixel_size_um", it) }
            putString("plate_sensor_id", selectedSensorId)
        }.apply()
    }

    fun solveConfiguration(): PolarSolveConfiguration {
        val sensor = sensors.firstOrNull { it.id == selectedSensorId }
        val typedPixel = pixelSizeText.toDoubleOrNull()?.takeIf { it > 0.0 }
        val pixel = sensor?.let { pixelSizeForSensor(it, typedPixel) } ?: typedPixel
        val catalogHeight = sensor?.heightPx?.takeIf {
            sensor.id != OpticsEquipment.CUSTOM_SENSOR_ID && it > 0
        } ?: 0
        val focal = focalLengthText.toDoubleOrNull()?.takeIf { it > 0.0 }
        return PolarSolveConfiguration(focal, pixel, catalogHeight, useMountHint,
            searchRadiusText.toDoubleOrNull()?.coerceIn(.1, 180.0) ?: 30.0, blindFallback)
    }

    fun currentAstapFov(hints: FitsSolveHints = FitsSolveHints()): Double? = solveConfiguration().fieldOfView(hints)

    suspend fun executeSample(index: Int, configuration: PolarSolveConfiguration, capture: suspend () -> File?): PlateSolveResult =
        sampleSession.execute(index, capture,
            fieldOfView = { file ->
                val hints = withContext(Dispatchers.IO) { FitsSolveHintReader.read(requireNotNull(file)) }
                configuration.fieldOfView(hints)
            },
            solve = { file, fov ->
                val hint = latestMountCoordinates.value.takeIf { configuration.useMountHint }
                solveEngine.solveWithFallback(file, fov, hint, configuration.searchRadiusDeg, configuration.blindFallback)
            },
            noFrame = noCameraFrame, missingOptics = context.getString(R.string.plate_solve_need_optics),
            exposureTime = ::exposureTimeOf)

    suspend fun captureSample(index: Int, configuration: PolarSolveConfiguration, exposure: Float): PlateSolveResult =
        retrySolveCapture(onRetry = { attempt, result ->
            workflowStatus = context.getString(R.string.solve_retry_capture, attempt, result.message)
        }) { executeSample(index, configuration) { onCaptureFits(exposure) } }

    fun publishAdjustment(solved: PolarErrorDetermination, result: PolarAlignmentResult, sample: PolarSample) {
        determination = solved
        currentResult = result
        sampleModel.adjustment = PolarAdjustmentSnapshot.create(solved, result, sample)
        val plate = sample.result
        if (plate?.raDeg != null && plate.decDeg != null && sample.capturedAt != null)
            correctionField = solved.correctionFieldInfo(PolarSolvePoint(plate.raDeg, plate.decDeg, sample.capturedAt))
    }

    suspend fun correctAlignment(solved: PolarErrorDetermination, configuration: PolarSolveConfiguration, exposure: Float) {
        adjustmentLoopActive = true
        var retryAttempt = 1
        try {
        runPolarCorrectionPhase(solved, alignmentToleranceText.toDoubleOrNull() ?: 0.0,
            capture = {
                workflowStatus = context.getString(R.string.polar_correction_capturing)
                val result = executeSample(3, configuration) { onCaptureFits(exposure) }
                if (result.success && result.raDeg != null && result.decDeg != null) {
                    retryAttempt = 1
                    val at = requireNotNull(sampleSession.samples.value[3].capturedAt)
                    PolarSolvePoint(result.raDeg, result.decDeg, at)
                } else {
                    if (!result.canRetryCapture()) error(result.message)
                    workflowStatus = context.getString(R.string.solve_retry_capture, ++retryAttempt, result.message)
                    delay(1500)
                    null
                }
            },
            onEstimate = { result, _ -> publishAdjustment(solved, result, sampleSession.samples.value[3]) },
            onUnstable = { workflowStatus = context.getString(R.string.polar_estimate_unstable) },
            onWithinTolerance = { workflowStatus = context.getString(R.string.polar_tolerance_reached, it) },
            seed = sampleModel.adjustment?.current ?: solved.initial,
            shouldCapture = { automaticRefresh })
        if (!automaticRefresh) workflowStatus = context.getString(R.string.polar_manual_refresh_ready)
        } finally { adjustmentLoopActive = false }
    }

    fun startAutomaticRefresh() {
        val existing = sampleModel.adjustment ?: return
        val exposure = requestedExposureUs ?: return
        if (!cameraConnected || mountBusy || taskBusy) return
        if (!solveEngine.ready) { reportFailure(context.getString(R.string.database_required)); return }
        val configuration = solveConfiguration()
        workflowTask.start(stopMotionOnFinish = false) {
            error = null
            try { correctAlignment(existing.determination, configuration, exposure) }
            catch (cancel: CancellationException) { workflowStatus = context.getString(R.string.stopped); throw cancel }
            catch (failure: Exception) { error = failure.message ?: tppaFailed }
        }
    }

    LaunchedEffect(pendingAutomaticRefresh, taskBusy, mountBusy, cameraConnected, requestedExposureUs) {
        if (pendingAutomaticRefresh && !taskBusy && !mountBusy && cameraConnected && requestedExposureUs != null) {
            pendingAutomaticRefresh = false
            if (automaticRefresh) startAutomaticRefresh()
        }
    }

    fun runSingleSample(index: Int, recapture: Boolean, blind: Boolean = false) {
        val exposure = requestedExposureUs
        if (recapture && exposure == null) {
            reportFailure(context.getString(R.string.polar_exposure_out_of_range, cameraExposureMinUs / 1_000_000f, cameraExposureMaxUs / 1_000_000f))
            return
        }
        if (recapture && index < 3 && index != sampleModel.positionedPoint) {
            reportFailure(context.getString(R.string.polar_recapture_current_only))
            return
        }
        if (!solveEngine.ready) { reportFailure(context.getString(R.string.database_required)); return }
        val file = files.getOrNull(index) ?: samples.getOrNull(index)?.file
        if (!recapture && file == null) return
        if (recapture && index != sampleModel.positionedPoint || index < sampleModel.positionedPoint) sampleModel.canResume = false
        if (blind) useMountHint = false
        persistSolveOptics()
        val configuration = solveConfiguration()
        showParameters = false
        workflowTask.start(stopMotionOnFinish = false) {
            solvingSlot = index
            error = null
            if (index < 3) { determination = null; currentResult = null; sampleModel.adjustment = null }
            try {
                val solved = executeSample(index, configuration) { if (recapture) onCaptureFits(requireNotNull(exposure)) else file }
                workflowStatus = context.getString(if (solved.success) R.string.polar_point_solved else R.string.tppa_failed, index + 1)
                if (!solved.success) error = solved.message
                val previous = sampleModel.adjustment
                if (index == 3 && solved.success && previous != null) {
                    val sample = sampleSession.samples.value[3]
                    val estimate = ContinuousPolarErrorEstimator.estimate(previous.determination,
                        PolarSolvePoint(requireNotNull(solved.raDeg), requireNotNull(solved.decDeg), requireNotNull(sample.capturedAt)),
                        previous.current.azimuthErrorDeg, previous.current.altitudeErrorDeg)
                    if (estimate.success) publishAdjustment(previous.determination,
                        previous.determination.resultForResidual(estimate.azimuthErrorDeg, estimate.altitudeErrorDeg), sample)
                    else workflowStatus = context.getString(R.string.polar_estimate_unstable)
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: plateSolveFailed }
            finally { solvingSlot = null }
        }
    }

    LaunchedEffect(samples) {
        samples.indexOfFirst { it.phase == PolarSamplePhase.CAPTURING || it.phase == PolarSamplePhase.SOLVING }
            .takeIf { it >= 0 }?.let { index ->
                workflowStatus = when {
                    index == PolarSampleSession.PREVIEW_INDEX -> context.getString(R.string.polar_test_capturing)
                    index == 3 -> context.getString(if (samples[index].phase == PolarSamplePhase.CAPTURING)
                        R.string.solve_capturing_task else R.string.solve_solving_task)
                    samples[index].phase == PolarSamplePhase.CAPTURING -> context.getString(R.string.capturing_point, index + 1)
                    else -> context.getString(R.string.solving_point, index + 1)
                }
            }
    }

    LaunchedEffect(sensors, selectedSensorId, cameraPixelSizeUm) {
        if (selectedSensorId.isNotBlank() && sensors.any { it.id == selectedSensorId }) return@LaunchedEffect
        selectedSensorId = when {
            cameraPixelSizeUm != null && cameraPixelSizeUm > 0f -> OpticsEquipment.CONNECTED_SENSOR_ID
            else -> OpticsEquipment.matchCatalogSensor(pixelSizeUm = pixelSizeText.toDoubleOrNull())?.id
                ?: OpticsEquipment.CUSTOM_SENSOR_ID
        }
    }

    LaunchedEffect(focalLengthText, pixelSizeText, selectedSensorId, files.toList()) {
        val file = files.getOrNull(selectedSlot)
        val hints = if (file != null) {
            withContext(Dispatchers.IO) { FitsSolveHintReader.read(file) }
        } else {
            FitsSolveHints()
        }
        val sensor = sensors.firstOrNull { it.id == selectedSensorId }
        if (sensor != null && sensor.id != OpticsEquipment.CUSTOM_SENSOR_ID) {
            val formatted = formatPixelSizeUm(sensor.pixelSizeUm)
            if (pixelSizeText != formatted) pixelSizeText = formatted
        }
        computedFovDeg = currentAstapFov(hints)
    }

    val manualAdvanceState = rememberUpdatedState(manualAdvanceRequested)

    fun currentSite(): ObserverSite? {
        val lat = latitudeText.toDoubleOrNull() ?: return null
        val lon = longitudeText.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return ObserverSite(lat, lon, elevationText.toDoubleOrNull() ?: 0.0)
    }

    fun currentRefraction(): RefractionParameters {
        if (simulation) return RefractionParameters(pressureHPa = 0.0)
        val elevation = elevationText.toDoubleOrNull() ?: 0.0
        return RefractionParameters(
            pressureHPa = pressureText.toDoubleOrNull() ?: standardPressureHPa(elevation),
            temperatureC = temperatureText.toDoubleOrNull() ?: 15.0,
            relativeHumidity = 0.0
        )
    }

    fun importSample(slot: Int, uri: Uri, sourceLastModifiedMs: Long?) {
        if (taskBusy || mountBusy) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            error = null
            workflowStatus = context.getString(R.string.external_media_importing)
            try {
                val selected = runner.copyUriToCache(uri, sourceLastModifiedMs)
                sampleSession.import(slot, selected)
                if (slot < 3) {
                    determination = null
                    currentResult = null
                    sampleModel.adjustment = null
                }
                correctionField = null
                sampleModel.canResume = false
                workflowStatus = context.getString(R.string.polar_sample_imported)
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) { error = failure.message ?: plateSolveFailed }
            finally { importJob = null }
        }
        importJob = job
        job.start()
    }

    imagePickerSlot?.let { slot ->
        AppImagePicker(
            recordingsDir = context.getExternalFilesDir("recordings"),
            capturesDir = context.getExternalFilesDir("captures"),
            excludeFile = null,
            selectionLabel = if (slot == 3) stringResource(R.string.polar_correction_sample)
                else stringResource(R.string.point_number, slot + 1),
            onDismiss = { imagePickerSlot = null },
            onSelect = { file -> imagePickerSlot = null; importSample(slot, Uri.fromFile(file), file.lastModified()) },
            onDocumentSelect = { uri -> imagePickerSlot = null; importSample(slot, uri, null) }
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            locationPermissionDeniedForever = false
            scope.launch {
                error = null
                val site = runCatching { getPhoneSite(context.applicationContext) }.getOrElse {
                    error = it.message ?: phoneLocationFailed
                    null
                }
                if (site != null) {
                    phoneSite = site
                    latitudeText = "%.6f".format(Locale.US, site.latitudeDeg)
                    longitudeText = "%.6f".format(Locale.US, site.longitudeDeg)
                    prefs.edit()
                        .putFloat("polar_latitude_deg", site.latitudeDeg.toFloat())
                        .putFloat("polar_longitude_deg", site.longitudeDeg.toFloat())
                        .apply()
                }
            }
        } else {
            locationPermissionDeniedForever = activity != null &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) &&
                !ActivityCompat.shouldShowRequestPermissionRationale(
                    activity,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            error = context.getString(
                if (locationPermissionDeniedForever) {
                    R.string.location_permission_denied_forever
                } else {
                    R.string.location_permission_required
                }
            )
        }
    }

    val latestSample = samples.maxByOrNull { it.displaySequence }?.takeIf { it.displaySequence > 0 }
    val adjustment = sampleModel.adjustment
    val previewEntry = (samples.withIndex().filter { it.value.file != null } +
        listOfNotNull(adjustment?.sample?.let { IndexedValue(if (it == samples[2]) 2 else 3, it) }))
        .maxByOrNull { it.value.displaySequence }
    var previewFile by remember { mutableStateOf<File?>(null) }
    var previewIndex by remember { mutableIntStateOf(-1) }
    var previewSequence by remember { mutableLongStateOf(0L) }
    LaunchedEffect(previewEntry, taskBusy, samples.all { it.phase == PolarSamplePhase.EMPTY }) {
        if (previewEntry != null && previewEntry.value.displaySequence >= previewSequence) {
            previewFile = previewEntry.value.file; previewIndex = previewEntry.index
            previewSequence = previewEntry.value.displaySequence
        } else if (!taskBusy && samples.all { it.phase == PolarSamplePhase.EMPTY }) {
            previewFile = null; previewIndex = -1; previewSequence = 0L
        }
    }
    val previewLabel = when (previewIndex) {
        PolarSampleSession.PREVIEW_INDEX -> stringResource(R.string.polar_test_image)
        3 -> stringResource(R.string.polar_correction_sample)
        in 0..2 -> stringResource(R.string.point_number, previewIndex + 1)
        else -> stringResource(R.string.polar_camera_preview)
    }

    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { stopTask(); onBack() }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Text(stringResource(R.string.three_point_polar_alignment), style = MaterialTheme.typography.titleMedium)
            }

            PolarWorkflowLayout(
                modifier = Modifier.weight(1f),
                failureVisible = latestSample?.phase == PolarSamplePhase.FAILED || error != null,
                resultVisible = adjustment != null,
                preview = { modifier ->
                    val label = if (previewFile != null && latestSample?.file == null)
                        stringResource(R.string.polar_previous_image, previewLabel) else previewLabel
                    PolarWorkflowPreview(file = previewFile, label = label, livePreview = cameraPreviewBitmap,
                        previewSettings = alignmentPreviewSettings,
                        sample = latestSample, status = workflowStatus, error = error, busy = taskBusy || mountBusy,
                        onStop = {
                            if (taskBusy) stopTask() else onStopMountRaMove()
                            workflowStatus = context.getString(R.string.stopped)
                        }, inspectFile = inspectFile, onInspect = { inspectFile = it }, adjustment = adjustment, modifier = modifier)
                },
                controls = { modifier ->
                    Column(
                        modifier = modifier.verticalScroll(rememberScrollState()).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.solve_monochrome_preview), Modifier.weight(1f))
                            Switch(checked = monochromePreview, onCheckedChange = {
                                monochromePreview = it
                                prefs.edit().putBoolean("polar_monochrome_preview", it).apply()
                            })
                        }
                        PolarExposureControls(seconds = exposureSeconds, onSecondsChange = {
                            exposureSeconds = it
                            prefs.edit().putString("polar_exposure_seconds", it).apply()
                        }, minimumUs = cameraExposureMinUs, maximumUs = cameraExposureMaxUs, actualUs = cameraExposureUs,
                            connected = cameraConnected, busy = taskBusy || mountBusy,
                            onCapture = {
                                val exposure = requestedExposureUs
                                if (exposure != null) {
                                    showParameters = false
                                    workflowTask.start(stopMotionOnFinish = false) {
                                        error = null
                                        workflowStatus = context.getString(R.string.polar_test_capturing)
                                        try {
                                            sampleSession.capturePreview(capture = { onCaptureFits(exposure) }, exposureTime = ::exposureTimeOf)
                                            workflowStatus = context.getString(R.string.polar_test_complete)
                                        } catch (cancel: CancellationException) {
                                            workflowStatus = context.getString(R.string.stopped); throw cancel
                                        } catch (failure: Exception) {
                                            error = failure.message ?: noCameraFrame
                                            workflowStatus = context.getString(R.string.tppa_failed)
                                        }
                                    }
                                }
                            })
                        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(stringResource(R.string.workflow), style = MaterialTheme.typography.titleSmall)
                                Text(stringResource(R.string.polar_progress, samples.take(3).count { it.phase == PolarSamplePhase.COMPLETE }))
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(selected = automaticRefresh, onClick = {
                                        automaticRefresh = true
                                        prefs.edit().putBoolean("polar_automatic_refresh", true).apply()
                                        if (taskBusy && !adjustmentLoopActive && sampleModel.adjustment != null)
                                            pendingAutomaticRefresh = true
                                        else startAutomaticRefresh()
                                    }, label = { Text(stringResource(R.string.polar_refresh_auto)) },
                                        modifier = Modifier.testTag("polar-refresh-auto"))
                                    FilterChip(selected = !automaticRefresh, onClick = {
                                        automaticRefresh = false
                                        pendingAutomaticRefresh = false
                                        prefs.edit().putBoolean("polar_automatic_refresh", false).apply()
                                        if (adjustment != null && !taskBusy) workflowStatus = context.getString(R.string.polar_manual_refresh_ready)
                                    }, label = { Text(stringResource(R.string.polar_refresh_manual)) },
                                        modifier = Modifier.testTag("polar-refresh-manual"))
                                }
                                Text(stringResource(if (automaticRefresh) R.string.polar_refresh_auto_hint else R.string.polar_refresh_manual_hint),
                                    style = MaterialTheme.typography.bodySmall)
                                error?.let { msg ->
                                    if (locationPermissionDeniedForever &&
                                        msg == stringResource(R.string.location_permission_denied_forever)
                                    ) {
                                        OutlinedButton(
                                            onClick = { AppSettingsNavigator.openApplicationDetails(context) }
                                        ) {
                                            Text(stringResource(R.string.open_app_settings))
                                        }
                                    }
                                }
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        onClick = {
                                            val exposure = requestedExposureUs ?: return@Button
                                            val existing = sampleModel.adjustment
                                            if (existing != null) {
                                                if (automaticRefresh) startAutomaticRefresh() else runSingleSample(3, recapture = true)
                                                return@Button
                                            }
                                            val site = currentSite()
                                            if (site == null) {
                                                reportFailure(context.getString(R.string.invalid_coordinates), exposeSettings = true)
                                                return@Button
                                            }
                                            if (!solveEngine.ready) {
                                                reportFailure(context.getString(R.string.database_required))
                                                return@Button
                                            }
                                            if (!manualMode && mountCoordinates == null) {
                                                reportFailure(context.getString(R.string.tppa_requires_mount), exposeSettings = true)
                                                return@Button
                                            }
                                            persistSolveOptics()
                                            val configuration = solveConfiguration()
                                            val moveEast = eastDirection
                                            val distance = targetDistanceText.toDoubleOrNull()?.coerceIn(1.0, 90.0) ?: 10.0
                                            val rate = moveRateText.toDoubleOrNull()?.coerceIn(0.1, 20.0) ?: 3.0
                                            val refraction = currentRefraction()
                                            val useManual = manualMode
                                            val useRefractionAdjustment = refractionAdjustment
                                            showParameters = false

                                            prefs.edit()
                                                .putFloat("polar_latitude_deg", site.latitudeDeg.toFloat())
                                                .putFloat("polar_longitude_deg", site.longitudeDeg.toFloat())
                                                .apply()

                                            workflowTask.start {
                                                FileLogger.i("PolarAlign", "Workflow start site=$site optics=$configuration manual=$useManual distanceDeg=$distance rateDegPerSec=$rate east=$moveEast")
                                                runningAuto = true
                                                error = null
                                                determination = null
                                                currentResult = null
                                                sampleModel.adjustment = null
                                                correctionField = null
                                                try {
                                                    val plan = com.indigo.mobileobservatory.polar.polarResumePlan(samples, sampleModel.positionedPoint, sampleModel.canResume)
                                                    val startIndex = plan.startIndex
                                                    if (plan.reset) {
                                                        sampleSession.clear()
                                                        sampleModel.positionedPoint = 0
                                                        sampleModel.canResume = true
                                                    }
                                                    val points = samples.take(startIndex).map { sample ->
                                                        val result = requireNotNull(sample.result)
                                                        PolarSolvePoint(requireNotNull(result.raDeg), requireNotNull(result.decDeg), requireNotNull(sample.capturedAt))
                                                    }.toMutableList()
                                                    val mountDeclinations = mutableListOf<Double>()
                                                    var previousMountRaDeg = latestMountCoordinates.value?.raDeg

                                                    runPolarAlignmentWorkflow(initialPoints = points, moveToPoint = { index ->
                                                        if (index > sampleModel.positionedPoint) {
                                                            sampleModel.canResume = false
                                                            workflowStatus = context.getString(R.string.moving_ra_point, index + 1)
                                                            if (useManual) {
                                                                awaitingManualAdvance = true
                                                                manualAdvanceRequested = false
                                                                awaitManualMove(
                                                                    latestMountCoordinates,
                                                                    manualAdvanceState,
                                                                    previousMountRaDeg,
                                                                    distance
                                                                ) { travelled ->
                                                                    workflowStatus = if (travelled == null) {
                                                                        context.getString(R.string.polar_move_axis_blind, distance)
                                                                    } else {
                                                                        context.getString(R.string.polar_move_axis_prompt, travelled, distance)
                                                                    }
                                                                }
                                                                awaitingManualAdvance = false
                                                                manualAdvanceRequested = false
                                                            } else {
                                                                onMoveMountRaBy(distance, moveEast, rate)
                                                                when (com.indigo.mobileobservatory.polar.awaitPolarMove({ latestMountBusy.value }, distance, rate)) {
                                                                    com.indigo.mobileobservatory.polar.PolarMoveOutcome.NOT_STARTED -> error(context.getString(R.string.polar_move_not_started))
                                                                    com.indigo.mobileobservatory.polar.PolarMoveOutcome.TIMED_OUT -> error(context.getString(R.string.polar_move_timeout))
                                                                    else -> Unit
                                                                }
                                                                val travelled = raSeparationDeg(
                                                                    previousMountRaDeg,
                                                                    latestMountCoordinates.value?.raDeg
                                                                )
                                                                if (travelled != null && travelled - distance < -1.0) {
                                                                    error(context.getString(R.string.polar_short_move, travelled, distance))
                                                                }
                                                            }
                                                            workflowStatus = context.getString(R.string.polar_settling)
                                                            delay(SETTLE_MILLIS)
                                                            sampleModel.positionedPoint = index
                                                            sampleModel.canResume = true
                                                        }

                                                    }, capturePoint = { index ->
                                                        previousMountRaDeg = latestMountCoordinates.value?.raDeg
                                                        workflowStatus = context.getString(R.string.capturing_point, index + 1)
                                                        solvingSlot = index
                                                        val solved = captureSample(index, configuration, exposure)
                                                        val capturedAt = requireNotNull(sampleSession.samples.value[index].capturedAt)
                                                        solvingSlot = null
                                                        if (!solved.success) error(solved.message)
                                                        val point = PolarSolvePoint(
                                                            raDeg = solved.raDeg ?: error(solved.message),
                                                            decDeg = solved.decDeg ?: error(solved.message),
                                                            solvedAt = capturedAt
                                                        )
                                                        latestMountCoordinates.value?.let { mountDeclinations += it.decDeg }
                                                        point
                                                    }, determine = { allPoints ->
                                                        workflowStatus = context.getString(R.string.polar_calculating)
                                                        PolarAlignmentCalculator.determine(
                                                            points = allPoints,
                                                            site = site,
                                                            refraction = refraction,
                                                            correctForRefraction = useRefractionAdjustment,
                                                            declinationSpreadArcsec =
                                                                PolarAlignmentCalculator.declinationSpreadArcsec(mountDeclinations)
                                                        )
                                                    }, onDetermined = { solved ->
                                                        publishAdjustment(solved, solved.initial, sampleSession.samples.value[2])
                                                        workflowStatus = context.getString(R.string.tppa_complete)
                                                    }, adjust = { solved ->
                                                        correctAlignment(solved, configuration, exposure)
                                                    })
                                                } catch (cancel: CancellationException) {
                                                    FileLogger.i("PolarAlign", "Workflow cancelled stage=$workflowStatus")
                                                    workflowStatus = context.getString(R.string.stopped)
                                                    throw cancel
                                                } catch (e: Exception) {
                                                    error = context.getString(R.string.polar_failure_at_stage, workflowStatus, e.message ?: tppaFailed)
                                                    workflowStatus = tppaFailed
                                                    FileLogger.e("PolarAlign", error.orEmpty(), e)
                                                } finally {
                                                    awaitingManualAdvance = false
                                                    runningAuto = false
                                                    solvingSlot = null
                                                }
                                            }
                                        },
                                        modifier = Modifier.testTag("polar-run"),
                                        enabled = cameraConnected && requestedExposureUs != null && !taskBusy && !mountBusy && solvingSlot == null
                                    ) {
                                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text(stringResource(when {
                                            taskBusy -> R.string.running
                                            adjustment != null && !automaticRefresh -> R.string.polar_refresh_once
                                            adjustment != null -> R.string.polar_start_adjustment
                                            samples.take(3).all { it.phase == PolarSamplePhase.COMPLETE } -> R.string.polar_restart
                                            !sampleModel.canResume && samples.take(3).any { it.phase != PolarSamplePhase.EMPTY } -> R.string.polar_restart
                                            samples.take(3).any { it.phase != PolarSamplePhase.EMPTY } -> R.string.polar_continue
                                            else -> R.string.run_tppa
                                        }))
                                    }
                                    if (awaitingManualAdvance) {
                                        Button(onClick = { manualAdvanceRequested = true }, modifier = Modifier.testTag("polar-next-point")) {
                                            Text(stringResource(R.string.polar_next_point))
                                        }
                                    }
                                    OutlinedButton(
                                        onClick = {
                                            FileLogger.i("PolarAlign", "User stop requested")
                                            if (taskBusy) stopTask() else onStopMountRaMove()
                                            workflowStatus = context.getString(R.string.stopped)
                                        },
                                        enabled = taskBusy || mountBusy,
                                        modifier = Modifier.testTag("polar-stop")
                                    ) { Text(stringResource(R.string.stop)) }
                                }
                                if (!taskBusy && samples.any { it.file != null || it.failure != null }) {
                                    TextButton(onClick = {
                                        sampleSession.clear(); sampleModel.positionedPoint = 0; sampleModel.canResume = true
                                        determination = null; currentResult = null; error = null; workflowStatus = ""
                                        sampleModel.adjustment = null
                                    }) { Text(stringResource(R.string.polar_restart)) }
                                }
                                if (workflowStatus.isNotBlank()) {
                                    Text(workflowStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                }
                                Text(
                                    if (adjustment != null) {
                                        stringResource(R.string.polar_adjustment_knobs_hint)
                                    } else if (manualMode) {
                                        stringResource(R.string.polar_manual_mode_hint)
                                    } else {
                                        stringResource(R.string.polar_automatic_mode_hint)
                                    },
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }

                        if (showParameters) determination?.let { solved ->
                            PolarAlignmentResultCard(
                                determination = solved,
                                current = currentResult ?: solved.initial,
                                correctionField = correctionField
                            )
                        }

                        Text(stringResource(R.string.polar_setup_summary, latitudeText, longitudeText,
                            focalLengthText.ifBlank { "—" }, pixelSizeText.ifBlank { "—" }), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { showParameters = !showParameters }, modifier = Modifier.fillMaxWidth(), enabled = !taskBusy) {
                            Text(stringResource(R.string.polar_parameters), modifier = Modifier.weight(1f))
                            Icon(if (showParameters) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                        if (showParameters) {
                            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.site), style = MaterialTheme.typography.titleSmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedTextField(
                                            value = latitudeText,
                                            onValueChange = { latitudeText = it.filterCoordinateText() },
                                            label = { Text(stringResource(R.string.latitude_deg)) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                        OutlinedTextField(
                                            value = longitudeText,
                                            onValueChange = { longitudeText = it.filterCoordinateText() },
                                            label = { Text(stringResource(R.string.longitude_deg)) },
                                            singleLine = true,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OptionField(stringResource(R.string.elevation_m), elevationText, Modifier.weight(1f)) {
                                            elevationText = it.filterCoordinateText()
                                            it.toFloatOrNull()?.let { value ->
                                                prefs.edit().putFloat("polar_elevation_m", value).apply()
                                            }
                                        }
                                    }
                                    SolveOpticsFields(
                                        focalLengthText = focalLengthText,
                                        onFocalLengthChange = { focalLengthText = it },
                                        selectedSensorId = selectedSensorId,
                                        onSensorSelected = { sensor ->
                                            selectedSensorId = sensor.id
                                            pixelSizeText = formatPixelSizeUm(sensor.pixelSizeUm)
                                        },
                                        pixelSizeText = pixelSizeText,
                                        onPixelSizeChange = { pixelSizeText = it },
                                        sensors = sensors,
                                        computedFovHeightDeg = computedFovDeg,
                                        enabled = !runningAuto && solvingSlot == null
                                    )
                                    if (mountCoordinates != null) {
                                        Text(
                                            "Mount hint ${mountCoordinates.formatRa()}  ${mountCoordinates.formatDec()}",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                    phoneSite?.let {
                                        Text(stringResource(R.string.phone_gps_value, it.format()), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                    mountSite?.let {
                                        Text(stringResource(R.string.mount_site_value, it.format()), fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                    if (phoneSite != null && mountSite != null) {
                                        val diffMeters = siteDifferenceMeters(phoneSite!!, mountSite)
                                        if (diffMeters > 30.0) {
                                            Text(
                                                stringResource(R.string.site_mismatch_meters, diffMeters),
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = {
                                                if (hasLocationPermission(context)) {
                                                    scope.launch {
                                                        error = null
                                                        val site = runCatching { getPhoneSite(context.applicationContext) }.getOrElse {
                                                            error = it.message ?: phoneLocationFailed
                                                            null
                                                        }
                                                        if (site != null) {
                                                            phoneSite = site
                                                            latitudeText = "%.6f".format(Locale.US, site.latitudeDeg)
                                                            longitudeText = "%.6f".format(Locale.US, site.longitudeDeg)
                                                            prefs.edit()
                                                                .putFloat("polar_latitude_deg", site.latitudeDeg.toFloat())
                                                                .putFloat("polar_longitude_deg", site.longitudeDeg.toFloat())
                                                                .apply()
                                                        }
                                                    }
                                                } else {
                                                    locationPermissionLauncher.launch(
                                                        arrayOf(
                                                            Manifest.permission.ACCESS_FINE_LOCATION,
                                                            Manifest.permission.ACCESS_COARSE_LOCATION
                                                        )
                                                    )
                                                }
                                            }
                                        ) { Text(stringResource(R.string.phone_gps)) }
                                        OutlinedButton(
                                            onClick = onReadMountSite,
                                            enabled = !mountBusy
                                        ) { Text(stringResource(if (mountBusy) R.string.mount_busy else R.string.read_mount)) }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = {
                                                val site = mountSite ?: return@OutlinedButton
                                                latitudeText = "%.6f".format(Locale.US, site.latitudeDeg)
                                                longitudeText = "%.6f".format(Locale.US, site.longitudeDeg)
                                                prefs.edit()
                                                    .putFloat("polar_latitude_deg", site.latitudeDeg.toFloat())
                                                    .putFloat("polar_longitude_deg", site.longitudeDeg.toFloat())
                                                    .apply()
                                            },
                                            enabled = mountSite != null
                                        ) { Text(stringResource(R.string.mount_to_app)) }
                                        OutlinedButton(
                                            onClick = {
                                                val site = phoneSite ?: return@OutlinedButton
                                                onSyncPhoneSiteToMount(site.latitudeDeg, site.longitudeDeg)
                                            },
                                            enabled = phoneSite != null && !mountBusy
                                        ) { Text(stringResource(R.string.phone_to_mount)) }
                                    }
                                }
                            }

                            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(stringResource(R.string.nina_tppa_options), style = MaterialTheme.typography.titleSmall)
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        LabeledSwitch(stringResource(R.string.manual), manualMode) {
                                            manualMode = it
                                            prefs.edit().putBoolean("polar_manual_mode", it).apply()
                                        }
                                        LabeledSwitch(stringResource(R.string.start_current), startFromCurrent) {
                                            startFromCurrent = it
                                            prefs.edit().putBoolean("polar_start_current", it).apply()
                                        }
                                        LabeledSwitch(stringResource(R.string.east), eastDirection) {
                                            eastDirection = it
                                            prefs.edit().putBoolean("polar_east_direction", it).apply()
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OptionField(stringResource(R.string.distance_deg), targetDistanceText, Modifier.weight(1f)) {
                                            targetDistanceText = it.filterCoordinateText()
                                            it.toIntOrNull()?.let { value -> prefs.edit().putInt("polar_target_distance_deg", value.coerceIn(1, 90)).apply() }
                                        }
                                        OptionField(stringResource(R.string.move_rate), moveRateText, Modifier.weight(1f)) {
                                            moveRateText = it.filterCoordinateText()
                                            it.toFloatOrNull()?.let { value -> prefs.edit().putFloat("polar_move_rate_deg_sec", value.coerceIn(0.1f, 20f)).apply() }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OptionField(stringResource(R.string.search_radius), searchRadiusText, Modifier.weight(1f)) {
                                            searchRadiusText = it.filterCoordinateText()
                                            it.toFloatOrNull()?.let { value -> prefs.edit().putFloat("polar_search_radius_deg", value.coerceIn(.1f, 180f)).apply() }
                                        }
                                        OptionField(stringResource(R.string.tolerance_arcmin), alignmentToleranceText, Modifier.weight(1f)) {
                                            alignmentToleranceText = it.filterCoordinateText()
                                            it.toFloatOrNull()?.let { value -> prefs.edit().putFloat("polar_alignment_tolerance_arcmin", value.coerceAtLeast(0f)).apply() }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OptionField(stringResource(R.string.temperature_c), temperatureText, Modifier.weight(1f)) {
                                            temperatureText = it.filterCoordinateText()
                                            it.toFloatOrNull()?.let { value -> prefs.edit().putFloat("polar_temperature_c", value).apply() }
                                        }
                                        OptionField(stringResource(R.string.pressure_hpa), pressureText, Modifier.weight(1f)) {
                                            pressureText = it.filterCoordinateText()
                                            prefs.edit().putFloat("polar_pressure_hpa", it.toFloatOrNull() ?: 0f).apply()
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        LabeledSwitch(stringResource(R.string.refraction), refractionAdjustment) {
                                            refractionAdjustment = it
                                            prefs.edit().putBoolean("polar_refraction_adjustment", it).apply()
                                        }
                                        LabeledSwitch(stringResource(R.string.solve_blind_fallback), blindFallback) {
                                            blindFallback = it
                                            prefs.edit().putBoolean("polar_blind_fallback", it).apply()
                                        }
                                    }
                                    LabeledSwitch(stringResource(R.string.mount_hint), useMountHint) {
                                    useMountHint = it
                                    prefs.edit().putBoolean("polar_use_mount_hint", it).apply()
                                }
                                Text(
                                        stringResource(R.string.polar_refraction_hint),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Text(
                                        stringResource(R.string.polar_continuous_hint),
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Button(
                                            onClick = {
                                                onMoveMountRaBy(
                                                    targetDistanceText.toDoubleOrNull()?.coerceIn(1.0, 90.0) ?: 10.0,
                                                    eastDirection,
                                                    moveRateText.toDoubleOrNull()?.coerceIn(0.1, 20.0) ?: 3.0
                                                )
                                            },
                                            enabled = mountCoordinates != null && !mountBusy && !manualMode
                                        ) { Text(stringResource(R.string.move_ra)) }
                                        OutlinedButton(
                                            onClick = onStopMountRaMove,
                                            enabled = mountBusy
                                        ) { Text(stringResource(R.string.stop)) }
                                    }
                                    if (mountMoveStatus.isNotBlank()) {
                                        Text(mountMoveStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                                    }
                                }
                            }

                        }

                        TextButton(onClick = { showSamples = !showSamples }, modifier = Modifier.testTag("polar-sample-details")) {
                            Text(stringResource(R.string.polar_sample_details))
                            Icon(if (showSamples) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
                        }
                        if (showSamples) {
                            (0..3).filter { it < 3 || adjustment != null || samples[3].file != null ||
                                samples[3].phase != PolarSamplePhase.EMPTY }.forEach { index ->
                                MeasurementCard(
                                    index = index,
                                    file = samples[index].file,
                                    solve = samples[index].result,
                                    solving = solvingSlot == index,
                                    sample = samples[index],
                                    actionsEnabled = !taskBusy && !mountBusy,
                                    canRecapture = index == 3 || index == sampleModel.positionedPoint,
                                    onSelect = {
                                        selectedSlot = index
                                        imagePickerSlot = index
                                    },
                                    onSolve = { runSingleSample(index, false) },
                                    onRecapture = { runSingleSample(index, true) },
                                    onBlindRetry = { runSingleSample(index, false, blind = true) },
                                    onViewImage = { inspectFile = samples[index].file },
                                    previewSettings = alignmentPreviewSettings
                                )
                            }

                            Button(
                                onClick = {
                                    val site = currentSite()
                                    if (site == null) {
                                        error = context.getString(R.string.invalid_coordinates)
                                        return@Button
                                    }
                                    val points = (0..2).mapNotNull { index ->
                                        val solve = solves[index]
                                        val time = solvedAt[index]
                                        val ra = solve?.raDeg
                                        val dec = solve?.decDeg
                                        if (solve?.success == true && time != null && ra != null && dec != null) {
                                            PolarSolvePoint(ra, dec, time)
                                        } else {
                                            null
                                        }
                                    }
                                    if (points.size != 3) {
                                        error = threeSolvesRequired
                                        return@Button
                                    }
                                    prefs.edit()
                                        .putFloat("polar_latitude_deg", site.latitudeDeg.toFloat())
                                        .putFloat("polar_longitude_deg", site.longitudeDeg.toFloat())
                                        .apply()
                                    try {
                                        val solved = PolarAlignmentCalculator.determine(
                                            points = points,
                                            site = site,
                                            refraction = currentRefraction(),
                                            correctForRefraction = refractionAdjustment
                                        )
                                        publishAdjustment(solved, solved.initial, sampleSession.samples.value[2])
                                        correctionField = null
                                        error = null
                                    } catch (failure: Exception) {
                                        error = failure.message ?: tppaFailed
                                        FileLogger.e("PolarAlign", "Three-point geometry failed", failure)
                                    }
                                },
                                enabled = !runningAuto && solvingSlot == null && solves.count { it?.success == true } == 3
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.calculate))
                            }
                        }



                    }
                }
            )
        }
    }
}

private const val SETTLE_MILLIS = 3_000L

/** Barometric pressure of the standard atmosphere at a given elevation. */
private fun standardPressureHPa(elevationMeters: Double): Double =
    1013.25 * (1.0 - 2.25577e-5 * elevationMeters).coerceAtLeast(0.0).pow(5.25588)

/**
 * Epoch a solved field belongs to. The sky drifts 15 arcseconds per second of clock error, so a
 * frame loaded from disk long after it was taken must be dated from its header, not from the wall
 * clock.
 */
private suspend fun exposureTimeOf(file: File): Instant = withContext(Dispatchers.IO) {
    FitsHeaderReader.midExposureTime(file) ?: Instant.now()
}

/** Shortest separation between two right ascensions, in degrees. */
private fun raSeparationDeg(fromDeg: Double?, toDeg: Double?): Double? {
    if (fromDeg == null || toDeg == null) return null
    return 180.0 - abs(abs(fromDeg - toDeg) - 180.0)
}

/**
 * Manual mode waits for the observer to rotate the RA axis. With a connected mount the travelled
 * distance is read back, otherwise the observer confirms the move themselves.
 */
private suspend fun awaitManualMove(
    coordinates: State<MountCoordinates?>,
    advanceRequested: State<Boolean>,
    startRaDeg: Double?,
    targetDistanceDeg: Double,
    onProgress: (Double?) -> Unit
) {
    while (true) {
        if (advanceRequested.value) return
        val travelled = raSeparationDeg(startRaDeg, coordinates.value?.raDeg)
        onProgress(travelled)
        if (travelled != null && travelled >= targetDistanceDeg - 1.0) return
        delay(500L)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MeasurementCard(
    index: Int,
    file: File?,
    solve: PlateSolveResult?,
    solving: Boolean,
    onSelect: () -> Unit,
    onSolve: () -> Unit,
    sample: PolarSample = PolarSample(file = file, result = solve),
    actionsEnabled: Boolean = true,
    canRecapture: Boolean = true,
    onViewImage: () -> Unit = {},
    onRecapture: () -> Unit = {},
    onBlindRetry: () -> Unit = {},
    previewSettings: com.indigo.mobileobservatory.camera.CameraPreviewSettings = com.indigo.mobileobservatory.camera.CameraPreviewSettings()
) {
    val clipboard = LocalClipboardManager.current
    var showLog by rememberSaveable(file?.absolutePath) { mutableStateOf(false) }
    val available = file?.isFile == true
    Surface(modifier = Modifier.testTag("polar-point-$index"), shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (index == 3) stringResource(R.string.polar_correction_sample) else stringResource(R.string.point_number, index + 1),
                style = MaterialTheme.typography.titleSmall)
            if (sample.phase == PolarSamplePhase.CAPTURING || sample.phase == PolarSamplePhase.SOLVING) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(if (sample.phase == PolarSamplePhase.CAPTURING) R.string.polar_wait_frame else R.string.solving))
            }
            if (available) PolarSampleThumbnail(requireNotNull(file), onViewImage, previewSettings)
            else if (sample.phase == PolarSamplePhase.FAILED) Text(stringResource(R.string.polar_no_image_captured))
            solve?.takeIf { it.success }?.let { Text("RA ${it.raHms}  Dec ${it.decDms}", style = MaterialTheme.typography.bodySmall) }
            if (sample.phase == PolarSamplePhase.CANCELLED) Text(stringResource(R.string.polar_sample_cancelled))
            if (sample.phase == PolarSamplePhase.FAILED) {
                Text(sample.failure ?: solve?.message.orEmpty(), color = MaterialTheme.colorScheme.error)
                val advice = when (sample.advice()) {
                    PolarSampleAdvice.CAPTURE -> R.string.polar_capture_advice
                    PolarSampleAdvice.OPTICS -> R.string.polar_optics_advice
                    PolarSampleAdvice.STARS -> R.string.polar_stars_advice
                    PolarSampleAdvice.DATABASE -> R.string.polar_database_advice
                    PolarSampleAdvice.TIMEOUT -> R.string.polar_timeout_advice
                    PolarSampleAdvice.SOLVE -> R.string.polar_solve_advice
                }
                Text(stringResource(advice), style = MaterialTheme.typography.bodySmall)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onViewImage, modifier = Modifier.testTag("polar-view-$index"), enabled = available) { Text(stringResource(R.string.polar_view_sample)) }
                Button(onClick = onSolve, modifier = Modifier.testTag("polar-retry-$index"), enabled = available && actionsEnabled && !solving) { Text(stringResource(R.string.solve)) }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (canRecapture) OutlinedButton(onClick = onRecapture, modifier = Modifier.testTag("polar-recapture-$index"), enabled = actionsEnabled) { Text(stringResource(R.string.polar_recapture_sample)) }
                TextButton(onClick = onSelect, modifier = Modifier.testTag("polar-select-$index"), enabled = actionsEnabled) { Text(stringResource(R.string.select)) }
            }
            if (sample.phase == PolarSamplePhase.FAILED && sample.failedPhase == PolarSamplePhase.SOLVING &&
                sample.advice() !in listOf(PolarSampleAdvice.OPTICS, PolarSampleAdvice.DATABASE)) {
                TextButton(onClick = onBlindRetry, enabled = available && actionsEnabled) { Text(stringResource(R.string.polar_retry_blind)) }
            }
            if (sample.result != null || sample.failure != null) {
                val diagnostics = buildString {
                    appendLine("point=${index + 1} phase=${sample.phase} failedPhase=${sample.failedPhase}")
                    appendLine("image=${file?.absolutePath} bytes=${file?.length()} capturedAt=${sample.capturedAt}")
                    appendLine("reason=${sample.failure.orEmpty()} elapsedMs=${sample.result?.elapsedMs}")
                    append(sample.result?.log.orEmpty())
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { showLog = !showLog }, modifier = Modifier.testTag("polar-log-$index")) { Text(stringResource(if (showLog) R.string.hide_log else R.string.show_log)) }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(diagnostics)) }) { Text(stringResource(R.string.polar_copy_diagnostics)) }
                }
                if (showLog) SelectionContainer {
                    Text(diagnostics.take(64_000), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun OptionField(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        modifier = modifier
    )
}

@Composable
private fun LabeledSwitch(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun PolarAlignmentResultCard(
    determination: PolarErrorDetermination,
    current: PolarAlignmentResult,
    correctionField: CorrectionFieldInfo?
) {
    val latitudeDeg = determination.site.latitudeDeg
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.result), style = MaterialTheme.typography.titleSmall)
            val moveDown = stringResource(R.string.move_down)
            val moveUp = stringResource(R.string.move_up)
            val moveLeft = stringResource(R.string.move_left_west)
            val moveRight = stringResource(R.string.move_right_east)

            ResultRow(stringResource(R.string.axis_azimuth), stringResource(R.string.axis_value_degrees, current.axisAzimuthDeg))
            ResultRow(stringResource(R.string.axis_altitude), stringResource(R.string.axis_value_degrees, current.axisAltitudeDeg))
            ResultRow(
                stringResource(R.string.altitude),
                stringResource(
                    R.string.polar_error_value,
                    current.altitudeErrorArcMin,
                    altitudeDirection(current.altitudeErrorDeg, latitudeDeg, moveDown, moveUp)
                )
            )
            ResultRow(
                stringResource(R.string.azimuth),
                stringResource(
                    R.string.polar_error_value,
                    current.azimuthErrorArcMin,
                    azimuthDirection(current.azimuthErrorDeg, latitudeDeg, moveLeft, moveRight)
                )
            )
            ResultRow(stringResource(R.string.total), stringResource(R.string.polar_total_value, current.totalErrorArcMin))

            if (current !== determination.initial) {
                ResultRow(
                    stringResource(R.string.polar_initial_error),
                    stringResource(R.string.polar_total_value, determination.initial.totalErrorArcMin)
                )
            }

            if (determination.declinationSpreadLarge) {
                WarningText(stringResource(R.string.polar_warn_dec_spread, determination.declinationSpreadArcsec))
            }
            if (determination.initialErrorHuge) {
                WarningText(stringResource(R.string.polar_warn_error_huge))
            } else if (determination.initialErrorLarge) {
                WarningText(stringResource(R.string.polar_warn_error_large))
            }
            correctionField?.takeIf { it.nearEastWest }?.let {
                WarningText(stringResource(R.string.polar_warn_east_west, it.distanceToEastWestDeg))
            }
        }
    }
}

@Composable
private fun WarningText(message: String) {
    Text(message, fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun ResultRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline, modifier = Modifier.width(80.dp))
        Text(value, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

private fun altitudeDirection(errorDeg: Double, latitudeDeg: Double, moveDown: String, moveUp: String): String {
    if (errorDeg == 0.0) return ""
    val north = latitudeDeg >= 0.0
    return if ((errorDeg > 0.0 && north) || (errorDeg < 0.0 && !north)) moveDown else moveUp
}

private fun azimuthDirection(errorDeg: Double, latitudeDeg: Double, moveLeft: String, moveRight: String): String {
    if (errorDeg == 0.0) return ""
    val north = latitudeDeg >= 0.0
    return if ((errorDeg > 0.0 && north) || (errorDeg < 0.0 && !north)) moveLeft else moveRight
}

private fun String.filterCoordinateText(): String {
    return filter { it.isDigit() || it == '-' || it == '+' || it == '.' }.take(12)
}

private fun hasLocationPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private suspend fun getPhoneSite(context: Context): MountSite {
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        .filter { manager.isProviderEnabled(it) }
    if (providers.isEmpty()) error(context.getString(R.string.location_provider_disabled))

    val last = providers.mapNotNull { provider -> manager.getLastKnownLocation(provider) }
        .maxWithOrNull(compareBy<Location> { it.time }.thenByDescending { -it.accuracy })
    if (last != null && System.currentTimeMillis() - last.time < 10 * 60 * 1000L) {
        return MountSite(last.latitude, last.longitude)
    }

    return withTimeoutOrNull(15_000L) {
        suspendCancellableCoroutine { continuation ->
            var resumed = false
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (!resumed) {
                        resumed = true
                        manager.removeUpdates(this)
                        continuation.resume(MountSite(location.latitude, location.longitude))
                    }
                }

                @Deprecated("Deprecated in Android framework")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                }
            }
            providers.forEach { provider ->
                manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            }
            continuation.invokeOnCancellation {
                manager.removeUpdates(listener)
            }
        }
    } ?: error(context.getString(R.string.phone_location_timeout))
}

private fun siteDifferenceMeters(a: MountSite, b: MountSite): Double {
    val dLat = (a.latitudeDeg - b.latitudeDeg) * 111_320.0
    val meanLat = Math.toRadians((a.latitudeDeg + b.latitudeDeg) / 2.0)
    val dLon = (a.longitudeDeg - b.longitudeDeg) * 111_320.0 * kotlin.math.cos(meanLat)
    return kotlin.math.hypot(dLat, dLon)
}
