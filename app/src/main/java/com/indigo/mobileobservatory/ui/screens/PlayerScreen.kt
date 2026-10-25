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
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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
import com.indigo.mobileobservatory.media.MediaImageEditSettings
import com.indigo.mobileobservatory.media.MediaImageMemory
import com.indigo.mobileobservatory.media.MediaImageEditing
import com.indigo.mobileobservatory.media.MediaImageExportFormat
import com.indigo.mobileobservatory.media.PreparedMediaImage
import com.indigo.mobileobservatory.media.MediaImageEditViewModel
import com.indigo.mobileobservatory.media.MediaEditRecipe
import com.indigo.mobileobservatory.media.MediaEditRecipeStore
import com.indigo.mobileobservatory.media.MediaLineage
import com.indigo.mobileobservatory.media.MediaTrash
import com.indigo.mobileobservatory.media.MediaGroup
import com.indigo.mobileobservatory.media.MediaRole
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
import com.indigo.mobileobservatory.util.FitsHeaderParser
import com.indigo.mobileobservatory.util.FitsDisplayStretch
import com.indigo.mobileobservatory.ui.theme.ObservatoryTheme
import com.indigo.mobileobservatory.util.FileLogger
import com.indigo.mobileobservatory.util.ImageUtils
import kotlinx.coroutines.*
import java.io.File
import org.json.JSONObject
import kotlin.math.pow

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
    val context = LocalContext.current
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
                capturesDir = capturesDir,
                recordingsDir = recordingsDir,
                excludeFile = excludeFile,
                onBack = leaveFile,
                onNavigateUp = onNavigateUp,
                onImageExported = { refreshTrigger++ },
                onViewExported = { file ->
                    MediaLibrary.describe(file)?.let { selectedFile = it; showFilePicker = false }
                },
                onSolve = { plateSolveFile = firstFitsIn(info.file) ?: info.file },
                onStackFile = onStackFile?.let { launch -> { file: File ->
                    launch(file)
                } },
                onDelete = {
                    val currentIndex = currentFiles.indexOfFirst { it.file.absolutePath == info.file.absolutePath }
                    if (trashDir != null && info.file.exists()) {
                        if (!MediaTrash.move(info.file, trashDir)) {
                            Toast.makeText(context, R.string.media_trash_failed, Toast.LENGTH_LONG).show()
                            return@PlaybackView
                        }
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
    var grouped by rememberSaveable { mutableStateOf(true) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selectedPaths by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var mediaGroups by remember { mutableStateOf(emptyList<MediaGroup>()) }
    BackHandler(enabled = selecting && !showTrash) { selectedPaths = emptyList(); selecting = false }
    
    val trashDir = remember(recordingsDir) {
        recordingsDir?.let { File(it, ".trash").also { d -> d.mkdirs() } }
    }

    var loading by remember { mutableStateOf(true) }
    val files by produceState<List<VideoFileInfo>>(emptyList(), recordingsDir, capturesDir, selectedCategory, refreshTrigger, refreshVersion, excludeFile) {
        loading = true
        value = emptyList()
        val snapshot = withContext(Dispatchers.IO) {
            MediaLibrary.list(recordingsDir, capturesDir, selectedCategory, excludeFile) to
                MediaLineage.groups(MediaLibrary.list(recordingsDir, capturesDir, MediaCategory.RECENT, excludeFile)) { ensureActive() }
        }
        value = snapshot.first
        mediaGroups = snapshot.second
        loading = false
    }
    
    val trashFiles by produceState<List<VideoFileInfo>>(emptyList(), trashDir, showTrash, refreshTrigger) {
        value = withContext(Dispatchers.IO) {
            if (showTrash && trashDir != null && trashDir.exists()) {
                trashDir.listFiles()?.filter { !it.name.startsWith('.') && it.extension.lowercase() != "json" }?.map { f ->
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
                            if (!MediaTrash.move(info.file, trash)) Toast.makeText(context, R.string.media_trash_failed, Toast.LENGTH_LONG).show()
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
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = grouped, onClick = { grouped = !grouped }, label = { Text(stringResource(R.string.media_group_related)) })
                TextButton(onClick = { selecting = !selecting; if (!selecting) selectedPaths = emptyList() }) {
                    Text(stringResource(if (selecting) R.string.cancel else R.string.media_batch_select))
                }
            }
            if (selecting) MediaBatchActions(
                selected = mediaGroups.flatMap { it.members }.filter { it.file.absolutePath in selectedPaths },
                onClear = { selectedPaths = emptyList(); selecting = false })
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

        val included = files.map { it.file.absolutePath }.toSet()
        val visibleGroups = if (showTrash || !grouped) emptyList() else mediaGroups.filter { group -> group.members.any { it.file.absolutePath in included } }
        val displayFiles = if (showTrash) trashFiles else if (grouped) visibleGroups.flatMap { it.members } else files
        val headings = visibleGroups.filter { it.members.size > 1 }.associateBy { it.members.first().file.absolutePath }
        val roles = mediaGroups.flatMap { it.roles.entries }.associate { it.key to it.value }
        
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
                                if (!MediaTrash.restore(info.file, originalDir, listOfNotNull(recordingsDir, capturesDir)))
                                    Toast.makeText(context, R.string.media_trash_failed, Toast.LENGTH_LONG).show()
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
                            trashFiles.forEach { MediaTrash.delete(it.file) }
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
                    headings[info.file.absolutePath]?.let { group ->
                        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.media_group_count, group.members.size), Modifier.weight(1f),
                                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            if (selecting) TextButton(onClick = {
                                val paths = group.members.map { it.file.absolutePath }
                                selectedPaths = if (paths.all { it in selectedPaths }) selectedPaths - paths.toSet()
                                else (selectedPaths + paths).distinct()
                            }) { Text(stringResource(R.string.media_group_select)) }
                        }
                    }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { 
                                if (!showTrash) {
                                    if (selecting) {
                                        val path = info.file.absolutePath
                                        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
                                        return@clickable
                                    }
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
                                if (!showTrash) roles[info.file.absolutePath]?.let { role ->
                                    Text(stringResource(when (role) {
                                        MediaRole.ORIGINAL -> R.string.media_role_original
                                        MediaRole.STACK_LINEAR -> R.string.media_role_linear
                                        MediaRole.STACK_DISPLAY -> R.string.media_role_stack_display
                                        MediaRole.EDITED -> R.string.media_role_edited
                                    }), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
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
                                    if (!MediaTrash.restore(info.file, originalDir, listOfNotNull(recordingsDir, capturesDir)))
                                        Toast.makeText(context, R.string.media_trash_failed, Toast.LENGTH_LONG).show()
                                    refreshTrigger++
                                }) {
                                    Icon(Icons.Default.Restore, stringResource(R.string.restore), modifier = Modifier.size(20.dp))
                                }
                            } else {
                                if (selecting) Checkbox(checked = info.file.absolutePath in selectedPaths,
                                    onCheckedChange = { checked -> selectedPaths = if (checked) (selectedPaths + info.file.absolutePath).distinct()
                                        else selectedPaths - info.file.absolutePath })
                                else {
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
}

@Composable
private fun PlaybackView(
    fileInfo: VideoFileInfo,
    capturesDir: File?,
    recordingsDir: File?,
    excludeFile: File?,
    onBack: () -> Unit,
    onSolve: () -> Unit = {},
    onStackFile: ((File) -> Unit)? = null,
    onDelete: () -> Unit = {},
    onNavigateUp: (() -> Unit)? = null,
    onImageExported: () -> Unit = {},
    onViewExported: (File) -> Unit = {}
) {
    val isImage = fileInfo.format.equals("JPG", true) ||
                  fileInfo.format.equals("JPEG", true) ||
                  fileInfo.format.equals("PNG", true) ||
                  fileInfo.format.equals("FIT", true) ||
                  fileInfo.format.equals("FITS", true)

    if (isImage) {
        ImageViewScreen(
            fileInfo = fileInfo,
            capturesDir = capturesDir,
            recordingsDir = recordingsDir,
            excludeFile = excludeFile,
            onBack = onNavigateUp ?: onBack,
            onSolve = onSolve,
            onDelete = onDelete,
            externalUp = onNavigateUp != null,
            onExported = onImageExported,
            onViewExported = onViewExported
        )
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
    capturesDir: File?,
    recordingsDir: File?,
    excludeFile: File?,
    onBack: () -> Unit,
    onSolve: () -> Unit,
    onDelete: () -> Unit,
    externalUp: Boolean = false,
    onExported: () -> Unit = {},
    onViewExported: (File) -> Unit = {}
) {
    var sourceBitmap by remember(fileInfo) { mutableStateOf<Bitmap?>(null) }
    var bitmap by remember(fileInfo) { mutableStateOf<Bitmap?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var fitsInfo by remember { mutableStateOf("") }
    var resultMetadata by remember(fileInfo) { mutableStateOf("") }
    var showMetadata by remember(fileInfo) { mutableStateOf(false) }
    var showLp750Help by remember(fileInfo) { mutableStateOf(false) }
    var fitsCache by remember(fileInfo) { mutableStateOf<FitsViewCache?>(null) }
    var colorSource by remember(fileInfo) { mutableStateOf(true) }
    var editing by rememberSaveable(fileInfo.file.absolutePath) { mutableStateOf(false) }
    var editPreviewActive by rememberSaveable(fileInfo.file.absolutePath) { mutableStateOf(false) }
    val editor: MediaImageEditViewModel = androidx.lifecycle.viewmodel.compose.viewModel(key = "image-edit:${fileInfo.file.absolutePath}")
    val exportState by editor.state.collectAsStateWithPageLifecycle()
    val exporting = exportState.running
    var exportMessage by remember { mutableStateOf<String?>(null) }
    var preparedImage by remember(fileInfo) { mutableStateOf<PreparedMediaImage?>(null) }
    var preparing by remember { mutableStateOf(false) }
    var prepareRetry by remember { mutableIntStateOf(0) }
    val savedFile = exportState.result
    var showDiscard by remember { mutableStateOf(false) }
    var discardAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val history by editor.history.collectAsStateWithPageLifecycle()
    val editSettings = history.current
    val savedSettings by editor.checkpoint.collectAsStateWithPageLifecycle()
    val dirty = editSettings != savedSettings
    val context = LocalContext.current
    val recipeStore = remember(context) { MediaEditRecipeStore(File(context.filesDir, "image-recipes")) }
    var recipeSaving by remember { mutableStateOf(false) }
    var beforeBitmap by remember(fileInfo) { mutableStateOf<Bitmap?>(null) }
    val inspectionNavigation = remember(fileInfo) { MediaInspectionNavigation() }
    val nativePreview = remember(fileInfo) { MediaNativePreview() }
    val scope = rememberCoroutineScope()
    val unknownError = stringResource(R.string.unknown_error)
    val editorMaximumHeight = minOf(340.dp, (LocalConfiguration.current.screenHeightDp * .45f).dp)

    fun leaveImage(action: () -> Unit) {
        if (dirty || exporting) { discardAction = action; showDiscard = true } else action()
    }
    fun navigateImageBack() {
        if (inspectionNavigation.onBack()) return
        if (editing && !exporting) editing = false else leaveImage(onBack)
    }
    BackHandler(enabled = editing || dirty || exporting) { navigateImageBack() }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false; discardAction = null },
            title = { Text(stringResource(if (exporting) R.string.media_edit_cancel_save_title else R.string.media_edit_discard_title)) },
            text = { Text(stringResource(if (exporting) R.string.media_edit_cancel_save_hint else R.string.media_edit_discard_hint)) },
            confirmButton = { Column {
                if (!exporting && preparedImage != null) TextButton(enabled = !recipeSaving, onClick = {
                    val prepared = preparedImage ?: return@TextButton
                    val settings = editSettings
                    recipeSaving = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { recipeStore.save(prepared, settings) }
                            editor.markSaved(settings)
                            showDiscard = false
                            val action = discardAction; discardAction = null; action?.invoke()
                        } catch (cancelled: CancellationException) { throw cancelled
                        } catch (_: Exception) {
                            exportMessage = context.getString(R.string.media_recipe_save_failed)
                            showDiscard = false; editing = true
                        } finally { recipeSaving = false }
                    }
                }) { Text(stringResource(R.string.media_recipe_save_leave)) }
                TextButton(enabled = !recipeSaving, onClick = {
                    showDiscard = false
                    val action = discardAction; discardAction = null
                    scope.launch { editor.cancelAndJoin(); editor.resetHistory(savedSettings); action?.invoke() }
                }) { Text(stringResource(R.string.media_edit_discard)) }
            } },
            dismissButton = { TextButton(onClick = { showDiscard = false; discardAction = null; editing = true }) {
                Text(stringResource(R.string.media_edit_continue))
            } }
        )
    }

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
                        stringResource(if (editing) R.string.media_edit_lp700_hint else R.string.lp750_help_description),
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

    LaunchedEffect(fileInfo) {
        withContext(Dispatchers.IO) {
            try {
                val taskContext = currentCoroutineContext()
                val isFits = fileInfo.format.equals("FIT", true) || fileInfo.format.equals("FITS", true)
                if (isFits) {
                    val startedAt = SystemClock.elapsedRealtime()
                    val source = firstFitsIn(fileInfo.file) ?: throw IllegalArgumentException("Empty FITS session")
                    val sourceHeader = FitsHeaderParser.read(source)
                    val displayImage = MediaImageEditing.isEditedFits(source)
                    val largeImage = (sourceHeader.intValue("NAXIS1") ?: 0).toLong() *
                        (sourceHeader.intValue("NAXIS2") ?: 0) > 4_194_304L
                    if (displayImage || largeImage || !StackFitsReader.isNormalizedStack(source)) {
                        val prepared = MediaImageEditing.prepare(source) { !taskContext.isActive }
                        val viewSettings = if (displayImage) MediaImageEditSettings() else
                            MediaImageEditSettings(gamma = 1f / FitsDisplayStretch.DISPLAY_GAMMA)
                        val decoded = MediaImageEditing.renderPreview(prepared, viewSettings) { !taskContext.isActive }
                        val metadata = StackFitsReader.readMetadata(source)
                        withContext(Dispatchers.Main) {
                            sourceBitmap = decoded; bitmap = decoded
                            fitsInfo = "${prepared.originalWidth}×${prepared.originalHeight} ${sourceHeader["BITPIX"]}bit ${if (prepared.preview.channels == 3) "RGB" else "Mono"}"
                            colorSource = prepared.preview.channels == 3
                            resultMetadata = if (metadata.isEmpty()) "" else JSONObject(metadata).toString(2).take(20000)
                            error = null
                        }
                        return@withContext
                    }
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
                    val cached = workingCache.bitmaps[false]
                    val decoded = if (cached != null) {
                        cached to workingCache.infoByMode.getValue(false)
                    } else if (workingCache.normalized) {
                        decodeNormalizedFits(source, false) { !taskContext.isActive }
                    } else {
                        renderIntegerFits(
                            requireNotNull(workingCache.integerPreview),
                            false
                        ) { !taskContext.isActive }
                    }
                    val updatedCache = if (cached != null) {
                        workingCache
                    } else {
                        workingCache.copy(
                            bitmaps = workingCache.bitmaps + (false to decoded.first),
                            infoByMode = workingCache.infoByMode + (false to decoded.second)
                        )
                    }
                    FileLogger.i(
                        "FitsPreview",
                        "file=${source.name} sourceCacheHit=$sourceCacheHit " +
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
                        sourceBitmap = decoded.first
                        bitmap = decoded.first
                        fitsInfo = decoded.second + extra
                        colorSource = decoded.second.contains(" RGB") || decoded.second.contains("Bayer(")
                        resultMetadata = if (updatedCache.metadata.isEmpty()) "" else {
                            JSONObject(updatedCache.metadata).toString(2).take(20000)
                        }
                        error = null
                    }
                } else {
                    val prepared = MediaImageEditing.prepare(fileInfo.file) { !taskContext.isActive }
                    val bmp = MediaImageEditing.renderPreview(prepared, MediaImageEditSettings()) { !taskContext.isActive }
                    val sidecar = File(fileInfo.file.parentFile, "${fileInfo.file.nameWithoutExtension}.json")
                    val metadata = if (sidecar.isFile && sidecar.length() <= 16 * 1024 * 1024) {
                        runCatching { JSONObject(sidecar.readText()).toString(2).take(20000) }.getOrDefault("")
                    } else ""
                    withContext(Dispatchers.Main) {
                        sourceBitmap = bmp
                        bitmap = bmp
                        colorSource = true
                        resultMetadata = metadata
                        error = null
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

    LaunchedEffect(fileInfo, prepareRetry) {
        MediaImageMemory.configure(context)
        preparing = true
        try {
            val taskContext = currentCoroutineContext()
            val restored = withContext(Dispatchers.IO) {
                val opened = firstFitsIn(fileInfo.file) ?: fileInfo.file
                val indexed = MediaLibrary.list(recordingsDir, capturesDir, MediaCategory.RECENT, excludeFile)
                val exportedRecipe = MediaEditRecipe.parse(MediaLineage.metadata(opened))
                val original = exportedRecipe?.let { MediaLineage.recipeSource(it, indexed) }
                val source = original ?: opened
                val recipe = exportedRecipe?.takeIf { original != null } ?: recipeStore.load(source)
                val prepared = MediaImageEditing.prepare(source, recipe) { !taskContext.isActive }
                Triple(prepared, recipe, exportedRecipe != null && original == null)
            }
            val prepared = restored.first
            editor.bind(prepared.sourceFingerprint, restored.second?.settings ?: MediaImageEditSettings())
            if (restored.second != null && !restored.third) editPreviewActive = true
            preparedImage = prepared
            beforeBitmap = withContext(Dispatchers.Default) { MediaImageEditing.renderPreview(prepared, MediaImageEditSettings()) { !taskContext.isActive } }
            if (restored.third) exportMessage = context.getString(R.string.media_recipe_missing_source)
            else if (restored.second != null) exportMessage = context.getString(R.string.media_recipe_restored)
            colorSource = prepared.preview.channels == 3
            error = null
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (failure: OutOfMemoryError) { error = context.getString(R.string.media_edit_memory)
        } catch (failure: Exception) { error = mediaEditError(context, failure)
        } finally { preparing = false }
    }

    LaunchedEffect(sourceBitmap, preparedImage, editSettings, editing, editPreviewActive) {
        val source = sourceBitmap ?: return@LaunchedEffect
        if (!editing && !editPreviewActive) {
            bitmap = source
            return@LaunchedEffect
        }
        val prepared = preparedImage ?: return@LaunchedEffect
        try {
            delay(60)
            val taskContext = currentCoroutineContext()
            val rendered = withContext(Dispatchers.Default) {
                MediaImageEditing.renderPreview(prepared, editSettings) { !taskContext.isActive }
            }
            bitmap = rendered
            error = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: OutOfMemoryError) { error = context.getString(R.string.media_edit_memory)
        } catch (failure: Exception) {
            error = mediaEditError(context, failure)
        }
    }

    fun exportImage(format: MediaImageExportFormat) {
        if (exporting) return
        val prepared = preparedImage ?: return
        val settings = editSettings
        val library = capturesDir ?: run {
            Toast.makeText(context, R.string.media_edit_save_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        exportMessage = null
        editor.export(prepared, library, format, settings, recipeStore)
    }

    LaunchedEffect(exportState.completionId) {
        if (exportState.completionId == null) return@LaunchedEffect
        if (exportState.error != null) {
            exportMessage = mediaEditError(context, IllegalStateException(exportState.error))
        } else {
            exportState.result?.let { output ->
                exportState.savedSettings?.let { editor.markSaved(it) }
                exportMessage = context.getString(R.string.media_edit_saved, output.name)
                if (exportState.recipeError) exportMessage += "\n" + context.getString(R.string.media_recipe_save_failed)
                onExported()
                Toast.makeText(context, exportMessage, Toast.LENGTH_LONG).show()
            }
        }
        editor.acknowledgeCompletion()
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
            IconButton(onClick = ::navigateImageBack) {
                Icon(
                    Icons.Default.ArrowBack,
                    stringResource(if (editing) R.string.back else if (externalUp) R.string.external_media_up else R.string.back),
                    tint = ObservatoryTheme.colors.onImage
                )
            }
            Text(
                fileInfo.name,
                color = ObservatoryTheme.colors.onImage,
                fontSize = 13.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            if (resultMetadata.isNotEmpty()) {
                IconButton(onClick = { showMetadata = true }) {
                    Icon(Icons.Default.Info, stringResource(R.string.section_info), tint = ObservatoryTheme.colors.onImage)
                }
            }
            if (!editing && colorSource && (fileInfo.format.equals("FIT", true) || fileInfo.format.equals("FITS", true))) {
                FilterChip(
                    selected = editSettings.lp700,
                    onClick = { editPreviewActive = true; editor.change(editSettings.copy(lp700 = !editSettings.lp700)) },
                    label = { Text("LP700", fontSize = 11.sp) },
                    modifier = Modifier.padding(horizontal = 2.dp)
                )
                IconButton(onClick = { showLp750Help = true }) {
                    Icon(
                        Icons.Default.HelpOutline,
                        contentDescription = stringResource(R.string.lp750_help_button),
                        tint = ObservatoryTheme.colors.onImage
                    )
                }
            }
            if (!editing) IconButton(onClick = { leaveImage(onSolve) }, enabled = !exporting) {
                Icon(Icons.Default.Search, stringResource(R.string.plate_solve), tint = ObservatoryTheme.colors.onImage)
            }
            if (fileInfo.file.isFile) {
                    TextButton(onClick = { editPreviewActive = true; editing = !editing }, enabled = preparedImage != null && sourceBitmap != null && !exporting) {
                    Text(stringResource(if (editing) R.string.media_edit_done else R.string.media_edit_action), color = ObservatoryTheme.colors.onImage,
                        modifier = Modifier.semantics { contentDescription = context.getString(if (editing) R.string.media_edit_done else R.string.media_edit_action) })
                }
            }
            if (!editing) {
                IconButton(onClick = { leaveImage(onDelete) }, enabled = !exporting) {
                    Icon(Icons.Default.Delete, stringResource(R.string.delete), tint = Color(0xFFFF6B6B))
                }
            }
        }
        if (fitsInfo.isNotEmpty()) {
            Text(fitsInfo, color = ObservatoryTheme.colors.onImage.copy(alpha = 0.7f), fontSize = 11.sp,
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
                val prepared = preparedImage
                if (prepared != null) MediaImageInspection(
                    image = bmp, before = if (editPreviewActive || editing) beforeBitmap else null,
                    imageKey = fileInfo.file.absolutePath, navigation = inspectionNavigation, originalWidth = prepared.originalWidth,
                    originalHeight = prepared.originalHeight, nativeKey = listOf(editSettings, editPreviewActive, editing, prepared.sourceFingerprint),
                    renderNative = { region, before ->
                        val taskContext = currentCoroutineContext()
                        withContext(Dispatchers.IO) {
                            if (editing || editPreviewActive) MediaImageEditing.renderRegion(prepared,
                                if (before) MediaImageEditSettings() else editSettings, region) { !taskContext.isActive }
                            else nativePreview.render(prepared, bmp, region) { !taskContext.isActive }
                        }
                    }, modifier = Modifier.fillMaxSize())
                else Image(bitmap = bmp.asImageBitmap(), contentDescription = stringResource(R.string.image_desc),
                    contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            } else if (err != null) {
                Text(err, color = Color.Red, fontSize = 14.sp)
            } else {
                CircularProgressIndicator(color = ObservatoryTheme.colors.onImage, modifier = Modifier.size(32.dp))
            }
        }
        if (editing) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = editorMaximumHeight)
                    .background(Color(0xEE111111))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { exportImage(MediaImageExportFormat.JPG) }, enabled = !exporting && preparedImage != null,
                        modifier = Modifier.weight(1f)) { Text(stringResource(R.string.media_edit_export_jpg)) }
                    OutlinedButton(onClick = { exportImage(MediaImageExportFormat.PNG) }, enabled = !exporting && preparedImage != null,
                        modifier = Modifier.weight(1f)) { Text(stringResource(R.string.media_edit_export_png)) }
                    Button(onClick = { exportImage(MediaImageExportFormat.FIT) }, enabled = !exporting && preparedImage != null,
                        modifier = Modifier.weight(1f)) { Text(stringResource(R.string.media_edit_export_fit)) }
                }
                if (exporting || preparing) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(stringResource(if (exporting) R.string.media_edit_saving else R.string.media_edit_preparing), color = ObservatoryTheme.colors.onImage, style = MaterialTheme.typography.bodySmall)
                        if (exporting) TextButton(onClick = { editor.cancel() }) { Text(stringResource(R.string.cancel)) }
                    }
                }
                if (!exporting) savedFile?.let { output -> TextButton(onClick = { leaveImage { onViewExported(output) } }) {
                    Text(stringResource(R.string.media_edit_view_saved))
                } }
                Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { editor.undo() }, enabled = !exporting && (history.past.isNotEmpty() || history.gestureStart != null)) {
                        Text(stringResource(R.string.media_edit_undo))
                    }
                    TextButton(onClick = { editor.redo() }, enabled = !exporting && history.future.isNotEmpty()) {
                        Text(stringResource(R.string.media_edit_redo))
                    }
                    TextButton(enabled = !exporting && !recipeSaving && preparedImage != null, onClick = {
                        val prepared = preparedImage ?: return@TextButton
                        val settings = editSettings
                        recipeSaving = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { recipeStore.save(prepared, settings) }
                                editor.markSaved(settings)
                                exportMessage = context.getString(R.string.media_recipe_saved)
                            } catch (failure: Exception) { exportMessage = context.getString(R.string.media_recipe_save_failed)
                            } finally { recipeSaving = false }
                        }
                    }) { Text(stringResource(R.string.media_recipe_save)) }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (colorSource) {
                        FilterChip(
                            selected = editSettings.lp700,
                            onClick = { editor.change(editSettings.copy(lp700 = !editSettings.lp700)) },
                            enabled = !exporting && preparedImage != null,
                            label = { Text("LP700") }
                        )
                        TextButton(enabled = !exporting && preparedImage != null, onClick = {
                            val prepared = preparedImage ?: return@TextButton
                            val requested = editSettings
                            scope.launch {
                                val gains = withContext(Dispatchers.Default) { MediaImageEditing.estimateWhiteBalance(prepared, requested) }
                                if (editor.history.value.current == requested) editor.change(requested.copy(
                                    redGain = gains[0], greenGain = gains[1], blueGain = gains[2]))
                            }
                        }) { Text(stringResource(R.string.media_edit_auto_wb)) }
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(enabled = !exporting, onClick = { editor.change(MediaImageEditSettings()) }) {
                        Text(stringResource(R.string.media_edit_reset))
                    }
                }
                if (colorSource) {
                    Text(stringResource(R.string.media_edit_white_balance), color = ObservatoryTheme.colors.onImage, style = MaterialTheme.typography.labelLarge)
                    EditSlider(stringResource(R.string.media_edit_red), editSettings.redGain, 0.5f..2f,
                        { editor.update(editSettings.copy(redGain = it)) }, !exporting, editor::commitGesture)
                    EditSlider(stringResource(R.string.media_edit_green), editSettings.greenGain, 0.5f..2f,
                        { editor.update(editSettings.copy(greenGain = it)) }, !exporting, editor::commitGesture)
                    EditSlider(stringResource(R.string.media_edit_blue), editSettings.blueGain, 0.5f..2f,
                        { editor.update(editSettings.copy(blueGain = it)) }, !exporting, editor::commitGesture)
                }
                Text(stringResource(R.string.media_edit_curve), color = ObservatoryTheme.colors.onImage, style = MaterialTheme.typography.labelLarge)
                ImageEditCurve(editSettings)
                EditSlider(stringResource(R.string.media_edit_exposure), editSettings.exposureStops, -3f..3f,
                    { editor.update(editSettings.copy(exposureStops = it)) }, !exporting, editor::commitGesture)
                EditSlider(stringResource(R.string.media_edit_black_point), editSettings.blackPoint, 0f..0.30f,
                    { editor.update(editSettings.copy(blackPoint = it)) }, !exporting, editor::commitGesture)
                EditSlider(stringResource(R.string.media_edit_white_point), editSettings.whitePoint, 0.70f..1f,
                    { editor.update(editSettings.copy(whitePoint = it)) }, !exporting, editor::commitGesture)
                EditSlider(stringResource(R.string.media_edit_gamma), editSettings.gamma, 0.5f..2f,
                    { editor.update(editSettings.copy(gamma = it)) }, !exporting, editor::commitGesture)
                Text(stringResource(R.string.media_edit_fits_note), color = ObservatoryTheme.colors.onImage.copy(alpha = 0.72f), style = MaterialTheme.typography.bodySmall)
                if (colorSource) TextButton(onClick = { showLp750Help = true }) { Text(stringResource(R.string.lp750_help_button)) }
                exportMessage?.let { Text(it, color = ObservatoryTheme.colors.onImage, style = MaterialTheme.typography.bodySmall) }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (preparedImage == null && !preparing) TextButton(onClick = { prepareRetry++ }) {
                        Text(stringResource(R.string.media_edit_retry))
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun EditSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onValue: (Float) -> Unit,
                       enabled: Boolean = true, onFinished: () -> Unit = {}) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, color = ObservatoryTheme.colors.onImage.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(82.dp))
        Slider(value = value, onValueChange = onValue, valueRange = range, enabled = enabled, onValueChangeFinished = onFinished,
            modifier = Modifier.weight(1f).height(48.dp))
        Text(String.format("%.2f", value), color = ObservatoryTheme.colors.onImage, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.width(38.dp))
    }
}

@Composable
private fun ImageEditCurve(settings: MediaImageEditSettings) {
    val lineColor = MaterialTheme.colorScheme.primary
    val description = stringResource(R.string.media_edit_curve)
    Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = description }) {
        drawLine(Color.DarkGray, androidx.compose.ui.geometry.Offset(0f, size.height),
            androidx.compose.ui.geometry.Offset(size.width, 0f), 1f)
        val path = Path()
        val gain = 2.0.pow(settings.exposureStops.toDouble())
        for (step in 0..128) {
            val input = step / 128.0
            val output = ((input * gain - settings.blackPoint) / (settings.whitePoint - settings.blackPoint))
                .coerceIn(0.0, 1.0).pow(1.0 / settings.gamma)
            val x = (input * size.width).toFloat()
            val y = ((1.0 - output) * size.height).toFloat()
            if (step == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, lineColor, style = Stroke(2.dp.toPx()))
    }
}

private fun mediaEditError(context: Context, failure: Exception): String = context.getString(when (failure.message) {
    "memory_budget" -> R.string.media_edit_memory
    "storage_budget" -> R.string.media_edit_storage
    "source_changed" -> R.string.media_edit_source_changed
    else -> R.string.media_edit_save_failed
})

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
    if (!applyLp750) stackDisplayFile(file)?.let { display ->
        val prepared = MediaImageEditing.prepare(display, cancelled = cancelled)
        val header = FitsHeaderParser.read(file)
        if (prepared.originalWidth == header.intValue("NAXIS1") && prepared.originalHeight == header.intValue("NAXIS2")) {
            return MediaImageEditing.renderPreview(prepared, MediaImageEditSettings(), cancelled) to
                "${prepared.originalWidth}×${prepared.originalHeight} Float32 ${if (header.intValue("NAXIS3") == 3) "RGB" else "Mono"}"
        }
    }
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
