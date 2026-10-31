package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.mount.MountDirection
import com.indigo.mobileobservatory.mount.MountSlewRate
import com.indigo.mobileobservatory.ui.devices.MomentaryDeviceMove

/** Preview-only manual control. Ownership covers one held press, including cancellation/disposal. */
@Composable
internal fun CaptureMountPad(
    rate: MountSlewRate,
    enabled: Boolean,
    onMoveStart: (MountDirection) -> Unit,
    onMoveStop: (MountDirection) -> Unit,
    onRateChange: (MountSlewRate) -> Unit,
    modifier: Modifier = Modifier
) {
    val start by rememberUpdatedState(onMoveStart)
    val stop by rememberUpdatedState(onMoveStop)
    val heldMoves = remember {
        MountDirection.entries.associateWith { direction ->
            MomentaryDeviceMove(start = { start(direction) }, stop = { stop(direction) })
        }
    }
    val owner = LocalLifecycleOwner.current
    var ratesOpen by remember { mutableStateOf(false) }
    fun release() = heldMoves.values.forEach { it.end() }
    DisposableEffect(owner, heldMoves) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                release()
                ratesOpen = false
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer); release() }
    }
    LaunchedEffect(enabled) {
        if (!enabled) { release(); ratesOpen = false }
    }
    val centerWidth = if (LocalDensity.current.fontScale >= 1.5f) 72.dp else 48.dp
    val speedDescription = stringResource(R.string.capture_mount_speed_value, rate.label)
    Column(modifier.testTag("capture-mount-pad"), horizontalAlignment = Alignment.CenterHorizontally) {
        DirectionPadButton(Icons.Default.ArrowUpward, MountDirection.NORTH, R.string.move_north,
            enabled && !ratesOpen, heldMoves.getValue(MountDirection.NORTH))
        Row(verticalAlignment = Alignment.CenterVertically) {
            DirectionPadButton(Icons.Default.ArrowBack, MountDirection.WEST, R.string.move_west,
                enabled && !ratesOpen, heldMoves.getValue(MountDirection.WEST))
            Box {
                FilledTonalButton(
                    onClick = { release(); ratesOpen = true },
                    enabled = enabled,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier = Modifier.width(centerWidth).height(48.dp).testTag("capture-mount-speed")
                        .semantics { contentDescription = speedDescription }
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(rate.label, style = MaterialTheme.typography.labelSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Icon(Icons.Default.ArrowDropDown, stringResource(R.string.capture_mount_speed),
                            Modifier.size(14.dp))
                    }
                }
                DropdownMenu(expanded = ratesOpen, onDismissRequest = { ratesOpen = false },
                    modifier = Modifier.heightIn(max = 320.dp).testTag("capture-mount-speeds")) {
                    PopupWindowBackHandler { ratesOpen = false }
                    MountSlewRate.entries.forEach { choice ->
                        DropdownMenuItem(
                            text = { Text(choice.label) },
                            onClick = { ratesOpen = false; onRateChange(choice) },
                            modifier = Modifier.testTag("capture-mount-rate-${choice.name}")
                                .semantics { selected = rate == choice },
                            trailingIcon = {
                                if (choice == rate) Icon(Icons.Default.Check, null, Modifier.size(18.dp))
                            }
                        )
                    }
                }
            }
            DirectionPadButton(Icons.Default.ArrowForward, MountDirection.EAST, R.string.move_east,
                enabled && !ratesOpen, heldMoves.getValue(MountDirection.EAST))
        }
        DirectionPadButton(Icons.Default.ArrowDownward, MountDirection.SOUTH, R.string.move_south,
            enabled && !ratesOpen, heldMoves.getValue(MountDirection.SOUTH))
    }
}

@Composable
private fun DirectionPadButton(
    icon: ImageVector,
    direction: MountDirection,
    description: Int,
    enabled: Boolean,
    held: MomentaryDeviceMove
) {
    var pressed by remember { mutableStateOf(false) }
    val label = stringResource(description)
    Box(
        Modifier.size(48.dp).testTag("capture-mount-${direction.name.lowercase()}")
            .semantics {
                contentDescription = label
                role = Role.Button
                if (!enabled) disabled()
            }
            .pointerInput(held, enabled) {
                if (enabled) detectTapGestures(onPress = {
                    pressed = true
                    try { held.begin(); tryAwaitRelease() }
                    finally { pressed = false; held.end() }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = CircleShape,
            color = if (pressed) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .88f),
            contentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else .38f),
            modifier = Modifier.size(40.dp)
        ) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(22.dp)) }
        }
    }
}
