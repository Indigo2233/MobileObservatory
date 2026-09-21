package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R

/**
 * Landscape preview chrome: keep panel toggle + one overflow. The old vertical
 * FAB stack overlapped the focus-assist window on typical phone heights.
 */
@Composable
fun CameraPreviewTools(
    connected: Boolean,
    redNightMode: Boolean,
    showPanel: Boolean,
    showOverlayPanel: Boolean,
    focusAssistEnabled: Boolean,
    showCenterMarker: Boolean,
    onFitToView: () -> Unit,
    onRedNightModeChange: (Boolean) -> Unit,
    onTogglePanel: () -> Unit,
    onToggleOverlay: () -> Unit,
    onToggleFocusAssist: () -> Unit,
    onToggleCenterMarker: () -> Unit,
    onPlateSolve: () -> Unit,
    onOpenLibrary: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focusAssistEnabled) {
        if (focusAssistEnabled) menuOpen = false
    }

    Row(
        modifier = modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (connected) {
            SmallFloatingActionButton(
                onClick = onTogglePanel,
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Icon(
                    if (showPanel) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                    stringResource(R.string.toggle_panel),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Box {
            SmallFloatingActionButton(
                onClick = { menuOpen = true },
                containerColor = if (focusAssistEnabled || showCenterMarker || redNightMode) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                }
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    stringResource(R.string.camera_preview_tools),
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                if (connected) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.fit_to_view)) },
                        onClick = {
                            menuOpen = false
                            onFitToView()
                        },
                        leadingIcon = { Icon(Icons.Default.FitScreen, contentDescription = null) }
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.red_night_mode)) },
                    onClick = { onRedNightModeChange(!redNightMode) },
                    leadingIcon = {
                        Icon(
                            if (redNightMode) Icons.Default.Nightlight else Icons.Default.NightlightRound,
                            contentDescription = null
                        )
                    },
                    trailingIcon = {
                        Switch(checked = redNightMode, onCheckedChange = null)
                    }
                )
                if (connected) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.toggle_overlay)) },
                        onClick = onToggleOverlay,
                        leadingIcon = {
                            Icon(
                                if (showOverlayPanel) Icons.Default.Visibility
                                else Icons.Default.VisibilityOff,
                                contentDescription = null
                            )
                        },
                        trailingIcon = {
                            Switch(checked = showOverlayPanel, onCheckedChange = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.focus_assist)) },
                        onClick = onToggleFocusAssist,
                        leadingIcon = {
                            Icon(Icons.Default.CenterFocusWeak, contentDescription = null)
                        },
                        trailingIcon = {
                            Switch(checked = focusAssistEnabled, onCheckedChange = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.image_center_marker)) },
                        onClick = onToggleCenterMarker,
                        leadingIcon = {
                            Icon(Icons.Default.FilterCenterFocus, contentDescription = null)
                        },
                        trailingIcon = {
                            Switch(checked = showCenterMarker, onCheckedChange = null)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plate_solve)) },
                        onClick = {
                            menuOpen = false
                            onPlateSolve()
                        },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.video_library)) },
                        onClick = {
                            menuOpen = false
                            onOpenLibrary()
                        },
                        leadingIcon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) }
                    )
                }
            }
        }
    }
}
