package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.util.ImageUtils

@Composable
private fun FormatToggle(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(6.dp)
    val activeColor = MaterialTheme.colorScheme.primary
    val activeBg = MaterialTheme.colorScheme.primaryContainer
    val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val inactiveBg = Color.Transparent

    Row(
        modifier = modifier
            .clip(shape)
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
            .height(28.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, option ->
            if (index > 0) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                )
            }
            val isActive = option == selected
            Box(
                modifier = Modifier
                    .background(if (isActive) activeBg else inactiveBg)
                    .clickable { onSelect(option) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    fontSize = 10.sp,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (isActive) activeColor else inactiveColor
                )
            }
        }
    }
}

enum class RecordLimitType { NONE, FRAMES, TIME }
data class RecordLimit(val type: RecordLimitType, val value: Int) {
    fun displayText(): String = when (type) {
        RecordLimitType.NONE -> "∞"
        RecordLimitType.FRAMES -> "${value}f"
        RecordLimitType.TIME -> if (value >= 60) "${value / 60}m" else "${value}s"
    }
}

private val FRAME_OPTIONS = listOf(500, 1000, 2000, 5000)
private val TIME_OPTIONS = listOf(5, 10, 20, 30, 60, 120, 300, 600)

@Composable
private fun RecordLimitSelector(
    limit: RecordLimit,
    onSelect: (RecordLimit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)
    
    Box {
        Box(
            modifier = Modifier
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
                .clickable { expanded = true }
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .height(20.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.limit_label), fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    limit.displayText(),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (limit.type == RecordLimitType.NONE) 
                        MaterialTheme.colorScheme.onSurfaceVariant 
                    else MaterialTheme.colorScheme.primary
                )
            }
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.no_limit)) },
                onClick = {
                    onSelect(RecordLimit(RecordLimitType.NONE, 0))
                    expanded = false
                }
            )
            Divider()
            Text(stringResource(R.string.frames_header), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            FRAME_OPTIONS.forEach { f ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.frames_option, f)) },
                    onClick = {
                        onSelect(RecordLimit(RecordLimitType.FRAMES, f))
                        expanded = false
                    }
                )
            }
            Divider()
            Text(stringResource(R.string.duration_header), modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            TIME_OPTIONS.forEach { t ->
                DropdownMenuItem(
                    text = {
                        Text(
                            if (t >= 60) stringResource(R.string.duration_min, t / 60)
                            else stringResource(R.string.duration_sec, t)
                        )
                    },
                    onClick = {
                        onSelect(RecordLimit(RecordLimitType.TIME, t))
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun RecordBar(
    isRecording: Boolean,
    frameCount: Int,
    durationMs: Long,
    bytesWritten: Long,
    targetName: String,
    captureFormatLabel: String,
    recordFormatLabel: String,
    recordLimit: RecordLimit,
    onTargetNameChange: (String) -> Unit,
    onStartRecord: () -> Unit,
    onStopRecord: () -> Unit,
    onCapture: () -> Unit,
    onSelectCaptureFormat: (String) -> Unit,
    onSelectRecordFormat: (String) -> Unit,
    onSelectRecordLimit: (RecordLimit) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isRecording) {
            Column {
                Text(
                    stringResource(
                        R.string.recording_status,
                        ImageUtils.formatDuration(durationMs),
                        frameCount,
                        ImageUtils.formatFileSize(bytesWritten)
                    ),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    ),
                    color = Color(0xFFFF6666)
                )
            }
        } else {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.target_label), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val targets = listOf("Ju", "Sa", "Ma", "Ve", "Me", "Mo", "Su")
                    targets.forEach { t ->
                        Text(
                            t,
                            fontSize = 10.sp,
                            color = if (targetName == t) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onTargetNameChange(t) }
                                .background(
                                    if (targetName == t) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FormatToggle(
                        options = listOf("JPG", "FITS"),
                        selected = captureFormatLabel,
                        onSelect = onSelectCaptureFormat
                    )
                    FormatToggle(
                        options = listOf("SER", "PSER", "MP4"),
                        selected = recordFormatLabel,
                        onSelect = onSelectRecordFormat
                    )
                    RecordLimitSelector(
                        limit = recordLimit,
                        onSelect = onSelectRecordLimit
                    )
                }
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
                    .clickable { onCapture() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Camera,
                    contentDescription = stringResource(R.string.capture_desc),
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(22.dp)
                )
            }

            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .border(
                        2.dp,
                        if (isRecording) Color(0xFFFF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
                        CircleShape
                    )
                    .clickable { if (isRecording) onStopRecord() else onStartRecord() },
                contentAlignment = Alignment.Center
            ) {
                if (isRecording) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = stringResource(R.string.stop_recording),
                        tint = Color(0xFFFF4444),
                        modifier = Modifier.size(26.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = stringResource(R.string.start_recording),
                        tint = Color(0xFFFF4444),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}
