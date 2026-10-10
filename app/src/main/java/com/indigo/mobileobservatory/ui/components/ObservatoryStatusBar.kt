package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.NightlightRound
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ui.theme.ObservatoryTheme
import com.indigo.mobileobservatory.ui.theme.ObservatoryType

/** Connection / activity state of one device shown in the global status strip. */
enum class DeviceStatusCode {
    OFF,
    CONNECTING,
    READY,
    ACTIVE,
    ERROR,
}

/**
 * One entry of the global status strip. [value] is an optional live readout
 * (temperature, position, RMS...). Status is always encoded as colour **and**
 * label so red night vision keeps the information readable.
 */
data class DeviceStatus(
    val key: String,
    val label: String,
    val value: String? = null,
    val code: DeviceStatusCode = DeviceStatusCode.OFF,
    val icon: ImageVector? = null,
)

@Composable
fun deviceStatusColor(code: DeviceStatusCode): Color {
    val status = ObservatoryTheme.colors.status
    return when (code) {
        DeviceStatusCode.OFF -> ObservatoryTheme.colors.status.neutral
        DeviceStatusCode.CONNECTING -> status.active
        DeviceStatusCode.READY -> status.ready
        DeviceStatusCode.ACTIVE -> status.ready
        DeviceStatusCode.ERROR -> status.danger
    }
}

/**
 * Persistent device status strip: the answer to "what is my rig doing right
 * now" without leaving the current screen. Tapping a pill jumps to the screen
 * that owns the device; the right-hand icons are global settings and the red
 * night-vision toggle.
 */
@Composable
fun ObservatoryStatusBar(
    devices: List<DeviceStatus>,
    redNightMode: Boolean,
    onToggleRedNight: (Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onDeviceClick: ((DeviceStatus) -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Column {
            val deviceStatusDescription = stringResource(R.string.cockpit_device_status)
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .semantics { contentDescription = deviceStatusDescription },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    devices.forEach { status ->
                        StatusPill(
                            status = status,
                            onClick = onDeviceClick?.let { callback -> { callback(status) } }
                        )
                    }
                }
                Spacer(Modifier.width(4.dp))
                val nightDescription = stringResource(R.string.cockpit_red_night)
                Icon(
                    imageVector = if (redNightMode) Icons.Default.Nightlight
                    else Icons.Default.NightlightRound,
                    contentDescription = nightDescription,
                    tint = if (redNightMode) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { onToggleRedNight(!redNightMode) }
                        .padding(10.dp)
                )
                val settingsDescription = stringResource(R.string.cockpit_open_settings)
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = settingsDescription,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(44.dp)
                        .clickable { onOpenSettings() }
                        .padding(10.dp)
                )
            }
            Divider(color = ObservatoryTheme.colors.hairline)
        }
    }
}

@Composable
private fun StatusPill(
    status: DeviceStatus,
    onClick: (() -> Unit)?,
) {
    val colors = ObservatoryTheme.colors
    val accent = deviceStatusColor(status.code)
    val active = status.code == DeviceStatusCode.ACTIVE
    val surfaceColor = if (active) accent.copy(alpha = 0.22f)
    else MaterialTheme.colorScheme.surfaceVariant.copy(
        alpha = if (status.code == DeviceStatusCode.OFF) 0.45f else 1f
    )

    Surface(
        color = surfaceColor,
        shape = RoundedCornerShape(50),
        modifier = Modifier
            .height(40.dp)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable { onClick() }
                        .semantics { contentDescription = "${status.label} status" }
                } else {
                    Modifier
                }
            )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(accent, CircleShape)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = status.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (status.code == DeviceStatusCode.OFF) {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
            if (!status.value.isNullOrEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = status.value,
                    style = ObservatoryType.readoutSmall,
                    color = if (active) accent else colors.readout
                )
            }
        }
    }
}
