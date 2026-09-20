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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.webkit.WebViewAssetLoader
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.FovInstrumentMode
import com.indigo.mobileobservatory.astro.StarMapFovOverlay
import com.indigo.mobileobservatory.astro.OpticsEquipment
import com.indigo.mobileobservatory.catalog.AssetDeepSkyCatalog
import com.indigo.mobileobservatory.catalog.CatalogObject
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountSite
import com.indigo.mobileobservatory.mount.MountSlewRate
import com.indigo.mobileobservatory.mount.PrecisionGotoPhase
import com.indigo.mobileobservatory.mount.PrecisionGotoProgress
import com.indigo.mobileobservatory.starmap.HipsTileCache
import com.indigo.mobileobservatory.starmap.StarMapSearch
import com.indigo.mobileobservatory.ui.components.StarMapBackButton
import com.indigo.mobileobservatory.ui.components.StarMapGotoConfirmation
import com.indigo.mobileobservatory.ui.components.StarMapPrecisionGotoConfirmation
import com.indigo.mobileobservatory.ui.components.StarMapSyncConfirmation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.Locale

data class StarMapTarget(
    val name: String,
    val raHours: Double,
    val decDegrees: Double,
    val frame: String
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

private class StarMapJavascriptBridge(
    private val onReady: () -> Unit,
    private val onError: (String) -> Unit,
    private val onSelected: (StarMapTarget?) -> Unit,
    private val onFollowChanged: (Boolean) -> Unit,
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
            result.onSuccess { onSelected(it) }
                .onFailure { onError(parseError(it)) }
        }
    }

    @JavascriptInterface
    fun onSelectionCleared(@Suppress("UNUSED_PARAMETER") ignored: String) {
        mainHandler.post { onSelected(null) }
    }

    @JavascriptInterface
    fun onFollowMountChanged(enabled: String) {
        val follow = enabled.equals("true", ignoreCase = true)
        mainHandler.post { onFollowChanged(follow) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun StarMapScreen(
    mountCoordinates: MountCoordinates?,
    mountSite: MountSite?,
    mountConnected: Boolean,
    mountBusy: Boolean,
    mountSlewRate: MountSlewRate = MountSlewRate.DEFAULT,
    precisionGotoProgress: PrecisionGotoProgress = PrecisionGotoProgress(),
    cameraPixelSizeUm: Float? = null,
    cameraFrameWidthPx: Int = 0,
    cameraFrameHeightPx: Int = 0,
    onGoto: (StarMapTarget) -> Unit,
    onSync: (StarMapTarget) -> Unit = {},
    onPrecisionGoto: (StarMapTarget) -> Unit = {},
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
    var webViewSession by remember { mutableIntStateOf(0) }
    var selectedTarget by remember { mutableStateOf<StarMapTarget?>(null) }
    var engineState by remember { mutableStateOf<StarMapEngineState>(StarMapEngineState.Loading) }
    var gotoConfirmation by remember { mutableStateOf<StarMapTarget?>(null) }
    var syncConfirmation by remember { mutableStateOf<StarMapTarget?>(null) }
    var precisionConfirmation by remember { mutableStateOf<StarMapTarget?>(null) }
    var atmosphereVisible by remember { mutableStateOf(false) }
    var overlaysVisible by remember { mutableStateOf(true) }
    var overlaysLocked by remember { mutableStateOf(false) }
    var targetExpanded by remember { mutableStateOf(false) }
    var searchDialogVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var cornerPanel by remember { mutableStateOf(StarMapCornerPanel.NONE) }
    var confirmHome by remember { mutableStateOf(false) }
    var fovDialogVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE)
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
        mutableStateOf(prefs.getBoolean("star_map_follow_mount", false))
    }
    var fovMode by remember {
        mutableStateOf(
            if (prefs.getString("star_map_fov_mode", "SENSOR") == "EYEPIECE") {
                FovInstrumentMode.EYEPIECE
            } else {
                FovInstrumentMode.SENSOR
            }
        )
    }
    var selectedTelescopeId by remember {
        mutableStateOf(prefs.getString("star_map_telescope_id", "scope_80_500") ?: "scope_80_500")
    }
    var selectedEyepieceId by remember {
        mutableStateOf(prefs.getString("star_map_eyepiece_id", "ep_25_50") ?: "ep_25_50")
    }
    var selectedSensorId by remember {
        mutableStateOf(
            prefs.getString("star_map_sensor_id", OpticsEquipment.CONNECTED_SENSOR_ID)
                ?: OpticsEquipment.CONNECTED_SENSOR_ID
        )
    }
    var customTelescopeFl by remember {
        mutableStateOf(
            prefs.getFloat("plate_focal_length_mm", 500f).takeIf { it > 0f }?.let {
                "%.1f".format(Locale.US, it)
            } ?: (prefs.getString("star_map_custom_scope_fl", "500") ?: "500")
        )
    }
    var customEyepieceFl by remember {
        mutableStateOf(prefs.getString("star_map_custom_ep_fl", "25") ?: "25")
    }
    var customEyepieceAfov by remember {
        mutableStateOf(prefs.getString("star_map_custom_ep_afov", "50") ?: "50")
    }
    var customSensorPixelUm by remember {
        mutableStateOf(prefs.getString("star_map_custom_sensor_um", "3.75") ?: "3.75")
    }
    var customSensorWidth by remember {
        mutableStateOf(prefs.getString("star_map_custom_sensor_w", "1920") ?: "1920")
    }
    var customSensorHeight by remember {
        mutableStateOf(prefs.getString("star_map_custom_sensor_h", "1080") ?: "1080")
    }
    var showFovOverlay by remember {
        mutableStateOf(prefs.getBoolean("star_map_show_fov_overlay", true))
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
    val moveEnabled = mountConnected && !mountBusy

    val connectedSensorName = stringResource(R.string.connected_camera_sensor)
    val connectedSensor = remember(
        cameraPixelSizeUm,
        cameraFrameWidthPx,
        cameraFrameHeightPx,
        connectedSensorName
    ) {
        OpticsEquipment.connectedSensor(
            cameraPixelSizeUm,
            cameraFrameWidthPx,
            cameraFrameHeightPx,
            connectedSensorName
        )
    }
    val telescopes = OpticsEquipment.defaultTelescopes
    val eyepieces = OpticsEquipment.defaultEyepieces
    val sensors = remember(connectedSensor) {
        buildList {
            connectedSensor?.let { add(it) }
            addAll(OpticsEquipment.defaultSensors)
        }
    }
    LaunchedEffect(sensors, selectedSensorId) {
        if (sensors.none { it.id == selectedSensorId }) {
            selectedSensorId = sensors.firstOrNull()?.id
                ?: OpticsEquipment.defaultSensors.first().id
        }
    }

    val telescopeFl = remember(selectedTelescopeId, customTelescopeFl, telescopes) {
        resolveTelescopeFl(telescopes, selectedTelescopeId, customTelescopeFl)
    }
    val activeEyepiece = remember(
        selectedEyepieceId,
        customEyepieceFl,
        customEyepieceAfov,
        eyepieces
    ) {
        resolveEyepiece(eyepieces, selectedEyepieceId, customEyepieceFl, customEyepieceAfov)
    }
    val activeSensor = remember(
        selectedSensorId,
        customSensorPixelUm,
        customSensorWidth,
        customSensorHeight,
        sensors
    ) {
        resolveSensor(
            sensors,
            selectedSensorId,
            customSensorPixelUm,
            customSensorWidth,
            customSensorHeight
        )
    }
    val eyepieceComputation = remember(telescopeFl, activeEyepiece) {
        val fl = telescopeFl ?: return@remember null
        val ep = activeEyepiece ?: return@remember null
        OpticsEquipment.computeEyepiece(fl, ep)
    }
    val sensorComputation = remember(telescopeFl, activeSensor) {
        val fl = telescopeFl ?: return@remember null
        val sensor = activeSensor ?: return@remember null
        OpticsEquipment.computeSensor(fl, sensor)
    }
    val fovComputation = remember(fovMode, eyepieceComputation, sensorComputation) {
        when (fovMode) {
            FovInstrumentMode.EYEPIECE -> eyepieceComputation
            FovInstrumentMode.SENSOR -> sensorComputation
        }
    }

    fun evalStarMap(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    fun persistFovPrefs() {
        prefs.edit()
            .putBoolean("star_map_follow_mount", followMount)
            .putBoolean(HipsTileCache.PREFS_ONLINE_DSS, onlineDssEnabled)
            .putString(
                "star_map_fov_mode",
                if (fovMode == FovInstrumentMode.EYEPIECE) "EYEPIECE" else "SENSOR"
            )
            .putString("star_map_telescope_id", selectedTelescopeId)
            .putString("star_map_eyepiece_id", selectedEyepieceId)
            .putString("star_map_sensor_id", selectedSensorId)
            .putString("star_map_custom_scope_fl", customTelescopeFl)
            .putString("star_map_custom_ep_fl", customEyepieceFl)
            .putString("star_map_custom_ep_afov", customEyepieceAfov)
            .putString("star_map_custom_sensor_um", customSensorPixelUm)
            .putString("star_map_custom_sensor_w", customSensorWidth)
            .putString("star_map_custom_sensor_h", customSensorHeight)
            .putBoolean("star_map_show_fov_overlay", showFovOverlay)
            .putBoolean("star_map_equatorial_grid", equatorialGrid)
            .putBoolean("star_map_azimuthal_grid", azimuthalGrid)
            .putBoolean("star_map_meridian", meridianLine)
            .putBoolean("star_map_ecliptic", eclipticLine)
            .putBoolean("star_map_constellation_lines", constellationLines)
            .putBoolean("star_map_constellation_labels", constellationLabels)
            .putBoolean("star_map_constellation_bounds", constellationBounds)
            .putBoolean("star_map_star_labels", starHints)
            .apply()
    }

    fun applySkyAppearance() {
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.setSkyAppearance(" +
                "$equatorialGrid,$azimuthalGrid,$meridianLine,$eclipticLine," +
                "$constellationLines,$constellationLabels,$constellationBounds,$starHints);"
        )
    }

    fun persistImagingFocalLength(focalLengthMm: Double?) {
        focalLengthMm?.toFloat()?.takeIf { it > 0f }?.let {
            prefs.edit().putFloat("plate_focal_length_mm", it).apply()
        }
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

    fun applyFovOverlays(alsoZoom: Boolean) {
        StarMapFovOverlay.scripts(
            showOverlay = showFovOverlay,
            eyepieceFovDeg = eyepieceComputation?.circleDeg,
            sensorWidthDeg = sensorComputation?.rectWidthDeg,
            sensorHeightDeg = sensorComputation?.rectHeightDeg,
            alsoZoom = alsoZoom,
            zoomMode = fovMode
        ).forEach(::evalStarMap)
    }

    fun setFollowMountEnabled(enabled: Boolean) {
        followMount = enabled
        persistFovPrefs()
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.setFollowMount(${if (enabled) "true" else "false"});"
        )
        overlaysVisible = true
    }

    fun persistAndApplySkyAppearance() {
        persistFovPrefs()
        applySkyAppearance()
    }

    fun centerOnMount() {
        evalStarMap("window.MercStarMap && window.MercStarMap.centerOnMount(1);")
        overlaysVisible = true
    }

    fun centerOnTarget() {
        evalStarMap("window.MercStarMap && window.MercStarMap.centerOnSelection(1);")
        overlaysVisible = true
    }

    fun centerOnRaDec(raHours: Double, decDegrees: Double, frame: String = "JNOW") {
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.centerOnRaDec(" +
                "%.8f,%.8f,1,'%s');".format(Locale.US, raHours, decDegrees, frame)
        )
        overlaysVisible = true
    }

    fun gotoCatalogObject(obj: CatalogObject) {
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
        selectedTarget = null
        targetExpanded = false
        cornerPanel = StarMapCornerPanel.NONE
        engineState = StarMapEngineState.Loading
        overlaysVisible = true
        webView = null
        webViewSession++
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
                selectedTarget = it
                targetExpanded = false
                overlaysVisible = true
            },
            onFollowChanged = { enabled -> setFollowMountEnabled(enabled) },
            fallbackTargetName = context.getString(R.string.selected_target),
            parseError = { context.getString(R.string.star_target_parse_error, it.message.orEmpty()) }
        )
    }

    BackHandler(onBack = onBack)

    LaunchedEffect(webViewSession, engineState) {
        if (engineState !is StarMapEngineState.Loading) return@LaunchedEffect
        delay(StarMapLoadRules.READY_TIMEOUT_MS)
        val timeoutMessage = context.getString(R.string.star_map_load_timeout)
        engineState = StarMapLoadRules.acceptTimeout(engineState, timeoutMessage)
        if (engineState is StarMapEngineState.Error) overlaysVisible = true
    }

    LaunchedEffect(
        overlaysVisible,
        overlaysLocked,
        engineState,
        selectedTarget,
        gotoConfirmation,
        syncConfirmation,
        precisionConfirmation,
        precisionGotoProgress.isActive,
        searchDialogVisible,
        cornerPanel,
        fovDialogVisible,
        confirmHome
    ) {
        if (!overlaysVisible ||
            overlaysLocked ||
            engineState !is StarMapEngineState.Ready ||
            gotoConfirmation != null ||
            syncConfirmation != null ||
            precisionConfirmation != null ||
            precisionGotoProgress.isActive ||
            searchDialogVisible ||
            cornerPanel != StarMapCornerPanel.NONE ||
            fovDialogVisible ||
            confirmHome
        ) {
            return@LaunchedEffect
        }
        delay(StarMapLoadRules.OVERLAY_IDLE_MS)
        overlaysVisible = false
    }

    LaunchedEffect(webView, mountSite, engineState) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        val site = mountSite ?: return@LaunchedEffect
        val script = "window.MercStarMap && window.MercStarMap.setObserver(" +
            "${site.latitudeDeg},${site.longitudeDeg},${System.currentTimeMillis()});"
        webView?.evaluateJavascript(script, null)
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

    LaunchedEffect(
        webView,
        engineState,
        followMount,
        showFovOverlay,
        eyepieceComputation,
        sensorComputation,
        fovComputation,
        fovMode,
        selectedTelescopeId,
        selectedEyepieceId,
        selectedSensorId,
        customTelescopeFl,
        customEyepieceFl,
        customEyepieceAfov,
        customSensorPixelUm,
        customSensorWidth,
        customSensorHeight,
        cameraPixelSizeUm,
        cameraFrameWidthPx,
        cameraFrameHeightPx
    ) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.setFollowMount(${if (followMount) "true" else "false"});"
        )
        // Zoom once when the sheet is open so the frame fills the view; otherwise
        // only redraw overlays so pan/zoom the user already set stays put.
        applyFovOverlays(alsoZoom = fovDialogVisible)
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
        centerOnRaDec(ra, dec)
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
        if (!overlaysVisible) cornerPanel = StarMapCornerPanel.NONE
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                                        "https://appassets.androidplatform.net/assets/stellarium/index.html"
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
                modifier = Modifier.fillMaxSize()
            )
        }

        if (!overlaysVisible && engineState is StarMapEngineState.Ready) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures {
                            overlaysVisible = true
                        }
                    }
                    .semantics {
                        contentDescription =
                            context.getString(R.string.show_star_map_controls)
                    }
            )
        }

        AnimatedVisibility(
            visible = overlaysVisible || engineState !is StarMapEngineState.Ready,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            Card {
                StarMapBackButton(onBack = onBack)
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
                    Card(modifier = Modifier.padding(8.dp)) {
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
            visible = overlaysVisible && engineState is StarMapEngineState.Ready,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            StarMapCornerControls(
                panel = cornerPanel,
                onPanelChange = {
                    cornerPanel = it
                    overlaysVisible = true
                    if (it == StarMapCornerPanel.SKY) refreshHipsCacheLabel()
                },
                mountConnected = mountConnected,
                moveEnabled = moveEnabled,
                mountSlewRate = mountSlewRate,
                followMount = followMount,
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
                redNightMode = redNightMode,
                onlineDssEnabled = onlineDssEnabled,
                overlaysLocked = overlaysLocked,
                hipsCacheSizeLabel = hipsCacheSizeLabel,
                onSlewRateChange = onSlewRateChange,
                onManualMoveStart = onManualMoveStart,
                onManualMoveStop = onManualMoveStop,
                onStopMount = onStopMount,
                onConfirmHome = { confirmHome = true },
                onFollowMountChange = ::setFollowMountEnabled,
                onCenterOnMount = ::centerOnMount,
                onOpenFov = {
                    fovDialogVisible = true
                    overlaysVisible = true
                },
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
                onRedNightModeChange = onRedNightModeChange,
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
        }

        AnimatedVisibility(
            visible = overlaysVisible && engineState is StarMapEngineState.Ready,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomEnd)
        ) {
            val target = selectedTarget
            if (target == null) {
                Card(modifier = Modifier.padding(8.dp)) {
                    Column(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            stringResource(R.string.select_celestial_target),
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (mountConnected && mountCoordinates != null) {
                            OutlinedButton(onClick = ::centerOnMount) {
                                Text(stringResource(R.string.center_on_mount))
                            }
                        }
                    }
                }
            } else {
                Card(
                    modifier = Modifier
                        .padding(8.dp)
                        .widthIn(max = 300.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            target.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { targetExpanded = !targetExpanded }
                                .semantics {
                                    contentDescription =
                                        context.getString(R.string.expand_target_details)
                                }
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                enabled = mountConnected && !mountBusy,
                                onClick = {
                                    overlaysVisible = true
                                    precisionConfirmation = target
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.precision_goto_label))
                            }
                            Button(
                                enabled = mountConnected && !mountBusy,
                                onClick = {
                                    overlaysVisible = true
                                    gotoConfirmation = target
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.goto_label))
                            }
                        }
                        if (precisionGotoProgress.isActive ||
                            precisionGotoProgress.phase == PrecisionGotoPhase.SUCCEEDED ||
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
                        if (targetExpanded) {
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
                                TextButton(
                                    enabled = mountConnected && !mountBusy,
                                    onClick = {
                                        overlaysVisible = true
                                        syncConfirmation = target
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.sync_label))
                                }
                            }
                            if (mountConnected && mountCoordinates != null) {
                                TextButton(onClick = ::centerOnMount) {
                                    Text(stringResource(R.string.center_on_mount))
                                }
                            }
                            if (!mountConnected) {
                                Text(
                                    stringResource(R.string.connect_mount_for_goto),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    gotoConfirmation?.let { target ->
        StarMapGotoConfirmation(
            targetName = target.name,
            coordinates = target.coordinatesText(),
            onConfirm = {
                gotoConfirmation = null
                onGoto(target)
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
                onSync(target)
            },
            onDismiss = { syncConfirmation = null }
        )
    }

    precisionConfirmation?.let { target ->
        StarMapPrecisionGotoConfirmation(
            targetName = target.name,
            coordinates = target.coordinatesText(),
            onConfirm = {
                precisionConfirmation = null
                onPrecisionGoto(target)
            },
            onDismiss = { precisionConfirmation = null }
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
                    onGoHome()
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

    if (fovDialogVisible) {
        StarMapFovSheet(
            mode = fovMode,
            telescopes = telescopes,
            eyepieces = eyepieces,
            sensors = sensors,
            selectedTelescopeId = selectedTelescopeId,
            selectedEyepieceId = selectedEyepieceId,
            selectedSensorId = selectedSensorId,
            customTelescopeFl = customTelescopeFl,
            customEyepieceFl = customEyepieceFl,
            customEyepieceAfov = customEyepieceAfov,
            customSensorPixelUm = customSensorPixelUm,
            customSensorWidth = customSensorWidth,
            customSensorHeight = customSensorHeight,
            showOverlay = showFovOverlay,
            computation = fovComputation,
            onModeChange = {
                fovMode = it
                showFovOverlay = true
                persistFovPrefs()
            },
            onTelescopeSelected = {
                selectedTelescopeId = it
                showFovOverlay = true
                persistFovPrefs()
                persistImagingFocalLength(resolveTelescopeFl(telescopes, it, customTelescopeFl))
            },
            onEyepieceSelected = {
                selectedEyepieceId = it
                showFovOverlay = true
                persistFovPrefs()
            },
            onSensorSelected = {
                selectedSensorId = it
                showFovOverlay = true
                persistFovPrefs()
            },
            onCustomTelescopeFl = {
                customTelescopeFl = it
                persistFovPrefs()
                persistImagingFocalLength(resolveTelescopeFl(telescopes, selectedTelescopeId, it))
            },
            onCustomEyepieceFl = {
                customEyepieceFl = it
                persistFovPrefs()
            },
            onCustomEyepieceAfov = {
                customEyepieceAfov = it
                persistFovPrefs()
            },
            onCustomSensorPixelUm = {
                customSensorPixelUm = it
                persistFovPrefs()
            },
            onCustomSensorWidth = {
                customSensorWidth = it
                persistFovPrefs()
            },
            onCustomSensorHeight = {
                customSensorHeight = it
                persistFovPrefs()
            },
            onShowOverlayChange = {
                showFovOverlay = it
                persistFovPrefs()
            },
            onDismiss = {
                persistFovPrefs()
                applyFovOverlays(alsoZoom = false)
                fovDialogVisible = false
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
            .semantics { this.contentDescription = contentDescription }
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
