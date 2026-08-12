package com.indigo.mobileobservatory.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.CoordinateTransform
import com.indigo.mobileobservatory.astro.EquatorialCoordinates
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.pointing.GuidanceCommand
import com.indigo.mobileobservatory.pointing.GuidanceProximity
import com.indigo.mobileobservatory.pointing.PhoneSiteProvider
import com.indigo.mobileobservatory.pointing.PhoneSkyAttitudeSource
import com.indigo.mobileobservatory.pointing.PushToGuidance
import com.indigo.mobileobservatory.pointing.SkyAttitudeFix
import kotlinx.coroutines.launch
import java.time.Instant
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

private val SkyBlack = Color(0xFF050505)
private val ReticleDim = Color(0xFF3A1010)

/**
 * SSE-style push-to: distance-adaptive reticle, big move hints.
 * The phone camera's optical axis and a real target position drive the reticle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PushToScreen(
    onBack: () -> Unit,
    onOpenCalibration: () -> Unit = {},
    onOpenTargets: () -> Unit = {},
    initialTargetName: String? = null,
    targetRaHours: Double = 5.588,
    targetDecDeg: Double = -5.391
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val source = remember { PhoneSkyAttitudeSource(context) }
    var currentFix by remember { mutableStateOf<SkyAttitudeFix?>(null) }
    var site by remember { mutableStateOf<ObserverSite?>(null) }
    var solving by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var eyepieceFov by remember { mutableFloatStateOf(1.5f) }
    var previousProximity by remember { mutableStateOf<GuidanceProximity?>(null) }
    val targetName = initialTargetName ?: "M42 · Orion Nebula"

    fun loadSite() {
        scope.launch {
            try {
                site = PhoneSiteProvider.currentSite(context)
                status = context.getString(R.string.push_to_live_ready)
            } catch (t: Throwable) {
                status = t.message ?: context.getString(R.string.phone_location_failed)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val locationGranted = grants[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            PhoneSiteProvider.hasPermission(context)
        if (locationGranted) loadSite()
        else status = context.getString(R.string.location_permission_required)
    }

    DisposableEffect(source) {
        source.onFix = { currentFix = it }
        if (!source.start()) status = context.getString(R.string.push_to_sensor_unavailable)
        onDispose {
            source.onFix = null
            source.stop()
        }
    }

    LaunchedEffect(Unit) {
        if (PhoneSiteProvider.hasPermission(context)) loadSite()
    }

    val fix = currentFix ?: SkyAttitudeFix(
        altDeg = 0.0,
        azDeg = 0.0,
        timestampMs = System.currentTimeMillis(),
        sourceId = source.id
    )
    val targetHorizontal = site?.let { observer ->
        CoordinateTransform.j2000ToTopocentric(
            EquatorialCoordinates(targetRaHours * 15.0, targetDecDeg),
            Instant.ofEpochMilli(fix.timestampMs),
            observer,
            refraction = null
        )
    }
    val targetAlt = targetHorizontal?.altitudeDeg ?: 0.0
    val targetAz = targetHorizontal?.azimuthDeg ?: 0.0

    val cmd = PushToGuidance.compute(
        currentAltDeg = fix.altDeg,
        currentAzDeg = fix.azDeg,
        targetAltDeg = targetAlt,
        targetAzDeg = targetAz,
        eyepieceFovDeg = eyepieceFov.toDouble(),
        previousProximity = previousProximity
    )
    SideEffect { previousProximity = cmd.proximity }

    val proximityColor = proximityColor(cmd.proximity)
    val animAlt by animateFloatAsState(cmd.deltaAltDeg.toFloat(), label = "dAlt")
    val animAz by animateFloatAsState(cmd.deltaAzDeg.toFloat(), label = "dAz")
    val animSep by animateFloatAsState(cmd.separationDeg.toFloat(), label = "sep")
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by pulse.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.95f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulseA"
    )

    val moveLabel = buildString {
        when {
            cmd.proximity == GuidanceProximity.ON_TARGET ->
                append(stringResource(R.string.push_to_on_target))
            cmd.zenithDegenerate ->
                append(stringResource(R.string.push_to_zenith_warn))
            else -> {
                val up = PushToGuidance.signedHint(cmd.deltaAltDeg, "↑", "↓")
                val side = PushToGuidance.signedHint(cmd.deltaAzDeg, "→", "←")
                append(listOf(up, side).filter { it.isNotBlank() }.joinToString("   "))
            }
        }
    }

    Scaffold(
        containerColor = SkyBlack,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = SkyBlack,
                    titleContentColor = proximityColor,
                    navigationIconContentColor = Color(0xFFFF8A80),
                    actionIconContentColor = Color(0xFFFF8A80)
                ),
                title = {
                    Column {
                        Text(
                            targetName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                        Text(
                            stringResource(
                                R.string.push_to_status_compact,
                                PushToGuidance.formatDegrees(animSep.toDouble()),
                                cmd.proximity.name
                            ),
                            style = MaterialTheme.typography.labelSmall,
                            color = proximityColor.copy(alpha = 0.85f),
                            fontFamily = FontFamily.Monospace
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(onClick = onOpenTargets) {
                        Text(stringResource(R.string.push_to_targets), color = Color(0xFFFF8A80))
                    }
                    TextButton(onClick = onOpenCalibration) {
                        Text(stringResource(R.string.push_to_calibrate), color = Color(0xFFFF8A80))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(SkyBlack)
        ) {
            // Immersive reticle — takes remaining space above controls.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .semantics {
                        contentDescription = moveLabel
                    },
                contentAlignment = Alignment.Center
            ) {
                SseReticle(
                    cmd = cmd,
                    deltaAlt = animAlt.toDouble(),
                    deltaAz = animAz.toDouble(),
                    eyepieceFovDeg = eyepieceFov.toDouble(),
                    color = proximityColor,
                    pulseAlpha = pulseAlpha,
                    modifier = Modifier.fillMaxSize()
                )
            }

            Text(
                moveLabel,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = proximityColor
            )

            if (cmd.zenithDegenerate && cmd.proximity != GuidanceProximity.ON_TARGET) {
                Text(
                    stringResource(R.string.push_to_zenith_hint),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFFFAB91)
                )
            }

            ProximityPills(cmd.proximity, proximityColor)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    when {
                        currentFix == null -> stringResource(R.string.push_to_waiting_sensor)
                        source.plateSolved -> stringResource(R.string.push_to_source_solved)
                        else -> stringResource(R.string.push_to_source_sensor)
                    },
                    color = if (source.plateSolved) Color(0xFF80CBC4) else Color(0xFFFFCC80),
                    style = MaterialTheme.typography.labelSmall
                )
                Text(
                    stringResource(
                        R.string.push_to_live_coordinates,
                        fix.altDeg,
                        fix.azDeg,
                        targetAlt,
                        targetAz
                    ),
                    color = Color(0xFFBCAAA4),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilledTonalButton(
                        enabled = !solving && site != null && currentFix != null,
                        onClick = {
                            val observer = site ?: return@FilledTonalButton
                            val cameraGranted = androidx.core.content.ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.CAMERA
                            ) == PackageManager.PERMISSION_GRANTED
                            if (!cameraGranted) {
                                permissionLauncher.launch(arrayOf(Manifest.permission.CAMERA))
                                status = context.getString(R.string.phone_camera_permission_required)
                                return@FilledTonalButton
                            }
                            solving = true
                            status = context.getString(R.string.push_to_solving)
                            scope.launch {
                                val result = source.captureAndSolve(observer)
                                status = result.message
                                solving = false
                            }
                        }
                    ) {
                        Text(stringResource(if (solving) R.string.solving else R.string.push_to_solve_now))
                    }
                    TextButton(onClick = {
                        permissionLauncher.launch(
                            arrayOf(
                                Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.CAMERA
                            )
                        )
                    }) {
                        Text(stringResource(R.string.push_to_permissions), color = Color(0xFFFF8A80))
                    }
                }
                if (status.isNotBlank()) {
                    Text(
                        status,
                        color = Color(0xFF9E9E9E),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center,
                        maxLines = 2
                    )
                }
                Text(
                    stringResource(R.string.push_to_eyepiece_fov, eyepieceFov),
                    color = Color(0xFFBCAAA4),
                    style = MaterialTheme.typography.labelSmall
                )
                Slider(
                    value = eyepieceFov,
                    onValueChange = { eyepieceFov = it },
                    valueRange = 0.5f..3f,
                    steps = 4,
                    modifier = Modifier.height(28.dp)
                )
            }
        }
    }
}

@Composable
private fun ProximityPills(proximity: GuidanceProximity, color: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        listOf(
            GuidanceProximity.FAR to ">10°",
            GuidanceProximity.MEDIUM to "1–10°",
            GuidanceProximity.NEAR to "FOV",
            GuidanceProximity.ON_TARGET to "OK"
        ).forEach { (band, label) ->
            val selected = proximity == band
            Surface(
                shape = RoundedCornerShape(50),
                color = if (selected) color.copy(alpha = 0.25f) else Color(0xFF1A1A1A),
                border = BorderStroke(
                    1.dp,
                    if (selected) color else Color(0xFF333333)
                )
            ) {
                Text(
                    label,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    color = if (selected) color else Color(0xFF757575),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
private fun SseReticle(
    cmd: GuidanceCommand,
    deltaAlt: Double,
    deltaAz: Double,
    eyepieceFovDeg: Double,
    color: Color,
    pulseAlpha: Float,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val labelPaint = remember {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            setColor(android.graphics.Color.WHITE)
        }
    }
    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val r = min(size.width, size.height) * 0.42f
        labelPaint.textSize = with(density) { 16.sp.toPx() }

        when (cmd.proximity) {
            GuidanceProximity.FAR -> drawFarMode(
                cx, cy, r, deltaAlt, deltaAz, color, labelPaint
            )
            GuidanceProximity.MEDIUM -> drawMediumMode(
                cx, cy, r, deltaAlt, deltaAz, color, labelPaint
            )
            GuidanceProximity.NEAR -> drawNearMode(
                cx, cy, r, deltaAlt, deltaAz, eyepieceFovDeg, color, pulseAlpha
            )
            GuidanceProximity.ON_TARGET -> drawOnTarget(cx, cy, r, color, pulseAlpha)
        }
    }
}

private fun DrawScope.drawFarMode(
    cx: Float,
    cy: Float,
    r: Float,
    dAlt: Double,
    dAz: Double,
    color: Color,
    paint: android.graphics.Paint
) {
    drawCircle(ReticleDim, r, Offset(cx, cy), style = Stroke(3f))
    // Cardinal chevrons — highlight axes that need motion.
    val needUp = dAlt > 0.3
    val needDown = dAlt < -0.3
    val needRight = dAz > 0.3
    val needLeft = dAz < -0.3
    if (needUp) drawChevron(cx, cy - r * 0.72f, 0f, color, r * 0.22f)
    if (needDown) drawChevron(cx, cy + r * 0.72f, 180f, color, r * 0.22f)
    if (needRight) drawChevron(cx + r * 0.72f, cy, 90f, color, r * 0.22f)
    if (needLeft) drawChevron(cx - r * 0.72f, cy, -90f, color, r * 0.22f)

    paint.color = android.graphics.Color.argb(
        255,
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )
    paint.textSize = size.minDimension * 0.07f
    drawContext.canvas.nativeCanvas.drawText(
        PushToGuidance.formatDegrees(hypotCompat(dAlt, dAz)),
        cx,
        cy + paint.textSize / 3f,
        paint
    )
    paint.textSize = size.minDimension * 0.035f
    paint.color = android.graphics.Color.GRAY
    drawContext.canvas.nativeCanvas.drawText("PUSH", cx, cy + r * 0.28f, paint)
}

private fun DrawScope.drawMediumMode(
    cx: Float,
    cy: Float,
    r: Float,
    dAlt: Double,
    dAz: Double,
    color: Color,
    paint: android.graphics.Paint
) {
    drawCircle(color.copy(alpha = 0.12f), r, Offset(cx, cy))
    drawCircle(color, r, Offset(cx, cy), style = Stroke(2.5f))
    // Tick marks every ~2° of the 10° ring.
    for (i in 1..4) {
        val t = i / 5f
        val rr = r * t
        drawCircle(color.copy(alpha = 0.2f), rr, Offset(cx, cy), style = Stroke(1f))
    }
    drawLine(color.copy(alpha = 0.45f), Offset(cx - r, cy), Offset(cx + r, cy), 2f)
    drawLine(color.copy(alpha = 0.45f), Offset(cx, cy - r), Offset(cx, cy + r), 2f)

    val maxSep = 10.0
    val scale = (hypotCompat(dAlt, dAz) / maxSep).toFloat().coerceAtMost(1f)
    val angle = atan2(dAz, dAlt).toFloat()
    val len = scale * r * 0.85f
    val tx = cx + len * sin(angle)
    val ty = cy - len * cos(angle)
    drawLine(color, Offset(cx, cy), Offset(tx, ty), strokeWidth = 5f, cap = StrokeCap.Round)
    drawCircle(color, 10f, Offset(tx, ty))
    drawCircle(Color.White, 4f, Offset(cx, cy))

    paint.color = android.graphics.Color.WHITE
    paint.textSize = size.minDimension * 0.045f
    drawContext.canvas.nativeCanvas.drawText(
        PushToGuidance.formatDegrees(hypotCompat(dAlt, dAz)),
        cx,
        cy + r + paint.textSize * 1.4f,
        paint
    )
}

private fun DrawScope.drawNearMode(
    cx: Float,
    cy: Float,
    r: Float,
    dAlt: Double,
    dAz: Double,
    fovDeg: Double,
    color: Color,
    pulseAlpha: Float
) {
    // Outer = eyepiece FOV. Target offset in FOV fractions.
    drawCircle(color.copy(alpha = 0.08f), r, Offset(cx, cy))
    drawCircle(color, r, Offset(cx, cy), style = Stroke(3f))
    drawCircle(Color.White.copy(alpha = 0.35f), r * 0.08f, Offset(cx, cy), style = Stroke(2f))
    // Crosshair
    val tick = r * 0.12f
    drawLine(Color.White.copy(alpha = 0.5f), Offset(cx - tick, cy), Offset(cx + tick, cy), 2f)
    drawLine(Color.White.copy(alpha = 0.5f), Offset(cx, cy - tick), Offset(cx, cy + tick), 2f)

    val halfFov = (fovDeg / 2.0).coerceAtLeast(0.05)
    val nx = (dAz / halfFov).toFloat().coerceIn(-1.15f, 1.15f)
    val ny = (-dAlt / halfFov).toFloat().coerceIn(-1.15f, 1.15f)
    val tx = cx + nx * r
    val ty = cy + ny * r
    drawCircle(color.copy(alpha = pulseAlpha), 16f, Offset(tx, ty))
    drawCircle(Color.White, 5f, Offset(tx, ty))
    // Trail line from center to target
    drawLine(color.copy(alpha = 0.5f), Offset(cx, cy), Offset(tx, ty), 2f)
}

private fun DrawScope.drawOnTarget(
    cx: Float,
    cy: Float,
    r: Float,
    color: Color,
    pulseAlpha: Float
) {
    drawCircle(color.copy(alpha = pulseAlpha * 0.25f), r * 0.9f, Offset(cx, cy))
    drawCircle(color, r * 0.55f, Offset(cx, cy), style = Stroke(4f))
    drawCircle(color, r * 0.28f, Offset(cx, cy), style = Stroke(3f))
    drawCircle(color, 14f, Offset(cx, cy))
    drawCircle(Color.White, 5f, Offset(cx, cy))
}

private fun DrawScope.drawChevron(
    x: Float,
    y: Float,
    rotationDeg: Float,
    color: Color,
    size: Float
) {
    rotate(rotationDeg, Offset(x, y)) {
        val path = Path().apply {
            moveTo(x, y - size)
            lineTo(x - size * 0.7f, y + size * 0.45f)
            lineTo(x, y + size * 0.1f)
            lineTo(x + size * 0.7f, y + size * 0.45f)
            close()
        }
        drawPath(path, color)
        drawPath(path, Color.White.copy(alpha = 0.25f), style = Stroke(2f))
    }
}

private fun proximityColor(p: GuidanceProximity): Color = when (p) {
    GuidanceProximity.FAR -> Color(0xFFFF5252)
    GuidanceProximity.MEDIUM -> Color(0xFFFFD740)
    GuidanceProximity.NEAR -> Color(0xFF69F0AE)
    GuidanceProximity.ON_TARGET -> Color(0xFF00E676)
}

private fun hypotCompat(a: Double, b: Double): Double =
    kotlin.math.hypot(a, b)
