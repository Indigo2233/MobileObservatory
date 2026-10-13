package com.indigo.mobileobservatory.ui.screens

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.*

/** Same optical setup editor for the native map; no dependency on the Stellarium engine. */
@Composable
internal fun PointingOpticsConfigurationSheet(
    primaryGeometry: ConnectedOpticsGeometry?,
    secondaryGeometry: ConnectedOpticsGeometry?,
    onOpticalPathInvalidated: (OpticsTrainId, PointingOpticsChange) -> Unit,
    onDismiss: () -> Unit,
    opticsPreferencesRevision: Long = 0L,
    initialTrain: OpticsTrainId? = null,
    terminal: PointingOpticsEndpoint? = null,
    restrictToTrain: Boolean = false,
    onConfirmed: (PointingOpticsConfiguration) -> Unit = {}
) {
    BackHandler(enabled = rememberPageForeground(), onBack = onDismiss)
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE) }
    val connectedLabel = stringResource(R.string.connected_camera_sensor)
    var configuration by remember { mutableStateOf(PointingOpticsConfiguration.load(
        AndroidOpticsPrefs(prefs), primaryGeometry, secondaryGeometry, connectedLabel)) }
    var editing by rememberSaveable { mutableStateOf(initialTrain ?: StarMapOpticsPrefs.loadActive(AndroidOpticsPrefs(prefs))) }
    var previous by remember { mutableStateOf(configuration) }
    LaunchedEffect(opticsPreferencesRevision) {
        configuration = PointingOpticsConfiguration.load(AndroidOpticsPrefs(prefs), primaryGeometry,
            secondaryGeometry, connectedLabel)
    }
    LaunchedEffect(primaryGeometry, secondaryGeometry, connectedLabel) {
        configuration = configuration.copy(primaryGeometry = primaryGeometry,
            secondaryGeometry = secondaryGeometry, connectedDisplayName = connectedLabel)
    }
    LaunchedEffect(configuration) {
        OpticsTrainId.entries.forEach { id ->
            previous.changeTo(configuration, id)?.let { onOpticalPathInvalidated(id, it) }
        }
        previous = configuration
    }
    fun persist(next: PointingOpticsConfiguration) {
        val write = StarMapOpticsPrefs.snapshot(next.primary, next.secondary,
            StarMapOpticsPrefs.loadActive(AndroidOpticsPrefs(prefs)),
            prefs.getBoolean(StarMapOpticsPrefs.SHOW_OVERLAY, true))
        val editor = prefs.edit()
        write.strings.forEach { (key, value) -> editor.putString(key, value) }
        write.bools.forEach { (key, value) -> editor.putBoolean(key, value) }
        editor.putString(UserOpticsCatalog.TELESCOPES_KEY, UserOpticsCatalog.formatTelescopes(next.telescopes))
            .putString(UserOpticsCatalog.EYEPIECES_KEY, UserOpticsCatalog.formatEyepieces(next.eyepieces))
            .putString(UserOpticsCatalog.CAMERAS_KEY, UserOpticsCatalog.formatCameras(next.cameras))
        OpticsTrainId.entries.forEach { editor.putBoolean(PointingOpticsConfiguration.confirmationKey(it), it in next.confirmedTrains) }
        val oldGuide = configuration.secondary; val newGuide = next.secondary
        if (editing == OpticsTrainId.SECONDARY && (StarMapOpticsPrefs.shouldWritePlateFocalLength(oldGuide, newGuide) ||
            newGuide.mode == FovInstrumentMode.SENSOR &&
            oldGuide.focalLengthMm(configuration.telescopes) != newGuide.focalLengthMm(next.telescopes))) {
            newGuide.focalLengthMm(next.telescopes)
                ?.toFloat()?.takeIf { it.isFinite() && it > 0 }?.let { editor.putFloat(StarMapOpticsPrefs.PLATE_FOCAL_LENGTH_MM, it) }
        }
        editor.apply()
    }
    fun update(next: PointingOpticsConfiguration) {
        val changed = OpticsTrainId.entries.filter {
            configuration.train(it).copy(telescopeFocalLengthOverrideMm = next.train(it).telescopeFocalLengthOverrideMm) != next.train(it) ||
                configuration.computation(it) != next.computation(it) ||
                configuration.changeTo(next, it) != null
        }.toSet()
        val updated = next.copy(confirmedTrains = next.confirmedTrains - changed)
        persist(updated)
        OpticsTrainId.entries.forEach { id -> previous.changeTo(updated, id)?.let { onOpticalPathInvalidated(id, it) } }
        previous = updated
        configuration = updated
    }
    val selectedTrain = configuration.train(editing)
    val train = if (terminal == null) selectedTrain else selectedTrain.copy(mode =
        if (terminal == PointingOpticsEndpoint.EYEPIECE) FovInstrumentMode.EYEPIECE else FovInstrumentMode.SENSOR)
    val displayedConfiguration = if (editing == OpticsTrainId.PRIMARY) configuration.copy(primary = train) else configuration.copy(secondary = train)
    StarMapFovSheet(editingTrain = editing, config = train, telescopes = configuration.telescopes,
        eyepieces = configuration.eyepieces, sensors = configuration.sensorsFor(editing),
        showOverlay = false, computation = displayedConfiguration.computation(editing),
        mosaicConfig = StarMapMosaicConfig(), positionAngleText = "0",
        onEditingTrainChange = { if (!restrictToTrain) editing = it },
        onConfigChange = {
            val before = configuration.train(it.id)
            val configured = it.copy(telescopeFocalLengthOverrideMm =
                if (before.telescopeId != it.telescopeId || before.customTelescopeFl != it.customTelescopeFl)
                    it.customTelescopeFl.toDoubleOrNull() else before.telescopeFocalLengthOverrideMm)
            update(if (it.id == OpticsTrainId.PRIMARY) configuration.copy(primary = configured) else configuration.copy(secondary = configured))
        },
        onMosaicConfigChange = {}, onPositionAngleChange = {}, onShowOverlayChange = {},
        onTelescopesChange = { updated ->
            fun freezeFocal(train: OpticsTrainConfig): OpticsTrainConfig {
                val old = configuration.telescopes.firstOrNull { it.id == train.telescopeId }
                val next = updated.firstOrNull { it.id == train.telescopeId }
                return if (old?.focalLengthMm != next?.focalLengthMm)
                    train.copy(telescopeFocalLengthOverrideMm = train.focalLengthMm(configuration.telescopes)) else train
            }
            update(configuration.copy(primary = freezeFocal(configuration.primary),
                secondary = freezeFocal(configuration.secondary), telescopes = updated))
        },
        onEyepiecesChange = { update(configuration.copy(eyepieces = it)) },
        onCamerasChange = { update(configuration.copy(cameras = it)) },
        onDismiss = onDismiss,
        configurationConfirmed = editing in configuration.confirmedTrains && train.mode == selectedTrain.mode,
        onConfirmConfiguration = {
            val next = displayedConfiguration.copy(confirmedTrains = configuration.confirmedTrains + editing)
            persist(next)
            // The calling task may close this sheet immediately, before an effect can run.
            OpticsTrainId.entries.forEach { id -> previous.changeTo(next, id)?.let { onOpticalPathInvalidated(id, it) } }
            previous = next
            configuration = next
            onConfirmed(next)
        },
        showFramingControls = false, showOverlaySwitch = false,
        showTrainSelector = !restrictToTrain, showEndpointSelector = terminal == null,
        onInvalidConfigurationDraft = {
            val next = configuration.copy(confirmedTrains = configuration.confirmedTrains - editing)
            persist(next)
            configuration = next
        }
    )
}
