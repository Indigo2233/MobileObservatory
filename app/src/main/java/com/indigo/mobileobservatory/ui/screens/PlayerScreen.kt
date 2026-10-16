package com.indigo.mobileobservatory.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.saveable.listSaver
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.graphics.Bitmap
import android.os.SystemClock
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.media.LibraryImportViewModel
import com.indigo.mobileobservatory.media.ExternalMediaImportError
import com.indigo.mobileobservatory.ui.components.Lp750Credit
import com.indigo.mobileobservatory.mount.MountCoordinates
import com.indigo.mobileobservatory.recording.SERReader
import com.indigo.mobileobservatory.recording.MediaLibrary
import androidx.core.content.FileProvider
import android.widget.Toast
import com.indigo.mobileobservatory.stacking.StackBitmap
import com.indigo.mobileobservatory.stacking.StackFitsReader
import com.indigo.mobileobservatory.stacking.StackFitsPreview
import com.indigo.mobileobservatory.util.FitsIntegerPreview
import com.indigo.mobileobservatory.util.FitsIntegerPreviewDecoder
import com.indigo.mobileobservatory.util.FileLogger
import com.indigo.mobileobservatory.util.ImageUtils
import kotlinx.coroutines.*
import java.io.File
import org.json.JSONObject

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
    MediaCategory.RECENT -> R.string.wf_recent
    MediaCategory.PHOTOS -> R.string.media_category_photos
    MediaCategory.VIDEOS -> R.string.wf_videos
    MediaCategory.SER_PSER -> R.string.media_category_ser
    MediaCategory.MP4 -> R.string.media_category_mp4
    MediaCategory.FITS -> R.string.media_category_fits
    MediaCategory.JPG -> R.string.media_category_photos
    MediaCategory.STACK_RESULTS -> R.string.media_category_results
}

private fun mediaEmptyHint(category: MediaCategory) = when (category) {
    MediaCategory.RECENT -> R.string.wf_media_empty
    MediaCategory.PHOTOS -> R.string.media_empty_photos
    MediaCategory.VIDEOS -> R.string.media_empty_ser
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
    onBack: () -> Unit,
    isWorkspace: Boolean = false,
    onCamera: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    onPostStack: (() -> Unit)? = null,
    onPlateSolve: (() -> Unit)? = null,
    /** External file viewing has a platform Back boundary and a separate app-owned Up destination. */
    onNavigateUp: (() -> Unit)? = null
) {
    // Orientation belongs to CameraScreen's navigation owner. Mutating it again here,
    // especially during disposal, can request a second Activity configuration change.
    val fileSaver = listSaver<VideoFileInfo?, String>(
        save = { info -> info?.let { listOf(it.file.absolutePath, it.name, it.size.toString(), it.format) } ?: emptyList() },
        restore = { if (it.isEmpty()) null else VideoFileInfo(File(it[0]), it[1], it[2].toLong(), it[3]) })
    var selectedFile by rememberSaveable(stateSaver = fileSaver) { mutableStateOf<VideoFileInfo?>(null) }
    var plateSolveFile by remember { mutableStateOf<File?>(null) }
    var selectedCategory by rememberSaveable(initialCategory) { mutableStateOf(initialCategory) }
    var showFilePicker by rememberSaveable { mutableStateOf(true) }
    com.indigo.mobileobservatory.ui.components.ReportObservatoryImmersion(selectedFile != null && !showFilePicker)
    var refreshTrigger by remember { mutableIntStateOf(0) }
    val importer: LibraryImportViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "library-import")
    val importState by importer.state.collectAsStateWithPageLifecycle()
    var lastImportCompletion by rememberSaveable { mutableStateOf<String?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importer.importFile(uri)
    }
    LaunchedEffect(importState.completionId) {
        if (isWorkspace && importState.completionId != null && importState.completionId != lastImportCompletion) {
            lastImportCompletion = importState.completionId
            selectedCategory = MediaCategory.RECENT
            refreshTrigger++
        }
    }
    var directFileUnavailable by remember { mutableStateOf(false) }
    var initialFileLoaded by rememberSaveable(initialFile?.absolutePath) { mutableStateOf(false) }
    LaunchedEffect(initialFile) {
        if (initialFileLoaded) return@LaunchedEffect
        initialFileLoaded = true
        initialFile?.let { file ->
            val info = withContext(Dispatchers.IO) {
                if (file.absolutePath == excludeFile?.absolutePath) null else MediaLibrary.describe(file)
            }
            selectedFile = info
            showFilePicker = info == null
            directFileUnavailable = info == null
        }
    }

    val leaveFile: () -> Unit = {
        if (initialFile != null) onBack() else { selectedFile = null; showFilePicker = true }
    }
    val navigateBack: () -> Unit = {
        when {
            plateSolveFile != null -> plateSolveFile = null
            selectedFile != null -> leaveFile()
            else -> onBack()
        }
    }
    BackHandler(enabled = !isWorkspace || selectedFile != null || plateSolveFile != null) { navigateBack() }
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
            onBack = onNavigateUp ?: onBack,
            onStackFile = onStackFile,
            selectedCategory = selectedCategory,
            excludeFile = excludeFile,
            fileUnavailable = directFileUnavailable,
            onCategoryChange = { cat -> selectedCategory = cat },
            isWorkspace = isWorkspace, onCamera = onCamera,
            onImport = onImport ?: if (isWorkspace) ({ importPicker.launch(arrayOf("*/*")) }) else null,
            onPostStack = onPostStack, onPlateSolve = onPlateSolve, importState = if (isWorkspace) importState else null,
            onCancelImport = importer::cancel, refreshVersion = refreshTrigger
        )
    } else {
        val info = selectedFile
        if (info != null) {
            key(info.file.absolutePath) {
            PlaybackView(
                fileInfo = info,
                onBack = leaveFile,
                onNavigateUp = onNavigateUp,
                onSolve = { plateSolveFile = firstFitsIn(info.file) ?: info.file },
                onStackFile = onStackFile?.let { launch -> { file: File ->
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
    onCategoryChange: (MediaCategory) -> Unit = {},
    isWorkspace: Boolean = false,
    onCamera: (() -> Unit)? = null,
    onImport: (() -> Unit)? = null,
    onPostStack: (() -> Unit)? = null,
    onPlateSolve: (() -> Unit)? = null,
    importState: com.indigo.mobileobservatory.media.LibraryImportState? = null,
    onCancelImport: () -> Unit = {},
    refreshVersion: Int = 0
) {
    val context = LocalContext.current
    var showTrash by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showTrash) { showTrash = false }
    var fileToDelete by remember { mutableStateOf<VideoFileInfo?>(null) }
    var refreshTrigger by remember { mutableIntStateOf(0) }
    
    val trashDir = remember(recordingsDir) {
        recordingsDir?.let { File(it, ".trash").also { d -> d.mkdirs() } }
    }

    var loading by remember { mutableStateOf(true) }
    val files by produceState<List<VideoFileInfo>>(emptyList(), recordingsDir, capturesDir, selectedCategory, refreshTrigger, refreshVersion, excludeFile) {
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
            if (!isWorkspace || showTrash) IconButton(onClick = { if (showTrash) showTrash = false else onBack() }) {
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
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onImport != null) TextButton(onClick = onImport, enabled = importState?.busy != true,
                    modifier = Modifier.testTag("media-import")) { Text(stringResource(R.string.wf_import_video)) }
                if (onPostStack != null) OutlinedButton(onClick = onPostStack,
                    modifier = Modifier.testTag("media-post-stack")) { Text(stringResource(R.string.stack_offline_title)) }
                if (onPlateSolve != null) TextButton(onClick = onPlateSolve,
                    modifier = Modifier.testTag("media-plate-solve")) { Text(stringResource(R.string.plate_solve)) }
            }
            if (importState?.busy == true) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.external_media_importing), Modifier.weight(1f))
                TextButton(onClick = onCancelImport) { Text(stringResource(R.string.cancel)) }
            }
            importState?.error?.let { reason -> Text(stringResource(when (reason) {
                ExternalMediaImportError.MISSING_FILE -> R.string.external_media_missing
                ExternalMediaImportError.UNSUPPORTED -> R.string.external_media_unsupported
                ExternalMediaImportError.READ_PERMISSION -> R.string.external_media_permission
                ExternalMediaImportError.NO_SPACE -> R.string.external_media_space
                ExternalMediaImportError.TOO_LARGE -> R.string.external_media_large
                ExternalMediaImportError.INVALID_FILE -> R.string.external_media_invalid
                ExternalMediaImportError.COPY_FAILED -> R.string.external_media_copy_failed
            }), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error) }
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
                (if (isWorkspace) listOf(MediaCategory.RECENT, MediaCategory.PHOTOS, MediaCategory.VIDEOS, MediaCategory.STACK_RESULTS)
                else MediaCategory.entries).forEach { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { 
                            onCategoryChange(cat)
                        },
                        label = { Text(stringResource(mediaCategoryLabel(cat)), fontSize = 11.sp) },
                        modifier = Modifier.heightIn(min = 32.dp)
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
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        if (showTrash) Icons.Default.Delete else Icons.Default.VideoLibrary,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    if (!showTrash && !loading && onCamera != null) TextButton(onClick = onCamera) { Text(stringResource(R.string.wf_go_capture)) }
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
                                    "${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(info.file.lastModified()))}  ·  ${info.format}  ·  ${ImageUtils.formatFileSize(info.size)}",
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
    onDelete: () -> Unit = {},
    onNavigateUp: (() -> Unit)? = null
) {
    val isImage = fileInfo.format.equals("JPG", true) ||
                  fileInfo.format.equals("JPEG", true) ||
                  fileInfo.format.equals("PNG", true) ||
                  fileInfo.format.equals("FIT", true) ||
                  fileInfo.format.equals("FITS", true)

    if (isImage) {
        ImageViewScreen(fileInfo = fileInfo, onBack = onNavigateUp ?: onBack, onSolve = onSolve, onDelete = onDelete,
            externalUp = onNavigateUp != null)
        return
    }

    val reader = remember { SERReader(fileInfo.file) }
    var isOpened by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var currentFrame by rememberSaveable { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    val scope = rememberCoroutineScope()
    var playJob by remember { mutableStateOf<Job?>(null) }
    var seekJob by remember { mutableStateOf<Job?>(null) }
    var openJob by remember { mutableStateOf<Job?>(null) }
    var readerClosed by remember { mutableStateOf(false) }
    var navigationJob by remember { mutableStateOf<Job?>(null) }
    var navigationPending by remember { mutableStateOf(false) }

    var totalFrames by remember { mutableIntStateOf(0) }

    var pendingAutoPlay by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        openJob = scope.launch(Dispatchers.IO) {
            try {
            val opened = reader.open()
            withContext(Dispatchers.Main) { if (!readerClosed) isOpened = opened }
            if (opened && isActive && !readerClosed) {
                withContext(Dispatchers.Main) {
                    totalFrames = reader.header?.frameCount ?: 0
                }
                val bmp = reader.readPreviewBitmap(currentFrame.coerceIn(0, (totalFrames - 1).coerceAtLeast(0))) { ensureActive() }
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
            navigationJob?.cancel()
            reader.close()
        }
    }

    fun closeThen(action: () -> Unit) {
        if (readerClosed) return
        readerClosed = true
        navigationJob?.cancel()
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

    BackHandler { closeThen(onBack) }

    fun pauseThen(action: () -> Unit) {
        if (readerClosed || navigationPending) return
        navigationPending = true
        isPlaying = false
        pendingAutoPlay = false
        val pending = listOfNotNull(playJob, seekJob)
        pending.forEach { it.cancel() }
        navigationJob = scope.launch {
            try {
                pending.joinAll()
                // A covered player stays composed. Retain its reader and current frame
                // so returning from processing restores a paused, usable player.
                if (!readerClosed) action()
            } finally {
                navigationPending = false
            }
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

    val pageVisible = rememberPageForeground()
    LaunchedEffect(pageVisible) {
        if (!pageVisible) { isPlaying = false; pendingAutoPlay = false; playJob?.cancel() }
    }
    LaunchedEffect(isOpened, pendingAutoPlay, pageVisible) {
        if (pageVisible && isOpened && pendingAutoPlay && totalFrames > 1) {
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
                closeThen(onNavigateUp ?: onBack)
            }) {
                Icon(Icons.Default.ArrowBack, stringResource(if (onNavigateUp != null) R.string.external_media_up else R.string.back), tint = Color.White)
            }
            Text(
                fileInfo.name,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (onStackFile != null && (fileInfo.format.equals("SER", true) || fileInfo.format.equals("PSER", true))) {
                IconButton(onClick = { pauseThen { onStackFile(fileInfo.file) } }, enabled = !readerClosed && !navigationPending) {
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
    onDelete: () -> Unit,
    externalUp: Boolean = false
) {
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fitsInfo by remember { mutableStateOf("") }
    var resultMetadata by remember(fileInfo) { mutableStateOf("") }
    var showMetadata by remember(fileInfo) { mutableStateOf(false) }
    var lp750Enabled by remember(fileInfo) { mutableStateOf(false) }
    var showLp750Help by remember(fileInfo) { mutableStateOf(false) }
    var fitsCache by remember(fileInfo) { mutableStateOf<FitsViewCache?>(null) }
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

    if (showLp750Help) {
        AlertDialog(
            onDismissRequest = { showLp750Help = false },
            title = { Text(stringResource(R.string.lp750_help_title)) },
            text = {
                Column(
                    Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        stringResource(R.string.lp750_help_description),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Lp750Credit()
                }
            },
            confirmButton = {
                TextButton(onClick = { showLp750Help = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    LaunchedEffect(fileInfo, lp750Enabled) {
        withContext(Dispatchers.IO) {
            try {
                val taskContext = currentCoroutineContext()
                val isFits = fileInfo.format.equals("FIT", true) || fileInfo.format.equals("FITS", true)
                if (isFits) {
                    val startedAt = SystemClock.elapsedRealtime()
                    val source = firstFitsIn(fileInfo.file) ?: throw IllegalArgumentException("Empty FITS session")
                    val sourceKey = "${source.absolutePath}:${source.length()}:${source.lastModified()}"
                    var workingCache = fitsCache?.takeIf { it.sourceKey == sourceKey }
                    val sourceCacheHit = workingCache != null
                    if (workingCache == null) {
                        val normalized = StackFitsReader.isNormalizedStack(source)
                        val integerPreview = if (normalized) null else {
                            FitsIntegerPreviewDecoder.read(source) { !taskContext.isActive }
                        }
                        val metadata = if (StackFitsReader.isFloatingPoint(source)) {
                            StackFitsReader.readMetadata(source)
                        } else {
                            emptyMap()
                        }
                        workingCache = FitsViewCache(
                            sourceKey = sourceKey,
                            normalized = normalized,
                            integerPreview = integerPreview,
                            metadata = metadata
                        )
                    }
                    val cached = workingCache.bitmaps[lp750Enabled]
                    val decoded = if (cached != null) {
                        cached to workingCache.infoByMode.getValue(lp750Enabled)
                    } else if (workingCache.normalized) {
                        decodeNormalizedFits(source, lp750Enabled) { !taskContext.isActive }
                    } else {
                        renderIntegerFits(
                            requireNotNull(workingCache.integerPreview),
                            lp750Enabled
                        ) { !taskContext.isActive }
                    }
                    val updatedCache = if (cached != null) {
                        workingCache
                    } else {
                        workingCache.copy(
                            bitmaps = workingCache.bitmaps + (lp750Enabled to decoded.first),
                            infoByMode = workingCache.infoByMode + (lp750Enabled to decoded.second)
                        )
                    }
                    FileLogger.i(
                        "FitsPreview",
                        "file=${source.name} lp750=$lp750Enabled sourceCacheHit=$sourceCacheHit " +
                            "bitmapCacheHit=${cached != null} size=${decoded.first.width}x${decoded.first.height} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    val extra = if (fileInfo.file.isDirectory) {
                        val n = fileInfo.file.listFiles()?.count {
                            it.isFile && (it.extension.equals("fit", true) || it.extension.equals("fits", true))
                        } ?: 0
                        if (n > 1) "  $n frames" else ""
                    } else ""
                    withContext(Dispatchers.Main) {
                        fitsCache = updatedCache
                        bitmap = decoded.first
                        fitsInfo = decoded.second + extra
                        resultMetadata = if (updatedCache.metadata.isEmpty()) "" else {
                            JSONObject(updatedCache.metadata).toString(2).take(20000)
                        }
                        error = null
                    }
                } else {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(fileInfo.file.absolutePath, bounds)
                    var sample = 1
                    while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
                    val bmp = BitmapFactory.decodeFile(fileInfo.file.absolutePath, BitmapFactory.Options().apply {
                        inSampleSize = sample; inScaled = false
                    })
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
                Icon(Icons.Default.ArrowBack, stringResource(if (externalUp) R.string.external_media_up else R.string.back), tint = Color.White)
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
                    label = { Text("LP700", fontSize = 11.sp) },
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                IconButton(onClick = { showLp750Help = true }) {
                    Icon(
                        Icons.Default.HelpOutline,
                        contentDescription = stringResource(R.string.lp750_help_button),
                        tint = Color.White
                    )
                }
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

private data class FitsViewCache(
    val sourceKey: String,
    val normalized: Boolean,
    val integerPreview: FitsIntegerPreview?,
    val metadata: Map<String, String>,
    val bitmaps: Map<Boolean, Bitmap> = emptyMap(),
    val infoByMode: Map<Boolean, String> = emptyMap()
)

private fun decodeNormalizedFits(
    file: File,
    applyLp750: Boolean,
    cancelled: () -> Boolean
): Pair<Bitmap, String> {
    val result = StackFitsPreview.read(file, applyLp750, cancelled)
    val image = result.image
    val bitmap = StackBitmap.create(image, 2048) {
        if (cancelled()) throw CancellationException("FITS preview cancelled")
    }
    return bitmap to "${image.width}×${image.height} Float32 ${if (image.channels == 3) "RGB" else "Mono"}"
}

private fun renderIntegerFits(
    preview: FitsIntegerPreview,
    applyLp750: Boolean,
    cancelled: () -> Boolean
): Pair<Bitmap, String> {
    val pixels = preview.render(applyLp750, cancelled)
    val bitmap = Bitmap.createBitmap(preview.width, preview.height, Bitmap.Config.ARGB_8888)
    bitmap.setPixels(pixels, 0, preview.width, 0, 0, preview.width, preview.height)
    return bitmap to preview.info
}
