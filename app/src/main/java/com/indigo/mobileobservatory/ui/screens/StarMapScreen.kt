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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.DisposableEffect
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
import com.indigo.mobileobservatory.astro.OpticsFov
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountSite
import com.indigo.mobileobservatory.mount.MountSlewRate
import com.indigo.mobileobservatory.mount.PrecisionGotoPhase
import com.indigo.mobileobservatory.mount.PrecisionGotoProgress
import com.indigo.mobileobservatory.ui.components.StarMapBackButton
import com.indigo.mobileobservatory.ui.components.StarMapGotoConfirmation
import com.indigo.mobileobservatory.ui.components.StarMapPrecisionGotoConfirmation
import com.indigo.mobileobservatory.ui.components.StarMapSyncConfirmation
import kotlinx.coroutines.delay
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
    var directionPadExpanded by remember { mutableStateOf(false) }
    var fovDialogVisible by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("mobile_observatory", android.content.Context.MODE_PRIVATE)
    }
    var followMount by remember {
        mutableStateOf(prefs.getBoolean("star_map_follow_mount", true))
    }
    var eyepieceFovText by remember {
        mutableStateOf(prefs.getString("star_map_eyepiece_fov", "1.0") ?: "1.0")
    }
    var sensorFovWidthText by remember {
        mutableStateOf(prefs.getString("star_map_sensor_fov_w", "1.2") ?: "1.2")
    }
    var sensorFovHeightText by remember {
        mutableStateOf(prefs.getString("star_map_sensor_fov_h", "0.8") ?: "0.8")
    }
    var showSensorFov by remember {
        mutableStateOf(prefs.getBoolean("star_map_show_sensor_fov", true))
    }
    var showEyepieceFov by remember {
        mutableStateOf(prefs.getBoolean("star_map_show_eyepiece_fov", false))
    }
    var focalLengthText by remember {
        mutableStateOf(
            prefs.getFloat("plate_focal_length_mm", 0f).takeIf { it > 0f }?.let {
                "%.1f".format(Locale.US, it)
            } ?: ""
        )
    }
    var useCameraFov by remember {
        mutableStateOf(prefs.getBoolean("star_map_use_camera_fov", true))
    }
    val moveEnabled = mountConnected && !mountBusy

    val cameraComputedFov = remember(
        cameraPixelSizeUm,
        cameraFrameWidthPx,
        cameraFrameHeightPx,
        focalLengthText
    ) {
        val px = cameraPixelSizeUm?.toDouble() ?: return@remember null
        val fl = focalLengthText.toDoubleOrNull() ?: return@remember null
        OpticsFov.rectangleDegrees(px, fl, cameraFrameWidthPx, cameraFrameHeightPx)
    }

    fun evalStarMap(script: String) {
        webView?.evaluateJavascript(script, null)
    }

    fun persistFovPrefs() {
        prefs.edit()
            .putBoolean("star_map_follow_mount", followMount)
            .putString("star_map_eyepiece_fov", eyepieceFovText)
            .putString("star_map_sensor_fov_w", sensorFovWidthText)
            .putString("star_map_sensor_fov_h", sensorFovHeightText)
            .putBoolean("star_map_show_sensor_fov", showSensorFov)
            .putBoolean("star_map_show_eyepiece_fov", showEyepieceFov)
            .putBoolean("star_map_use_camera_fov", useCameraFov)
            .apply()
        focalLengthText.toFloatOrNull()?.takeIf { it > 0f }?.let {
            prefs.edit().putFloat("plate_focal_length_mm", it).apply()
        }
    }

    fun applyCameraFovToFields(): Boolean {
        val fov = cameraComputedFov ?: return false
        sensorFovWidthText = "%.3f".format(Locale.US, fov.first)
        sensorFovHeightText = "%.3f".format(Locale.US, fov.second)
        showSensorFov = true
        useCameraFov = true
        persistFovPrefs()
        return true
    }

    fun applyFovOverlays(alsoZoom: Boolean) {
        if (useCameraFov) {
            applyCameraFovToFields()
        }
        if (showSensorFov) {
            val w = sensorFovWidthText.toDoubleOrNull()
            val h = sensorFovHeightText.toDoubleOrNull()
            if (w != null && h != null && w > 0 && h > 0) {
                evalStarMap(
                    "window.MercStarMap && window.MercStarMap.setSensorFovOverlay($w,$h,$alsoZoom);"
                )
            }
        } else {
            evalStarMap("window.MercStarMap && window.MercStarMap.clearSensorFovOverlay();")
        }
        if (showEyepieceFov) {
            eyepieceFovText.toDoubleOrNull()?.takeIf { it > 0 }?.let { fov ->
                evalStarMap(
                    "window.MercStarMap && window.MercStarMap.setEyepieceFovOverlay($fov,$alsoZoom);"
                )
            }
        } else {
            evalStarMap("window.MercStarMap && window.MercStarMap.clearEyepieceFovOverlay();")
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

    fun centerOnRaDec(raHours: Double, decDegrees: Double) {
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.centerOnRaDec(" +
                "%.8f,%.8f,1);".format(Locale.US, raHours, decDegrees)
        )
        overlaysVisible = true
    }

    LaunchedEffect(mountConnected) {
        if (!mountConnected) directionPadExpanded = false
    }

    fun destroyWebView(current: WebView?) {
        current?.apply {
            removeJavascriptInterface("AndroidStarMap")
            stopLoading()
            destroy()
        }
    }

    fun reloadStarMap() {
        destroyWebView(webView)
        webView = null
        selectedTarget = null
        targetExpanded = false
        engineState = StarMapEngineState.Loading
        overlaysVisible = true
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

    DisposableEffect(webViewSession) {
        onDispose {
            destroyWebView(webView)
            webView = null
        }
    }

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
        showSensorFov,
        showEyepieceFov,
        useCameraFov,
        cameraComputedFov,
        cameraPixelSizeUm,
        cameraFrameWidthPx,
        cameraFrameHeightPx,
        focalLengthText
    ) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        evalStarMap(
            "window.MercStarMap && window.MercStarMap.setFollowMount(${if (followMount) "true" else "false"});"
        )
        applyFovOverlays(alsoZoom = false)
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

    LaunchedEffect(webView, atmosphereVisible, engineState) {
        if (engineState !is StarMapEngineState.Ready) return@LaunchedEffect
        val script =
            "window.MercStarMap && window.MercStarMap.setAtmosphereVisible($atmosphereVisible);"
        webView?.evaluateJavascript(script, null)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        key(webViewSession) {
            AndroidView(
                factory = { viewContext ->
                    val assetLoader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(viewContext))
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
                                destroyWebView(view)
                                if (webView === view) webView = null
                                return true
                            }
                        }
                        webView = this
                        var pageLoaded = false
                        doOnLayout { laidOutView ->
                            if (!pageLoaded && laidOutView.isAttachedToWindow) {
                                pageLoaded = true
                                laidOutView.post {
                                    loadUrl(
                                        "https://appassets.androidplatform.net/assets/stellarium/index.html"
                                    )
                                }
                            }
                        }
                    }
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
                        Box {
                            IconButton(
                                onClick = { settingsExpanded = true },
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

    if (fovDialogVisible) {
        AlertDialog(
            onDismissRequest = { fovDialogVisible = false },
            title = { Text(stringResource(R.string.star_map_fov)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.fov_hint),
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = focalLengthText,
                        onValueChange = { focalLengthText = it },
                        label = { Text(stringResource(R.string.focal_length_mm)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    val computed = cameraComputedFov
                    if (computed != null) {
                        Text(
                            stringResource(
                                R.string.camera_fov_computed,
                                computed.first,
                                computed.second
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            stringResource(R.string.camera_fov_need_params),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    OutlinedButton(
                        onClick = {
                            if (applyCameraFovToFields()) {
                                applyFovOverlays(alsoZoom = true)
                            }
                        },
                        enabled = cameraComputedFov != null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.use_camera_fov))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(
                            checked = useCameraFov,
                            onCheckedChange = {
                                useCameraFov = it
                                persistFovPrefs()
                                if (it) applyCameraFovToFields()
                            },
                            enabled = cameraComputedFov != null
                        )
                        Text(stringResource(R.string.auto_camera_fov))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(
                            checked = showEyepieceFov,
                            onCheckedChange = {
                                showEyepieceFov = it
                                persistFovPrefs()
                            }
                        )
                        Text(stringResource(R.string.show_eyepiece_fov))
                    }
                    OutlinedTextField(
                        value = eyepieceFovText,
                        onValueChange = { eyepieceFovText = it },
                        label = { Text(stringResource(R.string.eyepiece_fov_degrees)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            showEyepieceFov = true
                            persistFovPrefs()
                            applyFovOverlays(alsoZoom = true)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.apply_eyepiece_fov))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Switch(
                            checked = showSensorFov,
                            onCheckedChange = {
                                showSensorFov = it
                                persistFovPrefs()
                            }
                        )
                        Text(stringResource(R.string.show_sensor_fov))
                    }
                    OutlinedTextField(
                        value = sensorFovWidthText,
                        onValueChange = { sensorFovWidthText = it },
                        label = { Text(stringResource(R.string.sensor_fov_width)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = sensorFovHeightText,
                        onValueChange = { sensorFovHeightText = it },
                        label = { Text(stringResource(R.string.sensor_fov_height)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = {
                            showSensorFov = true
                            persistFovPrefs()
                            applyFovOverlays(alsoZoom = true)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.apply_sensor_fov))
                    }
                    TextButton(
                        onClick = {
                            showSensorFov = false
                            showEyepieceFov = false
                            persistFovPrefs()
                            evalStarMap(
                                "window.MercStarMap && window.MercStarMap.clearSensorFovOverlay();"
                            )
                            evalStarMap(
                                "window.MercStarMap && window.MercStarMap.clearEyepieceFovOverlay();"
                            )
                        }
                    ) {
                        Text(stringResource(R.string.clear_sensor_fov))
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        persistFovPrefs()
                        applyFovOverlays(alsoZoom = false)
                        fovDialogVisible = false
                    }
                ) {
                    Text(stringResource(R.string.close))
                }
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
