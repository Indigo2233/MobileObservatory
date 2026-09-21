@file:OptIn(ExperimentalMaterial3Api::class)

package com.indigo.mobileobservatory.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.astro.OpticsTrainId
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountSlewRate

internal enum class StarMapCornerPanel { NONE, OBSERVING, SKY }

/**
 * Stellarium Plus–style bottom-left chrome: two round buttons. One opens the
 * observing tools (mount pad, follow, FOV); the other opens sky drawing
 * (grids, constellations, labels, night mode). Both hide with the rest of the HUD.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StarMapCornerControls(
    panel: StarMapCornerPanel,
    onPanelChange: (StarMapCornerPanel) -> Unit,
    mountConnected: Boolean,
    moveEnabled: Boolean,
    mountSlewRate: MountSlewRate,
    followMount: Boolean,
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
    redNightMode: Boolean,
    onlineDssEnabled: Boolean,
    overlaysLocked: Boolean,
    hipsCacheSizeLabel: String,
    onSlewRateChange: (MountSlewRate) -> Unit,
    onManualMoveStart: (MountDirection) -> Unit,
    onManualMoveStop: (MountDirection) -> Unit,
    onStopMount: () -> Unit,
    onConfirmHome: () -> Unit,
    onFollowMountChange: (Boolean) -> Unit,
    onCenterOnMount: () -> Unit,
    onActiveTrainChange: (OpticsTrainId) -> Unit,
    onOpenFov: () -> Unit,
    onShowFovOverlayChange: (Boolean) -> Unit,
    onEquatorialGridChange: (Boolean) -> Unit,
    onAzimuthalGridChange: (Boolean) -> Unit,
    onMeridianChange: (Boolean) -> Unit,
    onEclipticChange: (Boolean) -> Unit,
    onConstellationLinesChange: (Boolean) -> Unit,
    onConstellationLabelsChange: (Boolean) -> Unit,
    onConstellationBoundsChange: (Boolean) -> Unit,
    onStarHintsChange: (Boolean) -> Unit,
    onAtmosphereChange: (Boolean) -> Unit,
    onRedNightModeChange: (Boolean) -> Unit,
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
        modifier = Modifier.padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (panel) {
            StarMapCornerPanel.OBSERVING -> Card(modifier = Modifier.widthIn(max = 300.dp)) {
                Column(
                    modifier = Modifier
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(R.string.star_map_observing_panel),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (mountConnected) {
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
                        OutlinedButton(
                            onClick = onConfirmHome,
                            enabled = moveEnabled,
                            modifier = Modifier.height(36.dp)
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
                        label = stringResource(R.string.follow_mount_pointing),
                        checked = followMount,
                        enabled = mountConnected,
                        onCheckedChange = onFollowMountChange
                    )
                    OutlinedButton(
                        onClick = onCenterOnMount,
                        enabled = mountConnected,
                        modifier = Modifier.fillMaxWidth().height(36.dp)
                    ) {
                        Text(stringResource(R.string.center_on_mount), fontSize = 12.sp)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = activeTrain == OpticsTrainId.PRIMARY,
                            onClick = { onActiveTrainChange(OpticsTrainId.PRIMARY) },
                            label = { Text(stringResource(R.string.star_map_train_primary)) }
                        )
                        FilterChip(
                            selected = activeTrain == OpticsTrainId.SECONDARY,
                            onClick = { onActiveTrainChange(OpticsTrainId.SECONDARY) },
                            label = { Text(stringResource(R.string.star_map_train_secondary)) }
                        )
                    }
                    StarMapFlagSwitch(
                        label = stringResource(R.string.fov_show_overlay),
                        checked = showFovOverlay,
                        onCheckedChange = onShowFovOverlayChange
                    )
                    OutlinedButton(
                        onClick = onOpenFov,
                        modifier = Modifier.fillMaxWidth().height(36.dp)
                    ) {
                        Text(stringResource(R.string.star_map_fov), fontSize = 12.sp)
                    }
                }
            }
            StarMapCornerPanel.SKY -> Card(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .heightIn(max = 420.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(10.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        stringResource(R.string.star_map_sky_panel),
                        style = MaterialTheme.typography.titleSmall
                    )
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
                        label = stringResource(R.string.atmosphere),
                        checked = atmosphereVisible,
                        onCheckedChange = onAtmosphereChange
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.red_night_mode),
                        checked = redNightMode,
                        onCheckedChange = onRedNightModeChange
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.online_dss_survey),
                        checked = onlineDssEnabled,
                        onCheckedChange = onOnlineDssChange
                    )
                    StarMapFlagSwitch(
                        label = stringResource(R.string.lock_star_map_overlays),
                        checked = overlaysLocked,
                        onCheckedChange = onOverlaysLockedChange
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
                        modifier = Modifier.fillMaxWidth().height(36.dp)
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
        }
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
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
