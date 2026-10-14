package com.indigo.mobileobservatory.ui.screens

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.*
import com.indigo.mobileobservatory.camera.ConnectionState
import com.indigo.mobileobservatory.pointing.*
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.*
import java.time.Instant
import kotlin.math.abs

/** Snapshot push-to: USB delivery brackets and continuous optical motion, never an unfixed IMU. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuxiliaryPushToScreen(viewModel: CameraViewModel, target: SkyDirection, targetName: String,
    onBack: () -> Unit, onCalibration: () -> Unit,
    opticsConfiguration: PointingOpticsConfiguration? = null,
    onPhoneGuide: (() -> Unit)? = null) {
    require(target.epoch == SkyEpoch.J2000)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repository = viewModel.pointingRepository
    val relationships by repository.relationships.collectAsStateWithPageLifecycle()
    val connection by viewModel.guideConnectionState.collectAsStateWithPageLifecycle()
    val guiding by viewModel.guideRunning.collectAsStateWithPageLifecycle()
    val guideCalibration by viewModel.guideCalibrating.collectAsStateWithPageLifecycle()
    val referenceReady by viewModel.guideReferenceReady.collectAsStateWithPageLifecycle()
    val settingBusy by viewModel.guideGainWriteInProgress.collectAsStateWithPageLifecycle()
    val exposureUs by viewModel.guideExposureUs.collectAsStateWithPageLifecycle()
    val motion by viewModel.auxiliaryOpticalMotion.collectAsStateWithPageLifecycle()
    val foreground = rememberPageForeground()
    val primary = remember { OpticalIdentity("telescope", "axis", "primary") }
    val verification = remember { PushToVerificationSession(VerificationPolicy(maxClockUncertaintyMs = 15_000.0)) }
    var verificationState by remember { mutableStateOf(verification.state) }
    var site by remember { mutableStateOf<ObserverSite?>(null) }
    var trackingSky by rememberSaveable { mutableStateOf(false) }
    var options by rememberSaveable { mutableStateOf(false) }
    var armed by remember { mutableStateOf(false) }
    var solving by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var snapshot by remember { mutableStateOf<AuxiliaryStableReference?>(null) }
    var snapshotEpoch by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempts by remember { mutableStateOf(0) }
    var nowElapsed by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    var nowUtc by remember { mutableStateOf(System.currentTimeMillis()) }
    val cameraId = viewModel.auxiliaryReferenceCameraId()
    val relation = relationships.filter { it.valid && it.source.deviceId == cameraId &&
        it.source.opticalPathId == "secondary" && it.target == primary }.maxByOrNull { it.version }
    val calibrationKey = relation?.let { "${it.id}:${it.version}:${it.source}:${it.sourceParity}:${it.sourceIntrinsicsFingerprint}" }.orEmpty()
    val endpoint = if (opticsConfiguration?.primary?.mode == FovInstrumentMode.EYEPIECE)
        PointingOpticsEndpoint.EYEPIECE else PointingOpticsEndpoint.SENSOR
    val configuredField = opticsConfiguration?.verificationField(PointingOpticsSelection(OpticsTrainId.PRIMARY, endpoint))
    val signature = "$target|$targetName|$cameraId|$trackingSky|$configuredField"

    fun loadSite() { scope.launch {
        try { site = PhoneSiteProvider.currentSite(context); error = null }
        catch (cancel: CancellationException) { throw cancel }
        catch (t: Throwable) { error = t.message }
    } }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { loadSite() }
    LaunchedEffect(Unit) { loadSite() }
    fun cancelCapture() { job?.cancel(); armed = false; verification.requestReference(); verificationState = verification.state }
    fun back() { cancelCapture(); onBack() }
    BackHandler(enabled = foreground, onBack = ::back)
    DisposableEffect(Unit) { onDispose { job?.cancel(); viewModel.setAuxiliaryMotionMonitoring(false) } }
    LaunchedEffect(site, cameraId, trackingSky, foreground) {
        job?.cancel(); snapshotEpoch = null; verification.requestReference()
        val observer = site
        viewModel.setAuxiliaryMotionMonitoring(foreground && observer != null, observer, trackingSky)
        if (!foreground) armed = false
    }
    LaunchedEffect(signature, calibrationKey) {
        job?.cancel(); verification.setTask(signature, calibrationKey); snapshotEpoch = null; attempts = 0
        verificationState = verification.state
    }

    fun fieldFor(estimate: PointingEstimate, observer: ObserverSite): VerificationField {
        val field = configuredField ?: return VerificationField()
        if (field.circleDiameterDeg != null) return field
        val pa = estimate.imageUpPositionAngleDeg
            ?: return VerificationField(circleDiameterDeg = minOf(requireNotNull(field.widthDeg), requireNotNull(field.heightDeg)))
        val pose = SkyPose.fromSolution(estimate.direction, pa)
        val upDirection = (pose.forward + pose.up * .001).unit().direction()
        val instant = Instant.ofEpochMilli(estimate.absoluteUtcMs)
        val center = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(estimate.direction.raDeg,
            estimate.direction.decDeg), instant, observer, null)
        val up = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(upDirection.raDeg, upDirection.decDeg), instant, observer, null)
        val offset = PushToGuidance.fieldOffsets(center.altitudeDeg, center.azimuthDeg, up.altitudeDeg, up.azimuthDeg)
            ?: return VerificationField()
        return field.copy(altitudeUpRollDeg = Math.toDegrees(kotlin.math.atan2(offset.first, offset.second)))
    }

    fun capture(manual: Boolean) {
        val observer = site ?: return
        if (solving || guiding || guideCalibration || settingBusy || !referenceReady || !foreground) return
        val current = viewModel.tickAuxiliaryMotion(SystemClock.elapsedRealtime())
        verification.observeMotion(current.epoch, current.phase, SystemClock.elapsedRealtime())
        if (manual) { attempts = 0; verification.retry(SystemClock.elapsedRealtime()); armed = true }
        val ticket = verification.beginCapture(SystemClock.elapsedRealtime(), allowMovingDebug = manual) ?: return
        attempts++; solving = true; error = null
        val expectedCalibration = calibrationKey
        job = scope.launch {
            try {
                val result = viewModel.captureAuxiliaryStableReference(observer, trackingSky)
                snapshot = result
                val observation = result.observation
                val currentMotion = viewModel.tickAuxiliaryMotion(SystemClock.elapsedRealtime())
                snapshotEpoch = currentMotion.epoch.takeIf { currentMotion.reliable && currentMotion.phase == MotionPhase.STILL &&
                    result.stability.motionUpperBoundDeg <= .02 }
                verification.observeMotion(currentMotion.epoch, currentMotion.phase, SystemClock.elapsedRealtime())
                val currentRelation = repository.relationshipFor(observation, primary)
                val estimate = currentRelation?.estimate(observation, primary)
                if (estimate == null || currentRelation != relation || expectedCalibration != calibrationKey) {
                    verification.fail(ticket, SystemClock.elapsedRealtime(), "calibration_changed")
                    error = context.getString(R.string.wfa_push_calibrate); armed = false
                } else {
                    val instant = Instant.ofEpochMilli(observation.stamp.utcMidpointMs)
                    val axis = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(estimate.direction.raDeg,
                        estimate.direction.decDeg), instant, observer, null)
                    val goal = CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(target.raDeg, target.decDeg), instant, observer, null)
                    val timingError = observation.stamp.clockUncertaintyMs * .0042 / 1000
                    val proof = OpticalVerificationEvidence(observation.stamp.elapsedMidpointMs,
                        observation.stamp.elapsedMidpointMs - observation.stamp.clockUncertaintyMs,
                        observation.stamp.elapsedMidpointMs + observation.stamp.clockUncertaintyMs,
                        axis.altitudeDeg, axis.azimuthDeg, goal.altitudeDeg, goal.azimuthDeg,
                        estimate.uncertaintyDeg + result.stability.motionUpperBoundDeg + timingError,
                        expectedCalibration, true, true, result.stability.motionUpperBoundDeg,
                        observation.stamp.clockUncertaintyMs.toDouble(), currentRelation.valid)
                    if (verification.complete(ticket, proof, fieldFor(estimate, observer), SystemClock.elapsedRealtime())) {
                        snapshotEpoch = currentMotion.epoch; attempts = 0; repository.publish(observation, primary)
                    } else error = context.getString(R.string.wfa_push_fresh_required)
                }
            } catch (timeout: TimeoutCancellationException) {
                verification.fail(ticket, SystemClock.elapsedRealtime(), "capture_timeout")
                error = viewModel.auxiliaryCalibrationFailure(timeout)
            } catch (cancel: CancellationException) {
                verification.fail(ticket, SystemClock.elapsedRealtime(), "capture_cancelled"); throw cancel
            } catch (t: Throwable) {
                verification.fail(ticket, SystemClock.elapsedRealtime(), "solve_failed")
                error = viewModel.auxiliaryCalibrationFailure(t)
            } finally { solving = false; verificationState = verification.tick(SystemClock.elapsedRealtime()) }
        }
    }
    val latestCapture by rememberUpdatedState<(Boolean) -> Unit>(::capture)
    LaunchedEffect(foreground, signature, calibrationKey) {
        if (!foreground) return@LaunchedEffect
        while (isActive) {
            nowElapsed = SystemClock.elapsedRealtime(); nowUtc = System.currentTimeMillis()
            val value = viewModel.tickAuxiliaryMotion(nowElapsed)
            verification.observeMotion(value.epoch, value.phase, nowElapsed)
            verificationState = verification.tick(nowElapsed)
            if (armed && !solving && attempts < 5 && relation != null && configuredField != null &&
                value.reliable && value.phase == MotionPhase.STILL && verificationState.needsReference &&
                verificationState.phase != VerificationPhase.RETRY_LIMIT) latestCapture(false)
            delay(200)
        }
    }

    val sourceObservation = snapshot?.observation
    val estimate = sourceObservation?.let { repository.relationshipFor(it, primary)?.estimate(it, primary) }
    val fresh = sourceObservation != null && snapshotEpoch == motion.epoch && motion.reliable && motion.phase == MotionPhase.STILL &&
        nowElapsed - (sourceObservation.stamp.elapsedMidpointMs - sourceObservation.stamp.clockUncertaintyMs) in 0..10_000
    val axisDirection = estimate?.direction ?: sourceObservation?.direction
    val instant = if (trackingSky) Instant.ofEpochMilli(nowUtc) else Instant.ofEpochMilli(sourceObservation?.stamp?.utcMidpointMs ?: nowUtc)
    val horizontal = axisDirection?.let { p -> site?.let { CoordinateTransform.j2000ToTopocentric(
        EquatorialCoordinates(p.raDeg, p.decDeg), instant, it, null) } }
    val targetHorizontal = site?.let { CoordinateTransform.j2000ToTopocentric(EquatorialCoordinates(target.raDeg, target.decDeg),
        Instant.ofEpochMilli(nowUtc), it, null) }
    val visibleProof = verificationState.isVerified && fresh && estimate != null && relation != null &&
        horizontal != null && targetHorizontal != null && site?.let { observer -> fieldFor(estimate, observer).contains(
            horizontal.altitudeDeg, horizontal.azimuthDeg, targetHorizontal.altitudeDeg, targetHorizontal.azimuthDeg,
            estimate.uncertaintyDeg + requireNotNull(snapshot).stability.motionUpperBoundDeg +
                (nowElapsed - requireNotNull(sourceObservation).stamp.elapsedMidpointMs + sourceObservation.stamp.clockUncertaintyMs) * .0042 / 1000,
            alreadyVerified = true) } == true
    val guidance = if (horizontal != null && targetHorizontal != null) PushToGuidance.compute(horizontal.altitudeDeg,
        horizontal.azimuthDeg, targetHorizontal.altitudeDeg, targetHorizontal.azimuthDeg,
        configuredField?.circleDiameterDeg ?: configuredField?.let { minOf(it.widthDeg ?: 1.0, it.heightDeg ?: 1.0) } ?: 1.0) else null
    val nightRed = Color(0xFFFF8A80)
    val color = if (visibleProof) Color(0xFF00E676) else nightRed
    val stateText = when {
        guiding || guideCalibration -> R.string.wfa_stop_guiding
        solving -> R.string.wf_push_verifying
        attempts >= 5 || verificationState.phase == VerificationPhase.RETRY_LIMIT -> R.string.wf_push_retry_limit
        relation == null -> R.string.wfa_push_calibrate
        configuredField == null -> R.string.wf_push_field_unknown
        visibleProof -> R.string.wf_push_verified
        motion.phase == MotionPhase.MOVING -> R.string.wfa_push_moving
        fresh -> R.string.wf_push_estimated
        else -> R.string.wfa_push_fresh_required
    }
    Scaffold(containerColor = Color(0xFF050505), topBar = { TopAppBar(title = { Text(targetName, color = nightRed) },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF050505)), navigationIcon = {
            IconButton(onClick = ::back) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = nightRed) }
        }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().background(Color(0xFF050505)).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.wfa_push_source), color = nightRed)
            Text(stringResource(stateText), color = color, style = MaterialTheme.typography.titleMedium)
            if (guidance != null && fresh) {
                val display = if (visibleProof) guidance.copy(proximity = GuidanceProximity.ON_TARGET)
                    else if (guidance.proximity == GuidanceProximity.ON_TARGET) guidance.copy(proximity = GuidanceProximity.NEAR) else guidance
                SseReticle(display, display.deltaAltDeg, display.deltaAzDeg,
                    configuredField?.circleDiameterDeg ?: 1.0, color, 1f, Modifier.fillMaxWidth().height(240.dp))
                Text(stringResource(if (estimate == null) R.string.wfa_push_aux_axis else R.string.wfa_push_primary_axis), color = nightRed)
                if (!visibleProof) {
                    if (abs(display.deltaAltDeg) > .05) Text(stringResource(if (display.deltaAltDeg > 0)
                        R.string.push_to_raise_tube else R.string.push_to_lower_tube, PushToGuidance.formatDegrees(display.deltaAltDeg)), color = nightRed)
                    if (abs(display.deltaAzDeg) > .05) Text(stringResource(if (display.deltaAzDeg > 0)
                        R.string.push_to_yaw_right else R.string.push_to_yaw_left, PushToGuidance.formatDegrees(display.deltaAzDeg)), color = nightRed)
                }
            }
            if (relation == null) OutlinedButton(onClick = { cancelCapture(); onCalibration() }) { Text(stringResource(R.string.wfa_title)) }
            if (site == null) OutlinedButton(onClick = { permissions.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION)) }) { Text(stringResource(R.string.wfa_site_permission)) }
            if (solving) TextButton(onClick = ::cancelCapture) { Text(stringResource(R.string.wfp_cancel), color = nightRed) }
            else Button(onClick = { capture(true) }, enabled = connection is ConnectionState.Connected && site != null &&
                referenceReady && !settingBusy && !guiding && !guideCalibration && exposureUs in 500_000f..5_000_000f) {
                Text(stringResource(R.string.wfa_push_sample))
            }
            TextButton(onClick = { options = !options }) { Text(stringResource(R.string.wfp_other_options), color = nightRed) }
            if (options) {
                onPhoneGuide?.let { open ->
                    TextButton(onClick = { cancelCapture(); open() }) {
                        Text(stringResource(R.string.wfp_source_phone), color = nightRed)
                    }
                }
                Text(stringResource(R.string.wfa_exposure), color = nightRed)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(.5f, 1f, 2f).forEach { seconds -> FilterChip(
                        kotlin.math.abs(exposureUs / 1_000_000f - seconds) < .01f,
                        { cancelCapture(); viewModel.setGuideExposure(seconds * 1_000_000f) },
                        enabled = !solving && !guiding && !guideCalibration && !settingBusy,
                        label = { Text("${seconds}s") }) }
                }
                Row { Checkbox(trackingSky, { cancelCapture(); trackingSky = it }); Text(stringResource(R.string.wfp_tracking), color = nightRed) }
                Text(stringResource(R.string.wfa_push_policy), color = nightRed, style = MaterialTheme.typography.bodySmall)
            }
            error?.let { Text(it, color = nightRed) }
        }
    }
}
