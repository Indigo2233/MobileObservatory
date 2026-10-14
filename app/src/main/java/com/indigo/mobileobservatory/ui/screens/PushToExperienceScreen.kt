package com.indigo.mobileobservatory.ui.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
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
import com.indigo.mobileobservatory.pointing.PointingRepository
import com.indigo.mobileobservatory.pointing.PointingEstimate
import com.indigo.mobileobservatory.pointing.OpticalIdentity
import com.indigo.mobileobservatory.pointing.ImageParity
import com.indigo.mobileobservatory.pointing.PushToVerificationSession
import com.indigo.mobileobservatory.pointing.PushToVerificationState
import com.indigo.mobileobservatory.pointing.VerificationPhase
import com.indigo.mobileobservatory.pointing.VerificationField
import com.indigo.mobileobservatory.pointing.OpticalVerificationEvidence
import com.indigo.mobileobservatory.pointing.VerificationTicket
import com.indigo.mobileobservatory.pointing.SkyPose
import com.indigo.mobileobservatory.pointing.SkyVector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
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
    targetDecDeg: Double = -5.391,
    onTargetChanged: (CatalogObject) -> Unit = {},
    pointingRepository: PointingRepository? = null,
    onCalibration: () -> Unit = {},
    verificationField: VerificationField? = null,
    opticsConfiguration: com.indigo.mobileobservatory.astro.PointingOpticsConfiguration? = null,
    onAuxiliaryGuide: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val source = remember { PhoneSkyAttitudeSource(context) }
    val uiUpdateGate = remember(source) { PushToUiUpdateGate() }
    val repository = remember(pointingRepository, context) { pointingRepository ?: PointingRepository(context.applicationContext) }
    val relationships by repository.relationships.collectAsStateWithPageLifecycle()
    val handEyes by repository.handEyes.collectAsStateWithPageLifecycle()
    val opticalAxis = remember { OpticalIdentity("telescope", "axis", "primary") }
    val verification = remember { PushToVerificationSession() }
    var verificationState by remember { mutableStateOf(PushToVerificationState()) }
    var axisEstimate by remember { mutableStateOf<PointingEstimate?>(null) }
    var calibrationMismatch by remember { mutableStateOf(false) }
    var solveJob by remember { mutableStateOf<Job?>(null) }
    var clockUtcMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var clockElapsedMs by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    val capturePrefs = remember { PushToCapturePrefs(context) }
    val opticsPrefs = remember { context.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE) }
    var calibratedCameraId by remember { mutableStateOf(opticsPrefs.getString("pointing_camera_lens_id", null)) }
    DisposableEffect(opticsPrefs) {
        val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
            if (key == "pointing_camera_lens_id") calibratedCameraId = prefs.getString(key, null)
        }
        opticsPrefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { opticsPrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    var currentFix by remember { mutableStateOf<SkyAttitudeFix?>(null) }
    var site by remember { mutableStateOf<ObserverSite?>(null) }
    val sharedSite = rememberSavedObserverSite()
    LaunchedEffect(sharedSite?.site) { sharedSite?.site?.let { site = it } }
    var cameras by remember { mutableStateOf<List<PhoneCameraCapability>>(emptyList()) }
    var selectedCameraId by rememberSaveable { mutableStateOf(calibratedCameraId) }
    var appliedCameraId by remember { mutableStateOf<String?>(null) }
    var exposureSeconds by rememberSaveable { mutableFloatStateOf(1f) }
    var iso by rememberSaveable { mutableFloatStateOf(1600f) }
    var autoIso by rememberSaveable { mutableStateOf(true) }
    var preferRaw by rememberSaveable { mutableStateOf(true) }
    var burstFrameCount by rememberSaveable { mutableFloatStateOf(1f) }
    var cameraMenuOpen by remember { mutableStateOf(false) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var solving by remember { mutableStateOf(false) }
    var sessionArmed by remember { mutableStateOf(false) }
    var motionPhase by remember { mutableStateOf(MotionPhase.STILL) }
    var captureDue by remember { mutableStateOf(true) }
    var lastSolveEndedMs by remember { mutableStateOf(0L) }
    var status by remember { mutableStateOf("") }
    var capturedFrame by remember { mutableStateOf<FrameData?>(null) }
    var extraction by remember { mutableStateOf<StarExtractionResult?>(null) }
    var skySolution by remember { mutableStateOf<WideFieldSolveResult?>(null) }
    var needsSingleFrameVerification by remember { mutableStateOf(false) }
    var eyepieceFov by rememberSaveable { mutableFloatStateOf(1.5f) }
    var previousProximity by remember { mutableStateOf<GuidanceProximity?>(null) }
    var showTargetSheet by remember { mutableStateOf(false) }
    var targetName by rememberSaveable { mutableStateOf(initialTargetName ?: "M42 · Orion Nebula") }
    var selectedRaHours by rememberSaveable { mutableDoubleStateOf(targetRaHours) }
    var selectedDecDeg by rememberSaveable { mutableDoubleStateOf(targetDecDeg) }
    var fieldConfirmed by rememberSaveable { mutableStateOf(false) }
    var fieldShape by rememberSaveable { mutableStateOf("circle") }
    var fieldWidth by rememberSaveable { mutableStateOf("1.5") }
    var fieldHeight by rememberSaveable { mutableStateOf("1.0") }
    var fieldDialog by remember { mutableStateOf(false) }
    var opticsDialog by rememberSaveable { mutableStateOf(false) }
    var fieldAdvanced by rememberSaveable { mutableStateOf(false) }
    var localOptics by remember { mutableStateOf(com.indigo.mobileobservatory.astro.PointingOpticsConfiguration.load(
        com.indigo.mobileobservatory.astro.AndroidOpticsPrefs(opticsPrefs))) }
    val workingOptics = opticsConfiguration ?: localOptics

    val configuredCamera = cameras.firstOrNull { it.cameraId == selectedCameraId }
    val selectedLensKey = configuredCamera?.let {
        "${it.logicalParentId ?: it.cameraId}/${it.cameraId}"
    }
    fun lensMatchesSelection(key: String): Boolean = if (configuredCamera?.isPhysicalSubCamera == true)
        key == selectedLensKey else configuredCamera?.let { key.startsWith("${it.openableCameraId}/") } == true
    val actualLensKey = source.latestObservation?.identity?.physicalLensId?.takeIf(::lensMatchesSelection)
    val matchingCalibration = relationships.filter { it.valid && it.source.deviceId == "phone" &&
        lensMatchesSelection(it.source.physicalLensId) && (actualLensKey == null || it.source.physicalLensId == actualLensKey) &&
        it.target == opticalAxis }.maxByOrNull { it.version }
    fun calibrationIdentity(value: com.indigo.mobileobservatory.pointing.OpticalCalibration) =
        "${value.id}:${value.version}:${value.source}:${value.sourceParity}:${value.sourceIntrinsicsFingerprint}"
    val calibrationKey = matchingCalibration?.let(::calibrationIdentity).orEmpty()
    val selectedTerminal = when (matchingCalibration?.model) {
        com.indigo.mobileobservatory.pointing.RelationshipModel.FULL_POSE -> com.indigo.mobileobservatory.astro.PointingOpticsEndpoint.SENSOR
        com.indigo.mobileobservatory.pointing.RelationshipModel.AXIS_ONLY -> com.indigo.mobileobservatory.astro.PointingOpticsEndpoint.EYEPIECE
        else -> if (workingOptics.primary.mode == com.indigo.mobileobservatory.astro.FovInstrumentMode.SENSOR)
            com.indigo.mobileobservatory.astro.PointingOpticsEndpoint.SENSOR else com.indigo.mobileobservatory.astro.PointingOpticsEndpoint.EYEPIECE
    }
    val sharedField = matchingCalibration?.let { calibration -> workingOptics.verificationField(
        com.indigo.mobileobservatory.astro.PointingOpticsSelection(com.indigo.mobileobservatory.astro.OpticsTrainId.PRIMARY,
            if (calibration.model == com.indigo.mobileobservatory.pointing.RelationshipModel.FULL_POSE)
                com.indigo.mobileobservatory.astro.PointingOpticsEndpoint.SENSOR else com.indigo.mobileobservatory.astro.PointingOpticsEndpoint.EYEPIECE)) }
    val configuredField = verificationField ?: if (fieldConfirmed) runCatching {
        if (fieldShape == "circle") VerificationField(circleDiameterDeg = fieldWidth.toDouble())
        else VerificationField(widthDeg = fieldWidth.toDouble(), heightDeg = fieldHeight.toDouble())
    }.getOrNull() else sharedField
    fun taskSignature() = "$targetName|$selectedRaHours|$selectedDecDeg|$selectedCameraId|$preferRaw|$configuredField|$fieldConfirmed/$fieldShape/$fieldWidth/$fieldHeight"
    var acceptedAbsoluteEpoch by remember { mutableStateOf<Long?>(null) }
    fun refreshAxisEstimate() {
        val observation = source.latestObservation
        val pose = source.currentPose
        axisEstimate = if (observation != null && lensMatchesSelection(observation.identity.physicalLensId)) {
            if (pose != null) repository.publishPropagated(observation, pose, opticalAxis,
                System.currentTimeMillis(), SystemClock.elapsedRealtime())
            else if (acceptedAbsoluteEpoch == source.motionGate.motionEpoch &&
                SystemClock.elapsedRealtime() - observation.stamp.elapsedMidpointMs in 0..10_000)
                repository.publish(observation, opticalAxis)
            else null
        } else null
    }
    fun fieldForEstimate(estimate: PointingEstimate?, observer: ObserverSite, instant: Instant): VerificationField {
        val field = configuredField ?: return VerificationField()
        if (field.circleDiameterDeg != null) return field
        val imageUp = estimate?.imageUpPositionAngleDeg
        if (estimate == null || imageUp == null) {
            // Every rectangle contains this circle, regardless of unknown image roll.
            return VerificationField(circleDiameterDeg = minOf(field.widthDeg!!, field.heightDeg!!))
        }
        val pose = SkyPose.fromSolution(estimate.direction, imageUp)
        val upDirection = (pose.forward * kotlin.math.cos(Math.toRadians(.01)) + pose.up * kotlin.math.sin(Math.toRadians(.01))).direction()
        val center = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(estimate.direction.raDeg, estimate.direction.decDeg), instant, observer, null)
        val up = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(upDirection.raDeg, upDirection.decDeg), instant, observer, null)
        val offsets = PushToGuidance.fieldOffsets(center.altitudeDeg, center.azimuthDeg, up.altitudeDeg, up.azimuthDeg)
            ?: return VerificationField()
        return field.copy(altitudeUpRollDeg = Math.toDegrees(kotlin.math.atan2(offsets.first, offsets.second)))
    }
    LaunchedEffect(targetName, selectedRaHours, selectedDecDeg, selectedCameraId, preferRaw, calibrationKey, configuredField) {
        calibrationMismatch = false
        verification.setTask(taskSignature(), calibrationKey)
        verificationState = verification.tick(SystemClock.elapsedRealtime())
        source.requestVerification()
        captureDue = true
    }

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
        source.prepareLens(camera.cameraId, camera.sensorOrientation)
        axisEstimate = null
        acceptedAbsoluteEpoch = null
        verification.requestReference()
        verificationState = verification.state
        val saved = capturePrefs.load()
        val defaults = defaultPushToSettings(camera)
        selectedCameraId = camera.cameraId
        appliedCameraId = camera.cameraId
        if (status == context.getString(R.string.wf_push_camera_unavailable) ||
            status == context.getString(R.string.phone_camera_lens_none)) status = ""
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
            val cameraId = selectPushToCameraId(found.map { it.cameraId }, selectedCameraId, calibratedCameraId,
                capturePrefs.load()?.cameraId, found.firstOrNull { it.lensRole == PhoneLensRole.MAIN }?.cameraId)
            val selected = found.firstOrNull { it.cameraId == cameraId }
            if (selected != null && selected.cameraId != appliedCameraId) applyCameraDefaults(selected)
            if (selected == null) {
                selectedCameraId = null
                appliedCameraId = null
                axisEstimate = null
                status = context.getString(if (found.isEmpty()) R.string.phone_camera_lens_none else R.string.wf_push_camera_unavailable)
            }
        }
    }

    // A newly saved alignment selects its own lens, including when this task was retained.
    LaunchedEffect(calibratedCameraId) {
        calibratedCameraId?.let { selectedCameraId = it }
        loadCameras()
    }

    fun loadSite() {
        if (!PhoneSiteProvider.canResolveSite(context)) return
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

    val pageVisible = rememberPageForeground()
    val latestRefreshAxis by androidx.compose.runtime.rememberUpdatedState { refreshAxisEstimate() }
    DisposableEffect(source, pageVisible) {
        source.onFix = { next ->
            if (uiUpdateGate.shouldPublish(SystemClock.elapsedRealtime())) {
                currentFix = next
                latestRefreshAxis()
            }
        }
        source.onMotion = { phase ->
            if (phase != motionPhase) motionPhase = phase
            val due = source.motionGate.autoCaptureDue
            if (due != captureDue) captureDue = due
            verification.observeMotion(source.motionGate.motionEpoch, phase, SystemClock.elapsedRealtime())
            verificationState = verification.state
        }
        if (pageVisible) uiUpdateGate.reset()
        if (pageVisible && !source.start()) status = context.getString(R.string.push_to_sensor_unavailable)
        onDispose {
            source.onFix = null
            source.onMotion = null
            source.stop()
            solveJob?.cancel()
            solving = false
            acceptedAbsoluteEpoch = null
            axisEstimate = null
            verification.retry(SystemClock.elapsedRealtime())
            verificationState = verification.state
        }
    }

    LaunchedEffect(pageVisible) {
        if (!pageVisible) return@LaunchedEffect
        while (true) {
            clockElapsedMs = SystemClock.elapsedRealtime()
            clockUtcMs = System.currentTimeMillis()
            source.refreshCurrentPose(clockUtcMs)
            if (uiUpdateGate.shouldPublish(clockElapsedMs)) {
                source.latestFix()?.let { currentFix = it }
                latestRefreshAxis()
            }
            motionPhase = source.motionGate.tick(clockElapsedMs)
            verification.observeMotion(source.motionGate.motionEpoch, motionPhase, clockElapsedMs)
            verificationState = verification.tick(clockElapsedMs)
            captureDue = source.motionGate.autoCaptureDue
            val wake = listOfNotNull(source.motionGate.nextWakeAtMs, verification.nextWakeAtMs).filter { it > clockElapsedMs }.minOrNull()
            delay(if (wake != null) (wake - clockElapsedMs).coerceIn(50L, 1_000L) else 1_000L)
        }
    }

    DisposableEffect(pageVisible) {
        val window = (context as? Activity)?.window
        val previousBrightness = window?.attributes?.screenBrightness
            ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        if (pageVisible) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (pageVisible) window?.let { host ->
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
        if (!PhoneSiteProvider.canResolveSite(context) ||
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
    val now = Instant.ofEpochMilli(clockUtcMs)
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
    val axisHorizontal = axisEstimate?.let { estimate -> site?.let { observer ->
        CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(estimate.direction.raDeg, estimate.direction.decDeg),
            if (estimate.propagated) now else Instant.ofEpochMilli(estimate.absoluteUtcMs), observer, null)
    } }
    val hasAxisEstimate = axisHorizontal != null && matchingCalibration != null && !calibrationMismatch
    val activeField = site?.let { fieldForEstimate(axisEstimate, it, now) } ?: VerificationField()
    val onTarget = verificationState.isVerified && hasAxisEstimate && configuredField != null &&
        activeField.contains(axisHorizontal!!.altitudeDeg, axisHorizontal.azimuthDeg, targetAlt, targetAz,
            axisEstimate!!.uncertaintyDeg, alreadyVerified = true)
    val geometricGuidance = PushToGuidance.compute(
        currentAltDeg = axisHorizontal?.altitudeDeg ?: fix.altDeg,
        currentAzDeg = axisHorizontal?.azimuthDeg ?: fix.azDeg,
        targetAltDeg = targetAlt,
        targetAzDeg = targetAz,
        eyepieceFovDeg = eyepieceFov.toDouble(),
        previousProximity = previousProximity
    )
    val guidance = if (!onTarget && geometricGuidance.proximity == GuidanceProximity.ON_TARGET)
        geometricGuidance.copy(proximity = GuidanceProximity.NEAR) else geometricGuidance
    SideEffect { previousProximity = geometricGuidance.proximity }
    val animatedAlt by animateFloatAsState(guidance.deltaAltDeg.toFloat(), label = "guide-alt")
    val animatedAz by animateFloatAsState(guidance.deltaAzDeg.toFloat(), label = "guide-az")
    val selectedCamera = cameras.firstOrNull { it.cameraId == selectedCameraId }
    val readyToCapture = site != null && selectedCamera != null && !solving
    val cameraPermissionGranted = androidx.core.content.ContextCompat.checkSelfPermission(
        context, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    val locationPermissionGranted = PhoneSiteProvider.canResolveSite(context)
    val needsPermissions = !cameraPermissionGranted || !locationPermissionGranted

    LaunchedEffect(motionPhase, hasAxisEstimate) {
        if (motionPhase == MotionPhase.STILL && hasAxisEstimate) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }
    LaunchedEffect(onTarget) {
        if (onTarget) {
            val kind = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                HapticFeedbackConstants.CONFIRM
            } else {
                HapticFeedbackConstants.LONG_PRESS
            }
            view.performHapticFeedback(kind)
        }
    }

    fun startSolve(auto: Boolean, forceSingleFrame: Boolean = auto) {
        val observer = site ?: return
        val camera = selectedCamera ?: return
        if (solving) return
        val elapsed = SystemClock.elapsedRealtime()
        verification.setTask(taskSignature(), calibrationKey)
        verification.observeMotion(source.motionGate.motionEpoch, source.motionGate.phase, elapsed)
        if (!auto) { verification.retry(elapsed); source.requestVerification() }
        val ticket = verification.beginCapture(elapsed, allowMovingDebug = !auto && !source.available) ?: return
        val requestedSignature = taskSignature()
        val captureCalibration = matchingCalibration
        val captureHandEye = handEyes.filter { captureCalibration != null && it.valid && it.source == captureCalibration.source &&
            it.geometry.parity == captureCalibration.sourceParity &&
            it.geometry.intrinsicsFingerprint == captureCalibration.sourceIntrinsicsFingerprint }.maxByOrNull { it.version }
        source.motionGate.consumeAutoCapture()
        captureDue = source.motionGate.autoCaptureDue
        solving = true
        capturedFrame = null
        extraction = null
        needsSingleFrameVerification = false
        solveJob = scope.launch {
            try {
            var attempt = PhoneCaptureAttempt(
                exposureSeconds = exposureSeconds.toDouble(),
                iso = if (autoIso) PhoneManualExposure.ISO_AUTO else iso.toInt(),
                burstFrameCount = if (auto || forceSingleFrame) 1 else burstFrameCount.toInt()
            )
            var attemptIndex = 0
            var result = source.captureAndSolve(
                site = observer,
                exposureSeconds = attempt.exposureSeconds,
                iso = attempt.iso,
                cameraId = camera.cameraId,
                mirrored = captureCalibration?.sourceParity == ImageParity.MIRRORED,
                handEyeCalibration = captureHandEye,
                installationRevision = captureCalibration?.source?.installationRevision ?: 0,
                opticsRevision = captureCalibration?.source?.opticsRevision ?: 0,
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
                if (ticket.taskGeneration != verification.state.taskGeneration ||
                    ticket.motionEpoch != source.motionGate.motionEpoch || requestedSignature != taskSignature()) break
                val next = PhoneSolveCaptureLadder.next(
                    current = attempt,
                    failure = result.skySolution?.failure,
                    starCount = result.extraction?.stars?.size ?: 0,
                    attemptIndex = attemptIndex,
                    maxExposureSeconds = camera.captureExposureRange().endInclusive.toDouble(),
                    minIso = camera.isoRange?.lower ?: 100,
                    maxIso = camera.isoRange?.upper ?: 3200,
                    allowBurst = !(auto || forceSingleFrame)
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
                    mirrored = captureCalibration?.sourceParity == ImageParity.MIRRORED,
                    handEyeCalibration = captureHandEye,
                    installationRevision = captureCalibration?.source?.installationRevision ?: 0,
                    opticsRevision = captureCalibration?.source?.opticsRevision ?: 0,
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
            needsSingleFrameVerification = result.requiresSingleFrameVerification
            val observation = result.observation
            val relation = observation?.let { repository.relationshipFor(it, opticalAxis) }
            val absolute = if (observation != null && relation != null) relation.estimate(observation, opticalAxis) else null
            val currentTask = requestedSignature == taskSignature() && ticket.taskGeneration == verification.state.taskGeneration &&
                ticket.motionEpoch == source.motionGate.motionEpoch
            var accepted = false
            if (observation != null && relation != null && absolute != null && currentTask) {
                val instant = Instant.ofEpochMilli(observation.stamp.utcMidpointMs)
                val measuredAxis = CoordinateTransform.j2000ToTopocentric(
                    EquatorialCoordinates(absolute.direction.raDeg, absolute.direction.decDeg), instant, observer, null)
                val measuredTarget = CoordinateTransform.j2000ToTopocentric(
                    EquatorialCoordinates(selectedRaHours * 15, selectedDecDeg), instant, observer, null)
                val halfExposure = observation.stamp.exposureDurationMs / 2
                val evidence = OpticalVerificationEvidence(observation.stamp.elapsedMidpointMs,
                    observation.stamp.elapsedMidpointMs - halfExposure,
                    observation.stamp.elapsedMidpointMs + (observation.stamp.exposureDurationMs - halfExposure),
                    measuredAxis.altitudeDeg, measuredAxis.azimuthDeg, measuredTarget.altitudeDeg, measuredTarget.azimuthDeg,
                    absolute.uncertaintyDeg, calibrationIdentity(relation),
                    observation.id !in relation.trainingObservationIds && observation.id !in relation.validationObservationIds,
                    result.success, result.exposureMotionDeg, observation.stamp.clockUncertaintyMs.toDouble(), relation.valid)
                accepted = verification.complete(ticket, evidence, fieldForEstimate(absolute, observer, instant), SystemClock.elapsedRealtime())
                if (accepted) {
                    acceptedAbsoluteEpoch = ticket.motionEpoch
                    axisEstimate = repository.publish(observation, opticalAxis)
                    calibrationMismatch = false
                }
            } else {
                if (observation != null && relation == null) { calibrationMismatch = true; axisEstimate = null }
                verification.fail(ticket, SystemClock.elapsedRealtime(), if (result.success) "observation_not_usable" else "solve_failed")
            }
            verificationState = verification.tick(SystemClock.elapsedRealtime())
            status = if (result.success) {
                context.getString(
                    R.string.push_to_solve_success,
                    result.extraction?.stars?.size ?: 0
                )
            } else if (result.skySolution?.failure == com.indigo.mobileobservatory.pointing.WideFieldSolveFailure.RESOURCE_LIMIT) {
                result.message
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
            if (result.success && !accepted) status = context.getString(if (result.requiresSingleFrameVerification)
                R.string.wfp_burst_image_only else R.string.wf_push_verification_rejected)
            source.motionGate.notifyCaptureFinished(ticket.motionEpoch, success = accepted)
            lastSolveEndedMs = SystemClock.elapsedRealtime()
            captureDue = source.motionGate.autoCaptureDue
            solving = false
            } catch (cancel: CancellationException) {
                verification.fail(ticket, SystemClock.elapsedRealtime(), "capture_cancelled")
                throw cancel
            } finally {
                solving = false
                verificationState = verification.tick(SystemClock.elapsedRealtime())
                captureDue = source.motionGate.autoCaptureDue
            }
        }
    }

    LaunchedEffect(
        sessionArmed,
        pageVisible,
        readyToCapture,
        motionPhase,
        captureDue,
        solving,
        verificationState,
        clockElapsedMs,
        calibrationKey,
        configuredField
    ) {
        if (!pageVisible || !sessionArmed || !readyToCapture || matchingCalibration == null ||
            calibrationMismatch || configuredField == null || !source.available || !verificationState.needsReference ||
            verificationState.phase == VerificationPhase.RETRY_LIMIT) return@LaunchedEffect
        startSolve(auto = true)
    }

    val usagePrompt = when {
        matchingCalibration == null || calibrationMismatch -> stringResource(R.string.wf_push_calibration_needed)
        configuredField == null -> stringResource(R.string.wf_push_field_unknown)
        verificationState.phase == VerificationPhase.RETRY_LIMIT -> stringResource(R.string.wf_push_retry_limit)
        verificationState.phase == VerificationPhase.BACKOFF -> stringResource(R.string.wf_push_retry_wait)
        solving -> stringResource(R.string.wf_push_verifying)
        onTarget -> stringResource(R.string.wf_push_verified)
        motionPhase == MotionPhase.MOVING && hasAxisEstimate -> stringResource(R.string.push_to_keep_pushing)
        motionPhase == MotionPhase.MOVING -> stringResource(R.string.push_to_waiting_still)
        hasAxisEstimate -> stringResource(R.string.wf_push_estimated)
        else -> stringResource(R.string.push_to_waiting_still)
    }

    val moveLabel = when {
        onTarget -> stringResource(R.string.wf_push_verified)
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

    val captureSettings: @Composable () -> Unit = {
        CaptureSettingsCard(
            cameras = cameras,
            selectedCamera = selectedCamera,
            cameraMenuOpen = cameraMenuOpen,
            onCameraMenuChange = { cameraMenuOpen = it },
            onCameraSelected = {
                applyCameraDefaults(it)
                persistCaptureSettings(it.cameraId)
                cameraMenuOpen = false
            },
            exposureSeconds = exposureSeconds,
            onExposureChanged = { exposureSeconds = it },
            onContinuousChangeFinished = { persistCaptureSettings(selectedCameraId) },
            iso = iso,
            onIsoChanged = { iso = it },
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
    val mainContent: @Composable (Modifier, Boolean) -> Unit = { modifier, includeSettings ->
        if (hasAxisEstimate && site != null) {
            Column(modifier) {
                PushToNightChart(
                    currentAltDeg = axisHorizontal!!.altitudeDeg,
                    currentAzDeg = axisHorizontal.azimuthDeg,
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
            }
        } else {
            Column(
                modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
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
                                if (starCount >= 15) R.string.push_to_star_count else R.string.push_to_star_count_low,
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
                if (includeSettings) captureSettings()
            }
        }
    }
    val actions: @Composable () -> Unit = {
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
        if (needsSingleFrameVerification && !solving) {
            TextButton(onClick = { startSolve(auto = false, forceSingleFrame = true) }, enabled = readyToCapture) {
                Text(stringResource(R.string.wfp_verify_single_frame))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (matchingCalibration == null || calibrationMismatch) {
                FilledTonalButton(onClick = onCalibration, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.wf_push_calibrate))
                }
            } else TextButton(onClick = onCalibration, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.wf_push_calibrate))
            }
            TextButton(onClick = { opticsDialog = true }, enabled = verificationField == null, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.wf_optics_title))
            }
        }
        TextButton(onClick = { fieldAdvanced = !fieldAdvanced }, modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.wf_optics_advanced_field))
        }
        if (fieldAdvanced) Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TextButton(onClick = { fieldDialog = true }, enabled = verificationField == null) {
                Text(stringResource(R.string.wf_push_field))
            }
            if (fieldConfirmed) TextButton(onClick = { fieldConfirmed = false }) {
                Text(stringResource(R.string.wf_optics_use_shared))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilledTonalButton(
                enabled = readyToCapture && (motionPhase == MotionPhase.STILL || !source.available),
                modifier = Modifier.weight(1f),
                onClick = { startSolve(auto = false) }
            ) {
                Text(
                    stringResource(
                        when {
                            solving -> R.string.push_to_solving
                            matchingCalibration == null || calibrationMismatch -> R.string.wf_push_phone_debug
                            else -> R.string.push_to_resolve
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
                        Text(targetName, maxLines = 1, fontWeight = FontWeight.Bold,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("push-task-target"))
                        Text(
                            stringResource(
                                if (hasAxisEstimate) R.string.push_to_step_guiding
                                else R.string.push_to_step_setup
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (onTarget) Color(0xFF80CBC4) else Color(0xFFFFCC80)
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    onAuxiliaryGuide?.let { open -> TextButton(onClick = open) { Text(stringResource(R.string.wfp_source_auxiliary), color = NightRed) } }
                    TextButton(onClick = { showTargetSheet = true }) {
                        Text(stringResource(R.string.push_to_change_target), color = NightRed)
                    }
                }
            )
        }
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize()) {
        val useWideLayout = maxWidth >= 520.dp && maxWidth > maxHeight
        if (useWideLayout) {
            PushToAdaptiveLayout(
                modifier = Modifier.fillMaxSize().padding(padding).background(NightBlack),
                settingsInMain = false,
                workflow = {
                    WorkflowStrip(
                        targetReady = targetHorizontal != null,
                        cameraReady = selectedCamera != null,
                        solved = hasAxisEstimate,
                        onTarget = onTarget
                    )
                },
                main = mainContent,
                settings = captureSettings,
                actions = actions
            )
        } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(NightBlack)
        ) {
            WorkflowStrip(
                targetReady = targetHorizontal != null,
                cameraReady = selectedCamera != null,
                solved = hasAxisEstimate,
                onTarget = onTarget
            )

            if (hasAxisEstimate && site != null) {
                PushToNightChart(
                    currentAltDeg = axisHorizontal!!.altitudeDeg,
                    currentAzDeg = axisHorizontal.azimuthDeg,
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
                            persistCaptureSettings(it.cameraId)
                            cameraMenuOpen = false
                        },
                        exposureSeconds = exposureSeconds,
                        onExposureChanged = {
                            exposureSeconds = it
                        },
                        onContinuousChangeFinished = { persistCaptureSettings(selectedCameraId) },
                        iso = iso,
                        onIsoChanged = {
                            iso = it
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

            if (hasAxisEstimate) {
                CaptureSettingsCard(
                    cameras = cameras,
                    selectedCamera = selectedCamera,
                    cameraMenuOpen = cameraMenuOpen,
                    onCameraMenuChange = { cameraMenuOpen = it },
                    onCameraSelected = {
                        applyCameraDefaults(it)
                        persistCaptureSettings(it.cameraId)
                        cameraMenuOpen = false
                    },
                    exposureSeconds = exposureSeconds,
                    onExposureChanged = {
                        exposureSeconds = it
                    },
                    onContinuousChangeFinished = { persistCaptureSettings(selectedCameraId) },
                    iso = iso,
                    onIsoChanged = {
                        iso = it
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
            if (needsSingleFrameVerification && !solving) {
                TextButton(onClick = { startSolve(auto = false, forceSingleFrame = true) }, enabled = readyToCapture) {
                    Text(stringResource(R.string.wfp_verify_single_frame))
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (matchingCalibration == null || calibrationMismatch) {
                    FilledTonalButton(onClick = onCalibration, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.wf_push_calibrate))
                    }
                } else TextButton(onClick = onCalibration, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.wf_push_calibrate))
                }
                TextButton(onClick = { opticsDialog = true }, enabled = verificationField == null, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.wf_optics_title))
                }
            }
            TextButton(onClick = { fieldAdvanced = !fieldAdvanced }, modifier = Modifier.padding(horizontal = 16.dp)) {
                Text(stringResource(R.string.wf_optics_advanced_field))
            }
            if (fieldAdvanced) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { fieldDialog = true }, enabled = verificationField == null) {
                    Text(stringResource(R.string.wf_push_field))
                }
                if (fieldConfirmed) TextButton(onClick = { fieldConfirmed = false }) { Text(stringResource(R.string.wf_optics_use_shared)) }
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    enabled = readyToCapture && (motionPhase == MotionPhase.STILL || !source.available),
                    modifier = Modifier.weight(1f),
                    onClick = { startSolve(auto = false) }
                ) {
                    Text(
                        stringResource(
                            when {
                                solving -> R.string.push_to_solving
                                matchingCalibration == null || calibrationMismatch -> R.string.wf_push_phone_debug
                                else -> R.string.push_to_resolve
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
        }
    }
    if (opticsDialog) PointingOpticsConfigurationSheet(workingOptics.primaryGeometry, workingOptics.secondaryGeometry,
        onOpticalPathInvalidated = { train, reason -> repository.invalidateOpticalPath(train.prefValue(), reason.name) },
        onDismiss = { opticsDialog = false }, initialTrain = com.indigo.mobileobservatory.astro.OpticsTrainId.PRIMARY,
        terminal = selectedTerminal, restrictToTrain = true,
        onConfirmed = { localOptics = it; fieldConfirmed = false; opticsDialog = false })
    if (fieldDialog) PushToFieldDialog(
        if (!fieldConfirmed && sharedField != null) (if (sharedField.circleDiameterDeg != null) "circle" else "camera") else fieldShape,
        if (!fieldConfirmed && sharedField != null) (sharedField.circleDiameterDeg ?: sharedField.widthDeg).toString() else fieldWidth,
        if (!fieldConfirmed && sharedField?.heightDeg != null) sharedField.heightDeg.toString() else fieldHeight,
        onDismiss = { fieldDialog = false }, onConfirm = { shape, width, height ->
            fieldShape = shape; fieldWidth = width; fieldHeight = height; fieldConfirmed = true
            eyepieceFov = (if (shape == "circle") width.toFloat() else minOf(width.toFloat(), height.toFloat()))
            fieldDialog = false
        })
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
                showSequenceActions = false,
                onGuideTo = { obj: CatalogObject ->
                    onTargetChanged(obj)
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

@Composable
internal fun PushToAdaptiveLayout(
    modifier: Modifier = Modifier,
    settingsInMain: Boolean,
    workflow: @Composable () -> Unit,
    main: @Composable (Modifier, Boolean) -> Unit,
    settings: @Composable () -> Unit,
    actions: @Composable () -> Unit
) {
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 520.dp && maxWidth > maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize().testTag("push-layout-wide")) {
                Column(Modifier.weight(0.58f).fillMaxHeight()) {
                    workflow()
                    main(Modifier.weight(1f), false)
                }
                Column(
                    Modifier
                        .weight(0.42f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(bottom = 6.dp)
                ) {
                    settings()
                    actions()
                }
            }
        } else {
            Column(Modifier.fillMaxSize().testTag("push-layout-standard")) {
                workflow()
                main(Modifier.weight(1f), settingsInMain)
                if (!settingsInMain) settings()
                actions()
            }
        }
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
internal fun CaptureSettingsCard(
    cameras: List<PhoneCameraCapability>,
    selectedCamera: PhoneCameraCapability?,
    cameraMenuOpen: Boolean,
    onCameraMenuChange: (Boolean) -> Unit,
    onCameraSelected: (PhoneCameraCapability) -> Unit,
    exposureSeconds: Float,
    onExposureChanged: (Float) -> Unit,
    onContinuousChangeFinished: () -> Unit = {},
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
                val baseLabels = cameras.associateWith { localizedCameraLabel(it) }
                fun labelFor(camera: PhoneCameraCapability): String {
                    val base = baseLabels.getValue(camera)
                    return if (baseLabels.values.count { it == base } > 1) "$base · #${camera.cameraId}" else base
                }
                val lensLabel = stringResource(R.string.phone_camera_lens)
                val selectedLabel = selectedCamera?.let(::labelFor)
                    ?: stringResource(R.string.phone_camera_lens_none)
                Box(Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = selectedLabel,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text(lensLabel) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(cameraMenuOpen) }
                    )
                    Box(
                        Modifier
                            .matchParentSize()
                            .testTag("push-camera-menu-anchor")
                            .semantics { contentDescription = "$lensLabel: $selectedLabel" }
                            .clickable(role = Role.Button) { onCameraMenuChange(!cameraMenuOpen) }
                    )
                    DropdownMenu(
                        expanded = cameraMenuOpen,
                        onDismissRequest = { onCameraMenuChange(false) },
                        properties = PopupProperties(
                            focusable = true,
                            dismissOnBackPress = true,
                            dismissOnClickOutside = true
                        )
                    ) {
                        cameras.forEach { camera ->
                            DropdownMenuItem(
                                text = { Text(labelFor(camera)) },
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
                    onValueChangeFinished = onContinuousChangeFinished,
                    valueRange = 0f..1f,
                    modifier = Modifier.height(32.dp).testTag("push-exposure-slider")
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
                        onValueChangeFinished = onContinuousChangeFinished,
                        valueRange = isoMin..isoMax,
                        modifier = Modifier.height(32.dp).testTag("push-iso-slider")
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
    val focal = camera.equivalentFocalLengthMm?.let { value ->
        val formatted = if (value % 1f == 0f) value.toInt().toString()
            else String.format(java.util.Locale.getDefault(), "%.1f", value)
        " · $formatted mm"
    }.orEmpty()
    val raw = if (camera.supportsRaw) " · RAW" else ""
    return role + focal + raw
}
