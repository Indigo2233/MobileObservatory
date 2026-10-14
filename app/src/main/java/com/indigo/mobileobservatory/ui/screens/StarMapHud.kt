@file:OptIn(ExperimentalMaterial3Api::class)

package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.PlainTooltipBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.OpticsTrainId
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountMotionState
import com.indigo.mobileobservatory.mount.MountSlewRate

internal enum class StarMapCornerPanel { NONE, OBSERVING, SKY }

/** Give the whole panel a touch surface, including padding between its controls. */
internal fun Modifier.consumeStarMapPanelTouches(): Modifier = pointerInput(Unit) {
    detectTapGestures(onTap = {})
}

/**
 * Bottom-left chrome: two panel buttons and a compact HUD pin. One opens the
 * observing tools (mount pad, follow, FOV); the other opens sky drawing
 * (grids, constellations, labels). All hide with the rest of the HUD.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StarMapCornerControls(
    modifier: Modifier = Modifier,
    maxPanelHeight: Dp = 420.dp,
    panel: StarMapCornerPanel,
    onPanelChange: (StarMapCornerPanel) -> Unit,
    mountConnected: Boolean,
    followAvailable: Boolean,
    pointingSourceCaption: String,
    canManualMove: Boolean,
    canHome: Boolean,
    homeEnabled: Boolean,
    moveEnabled: Boolean,
    mountSlewRate: MountSlewRate,
    followMount: Boolean,
    followPhone: Boolean,
    phoneFollowAvailable: Boolean,
    phoneFollowCaption: String,
    activeTrain: OpticsTrainId,
    showFovOverlay: Boolean,
    equatorialGrid: Boolean,
    azimuthalGrid: Boolean,
    meridian: Boolean,
    ecliptic: Boolean,
    constellationLines: Boolean,
    constellationLabels: Boolean,
    constellationBounds: Boolean,
    starHints: Boolean,
    atmosphereVisible: Boolean,
    landscapeVisible: Boolean,
    onlineDssEnabled: Boolean,
    starMapTimeLabel: String,
    overlaysLocked: Boolean,
    hipsCacheSizeLabel: String,
    onSlewRateChange: (MountSlewRate) -> Unit,
    onManualMoveStart: (MountDirection) -> Unit,
    onManualMoveStop: (MountDirection) -> Unit,
    onStopMount: () -> Unit,
    onConfirmHome: () -> Unit,
    onFollowMountChange: (Boolean) -> Unit,
    onFollowPhoneChange: (Boolean) -> Unit,
    onCenterOnPointing: () -> Unit,
    onRefreshPointing: () -> Unit,
    onActiveTrainChange: (OpticsTrainId) -> Unit,
    onOpenFov: () -> Unit,
    onOpenTimeSettings: () -> Unit,
    onShowFovOverlayChange: (Boolean) -> Unit,
    precisionToleranceText: String,
    onPrecisionToleranceChange: (String) -> Unit,
    onEquatorialGridChange: (Boolean) -> Unit,
    onAzimuthalGridChange: (Boolean) -> Unit,
    onMeridianChange: (Boolean) -> Unit,
    onEclipticChange: (Boolean) -> Unit,
    onConstellationLinesChange: (Boolean) -> Unit,
    onConstellationLabelsChange: (Boolean) -> Unit,
    onConstellationBoundsChange: (Boolean) -> Unit,
    onStarHintsChange: (Boolean) -> Unit,
    onAtmosphereChange: (Boolean) -> Unit,
    onLandscapeChange: (Boolean) -> Unit,
    onOnlineDssChange: (Boolean) -> Unit,
    onOverlaysLockedChange: (Boolean) -> Unit,
    onRefreshHipsCache: () -> Unit,
    onClearHipsCache: () -> Unit,
    onReloadStarMap: () -> Unit
) {
    fun toggle(target: StarMapCornerPanel) {
        onPanelChange(if (panel == target) StarMapCornerPanel.NONE else target)
    }

    Column(
        modifier = modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (panel) {
            StarMapCornerPanel.OBSERVING -> Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxPanelHeight)
                    .consumeStarMapPanelTouches()
            ) {
                Column(
                    modifier = Modifier
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    StarMapPanelHeading(stringResource(R.string.star_map_observing_panel)) {
                        onPanelChange(StarMapCornerPanel.NONE)
                    }
                    StarMapFlagSwitch(
                        label = stringResource(R.string.sky_phone_follow),
                        checked = followPhone,
                        enabled = phoneFollowAvailable,
                        switchModifier = Modifier.testTag("sky-phone-follow"),
                        onCheckedChange = onFollowPhoneChange
                    )
                    Text(phoneFollowCaption, style = MaterialTheme.typography.bodySmall)
                    if (mountConnected) {
                        if (canManualMove) {
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
                                    label = { Text(rate.label, fontSize = 10.sp) },
                                    modifier = Modifier.heightIn(min = 48.dp)
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
                        } else {
                            FilledTonalButton(onClick = onStopMount) {
                                Icon(Icons.Default.Stop, contentDescription = stringResource(R.string.stop_mount))
                            }
                        }
                        if (canHome) OutlinedButton(
                            onClick = onConfirmHome,
                            enabled = homeEnabled,
                            modifier = Modifier.heightIn(min = 48.dp)
                        ) {
                            Text(stringResource(R.string.home), fontSize = 12.sp)
                        }
                    } else {
                        Text(
                            stringResource(R.string.connect_mount_for_manual_move),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    StarMapFlagSwitch(
                        label = stringResource(R.string.wf_follow_pointing),
                        checked = followMount,
                        enabled = followAvailable,
                        switchModifier = Modifier.testTag("pointing-follow"),
                        onCheckedChange = onFollowMountChange
                    )
                    OutlinedButton(
                        onClick = onCenterOnPointing,
                        enabled = followAvailable,
                        modifier = Modifier.testTag("pointing-center").fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.wf_center_pointing), fontSize = 12.sp)
                    }
                    Text(pointingSourceCaption, style = MaterialTheme.typography.labelSmall)
                    if (!followAvailable) TextButton(onClick = onRefreshPointing) { Text(stringResource(R.string.wf_follow_refresh)) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = activeTrain == OpticsTrainId.PRIMARY,
                            onClick = { onActiveTrainChange(OpticsTrainId.PRIMARY) },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    stringResource(R.string.star_map_train_primary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                        FilterChip(
                            selected = activeTrain == OpticsTrainId.SECONDARY,
                            onClick = { onActiveTrainChange(OpticsTrainId.SECONDARY) },
                            modifier = Modifier.weight(1f),
                            label = {
                                Text(
                                    stringResource(R.string.star_map_train_secondary),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        )
                    }
                    StarMapFlagSwitch(
                        label = stringResource(R.string.fov_show_overlay),
                        checked = showFovOverlay,
                        onCheckedChange = onShowFovOverlayChange
                    )
                    OutlinedButton(
                        onClick = onOpenFov,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(stringResource(R.string.star_map_fov), fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = onOpenTimeSettings,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Text(
                            stringResource(R.string.star_map_time_button, starMapTimeLabel),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    OutlinedTextField(
                        value = precisionToleranceText,
                        onValueChange = onPrecisionToleranceChange,
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        label = {
                            Text(
                                stringResource(R.string.precision_goto_tolerance),
                                fontSize = 11.sp
                            )
                        }
                    )
                }
            }
            StarMapCornerPanel.SKY -> Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxPanelHeight)
                    .consumeStarMapPanelTouches()
            ) {
                Column(
                    modifier = Modifier
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    StarMapPanelHeading(stringResource(R.string.star_map_sky_panel)) {
                        onPanelChange(StarMapCornerPanel.NONE)
                    }
                    Text(
                        stringResource(R.string.star_map_sky_section_grids),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.star_map_equatorial_grid),
                        checked = equatorialGrid,
                        onCheckedChange = onEquatorialGridChange
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.star_map_azimuthal_grid),
                        checked = azimuthalGrid,
                        onCheckedChange = onAzimuthalGridChange
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.star_map_meridian),
                        checked = meridian,
                        onCheckedChange = onMeridianChange
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.star_map_ecliptic),
                        checked = ecliptic,
                        onCheckedChange = onEclipticChange
                    )
                    Text(
                        stringResource(R.string.star_map_sky_section_constellations),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.star_map_constellation_lines),
                        checked = constellationLines,
                        onCheckedChange = onConstellationLinesChange
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.star_map_constellation_labels),
                        checked = constellationLabels,
                        onCheckedChange = onConstellationLabelsChange
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.star_map_constellation_bounds),
                        checked = constellationBounds,
                        onCheckedChange = onConstellationBoundsChange
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.star_map_star_labels),
                        checked = starHints,
                        onCheckedChange = onStarHintsChange
                    )
                    Text(
                        stringResource(R.string.star_map_sky_section_display),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.sky_simulated_landscape),
                        checked = landscapeVisible,
                        switchModifier = Modifier.testTag("sky-landscape"),
                        onCheckedChange = onLandscapeChange
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.atmosphere),
                        checked = atmosphereVisible,
                        onCheckedChange = onAtmosphereChange
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.online_dss_survey),
                        checked = onlineDssEnabled,
                        onCheckedChange = onOnlineDssChange
                    )
                    Text(
                        stringResource(R.string.hips_cache_size, hipsCacheSizeLabel),
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onRefreshHipsCache) {
                            Text(stringResource(R.string.refresh))
                        }
                        TextButton(onClick = onClearHipsCache) {
                            Text(stringResource(R.string.clear_hips_cache))
                        }
                    }
                    OutlinedButton(
                        onClick = onReloadStarMap,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Text(stringResource(R.string.reload_star_map), fontSize = 12.sp)
                    }
                }
            }
            StarMapCornerPanel.NONE -> Unit
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StarMapCornerButton(
                selected = panel == StarMapCornerPanel.OBSERVING,
                icon = Icons.Default.Explore,
                contentDescription = stringResource(R.string.star_map_observing_panel),
                onClick = { toggle(StarMapCornerPanel.OBSERVING) }
            )
            StarMapCornerButton(
                selected = panel == StarMapCornerPanel.SKY,
                icon = Icons.Default.GridOn,
                contentDescription = stringResource(R.string.star_map_sky_panel),
                onClick = { toggle(StarMapCornerPanel.SKY) }
            )
            val lockAction = stringResource(
                if (overlaysLocked) R.string.sky_controls_unpin else R.string.sky_controls_pin
            )
            val lockState = stringResource(
                if (overlaysLocked) R.string.sky_controls_pinned else R.string.sky_controls_auto_hide
            )
            PlainTooltipBox(tooltip = { Text(lockAction) }) {
                Card(shape = CircleShape) {
                    IconToggleButton(
                        checked = overlaysLocked,
                        onCheckedChange = onOverlaysLockedChange,
                        modifier = Modifier.size(48.dp).tooltipAnchor().testTag("star-map-controls-lock")
                            .semantics {
                                contentDescription = lockAction
                                stateDescription = lockState
                            }
                    ) {
                        Icon(
                            if (overlaysLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                            tint = if (overlaysLocked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StarMapPanelHeading(title: String, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.close)) }
    }
}

@Composable
private fun StarMapCornerButton(
    selected: Boolean,
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Card(shape = CircleShape) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.semantics { this.contentDescription = contentDescription }
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }
    }
}

@Composable
private fun StarMapFlagSwitch(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    switchModifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().then(switchModifier)
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
        )
        Switch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled
        )
    }
}

/**
 * Always-on abort chrome for GOTO / home / RA move. Keep this out of the
 * observing tools and target card so it cannot reflow or cover them.
 */
@Composable
internal fun MountMotionStopBanner(
    state: MountMotionState,
    onStop: () -> Unit,
    detail: String? = null,
    modifier: Modifier = Modifier
) {
    if (!state.showsGlobalStop) return
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (!detail.isNullOrBlank()) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    state.label,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = onStop,
                    enabled = !state.isStopping,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    if (state.isStopping) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError
                        )
                    } else {
                        Icon(Icons.Default.Stop, contentDescription = null)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.isStopping) "STOPPING" else "STOP")
                }
            }
        }
    }
}
