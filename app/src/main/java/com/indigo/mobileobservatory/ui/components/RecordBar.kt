package com.indigo.mobileobservatory.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PhotoLibrary
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.camera.CaptureTargetNames
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
            .height(40.dp),
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
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab
                    ) { onSelect(option) }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    option,
                    fontSize = 12.sp,
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
        RecordLimitType.TIME -> formatRecordLimitDuration(value)
    }
}

private val FRAME_OPTIONS = listOf(500, 1000, 2000, 5000)
private val TIME_OPTIONS = listOf(5, 10, 20, 30, 60, 120, 300, 600)

internal const val MAX_CUSTOM_RECORD_FRAMES = 2_000_000_000
internal const val MAX_CUSTOM_RECORD_SECONDS = 99 * 3600 + 59 * 60 + 59

internal fun parseCustomRecordFrames(text: String): Int? =
    text.toIntOrNull()?.takeIf { it in 1..MAX_CUSTOM_RECORD_FRAMES }

internal fun parseCustomRecordDuration(hours: String, minutes: String, seconds: String): Int? {
    if (hours.any { !it.isDigit() } || minutes.any { !it.isDigit() } || seconds.any { !it.isDigit() }) return null
    val h = hours.ifEmpty { "0" }.toIntOrNull() ?: return null
    val m = minutes.ifEmpty { "0" }.toIntOrNull() ?: return null
    val s = seconds.ifEmpty { "0" }.toIntOrNull() ?: return null
    if (m !in 0..59 || s !in 0..59) return null
    val total = h.toLong() * 3600L + m * 60L + s
    return total.takeIf { it in 1..MAX_CUSTOM_RECORD_SECONDS.toLong() }?.toInt()
}

internal fun formatRecordLimitDuration(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = totalSeconds % 3600 / 60
    val s = totalSeconds % 60
    return when {
        h > 0 && (m > 0 || s > 0) -> "${h}h${m.toString().padStart(2, '0')}m${s.toString().padStart(2, '0')}s"
        h > 0 -> "${h}h"
        m > 0 && s > 0 -> "${m}m${s.toString().padStart(2, '0')}s"
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

@Composable
private fun CustomFrameLimitDialog(
    initial: RecordLimit,
    onDismiss: () -> Unit,
    onConfirm: (RecordLimit) -> Unit
) {
    var text by remember {
        mutableStateOf(if (initial.type == RecordLimitType.FRAMES) initial.value.toString() else "")
    }
    val frames = parseCustomRecordFrames(text)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_frame_limit_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { value -> text = value.filter(Char::isDigit).take(10) },
                label = { Text(stringResource(R.string.custom_frame_limit_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = { frames?.let { onConfirm(RecordLimit(RecordLimitType.FRAMES, it)) } }
                ),
                supportingText = {
                    Text(stringResource(R.string.custom_frame_limit_range, MAX_CUSTOM_RECORD_FRAMES))
                },
                isError = text.isNotEmpty() && frames == null,
                modifier = Modifier.fillMaxWidth().testTag("custom-frame-limit")
            )
        },
        confirmButton = {
            TextButton(
                onClick = { frames?.let { onConfirm(RecordLimit(RecordLimitType.FRAMES, it)) } },
                enabled = frames != null
            ) { Text(stringResource(R.string.apply_limit)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun CustomDurationLimitDialog(
    initial: RecordLimit,
    onDismiss: () -> Unit,
    onConfirm: (RecordLimit) -> Unit
) {
    val initialSeconds = if (initial.type == RecordLimitType.TIME) initial.value else 0
    var hours by remember { mutableStateOf(if (initialSeconds > 0) (initialSeconds / 3600).toString() else "") }
    var minutes by remember {
        mutableStateOf(if (initialSeconds > 0) (initialSeconds % 3600 / 60).toString().padStart(2, '0') else "")
    }
    var seconds by remember {
        mutableStateOf(if (initialSeconds > 0) (initialSeconds % 60).toString().padStart(2, '0') else "")
    }
    val total = parseCustomRecordDuration(hours, minutes, seconds)
    val confirm = {
        val value = total
        if (value != null) onConfirm(RecordLimit(RecordLimitType.TIME, value))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_duration_limit_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = hours,
                        onValueChange = { hours = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.duration_hours)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("custom-duration-hours")
                    )
                    OutlinedTextField(
                        value = minutes,
                        onValueChange = { minutes = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.duration_minutes)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        isError = minutes.any(Char::isDigit) && (minutes.toIntOrNull() ?: 60) !in 0..59,
                        modifier = Modifier.weight(1f).testTag("custom-duration-minutes")
                    )
                    OutlinedTextField(
                        value = seconds,
                        onValueChange = { seconds = it.filter(Char::isDigit).take(2) },
                        label = { Text(stringResource(R.string.duration_seconds)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Number,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { confirm() }),
                        isError = seconds.any(Char::isDigit) && (seconds.toIntOrNull() ?: 60) !in 0..59,
                        modifier = Modifier.weight(1f).testTag("custom-duration-seconds")
                    )
                }
                Text(
                    if (total != null) stringResource(R.string.custom_duration_summary, formatRecordLimitDuration(total))
                    else stringResource(R.string.custom_duration_range),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (total != null) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(onClick = confirm, enabled = total != null) {
                Text(stringResource(R.string.apply_limit))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

@Composable
private fun RecordLimitSelector(
    limit: RecordLimit,
    onSelect: (RecordLimit) -> Unit,
    liveFrameCount: Int?,
    liveExposureSeconds: Double?,
    limitPaused: Boolean,
    onContinueWithoutLimit: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var customFrames by remember { mutableStateOf(false) }
    var customDuration by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(6.dp)

    if (customFrames) CustomFrameLimitDialog(
        initial = limit,
        onDismiss = { customFrames = false },
        onConfirm = { onSelect(it); customFrames = false }
    )
    if (customDuration) CustomDurationLimitDialog(
        initial = limit,
        onDismiss = { customDuration = false },
        onConfirm = { onSelect(it); customDuration = false }
    )
    
    Box {
        Box(
            modifier = Modifier
                .clip(shape)
                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), shape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    role = Role.Button
                ) { expanded = true }
                .height(40.dp)
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.limit_label), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(4.dp))
                Text(
                    limit.displayText(),
                    fontSize = 12.sp,
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
            PopupWindowBackHandler(enabled = expanded) { expanded = false }
            Column(
                Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
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
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.custom_frame_limit)) },
                    onClick = {
                        customFrames = true
                        expanded = false
                    }
                )
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
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.custom_duration_limit)) },
                    onClick = {
                        customDuration = true
                        expanded = false
                    }
                )
                if (liveFrameCount != null || liveExposureSeconds != null) {
                    Divider()
                    Text(
                        stringResource(
                            R.string.record_limit_live_summary,
                            liveFrameCount ?: 0,
                            liveExposureSeconds ?: 0.0
                        ),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (limitPaused) {
                        Text(
                            stringResource(R.string.capture_limit_paused_hint),
                            modifier = Modifier.padding(horizontal = 12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.tertiary
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.capture_limit_continue)) },
                            onClick = {
                                onContinueWithoutLimit()
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TargetSelector(targetName: String, onTargetNameChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val normalizedTarget = CaptureTargetNames.canonical(targetName)
    val targets = CaptureTargetNames.solarSystemTargets + CaptureTargetNames.calibrationTargets
    val choices = if (normalizedTarget !in targets) listOf(normalizedTarget) + targets else targets
    Box {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.height(34.dp).testTag("record-target-selector"),
            contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp)
        ) {
            Text("${stringResource(R.string.target_label)} ${targetDisplayName(normalizedTarget)}", maxLines = 1, fontSize = 12.sp)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PopupWindowBackHandler(enabled = expanded) { expanded = false }
            choices.forEach { target ->
                DropdownMenuItem(text = { Text(targetDisplayName(target)) }, onClick = {
                    onTargetNameChange(target)
                    expanded = false
                })
            }
        }
    }
}

@Composable
private fun targetDisplayName(code: String): String = when (code) {
    "BIAS" -> stringResource(R.string.capture_target_bias)
    "DARK" -> stringResource(R.string.capture_target_dark)
    "FLAT" -> stringResource(R.string.capture_target_flat)
    "Jupiter" -> stringResource(R.string.capture_target_jupiter)
    "Saturn" -> stringResource(R.string.capture_target_saturn)
    "Mars" -> stringResource(R.string.capture_target_mars)
    "Venus" -> stringResource(R.string.capture_target_venus)
    "Mercury" -> stringResource(R.string.capture_target_mercury)
    "Moon" -> stringResource(R.string.capture_target_moon)
    "Sun" -> stringResource(R.string.capture_target_sun)
    "" -> "—"
    else -> code
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
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
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onOpenLibrary: () -> Unit = {},
    isFinalizing: Boolean = false,
    liveFrameCount: Int? = null,
    liveExposureSeconds: Double? = null,
    limitPaused: Boolean = false,
    onContinueWithoutLimit: () -> Unit = {}
) {
    if (compact) {
        Column(modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 12.dp, vertical = 4.dp)) {
            if (isFinalizing) Text(stringResource(R.string.recording_finishing), Modifier.testTag("record-finalizing"),
                style = MaterialTheme.typography.labelMedium)
            else if (isRecording) Text(stringResource(R.string.recording_status, ImageUtils.formatDuration(durationMs), frameCount,
                ImageUtils.formatFileSize(bytesWritten)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
            else Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TargetSelector(targetName, onTargetNameChange)
                CompactFormatMenu(stringResource(R.string.capture_photo_label), listOf("JPG", "FITS"), captureFormatLabel, onSelectCaptureFormat)
                CompactFormatMenu(stringResource(R.string.capture_video_label), listOf("SER", "PSER", "MP4", "FITS"), recordFormatLabel, onSelectRecordFormat)
                RecordLimitSelector(
                    recordLimit,
                    onSelectRecordLimit,
                    liveFrameCount,
                    liveExposureSeconds,
                    limitPaused,
                    onContinueWithoutLimit
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onOpenLibrary, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.PhotoLibrary, stringResource(R.string.wf_media))
                }
                OutlinedIconButton(onClick = onCapture, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Camera, stringResource(R.string.capture_desc), Modifier.size(22.dp))
                }
                OutlinedIconButton(onClick = if (isRecording) onStopRecord else onStartRecord,
                    enabled = !isFinalizing, modifier = Modifier.size(48.dp)) {
                    if (isFinalizing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(if (isRecording) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                        stringResource(if (isRecording) R.string.stop_recording else R.string.start_recording),
                        Modifier.size(30.dp), tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isFinalizing) {
            Text(stringResource(R.string.recording_finishing), Modifier.testTag("record-finalizing"),
                style = MaterialTheme.typography.labelSmall)
        } else if (isRecording) {
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
                        fontSize = 13.sp
                    ),
                    color = Color(0xFFFF6666)
                )
            }
        } else {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TargetSelector(targetName, onTargetNameChange)
                FormatToggle(listOf("JPG", "FITS"), captureFormatLabel, onSelectCaptureFormat)
                FormatToggle(listOf("SER", "PSER", "MP4", "FITS"), recordFormatLabel, onSelectRecordFormat)
                RecordLimitSelector(
                    recordLimit,
                    onSelectRecordLimit,
                    liveFrameCount,
                    liveExposureSeconds,
                    limitPaused,
                    onContinueWithoutLimit
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(14.dp),
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
                    .clickable(enabled = !isFinalizing) { if (isRecording) onStopRecord() else onStartRecord() },
                contentAlignment = Alignment.Center
            ) {
                if (isFinalizing) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                } else if (isRecording) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = stringResource(R.string.stop_recording),
                        tint = Color(0xFFFF4444),
                        modifier = Modifier.size(30.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.FiberManualRecord,
                        contentDescription = stringResource(R.string.start_recording),
                        tint = Color(0xFFFF4444),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactFormatMenu(label: String, formats: List<String>, selected: String, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, contentPadding = PaddingValues(horizontal = 4.dp)) {
            Text("$label $selected", style = MaterialTheme.typography.labelMedium)
            Icon(Icons.Default.ExpandMore, null, Modifier.size(16.dp))
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            PopupWindowBackHandler(enabled = open) { open = false }
            formats.forEach { format -> DropdownMenuItem(text = { Text(format) }, onClick = { onSelect(format); open = false }) }
        }
    }
}
