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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.webkit.WebViewAssetLoader
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.FovInstrumentMode
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
    var settingsExpanded by remember { mutableStateOf(false) }
    var searchDialogVisible by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf<List<CatalogObject>>(emptyList()) }
    var directionPadExpanded by remember { mutableStateOf(false) }
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
        mutableStateOf(prefs.getBoolean("star_map_follow_mount", true))
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
    val fovComputation = remember(fovMode, telescopeFl, activeEyepiece, activeSensor) {
        val fl = telescopeFl ?: return@remember null
        when (fovMode) {
            FovInstrumentMode.EYEPIECE -> {
                val ep = activeEyepiece ?: return@remember null
                OpticsEquipment.computeEyepiece(fl, ep)
            }
            FovInstrumentMode.SENSOR -> {
                val sensor = activeSensor ?: return@remember null
                OpticsEquipment.computeSensor(fl, sensor)
            }
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
            .apply()
        customTelescopeFl.toFloatOrNull()?.takeIf { it > 0f }?.let {
            prefs.edit().putFloat("plate_focal_length_mm", it).apply()
        }
        telescopeFl?.toFloat()?.takeIf { it > 0f }?.let {
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
        if (!showFovOverlay || fovComputation == null || !fovComputation.hasOverlay) {
            evalStarMap("window.MercStarMap && window.MercStarMap.clearSensorFovOverlay();")
            evalStarMap("window.MercStarMap && window.MercStarMap.clearEyepieceFovOverlay();")
            return
        }
        when (fovComputation.mode) {
            FovInstrumentMode.EYEPIECE -> {
                val fov = fovComputation.circleDeg!!
                evalStarMap("window.MercStarMap && window.MercStarMap.clearSensorFovOverlay();")
                evalStarMap(
                    "window.MercStarMap && window.MercStarMap.setEyepieceFovOverlay($fov,$alsoZoom);"
                )
            }
            FovInstrumentMode.SENSOR -> {
                val w = fovComputation.rectWidthDeg!!
                val h = fovComputation.rectHeightDeg!!
                evalStarMap("window.MercStarMap && window.MercStarMap.clearEyepieceFovOverlay();")
                evalStarMap(
                    "window.MercStarMap && window.MercStarMap.setSensorFovOverlay($w,$h,$alsoZoom);"
                )
            }
        }
    }

    fun setFollowMountEnabled(enabled: Boolean) {
        followMount = enabled
        persistFovPrefs()
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.setFollowMount(${if (enabled) "true" else "false"});"
        )
        overlaysVisible = true
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

    LaunchedEffect(mountConnected) {
        if (!mountConnected) directionPadExpanded = false
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
        settingsExpanded = false
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
        settingsExpanded,
        searchDialogVisible,
        directionPadExpanded,
        fovDialogVisible
    ) {
        if (!overlaysVisible ||
            overlaysLocked ||
            engineState !is StarMapEngineState.Ready ||
            gotoConfirmation != null ||
            syncConfirmation != null ||
            precisionConfirmation != null ||
            precisionGotoProgress.isActive ||
            settingsExpanded ||
            searchDialogVisible ||
            directionPadExpanded ||
            fovDialogVisible
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

        Card(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
        ) {
            StarMapBackButton(onBack = onBack)
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
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
                            Box {
                            IconButton(
                                onClick = {
                                    refreshHipsCacheLabel()
                                    settingsExpanded = true
                                },
                                modifier = Modifier.semantics {
                                    contentDescription =
                                        context.getString(R.string.star_map_settings)
                                }
                            ) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.star_map_settings)
                                )
                            }
                            DropdownMenu(
                                expanded = settingsExpanded,
                                onDismissRequest = { settingsExpanded = false }
                            ) {
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(stringResource(R.string.atmosphere))
                                            Switch(
                                                checked = atmosphereVisible,
                                                onCheckedChange = { atmosphereVisible = it }
                                            )
                                        }
                                    },
                                    onClick = { atmosphereVisible = !atmosphereVisible }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                                            ) {
                                                Text(stringResource(R.string.online_dss_survey))
                                                Switch(
                                                    checked = onlineDssEnabled,
                                                    onCheckedChange = ::setOnlineDssEnabled
                                                )
                                            }
                                            Text(
                                                stringResource(R.string.online_dss_survey_hint),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    },
                                    onClick = { setOnlineDssEnabled(!onlineDssEnabled) }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                R.string.hips_cache_size,
                                                hipsCacheSizeLabel
                                            )
                                        )
                                    },
                                    onClick = { refreshHipsCacheLabel() },
                                    trailingIcon = {
                                        TextButton(
                                            onClick = {
                                                hipsCache.clearCache()
                                                refreshHipsCacheLabel()
                                            }
                                        ) {
                                            Text(stringResource(R.string.clear_hips_cache))
                                        }
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(stringResource(R.string.lock_star_map_overlays))
                                            Switch(
                                                checked = overlaysLocked,
                                                onCheckedChange = {
                                                    overlaysLocked = it
                                                    if (it) overlaysVisible = true
                                                }
                                            )
                                        }
                                    },
                                    onClick = {
                                        overlaysLocked = !overlaysLocked
                                        if (overlaysLocked) overlaysVisible = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Text(stringResource(R.string.follow_mount_pointing))
                                            Switch(
                                                checked = followMount,
                                                onCheckedChange = { setFollowMountEnabled(it) }
                                            )
                                        }
                                    },
                                    onClick = { setFollowMountEnabled(!followMount) },
                                    enabled = mountConnected
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.star_map_fov)) },
                                    onClick = {
                                        settingsExpanded = false
                                        fovDialogVisible = true
                                        overlaysVisible = true
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.center_on_mount)) },
                                    enabled = mountConnected && mountCoordinates != null,
                                    onClick = {
                                        settingsExpanded = false
                                        centerOnMount()
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.reload_star_map)) },
                                    onClick = {
                                        settingsExpanded = false
                                        reloadStarMap()
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Refresh, contentDescription = null)
                                    }
                                )
                            }
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
            visible = overlaysVisible && engineState is StarMapEngineState.Ready,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomStart)
        ) {
            Column(
                modifier = Modifier.padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (directionPadExpanded && mountConnected) {
                    Card(modifier = Modifier.widthIn(max = 280.dp)) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                MountSlewRate.entries.forEach { rate ->
                                    FilterChip(
                                        selected = rate == mountSlewRate,
                                        onClick = { onSlewRateChange(rate) },
                                        enabled = moveEnabled,
                                        label = {
                                            Text(rate.label, fontSize = 10.sp)
                                        },
                                        modifier = Modifier.height(28.dp)
                                    )
                                }
                            }
                            StarMapMountDirectionButton(
                                label = "N",
                                contentDescription = stringResource(R.string.move_north),
                                direction = MountDirection.NORTH,
                                enabled = moveEnabled,
                                onMoveStart = onManualMoveStart,
                                onMoveStop = onManualMoveStop
                            )
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StarMapMountDirectionButton(
                                    label = "W",
                                    contentDescription = stringResource(R.string.move_west),
                                    direction = MountDirection.WEST,
                                    enabled = moveEnabled,
                                    onMoveStart = onManualMoveStart,
                                    onMoveStop = onManualMoveStop
                                )
                                FilledTonalButton(
                                    onClick = onStopMount,
                                    enabled = mountConnected,
                                    modifier = Modifier.size(width = 64.dp, height = 44.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Stop,
                                        contentDescription = stringResource(R.string.stop_mount)
                                    )
                                }
                                StarMapMountDirectionButton(
                                    label = "E",
                                    contentDescription = stringResource(R.string.move_east),
                                    direction = MountDirection.EAST,
                                    enabled = moveEnabled,
                                    onMoveStart = onManualMoveStart,
                                    onMoveStop = onManualMoveStop
                                )
                            }
                            StarMapMountDirectionButton(
                                label = "S",
                                contentDescription = stringResource(R.string.move_south),
                                direction = MountDirection.SOUTH,
                                enabled = moveEnabled,
                                onMoveStart = onManualMoveStart,
                                onMoveStop = onManualMoveStop
                            )
                        }
                    }
                }
                Card {
                    IconButton(
                        onClick = {
                            if (!mountConnected) return@IconButton
                            directionPadExpanded = !directionPadExpanded
                            if (directionPadExpanded) overlaysVisible = true
                        },
                        enabled = mountConnected,
                        modifier = Modifier.semantics {
                            contentDescription = context.getString(
                                if (directionPadExpanded) {
                                    R.string.close_mount_direction_pad
                                } else {
                                    R.string.open_mount_direction_pad
                                }
                            )
                        }
                    ) {
                        Icon(
                            Icons.Default.OpenWith,
                            contentDescription = null,
                            tint = if (mountConnected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                }
                if (!mountConnected) {
                    Text(
                        stringResource(R.string.connect_mount_for_manual_move),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.padding(horizontal = 4.dp)
                    )
                }
            }
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
                            } else {
                                Text(
                                    stringResource(R.string.sync_hint),
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
private fun StarMapMountDirectionButton(
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
