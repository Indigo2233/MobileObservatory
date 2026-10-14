package com.indigo.mobileobservatory

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.indigo.mobileobservatory.media.*
import com.indigo.mobileobservatory.ui.screens.PlayerScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Standard-launch viewer preserves the external caller's task. It owns no camera/device VM. */
class ExternalMediaActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val importer = ViewModelProvider(this)[ExternalMediaImportViewModel::class.java]
        importer.start(intent)
        setContent {
            val red = getSharedPreferences("mobile_observatory", MODE_PRIVATE).getBoolean("red_night_mode", false)
            val state by importer.state.collectAsState()
            MobileObservatoryTheme(redNightMode = red) {
                Surface(Modifier.fillMaxSize()) {
                    when (val current = state) {
                        is ExternalMediaImportState.Ready -> Box(Modifier.fillMaxSize().systemBarsPadding()) {
                            PlayerScreen(recordingsDir = getExternalFilesDir("recordings"), capturesDir = getExternalFilesDir("captures"),
                                initialFile = current.file, onBack = { finish() }, onNavigateUp = ::navigateUp)
                        }
                        else -> ImportStatus(current, retry = { importer.start(intent, retry = true) },
                            onBack = { finish() }, onNavigateUp = ::navigateUp)
                    }
                }
            }
        }
    }

    private fun navigateUp() {
        startActivity(MediaNavigationContract.parentIntent(this))
        finish()
    }
}

internal sealed interface ExternalMediaImportState {
    data object Loading : ExternalMediaImportState
    data class Ready(val file: File) : ExternalMediaImportState
    data class Failed(val reason: ExternalMediaImportError) : ExternalMediaImportState
}

internal class ExternalMediaImportViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val mutableState = MutableStateFlow<ExternalMediaImportState>(ExternalMediaImportState.Loading)
    val state = mutableState.asStateFlow()
    private var started = false

    fun start(intent: Intent, retry: Boolean = false) {
        if (started && !retry) return
        started = true
        val app = getApplication<Application>()
        val restored = saved.get<String>("imported_media_path")?.let { MediaNavigationContract.ownedFile(app, it) }
        if (restored != null) { mutableState.value = ExternalMediaImportState.Ready(restored); return }
        mutableState.value = ExternalMediaImportState.Loading
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { ExternalMediaImporter.importFile(app, intent) }
                saved["imported_media_path"] = file.absolutePath
                mutableState.value = ExternalMediaImportState.Ready(file)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                mutableState.value = ExternalMediaImportState.Failed((error as? ExternalMediaImportException)?.reason ?: ExternalMediaImportError.COPY_FAILED)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun ImportStatus(state: ExternalMediaImportState, retry: () -> Unit, onBack: () -> Unit, onNavigateUp: () -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.external_media_title)) }, navigationIcon = {
        IconButton(onClick = onNavigateUp) { Icon(Icons.Default.ArrowBack, stringResource(R.string.external_media_up)) }
    }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state is ExternalMediaImportState.Failed) {
                Text(stringResource(when (state.reason) {
                    ExternalMediaImportError.MISSING_FILE -> R.string.external_media_missing
                    ExternalMediaImportError.UNSUPPORTED -> R.string.external_media_unsupported
                    ExternalMediaImportError.READ_PERMISSION -> R.string.external_media_permission
                    ExternalMediaImportError.NO_SPACE -> R.string.external_media_space
                    ExternalMediaImportError.TOO_LARGE -> R.string.external_media_large
                    ExternalMediaImportError.INVALID_FILE -> R.string.external_media_invalid
                    ExternalMediaImportError.COPY_FAILED -> R.string.external_media_copy_failed
                }))
                Button(onClick = retry) { Text(stringResource(R.string.retry)) }
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(stringResource(R.string.external_media_importing))
            }
            TextButton(onClick = onBack) { Text(stringResource(R.string.external_media_return)) }
        }
    }
}
