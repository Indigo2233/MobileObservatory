package com.indigo.mobileobservatory.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.media.ExternalMediaImportError
import com.indigo.mobileobservatory.media.LibraryImportState
import com.indigo.mobileobservatory.media.MediaBatchViewModel
import com.indigo.mobileobservatory.media.MediaEditRecipeStore
import com.indigo.mobileobservatory.media.MediaRename
import com.indigo.mobileobservatory.media.MediaTrash
import com.indigo.mobileobservatory.media.shareMediaFiles
import com.indigo.mobileobservatory.recording.MediaLibrary
import com.indigo.mobileobservatory.util.ImageUtils
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val libraryCategories = listOf(MediaCategory.RECENT, MediaCategory.PHOTOS, MediaCategory.VIDEOS, MediaCategory.STACK_RESULTS)

private fun categoryLabel(category: MediaCategory) = when (category) {
    MediaCategory.RECENT -> R.string.wf_recent
    MediaCategory.PHOTOS, MediaCategory.JPG -> R.string.media_category_photos
    MediaCategory.VIDEOS -> R.string.wf_videos
    MediaCategory.STACK_RESULTS -> R.string.media_category_results
    MediaCategory.SER_PSER -> R.string.media_category_ser
    MediaCategory.MP4 -> R.string.media_category_mp4
    MediaCategory.FITS -> R.string.media_category_fits
}

private fun emptyHint(category: MediaCategory) = when (category) {
    MediaCategory.RECENT -> R.string.wf_media_empty
    MediaCategory.PHOTOS, MediaCategory.JPG -> R.string.media_empty_photos
    MediaCategory.VIDEOS -> R.string.media_empty_videos
    MediaCategory.STACK_RESULTS -> R.string.media_empty_results
    MediaCategory.SER_PSER -> R.string.media_empty_ser
    MediaCategory.MP4 -> R.string.media_empty_mp4
    MediaCategory.FITS -> R.string.media_empty_fits
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MediaFileBrowser(
    recordingsDir: File?,
    capturesDir: File?,
    trashDir: File?,
    listState: LazyListState,
    files: List<VideoFileInfo>,
    loading: Boolean,
    initialCategory: MediaCategory,
    onSelect: (VideoFileInfo) -> Unit,
    onBack: () -> Unit,
    onStackFile: ((File) -> Unit)?,
    onReviewFrames: (File) -> Unit,
    selectedCategory: MediaCategory,
    excludeFile: File?,
    fileUnavailable: Boolean,
    onCategoryChange: (MediaCategory) -> Unit,
    isWorkspace: Boolean,
    onCamera: (() -> Unit)?,
    onImport: (() -> Unit)?,
    onPostStack: (() -> Unit)?,
    onPlateSolve: (() -> Unit)?,
    importState: LibraryImportState?,
    onCancelImport: () -> Unit,
    onRefresh: (Set<File>) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val notification = remember { SnackbarHostState() }
    val currentExclude by rememberUpdatedState(excludeFile)
    var showTrash by rememberSaveable { mutableStateOf(false) }
    val trashListState = rememberLazyListState()
    var trashRevision by remember { mutableIntStateOf(0) }
    val trashFiles by produceState(emptyList<VideoFileInfo>(), trashDir, showTrash, trashRevision) {
        if (showTrash) value = withContext(Dispatchers.IO) {
            trashDir?.listFiles().orEmpty()
                .filter { !it.name.startsWith('.') && !it.extension.equals("json", true) }
                .sortedByDescending(File::lastModified)
                .map { VideoFileInfo(it, it.name, it.length(), if (it.isDirectory) "FITS" else it.extension.uppercase()) }
        }
    }
    var selecting by rememberSaveable(selectedCategory) { mutableStateOf(false) }
    var selectedPaths by rememberSaveable(selectedCategory) { mutableStateOf(emptyList<String>()) }
    val selectedPathSet = remember(selectedPaths) { selectedPaths.toHashSet() }
    val selectedFiles = remember(files, selectedPathSet) { files.filter { it.file.absolutePath in selectedPathSet } }
    val batchTask: MediaBatchViewModel = viewModel(key = "media-batch:$isWorkspace:${initialCategory.name}")
    val batchState by batchTask.state.collectAsStateWithPageLifecycle()
    val exporting = batchState.busy || batchState.archive != null && !batchState.share
    var mutating by remember { mutableStateOf(false) }
    val blocked = mutating || exporting
    var pendingTrash by remember { mutableStateOf(emptyList<VideoFileInfo>()) }
    var confirmEmpty by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<VideoFileInfo?>(null) }
    var renameText by remember { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    val recipeStore = remember(context) { MediaEditRecipeStore(File(context.filesDir, "image-recipes")) }

    fun closeSelection() {
        selectedPaths = emptyList()
        selecting = false
    }

    fun toggle(info: VideoFileInfo) {
        val path = info.file.absolutePath
        selectedPaths = if (path in selectedPaths) selectedPaths - path else selectedPaths + path
    }

    BackHandler(enabled = showTrash || selecting || blocked) {
        if (!blocked) {
            if (showTrash) showTrash = false else closeSelection()
        }
    }

    fun moveToTrash(targets: List<VideoFileInfo>) {
        val trash = trashDir ?: return
        if (blocked || targets.isEmpty()) return
        mutating = true
        scope.launch {
            val moved = try {
                withContext(Dispatchers.IO) {
                    targets.mapNotNullTo(mutableSetOf()) { info ->
                        ensureActive()
                        info.file.takeIf { it != currentExclude && MediaTrash.move(it, trash) }
                    }
                }.also { moved ->
                    // Stable keys preserve the current row. If it was moved, keep a nearby survivor.
                    val first = listState.firstVisibleItemIndex
                    if (files.getOrNull(first)?.file?.let { it in moved } == true) {
                        val next = (first until files.size).firstOrNull { files[it].file !in moved }
                            ?: (first - 1 downTo 0).firstOrNull { files[it].file !in moved }
                        if (next != null) listState.scrollToItem(next, listState.firstVisibleItemScrollOffset)
                    }
                    onRefresh(moved)
                    selectedPaths = selectedPaths - moved.map { it.absolutePath }.toSet()
                    if (selecting && targets.size == moved.size) closeSelection()
                }
            } finally {
                mutating = false
            }
            if (moved.size < targets.size) {
                notification.showSnackbar(context.getString(R.string.media_trash_partial, targets.size - moved.size))
            }
        }
    }

    fun changeTrash(items: List<VideoFileInfo>, permanently: Boolean) {
        if (blocked || items.isEmpty()) return
        mutating = true
        scope.launch {
            val failures = try {
                withContext(Dispatchers.IO) {
                    items.count { info ->
                        ensureActive()
                        if (permanently) !MediaTrash.delete(info.file)
                        else {
                            val fallback = when (info.format) {
                                "SER", "PSER", "MP4" -> recordingsDir?.resolve(info.format)
                                "FIT", "FITS" -> {
                                    val root = if (info.name.startsWith("stack-", true)) capturesDir else recordingsDir
                                    root?.resolve("FITS")
                                }
                                else -> capturesDir?.resolve("JPG")
                            }
                            fallback == null || !MediaTrash.restore(info.file, fallback, listOfNotNull(recordingsDir, capturesDir))
                        }
                    }
                }
            } finally {
                trashRevision++
                onRefresh(emptySet())
                mutating = false
            }
            if (failures > 0) notification.showSnackbar(context.getString(R.string.media_trash_partial, failures))
        }
    }

    fun commitRename() {
        val info = renameTarget ?: return
        if (blocked) return
        val name = renameText
        mutating = true
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        check(info.file != currentExclude)
                        val indexed = MediaLibrary.list(recordingsDir, capturesDir, MediaCategory.RECENT, currentExclude)
                        MediaRename.rename(info.file, name, indexed, onRewritten = { recipeStore.refresh(it) })
                            .also { recipeStore.rename(info.file, it) }
                    }
                }
                result.onSuccess {
                    onRefresh(emptySet())
                    renameTarget = null
                }.onFailure {
                    renameError = context.getString(when (it.message) {
                        "name_empty", "name_invalid", "name_too_long", "name_exists" -> R.string.media_rename_invalid
                        else -> R.string.media_rename_failed
                    })
                }
            } finally {
                mutating = false
            }
        }
    }

    if (pendingTrash.isNotEmpty()) {
        val items = pendingTrash
        AlertDialog(
            onDismissRequest = { pendingTrash = emptyList() },
            title = { Text(stringResource(R.string.trash)) },
            text = {
                Text(if (items.size == 1) stringResource(R.string.move_to_trash, items.single().name)
                    else stringResource(R.string.media_move_selected, items.size))
            },
            confirmButton = {
                TextButton(onClick = { pendingTrash = emptyList(); moveToTrash(items) },
                    modifier = Modifier.testTag("media-confirm-trash")) {
                    Text(stringResource(R.string.media_move_to_trash))
                }
            },
            dismissButton = { TextButton(onClick = { pendingTrash = emptyList() }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    if (confirmEmpty) {
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text(stringResource(R.string.empty_trash)) },
            text = { Text(stringResource(R.string.media_empty_trash_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmEmpty = false; changeTrash(trashFiles, permanently = true) }) {
                    Text(stringResource(R.string.empty_trash), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
    renameTarget?.let { info ->
        AlertDialog(
            onDismissRequest = { if (!mutating) renameTarget = null },
            title = { Text(stringResource(R.string.media_rename_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = renameText, onValueChange = { renameText = it; renameError = null },
                        enabled = !mutating, label = { Text(stringResource(R.string.media_rename_label)) },
                        singleLine = true, isError = renameError != null
                    )
                    renameError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Text(stringResource(R.string.media_rename_hint, info.file.extension.uppercase()),
                        color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = ::commitRename, enabled = renameText.isNotBlank() && !mutating) {
                    Text(stringResource(R.string.rename))
                }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }, enabled = !mutating) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    Column(Modifier.fillMaxSize()) {
        if (selecting && !showTrash) {
            MediaBatchActions(
                task = batchTask, state = batchState, selected = selectedFiles, blocked = blocked,
                allSelected = selectedFiles.size == files.size, canSelect = files.isNotEmpty(),
                onSelectAll = {
                    selectedPaths = if (selectedFiles.size == files.size) emptyList() else files.map { it.file.absolutePath }
                },
                onClose = ::closeSelection,
                onMoveToTrash = if (trashDir == null) null else ({ pendingTrash = selectedFiles })
            )
        } else {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (!isWorkspace || showTrash) {
                    IconButton(onClick = { if (showTrash) showTrash = false else onBack() }, enabled = !blocked) {
                        Icon(Icons.Default.ArrowBack, stringResource(R.string.back))
                    }
                }
                Text(stringResource(if (showTrash) R.string.trash else R.string.media_library_title),
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = if (isWorkspace && !showTrash) 12.dp else 0.dp))
                if (showTrash) {
                    MediaActionsMenu(stringResource(R.string.media_more_actions), enabled = trashFiles.isNotEmpty() && !blocked) { close ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.restore_all)) },
                            onClick = { close(); changeTrash(trashFiles, permanently = false) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.empty_trash)) },
                            onClick = { close(); confirmEmpty = true })
                    }
                } else {
                    onImport?.let { importMedia ->
                        IconButton(onClick = importMedia, enabled = importState?.busy != true && !blocked,
                            modifier = Modifier.testTag("media-import")) {
                            Icon(Icons.Default.Add, stringResource(R.string.wf_import_video))
                        }
                    }
                    onPostStack?.let { stack ->
                        IconButton(onClick = stack, enabled = !blocked, modifier = Modifier.testTag("media-post-stack")) {
                            Icon(Icons.Default.Layers, stringResource(R.string.stack_offline_title))
                        }
                    }
                    MediaActionsMenu(stringResource(R.string.media_more_actions), Modifier.testTag("media-library-more"), !blocked) { close ->
                        DropdownMenuItem(text = { Text(stringResource(R.string.media_batch_select)) },
                            enabled = files.isNotEmpty() && importState?.busy != true,
                            onClick = { close(); selecting = true })
                        onPlateSolve?.let { solve ->
                            DropdownMenuItem(text = { Text(stringResource(R.string.plate_solve)) },
                                onClick = { close(); solve() }, modifier = Modifier.testTag("media-plate-solve"))
                        }
                        DropdownMenuItem(text = { Text(stringResource(R.string.trash)) }, enabled = trashDir != null,
                            onClick = { close(); showTrash = true })
                    }
                }
            }
        }

        if (!showTrash) {
            val categories = if (initialCategory in libraryCategories) libraryCategories else libraryCategories + initialCategory
            ScrollableTabRow(selectedTabIndex = categories.indexOf(selectedCategory).coerceAtLeast(0), edgePadding = 4.dp) {
                categories.forEach { category ->
                    Tab(selected = selectedCategory == category, enabled = !selecting && !blocked,
                        onClick = { onCategoryChange(category) },
                        text = { Text(stringResource(categoryLabel(category)), maxLines = 1) })
                }
            }
            if (importState?.busy == true) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.external_media_importing), Modifier.weight(1f))
                TextButton(onClick = onCancelImport) { Text(stringResource(R.string.cancel)) }
            }
            importState?.error?.let { reason ->
                Text(stringResource(when (reason) {
                    ExternalMediaImportError.MISSING_FILE -> R.string.external_media_missing
                    ExternalMediaImportError.UNSUPPORTED -> R.string.external_media_unsupported
                    ExternalMediaImportError.READ_PERMISSION -> R.string.external_media_permission
                    ExternalMediaImportError.NO_SPACE -> R.string.external_media_space
                    ExternalMediaImportError.TOO_LARGE -> R.string.external_media_large
                    ExternalMediaImportError.INVALID_FILE -> R.string.external_media_invalid
                    ExternalMediaImportError.COPY_FAILED -> R.string.external_media_copy_failed
                }), Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.error)
            }
            if (fileUnavailable) Text(stringResource(R.string.media_file_unavailable),
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = MaterialTheme.colorScheme.error)
        }
        if (mutating) LinearProgressIndicator(Modifier.fillMaxWidth())

        val displayed = if (showTrash) trashFiles else files
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag(if (showTrash) "media-trash-list" else "media-file-list"),
                state = if (showTrash) trashListState else listState
            ) {
                items(displayed, key = { it.file.absolutePath }) { info ->
                    val checked = info.file.absolutePath in selectedPathSet
                    MediaFileRow(
                        info = info, selecting = selecting && !showTrash, checked = checked,
                        onClick = if (showTrash) null else ({
                            when {
                                selecting -> toggle(info)
                                info.format == "MP4" -> openMp4(context, info.file)
                                else -> onSelect(info)
                            }
                        }),
                        onLongClick = if (showTrash || selecting || importState?.busy == true) null else ({
                            selecting = true
                            selectedPaths = listOf(info.file.absolutePath)
                        }),
                        enabled = !blocked
                    ) {
                        when {
                            showTrash -> IconButton(onClick = { changeTrash(listOf(info), permanently = false) }, enabled = !blocked) {
                                Icon(Icons.Default.Restore, stringResource(R.string.restore))
                            }
                            selecting -> Checkbox(checked = checked, onCheckedChange = null, enabled = !blocked,
                                modifier = Modifier.padding(horizontal = 12.dp))
                            else -> {
                                MediaActionsMenu(stringResource(R.string.media_file_actions, info.name),
                                    Modifier.testTag("media-file-menu-${info.name}"), enabled = !blocked) { close ->
                                    val fitsSequence = info.format == "FITS" && info.file.isDirectory
                                    val recordingFrames = info.format == "SER" || info.format == "PSER"
                                    if (recordingFrames || fitsSequence && trashDir != null) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.stack_review_frames)) },
                                            onClick = { close(); onReviewFrames(info.file) })
                                    }
                                    if (onStackFile != null && (recordingFrames || fitsSequence)) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.media_stack_action)) },
                                            onClick = { close(); onStackFile(info.file) }, modifier = Modifier.testTag("media-stack-${info.name}"))
                                    }
                                    if (info.file.isFile) {
                                        DropdownMenuItem(text = { Text(stringResource(R.string.rename)) }, onClick = {
                                            close()
                                            renameText = info.file.nameWithoutExtension
                                            renameError = null
                                            renameTarget = info
                                        }, modifier = Modifier.testTag("media-rename-${info.name}"))
                                        DropdownMenuItem(text = { Text(stringResource(R.string.media_batch_share)) }, onClick = {
                                            close()
                                            if (!shareMediaFiles(context, listOf(info.file))) {
                                                scope.launch { notification.showSnackbar(context.getString(R.string.media_share_failed)) }
                                            }
                                        }, modifier = Modifier.testTag("media-share-${info.name}"))
                                    }
                                    DropdownMenuItem(text = { Text(stringResource(R.string.media_move_to_trash)) }, enabled = trashDir != null,
                                        onClick = { close(); pendingTrash = listOf(info) })
                                }
                            }
                        }
                    }
                    Divider(Modifier.padding(start = 52.dp))
                }
            }
            if (displayed.isEmpty()) {
                Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(when {
                        showTrash -> R.string.trash_empty
                        loading -> R.string.media_loading
                        else -> emptyHint(selectedCategory)
                    }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!showTrash && !loading && onCamera != null) {
                        TextButton(onClick = onCamera) { Text(stringResource(R.string.wf_go_capture)) }
                    }
                }
            }
            SnackbarHost(notification, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaFileRow(
    info: VideoFileInfo,
    selecting: Boolean,
    checked: Boolean,
    onClick: (() -> Unit)?,
    onLongClick: (() -> Unit)?,
    enabled: Boolean,
    actions: @Composable () -> Unit
) {
    val selectLabel = stringResource(R.string.media_batch_select)
    val interaction = if (onClick == null) Modifier else Modifier.combinedClickable(
        enabled = enabled, onClick = onClick, onLongClick = onLongClick, onLongClickLabel = selectLabel
    )
    val date = remember(info) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(info.file.lastModified()))
    }
    Row(Modifier.fillMaxWidth().then(interaction)
        .then(if (selecting) Modifier.semantics { selected = checked } else Modifier)
        .heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(when (info.format) {
            "SER", "PSER", "MP4" -> Icons.Default.Movie
            "JPG", "JPEG", "PNG" -> Icons.Default.Image
            else -> Icons.Default.Description
        }, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(info.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$date · ${info.format} · ${ImageUtils.formatFileSize(info.size)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

@Composable
internal fun MediaActionsMenu(
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable (close: () -> Unit) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = enabled, modifier = modifier) {
            Icon(Icons.Default.MoreVert, description)
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            content { expanded = false }
        }
    }
}
