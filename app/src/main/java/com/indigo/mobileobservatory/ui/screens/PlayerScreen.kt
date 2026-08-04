package com.indigo.mobileobservatory.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.util.ImageUtils
import kotlinx.coroutines.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

data class VideoFileInfo(
    val file: File,
    val name: String,
    val size: Long,
    val format: String
)

enum class MediaCategory(val displayName: String, val extensions: List<String>) {
    SER_PSER("SER/PSER", listOf("ser", "pser")),
    MP4("MP4", listOf("mp4")),
    FITS("FITS", listOf("fit", "fits")),
    JPG("JPG", listOf("jpg", "jpeg", "png"))
}

@Composable
fun PlayerScreen(
    recordingsDir: File?,
    capturesDir: File? = null,
    mountCoordinates: MountCoordinates? = null,
    onBack: () -> Unit
) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            if (previousOrientation != null) {
                activity.requestedOrientation = previousOrientation
            }
        }
    }

    var selectedFile by remember { mutableStateOf<VideoFileInfo?>(null) }
    var plateSolveFile by remember { mutableStateOf<File?>(null) }
    var selectedCategory by remember { mutableStateOf(MediaCategory.SER_PSER) }
    var showFilePicker by remember { mutableStateOf(true) }
    var refreshTrigger by remember { mutableIntStateOf(0) }

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
    
    val currentFiles = remember(recordingsDir, capturesDir, selectedCategory, refreshTrigger) {
        val allFiles = mutableListOf<VideoFileInfo>()
        val searchDirs = when (selectedCategory) {
            MediaCategory.SER_PSER -> listOfNotNull(
                recordingsDir?.let { File(it, "SER") },
                recordingsDir?.let { File(it, "PSER") }
            )
            MediaCategory.MP4 -> listOfNotNull(
                recordingsDir?.let { File(it, "MP4") }
            )
            MediaCategory.FITS -> listOfNotNull(
                capturesDir?.let { File(it, "FITS") }
            )
            MediaCategory.JPG -> listOfNotNull(
                capturesDir?.let { File(it, "JPG") }
            )
        }
        searchDirs.forEach { dir ->
            dir.listFiles()?.filter { f -> 
                selectedCategory.extensions.any { ext -> f.extension.equals(ext, true) }
            }?.forEach { f ->
                allFiles.add(VideoFileInfo(f, f.name, f.length(), f.extension.uppercase()))
            }
        }
        allFiles.sortedByDescending { it.file.lastModified() }
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
            onCategoryChange = { cat -> selectedCategory = cat }
        )
    } else {
        val info = selectedFile
        if (info != null) {
            PlaybackView(
                fileInfo = info,
                onBack = {
                    selectedFile = null
                    showFilePicker = true
                },
                onSolve = { plateSolveFile = info.file },
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
    onCategoryChange: (MediaCategory) -> Unit = {}
) {
    var selectedCategory by remember { mutableStateOf(MediaCategory.SER_PSER) }
    var showTrash by remember { mutableStateOf(false) }
    var fileToDelete by remember { mutableStateOf<VideoFileInfo?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    val trashDir = remember(recordingsDir) {
        recordingsDir?.let { File(it, ".trash").also { d -> d.mkdirs() } }
    }

    val files = remember(recordingsDir, capturesDir, selectedCategory, refreshTrigger) {
        val allFiles = mutableListOf<VideoFileInfo>()
        val searchDirs = when (selectedCategory) {
            MediaCategory.SER_PSER -> listOfNotNull(
                recordingsDir?.let { File(it, "SER") },
                recordingsDir?.let { File(it, "PSER") }
            )
            MediaCategory.MP4 -> listOfNotNull(
                recordingsDir?.let { File(it, "MP4") }
            )
            MediaCategory.FITS -> listOfNotNull(
                capturesDir?.let { File(it, "FITS") }
            )
            MediaCategory.JPG -> listOfNotNull(
                capturesDir?.let { File(it, "JPG") }
            )
        }
        searchDirs.forEach { dir ->
            dir.listFiles()?.filter { f -> 
                selectedCategory.extensions.any { ext -> f.extension.equals(ext, true) }
            }?.forEach { f ->
                allFiles.add(VideoFileInfo(f, f.name, f.length(), f.extension.uppercase()))
            }
        }
        allFiles.sortedByDescending { it.file.lastModified() }
    }
    
    val trashFiles = remember(trashDir, showTrash, refreshTrigger) {
        if (showTrash && trashDir != null && trashDir.exists()) {
            trashDir.listFiles()?.map { f ->
                VideoFileInfo(f, f.name, f.length(), f.extension.uppercase())
            }?.sortedByDescending { it.file.lastModified() } ?: emptyList()
        } else emptyList()
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
                if (showTrash) stringResource(R.string.trash) else stringResource(R.string.files),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                MediaCategory.entries.forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { 
                            selectedCategory = cat
                            onCategoryChange(cat)
                        },
                        label = { Text(cat.displayName, fontSize = 11.sp) },
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
                        if (showTrash) stringResource(R.string.trash_empty) else stringResource(R.string.no_files_found),
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
                                        File(recordingsDir, "FITS")
                                    else -> File(recordingsDir, "JPG")
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
                            trashFiles.forEach { it.file.delete() }
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
                                            File(recordingsDir, "FITS")
                                        else -> File(recordingsDir, "JPG")
                                    }
                                    originalDir.mkdirs()
                                    info.file.renameTo(File(originalDir, info.name))
                                    refreshTrigger++
                                }) {
                                    Icon(Icons.Default.Restore, stringResource(R.string.restore), modifier = Modifier.size(20.dp))
                                }
                            } else {
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
    var readerClosed by remember { mutableStateOf(false) }

    var totalFrames by remember { mutableIntStateOf(0) }

    var pendingAutoPlay by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            isOpened = reader.open()
            if (isOpened) {
                withContext(Dispatchers.Main) {
                    totalFrames = reader.header?.frameCount ?: 0
                }
                val data = reader.readFrame(0)
                if (data != null) {
                    val bmp = reader.frameToBitmap(data)
                    if (bmp != null) {
                        withContext(Dispatchers.Main) {
                            bitmap = bmp
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            readerClosed = true
            playJob?.cancel()
            reader.close()
        }
    }

    var seekJob: kotlinx.coroutines.Job? = null
    fun seekTo(frame: Int) {
        if (readerClosed || totalFrames <= 0) return
        val f = frame.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))
        currentFrame = f
        seekJob?.cancel()
        seekJob = scope.launch(Dispatchers.IO) {
            if (readerClosed) return@launch
            try {
                val data = reader.readFrame(f) ?: return@launch
                if (readerClosed || !isActive) return@launch
                val bmp = reader.frameToBitmap(data) ?: return@launch
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
                        val data = withContext(Dispatchers.IO) {
                            if (readerClosed) null else reader.readFrame(nextFrame)
                        }
                        if (data != null && !readerClosed) {
                            val bmp = withContext(Dispatchers.IO) { reader.frameToBitmap(data) }
                            if (bmp != null) {
                                withContext(Dispatchers.Main) {
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
                playJob?.cancel()
                reader.close()
                onBack()
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
            IconButton(onClick = {
                playJob?.cancel()
                reader.close()
                onDelete()
            }) {
                Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = Color(0xFFFF6B6B))
            }
            if (isOpened) {
                val h = reader.header
                val recFps = reader.recordedFps
                val fpsStr = if (recFps > 0) " %.0ffps".format(recFps) else ""
                Text(
                    "${h?.width}x${h?.height} ${h?.pixelDepth}bit ${h?.frameCount}f$fpsStr" +
                            if (h?.isPSER == true) " PSER" else " SER",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
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
    val failedToDecodeImage = stringResource(R.string.failed_to_decode_image)
    val unknownError = stringResource(R.string.unknown_error)

    LaunchedEffect(fileInfo) {
        withContext(Dispatchers.IO) {
            try {
                val isFits = fileInfo.format.equals("FIT", true) || fileInfo.format.equals("FITS", true)
                if (isFits) {
                    val result = decodeFits(fileInfo.file)
                    withContext(Dispatchers.Main) {
                        bitmap = result.first
                        fitsInfo = result.second
                    }
                } else {
                    val bmp = BitmapFactory.decodeFile(fileInfo.file.absolutePath)
                    withContext(Dispatchers.Main) {
                        bitmap = bmp
                        if (bmp == null) error = failedToDecodeImage
                    }
                }
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
            if (fitsInfo.isNotEmpty()) {
                Text(fitsInfo, color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            IconButton(onClick = onSolve) {
                Icon(Icons.Default.Search, stringResource(R.string.plate_solve), tint = Color.White)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = Color(0xFFFF6B6B))
            }
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

private fun decodeFits(file: File): Pair<Bitmap, String> {
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
                    val rs = ((r - minVal) * 255 / range).coerceIn(0, 255)
                    val gs = ((g - minVal) * 255 / range).coerceIn(0, 255)
                    val bs = ((b - minVal) * 255 / range).coerceIn(0, 255)
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
        for (i in 0 until planeSize) {
            val r = ((rPlane[i] - globalMin) * 255 / range).coerceIn(0, 255)
            val g = ((gPlane[i] - globalMin) * 255 / range).coerceIn(0, 255)
            val b = ((bPlane[i] - globalMin) * 255 / range).coerceIn(0, 255)
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }

    bmp.setPixels(pixels, 0, width, 0, 0, width, height)
    val typeStr = when {
        channels > 1 -> " RGB"
        bayerPat != null -> " Bayer($bayerPat)"
        else -> " Mono"
    }
    val info = "${width}x${height} ${bitpix}bit$typeStr"
    return Pair(bmp, info)
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
