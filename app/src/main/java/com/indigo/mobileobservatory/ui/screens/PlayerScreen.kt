package com.indigo.mobileobservatory.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.MediaLibrary
import androidx.core.content.FileProvider
import android.widget.Toast
import com.indigo.mobileobservatory.stacking.StackBitmap
import com.indigo.mobileobservatory.stacking.StackFitsReader
import com.indigo.mobileobservatory.stacking.StackFitsPreview
import com.indigo.mobileobservatory.util.ImageUtils
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.json.JSONObject
import kotlin.math.asinh

@Composable
private fun RepeatableIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    onTap: () -> Unit,
    onRepeat: () -> Unit,
    size: androidx.compose.ui.unit.Dp = 36.dp,
    iconSize: androidx.compose.ui.unit.Dp = 20.dp
) {
    val scope = rememberCoroutineScope()
    var repeatJob by remember { mutableStateOf<Job?>(null) }

    Box(
        modifier = Modifier
            .size(size)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onTap()
                        repeatJob = scope.launch {
                            delay(300)
                            while (true) {
                                onRepeat()
                                delay(33)
                            }
                        }
                        tryAwaitRelease()
                        repeatJob?.cancel()
                        repeatJob = null
                    }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, description, tint = Color.White, modifier = Modifier.size(iconSize))
    }
}

// Preserve source compatibility for screens that previously imported these UI types.
typealias VideoFileInfo = com.indigo.mobileobservatory.recording.VideoFileInfo
typealias MediaCategory = com.indigo.mobileobservatory.recording.MediaCategory

private fun mediaCategoryLabel(category: MediaCategory) = when (category) {
    MediaCategory.SER_PSER -> R.string.media_category_ser
    MediaCategory.MP4 -> R.string.media_category_mp4
    MediaCategory.FITS -> R.string.media_category_fits
    MediaCategory.JPG -> R.string.media_category_photos
    MediaCategory.STACK_RESULTS -> R.string.media_category_results
}

private fun mediaEmptyHint(category: MediaCategory) = when (category) {
    MediaCategory.SER_PSER -> R.string.media_empty_ser
    MediaCategory.MP4 -> R.string.media_empty_mp4
    MediaCategory.FITS -> R.string.media_empty_fits
    MediaCategory.JPG -> R.string.media_empty_photos
    MediaCategory.STACK_RESULTS -> R.string.media_empty_results
}

/** Read-only content URI; the recorder's app-owned file is never exposed as a file:// URI. */
internal fun mediaVideoViewIntent(context: Context, file: File): Intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file), "video/mp4")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    if (context.findActivity() == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

private fun openMp4(context: Context, file: File) {
    try {
        context.startActivity(mediaVideoViewIntent(context, file))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.media_no_video_player, Toast.LENGTH_LONG).show()
    } catch (_: Exception) {
        Toast.makeText(context, R.string.media_video_open_failed, Toast.LENGTH_LONG).show()
    }
}

private fun firstFitsIn(file: File): File? {
    if (file.isFile) return file
    return file.listFiles()
        ?.filter { it.isFile && (it.extension.equals("fit", true) || it.extension.equals("fits", true)) }
        ?.sortedBy { it.name }
        ?.firstOrNull()
}

@Composable
fun PlayerScreen(
    recordingsDir: File?,
    capturesDir: File? = null,
    mountCoordinates: MountCoordinates? = null,
    onStackFile: ((File) -> Unit)? = null,
    initialFile: File? = null,
    initialCategory: MediaCategory = MediaCategory.SER_PSER,
    excludeFile: File? = null,
    onBack: () -> Unit
) {
    // Orientation belongs to CameraScreen's navigation owner. Mutating it again here,
    // especially during disposal, can request a second Activity configuration change.
    var selectedFile by remember { mutableStateOf<VideoFileInfo?>(null) }
    var plateSolveFile by remember { mutableStateOf<File?>(null) }
    var selectedCategory by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }
    var showFilePicker by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    var directFileUnavailable by remember { mutableStateOf(false) }
    LaunchedEffect(initialFile) {
        initialFile?.let { file ->
            val info = withContext(Dispatchers.IO) {
                if (file.absolutePath == excludeFile?.absolutePath) null else MediaLibrary.describe(file)
            }
            selectedFile = info
            showFilePicker = info == null
            directFileUnavailable = info == null
        }
    }

    plateSolveFile?.let { file ->
        PlateSolveScreen(
            initialFile = file,
            mountCoordinates = mountCoordinates,
            onBack = { plateSolveFile = null }
        )
        return
    }
    
    val trashDir = remember(recordingsDir) {
        recordingsDir?.let { File(it, ".trash").also { d -> d.mkdirs() } }
    }
    
    val currentFiles by produceState<List<VideoFileInfo>>(emptyList(), recordingsDir, capturesDir, selectedCategory, refreshTrigger, excludeFile) {
        value = withContext(Dispatchers.IO) { MediaLibrary.list(recordingsDir, capturesDir, selectedCategory, excludeFile) }
    }

    if (showFilePicker && selectedFile == null) {
        FilePickerView(
            recordingsDir = recordingsDir,
            capturesDir = capturesDir,
            onSelect = { info ->
                selectedFile = info
                showFilePicker = false
            },
            onBack = onBack,
            onStackFile = onStackFile,
            selectedCategory = selectedCategory,
            excludeFile = excludeFile,
            fileUnavailable = directFileUnavailable,
            onCategoryChange = { cat -> selectedCategory = cat }
        )
    } else {
        val info = selectedFile
        if (info != null) {
            key(info.file.absolutePath) {
            PlaybackView(
                fileInfo = info,
                onBack = {
                    selectedFile = null
                    showFilePicker = true
                },
                onSolve = { plateSolveFile = firstFitsIn(info.file) ?: info.file },
                onStackFile = onStackFile?.let { launch -> { file: File ->
                    selectedFile = null
                    showFilePicker = true
                    launch(file)
                } },
                onDelete = {
                    val currentIndex = currentFiles.indexOfFirst { it.file.absolutePath == info.file.absolutePath }
                    if (trashDir != null && info.file.exists()) {
                        val targetFile = File(trashDir, info.file.name)
                        info.file.renameTo(targetFile)
                    }
                    refreshTrigger++
                    val newFiles = currentFiles.filter { it.file.absolutePath != info.file.absolutePath }
                    val nextIndex = when {
                        newFiles.isEmpty() -> -1
                        currentIndex >= newFiles.size -> newFiles.size - 1
                        else -> currentIndex
                    }
                    if (nextIndex >= 0 && nextIndex < newFiles.size) {
                        selectedFile = newFiles[nextIndex]
                    } else {
                        selectedFile = null
                        showFilePicker = true
                    }
                }
            )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilePickerView(
    recordingsDir: File?,
    capturesDir: File? = null,
    onSelect: (VideoFileInfo) -> Unit,
    onBack: () -> Unit,
    onStackFile: ((File) -> Unit)? = null,
    selectedCategory: MediaCategory,
    excludeFile: File? = null,
    fileUnavailable: Boolean = false,
    onCategoryChange: (MediaCategory) -> Unit = {}
) {
    val context = LocalContext.current
    var showTrash by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<VideoFileInfo?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    val trashDir = remember(recordingsDir) {
        recordingsDir?.let { File(it, ".trash").also { d -> d.mkdirs() } }
    }

    var loading by remember { mutableStateOf(true) }
    val files by produceState<List<VideoFileInfo>>(emptyList(), recordingsDir, capturesDir, selectedCategory, refreshTrigger, excludeFile) {
        loading = true
        value = emptyList()
        value = withContext(Dispatchers.IO) { MediaLibrary.list(recordingsDir, capturesDir, selectedCategory, excludeFile) }
        loading = false
    }
    
    val trashFiles by produceState<List<VideoFileInfo>>(emptyList(), trashDir, showTrash, refreshTrigger) {
        value = withContext(Dispatchers.IO) {
            if (showTrash && trashDir != null && trashDir.exists()) {
                trashDir.listFiles()?.map { f ->
                    VideoFileInfo(f, f.name, f.length(), f.extension.uppercase())
                }?.sortedByDescending { it.file.lastModified() } ?: emptyList()
            } else emptyList()
        }
    }
    
    if (fileToDelete != null) {
        AlertDialog(
            onDismissRequest = { fileToDelete = null },
            title = { Text(stringResource(R.string.delete_file)) },
            text = { Text(stringResource(R.string.move_to_trash, fileToDelete?.name ?: "")) },
            confirmButton = {
                TextButton(onClick = {
                    fileToDelete?.let { info ->
                        trashDir?.let { trash ->
                            info.file.renameTo(File(trash, info.name))
                            refreshTrigger++
                        }
                    }
                    fileToDelete = null
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { fileToDelete = null }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
            }
            Text(
                if (showTrash) stringResource(R.string.trash) else stringResource(R.string.media_library_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .padding(start = 8.dp)
                    .weight(1f)
            )
            IconButton(onClick = { showTrash = !showTrash }) {
                Icon(
                    if (showTrash) Icons.Default.FolderOpen else Icons.Default.Delete,
                    if (showTrash) stringResource(R.string.files) else stringResource(R.string.trash)
                )
            }
        }
        
        if (!showTrash) {
            Text(stringResource(if (fileUnavailable) R.string.media_file_unavailable else R.string.media_library_hint),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MediaCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { 
                            onCategoryChange(cat)
                        },
                        label = { Text(stringResource(mediaCategoryLabel(cat)), fontSize = 11.sp) },
                        modifier = Modifier.height(32.dp)
                    )
                }
            }
        }

        val displayFiles = if (showTrash) trashFiles else files
        
        if (displayFiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (showTrash) Icons.Default.Delete else Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        if (showTrash) stringResource(R.string.trash_empty) else if (loading) stringResource(R.string.media_loading)
                        else stringResource(mediaEmptyHint(selectedCategory)),
                        modifier = Modifier.padding(horizontal = 24.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            if (showTrash) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            trashFiles.forEach { info ->
                                val originalDir = when {
                                    info.format.equals("SER", true) || info.format.equals("PSER", true) -> 
                                        File(recordingsDir, info.format.uppercase())
                                    info.format.equals("MP4", true) -> File(recordingsDir, "MP4")
                                    info.format.equals("FIT", true) || info.format.equals("FITS", true) -> 
                                        File(if (info.name.startsWith("stack-", true)) capturesDir else recordingsDir, "FITS")
                                    else -> File(capturesDir, "JPG")
                                }
                                originalDir.mkdirs()
                                info.file.renameTo(File(originalDir, info.name))
                            }
                            refreshTrigger++
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Restore, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.restore_all))
                    }
                    Button(
                        onClick = {
                            trashFiles.forEach { it.file.deleteRecursively() }
                            refreshTrigger++
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.empty_trash))
                    }
                }
            }
            
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                items(displayFiles, key = { it.file.absolutePath }) { info ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                if (!showTrash) {
                                    if (info.format.equals("MP4", true)) {
                                        openMp4(context, info.file)
                                        return@clickable
                                    }
                                    val canOpen = info.format.equals("SER", true) || 
                                                  info.format.equals("PSER", true) ||
                                                  info.format.equals("JPG", true) ||
                                                  info.format.equals("JPEG", true) ||
                                                  info.format.equals("PNG", true) ||
                                                  info.format.equals("FIT", true) ||
                                                  info.format.equals("FITS", true)
                                    if (canOpen) onSelect(info)
                                }
                            },
                        shape = MaterialTheme.shapes.medium,
                        tonalElevation = 1.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                when {
                                    info.format.equals("SER", true) || info.format.equals("PSER", true) -> 
                                        Icons.Default.Movie
                                    info.format.equals("MP4", true) -> Icons.Default.PlayCircle
                                    info.format.equals("JPG", true) || info.format.equals("JPEG", true) ||
                                    info.format.equals("PNG", true) -> Icons.Default.Image
                                    else -> Icons.Default.Description
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(28.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    info.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1
                                )
                                Text(
                                    "${info.format}  •  ${ImageUtils.formatFileSize(info.size)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                            if (showTrash) {
                                IconButton(onClick = {
                                    val originalDir = when {
                                        info.format.equals("SER", true) || info.format.equals("PSER", true) -> 
                                            File(recordingsDir, info.format.uppercase())
                                        info.format.equals("MP4", true) -> File(recordingsDir, "MP4")
                                        info.format.equals("FIT", true) || info.format.equals("FITS", true) -> 
                                            File(if (info.name.startsWith("stack-", true)) capturesDir else recordingsDir, "FITS")
                                        else -> File(capturesDir, "JPG")
                                    }
                                    originalDir.mkdirs()
                                    info.file.renameTo(File(originalDir, info.name))
                                    refreshTrigger++
                                }) {
                                    Icon(Icons.Default.Restore, stringResource(R.string.restore), modifier = Modifier.size(20.dp))
                                }
                            } else {
                                if (onStackFile != null && (info.format.equals("SER", true) || info.format.equals("PSER", true))) {
                                    val description = stringResource(R.string.stack_process_file)
                                    TextButton(onClick = { onStackFile(info.file) },
                                        modifier = Modifier.semantics { contentDescription = description }) {
                                        Icon(Icons.Default.Layers, null,
                                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text(stringResource(R.string.media_stack_action))
                                    }
                                }
                                IconButton(onClick = { fileToDelete = info }) {
                                    Icon(
                                        Icons.Default.Delete, 
                                        stringResource(R.string.delete),
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlaybackView(
    fileInfo: VideoFileInfo,
    onBack: () -> Unit,
    onSolve: () -> Unit = {},
    onStackFile: ((File) -> Unit)? = null,
    onDelete: () -> Unit = {}
) {
    val isImage = fileInfo.format.equals("JPG", true) ||
                  fileInfo.format.equals("JPEG", true) ||
                  fileInfo.format.equals("PNG", true) ||
                  fileInfo.format.equals("FIT", true) ||
                  fileInfo.format.equals("FITS", true)

    if (isImage) {
        ImageViewScreen(fileInfo = fileInfo, onBack = onBack, onSolve = onSolve, onDelete = onDelete)
        return
    }

    val reader = remember { SERReader(fileInfo.file) }
    var isOpened by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentFrame by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    var playJob by remember { mutableStateOf<Job?>(null) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var openJob by remember { mutableStateOf<Job?>(null) }
    var readerClosed by remember { mutableStateOf(false) }

    var totalFrames by remember { mutableIntStateOf(0) }

    var pendingAutoPlay by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        openJob = scope.launch(Dispatchers.IO) {
            try {
            val opened = reader.open()
            withContext(Dispatchers.Main) { if (!readerClosed) isOpened = opened }
            if (opened && isActive && !readerClosed) {
                withContext(Dispatchers.Main) {
                    totalFrames = reader.header?.frameCount ?: 0
                }
                val bmp = reader.readPreviewBitmap(0) { ensureActive() }
                if (bmp != null) {
                    withContext(Dispatchers.Main) {
                        if (!readerClosed) bitmap = bmp
                    }
                }
            }
            } finally {
                if (!isActive || readerClosed) reader.close()
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            readerClosed = true
            playJob?.cancel()
            seekJob?.cancel()
            openJob?.cancel()
            reader.close()
        }
    }

    fun closeThen(action: () -> Unit) {
        if (readerClosed) return
        readerClosed = true
        isPlaying = false
        pendingAutoPlay = false
        val pending = listOfNotNull(openJob, playJob, seekJob)
        pending.forEach { it.cancel() }
        scope.launch {
            pending.joinAll()
            withContext(Dispatchers.IO) { reader.close() }
            action()
        }
    }

    fun seekTo(frame: Int) {
        if (readerClosed || totalFrames <= 0) return
        val f = frame.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        currentFrame = f
        seekJob?.cancel()
        seekJob = scope.launch(Dispatchers.IO) {
            if (readerClosed) return@launch
            try {
                val bmp = reader.readPreviewBitmap(f) { ensureActive() } ?: return@launch
                if (readerClosed || !isActive) return@launch
                withContext(Dispatchers.Main) {
                    if (!readerClosed) bitmap = bmp
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
            } catch (_: Exception) {
            }
        }
    }

    fun togglePlay() {
        if (readerClosed || totalFrames <= 1) return
        if (isPlaying) {
            isPlaying = false
            playJob?.cancel()
        } else {
            if (currentFrame >= totalFrames - 1) {
                currentFrame = 0
            }
            isPlaying = true
            playJob = scope.launch(Dispatchers.Default) {
                val ts = reader.frameTimestamps
                val maxDisplayFps = 30
                val minDisplayIntervalMs = 1000L / maxDisplayFps
                val playStart = System.currentTimeMillis()
                val startFrame = currentFrame
                val startTick = if (ts != null && startFrame < ts.size) ts[startFrame] else 0L

                try {
                    while (isActive && isPlaying && !readerClosed && currentFrame < totalFrames - 1) {
                        val wallElapsed = System.currentTimeMillis() - playStart

                        val nextFrame: Int
                        if (ts != null && startTick > 0 && ts.size == totalFrames) {
                            val wallTicks = wallElapsed * 10_000L
                            val targetTick = startTick + wallTicks
                            var f = currentFrame + 1
                            while (f < totalFrames - 1 && f < ts.size && ts[f] < targetTick) {
                                f++
                            }
                            if (f < ts.size && ts[f] > targetTick && f > currentFrame + 1) {
                                f--
                            }
                            nextFrame = f.coerceAtMost(totalFrames - 1)
                        } else {
                            nextFrame = currentFrame + 1
                        }

                        if (nextFrame <= currentFrame || nextFrame >= totalFrames) {
                            delay(minDisplayIntervalMs)
                            continue
                        }

                        val renderStart = System.currentTimeMillis()
                        val bmp = withContext(Dispatchers.IO) {
                            if (readerClosed) null else reader.readPreviewBitmap(nextFrame) { ensureActive() }
                        }
                        if (bmp != null && !readerClosed) {
                            withContext(Dispatchers.Main) {
                                if (!readerClosed) {
                                    currentFrame = nextFrame
                                    bitmap = bmp
                                }
                            }
                        }
                        val renderTime = System.currentTimeMillis() - renderStart
                        val sleepMs = (minDisplayIntervalMs - renderTime).coerceAtLeast(1)
                        delay(sleepMs)
                    }
                } catch (_: Exception) {}
                withContext(Dispatchers.Main) {
                    isPlaying = false
                }
            }
        }
    }

    LaunchedEffect(isOpened, pendingAutoPlay) {
        if (isOpened && pendingAutoPlay && totalFrames > 1) {
            pendingAutoPlay = false
            togglePlay()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                closeThen(onBack)
            }) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                fileInfo.name,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (onStackFile != null && (fileInfo.format.equals("SER", true) || fileInfo.format.equals("PSER", true))) {
                IconButton(onClick = { closeThen { onStackFile(fileInfo.file) } }, enabled = !readerClosed) {
                    Icon(Icons.Default.Layers, stringResource(R.string.stack_planetary), tint = Color.White)
                }
            }
            IconButton(onClick = {
                closeThen(onDelete)
            }) {
                Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = Color(0xFFFF6B6B))
            }
        }
        if (isOpened && !readerClosed) {
            val h = reader.header
            val recFps = reader.recordedFps
            val fpsStr = if (recFps > 0) " %.0ffps".format(recFps) else ""
            Text(
                "${h?.width}x${h?.height} ${h?.pixelDepth}bit ${h?.frameCount}f$fpsStr" +
                    if (h?.isPSER == true) " PSER" else " SER",
                color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
            )
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.frame_desc, currentFrame),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (!isOpened) {
                Text(stringResource(R.string.failed_to_open_file), color = Color.Red, fontSize = 14.sp)
            } else {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            }
        }

        if (isOpened && totalFrames > 0) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xCC000000))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Slider(
                    value = currentFrame.toFloat(),
                    onValueChange = { seekTo(it.toInt()) },
                    onValueChangeFinished = {},
                    valueRange = 0f..(totalFrames - 1).toFloat().coerceAtLeast(0f),
                    modifier = Modifier.fillMaxWidth().height(24.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RepeatableIconButton(
                            icon = Icons.Default.FastRewind,
                            description = stringResource(R.string.rewind),
                            onTap = { seekTo(currentFrame - 20) },
                            onRepeat = { seekTo(currentFrame - 20) },
                            size = 36.dp,
                            iconSize = 20.dp
                        )
                        RepeatableIconButton(
                            icon = Icons.Default.SkipPrevious,
                            description = stringResource(R.string.prev_frame),
                            onTap = { seekTo(currentFrame - 1) },
                            onRepeat = { seekTo(currentFrame - 1) },
                            size = 36.dp,
                            iconSize = 20.dp
                        )
                        IconButton(
                            onClick = { togglePlay() },
                            modifier = Modifier.size(44.dp)
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                if (isPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                                tint = Color.White,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        RepeatableIconButton(
                            icon = Icons.Default.SkipNext,
                            description = stringResource(R.string.next_frame),
                            onTap = { seekTo(currentFrame + 1) },
                            onRepeat = { seekTo(currentFrame + 1) },
                            size = 36.dp,
                            iconSize = 20.dp
                        )
                        RepeatableIconButton(
                            icon = Icons.Default.FastForward,
                            description = stringResource(R.string.forward),
                            onTap = { seekTo(currentFrame + 20) },
                            onRepeat = { seekTo(currentFrame + 20) },
                            size = 36.dp,
                            iconSize = 20.dp
                        )
                    }

                    Text(
                        stringResource(R.string.frame_counter, currentFrame + 1, totalFrames),
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageViewScreen(
    fileInfo: VideoFileInfo,
    onBack: () -> Unit,
    onSolve: () -> Unit,
    onDelete: () -> Unit
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fitsInfo by remember { mutableStateOf("") }
    var resultMetadata by remember(fileInfo) { mutableStateOf("") }
    var showMetadata by remember(fileInfo) { mutableStateOf(false) }
    var lp750Enabled by remember(fileInfo) { mutableStateOf(false) }
    val failedToDecodeImage = stringResource(R.string.failed_to_decode_image)
    val unknownError = stringResource(R.string.unknown_error)

    if (showMetadata) {
        AlertDialog(
            onDismissRequest = { showMetadata = false },
            title = { Text(stringResource(R.string.section_info)) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(resultMetadata, style = MaterialTheme.typography.bodySmall) }
                }
            },
            confirmButton = { TextButton(onClick = { showMetadata = false }) { Text(stringResource(R.string.close)) } }
        )
    }

    LaunchedEffect(fileInfo, lp750Enabled) {
        withContext(Dispatchers.IO) {
            try {
                val taskContext = currentCoroutineContext()
                val isFits = fileInfo.format.equals("FIT", true) || fileInfo.format.equals("FITS", true)
                if (isFits) {
                    val source = firstFitsIn(fileInfo.file) ?: throw IllegalArgumentException("Empty FITS session")
                    val result = decodeFits(source, applyLp750 = lp750Enabled, cancelled = { !taskContext.isActive })
                    val metadata = if (StackFitsReader.isFloatingPoint(source)) StackFitsReader.readMetadata(source) else emptyMap()
                    val extra = if (fileInfo.file.isDirectory) {
                        val n = fileInfo.file.listFiles()?.count {
                            it.isFile && (it.extension.equals("fit", true) || it.extension.equals("fits", true))
                        } ?: 0
                        if (n > 1) "  $n frames" else ""
                    } else ""
                    withContext(Dispatchers.Main) {
                        bitmap = result.first
                        fitsInfo = result.second + extra
                        resultMetadata = if (metadata.isEmpty()) "" else JSONObject(metadata).toString(2).take(20000)
                        error = null
                    }
                } else {
                    val bmp = BitmapFactory.decodeFile(fileInfo.file.absolutePath)
                    val sidecar = File(fileInfo.file.parentFile, "${fileInfo.file.nameWithoutExtension}.json")
                    val metadata = if (sidecar.isFile && sidecar.length() <= 16 * 1024 * 1024) {
                        runCatching { JSONObject(sidecar.readText()).toString(2).take(20000) }.getOrDefault("")
                    } else ""
                    withContext(Dispatchers.Main) {
                        bitmap = bmp
                        resultMetadata = metadata
                        if (bmp == null) error = failedToDecodeImage
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = e.message ?: unknownError
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xCC000000))
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, stringResource(R.string.back), tint = Color.White)
            }
            Text(
                fileInfo.name,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (resultMetadata.isNotEmpty()) {
                IconButton(onClick = { showMetadata = true }) {
                    Icon(Icons.Default.Info, stringResource(R.string.section_info), tint = Color.White)
                }
            }
            if (fileInfo.format.equals("FIT", true) || fileInfo.format.equals("FITS", true)) {
                FilterChip(
                    selected = lp750Enabled,
                    onClick = { lp750Enabled = !lp750Enabled },
                    label = { Text("LP750", fontSize = 11.sp) },
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
            }
            IconButton(onClick = onSolve) {
                Icon(Icons.Default.Search, stringResource(R.string.plate_solve), tint = Color.White)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = Color(0xFFFF6B6B))
            }
        }
        if (fitsInfo.isNotEmpty()) {
            Text(fitsInfo, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp,
                fontFamily = FontFamily.Monospace, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val bmp = bitmap
            val err = error
            if (bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = stringResource(R.string.image_desc),
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else if (err != null) {
                Text(err, color = Color.Red, fontSize = 14.sp)
            } else {
                CircularProgressIndicator(color = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

private fun decodeFits(file: File, applyLp750: Boolean = false, cancelled: () -> Boolean = { false }): Pair<Bitmap, String> {
    if (StackFitsReader.isNormalizedStack(file)) {
        val result = StackFitsPreview.read(file, applyLp750, cancelled)
        val image = result.image
        val bitmap = StackBitmap.create(image, 2048) {
            if (cancelled()) throw CancellationException("FITS preview cancelled")
        }
        return bitmap to "${image.width}×${image.height} Float32 ${if (image.channels == 3) "RGB" else "Mono"}"
    }
    val bytes = file.readBytes()
    var offset = 0
    var width = 0; var height = 0; var bitpix = 16; var naxis = 2; var naxis3 = 1
    var bayerPat: String? = null
    var end = false

    while (!end && offset < bytes.size) {
        val blockEnd = (offset + 2880).coerceAtMost(bytes.size)
        while (offset < blockEnd) {
            if (offset + 80 > bytes.size) break
            val card = String(bytes, offset, 80)
            offset += 80
            when {
                card.startsWith("NAXIS1") -> width = card.substringAfter("=").substringBefore("/").trim().toIntOrNull() ?: 0
                card.startsWith("NAXIS2") -> height = card.substringAfter("=").substringBefore("/").trim().toIntOrNull() ?: 0
                card.startsWith("NAXIS3") -> naxis3 = card.substringAfter("=").substringBefore("/").trim().toIntOrNull() ?: 1
                card.startsWith("BITPIX") -> bitpix = card.substringAfter("=").substringBefore("/").trim().toIntOrNull() ?: 16
                card.startsWith("NAXIS ") -> naxis = card.substringAfter("=").substringBefore("/").trim().toIntOrNull() ?: 2
                card.startsWith("BAYERPAT") -> bayerPat = card.substringAfter("'").substringBefore("'").trim()
                card.startsWith("END ") || card.trimEnd() == "END" -> end = true
            }
            if (end) break
        }
        if (!end) offset = blockEnd
    }

    val dataOffset = ((offset + 2879) / 2880) * 2880
    if (width <= 0 || height <= 0) throw IllegalArgumentException("Invalid FITS: ${width}x${height}")

    val channels = if (naxis >= 3) naxis3 else 1
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val pixels = IntArray(width * height)
    val buf = ByteBuffer.wrap(bytes, dataOffset, bytes.size - dataOffset).order(ByteOrder.BIG_ENDIAN)

    if (channels == 1) {
        val rawVals = IntArray(width * height)
        var minVal = Int.MAX_VALUE; var maxVal = Int.MIN_VALUE
        for (i in rawVals.indices) {
            val v = when (bitpix) {
                8 -> buf.get().toInt() and 0xFF
                16 -> buf.short.toInt()
                32 -> buf.int
                -32 -> buf.float.toInt()
                else -> buf.short.toInt()
            }
            rawVals[i] = v
            if (v < minVal) minVal = v
            if (v > maxVal) maxVal = v
        }
        val range = (maxVal - minVal).coerceAtLeast(1)

        if (bayerPat != null) {
            val lp750Gains = if (applyLp750) {
                fitsLp750WhiteBalance(rawVals, width, height, bayerPat, minVal, range)
            } else null
            val rX: Int; val rY: Int
            when (bayerPat) {
                "RGGB" -> { rX = 0; rY = 0 }
                "GRBG" -> { rX = 1; rY = 0 }
                "GBRG" -> { rX = 0; rY = 1 }
                else   -> { rX = 1; rY = 1 } // BGGR
            }
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val bx = x % 2; val by = y % 2
                    val raw = rawVals[y * width + x]
                    val r: Int; val g: Int; val b: Int
                    when {
                        bx == rX && by == rY -> {
                            r = raw
                            g = fitsBayerAvgN(rawVals, x, y, width, height)
                            b = fitsBayerAvgD(rawVals, x, y, width, height)
                        }
                        bx != rX && by != rY -> {
                            b = raw
                            g = fitsBayerAvgN(rawVals, x, y, width, height)
                            r = fitsBayerAvgD(rawVals, x, y, width, height)
                        }
                        else -> {
                            g = raw
                            if (by == rY) {
                                r = fitsBayerAvgH(rawVals, x, y, width)
                                b = fitsBayerAvgV(rawVals, x, y, width, height)
                            } else {
                                b = fitsBayerAvgH(rawVals, x, y, width)
                                r = fitsBayerAvgV(rawVals, x, y, width, height)
                            }
                        }
                    }
                    val rn = (r - minVal).toFloat() / range
                    val gn = (g - minVal).toFloat() / range
                    val bn = (b - minVal).toFloat() / range
                    val converted = if (lp750Gains != null) ImageUtils.lp750Matrix(rn, gn, bn) else null
                    val gains = lp750Gains
                    val rs = converted?.let { gains?.let { g -> (it[0] * g[0] * 255f).toInt().coerceIn(0, 255) } }
                        ?: (rn * 255f).toInt().coerceIn(0, 255)
                    val gs = converted?.let { gains?.let { g -> (it[1] * g[1] * 255f).toInt().coerceIn(0, 255) } }
                        ?: (gn * 255f).toInt().coerceIn(0, 255)
                    val bs = converted?.let { gains?.let { g -> (it[2] * g[2] * 255f).toInt().coerceIn(0, 255) } }
                        ?: (bn * 255f).toInt().coerceIn(0, 255)
                    pixels[y * width + x] = (0xFF shl 24) or (rs shl 16) or (gs shl 8) or bs
                }
            }
        } else {
            for (i in rawVals.indices) {
                val g = ((rawVals[i] - minVal) * 255 / range).coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
        }
    } else {
        val planeSize = width * height
        val rPlane = IntArray(planeSize)
        val gPlane = IntArray(planeSize)
        val bPlane = IntArray(planeSize)
        val planes = arrayOf(rPlane, gPlane, bPlane)

        var globalMin = Int.MAX_VALUE; var globalMax = Int.MIN_VALUE
        for (ch in 0 until channels.coerceAtMost(3)) {
            for (i in 0 until planeSize) {
                val v = when (bitpix) {
                    8 -> buf.get().toInt() and 0xFF
                    16 -> buf.short.toInt()
                    32 -> buf.int
                    -32 -> buf.float.toInt()
                    else -> buf.short.toInt()
                }
                planes[ch][i] = v
                if (v < globalMin) globalMin = v
                if (v > globalMax) globalMax = v
            }
        }
        val range = (globalMax - globalMin).coerceAtLeast(1)
        val lp750Gains = if (applyLp750) {
            ImageUtils.lp750WhiteBalanceGains(
                rPlane.averageNormalized(globalMin, range),
                gPlane.averageNormalized(globalMin, range),
                bPlane.averageNormalized(globalMin, range)
            )
        } else null
        for (i in 0 until planeSize) {
            val rn = (rPlane[i] - globalMin).toFloat() / range
            val gn = (gPlane[i] - globalMin).toFloat() / range
            val bn = (bPlane[i] - globalMin).toFloat() / range
            val converted = if (lp750Gains != null) ImageUtils.lp750Matrix(rn, gn, bn) else null
            val gains = lp750Gains
            val r = converted?.let { gains?.let { g -> (it[0] * g[0] * 255f).toInt().coerceIn(0, 255) } }
                ?: (rn * 255f).toInt().coerceIn(0, 255)
            val g = converted?.let { gains?.let { g -> (it[1] * g[1] * 255f).toInt().coerceIn(0, 255) } }
                ?: (gn * 255f).toInt().coerceIn(0, 255)
            val b = converted?.let { gains?.let { g -> (it[2] * g[2] * 255f).toInt().coerceIn(0, 255) } }
                ?: (bn * 255f).toInt().coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    applyFitsAutoStretch(pixels)
    bmp.setPixels(pixels, 0, width, 0, 0, width, height)
    val typeStr = when {
        channels > 1 -> " RGB"
        bayerPat != null -> " Bayer($bayerPat)"
        else -> " Mono"
    }
    val info = "${width}x${height} ${bitpix}bit$typeStr"
    return Pair(bmp, info)
}

private fun applyFitsAutoStretch(pixels: IntArray) {
    if (pixels.isEmpty()) return
    val bins = IntArray(256)
    for (p in pixels) {
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        bins[((r * 299 + g * 587 + b * 114) / 1000).coerceIn(0, 255)]++
    }
    fun percentileBin(fraction: Double): Int {
        val target = (pixels.size * fraction).toInt().coerceIn(0, pixels.size - 1)
        var cumulative = 0
        for (i in bins.indices) {
            cumulative += bins[i]
            if (cumulative > target) return i
        }
        return bins.lastIndex
    }
    val black = percentileBin(0.002)
    val white = percentileBin(0.998)
    val range = (white - black).coerceAtLeast(1)
    val stretchDenominator = asinh(12.0).toFloat()
    fun stretch(channel: Int): Int {
        val normalized = ((channel - black).toFloat() / range).coerceIn(0f, 1f)
        return (asinh((normalized * 12f).toDouble()).toFloat() / stretchDenominator * 255f)
            .toInt().coerceIn(0, 255)
    }
    for (i in pixels.indices) {
        val p = pixels[i]
        val r = stretch((p ushr 16) and 0xFF)
        val g = stretch((p ushr 8) and 0xFF)
        val b = stretch(p and 0xFF)
        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}

private fun IntArray.averageNormalized(minValue: Int, range: Int): Float {
    if (isEmpty()) return 0f
    var sum = 0.0
    val step = (size / 10000).coerceAtLeast(1)
    var count = 0
    for (i in indices step step) {
        sum += (this[i] - minValue).toDouble() / range
        count++
    }
    return if (count == 0) 0f else (sum / count).toFloat()
}

private fun fitsLp750WhiteBalance(
    raw: IntArray,
    width: Int,
    height: Int,
    bayerPat: String,
    minValue: Int,
    range: Int
): FloatArray {
    val rX: Int; val rY: Int
    when (bayerPat) {
        "RGGB" -> { rX = 0; rY = 0 }
        "GRBG" -> { rX = 1; rY = 0 }
        "GBRG" -> { rX = 0; rY = 1 }
        else -> { rX = 1; rY = 1 }
    }
    var sumR = 0.0; var sumG = 0.0; var sumB = 0.0; var count = 0
    val step = 4
    for (y in 0 until height - 1 step step * 2) {
        for (x in 0 until width - 1 step step * 2) {
            val v00 = raw[y * width + x]
            val v10 = raw[y * width + x + 1]
            val v01 = raw[(y + 1) * width + x]
            val v11 = raw[(y + 1) * width + x + 1]
            val r: Int; val g1: Int; val g2: Int; val b: Int
            when {
                rX == 0 && rY == 0 -> { r = v00; g1 = v10; g2 = v01; b = v11 }
                rX == 1 && rY == 0 -> { g1 = v00; r = v10; b = v01; g2 = v11 }
                rX == 0 && rY == 1 -> { g1 = v00; b = v10; r = v01; g2 = v11 }
                else -> { b = v00; g1 = v10; g2 = v01; r = v11 }
            }
            sumR += r; sumG += (g1 + g2) * 0.5; sumB += b; count++
        }
    }
    if (count == 0) return floatArrayOf(1f, 1f, 1f)
    return ImageUtils.lp750WhiteBalanceGains(
        ((sumR / count - minValue) / range).toFloat(),
        ((sumG / count - minValue) / range).toFloat(),
        ((sumB / count - minValue) / range).toFloat()
    )
}

private fun fitsBayerAvgN(vals: IntArray, x: Int, y: Int, w: Int, h: Int): Int {
    var s = 0; var c = 0
    if (x > 0) { s += vals[y * w + x - 1]; c++ }
    if (x < w - 1) { s += vals[y * w + x + 1]; c++ }
    if (y > 0) { s += vals[(y - 1) * w + x]; c++ }
    if (y < h - 1) { s += vals[(y + 1) * w + x]; c++ }
    return if (c > 0) s / c else 0
}

private fun fitsBayerAvgD(vals: IntArray, x: Int, y: Int, w: Int, h: Int): Int {
    var s = 0; var c = 0
    if (x > 0 && y > 0) { s += vals[(y - 1) * w + x - 1]; c++ }
    if (x < w - 1 && y > 0) { s += vals[(y - 1) * w + x + 1]; c++ }
    if (x > 0 && y < h - 1) { s += vals[(y + 1) * w + x - 1]; c++ }
    if (x < w - 1 && y < h - 1) { s += vals[(y + 1) * w + x + 1]; c++ }
    return if (c > 0) s / c else 0
}

private fun fitsBayerAvgH(vals: IntArray, x: Int, y: Int, w: Int): Int {
    var s = 0; var c = 0
    if (x > 0) { s += vals[y * w + x - 1]; c++ }
    if (x < w - 1) { s += vals[y * w + x + 1]; c++ }
    return if (c > 0) s / c else 0
}

private fun fitsBayerAvgV(vals: IntArray, x: Int, y: Int, w: Int, h: Int): Int {
    var s = 0; var c = 0
    if (y > 0) { s += vals[(y - 1) * w + x]; c++ }
    if (y < h - 1) { s += vals[(y + 1) * w + x]; c++ }
    return if (c > 0) s / c else 0
}
