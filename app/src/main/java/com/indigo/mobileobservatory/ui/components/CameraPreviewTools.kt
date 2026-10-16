package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusWeak
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FilterCenterFocus
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R

/**
 * Shared preview chrome: panel toggle, fit-to-view, and one overflow.
 * Do not restack these as a vertical FAB column — it covers focus assist.
 */
@Composable
fun CameraPreviewTools(
    connected: Boolean,
    showPanel: Boolean,
    showOverlayPanel: Boolean,
    focusAssistEnabled: Boolean,
    showCenterMarker: Boolean,
    lp750Enabled: Boolean,
    lp750Available: Boolean,
    selectedTarget: String?,
    currentFrameSolveBusy: Boolean,
    onFitToView: () -> Unit,
    onTogglePanel: () -> Unit,
    onToggleOverlay: () -> Unit,
    onToggleFocusAssist: () -> Unit,
    onToggleCenterMarker: () -> Unit,
    onToggleLp750: (Boolean) -> Unit,
    onSolveCurrentFrame: () -> Unit,
    onChooseTarget: () -> Unit,
    onOpenLiveStack: () -> Unit = {},
    onCfaSettings: () -> Unit = {},
    simulated: Boolean = false,
    canStartSimulation: Boolean = false,
    onStartSimulation: () -> Unit = {},
    onStopSimulation: () -> Unit = {},
    portrait: Boolean = false,
    showSequence: Boolean = false,
    onOpenSequence: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var showLp750Help by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(focusAssistEnabled) {
        if (focusAssistEnabled) menuOpen = false
    }

    Row(
        modifier = modifier.padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Top
    ) {
        if (connected) {
            PreviewChromeButton(
                onClick = onTogglePanel,
                description = stringResource(R.string.toggle_panel)
            ) {
                Icon(
                    if (portrait) { if (showPanel) Icons.Default.ExpandMore else Icons.Default.ExpandLess }
                    else if (showPanel) Icons.Default.ChevronRight else Icons.Default.ChevronLeft,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            PreviewChromeButton(
                onClick = onFitToView,
                description = stringResource(R.string.fit_to_view)
            ) {
                Icon(
                    Icons.Default.FitScreen,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
        }
        Box {
            PreviewChromeButton(
                onClick = { menuOpen = true },
                description = stringResource(R.string.camera_preview_tools),
                highlighted = focusAssistEnabled || showCenterMarker || lp750Enabled
            ) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                PopupWindowBackHandler(enabled = menuOpen) { menuOpen = false }
                if (simulated || canStartSimulation) DropdownMenuItem(
                    text = { Text(stringResource(if (simulated) R.string.sim_camera_stop else R.string.sim_camera_start)) },
                    onClick = { menuOpen = false; if (simulated) onStopSimulation() else onStartSimulation() })
                if (connected) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.camera_cfa_title)) },
                        onClick = { menuOpen = false; onCfaSettings() })
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
                        text = { Text(stringResource(R.string.stack_live)) },
                        onClick = { menuOpen = false; onOpenLiveStack() },
                        leadingIcon = { Icon(Icons.Default.Layers, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(selectedTarget ?: stringResource(R.string.wf_choose_target)) },
                        onClick = { menuOpen = false; onChooseTarget() },
                        leadingIcon = { Icon(Icons.Default.Star, contentDescription = null) }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.plate_solve_current_frame)) },
                        onClick = { menuOpen = false; onSolveCurrentFrame() },
                        enabled = !currentFrameSolveBusy,
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = lp750Available) { onToggleLp750(!lp750Enabled) }
                            .padding(start = 12.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            stringResource(R.string.lp750_preview_mapping),
                            modifier = Modifier.weight(1f),
                            color = if (lp750Available) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                        )
                        IconButton(onClick = { showLp750Help = true }) {
                            Icon(Icons.Default.HelpOutline, stringResource(R.string.lp750_help_button))
                        }
                        Switch(
                            checked = lp750Enabled && lp750Available,
                            onCheckedChange = if (lp750Available) onToggleLp750 else null,
                            enabled = lp750Available
                        )
                    }
                    if (showSequence) DropdownMenuItem(
                        text = { Text(stringResource(R.string.tab_sequence)) },
                        onClick = { menuOpen = false; onOpenSequence() }
                    )
                }
            }
        }
    }

    if (showLp750Help) AlertDialog(
        onDismissRequest = { showLp750Help = false },
        title = { Text(stringResource(R.string.lp750_help_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.lp750_help_description))
                Lp750Credit()
            }
        },
        confirmButton = {
            Button(onClick = { showLp750Help = false }) {
                Text(stringResource(android.R.string.ok))
            }
        }
    )
}

/**
 * Preview chrome button: 44dp visual with a 48dp minimum touch target, so it
 * still works with gloves while staying out of the preview's way.
 */
@Composable
private fun PreviewChromeButton(
    onClick: () -> Unit,
    description: String,
    highlighted: Boolean = false,
    content: @Composable () -> Unit
) {
    Surface(
        color = if (highlighted) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        shape = CircleShape,
        shadowElevation = 2.dp,
        modifier = Modifier
            .size(44.dp)
            .clickable(onClick = onClick)
            .semantics { contentDescription = description }
    ) {
        Box(contentAlignment = Alignment.Center) {
            content()
        }
    }
}
