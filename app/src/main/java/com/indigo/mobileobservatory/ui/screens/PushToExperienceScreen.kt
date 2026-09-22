package com.indigo.mobileobservatory.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.camera.FrameData
import com.indigo.mobileobservatory.camera.PhoneCameraCapability
import com.indigo.mobileobservatory.camera.PhoneLensRole
import com.indigo.mobileobservatory.camera.PhoneManualExposure
import com.indigo.mobileobservatory.catalog.CatalogObject
import com.indigo.mobileobservatory.pointing.GuidanceProximity
import com.indigo.mobileobservatory.pointing.MotionPhase
import com.indigo.mobileobservatory.pointing.PhoneCaptureAttempt
import com.indigo.mobileobservatory.pointing.PhoneSiteProvider
import com.indigo.mobileobservatory.pointing.PhoneSkyAttitudeSource
import com.indigo.mobileobservatory.pointing.PhoneSkySolveStage
import com.indigo.mobileobservatory.pointing.PhoneSolveCaptureLadder
import com.indigo.mobileobservatory.pointing.PushToAutoSolve
import com.indigo.mobileobservatory.pointing.PushToGuidance
import com.indigo.mobileobservatory.pointing.SkyAttitudeFix
import com.indigo.mobileobservatory.pointing.StarExtractionResult
import com.indigo.mobileobservatory.pointing.WideFieldSolveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlin.math.abs

private val NightBlack = Color(0xFF050505)
private val NightRed = Color(0xFFFF8A80)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushToScreen(
    onBack: () -> Unit,
    onOpenTargets: () -> Unit = {},
    initialTargetName: String? = null,
    targetRaHours: Double = 5.588,
    targetDecDeg: Double = -5.391
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val source = remember { PhoneSkyAttitudeSource(context) }
    val capturePrefs = remember { PushToCapturePrefs(context) }
    var currentFix by remember { mutableStateOf<SkyAttitudeFix?>(null) }
    var site by remember { mutableStateOf<ObserverSite?>(null) }
    var cameras by remember { mutableStateOf<List<PhoneCameraCapability>>(emptyList()) }
    var selectedCameraId by remember { mutableStateOf<String?>(null) }
    var exposureSeconds by remember { mutableFloatStateOf(1f) }
    var iso by remember { mutableFloatStateOf(1600f) }
    var autoIso by remember { mutableStateOf(true) }
    var preferRaw by remember { mutableStateOf(true) }
    var burstFrameCount by remember { mutableFloatStateOf(1f) }
    var cameraMenuOpen by remember { mutableStateOf(false) }
    var settingsExpanded by remember { mutableStateOf(false) }
    var solving by remember { mutableStateOf(false) }
    var sessionArmed by remember { mutableStateOf(false) }
    var motionPhase by remember { mutableStateOf(MotionPhase.STILL) }
    var captureDue by remember { mutableStateOf(true) }
    var lastSolveEndedMs by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf("") }
    var capturedFrame by remember { mutableStateOf<FrameData?>(null) }
    var extraction by remember { mutableStateOf<StarExtractionResult?>(null) }
    var skySolution by remember { mutableStateOf<WideFieldSolveResult?>(null) }
    var eyepieceFov by remember { mutableFloatStateOf(1.5f) }
    var previousProximity by remember { mutableStateOf<GuidanceProximity?>(null) }
    var showTargetSheet by remember { mutableStateOf(false) }
    var targetName by remember { mutableStateOf(initialTargetName ?: "M42 · Orion Nebula") }
    var selectedRaHours by remember { mutableDoubleStateOf(targetRaHours) }
    var selectedDecDeg by remember { mutableDoubleStateOf(targetDecDeg) }

    fun persistCaptureSettings(cameraId: String?) {
        val id = cameraId ?: return
        capturePrefs.save(
            PushToCaptureSettings(
                cameraId = id,
                exposureSeconds = exposureSeconds.toDouble(),
                iso = iso.toInt(),
                preferRaw = preferRaw,
                autoIso = autoIso,
                burstFrameCount = burstFrameCount.toInt()
            )
        )
    }

    fun applyCameraDefaults(camera: PhoneCameraCapability) {
        val saved = capturePrefs.load()
        val defaults = defaultPushToSettings(camera)
        selectedCameraId = camera.cameraId
        if (saved != null && saved.cameraId == camera.cameraId) {
            val range = camera.captureExposureRange()
            exposureSeconds = saved.exposureSeconds.toFloat().coerceIn(range.start, range.endInclusive)
            iso = saved.iso.toFloat().coerceIn(
                (camera.isoRange?.lower ?: 100).toFloat(),
                (camera.isoRange?.upper ?: saved.iso).toFloat()
            )
            autoIso = saved.autoIso
            preferRaw = saved.preferRaw && camera.supportsRaw
            burstFrameCount = saved.burstFrameCount.toFloat().coerceIn(1f, 16f)
        } else {
            exposureSeconds = defaults.exposureSeconds.toFloat()
            iso = defaults.iso.toFloat()
            autoIso = defaults.autoIso
            preferRaw = defaults.preferRaw
            burstFrameCount = defaults.burstFrameCount.toFloat()
        }
    }

    fun loadCameras() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) return
        scope.launch {
            val found = withContext(Dispatchers.Default) {
                PhoneCameraCapability.enumerateBackCameras(context)
                    .filter { it.supportsManualSensor }
            }
            cameras = found
            val savedId = capturePrefs.load()?.cameraId
            val selected = found.firstOrNull { it.cameraId == selectedCameraId }
                ?: found.firstOrNull { it.cameraId == savedId }
                ?: found.firstOrNull { it.lensRole == PhoneLensRole.MAIN }
                ?: found.firstOrNull()
            if (selected != null && selected.cameraId != selectedCameraId) applyCameraDefaults(selected)
            if (found.isEmpty()) status = context.getString(R.string.phone_camera_lens_none)
        }
    }

    fun loadSite() {
        if (!PhoneSiteProvider.hasPermission(context)) return
        scope.launch {
            try {
                site = PhoneSiteProvider.currentSite(context)
                if (status.isBlank()) status = context.getString(R.string.push_to_live_ready)
            } catch (t: Throwable) {
                status = t.message ?: context.getString(R.string.phone_location_failed)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        loadSite()
        loadCameras()
    }

    DisposableEffect(source) {
        source.onFix = { currentFix = it }
        source.onMotion = { phase ->
            if (phase != motionPhase) motionPhase = phase
            val due = source.motionGate.autoCaptureDue
            if (due != captureDue) captureDue = due
        }
        if (!source.start()) status = context.getString(R.string.push_to_sensor_unavailable)
        onDispose {
            source.onFix = null
            source.onMotion = null
            source.stop()
        }
    }

    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val previousBrightness = window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window?.let { host ->
            val layout = host.attributes
            layout.screenBrightness = 0.16f
            host.attributes = layout
        }
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window?.let { host ->
                val layout = host.attributes
                layout.screenBrightness = previousBrightness
                host.attributes = layout
            }
        }
    }

    LaunchedEffect(initialTargetName, targetRaHours, targetDecDeg) {
        initialTargetName?.let { targetName = it }
        selectedRaHours = targetRaHours
        selectedDecDeg = targetDecDeg
    }

    LaunchedEffect(Unit) {
        loadSite()
        loadCameras()
        if (!PhoneSiteProvider.hasPermission(context) ||
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.CAMERA
                )
            )
        }
        delay(1_000)
        sessionArmed = true
    }

    val fix = currentFix ?: SkyAttitudeFix(
        altDeg = 0.0,
        azDeg = 0.0,
        timestampMs = System.currentTimeMillis(),
        sourceId = source.id
    )
    val now = Instant.ofEpochMilli(fix.timestampMs)
    val targetHorizontal = site?.let { observer ->
        CoordinateTransform.j2000ToTopocentric(
            EquatorialCoordinates(selectedRaHours * 15.0, selectedDecDeg),
            now,
            observer,
            refraction = null
        )
    }
    val targetAlt = targetHorizontal?.altitudeDeg ?: 0.0
    val targetAz = targetHorizontal?.azimuthDeg ?: 0.0
    val guidance = PushToGuidance.compute(
        currentAltDeg = fix.altDeg,
        currentAzDeg = fix.azDeg,
        targetAltDeg = targetAlt,
        targetAzDeg = targetAz,
        eyepieceFovDeg = eyepieceFov.toDouble(),
        previousProximity = previousProximity
    )
    SideEffect { previousProximity = guidance.proximity }
    val animatedAlt by animateFloatAsState(guidance.deltaAltDeg.toFloat(), label = "guide-alt")
    val animatedAz by animateFloatAsState(guidance.deltaAzDeg.toFloat(), label = "guide-az")
    val selectedCamera = cameras.firstOrNull { it.cameraId == selectedCameraId }
    val readyToCapture = site != null && selectedCamera != null && !solving
    val onTarget = guidance.proximity == GuidanceProximity.ON_TARGET
    val cameraPermissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    val locationPermissionGranted = PhoneSiteProvider.hasPermission(context)
    val needsPermissions = !cameraPermissionGranted || !locationPermissionGranted

    LaunchedEffect(motionPhase, source.plateSolved) {
        if (motionPhase == MotionPhase.STILL && source.plateSolved) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    LaunchedEffect(onTarget, source.plateSolved) {
        if (onTarget && source.plateSolved) {
            val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
            view.performHapticFeedback(kind)
        }
    }

    fun startSolve(auto: Boolean) {
        val observer = site ?: return
        val camera = selectedCamera ?: return
        if (solving) return
        if (auto) {
            if (!source.motionGate.consumeAutoCapture()) return
        } else {
            source.motionGate.consumeAutoCapture()
        }
        captureDue = source.motionGate.autoCaptureDue
        solving = true
        capturedFrame = null
        extraction = null
        scope.launch {
            var attempt = PhoneCaptureAttempt(
                exposureSeconds = exposureSeconds.toDouble(),
                iso = if (autoIso) PhoneManualExposure.ISO_AUTO else iso.toInt(),
                burstFrameCount = burstFrameCount.toInt()
            )
            var attemptIndex = 0
            var result = source.captureAndSolve(
                site = observer,
                exposureSeconds = attempt.exposureSeconds,
                iso = attempt.iso,
                cameraId = camera.cameraId,
                preferRaw = preferRaw,
                burstFrameCount = attempt.burstFrameCount,
                onProgress = { stage ->
                    status = context.getString(
                        when (stage) {
                            PhoneSkySolveStage.CAPTURING -> R.string.push_to_stage_capturing
                            PhoneSkySolveStage.EXTRACTING_STARS -> R.string.push_to_stage_extracting
                            PhoneSkySolveStage.SOLVING -> R.string.push_to_stage_solving
                            PhoneSkySolveStage.COMPLETE -> R.string.push_to_stage_complete
                        }
                    )
                },
                onCapture = { frame, stars, count ->
                    capturedFrame = frame
                    extraction = stars
                    status = if (count > 1) {
                        context.getString(R.string.push_to_stage_stacked, count)
                    } else {
                        context.getString(R.string.push_to_stage_extracting)
                    }
                },
                onBurstProgress = { completed, total ->
                    status = context.getString(
                        R.string.push_to_stage_burst_capturing,
                        completed,
                        total
                    )
                }
            )
            while (!result.success) {
                val next = PhoneSolveCaptureLadder.next(
                    current = attempt,
                    failure = result.skySolution?.failure,
                    starCount = result.extraction?.stars?.size ?: 0,
                    attemptIndex = attemptIndex,
                    maxExposureSeconds = camera.captureExposureRange().endInclusive.toDouble(),
                    minIso = camera.isoRange?.lower ?: 100,
                    maxIso = camera.isoRange?.upper ?: 3200
                ) ?: break
                attempt = next
                attemptIndex++
                exposureSeconds = attempt.exposureSeconds.toFloat()
                burstFrameCount = attempt.burstFrameCount.toFloat()
                if (attempt.iso == PhoneManualExposure.ISO_AUTO) {
                    autoIso = true
                } else {
                    autoIso = false
                    iso = attempt.iso.toFloat()
                }
                status = context.getString(R.string.push_to_retrying, attempt.burstFrameCount)
                result = source.captureAndSolve(
                    site = observer,
                    exposureSeconds = attempt.exposureSeconds,
                    iso = attempt.iso,
                    cameraId = camera.cameraId,
                    preferRaw = preferRaw,
                    burstFrameCount = attempt.burstFrameCount,
                    onProgress = { stage ->
                        status = context.getString(
                            when (stage) {
                                PhoneSkySolveStage.CAPTURING -> R.string.push_to_stage_capturing
                                PhoneSkySolveStage.EXTRACTING_STARS -> R.string.push_to_stage_extracting
                                PhoneSkySolveStage.SOLVING -> R.string.push_to_stage_solving
                                PhoneSkySolveStage.COMPLETE -> R.string.push_to_stage_complete
                            }
                        )
                    },
                    onCapture = { frame, stars, count ->
                        capturedFrame = frame
                        extraction = stars
                    },
                    onBurstProgress = { completed, total ->
                        status = context.getString(
                            R.string.push_to_stage_burst_capturing,
                            completed,
                            total
                        )
                    }
                )
            }
            capturedFrame = result.frame ?: capturedFrame
            extraction = result.extraction ?: extraction
            skySolution = result.skySolution
            status = if (result.success) {
                context.getString(
                    R.string.push_to_solve_success,
                    result.extraction?.stars?.size ?: 0
                )
            } else {
                context.getString(
                    PushToSolveCopy.messageRes(
                        result.skySolution?.failure,
                        result.extraction?.stars?.size ?: 0
                    )
                )
            }
            persistCaptureSettings(camera.cameraId)
            if (result.success) settingsExpanded = false
            source.motionGate.notifyCaptureFinished()
            lastSolveEndedMs = System.currentTimeMillis()
            captureDue = source.motionGate.autoCaptureDue
            solving = false
        }
    }

    LaunchedEffect(
        sessionArmed,
        readyToCapture,
        motionPhase,
        captureDue,
        solving,
        source.plateSolved,
        onTarget
    ) {
        if (!sessionArmed || !readyToCapture) return@LaunchedEffect
        if (source.plateSolved && onTarget && motionPhase == MotionPhase.STILL) {
            source.motionGate.acknowledgeIdle()
            captureDue = false
            return@LaunchedEffect
        }
        val clockMs = System.currentTimeMillis()
        if (!PushToAutoSolve.shouldFire(
                phase = motionPhase,
                autoCaptureDue = source.motionGate.autoCaptureDue,
                alreadySolved = source.plateSolved,
                onTarget = onTarget,
                solving = solving,
                nowMs = clockMs,
                lastSolveEndedMs = lastSolveEndedMs
            )
        ) return@LaunchedEffect
        startSolve(auto = true)
    }

    val usagePrompt = when {
        solving && source.plateSolved -> stringResource(R.string.push_to_hold_still_refine)
        solving -> stringResource(R.string.push_to_hold_still_align)
        motionPhase == MotionPhase.MOVING && source.plateSolved ->
            stringResource(R.string.push_to_keep_pushing)
        motionPhase == MotionPhase.MOVING -> stringResource(R.string.push_to_waiting_still)
        source.plateSolved && onTarget -> stringResource(R.string.push_to_on_target)
        source.plateSolved -> stringResource(R.string.push_to_keep_pushing)
        else -> stringResource(R.string.push_to_waiting_still)
    }

    val moveLabel = when {
        guidance.proximity == GuidanceProximity.ON_TARGET -> stringResource(R.string.push_to_on_target)
        guidance.zenithDegenerate -> stringResource(R.string.push_to_zenith_warn)
        else -> buildList {
            if (abs(guidance.deltaAltDeg) >= 0.05) {
                add(
                    stringResource(
                        if (guidance.deltaAltDeg > 0) R.string.push_to_raise_tube else R.string.push_to_lower_tube,
                        PushToGuidance.formatDegrees(guidance.deltaAltDeg)
                    )
                )
            }
            if (abs(guidance.deltaAzDeg) >= 0.05) {
                add(
                    stringResource(
                        if (guidance.deltaAzDeg > 0) R.string.push_to_yaw_right else R.string.push_to_yaw_left,
                        PushToGuidance.formatDegrees(guidance.deltaAzDeg)
                    )
                )
            }
        }.joinToString("\n")
    }

    Scaffold(
        containerColor = NightBlack,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NightBlack,
                    titleContentColor = NightRed,
                    navigationIconContentColor = NightRed
                ),
                title = {
                    Column {
                        Text(targetName, maxLines = 1, fontWeight = FontWeight.Bold)
                        Text(
                            stringResource(
                                if (source.plateSolved) R.string.push_to_step_guiding
                                else R.string.push_to_step_setup
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (source.plateSolved) Color(0xFF80CBC4) else Color(0xFFFFCC80)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = { showTargetSheet = true }) {
                        Text(stringResource(R.string.push_to_change_target), color = NightRed)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(NightBlack)
        ) {
            WorkflowStrip(
                targetReady = targetHorizontal != null,
                cameraReady = selectedCamera != null,
                solved = source.plateSolved || skySolution?.success == true,
                onTarget = onTarget && source.plateSolved
            )

            if (source.plateSolved && site != null) {
                PushToNightChart(
                    currentAltDeg = fix.altDeg,
                    currentAzDeg = fix.azDeg,
                    targetAltDeg = targetAlt,
                    targetAzDeg = targetAz,
                    site = site!!,
                    instant = now,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.52f)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(0.35f)
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SseReticle(
                        cmd = guidance,
                        deltaAlt = animatedAlt.toDouble(),
                        deltaAz = animatedAz.toDouble(),
                        eyepieceFovDeg = eyepieceFov.toDouble(),
                        color = guidanceColor(guidance.proximity),
                        pulseAlpha = 0.85f,
                        modifier = Modifier.weight(1f).fillMaxSize()
                    )
                    Column(
                        modifier = Modifier.weight(0.8f).padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            usagePrompt,
                            color = guidanceColor(guidance.proximity),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        if (!onTarget) {
                            Text(
                                moveLabel,
                                color = guidanceColor(guidance.proximity),
                                style = MaterialTheme.typography.titleMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                        Text(
                            stringResource(
                                R.string.push_to_distance_remaining,
                                PushToGuidance.formatDegrees(guidance.separationDeg)
                            ),
                            color = Color(0xFFBCAAA4),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        stringResource(R.string.push_to_first_time_help),
                        color = Color(0xFFFFCCBC),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        usagePrompt,
                        color = Color(0xFFFFCC80),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    skySolution?.takeIf { it.success }?.let { solution ->
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF10201D))) {
                            Text(
                                stringResource(
                                    R.string.push_to_solution_summary,
                                    solution.raDeg ?: 0.0,
                                    solution.decDeg ?: 0.0,
                                    solution.rotationDeg ?: 0.0,
                                    solution.fovWidthDeg ?: 0.0,
                                    solution.fovHeightDeg ?: 0.0
                                ),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                color = Color(0xFFB2DFDB),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (!source.plateSolved) {
                                Text(
                                    stringResource(R.string.push_to_photo_only),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = Color(0xFFFFCC80),
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                    capturedFrame?.let { frame ->
                        val starCount = extraction?.stars?.size ?: 0
                        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF120909))) {
                            PhoneSkyPreview(frame, extraction, Modifier.padding(4.dp))
                            Text(
                                stringResource(
                                    if (starCount >= 15) R.string.push_to_star_count
                                    else R.string.push_to_star_count_low,
                                    starCount
                                ),
                                modifier = Modifier.padding(8.dp),
                                color = if (starCount >= 15) Color(0xFF80CBC4) else Color(0xFFFFAB91),
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (starCount < 15 && burstFrameCount.toInt() < 16 && !solving) {
                                TextButton(onClick = {
                                    val next = PhoneManualExposure.BURST_PRESETS
                                        .firstOrNull { it > burstFrameCount.toInt() } ?: 16
                                    burstFrameCount = next.toFloat()
                                    persistCaptureSettings(selectedCameraId)
                                }) {
                                    Text(stringResource(R.string.push_to_bump_burst), color = NightRed)
                                }
                            }
                        }
                    }
                    CaptureSettingsCard(
                        cameras = cameras,
                        selectedCamera = selectedCamera,
                        cameraMenuOpen = cameraMenuOpen,
                        onCameraMenuChange = { cameraMenuOpen = it },
                        onCameraSelected = {
                            applyCameraDefaults(it)
                            cameraMenuOpen = false
                        },
                        exposureSeconds = exposureSeconds,
                        onExposureChanged = {
                            exposureSeconds = it
                            persistCaptureSettings(selectedCameraId)
                        },
                        iso = iso,
                        onIsoChanged = {
                            iso = it
                            persistCaptureSettings(selectedCameraId)
                        },
                        autoIso = autoIso,
                        onAutoIsoChanged = {
                            autoIso = it
                            persistCaptureSettings(selectedCameraId)
                        },
                        preferRaw = preferRaw,
                        onPreferRawChanged = {
                            preferRaw = it
                            persistCaptureSettings(selectedCameraId)
                        },
                        burstFrameCount = burstFrameCount,
                        onBurstFrameCountChanged = {
                            burstFrameCount = it
                            persistCaptureSettings(selectedCameraId)
                        },
                        expanded = settingsExpanded,
                        collapsible = true,
                        onExpandedChanged = { settingsExpanded = it }
                    )
                }
            }

            if (source.plateSolved) {
                CaptureSettingsCard(
                    cameras = cameras,
                    selectedCamera = selectedCamera,
                    cameraMenuOpen = cameraMenuOpen,
                    onCameraMenuChange = { cameraMenuOpen = it },
                    onCameraSelected = {
                        applyCameraDefaults(it)
                        cameraMenuOpen = false
                    },
                    exposureSeconds = exposureSeconds,
                    onExposureChanged = {
                        exposureSeconds = it
                        persistCaptureSettings(selectedCameraId)
                    },
                    iso = iso,
                    onIsoChanged = {
                        iso = it
                        persistCaptureSettings(selectedCameraId)
                    },
                    autoIso = autoIso,
                    onAutoIsoChanged = {
                        autoIso = it
                        persistCaptureSettings(selectedCameraId)
                    },
                    preferRaw = preferRaw,
                    onPreferRawChanged = {
                        preferRaw = it
                        persistCaptureSettings(selectedCameraId)
                    },
                    burstFrameCount = burstFrameCount,
                    onBurstFrameCountChanged = {
                        burstFrameCount = it
                        persistCaptureSettings(selectedCameraId)
                    },
                    expanded = settingsExpanded,
                    collapsible = true,
                    onExpandedChanged = { settingsExpanded = it }
                )
            }

            if (status.isNotBlank()) {
                Text(
                    status,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
                    color = Color(0xFFBCAAA4),
                    style = MaterialTheme.typography.labelSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    enabled = readyToCapture,
                    modifier = Modifier.weight(1f),
                    onClick = { startSolve(auto = false) }
                ) {
                    Text(
                        stringResource(
                            when {
                                solving -> R.string.push_to_solving
                                source.plateSolved -> R.string.push_to_resolve
                                else -> R.string.push_to_start_alignment
                            }
                        )
                    )
                }
                if (needsPermissions) {
                    TextButton(onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.CAMERA
                            )
                        )
                    }) {
                        Text(stringResource(R.string.push_to_permissions), color = NightRed)
                    }
                }
            }
        }
    }
    if (showTargetSheet) {
        ModalBottomSheet(
            onDismissRequest = { showTargetSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = NightBlack,
            contentColor = NightRed
        ) {
            Text(
                stringResource(R.string.push_to_pick_target),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontWeight = FontWeight.Bold,
                color = NightRed
            )
            TargetLibraryContent(
                onGuideTo = { obj: CatalogObject ->
                    targetName = "${obj.id} · ${obj.name}"
                    selectedRaHours = obj.raHours
                    selectedDecDeg = obj.decDeg
                    showTargetSheet = false
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(480.dp)
                    .padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun WorkflowStrip(
    targetReady: Boolean,
    cameraReady: Boolean,
    solved: Boolean,
    onTarget: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        WorkflowChip("1", stringResource(R.string.push_to_workflow_target), targetReady)
        WorkflowChip("2", stringResource(R.string.push_to_workflow_camera), solved || cameraReady)
        WorkflowChip("3", stringResource(R.string.push_to_workflow_solve), solved)
        WorkflowChip("4", stringResource(R.string.push_to_workflow_push), onTarget)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowChip(number: String, label: String, complete: Boolean) {
    FilterChip(
        selected = complete,
        onClick = {},
        enabled = false,
        label = { Text("$number  $label") }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CaptureSettingsCard(
    cameras: List<PhoneCameraCapability>,
    selectedCamera: PhoneCameraCapability?,
    cameraMenuOpen: Boolean,
    onCameraMenuChange: (Boolean) -> Unit,
    onCameraSelected: (PhoneCameraCapability) -> Unit,
    exposureSeconds: Float,
    onExposureChanged: (Float) -> Unit,
    iso: Float,
    onIsoChanged: (Float) -> Unit,
    autoIso: Boolean,
    onAutoIsoChanged: (Boolean) -> Unit,
    preferRaw: Boolean,
    onPreferRawChanged: (Boolean) -> Unit,
    burstFrameCount: Float,
    onBurstFrameCountChanged: (Float) -> Unit,
    expanded: Boolean,
    collapsible: Boolean,
    onExpandedChanged: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF120909)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.push_to_advanced_settings),
                color = NightRed,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (collapsible) {
                IconButton(onClick = { onExpandedChanged(!expanded) }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = null,
                        tint = NightRed
                    )
                }
            }
        }
        AnimatedVisibility(visible = expanded, enter = expandVertically() + fadeIn()) {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ExposedDropdownMenuBox(
                    expanded = cameraMenuOpen,
                    onExpandedChange = onCameraMenuChange
                ) {
                    OutlinedTextField(
                        value = selectedCamera?.let { localizedCameraLabel(it) }
                            ?: stringResource(R.string.phone_camera_lens_none),
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        label = { Text(stringResource(R.string.phone_camera_lens)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cameraMenuOpen) }
                    )
                    ExposedDropdownMenu(
                        expanded = cameraMenuOpen,
                        onDismissRequest = { onCameraMenuChange(false) }
                    ) {
                        cameras.forEach { camera ->
                            DropdownMenuItem(
                                text = { Text(localizedCameraLabel(camera)) },
                                onClick = { onCameraSelected(camera) }
                            )
                        }
                    }
                }
                val exposureRange = selectedCamera?.captureExposureRange() ?: 0.1f..2f
                val exposureMin = exposureRange.start
                val exposureMax = exposureRange.endInclusive
                val exposureLabel = PhoneManualExposure.formatSeconds(exposureSeconds.coerceIn(exposureMin, exposureMax))
                Text(
                    stringResource(R.string.push_to_exposure_value, exposureLabel),
                    color = Color(0xFFBCAAA4),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = PhoneManualExposure.toSlider(
                        exposureSeconds.coerceIn(exposureMin, exposureMax),
                        exposureMin,
                        exposureMax
                    ),
                    onValueChange = {
                        onExposureChanged(PhoneManualExposure.fromSlider(it, exposureMin, exposureMax))
                    },
                    valueRange = 0f..1f,
                    modifier = Modifier.height(32.dp)
                )
                if (exposureMax <= 0.6f) {
                    Text(
                        stringResource(R.string.push_to_exposure_hal_max, PhoneManualExposure.formatSeconds(exposureMax)),
                        color = Color(0xFFBCAAA4),
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Text(
                    stringResource(R.string.push_to_burst_label),
                    color = Color(0xFFBCAAA4),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    stringResource(
                        R.string.push_to_burst_hint,
                        PhoneManualExposure.formatSeconds(exposureMax),
                        burstFrameCount.toInt(),
                        PhoneManualExposure.formatSeconds(exposureSeconds * burstFrameCount)
                    ),
                    color = Color(0xFFBCAAA4),
                    style = MaterialTheme.typography.labelSmall
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PhoneManualExposure.BURST_PRESETS.forEach { count ->
                        FilterChip(
                            selected = burstFrameCount.toInt() == count,
                            onClick = { onBurstFrameCountChanged(count.toFloat()) },
                            label = { Text(stringResource(R.string.push_to_burst_frames_chip, count)) }
                        )
                    }
                }
                val isoMin = selectedCamera?.isoRange?.lower?.toFloat() ?: 100f
                val isoMax = maxOf(
                    isoMin + 1f,
                    selectedCamera?.isoRange?.upper?.toFloat() ?: 3200f
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (autoIso) {
                            stringResource(R.string.phone_camera_iso) + ": " +
                                stringResource(R.string.push_to_iso_auto_on)
                        } else {
                            stringResource(R.string.phone_camera_iso) + ": ${iso.toInt()}"
                        },
                        color = Color(0xFFBCAAA4),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        stringResource(R.string.push_to_iso_auto),
                        color = Color(0xFFBCAAA4),
                        style = MaterialTheme.typography.labelSmall
                    )
                    Switch(checked = autoIso, onCheckedChange = onAutoIsoChanged)
                }
                if (!autoIso) {
                    Slider(
                        value = iso.coerceIn(isoMin, isoMax),
                        onValueChange = onIsoChanged,
                        valueRange = isoMin..isoMax,
                        modifier = Modifier.height(32.dp)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.phone_camera_prefer_raw),
                        color = Color(0xFFBCAAA4),
                        modifier = Modifier.weight(1f)
                    )
                    Switch(
                        checked = preferRaw,
                        onCheckedChange = onPreferRawChanged,
                        enabled = selectedCamera?.supportsRaw == true
                    )
                }
            }
        }
    }
}

private fun guidanceColor(proximity: GuidanceProximity): Color = when (proximity) {
    GuidanceProximity.FAR -> Color(0xFFFF5252)
    GuidanceProximity.MEDIUM -> Color(0xFFFFD740)
    GuidanceProximity.NEAR -> Color(0xFF69F0AE)
    GuidanceProximity.ON_TARGET -> Color(0xFF00E676)
}

@Composable
private fun localizedCameraLabel(camera: PhoneCameraCapability): String {
    val role = stringResource(
        when (camera.lensRole) {
            PhoneLensRole.ULTRA_WIDE -> R.string.push_to_lens_ultra_wide
            PhoneLensRole.MAIN -> R.string.push_to_lens_main
            PhoneLensRole.TELEPHOTO -> R.string.push_to_lens_telephoto
            PhoneLensRole.UNKNOWN -> R.string.push_to_lens_other
        }
    )
    val focal = camera.equivalentFocalLengthMm?.let { " · ${it.toInt()} mm" }.orEmpty()
    val raw = if (camera.supportsRaw) " · RAW" else ""
    return role + focal + raw
}
