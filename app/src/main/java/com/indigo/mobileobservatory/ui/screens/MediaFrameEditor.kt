package com.indigo.mobileobservatory.ui.screens

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.media.MediaTrash
import com.indigo.mobileobservatory.recording.RecordingFrameSelection
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.SerFrameEditor
import com.indigo.mobileobservatory.stacking.fitsSequenceMembers
import com.indigo.mobileobservatory.stacking.previewFitsFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class FitsMember(val file: File, val bytes: Long = file.length(), val modified: Long = file.lastModified())

@Composable
internal fun MediaFrameEditor(
    file: File,
    trash: File?,
    isRecording: (File) -> Boolean,
    onDismiss: () -> Unit,
    onEdited: (failed: Int) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sourceBusy by rememberUpdatedState(isRecording)
    val directory = remember(file) { file.isDirectory }
    val snapshot by produceState<Result<Pair<SerFrameEditor.Layout?, List<FitsMember>>>?>(null, file) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                check(!sourceBusy(file)) { "recording_active" }
                if (directory) {
                    checkNotNull(trash) { "storage_unavailable" }
                    val frames = fitsSequenceMembers(file).map { FitsMember(it) }
                    check(frames.isNotEmpty()) { "stack_fits_empty" }
                    null to frames
                } else SerFrameEditor.readLayout(file) to emptyList()
            }
        }
    }
    val result = snapshot
    val info = result?.getOrNull()
    if (info == null) {
        AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.stack_review_frames)) },
            text = {
                if (result == null) CircularProgressIndicator()
                else Text(stringResource(frameEditMessage(result.exceptionOrNull()?.message)))
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } })
        return
    }

    val (recording, members) = info
    val total = recording?.frames ?: members.size.toLong()
    val identity = "${file.absolutePath}|${info.hashCode()}"
    var pending by remember(identity) { mutableStateOf<RecordingFrameSelection?>(null) }
    var progress by remember(identity) { mutableIntStateOf(-1) }
    var operationError by remember(identity) { mutableStateOf<String?>(null) }
    val working = progress >= 0
    val filenames = remember(members) { members.map { it.file.name } }
    key(identity) {
        FrameReview(total, filenames, working, progress, operationError,
            load = { index -> withContext(Dispatchers.IO) {
                check(!sourceBusy(file)) { "recording_active" }
                if (directory) previewFitsFrame(members[index.toInt()].file) { ensureActive() }
                else SERReader(file).use { reader ->
                    check(reader.open()) { reader.lastError ?: "frame_edit_invalid_ser" }
                    reader.readPreviewBitmap(index.toInt(), maxPixels = 262_144) { ensureActive() }
                        ?: error("frame_edit_invalid_ser")
                }
            } }, onDismiss = { if (!working) onDismiss() }, onRequest = { pending = it })
    }
    pending?.let { selection ->
        AlertDialog(onDismissRequest = { pending = null },
            title = { Text(stringResource(R.string.frame_edit_confirm_title)) },
            text = { Text(stringResource(if (directory) R.string.frame_edit_confirm_fits else R.string.frame_edit_confirm_ser,
                selection.total - selection.count)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    progress = 0
                    operationError = null
                    scope.launch {
                        try {
                            val failed = withContext(Dispatchers.IO) {
                                check(!sourceBusy(file)) { "recording_active" }
                                if (recording != null) {
                                    SerFrameEditor.removeExcluded(file, recording, selection, { sourceBusy(file) },
                                        { ensureActive() }) { copied, bytes ->
                                        progress = (100.0 * copied / bytes).toInt()
                                    }
                                    0
                                } else {
                                    check(fitsSequenceMembers(file).map { FitsMember(it) } == members) { "source_changed" }
                                    val target = checkNotNull(trash)
                                    var failedFrames = 0
                                    var completed = 0L
                                    for (range in selection.excluded) for (index in range) {
                                        ensureActive()
                                        val member = members[index.toInt()]
                                        if (sourceBusy(file) || FitsMember(member.file) != member ||
                                            !MediaTrash.moveSequenceFrame(member.file, target)) failedFrames++
                                        completed++
                                        progress = (100.0 * completed / (selection.total - selection.count)).toInt()
                                    }
                                    failedFrames
                                }
                            }
                            if (failed.toLong() == selection.total - selection.count) {
                                operationError = context.getString(R.string.media_trash_failed)
                            } else onEdited(failed)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            operationError = context.getString(frameEditMessage(failure.message))
                        } finally {
                            progress = -1
                        }
                    }
                }) { Text(stringResource(R.string.stack_apply_frame_selection)) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

private fun frameEditMessage(code: String?) = when (code) {
    "source_changed" -> R.string.stack_reason_source
    "recording_active" -> R.string.stack_recording_excluded
    "stack_fits_empty" -> R.string.stack_fits_empty
    "storage_full", "storage_unavailable" -> R.string.stack_reason_storage
    "frame_edit_invalid_ser" -> R.string.frame_edit_invalid_ser
    else -> R.string.frame_edit_failed
}

@Composable
private fun FrameReview(
    total: Long,
    filenames: List<String>,
    working: Boolean,
    progress: Int,
    error: String?,
    load: suspend (Long) -> Bitmap,
    onDismiss: () -> Unit,
    onRequest: (RecordingFrameSelection) -> Unit
) {
    val saver = listSaver<RecordingFrameSelection, Long>(
        save = { selection -> listOf(selection.total) + selection.excluded.flatMap { listOf(it.first, it.last) } },
        restore = { values ->
            RecordingFrameSelection.fromExcluded(values.first(), values.drop(1).chunked(2).map { it[0]..it[1] })
        }
    )
    var selection by rememberSaveable(stateSaver = saver) { mutableStateOf(RecordingFrameSelection(total)) }
    var frame by rememberSaveable { mutableLongStateOf(0) }
    var rangeOpen by rememberSaveable { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.padding(16.dp).widthIn(max = 620.dp).fillMaxWidth().fillMaxHeight(.92f),
            shape = MaterialTheme.shapes.large) {
            Column(Modifier.padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.stack_review_frames), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.stack_frames_kept, selection.count, selection.total),
                    style = MaterialTheme.typography.bodySmall)
                if (working) {
                    LinearProgressIndicator(progress = progress / 100f, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.frame_edit_writing))
                } else {
                FramePreview(frame, Modifier.fillMaxWidth().heightIn(min = 140.dp, max = 280.dp), load)
                FrameNavigation(frame, total, { frame = it }, filenames.getOrNull(frame.toInt()))
                val excluded = selection.isExcluded(frame)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(if (excluded) R.string.stack_frame_excluded else R.string.stack_frame_kept))
                    OutlinedButton(onClick = {
                        selection = if (excluded) selection.restore(frame..frame) else selection.exclude(frame..frame)
                    }) { Text(stringResource(if (excluded) R.string.stack_restore_frame else R.string.stack_exclude_frame)) }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { rangeOpen = true }) { Text(stringResource(R.string.stack_edit_frame_range)) }
                    TextButton(onClick = { selection = RecordingFrameSelection(selection.total) },
                        enabled = selection.excluded.isNotEmpty()) {
                        Text(stringResource(R.string.stack_restore_all_frames))
                    }
                }
                if (selection.count == 0L) Text(stringResource(R.string.stack_no_kept_frames), color = MaterialTheme.colorScheme.error)
                }
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !working) { Text(stringResource(R.string.cancel)) }
                    Button(onClick = { onRequest(selection) }, enabled = !working && selection.count in 1 until total) {
                        Text(stringResource(R.string.stack_apply_frame_selection))
                    }
                }
            }
        }
    }
    if (rangeOpen) FrameRangeDialog(frame, selection.total, { rangeOpen = false }) { range, remove ->
        selection = if (remove) selection.exclude(range) else selection.restore(range)
        rangeOpen = false
    }
}

@Composable
private fun FrameRangeDialog(frame: Long, total: Long, onDismiss: () -> Unit,
    onApply: (LongRange, Boolean) -> Unit) {
    var start by rememberSaveable { mutableStateOf((frame + 1).toString()) }
    var end by rememberSaveable { mutableStateOf((frame + 1).toString()) }
    val first = start.toLongOrNull()
    val last = end.toLongOrNull()
    val range = if (first != null && last != null && first in 1..total && last in first..total)
        (first - 1)..(last - 1) else null
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stack_edit_frame_range)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.stack_frame_range_hint, total))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FrameNumberField(start, { start = it }, R.string.stack_frame_start, Modifier.weight(1f))
                    FrameNumberField(end, { end = it }, R.string.stack_frame_end, Modifier.weight(1f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { range?.let { onApply(it, true) } }, enabled = range != null) {
                Text(stringResource(R.string.stack_exclude_range))
            }
        },
        dismissButton = {
            TextButton(onClick = { range?.let { onApply(it, false) } }, enabled = range != null) {
                Text(stringResource(R.string.stack_restore_range))
            }
        }
    )
}

@Composable
internal fun FitsSequencePreviewScreen(directory: File, revision: Int, onBack: () -> Unit,
    onStack: (() -> Unit)?, onEdit: (() -> Unit)?) {
    val context = LocalContext.current
    val shareFiles = rememberMediaShareLauncher(leaseDirectory = directory) {
        Toast.makeText(context, R.string.media_share_failed, Toast.LENGTH_LONG).show()
    }
    val members by produceState<List<File>?>(null, directory, revision) {
        value = withContext(Dispatchers.IO) { fitsSequenceMembers(directory) }
    }
    var index by rememberSaveable(directory.absolutePath) { mutableLongStateOf(0) }
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, stringResource(R.string.back)) }
            Text(directory.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.titleMedium)
            if (onStack != null) TextButton(onClick = onStack, enabled = members?.isNotEmpty() == true) {
                Text(stringResource(R.string.stack_offline_title))
            }
            MediaActionsMenu(stringResource(R.string.media_more_actions), enabled = members?.isNotEmpty() == true) { close ->
                DropdownMenuItem(text = { Text(stringResource(R.string.media_share_first_frame)) },
                    onClick = {
                        close()
                        members?.firstOrNull()?.let { shareFiles(listOf(it)) }
                    })
                if (onEdit != null) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.stack_review_frames)) },
                        onClick = { close(); onEdit() })
                }
            }
        }
        val files = members
        if (files.isNullOrEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (files == null) CircularProgressIndicator()
                else Text(stringResource(R.string.stack_fits_empty))
            }
        } else {
            val position = index.coerceIn(0, files.lastIndex.toLong())
            key(revision, files[position.toInt()]) {
                FramePreview(position, Modifier.fillMaxWidth().weight(1f)) { frame ->
                    withContext(Dispatchers.IO) { previewFitsFrame(files[frame.toInt()]) { ensureActive() } }
                }
            }
            FrameNavigation(position, files.size.toLong(), { index = it }, files[position.toInt()].name)
        }
    }
}

@Composable
private fun FrameNavigation(index: Long, total: Long, onMove: (Long) -> Unit, filename: String?) {
    var number by rememberSaveable(index) { mutableStateOf((index + 1).toString()) }
    val destination = number.toLongOrNull()?.takeIf { it in 1..total }
    Column {
        Text(stringResource(R.string.frame_counter, index + 1, total), style = MaterialTheme.typography.labelLarge)
        if (filename != null) Text(filename, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { onMove(index - 1) }, enabled = index > 0) {
                Icon(Icons.Default.ChevronLeft, stringResource(R.string.stack_previous_frame))
            }
            IconButton(onClick = { onMove(index + 1) }, enabled = index + 1 < total) {
                Icon(Icons.Default.ChevronRight, stringResource(R.string.stack_next_frame))
            }
            FrameNumberField(number, { number = it }, R.string.stack_frame_number, Modifier.weight(1f))
            TextButton(onClick = { destination?.let { onMove(it - 1) } }, enabled = destination != null) {
                Text(stringResource(R.string.go))
            }
        }
    }
}

@Composable
private fun FrameNumberField(value: String, onChange: (String) -> Unit, label: Int, modifier: Modifier) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit).take(18)) }, modifier,
        label = { Text(stringResource(label)) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun FramePreview(index: Long, modifier: Modifier, load: suspend (Long) -> Bitmap) {
    val currentLoad by rememberUpdatedState(load)
    val preview by produceState<Result<Bitmap>?>(null, index) {
        value = null
        value = try { Result.success(currentLoad(index)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: OutOfMemoryError) { Result.failure(IllegalStateException("memory_budget")) }
        catch (failure: Exception) { Result.failure(failure) }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val result = preview
        val bitmap = result?.getOrNull()
        when {
            bitmap != null -> Image(bitmap.asImageBitmap(), stringResource(R.string.frame_desc, index + 1),
                Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            result == null -> CircularProgressIndicator()
            else -> Text(stringResource(R.string.stack_frame_preview_failed))
        }
    }
}
