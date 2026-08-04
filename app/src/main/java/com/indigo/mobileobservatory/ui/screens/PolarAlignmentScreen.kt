package com.indigo.mobileobservatory.ui.screens

import com.indigo.mobileobservatory.R

import androidx.compose.ui.res.stringResource

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.indigo.mobileobservatory.astrometry.AstapRunner
import com.indigo.mobileobservatory.astrometry.D50Manager
import com.indigo.mobileobservatory.astrometry.FitsHeaderReader
import com.indigo.mobileobservatory.astrometry.PlateSolveResult
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.astro.RefractionParameters
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.mount.MountSite
import com.indigo.mobileobservatory.polar.ContinuousPolarErrorEstimator
import com.indigo.mobileobservatory.polar.CorrectionFieldInfo
import com.indigo.mobileobservatory.polar.PolarAlignmentCalculator
import com.indigo.mobileobservatory.polar.PolarAlignmentResult
import com.indigo.mobileobservatory.polar.PolarErrorDetermination
import com.indigo.mobileobservatory.polar.PolarSolvePoint
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

@Composable
fun PolarAlignmentScreen(
    mountCoordinates: MountCoordinates? = null,
    mountSite: MountSite? = null,
    mountBusy: Boolean = false,
    mountMoveStatus: String = "",
    onReadMountSite: () -> Unit = {},
    onSyncPhoneSiteToMount: (Double, Double) -> Unit = { _, _ -> },
    onMoveMountRaBy: (Double, Boolean, Double) -> Unit = { _, _, _ -> },
    onStopMountRaMove: () -> Unit = {},
    onCaptureFits: suspend () -> File? = { null },
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phoneLocationFailed = stringResource(R.string.phone_location_failed)
    val plateSolveFailed = stringResource(R.string.plate_solve_failed)
    val tppaFailed = stringResource(R.string.tppa_failed)
    val noCameraFrame = stringResource(R.string.no_camera_frame)
    val threeSolvesRequired = stringResource(R.string.three_solves_required)
    val runner = remember { AstapRunner(context.applicationContext) }
    val d50Manager = remember { D50Manager(context.applicationContext) }
    val prefs = remember { context.applicationContext.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE) }

    // The workflow runs inside a coroutine, so it needs the freshest mount readings, not the ones
    // captured when the coroutine was launched.
    val latestMountCoordinates = rememberUpdatedState(mountCoordinates)
    val latestMountBusy = rememberUpdatedState(mountBusy)

    var latitudeText by remember { mutableStateOf(prefs.getFloat("polar_latitude_deg", 0f).toString()) }
    var longitudeText by remember { mutableStateOf(prefs.getFloat("polar_longitude_deg", 0f).toString()) }
    var elevationText by remember { mutableStateOf(prefs.getFloat("polar_elevation_m", 0f).toString()) }
    var temperatureText by remember { mutableStateOf(prefs.getFloat("polar_temperature_c", 15f).toString()) }
    var pressureText by remember { mutableStateOf(prefs.getFloat("polar_pressure_hpa", 0f).takeIf { it > 0f }?.toString() ?: "") }
    var fovText by remember { mutableStateOf("1.0") }
    var manualMode by remember { mutableStateOf(prefs.getBoolean("polar_manual_mode", false)) }
    var startFromCurrent by remember { mutableStateOf(prefs.getBoolean("polar_start_current", true)) }
    var eastDirection by remember { mutableStateOf(prefs.getBoolean("polar_east_direction", true)) }
    var targetDistanceText by remember { mutableStateOf(prefs.getInt("polar_target_distance_deg", 10).toString()) }
    var moveRateText by remember { mutableStateOf(prefs.getFloat("polar_move_rate_deg_sec", 3f).toString()) }
    var searchRadiusText by remember { mutableStateOf(prefs.getFloat("polar_search_radius_deg", 30f).toString()) }
    var alignmentToleranceText by remember { mutableStateOf(prefs.getFloat("polar_alignment_tolerance_arcmin", 0f).toString()) }
    var refractionAdjustment by remember { mutableStateOf(prefs.getBoolean("polar_refraction_adjustment", true)) }
    var continuousEstimator by remember { mutableStateOf(prefs.getBoolean("polar_continuous_estimator", false)) }
    var selectedSlot by remember { mutableIntStateOf(0) }
    var solvingSlot by remember { mutableStateOf<Int?>(null) }
    var runningAuto by remember { mutableStateOf(false) }
    var workflowStatus by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var determination by remember { mutableStateOf<PolarErrorDetermination?>(null) }
    var currentResult by remember { mutableStateOf<PolarAlignmentResult?>(null) }
    var correctionField by remember { mutableStateOf<CorrectionFieldInfo?>(null) }
    var awaitingManualAdvance by remember { mutableStateOf(false) }
    var manualAdvanceRequested by remember { mutableStateOf(false) }
    var phoneSite by remember { mutableStateOf<MountSite?>(null) }
    val files = remember { mutableStateListOf<File?>(null, null, null) }
    val solves = remember { mutableStateListOf<PlateSolveResult?>(null, null, null) }
    val solvedAt = remember { mutableStateListOf<Instant?>(null, null, null) }

    val manualAdvanceState = rememberUpdatedState(manualAdvanceRequested)

    fun currentSite(): ObserverSite? {
        val lat = latitudeText.toDoubleOrNull() ?: return null
        val lon = longitudeText.toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return ObserverSite(lat, lon, elevationText.toDoubleOrNull() ?: 0.0)
    }

    fun currentRefraction(): RefractionParameters {
        val elevation = elevationText.toDoubleOrNull() ?: 0.0
        return RefractionParameters(
            pressureHPa = pressureText.toDoubleOrNull() ?: standardPressureHPa(elevation),
            temperatureC = temperatureText.toDoubleOrNull() ?: 15.0,
            relativeHumidity = 0.0
        )
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                error = null
                determination = null
                currentResult = null
                val slot = selectedSlot
                files[slot] = withContext(Dispatchers.IO) { runner.copyUriToCache(uri) }
                solves[slot] = null
                solvedAt[slot] = null
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val granted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
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
            error = context.getString(R.string.location_permission_required)
        }
    }

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
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = stringResource(R.string.back))
            }
            Text(stringResource(R.string.three_point_polar_alignment), style = MaterialTheme.typography.titleMedium)
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                        OptionField(stringResource(R.string.estimated_field_height_deg), fovText, Modifier.weight(1f)) {
                            fovText = it.filterCoordinateText()
                        }
                    }
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
                            it.toFloatOrNull()?.let { value -> prefs.edit().putFloat("polar_search_radius_deg", value.coerceIn(30f, 180f)).apply() }
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
                        LabeledSwitch(stringResource(R.string.continuous), continuousEstimator) {
                            continuousEstimator = it
                            prefs.edit().putBoolean("polar_continuous_estimator", it).apply()
                        }
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

            Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.workflow), style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = {
                                val site = currentSite()
                                if (site == null) {
                                    error = context.getString(R.string.invalid_coordinates)
                                    return@Button
                                }
                                if (!d50Manager.status().installed) {
                                    error = context.getString(R.string.database_required)
                                    return@Button
                                }
                                if (!manualMode && mountCoordinates == null) {
                                    error = context.getString(R.string.tppa_requires_mount)
                                    return@Button
                                }
                                val fov = fovText.toDoubleOrNull()?.coerceIn(0.2, 6.0) ?: 1.0
                                val searchRadius = searchRadiusText.toDoubleOrNull()?.coerceIn(30.0, 180.0) ?: 30.0
                                val distance = targetDistanceText.toDoubleOrNull()?.coerceIn(1.0, 90.0) ?: 10.0
                                val rate = moveRateText.toDoubleOrNull()?.coerceIn(0.1, 20.0) ?: 3.0
                                val tolerance = alignmentToleranceText.toDoubleOrNull() ?: 0.0
                                val refraction = currentRefraction()
                                val useContinuous = continuousEstimator
                                val useManual = manualMode
                                val useRefractionAdjustment = refractionAdjustment

                                prefs.edit()
                                    .putFloat("polar_latitude_deg", site.latitudeDeg.toFloat())
                                    .putFloat("polar_longitude_deg", site.longitudeDeg.toFloat())
                                    .apply()

                                scope.launch {
                                    runningAuto = true
                                    error = null
                                    determination = null
                                    currentResult = null
                                    correctionField = null
                                    try {
                                        val points = mutableListOf<PolarSolvePoint>()
                                        val mountDeclinations = mutableListOf<Double>()
                                        var previousMountRaDeg = latestMountCoordinates.value?.raDeg

                                        repeat(3) { index ->
                                            if (index > 0) {
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
                                                    onMoveMountRaBy(distance, eastDirection, rate)
                                                    awaitAutomatedMove(latestMountBusy, distance, rate)
                                                    val travelled = raSeparationDeg(
                                                        previousMountRaDeg,
                                                        latestMountCoordinates.value?.raDeg
                                                    )
                                                    if (travelled != null && travelled - distance < -1.0) {
                                                        error = context.getString(R.string.polar_short_move, travelled, distance)
                                                    }
                                                }
                                                workflowStatus = context.getString(R.string.polar_settling)
                                                delay(SETTLE_MILLIS)
                                            }

                                            previousMountRaDeg = latestMountCoordinates.value?.raDeg
                                            workflowStatus = context.getString(R.string.capturing_point, index + 1)
                                            val file = onCaptureFits() ?: error(noCameraFrame)
                                            val capturedAt = exposureTimeOf(file)
                                            files[index] = file
                                            workflowStatus = context.getString(R.string.solving_point, index + 1)
                                            val solved = runner.solve(file, fov, latestMountCoordinates.value, searchRadius)
                                            solves[index] = solved
                                            solvedAt[index] = capturedAt
                                            if (!solved.success) error(solved.message)
                                            points += PolarSolvePoint(
                                                raDeg = solved.raDeg ?: error(solved.message),
                                                decDeg = solved.decDeg ?: error(solved.message),
                                                solvedAt = capturedAt
                                            )
                                            latestMountCoordinates.value?.let { mountDeclinations += it.decDeg }
                                        }

                                        val solved = PolarAlignmentCalculator.determine(
                                            points = points,
                                            site = site,
                                            refraction = refraction,
                                            correctForRefraction = useRefractionAdjustment,
                                            declinationSpreadArcsec =
                                                PolarAlignmentCalculator.declinationSpreadArcsec(mountDeclinations)
                                        )
                                        determination = solved
                                        currentResult = solved.initial
                                        workflowStatus = context.getString(R.string.tppa_complete)

                                        if (useContinuous) {
                                            runCorrectionPhase(
                                                determination = solved,
                                                toleranceArcMin = tolerance,
                                                capture = {
                                                    workflowStatus = context.getString(R.string.polar_correction_capturing)
                                    val frame = onCaptureFits() ?: error(noCameraFrame)
                                    val at = exposureTimeOf(frame)
                                                    workflowStatus = context.getString(R.string.polar_correction_solving)
                                                    val result = runner.solve(frame, fov, latestMountCoordinates.value, searchRadius)
                                                    if (result.success && result.raDeg != null && result.decDeg != null) {
                                                        PolarSolvePoint(result.raDeg, result.decDeg, at)
                                                    } else {
                                                        null
                                                    }
                                                },
                                                onEstimate = { result, field ->
                                                    currentResult = result
                                                    correctionField = field
                                                },
                                                onUnstable = {
                                                    workflowStatus = context.getString(R.string.polar_estimate_unstable)
                                                },
                                                onWithinTolerance = { totalArcMin ->
                                                    workflowStatus = context.getString(R.string.polar_tolerance_reached, totalArcMin)
                                                }
                                            )
                                        }
                                    } catch (e: Throwable) {
                                        error = e.message ?: tppaFailed
                                        workflowStatus = tppaFailed
                                    } finally {
                                        awaitingManualAdvance = false
                                        runningAuto = false
                                    }
                                }
                            },
                            enabled = !runningAuto && solvingSlot == null
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(stringResource(if (runningAuto) R.string.running else R.string.run_tppa))
                        }
                        if (awaitingManualAdvance) {
                            Button(onClick = { manualAdvanceRequested = true }) {
                                Text(stringResource(R.string.polar_next_point))
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                onStopMountRaMove()
                                runningAuto = false
                                workflowStatus = context.getString(R.string.stopped)
                            },
                            enabled = runningAuto || mountBusy
                        ) { Text(stringResource(if (runningAuto) R.string.polar_finish else R.string.stop)) }
                    }
                    if (workflowStatus.isNotBlank()) {
                        Text(workflowStatus, fontSize = 12.sp, color = MaterialTheme.colorScheme.outline)
                    }
                    Text(
                        if (manualMode) {
                            stringResource(R.string.polar_manual_mode_hint)
                        } else {
                            stringResource(R.string.polar_automatic_mode_hint)
                        },
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }

            (0..2).forEach { index ->
                MeasurementCard(
                    index = index,
                    file = files[index],
                    solve = solves[index],
                    solving = solvingSlot == index,
                    onSelect = {
                        selectedSlot = index
                        picker.launch(arrayOf("image/*", "application/fits", "application/octet-stream", "*/*"))
                    },
                    onSolve = {
                        val file = files[index] ?: return@MeasurementCard
                        if (!d50Manager.status().installed) {
                            error = context.getString(R.string.database_required)
                            return@MeasurementCard
                        }
                        val fov = fovText.toDoubleOrNull()?.coerceIn(0.2, 6.0) ?: 1.0
                        val searchRadius = searchRadiusText.toDoubleOrNull()?.coerceIn(30.0, 180.0) ?: 10.0
                        scope.launch {
                            solvingSlot = index
                            error = null
                            determination = null
                            currentResult = null
                            try {
                                val solved = runner.solve(file, fov, mountCoordinates, searchRadius)
                                solves[index] = solved
                                solvedAt[index] = exposureTimeOf(file)
                                if (!solved.success) {
                                    error = solved.message
                                }
                            } catch (e: Throwable) {
                                error = e.message ?: plateSolveFailed
                            } finally {
                                solvingSlot = null
                            }
                        }
                    }
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
                    val solved = PolarAlignmentCalculator.determine(
                        points = points,
                        site = site,
                        refraction = currentRefraction(),
                        correctForRefraction = refractionAdjustment
                    )
                    determination = solved
                    currentResult = solved.initial
                    correctionField = null
                    error = null
                },
                enabled = !runningAuto && solvingSlot == null && solves.count { it?.success == true } == 3
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.calculate))
            }

            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            determination?.let { solved ->
                PolarAlignmentResultCard(
                    determination = solved,
                    current = currentResult ?: solved.initial,
                    correctionField = correctionField
                )
            }
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
 * The mount module already stops the RA move once the requested distance is reached, so the
 * workflow only has to wait for the busy flag to clear instead of guessing a duration.
 */
private suspend fun awaitAutomatedMove(
    busy: State<Boolean>,
    distanceDeg: Double,
    rateDegPerSec: Double
) {
    withTimeoutOrNull(5_000L) {
        while (!busy.value) delay(100L)
    }
    val timeout = ((distanceDeg / rateDegPerSec) * 3.0 * 1000.0).toLong().coerceIn(20_000L, 300_000L)
    withTimeoutOrNull(timeout) {
        while (busy.value) delay(250L)
    }
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

/**
 * Keeps solving while the observer adjusts the knobs and reports the error that is still left.
 * A single below-tolerance solve is not enough to finish; the reading has to repeat.
 */
private suspend fun runCorrectionPhase(
    determination: PolarErrorDetermination,
    toleranceArcMin: Double,
    capture: suspend () -> PolarSolvePoint?,
    onEstimate: (PolarAlignmentResult, CorrectionFieldInfo) -> Unit,
    onUnstable: () -> Unit,
    onWithinTolerance: (Double) -> Unit
) {
    var seedAzimuth = determination.initial.azimuthErrorDeg
    var seedAltitude = determination.initial.altitudeErrorDeg
    var consecutiveWithinTolerance = 0

    while (true) {
        val frame = capture() ?: continue
        val estimate = ContinuousPolarErrorEstimator.estimate(
            determination,
            frame,
            seedAzimuth,
            seedAltitude
        )
        if (!estimate.success) {
            onUnstable()
            continue
        }

        seedAzimuth = estimate.azimuthErrorDeg
        seedAltitude = estimate.altitudeErrorDeg
        val result = determination.resultForResidual(seedAzimuth, seedAltitude)
        onEstimate(result, determination.correctionFieldInfo(frame))

        if (toleranceArcMin > 0.0 && abs(result.totalErrorArcMin) <= toleranceArcMin) {
            consecutiveWithinTolerance++
            if (consecutiveWithinTolerance >= REQUIRED_TOLERANCE_CONFIRMATIONS) {
                onWithinTolerance(abs(result.totalErrorArcMin))
                return
            }
        } else {
            consecutiveWithinTolerance = 0
        }
    }
}

private const val REQUIRED_TOLERANCE_CONFIRMATIONS = 2

@Composable
private fun MeasurementCard(
    index: Int,
    file: File?,
    solve: PlateSolveResult?,
    solving: Boolean,
    onSelect: () -> Unit,
    onSolve: () -> Unit
) {
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 1.dp) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.point_number, index + 1), style = MaterialTheme.typography.titleSmall)
            Text(file?.name ?: stringResource(R.string.no_image_selected), fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            solve?.let {
                Text(
                    if (it.success) {
                        "RA ${it.raHms}  Dec ${it.decDms}"
                    } else {
                        it.message
                    },
                    color = if (it.success) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                    fontSize = 12.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onSelect, enabled = !solving) {
                    Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.select))
                }
                Button(onClick = onSolve, enabled = file != null && !solving) {
                    if (solving) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (solving) R.string.solving else R.string.solve))
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
