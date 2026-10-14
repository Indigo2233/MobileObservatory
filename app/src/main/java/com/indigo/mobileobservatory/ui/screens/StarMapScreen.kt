package com.indigo.mobileobservatory.ui.screens

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.webkit.WebViewAssetLoader
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.starmap.StarMapPhoneOrientation
import com.indigo.mobileobservatory.astro.FovInstrumentMode
import com.indigo.mobileobservatory.astro.FovSkyAnchor
import com.indigo.mobileobservatory.astro.FovPointingCandidate
import com.indigo.mobileobservatory.astro.FovPointingSources
import com.indigo.mobileobservatory.astro.FovPointingRole
import com.indigo.mobileobservatory.astro.FovPointingReason
import com.indigo.mobileobservatory.astro.MosaicStartCorner
import com.indigo.mobileobservatory.astro.MosaicTraversal
import com.indigo.mobileobservatory.astro.OpticsEquipment
import com.indigo.mobileobservatory.astro.OpticsTrainConfig
import com.indigo.mobileobservatory.astro.OpticsTrainId
import com.indigo.mobileobservatory.astro.AndroidOpticsPrefs
import com.indigo.mobileobservatory.astro.ConnectedOpticsGeometry
import com.indigo.mobileobservatory.astro.PointingOpticsConfiguration
import com.indigo.mobileobservatory.astro.PointingOpticsChange
import com.indigo.mobileobservatory.astro.ObserverSite
import com.indigo.mobileobservatory.pointing.PointingMountCapabilities
import com.indigo.mobileobservatory.astro.StarMapFovOverlay
import com.indigo.mobileobservatory.astro.StarMapMosaicConfig
import com.indigo.mobileobservatory.astro.StarMapMosaicPlanner
import com.indigo.mobileobservatory.astro.StarMapOpticsPrefs
import com.indigo.mobileobservatory.astro.SensorAngleCalibration
import com.indigo.mobileobservatory.sequence.SequenceEditorMode
import com.indigo.mobileobservatory.sequence.SequenceMosaicPanel
import com.indigo.mobileobservatory.sequence.SequenceMosaicPlan
import com.indigo.mobileobservatory.astro.UserOpticsCatalog
import com.indigo.mobileobservatory.astro.resolveTelescopeFl
import com.indigo.mobileobservatory.catalog.AssetDeepSkyCatalog
import com.indigo.mobileobservatory.catalog.CatalogObject
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountMotionState
import com.indigo.mobileobservatory.mount.MountSite
import com.indigo.mobileobservatory.mount.MountTime
import com.indigo.mobileobservatory.mount.MountSlewRate
import com.indigo.mobileobservatory.mount.PrecisionGotoMath
import com.indigo.mobileobservatory.mount.PrecisionGotoPhase
import com.indigo.mobileobservatory.mount.PrecisionGotoProgress
import com.indigo.mobileobservatory.starmap.HipsTileCache
import com.indigo.mobileobservatory.starmap.StarMapSearch
import com.indigo.mobileobservatory.ui.components.StarMapGotoConfirmation
import com.indigo.mobileobservatory.ui.components.StarMapPrecisionGotoConfirmation
import com.indigo.mobileobservatory.ui.components.StarMapSyncConfirmation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

private const val STAR_MAP_TIME_PREF = "star_map_epoch_millis"
private val STAR_MAP_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

private fun formatStarMapTime(epochMillis: Long): String {
    return Instant.ofEpochMilli(epochMillis)
        .atZone(ZoneId.systemDefault())
        .toLocalDateTime()
        .format(STAR_MAP_TIME_FORMATTER)
}

private fun parseStarMapTime(text: String): Long? {
    return try {
        LocalDateTime.parse(text.trim(), STAR_MAP_TIME_FORMATTER)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    } catch (_: DateTimeParseException) {
        null
    }
}

data class StarMapTarget(
    val name: String,
    val raHours: Double,
    val decDegrees: Double,
    val frame: String,
    val positionAngleDeg: Double = 0.0
) {
    fun coordinatesText(): String {
        return "RA %.5f h  Dec %+.4f°  %s".format(
            Locale.US,
            raHours,
            decDegrees,
            frame
        )
    }
}

private val StarMapTargetSaver = listSaver<StarMapTarget?, Any>(
    save = { target ->
        target?.let { listOf(it.name, it.raHours, it.decDegrees, it.frame, it.positionAngleDeg) } ?: emptyList()
    },
    restore = { values ->
        values.takeIf { it.size == 5 }?.let {
            StarMapTarget(it[0] as String, it[1] as Double, it[2] as Double, it[3] as String, it[4] as Double)
        }
    }
)

private val StarMapInteractionSaver = listSaver<StarMapInteractionState, Any>(
    save = { listOf(it.targetCardVisible, it.targetExpanded, it.panel.name) },
    restore = {
        StarMapInteractionState(it[0] as Boolean, it[1] as Boolean, StarMapCornerPanel.valueOf(it[2] as String))
    }
)

private fun StarMapTarget?.hasSameIdentity(other: StarMapTarget?): Boolean =
    this?.name == other?.name && this?.raHours == other?.raHours &&
        this?.decDegrees == other?.decDegrees && this?.frame == other?.frame

private fun normalizePositionAngle(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

private fun positionAngleText(value: Double): String {
    val normalized = normalizePositionAngle(value)
    return if (normalized % 1.0 == 0.0) {
        normalized.toLong().toString()
    } else {
        "%.1f".format(Locale.US, normalized)
    }
}

internal sealed interface StarMapEngineState {
    data object Loading : StarMapEngineState
    data object Ready : StarMapEngineState
    data class Error(val message: String) : StarMapEngineState
}

internal object StarMapLoadRules {
    const val READY_TIMEOUT_MS = 45_000L
    const val OVERLAY_IDLE_MS = 4_000L

    fun acceptReady(current: StarMapEngineState): StarMapEngineState =
        if (current is StarMapEngineState.Loading) StarMapEngineState.Ready else current

    fun acceptFailure(current: StarMapEngineState, message: String): StarMapEngineState =
        if (current is StarMapEngineState.Ready) current else StarMapEngineState.Error(message)

    fun acceptTimeout(current: StarMapEngineState, message: String): StarMapEngineState =
        if (current is StarMapEngineState.Loading) StarMapEngineState.Error(message) else current
}

private fun formatStoredTolerance(value: Float): String {
    val asDouble = value.toDouble()
    return if (kotlin.math.abs(asDouble - asDouble.toLong()) < 1e-6) {
        asDouble.toLong().toString()
    } else {
        asDouble.toString()
    }
}

private class StarMapJavascriptBridge(
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
    private val onSelected: (StarMapTarget?) -> Unit,
    private val onUpdated: (StarMapTarget) -> Unit,
    private val onBlankTap: () -> Unit,
    private val onViewChanged: (Double, Double, Double) -> Unit,
    private val onFollowChanged: (Boolean) -> Unit,
    private val onPhoneFollowChanged: (Boolean) -> Unit,
    private val fallbackTargetName: String,
    private val parseError: (Throwable) -> String
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun onEngineReady(@Suppress("UNUSED_PARAMETER") ignored: String) {
        mainHandler.post { onReady() }
    }

    @JavascriptInterface
    fun onEngineError(message: String) {
        mainHandler.post { onError(message) }
    }

    @JavascriptInterface
    fun onTargetSelected(payload: String) {
        readTarget(payload, selected = true)
    }

    @JavascriptInterface
    fun onTargetUpdated(payload: String) {
        readTarget(payload, selected = false)
    }

    private fun readTarget(payload: String, selected: Boolean) {
        val result = runCatching {
            val json = JSONObject(payload)
            StarMapTarget(
                name = json.optString("name").ifBlank { fallbackTargetName },
                raHours = json.getDouble("raHours").mod(24.0),
                decDegrees = json.getDouble("decDegrees").coerceIn(-90.0, 90.0),
                frame = json.optString("frame", "JNOW")
            )
        }
        mainHandler.post {
            result.onSuccess { if (selected) onSelected(it) else onUpdated(it) }
                .onFailure { onError(parseError(it)) }
        }
    }

    @JavascriptInterface
    fun onSelectionCleared(@Suppress("UNUSED_PARAMETER") ignored: String) {
        // The engine may clear its reticle on a blank tap. The observing target stays selected.
    }

    @JavascriptInterface
    fun onBlankTapped(@Suppress("UNUSED_PARAMETER") ignored: String) {
        mainHandler.post { onBlankTap() }
    }

    @JavascriptInterface
    fun onViewChanged(payload: String) {
        val view = runCatching {
            val json = JSONObject(payload)
            Triple(json.getDouble("raHours"), json.getDouble("decDegrees"), json.getDouble("fovDegrees"))
        }.getOrNull() ?: return
        mainHandler.post { onViewChanged(view.first, view.second, view.third) }
    }

    @JavascriptInterface
    fun onFollowMountChanged(enabled: String) {
        val follow = enabled.equals("true", ignoreCase = true)
        mainHandler.post { onFollowChanged(follow) }
    }

    @JavascriptInterface
    fun onPhoneFollowChanged(enabled: String) {
        mainHandler.post { onPhoneFollowChanged(enabled.equals("true", ignoreCase = true)) }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun StarMapScreen(
    mountCoordinates: MountCoordinates?,
    mountSite: MountSite?,
    mountTime: MountTime? = null,
    observerSite: ObserverSite? = null,
    observerSiteLabel: String? = null,
    onObserverSiteSettings: () -> Unit = {},
    mountConnected: Boolean,
    mountPointingCapabilities: PointingMountCapabilities = PointingMountCapabilities(),
    mountBusy: Boolean,
    mountMotionState: MountMotionState = MountMotionState.Idle,
    mountSlewRate: MountSlewRate = MountSlewRate.DEFAULT,
    precisionGotoProgress: PrecisionGotoProgress = PrecisionGotoProgress(),
    cameraPixelSizeUm: Float? = null,
    cameraFrameWidthPx: Int = 0,
    cameraFrameHeightPx: Int = 0,
    cameraHardwareBinning: Int = 1,
    cameraDeviceId: String = "primary-connected",
    cameraGeometry: ConnectedOpticsGeometry? = null,
    guideCameraGeometry: ConnectedOpticsGeometry? = null,
    opticsPreferencesRevision: Long = 0L,
    pointingUtcNow: () -> Long = System::currentTimeMillis,
    lastSolvedPositionAngleDeg: Double? = null,
    rotatorConnected: Boolean = false,
    rotatorMechanicalAngleDeg: Double = 0.0,
    initialSequencePlan: SequenceMosaicPlan? = null,
    onGoto: (StarMapTarget) -> Unit,
    onSync: (StarMapTarget) -> Unit = {},
    onPrecisionGoto: (StarMapTarget, Double) -> Unit = { _, _ -> },
    onAddToSequence: (SequenceMosaicPlan, SequenceEditorMode) -> Unit = { _, _ -> },
    onMoveRotator: (Double) -> Unit = {},
    showSequenceActions: Boolean = true,
    onTargetSelected: (StarMapTarget) -> Unit = {},
    selectedObservationTarget: StarMapTarget? = null,
    pointingSources: List<FovPointingCandidate> = emptyList(),
    opticalPathIds: Map<OpticsTrainId, String> = emptyMap(),
    onOpticalPathInvalidated: (OpticsTrainId, PointingOpticsChange) -> Unit = { _, _ -> },
    onOpenTargets: () -> Unit = {},
    onOpenPreparation: () -> Unit = {},
    onPushTo: (StarMapTarget) -> Unit = {},
    onSlewRateChange: (MountSlewRate) -> Unit = {},
    onManualMoveStart: (MountDirection) -> Unit = {},
    onManualMoveStop: (MountDirection) -> Unit = {},
    onStopMount: () -> Unit = {},
    onGoHome: () -> Unit = {},
    redNightMode: Boolean = false,
    onRedNightModeChange: (Boolean) -> Unit = {},
    onBack: () -> Unit
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    val foreground = rememberPageForeground()
    LaunchedEffect(webView, foreground) {
        webView?.let { view ->
            if (foreground) {
                view.visibility = android.view.View.VISIBLE
                view.onResume()
            } else {
                view.onPause()
                view.visibility = android.view.View.INVISIBLE
            }
        }
    }
    var webViewSession by remember { mutableIntStateOf(0) }
    var selectedTarget by rememberSaveable(stateSaver = StarMapTargetSaver) {
        mutableStateOf(
            selectedObservationTarget ?: initialSequencePlan?.let {
                StarMapTarget(
                    it.name,
                    it.centerRaHours,
                    it.centerDecDeg,
                    "J2000",
                    it.positionAngleDeg
                )
            }
        )
    }
    var engineState by remember { mutableStateOf<StarMapEngineState>(StarMapEngineState.Loading) }
    var gotoConfirmation by remember { mutableStateOf<StarMapTarget?>(null) }
    var syncConfirmation by remember { mutableStateOf<StarMapTarget?>(null) }
    var precisionConfirmation by remember { mutableStateOf<StarMapTarget?>(null) }
    var atmosphereVisible by remember { mutableStateOf(false) }
    var overlaysVisible by rememberSaveable { mutableStateOf(true) }
    var overlaysLocked by rememberSaveable { mutableStateOf(false) }
    var hudPointerActive by remember { mutableStateOf(false) }
    var hudActivityRevision by remember { mutableIntStateOf(0) }
    LaunchedEffect(foreground) { if (!foreground) hudPointerActive = false }
    var interaction by rememberSaveable(stateSaver = StarMapInteractionSaver) {
        mutableStateOf(StarMapInteractionState(targetCardVisible = selectedTarget != null))
    }
    val targetExpanded = interaction.targetExpanded
    val cornerPanel = interaction.panel
    var savedCenterRaHours by rememberSaveable { mutableStateOf<Double?>(null) }
    var savedCenterDecDegrees by rememberSaveable { mutableStateOf<Double?>(null) }
    var savedCenterFrame by rememberSaveable { mutableStateOf("JNOW") }
    var savedFovDegrees by rememberSaveable { mutableStateOf<Double?>(null) }
    var acceptViewUpdates by remember(webViewSession) { mutableStateOf(false) }
    var importAngleText by rememberSaveable {
        mutableStateOf(positionAngleText(selectedTarget?.positionAngleDeg ?: 0.0))
    }
    var sequenceImportPlan by remember { mutableStateOf<SequenceMosaicPlan?>(null) }
    var searchDialogVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var confirmHome by remember { mutableStateOf(false) }
    var fovDialogVisible by remember { mutableStateOf(false) }
    var starMapTimeDialogVisible by remember { mutableStateOf(false) }
    var starMapTimeText by remember { mutableStateOf("") }
    var starMapTimeError by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE)
    }
    var landscapeVisible by remember {
        mutableStateOf(prefs.getBoolean("star_map_simulated_landscape", true))
    }
    var followPhone by remember {
        mutableStateOf(prefs.getBoolean("star_map_follow_phone", false))
    }
    val phoneOrientation = remember(context) { StarMapPhoneOrientation(context) }
    var phoneFollowRegistrationFailed by remember { mutableStateOf(false) }
    var phoneFollowHasSample by remember { mutableStateOf(false) }
    var sensorAngleCalibration by remember {
        mutableStateOf(
            SensorAngleCalibration(
                offsetDeg = prefs.getFloat(SensorAngleCalibration.OFFSET_PREF, 0f).toDouble(),
                reversed = prefs.getBoolean(SensorAngleCalibration.REVERSED_PREF, false)
            )
        )
    }
    val hipsCache = remember { HipsTileCache.create(context.applicationContext) }
    val catalog = remember { AssetDeepSkyCatalog(context.applicationContext) }
    var onlineDssEnabled by remember {
        mutableStateOf(prefs.getBoolean(HipsTileCache.PREFS_ONLINE_DSS, false))
    }
    var hipsCacheSizeLabel by remember {
        mutableStateOf(HipsTileCache.formatCacheSize(hipsCache.cacheSizeBytes()))
    }
    var followMount by remember {
        mutableStateOf(prefs.getBoolean("star_map_follow_mount", false) && !followPhone)
    }
    var followInterrupted by rememberSaveable { mutableStateOf(false) }
    val resolvedObserverSite = observerSite ?: mountSite?.let { ObserverSite(it.latitudeDeg, it.longitudeDeg) }
    val latestObserverSite by rememberUpdatedState(resolvedObserverSite)
    var starMapTimeManual by remember {
        mutableStateOf(prefs.contains(STAR_MAP_TIME_PREF))
    }
    var starMapEpochMillis by remember {
        mutableStateOf(
            prefs.getLong(STAR_MAP_TIME_PREF, 0L).takeIf { it > 0L }
                ?: System.currentTimeMillis()
        )
    }
    var equatorialGrid by remember {
        mutableStateOf(prefs.getBoolean("star_map_equatorial_grid", false))
    }
    var azimuthalGrid by remember {
        mutableStateOf(prefs.getBoolean("star_map_azimuthal_grid", false))
    }
    var meridianLine by remember {
        mutableStateOf(prefs.getBoolean("star_map_meridian", false))
    }
    var eclipticLine by remember {
        mutableStateOf(prefs.getBoolean("star_map_ecliptic", false))
    }
    var constellationLines by remember {
        mutableStateOf(prefs.getBoolean("star_map_constellation_lines", false))
    }
    var constellationLabels by remember {
        mutableStateOf(prefs.getBoolean("star_map_constellation_labels", false))
    }
    var constellationBounds by remember {
        mutableStateOf(prefs.getBoolean("star_map_constellation_bounds", false))
    }
    var starHints by remember {
        mutableStateOf(prefs.getBoolean("star_map_star_labels", false))
    }
    var precisionToleranceText by remember {
        mutableStateOf(
            formatStoredTolerance(
                prefs.getFloat(
                    PrecisionGotoMath.PREFS_TOLERANCE_ARCMIN,
                    PrecisionGotoMath.TOLERANCE_ARCMIN.toFloat()
                )
            )
        )
    }
    val moveEnabled = mountConnected && mountPointingCapabilities.manualMove && !mountBusy
    val canGoto = mountConnected && mountPointingCapabilities.goto && !mountBusy
    val canSync = mountConnected && mountPointingCapabilities.sync && !mountBusy
    val canHome = mountConnected && mountPointingCapabilities.home && !mountBusy
    val canReadCoordinates = mountConnected && mountPointingCapabilities.readCoordinates
    LaunchedEffect(mountConnected, mountPointingCapabilities) {
        if (!mountConnected || !mountPointingCapabilities.goto) {
            gotoConfirmation = null
            precisionConfirmation = null
        }
        if (!mountConnected || !mountPointingCapabilities.sync) syncConfirmation = null
        if (!mountConnected || !mountPointingCapabilities.home) confirmHome = false
    }
    val showGlobalStop = mountMotionState.showsGlobalStop

    val connectedSensorName = stringResource(R.string.connected_camera_sensor)
    val primaryGeometry = remember(
        cameraGeometry,
        cameraPixelSizeUm,
        cameraFrameWidthPx,
        cameraFrameHeightPx,
        cameraHardwareBinning,
        cameraDeviceId
    ) {
        cameraGeometry ?: cameraPixelSizeUm?.let { ConnectedOpticsGeometry(it, cameraFrameWidthPx,
            cameraFrameHeightPx, cameraHardwareBinning, cameraDeviceId) }
    }
    val connectedGuideSensor = guideCameraGeometry?.sensor(connectedSensorName)
    var telescopes by remember {
        mutableStateOf(
            UserOpticsCatalog.loadTelescopes(
                prefs.getString(UserOpticsCatalog.TELESCOPES_KEY, null)
            )
        )
    }
    var eyepieces by remember {
        mutableStateOf(
            UserOpticsCatalog.loadEyepieces(
                prefs.getString(UserOpticsCatalog.EYEPIECES_KEY, null)
            )
        )
    }
    var userCameras by remember {
        mutableStateOf(
            UserOpticsCatalog.loadCameras(
                prefs.getString(UserOpticsCatalog.CAMERAS_KEY, null)
            )
        )
    }
    val opticsPrefs = remember { AndroidOpticsPrefs(prefs) }
    var primaryTrain by remember {
        mutableStateOf(StarMapOpticsPrefs.loadPrimary(opticsPrefs))
    }
    var secondaryTrain by remember {
        mutableStateOf(
            StarMapOpticsPrefs.loadSecondary(
                opticsPrefs,
                hasConnectedCamera = connectedGuideSensor != null
            )
        )
    }
    var activeTrain by remember {
        mutableStateOf(StarMapOpticsPrefs.loadActive(opticsPrefs))
    }
    var editingTrain by remember { mutableStateOf(activeTrain) }
    var confirmedTrains by remember {
        mutableStateOf(OpticsTrainId.entries.filter {
            prefs.getBoolean(PointingOpticsConfiguration.confirmationKey(it), false)
        }.toSet())
    }
    val opticsConfiguration = remember(primaryTrain, secondaryTrain, telescopes, eyepieces,
        userCameras, primaryGeometry, guideCameraGeometry, confirmedTrains, connectedSensorName) {
        PointingOpticsConfiguration(primaryTrain, secondaryTrain, telescopes, eyepieces, userCameras,
            primaryGeometry, guideCameraGeometry, confirmedTrains, connectedSensorName)
    }
    val primarySensors = remember(opticsConfiguration) { opticsConfiguration.sensorsFor(OpticsTrainId.PRIMARY) }
    val secondarySensors = remember(opticsConfiguration) { opticsConfiguration.sensorsFor(OpticsTrainId.SECONDARY) }
    // Detect device swaps and library edits only for the affected train. Initial composition is not a swap.
    var previousOptics by remember { mutableStateOf(opticsConfiguration) }
    LaunchedEffect(opticsConfiguration) {
        OpticsTrainId.entries.forEach { id ->
            previousOptics.changeTo(opticsConfiguration, id)?.let { onOpticalPathInvalidated(id, it) }
        }
        previousOptics = opticsConfiguration
    }
    var showFovOverlay by remember {
        mutableStateOf(prefs.getBoolean(StarMapOpticsPrefs.SHOW_OVERLAY, true))
    }
    LaunchedEffect(opticsPreferencesRevision) {
        // Other tasks may confirm or edit this shared setup while the map stays composed underneath.
        val stored = PointingOpticsConfiguration.load(opticsPrefs, primaryGeometry, guideCameraGeometry, connectedSensorName)
        primaryTrain = stored.primary
        secondaryTrain = stored.secondary
        telescopes = stored.telescopes
        eyepieces = stored.eyepieces
        userCameras = stored.cameras
        confirmedTrains = stored.confirmedTrains
        activeTrain = StarMapOpticsPrefs.loadActive(opticsPrefs)
        showFovOverlay = prefs.getBoolean(StarMapOpticsPrefs.SHOW_OVERLAY, true)
    }
    var mosaicConfig by remember {
        mutableStateOf(
            StarMapMosaicConfig(
                rows = initialSequencePlan?.rows
                    ?: prefs.getInt(StarMapMosaicConfig.ROWS_PREF, 1),
                columns = initialSequencePlan?.columns
                    ?: prefs.getInt(StarMapMosaicConfig.COLUMNS_PREF, 1),
                overlapPercent = initialSequencePlan?.overlapPercent
                    ?: prefs.getInt(StarMapMosaicConfig.OVERLAP_PREF, 10),
                showPanelNumbers = prefs.getBoolean(
                    StarMapMosaicConfig.SHOW_NUMBERS_PREF,
                    true
                ),
                traversal = MosaicTraversal.fromPref(
                    initialSequencePlan?.traversal
                        ?: prefs.getString(StarMapMosaicConfig.TRAVERSAL_PREF, null)
                ),
                startCorner = MosaicStartCorner.fromPref(
                    initialSequencePlan?.startCorner
                        ?: prefs.getString(StarMapMosaicConfig.START_CORNER_PREF, null)
                )
            ).normalized()
        )
    }
    LaunchedEffect(connectedGuideSensor) {
        if (!prefs.contains("star_map_secondary_fov_mode")) {
            secondaryTrain = StarMapOpticsPrefs.defaultSecondary(
                hasConnectedCamera = connectedGuideSensor != null,
                plateFocalLengthMm = prefs.getFloat("plate_focal_length_mm", 0f).takeIf { it > 0f }
            )
        }
    }
    LaunchedEffect(
        primarySensors,
        secondarySensors,
        telescopes,
        eyepieces,
        primaryTrain.sensorId,
        primaryTrain.telescopeId,
        primaryTrain.eyepieceId,
        primaryTrain.mode,
        secondaryTrain.sensorId,
        secondaryTrain.telescopeId,
        secondaryTrain.eyepieceId,
        secondaryTrain.mode
    ) {
        fun fallback(train: OpticsTrainConfig): OpticsTrainConfig {
            val sensors = if (train.id == OpticsTrainId.PRIMARY) primarySensors else secondarySensors
            var next = train
            if (telescopes.none { it.id == next.telescopeId }) {
                val spec = telescopes.firstOrNull() ?: return next
                next = next.copy(
                    telescopeId = spec.id,
                    customTelescopeFl = spec.focalLengthMm.toString().removeSuffix(".0")
                )
            }
            if (eyepieces.none { it.id == next.eyepieceId }) {
                val spec = eyepieces.firstOrNull() ?: return next
                next = next.copy(
                    eyepieceId = spec.id,
                    customEyepieceFl = spec.focalLengthMm.toString().removeSuffix(".0"),
                    customEyepieceAfov = spec.apparentFovDeg.toString().removeSuffix(".0")
                )
            }
            if (
                next.mode == FovInstrumentMode.SENSOR &&
                next.sensorId != OpticsEquipment.CONNECTED_SENSOR_ID &&
                sensors.none { it.id == next.sensorId }
            ) {
                next = next.copy(
                    sensorId = sensors.firstOrNull()?.id
                        ?: OpticsEquipment.defaultSensors.first().id
                )
            }
            return next
        }
        val nextPrimary = fallback(primaryTrain)
        val nextSecondary = fallback(secondaryTrain)
        fun clearConfirmation(id: OpticsTrainId) {
            confirmedTrains = confirmedTrains - id
            prefs.edit().putBoolean(PointingOpticsConfiguration.confirmationKey(id), false).apply()
        }
        if (nextPrimary != primaryTrain) { primaryTrain = nextPrimary; clearConfirmation(OpticsTrainId.PRIMARY) }
        if (nextSecondary != secondaryTrain) { secondaryTrain = nextSecondary; clearConfirmation(OpticsTrainId.SECONDARY) }
    }

    val activeConfig = if (activeTrain == OpticsTrainId.PRIMARY) primaryTrain else secondaryTrain
    val editingConfig = if (editingTrain == OpticsTrainId.PRIMARY) primaryTrain else secondaryTrain
    val activeSensors = if (activeTrain == OpticsTrainId.PRIMARY) primarySensors else secondarySensors
    val editingSensors = if (editingTrain == OpticsTrainId.PRIMARY) primarySensors else secondarySensors
    val activeComputation = remember(opticsConfiguration, activeTrain) {
        opticsConfiguration.computation(activeTrain)
    }
    val currentFrameCentered = opticsConfiguration.currentFrameCentered(activeTrain)
    val latestPointingClock by rememberUpdatedState(pointingUtcNow)
    var pointingClockUtcMs by remember { mutableStateOf(pointingUtcNow()) }
    LaunchedEffect(Unit) {
        while (true) { delay(1_000); pointingClockUtcMs = latestPointingClock() }
    }
    val currentFovSource = remember(pointingSources, opticalPathIds, activeTrain, activeComputation, pointingClockUtcMs) {
        val limit = activeComputation?.let { computation ->
            computation.circleDeg?.div(2) ?: listOfNotNull(computation.rectWidthDeg, computation.rectHeightDeg).minOrNull()?.div(2)
        } ?: Double.POSITIVE_INFINITY
        FovPointingSources.select(opticalPathIds[activeTrain] ?: activeTrain.prefValue(), pointingSources, pointingClockUtcMs,
            requireRoll = activeComputation?.mode == FovInstrumentMode.SENSOR, maxUncertaintyDeg = limit)
    }
    val currentFollowSource = remember(pointingSources, opticalPathIds, activeTrain, activeComputation, pointingClockUtcMs) {
        val limit = activeComputation?.let { computation ->
            computation.circleDeg?.div(2) ?: listOfNotNull(computation.rectWidthDeg, computation.rectHeightDeg).minOrNull()?.div(2)
        } ?: Double.POSITIVE_INFINITY
        // An axis can be followed without claiming an image-up angle for a rectangular FOV.
        FovPointingSources.select(opticalPathIds[activeTrain] ?: activeTrain.prefValue(), pointingSources,
            pointingClockUtcMs, requireRoll = false, maxUncertaintyDeg = limit)
    }
    val latestFollowSource by rememberUpdatedState(currentFollowSource)
    LaunchedEffect(currentFollowSource.showCurrent, followMount, resolvedObserverSite) {
        if (followMount && (!currentFollowSource.showCurrent || resolvedObserverSite == null)) {
            followInterrupted = true
            followMount = false
            prefs.edit().putBoolean("star_map_follow_mount", false).apply()
            webView?.evaluateJavascript("window.MercStarMap && window.MercStarMap.setFollowMount(false);", null)
        }
    }
    val editingComputation = remember(opticsConfiguration, editingTrain) {
        opticsConfiguration.computation(editingTrain)
    }
    val defaultPrimaryLabel = stringResource(R.string.star_map_train_primary)
    val defaultSecondaryLabel = stringResource(R.string.star_map_train_secondary)
    val overlayTrainLabel = remember(
        activeConfig,
        telescopes,
        eyepieces,
        activeSensors,
        defaultPrimaryLabel,
        defaultSecondaryLabel,
        activeTrain
    ) {
        UserOpticsCatalog.combinationName(activeConfig, telescopes, eyepieces, activeSensors)
            .ifEmpty {
                if (activeTrain == OpticsTrainId.PRIMARY) defaultPrimaryLabel else defaultSecondaryLabel
            }
    }
    val fovCurrentWord = stringResource(if (!currentFrameCentered && activeComputation?.mode == FovInstrumentMode.SENSOR)
        R.string.wf_fov_cropped_frame else when (currentFovSource.role) {
        FovPointingRole.OPTICAL -> R.string.wf_fov_optical
        FovPointingRole.CALIBRATED -> R.string.wf_fov_calibrated
        FovPointingRole.ESTIMATED -> R.string.wf_fov_estimated
        FovPointingRole.PLANNING -> R.string.wf_fov_planning
    })
    val sourceStatusWord = currentFollowSource.source?.let { source -> stringResource(when {
        source.kind == com.indigo.mobileobservatory.astro.FovPointingKind.DIRECT_OPTICAL -> R.string.wf_follow_source_camera
        source.sourceId.contains("PHONE", ignoreCase = true) -> R.string.wf_follow_source_phone
        source.sourceId.contains("AUX", ignoreCase = true) -> R.string.wf_follow_source_aux
        else -> when (currentFollowSource.role) {
            FovPointingRole.OPTICAL -> R.string.wf_fov_optical
            FovPointingRole.CALIBRATED -> R.string.wf_fov_calibrated
            else -> R.string.wf_fov_estimated
        }
    }) }
    val propagationCaption = if (currentFollowSource.role == FovPointingRole.ESTIMATED)
        " · ${stringResource(R.string.wf_fov_estimated)}" else ""
    val missingRollCaption = if (currentFovSource.reason == FovPointingReason.ROLL_UNKNOWN)
        " · ${stringResource(R.string.wf_fov_roll_unknown)}" else ""
    val croppedFrameCaption = if (activeComputation?.mode == FovInstrumentMode.SENSOR &&
        currentFollowSource.showCurrent && !currentFrameCentered) " · ${stringResource(R.string.wf_fov_cropped_frame)}" else ""
    val pointingSourceCaption = if (currentFollowSource.source != null) stringResource(R.string.wf_follow_source_age,
        if (activeTrain == OpticsTrainId.PRIMARY) defaultPrimaryLabel else defaultSecondaryLabel,
        sourceStatusWord.orEmpty() + propagationCaption,
        ((pointingClockUtcMs - currentFollowSource.source.absoluteUtcMs).coerceAtLeast(0) / 1000)) +
        missingRollCaption + croppedFrameCaption
        else stringResource(when (currentFollowSource.reason) {
            FovPointingReason.STALE -> R.string.wf_fov_stale
            FovPointingReason.CONFLICT -> R.string.wf_fov_conflict
            FovPointingReason.ROLL_UNKNOWN -> R.string.wf_fov_roll_unknown
            else -> R.string.wf_fov_planning
        })
    val fovTargetWord = stringResource(R.string.star_map_fov_target)
    val fovTargetRole = if (selectedTarget == null) stringResource(R.string.wf_fov_planning) else fovTargetWord
    val mosaicOverlaySummary = stringResource(
        R.string.fov_mosaic_overlay_summary,
        mosaicConfig.rows,
        mosaicConfig.columns,
        mosaicConfig.overlapPercent
    )
    val overlayCurrentLabel = remember(overlayTrainLabel, fovCurrentWord, activeComputation) {
        StarMapFovOverlay.overlayCaption(overlayTrainLabel, fovCurrentWord, activeComputation)
    }
    val overlayTargetLabel = remember(
        overlayTrainLabel,
        fovTargetRole,
        activeComputation,
        mosaicConfig,
        mosaicOverlaySummary
    ) {
        StarMapFovOverlay.overlayCaption(overlayTrainLabel, fovTargetRole, activeComputation) +
            if (activeComputation?.mode == FovInstrumentMode.SENSOR &&
                mosaicConfig.panelCount > 1
            ) {
                " · $mosaicOverlaySummary"
            } else {
                ""
            }
    }

    fun evalStarMap(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    fun openStarMapTimeDialog() {
        starMapTimeText = formatStarMapTime(starMapEpochMillis)
        starMapTimeError = false
        starMapTimeDialogVisible = true
        overlaysVisible = true
    }

    fun applyStarMapTime(epochMillis: Long, manual: Boolean) {
        starMapEpochMillis = epochMillis
        starMapTimeManual = manual
        if (manual) {
            prefs.edit().putLong(STAR_MAP_TIME_PREF, epochMillis).apply()
        } else {
            prefs.edit().remove(STAR_MAP_TIME_PREF).apply()
        }
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.setTime($epochMillis);"
        )
    }

    fun persistFovPrefs() {
        val optics = StarMapOpticsPrefs.snapshot(
            primaryTrain,
            secondaryTrain,
            activeTrain,
            showFovOverlay
        )
        val editor = prefs.edit()
            .putBoolean("star_map_follow_mount", followMount)
            .putBoolean(HipsTileCache.PREFS_ONLINE_DSS, onlineDssEnabled)
        optics.strings.forEach { (key, value) -> editor.putString(key, value) }
        optics.bools.forEach { (key, value) -> editor.putBoolean(key, value) }
        editor
            .putInt(StarMapMosaicConfig.ROWS_PREF, mosaicConfig.rows)
            .putInt(StarMapMosaicConfig.COLUMNS_PREF, mosaicConfig.columns)
            .putInt(StarMapMosaicConfig.OVERLAP_PREF, mosaicConfig.overlapPercent)
            .putBoolean(
                StarMapMosaicConfig.SHOW_NUMBERS_PREF,
                mosaicConfig.showPanelNumbers
            )
            .putString(StarMapMosaicConfig.TRAVERSAL_PREF, mosaicConfig.traversal.name)
            .putString(StarMapMosaicConfig.START_CORNER_PREF, mosaicConfig.startCorner.name)
            .putBoolean("star_map_equatorial_grid", equatorialGrid)
            .putBoolean("star_map_azimuthal_grid", azimuthalGrid)
            .putBoolean("star_map_meridian", meridianLine)
            .putBoolean("star_map_ecliptic", eclipticLine)
            .putBoolean("star_map_constellation_lines", constellationLines)
            .putBoolean("star_map_constellation_labels", constellationLabels)
            .putBoolean("star_map_constellation_bounds", constellationBounds)
            .putBoolean("star_map_star_labels", starHints)
            .putString(
                UserOpticsCatalog.TELESCOPES_KEY,
                UserOpticsCatalog.formatTelescopes(telescopes)
            )
            .putString(
                UserOpticsCatalog.EYEPIECES_KEY,
                UserOpticsCatalog.formatEyepieces(eyepieces)
            )
            .putString(
                UserOpticsCatalog.CAMERAS_KEY,
                UserOpticsCatalog.formatCameras(userCameras)
            )
            .apply()
    }

    fun persistPrecisionTolerance(raw: String) {
        precisionToleranceText = raw.filter { it.isDigit() || it == '.' }
        precisionToleranceText.toDoubleOrNull()?.takeIf { it > 0.0 }?.let { value ->
            prefs.edit().putFloat(
                PrecisionGotoMath.PREFS_TOLERANCE_ARCMIN,
                PrecisionGotoMath.clampToleranceArcmin(value).toFloat()
            ).apply()
        }
    }

    fun currentPrecisionToleranceArcmin(): Double {
        return PrecisionGotoMath.clampToleranceArcmin(
            precisionToleranceText.toDoubleOrNull() ?: PrecisionGotoMath.TOLERANCE_ARCMIN
        )
    }

    fun applySkyAppearance() {
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.setSkyAppearance(" +
                "$equatorialGrid,$azimuthalGrid,$meridianLine,$eclipticLine," +
                "$constellationLines,$constellationLabels,$constellationBounds,$starHints);"
        )
    }

    fun applyNightVision() {
        val view = webView
        if (view != null) {
            if (redNightMode) {
                val paint = android.graphics.Paint()
                paint.colorFilter = android.graphics.ColorMatrixColorFilter(
                    android.graphics.ColorMatrix(
                        floatArrayOf(
                            0.80f, 0.20f, 0.05f, 0f, 0f,
                            0.03f, 0.04f, 0f, 0f, 0f,
                            0.01f, 0f, 0.02f, 0f, 0f,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
                view.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            } else {
                view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
            }
        }
        val enabled = if (redNightMode) "true" else "false"
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.setNightVision($enabled);"
        )
    }

    fun persistImagingFocalLength(focalLengthMm: Double?) {
        focalLengthMm?.toFloat()?.takeIf { it.isFinite() && it > 0f }?.let {
            prefs.edit().putFloat("plate_focal_length_mm", it).apply()
        }
    }

    fun maybeWritePlateFocalLength(before: OpticsTrainConfig, train: OpticsTrainConfig) {
        if (!StarMapOpticsPrefs.shouldWritePlateFocalLength(before, train)) return
        persistImagingFocalLength(
            train.focalLengthMm(telescopes)
        )
    }

    fun setOnlineDssEnabled(enabled: Boolean) {
        onlineDssEnabled = enabled
        prefs.edit().putBoolean(HipsTileCache.PREFS_ONLINE_DSS, enabled).apply()
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.setOnlineSurveyEnabled(" +
                "${if (enabled) "true" else "false"});"
        )
    }

    fun refreshHipsCacheLabel() {
        hipsCacheSizeLabel = HipsTileCache.formatCacheSize(hipsCache.cacheSizeBytes())
    }

    fun applyTargetFraming(alsoZoom: Boolean = false) {
        val angle = normalizePositionAngle(importAngleText.toDoubleOrNull() ?: 0.0)
        StarMapFovOverlay.targetSensorFramingScripts(
            positionAngleDeg = angle,
            mosaic = mosaicConfig,
            alsoZoom = alsoZoom
        ).forEach(::evalStarMap)
    }

    fun applyFovOverlays(alsoZoom: Boolean) {
        val currentAnchor = currentFovSource.currentAnchor.takeIf { currentFrameCentered }
        val targetAnchor = selectedTarget?.let {
            FovSkyAnchor(it.raHours, it.decDegrees, it.frame)
        }
        StarMapFovOverlay.scripts(
            showOverlay = showFovOverlay,
            computation = activeComputation,
            currentLabel = overlayCurrentLabel,
            targetLabel = overlayTargetLabel,
            alsoZoom = alsoZoom,
            currentAnchor = currentAnchor,
            targetAnchor = targetAnchor,
            showCurrent = currentFovSource.showCurrent && currentFrameCentered && resolvedObserverSite != null,
            currentPositionAngleDeg = currentFovSource.source?.positionAngleDeg
        ).forEach(::evalStarMap)
        applyTargetFraming(alsoZoom)
    }

    fun setFollowMountEnabled(enabled: Boolean) {
        val source = latestFollowSource.source
        val now = latestPointingClock()
        val usable = latestObserverSite != null && latestFollowSource.showCurrent && source != null &&
            now - source.absoluteUtcMs in 0..30_000 && now - source.estimateUtcMs in 0..3_000
        if (!enabled && followMount && !usable) followInterrupted = true
        followMount = enabled && usable
        if (followMount) {
            followPhone = false
            prefs.edit().putBoolean("star_map_follow_phone", false).apply()
        }
        if (enabled) followInterrupted = !followMount
        persistFovPrefs()
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.setFollowMount(${if (followMount) "true" else "false"});"
        )
        overlaysVisible = true
    }

    fun setPhoneFollowEnabled(enabled: Boolean) {
        followPhone = enabled && phoneOrientation.available
        prefs.edit().putBoolean("star_map_follow_phone", followPhone).apply()
        if (followPhone) setFollowMountEnabled(false)
        overlaysVisible = true
    }

    fun persistAndApplySkyAppearance() {
        persistFovPrefs()
        applySkyAppearance()
    }

    fun centerOnPointing() {
        if (!latestFollowSource.showCurrent) return
        evalStarMap("window.MercStarMap && window.MercStarMap.centerOnPointing(1);")
        overlaysVisible = true
    }
    fun centerOnMount() {
        // Explicit model diagnostic; it is never used as the follow source.
        if (!canReadCoordinates) return
        evalStarMap("window.MercStarMap && window.MercStarMap.centerOnMount(1);")
        overlaysVisible = true
    }
    fun refreshPointing() {
        if (resolvedObserverSite == null) onObserverSiteSettings()
        else if (selectedTarget != null) onPushTo(requireNotNull(selectedTarget)) else onOpenPreparation()
    }

    fun centerOnRaDec(raHours: Double, decDegrees: Double, frame: String = "JNOW", duration: Int = 1) {
        val source = if (frame == "J2000" || frame == "ICRF") "ICRF" else "JNOW"
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.centerOnRaDec(" +
                "%.8f,%.8f,%d,'%s');".format(Locale.US, raHours, decDegrees, duration, source)
        )
        overlaysVisible = true
    }

    fun centerOnTarget() {
        selectedTarget?.let { centerOnRaDec(it.raHours, it.decDegrees, it.frame) }
        overlaysVisible = true
    }

    fun selectMapCenterTarget() {
        val name = JSONObject.quote(context.getString(R.string.star_map_custom_target))
        evalStarMap("window.MercStarMap && window.MercStarMap.selectMapCenter($name);")
        overlaysVisible = true
        interaction = interaction.selectTarget()
    }

    fun adjustImportAngle(delta: Double) {
        val current = importAngleText.toDoubleOrNull() ?: 0.0
        importAngleText = positionAngleText(current + delta)
    }

    fun updateSensorAngleCalibration(next: SensorAngleCalibration) {
        sensorAngleCalibration = next
        prefs.edit()
            .putFloat(SensorAngleCalibration.OFFSET_PREF, next.offsetDeg.toFloat())
            .putBoolean(SensorAngleCalibration.REVERSED_PREF, next.reversed)
            .apply()
    }

    fun sequenceMosaicPlan(target: StarMapTarget): SequenceMosaicPlan {
        val angle = normalizePositionAngle(importAngleText.toDoubleOrNull() ?: 0.0)
        val computation = activeComputation
        val panels = if (
            computation?.mode == FovInstrumentMode.SENSOR &&
            computation.rectWidthDeg != null &&
            computation.rectHeightDeg != null
        ) {
            StarMapMosaicPlanner.panels(
                centerRaHours = target.raHours,
                centerDecDegrees = target.decDegrees,
                widthDeg = computation.rectWidthDeg,
                heightDeg = computation.rectHeightDeg,
                positionAngleDeg = angle,
                config = mosaicConfig
            ).map { panel ->
                SequenceMosaicPanel(
                    number = panel.number,
                    row = panel.row + 1,
                    column = panel.column + 1,
                    raHours = panel.raHours,
                    decDeg = panel.decDegrees
                )
            }
        } else {
            listOf(SequenceMosaicPanel(1, 1, 1, target.raHours, target.decDegrees))
        }
        return SequenceMosaicPlan(
            name = target.name,
            centerRaHours = target.raHours,
            centerDecDeg = target.decDegrees,
            positionAngleDeg = angle,
            rows = if (panels.size > 1) mosaicConfig.rows else 1,
            columns = if (panels.size > 1) mosaicConfig.columns else 1,
            overlapPercent = if (panels.size > 1) mosaicConfig.overlapPercent else 0,
            traversal = mosaicConfig.traversal.name,
            startCorner = mosaicConfig.startCorner.name,
            panels = panels,
            rotateWithRotator = rotatorConnected
        )
    }

    fun gotoCatalogObject(obj: CatalogObject) {
        importAngleText = "0"
        selectedTarget = StarMapTarget(
            name = "${obj.id} · ${obj.name}",
            raHours = obj.raHours,
            decDegrees = obj.decDeg,
            frame = "J2000"
        )
        // OpenNGC coordinates are J2000 = ICRF for pointing purposes.
        centerOnRaDec(obj.raHours, obj.decDeg, frame = "ICRF")
        searchDialogVisible = false
        searchQuery = ""
        interaction = interaction.selectTarget()
        overlaysVisible = true
    }

    /**
     * Prefer letting the engine resolve and select the object: it owns the
     * coordinates the map is drawn from, so the view and the reported target
     * cannot disagree. `core_search` only sees already-loaded tiles, so fall
     * back to centering on the catalog position when it comes up empty.
     */
    fun gotoSearchResult(obj: CatalogObject) {
        searchDialogVisible = false
        searchQuery = ""
        overlaysVisible = true
        val view = webView
        if (view == null) {
            gotoCatalogObject(obj)
            return
        }
        view.evaluateJavascript(StarMapSearch.selectScript(obj.engineDesignations())) { raw ->
            if (raw?.trim() != "true") gotoCatalogObject(obj)
        }
    }

    fun destroyWebView(current: WebView?) {
        current?.apply {
            // AndroidView.onRelease has already detached us from the parent.
            removeJavascriptInterface("AndroidStarMap")
            stopLoading()
            destroy()
        }
    }

    /**
     * Recreate the Stellarium WebView by bumping [webViewSession].
     * Do **not** destroy the current WebView here: Compose disposes the old
     * `AndroidView` after the new factory has already assigned [webView], so an
     * eager destroy (or a session DisposableEffect that reads [webView]) would
     * kill the replacement and leave a permanent black screen.
     */
    fun reloadStarMap() {
        // Reloading the renderer preserves observing context and explicitly dismissed cards.
        interaction = interaction.copy(targetExpanded = false, panel = StarMapCornerPanel.NONE)
        engineState = StarMapEngineState.Loading
        overlaysVisible = true
        webView = null
        webViewSession++
    }

    val latestOnBlankTap by rememberUpdatedState {
        if (interaction.hasInformationLayer) interaction = interaction.dismissTopLayer()
        overlaysVisible = true
    }

    val bridge = remember(context, webViewSession) {
        StarMapJavascriptBridge(
            onReady = {
                engineState = StarMapLoadRules.acceptReady(engineState)
                overlaysVisible = true
            },
            onError = { message ->
                engineState = StarMapLoadRules.acceptFailure(engineState, message)
                overlaysVisible = true
            },
            onSelected = {
                if (selectedTarget?.name != it?.name) importAngleText = "0"
                selectedTarget = it
                interaction = interaction.selectTarget()
                overlaysVisible = true
            },
            onUpdated = { updated ->
                // Coordinate refreshes update the same selection without reopening its card.
                if (selectedTarget?.name == updated.name) selectedTarget = updated
            },
            onBlankTap = { latestOnBlankTap() },
            onViewChanged = { ra, dec, fov ->
                if (acceptViewUpdates) {
                    savedCenterRaHours = ra
                    savedCenterDecDegrees = dec
                    savedCenterFrame = "JNOW"
                    savedFovDegrees = fov
                }
            },
            onFollowChanged = { enabled -> setFollowMountEnabled(enabled) },
            onPhoneFollowChanged = { enabled -> setPhoneFollowEnabled(enabled) },
            fallbackTargetName = context.getString(R.string.selected_target),
            parseError = { context.getString(R.string.star_target_parse_error, it.message.orEmpty()) }
        )
    }

    BackHandler {
        when {
            gotoConfirmation != null -> gotoConfirmation = null
            syncConfirmation != null -> syncConfirmation = null
            precisionConfirmation != null -> precisionConfirmation = null
            sequenceImportPlan != null -> sequenceImportPlan = null
            confirmHome -> confirmHome = false
            searchDialogVisible -> searchDialogVisible = false
            starMapTimeDialogVisible -> starMapTimeDialogVisible = false
            fovDialogVisible -> fovDialogVisible = false
            interaction.hasInformationLayer -> interaction = interaction.dismissTopLayer()
            else -> onBack()
        }
    }

    LaunchedEffect(selectedObservationTarget?.name, selectedObservationTarget?.raHours,
        selectedObservationTarget?.decDegrees, selectedObservationTarget?.frame) {
        val next = selectedObservationTarget ?: return@LaunchedEffect
        if (!selectedTarget.hasSameIdentity(next)) {
            selectedTarget = next
            interaction = interaction.selectTarget()
            importAngleText = positionAngleText(next.positionAngleDeg)
            savedCenterRaHours = next.raHours
            savedCenterDecDegrees = next.decDegrees
            savedCenterFrame = next.frame
            overlaysVisible = true
            centerOnRaDec(next.raHours, next.decDegrees, next.frame)
        }
    }

    LaunchedEffect(webView, engineState) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        val ra = savedCenterRaHours ?: selectedTarget?.raHours
        val dec = savedCenterDecDegrees ?: selectedTarget?.decDegrees
        if (ra != null && dec != null) centerOnRaDec(ra, dec,
            if (savedCenterRaHours != null) savedCenterFrame else selectedTarget?.frame ?: "JNOW", duration = 0)
        savedFovDegrees?.let { fov ->
            evalStarMap("window.MercStarMap && window.MercStarMap.setFovDegrees($fov,0);")
        }
        // Ignore the renderer's default first view until restoration has executed.
        webView?.evaluateJavascript("true;") { acceptViewUpdates = true }
    }

    LaunchedEffect(webViewSession, engineState) {
        if (engineState !is StarMapEngineState.Loading) return@LaunchedEffect
        delay(StarMapLoadRules.READY_TIMEOUT_MS)
        val timeoutMessage = context.getString(R.string.star_map_load_timeout)
        engineState = StarMapLoadRules.acceptTimeout(engineState, timeoutMessage)
        if (engineState is StarMapEngineState.Error) overlaysVisible = true
    }

    LaunchedEffect(showGlobalStop) {
        if (showGlobalStop) interaction = interaction.copy(panel = StarMapCornerPanel.NONE)
    }

    LaunchedEffect(selectedTarget, importAngleText) {
        val current = selectedTarget ?: return@LaunchedEffect
        onTargetSelected(
            current.copy(
                positionAngleDeg = normalizePositionAngle(importAngleText.toDoubleOrNull() ?: 0.0)
            )
        )
    }

    LaunchedEffect(
        webView,
        engineState,
        importAngleText,
        mosaicConfig,
        activeComputation,
        showFovOverlay
    ) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        applyTargetFraming()
    }

    LaunchedEffect(
        foreground,
        overlaysVisible,
        overlaysLocked,
        hudPointerActive,
        hudActivityRevision,
        targetExpanded,
        engineState,
        selectedTarget,
        sequenceImportPlan,
        gotoConfirmation,
        syncConfirmation,
        precisionConfirmation,
        precisionGotoProgress.isActive,
        searchDialogVisible,
        cornerPanel,
        fovDialogVisible,
        confirmHome,
        starMapTimeDialogVisible
    ) {
        if (!foreground || !overlaysVisible ||
            overlaysLocked ||
            hudPointerActive ||
            targetExpanded ||
            engineState !is StarMapEngineState.Ready ||
            sequenceImportPlan != null ||
            gotoConfirmation != null ||
            syncConfirmation != null ||
            precisionConfirmation != null ||
            precisionGotoProgress.isActive ||
            searchDialogVisible ||
            cornerPanel != StarMapCornerPanel.NONE ||
            fovDialogVisible ||
            confirmHome ||
            starMapTimeDialogVisible
        ) {
            return@LaunchedEffect
        }
        val idleRevision = hudActivityRevision
        delay(StarMapLoadRules.OVERLAY_IDLE_MS)
        // A press or menu opening at the deadline can precede the next composition.
        // Recheck the live state so a resumed old coroutine cannot close that interaction.
        if (idleRevision != hudActivityRevision || hudPointerActive || overlaysLocked ||
            interaction.targetExpanded || interaction.panel != StarMapCornerPanel.NONE ||
            sequenceImportPlan != null || gotoConfirmation != null || syncConfirmation != null || precisionConfirmation != null ||
            searchDialogVisible || fovDialogVisible || confirmHome || starMapTimeDialogVisible ||
            precisionGotoProgress.isActive) return@LaunchedEffect
        overlaysVisible = false
        interaction = interaction.copy(targetCardVisible = false, targetExpanded = false)
    }

    LaunchedEffect(webView, resolvedObserverSite, starMapEpochMillis, engineState) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        val site = resolvedObserverSite
        if (site == null) {
            evalStarMap("window.MercStarMap && window.MercStarMap.clearObserver();")
            return@LaunchedEffect
        }
        val script = "window.MercStarMap && window.MercStarMap.setObserver(" +
            "${site.latitudeDeg},${site.longitudeDeg},$starMapEpochMillis);"
        webView?.evaluateJavascript(script, null)
    }

    LaunchedEffect(webView, starMapEpochMillis, engineState) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        evalStarMap("window.MercStarMap && window.MercStarMap.setTime($starMapEpochMillis);")
    }

    LaunchedEffect(starMapTimeManual) {
        if (starMapTimeManual) return@LaunchedEffect
        while (true) {
            starMapEpochMillis = System.currentTimeMillis()
            delay(60_000L)
        }
    }

    LaunchedEffect(mountTime) {
        val synchronizedTime = mountTime?.epochMillis ?: return@LaunchedEffect
        applyStarMapTime(synchronizedTime, manual = true)
    }

    LaunchedEffect(webView, mountCoordinates, engineState) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        val coordinates = mountCoordinates
        val script = if (coordinates == null) {
            "window.MercStarMap && window.MercStarMap.clearMountCoordinates();"
        } else {
            "window.MercStarMap && window.MercStarMap.setMountCoordinates(" +
                "${coordinates.raHours},${coordinates.decDeg});"
        }
        webView?.evaluateJavascript(script, null)
    }
    LaunchedEffect(webView, currentFollowSource, engineState) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        val source = currentFollowSource.source?.takeIf { currentFollowSource.showCurrent }
        evalStarMap(if (source == null) "window.MercStarMap && window.MercStarMap.clearPointingCoordinates();"
            else "window.MercStarMap && window.MercStarMap.setPointingCoordinates(" +
                "${source.anchor.raHours},${source.anchor.decDegrees},${JSONObject.quote(source.anchor.jsFrame())}," +
                "${source.absoluteUtcMs},${source.estimateUtcMs});")
    }

    LaunchedEffect(
        webView,
        engineState,
        followMount,
        showFovOverlay,
        activeTrain,
        primaryTrain,
        secondaryTrain,
        overlayCurrentLabel,
        overlayTargetLabel,
        currentFovSource,
        currentFrameCentered,
        mountCoordinates,
        mountConnected,
        cameraPixelSizeUm,
        cameraFrameWidthPx,
        cameraFrameHeightPx,
        fovDialogVisible,
        selectedTarget?.raHours,
        selectedTarget?.decDegrees,
        selectedTarget?.frame,
        mosaicConfig
    ) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.setFollowMount(${if (followMount) "true" else "false"});"
        )
        // Zoom once when the sheet is open so the frame fills the view; otherwise
        // only redraw overlays so pan/zoom the user already set stays put.
        applyFovOverlays(alsoZoom = fovDialogVisible && editingTrain == activeTrain)
    }

    LaunchedEffect(
        webView,
        engineState,
        precisionGotoProgress.solvedRaHours,
        precisionGotoProgress.solvedDecDeg,
        precisionGotoProgress.iteration
    ) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        val ra = precisionGotoProgress.solvedRaHours ?: return@LaunchedEffect
        val dec = precisionGotoProgress.solvedDecDeg ?: return@LaunchedEffect
        centerOnRaDec(ra, dec, frame = "JNOW")
    }

    LaunchedEffect(searchDialogVisible, searchQuery) {
        if (!searchDialogVisible || searchQuery.isBlank()) {
            searchResults = emptyList()
            return@LaunchedEffect
        }
        delay(180)
        // 13k entries: parse and scan off the main thread.
        searchResults = withContext(Dispatchers.Default) { catalog.search(searchQuery) }
    }

    LaunchedEffect(webView, atmosphereVisible, engineState) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        val script =
            "window.MercStarMap && window.MercStarMap.setAtmosphereVisible($atmosphereVisible);"
        webView?.evaluateJavascript(script, null)
    }

    LaunchedEffect(webView, landscapeVisible, engineState) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        evalStarMap("window.MercStarMap && window.MercStarMap.setLandscapeVisible($landscapeVisible);")
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(phoneOrientation, lifecycleOwner, foreground, followPhone, engineState, webView, resolvedObserverSite) {
        var registered = false
        phoneFollowHasSample = false
        fun updateSubscription() {
            val shouldRun = foreground && followPhone && engineState is StarMapEngineState.Ready &&
                webView != null && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if (shouldRun && !registered) {
                registered = phoneOrientation.start(resolvedObserverSite) { direction, utcMs ->
                    phoneFollowHasSample = true
                    evalStarMap("window.MercStarMap && window.MercStarMap.setPhoneOrientation(" +
                        "${direction.azimuthDeg},${direction.altitudeDeg},$utcMs);")
                }
                phoneFollowRegistrationFailed = !registered
                evalStarMap("window.MercStarMap && window.MercStarMap.setPhoneFollow($registered);")
            } else if (!shouldRun) {
                phoneOrientation.stop()
                registered = false
                evalStarMap("window.MercStarMap && window.MercStarMap.setPhoneFollow(false);")
            }
        }
        val observer = LifecycleEventObserver { _, _ -> updateSubscription() }
        lifecycleOwner.lifecycle.addObserver(observer)
        updateSubscription()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            phoneOrientation.stop()
            evalStarMap("window.MercStarMap && window.MercStarMap.setPhoneFollow(false);")
        }
    }

    LaunchedEffect(webView, redNightMode, engineState) {
        applyNightVision()
    }

    LaunchedEffect(webView, onlineDssEnabled, engineState) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        val enabled = if (onlineDssEnabled) "true" else "false"
        webView?.evaluateJavascript(
            "window.MercStarMap && window.MercStarMap.setOnlineSurveyEnabled($enabled);",
            null
        )
    }

    LaunchedEffect(
        webView,
        engineState,
        equatorialGrid,
        azimuthalGrid,
        meridianLine,
        eclipticLine,
        constellationLines,
        constellationLabels,
        constellationBounds,
        starHints
    ) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        applySkyAppearance()
    }

    LaunchedEffect(overlaysVisible) {
        if (!overlaysVisible) interaction = interaction.copy(panel = StarMapCornerPanel.NONE)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
        // Observe, without consuming, all Compose/WebView presses. A live press/scroll
        // cancels the idle coroutine; release starts a complete new interval.
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.any { it.pressed }
                if (hudPointerActive != pressed) {
                    hudPointerActive = pressed
                    hudActivityRevision++
                }
            }
        }
    }) {
        val availablePanelHeight = minOf(420.dp, (maxHeight - 78.dp).coerceAtLeast(48.dp))
        val compactLandscape = maxWidth > maxHeight && maxHeight < 400.dp
        key(webViewSession) {
            AndroidView(
                factory = { viewContext ->
                    val assetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(viewContext))
                        .addPathHandler("/hips/", hipsCache.pathHandler())
                        .build()
                    WebView(viewContext).apply {
                        setBackgroundColor(Color.BLACK)
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        addJavascriptInterface(bridge, "AndroidStarMap")
                        webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest
                            ): WebResourceResponse? {
                                return assetLoader.shouldInterceptRequest(request.url)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError
                            ) {
                                if (!request.isForMainFrame) return
                                val detail = error.description?.toString().orEmpty()
                                engineState = StarMapLoadRules.acceptFailure(
                                    engineState,
                                    viewContext.getString(R.string.star_map_load_failed, detail)
                                )
                                overlaysVisible = true
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: WebResourceResponse
                            ) {
                                if (!request.isForMainFrame) return
                                engineState = StarMapLoadRules.acceptFailure(
                                    engineState,
                                    viewContext.getString(
                                        R.string.star_map_load_failed,
                                        "HTTP ${errorResponse.statusCode}"
                                    )
                                )
                                overlaysVisible = true
                            }

                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail
                            ): Boolean {
                                engineState = StarMapLoadRules.acceptFailure(
                                    engineState,
                                    viewContext.getString(R.string.star_map_renderer_crashed)
                                )
                                overlaysVisible = true
                                if (webView === view) webView = null
                                // Let Compose tear the view down; do not destroy here while
                                // still attached — onRelease will finish cleanup.
                                return true
                            }
                        }
                        webView = this
                        // Load after the first real layout so the WASM canvas gets a
                        // non-zero size; posting avoids racing AndroidView attach.
                        var pageLoaded = false
                        doOnLayout { laidOutView ->
                            if (pageLoaded || !laidOutView.isAttachedToWindow) return@doOnLayout
                            pageLoaded = true
                            laidOutView.post {
                                if (laidOutView.isAttachedToWindow) {
                                    (laidOutView as WebView).loadUrl(
                                        "https://appassets.androidplatform.net/assets/stellarium/index.html?landscape=" +
                                            if (landscapeVisible) "1" else "0"
                                    )
                                }
                            }
                        }
                    }
                },
                onRelease = { released ->
                    if (webView === released) webView = null
                    destroyWebView(released)
                },
                modifier = Modifier.fillMaxSize().testTag("star-map-canvas")
            )
        }

        // Every ordinary browsing control shares one idle timer and blank-tap wakeup.
        AnimatedVisibility(
            visible = overlaysVisible || engineState !is StarMapEngineState.Ready,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart)
        ) {
        FlowRow(
            modifier = Modifier
                .padding(8.dp)
                .widthIn(max = if (compactLandscape) (maxWidth - 80.dp).coerceAtLeast(160.dp) else 240.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
                Card {
                    TextButton(onClick = onOpenTargets) {
                        Text(stringResource(R.string.target_library_title))
                    }
                }
                Card {
                    TextButton(onClick = onOpenPreparation) {
                        Text(stringResource(R.string.wf_star_map_preparation))
                    }
                }
                Card { TextButton(onClick = onObserverSiteSettings) {
                    Text(if (resolvedObserverSite == null && landscapeVisible) stringResource(R.string.sky_simulated_site_missing)
                        else observerSiteLabel ?: stringResource(if (resolvedObserverSite == null) R.string.wf_observer_missing else R.string.wf_observer_site))
                } }
                if (showFovOverlay && activeComputation?.hasOverlay == true || followMount || followInterrupted) {
                    Card {
                        Column {
                        Text(pointingSourceCaption,
                            Modifier.testTag("pointing-follow-source").padding(horizontal = 10.dp, vertical = 6.dp), style = MaterialTheme.typography.labelSmall
                        )
                        if (followInterrupted) TextButton(onClick = {
                            if (currentFollowSource.showCurrent && resolvedObserverSite != null) setFollowMountEnabled(true) else refreshPointing()
                        }) { Text(stringResource(if (resolvedObserverSite == null) R.string.wf_observer_site else if (currentFollowSource.showCurrent) R.string.wf_follow_resume else R.string.wf_follow_refresh)) }
                        }
                    }
                }
                if (selectedTarget != null && !interaction.targetCardVisible) {
                    Card {
                        TextButton(onClick = {
                            interaction = interaction.selectTarget()
                            overlaysVisible = true
                        }) {
                            Text(stringResource(R.string.wf_star_map_current_target))
                        }
                    }
                }
        }
        }

        AnimatedVisibility(
            visible = overlaysVisible || engineState !is StarMapEngineState.Ready,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            when (val state = engineState) {
                StarMapEngineState.Loading -> {
                    Card(modifier = Modifier.padding(8.dp)) {
                        Text(
                            stringResource(R.string.loading_star_map),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                StarMapEngineState.Ready -> {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Card {
                            IconButton(
                                onClick = {
                                    searchQuery = ""
                                    searchDialogVisible = true
                                    overlaysVisible = true
                                },
                                modifier = Modifier.semantics {
                                    contentDescription =
                                        context.getString(R.string.star_map_search)
                                }
                            ) {
                                Icon(
                                    Icons.Default.Search,
                                    contentDescription = stringResource(R.string.star_map_search)
                                )
                            }
                        }
                        Card {
                            TextButton(onClick = ::selectMapCenterTarget) {
                                Text(
                                    stringResource(R.string.star_map_use_center),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
                is StarMapEngineState.Error -> {
                    Card(
                        modifier = Modifier
                            .padding(8.dp)
                            .widthIn(max = 320.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                state.message,
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                            OutlinedButton(onClick = ::reloadStarMap) {
                                Icon(Icons.Default.Refresh, contentDescription = null)
                                Text(stringResource(R.string.reload_star_map))
                            }
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = overlaysVisible &&
                engineState is StarMapEngineState.Ready &&
                !showGlobalStop,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val panelMax = minOf(360.dp, maxWidth - 16.dp)
                val target = selectedTarget
                val showTargetCard = target != null && interaction.targetCardVisible &&
                    cornerPanel == StarMapCornerPanel.NONE
                val canSlewMount = canGoto
                val canSyncMount = canSync
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
            StarMapCornerControls(
                modifier = Modifier.widthIn(max = panelMax),
                maxPanelHeight = availablePanelHeight,
                panel = cornerPanel,
                onPanelChange = {
                    interaction = interaction.openPanel(it)
                    overlaysVisible = true
                    if (it == StarMapCornerPanel.SKY) refreshHipsCacheLabel()
                },
                mountConnected = mountConnected,
                followAvailable = currentFollowSource.showCurrent && resolvedObserverSite != null,
                pointingSourceCaption = pointingSourceCaption,
                canManualMove = mountPointingCapabilities.manualMove,
                canHome = mountPointingCapabilities.home,
                homeEnabled = canHome,
                moveEnabled = moveEnabled,
                mountSlewRate = mountSlewRate,
                followMount = followMount,
                followPhone = followPhone,
                phoneFollowAvailable = phoneOrientation.available,
                phoneFollowCaption = stringResource(when {
                    !phoneOrientation.available -> R.string.sky_phone_follow_unavailable
                    phoneFollowRegistrationFailed -> R.string.sky_phone_follow_failed
                    followPhone && !phoneFollowHasSample -> R.string.sky_phone_follow_waiting
                    resolvedObserverSite == null -> R.string.sky_phone_follow_magnetic
                    else -> R.string.sky_phone_follow_hint
                }),
                activeTrain = activeTrain,
                showFovOverlay = showFovOverlay,
                equatorialGrid = equatorialGrid,
                azimuthalGrid = azimuthalGrid,
                meridian = meridianLine,
                ecliptic = eclipticLine,
                constellationLines = constellationLines,
                constellationLabels = constellationLabels,
                constellationBounds = constellationBounds,
                starHints = starHints,
                atmosphereVisible = atmosphereVisible,
                landscapeVisible = landscapeVisible,
                onlineDssEnabled = onlineDssEnabled,
                starMapTimeLabel = formatStarMapTime(starMapEpochMillis),
                overlaysLocked = overlaysLocked,
                hipsCacheSizeLabel = hipsCacheSizeLabel,
                onSlewRateChange = onSlewRateChange,
                onManualMoveStart = onManualMoveStart,
                onManualMoveStop = onManualMoveStop,
                onStopMount = onStopMount,
                onConfirmHome = { if (canHome) confirmHome = true },
                onFollowMountChange = ::setFollowMountEnabled,
                onFollowPhoneChange = ::setPhoneFollowEnabled,
                onCenterOnPointing = ::centerOnPointing,
                onRefreshPointing = ::refreshPointing,
                onActiveTrainChange = {
                    activeTrain = it
                    persistFovPrefs()
                    applyFovOverlays(alsoZoom = fovDialogVisible)
                },
                onOpenFov = {
                    editingTrain = activeTrain
                    fovDialogVisible = true
                    overlaysVisible = true
                },
                onOpenTimeSettings = ::openStarMapTimeDialog,
                precisionToleranceText = precisionToleranceText,
                onPrecisionToleranceChange = ::persistPrecisionTolerance,
                onShowFovOverlayChange = {
                    showFovOverlay = it
                    persistFovPrefs()
                    applyFovOverlays(alsoZoom = false)
                },
                onEquatorialGridChange = {
                    equatorialGrid = it
                    persistAndApplySkyAppearance()
                },
                onAzimuthalGridChange = {
                    azimuthalGrid = it
                    persistAndApplySkyAppearance()
                },
                onMeridianChange = {
                    meridianLine = it
                    persistAndApplySkyAppearance()
                },
                onEclipticChange = {
                    eclipticLine = it
                    persistAndApplySkyAppearance()
                },
                onConstellationLinesChange = {
                    constellationLines = it
                    persistAndApplySkyAppearance()
                },
                onConstellationLabelsChange = {
                    constellationLabels = it
                    persistAndApplySkyAppearance()
                },
                onConstellationBoundsChange = {
                    constellationBounds = it
                    persistAndApplySkyAppearance()
                },
                onStarHintsChange = {
                    starHints = it
                    persistAndApplySkyAppearance()
                },
                onAtmosphereChange = { atmosphereVisible = it },
                onLandscapeChange = {
                    landscapeVisible = it
                    prefs.edit().putBoolean("star_map_simulated_landscape", it).apply()
                },
                onOnlineDssChange = ::setOnlineDssEnabled,
                onOverlaysLockedChange = {
                    overlaysLocked = it
                    if (it) overlaysVisible = true
                },
                onRefreshHipsCache = ::refreshHipsCacheLabel,
                onClearHipsCache = {
                    hipsCache.clearCache()
                    refreshHipsCacheLabel()
                },
                onReloadStarMap = ::reloadStarMap
            )
            if (showTargetCard && target != null) {
                Card(
                    modifier = Modifier
                        .padding(8.dp)
                        .widthIn(max = panelMax)
                        .heightIn(max = availablePanelHeight)
                        .testTag("star-map-target-card")
                        .consumeStarMapPanelTouches()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                target.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        interaction = interaction.copy(targetExpanded = !interaction.targetExpanded)
                                    }
                                    .semantics {
                                        contentDescription = context.getString(R.string.expand_target_details)
                                    }
                            )
                            IconButton(onClick = { interaction = interaction.dismissTopLayer() }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.close))
                            }
                        }
                        val startPushTo = {
                            onPushTo(target.copy(positionAngleDeg = normalizePositionAngle(importAngleText.toDoubleOrNull() ?: 0.0)))
                        }
                        if (mountConnected && mountPointingCapabilities.goto) {
                            FilledTonalButton(onClick = startPushTo, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.push_to_title))
                            }
                        } else {
                            Button(onClick = startPushTo, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(R.string.push_to_title))
                            }
                        }
                        if (mountConnected && (mountPointingCapabilities.goto || mountPointingCapabilities.sync)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (mountPointingCapabilities.goto) Button(
                                    enabled = canSlewMount,
                                    onClick = {
                                        overlaysVisible = true
                                        gotoConfirmation = target
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minWidth = 0.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.goto_label),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip
                                    )
                                }
                                if (mountPointingCapabilities.sync) FilledTonalButton(
                                    enabled = canSyncMount,
                                    onClick = {
                                        overlaysVisible = true
                                        syncConfirmation = target
                                    },
                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                                    modifier = Modifier
                                        .weight(1f)
                                        .defaultMinSize(minWidth = 0.dp)
                                ) {
                                    Text(
                                        stringResource(R.string.sync_label),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Clip
                                    )
                                }
                            }
                            if (mountPointingCapabilities.goto) OutlinedButton(
                                enabled = canSlewMount,
                                onClick = {
                                    overlaysVisible = true
                                    precisionConfirmation = target
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    stringResource(R.string.precision_goto_label),
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (targetExpanded) {
                            Text(
                                stringResource(R.string.sequence_sensor_angle),
                                style = MaterialTheme.typography.labelMedium
                            )
                            Text(
                                stringResource(
                                    if (showFovOverlay && activeComputation?.mode == FovInstrumentMode.SENSOR) {
                                        R.string.sequence_sensor_angle_preview_active
                                    } else {
                                        R.string.sequence_sensor_angle_preview_hint
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedTextField(
                                    value = importAngleText,
                                    onValueChange = { importAngleText = it },
                                    suffix = { Text("°") },
                                    singleLine = true,
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = { adjustImportAngle(-1.0) },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.defaultMinSize(minWidth = 0.dp)
                                ) { Text("−1°") }
                                OutlinedButton(
                                    onClick = { adjustImportAngle(1.0) },
                                    contentPadding = PaddingValues(horizontal = 8.dp),
                                    modifier = Modifier.defaultMinSize(minWidth = 0.dp)
                                ) { Text("+1°") }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                listOf(0, 90, 180, 270).forEach { angle ->
                                    TextButton(
                                        onClick = { importAngleText = angle.toString() },
                                        contentPadding = PaddingValues(horizontal = 2.dp),
                                        modifier = Modifier
                                            .weight(1f)
                                            .defaultMinSize(minWidth = 0.dp)
                                    ) { Text("$angle°", maxLines = 1) }
                                }
                            }
                            lastSolvedPositionAngleDeg?.let { solvedAngle ->
                                val plannedAngle = normalizePositionAngle(
                                    importAngleText.toDoubleOrNull() ?: 0.0
                                )
                                val error = SensorAngleCalibration.angularError(plannedAngle, solvedAngle)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        stringResource(
                                            R.string.fov_solved_sensor_angle,
                                            positionAngleText(solvedAngle)
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f)
                                    )
                                    TextButton(onClick = {
                                        importAngleText = positionAngleText(solvedAngle)
                                    }) {
                                        Text(stringResource(R.string.fov_use_solved_angle))
                                    }
                                }
                                if (rotatorConnected && error > 1.0) {
                                    Text(
                                        stringResource(R.string.fov_sensor_angle_error, error),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            if (rotatorConnected) {
                                val plannedAngle = normalizePositionAngle(
                                    importAngleText.toDoubleOrNull() ?: 0.0
                                )
                                val targetMechanical = sensorAngleCalibration.mechanicalAngle(plannedAngle)
                                Text(
                                    stringResource(
                                        R.string.fov_rotator_angle_summary,
                                        rotatorMechanicalAngleDeg,
                                        targetMechanical,
                                        sensorAngleCalibration.offsetDeg
                                    ),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    FilterChip(
                                        selected = sensorAngleCalibration.reversed,
                                        onClick = {
                                            updateSensorAngleCalibration(
                                                sensorAngleCalibration.copy(
                                                    reversed = !sensorAngleCalibration.reversed
                                                )
                                            )
                                        },
                                        label = { Text(stringResource(R.string.fov_rotator_reverse_mapping)) }
                                    )
                                    TextButton(
                                        enabled = lastSolvedPositionAngleDeg != null,
                                        onClick = {
                                            val solved = lastSolvedPositionAngleDeg ?: return@TextButton
                                            updateSensorAngleCalibration(
                                                sensorAngleCalibration.calibrated(
                                                    solved,
                                                    rotatorMechanicalAngleDeg
                                                )
                                            )
                                        }
                                    ) {
                                        Text(stringResource(R.string.fov_calibrate_rotator_angle))
                                    }
                                }
                                OutlinedButton(
                                    onClick = { onMoveRotator(targetMechanical) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.fov_rotate_to_composition))
                                }
                            }
                            if (showSequenceActions) {
                                OutlinedButton(
                                    onClick = {
                                        overlaysVisible = true
                                        sequenceImportPlan = sequenceMosaicPlan(target)
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        stringResource(R.string.sequence_add_from_star_map),
                                        maxLines = 1,
                                        softWrap = false,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                        TextButton(
                            onClick = { interaction = interaction.copy(targetExpanded = !interaction.targetExpanded) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(if (targetExpanded) R.string.wf_star_map_hide_details else R.string.wf_star_map_target_details)) }
                        if (precisionGotoProgress.isActive ||
                            precisionGotoProgress.phase == PrecisionGotoPhase.SUCCEEDED ||
                            precisionGotoProgress.phase == PrecisionGotoPhase.CANCELLED ||
                            precisionGotoProgress.phase == PrecisionGotoPhase.FAILED
                        ) {
                            Text(
                                buildString {
                                    append(precisionGotoProgress.message)
                                    precisionGotoProgress.errorArcmin?.let {
                                        append(" · ")
                                        append(
                                            context.getString(
                                                R.string.precision_goto_error_arcmin,
                                                it
                                            )
                                        )
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 2
                            )
                        }
                        if (mountConnected && mountPointingCapabilities.goto && !mountPointingCapabilities.sync) {
                            Text(
                                stringResource(R.string.mount_sync_unsupported),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        if (!mountConnected && targetExpanded) {
                            Text(
                                stringResource(R.string.connect_mount_for_goto),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        if (targetExpanded) {
                            Text(
                                stringResource(R.string.star_map_visual_sync_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                            Text(
                                target.coordinatesText(),
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                TextButton(
                                    onClick = ::centerOnTarget,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.center_on_target))
                                }
                                if (canReadCoordinates && mountCoordinates != null) {
                                    TextButton(
                                        onClick = ::centerOnMount,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(stringResource(R.string.center_on_mount))
                                    }
                                }
                            }
                        }
                    }
                }
            }
                }
            }
        }

        if (showGlobalStop) {
            val stopDetail = precisionGotoProgress.message.takeIf { it.isNotBlank() }
            MountMotionStopBanner(
                state = mountMotionState,
                onStop = onStopMount,
                detail = stopDetail,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(12.dp)
            )
        }
    }

    gotoConfirmation?.let { target ->
        StarMapGotoConfirmation(
            targetName = target.name,
            coordinates = target.coordinatesText(),
            onConfirm = {
                gotoConfirmation = null
                if (canGoto) onGoto(target)
            },
            onDismiss = { gotoConfirmation = null }
        )
    }

    syncConfirmation?.let { target ->
        StarMapSyncConfirmation(
            targetName = target.name,
            coordinates = target.coordinatesText(),
            onConfirm = {
                syncConfirmation = null
                if (canSync) onSync(target)
            },
            onDismiss = { syncConfirmation = null }
        )
    }

    precisionConfirmation?.let { target ->
        StarMapPrecisionGotoConfirmation(
            targetName = target.name,
            coordinates = target.coordinatesText(),
            toleranceArcmin = currentPrecisionToleranceArcmin(),
            onConfirm = {
                precisionConfirmation = null
                if (canGoto) onPrecisionGoto(target, currentPrecisionToleranceArcmin())
            },
            onDismiss = { precisionConfirmation = null }
        )
    }

    sequenceImportPlan?.let { plan ->
        AlertDialog(
            onDismissRequest = { sequenceImportPlan = null },
            title = { Text(stringResource(R.string.sequence_import_destination)) },
            text = {
                Text(
                    stringResource(
                        R.string.sequence_import_mosaic_detail,
                        plan.name,
                        plan.panels.size,
                        positionAngleText(plan.positionAngleDeg)
                    )
                )
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        sequenceImportPlan = null
                        onAddToSequence(plan, SequenceEditorMode.Simple)
                    }) { Text(stringResource(R.string.sequence_simple)) }
                    TextButton(onClick = {
                        sequenceImportPlan = null
                        onAddToSequence(plan, SequenceEditorMode.Advanced)
                    }) { Text(stringResource(R.string.sequence_advanced)) }
                }
            },
            dismissButton = {
                TextButton(onClick = { sequenceImportPlan = null }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (confirmHome) {
        AlertDialog(
            onDismissRequest = { confirmHome = false },
            title = { Text(stringResource(R.string.go_home)) },
            text = { Text(stringResource(R.string.mount_home_confirmation)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmHome = false
                    if (canHome) onGoHome()
                }) { Text(stringResource(R.string.go)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmHome = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (searchDialogVisible) {
        AlertDialog(
            onDismissRequest = { searchDialogVisible = false },
            title = { Text(stringResource(R.string.star_map_search)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(stringResource(R.string.star_map_search_hint)) }
                    )
                    if (searchResults.isEmpty()) {
                        Text(
                            stringResource(R.string.star_map_search_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            items(searchResults, key = { it.id }) { obj ->
                                TextButton(
                                    onClick = { gotoSearchResult(obj) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.Start
                                    ) {
                                        Text(
                                            obj.aliases.take(3).joinToString(" · "),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            listOfNotNull(
                                                obj.type.takeIf { it.isNotBlank() },
                                                obj.magnitude?.let { "%.1f mag".format(Locale.US, it) }
                                            ).joinToString(" · "),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { searchDialogVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (starMapTimeDialogVisible) {
        AlertDialog(
            onDismissRequest = { starMapTimeDialogVisible = false },
            title = { Text(stringResource(R.string.star_map_time_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.star_map_time_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = starMapTimeText,
                        onValueChange = {
                            starMapTimeText = it
                            starMapTimeError = false
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        isError = starMapTimeError,
                        label = { Text(stringResource(R.string.star_map_time_label)) },
                        supportingText = if (starMapTimeError) {
                            { Text(stringResource(R.string.star_map_time_invalid)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                    )
                    TextButton(
                        onClick = {
                            val now = System.currentTimeMillis()
                            applyStarMapTime(now, manual = false)
                            starMapTimeText = formatStarMapTime(now)
                            starMapTimeError = false
                            starMapTimeDialogVisible = false
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.star_map_time_use_current))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val parsed = parseStarMapTime(starMapTimeText)
                        if (parsed == null) {
                            starMapTimeError = true
                        } else {
                            applyStarMapTime(parsed, manual = true)
                            starMapTimeDialogVisible = false
                        }
                    }
                ) {
                    Text(stringResource(R.string.set))
                }
            },
            dismissButton = {
                TextButton(onClick = { starMapTimeDialogVisible = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    if (fovDialogVisible) {
        StarMapFovSheet(
            editingTrain = editingTrain,
            config = editingConfig,
            telescopes = telescopes,
            eyepieces = eyepieces,
            sensors = editingSensors,
            showOverlay = showFovOverlay,
            computation = editingComputation,
            mosaicConfig = mosaicConfig,
            positionAngleText = importAngleText,
            onEditingTrainChange = { editingTrain = it },
            onConfigChange = { updated ->
                val before = if (updated.id == OpticsTrainId.PRIMARY) primaryTrain else secondaryTrain
                val configured = updated.copy(telescopeFocalLengthOverrideMm =
                    if (before.telescopeId != updated.telescopeId || before.customTelescopeFl != updated.customTelescopeFl)
                        updated.customTelescopeFl.toDoubleOrNull() else before.telescopeFocalLengthOverrideMm)
                val nextOptics = if (updated.id == OpticsTrainId.PRIMARY) opticsConfiguration.copy(primary = configured)
                    else opticsConfiguration.copy(secondary = configured)
                opticsConfiguration.changeTo(nextOptics, updated.id)?.let { onOpticalPathInvalidated(updated.id, it) }
                previousOptics = nextOptics
                if (updated.id == OpticsTrainId.PRIMARY) {
                    primaryTrain = configured
                } else {
                    secondaryTrain = configured
                }
                showFovOverlay = true
                if (before != configured) {
                    confirmedTrains = confirmedTrains - updated.id
                    prefs.edit().putBoolean(PointingOpticsConfiguration.confirmationKey(updated.id), false).apply()
                }
                persistFovPrefs()
                maybeWritePlateFocalLength(before, configured)
            },
            onMosaicConfigChange = {
                mosaicConfig = it.normalized()
                showFovOverlay = true
                persistFovPrefs()
                applyTargetFraming(alsoZoom = true)
            },
            onPositionAngleChange = {
                importAngleText = it
                showFovOverlay = true
            },
            onShowOverlayChange = {
                showFovOverlay = it
                persistFovPrefs()
            },
            onTelescopesChange = {
                // A named model can be selected in both trains. Preserve the other train's focal length.
                fun freezeFocal(train: OpticsTrainConfig): OpticsTrainConfig {
                    val old = telescopes.firstOrNull { spec -> spec.id == train.telescopeId }
                    val next = it.firstOrNull { spec -> spec.id == train.telescopeId }
                    return if (old?.focalLengthMm != next?.focalLengthMm)
                        train.copy(telescopeFocalLengthOverrideMm = train.focalLengthMm(telescopes)) else train
                }
                primaryTrain = freezeFocal(primaryTrain)
                secondaryTrain = freezeFocal(secondaryTrain)
                telescopes = it
                persistFovPrefs()
                applyFovOverlays(alsoZoom = false)
            },
            onEyepiecesChange = {
                eyepieces = it
                persistFovPrefs()
                applyFovOverlays(alsoZoom = false)
            },
            onCamerasChange = {
                userCameras = it
                persistFovPrefs()
                applyFovOverlays(alsoZoom = false)
            },
            onDismiss = {
                persistFovPrefs()
                applyFovOverlays(alsoZoom = false)
                fovDialogVisible = false
            },
            configurationConfirmed = editingTrain in confirmedTrains,
            onConfirmConfiguration = {
                confirmedTrains = confirmedTrains + editingTrain
                prefs.edit().putBoolean(PointingOpticsConfiguration.confirmationKey(editingTrain), true).apply()
                persistFovPrefs()
            },
            onInvalidConfigurationDraft = {
                confirmedTrains = confirmedTrains - editingTrain
                prefs.edit().putBoolean(PointingOpticsConfiguration.confirmationKey(editingTrain), false).apply()
            }
        )
    }
}

@Composable
internal fun StarMapMountDirectionButton(
    label: String,
    contentDescription: String,
    direction: MountDirection,
    enabled: Boolean,
    onMoveStart: (MountDirection) -> Unit,
    onMoveStop: (MountDirection) -> Unit
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = if (enabled) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        tonalElevation = 1.dp,
        modifier = Modifier
            .size(44.dp)
            .semantics { this.contentDescription = contentDescription; if (!enabled) disabled() }
            .pointerInput(enabled, direction) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        onMoveStart(direction)
                        try {
                            tryAwaitRelease()
                        } finally {
                            onMoveStop(direction)
                        }
                    }
                )
            }
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}
