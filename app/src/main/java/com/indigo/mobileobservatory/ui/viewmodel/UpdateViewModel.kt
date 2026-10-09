package com.indigo.mobileobservatory.ui.viewmodel

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.indigo.mobileobservatory.BuildConfig
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.update.ApkDownloader
import com.indigo.mobileobservatory.update.ApkVerification
import com.indigo.mobileobservatory.update.UpdateFeed
import com.indigo.mobileobservatory.update.UpdateInstaller
import com.indigo.mobileobservatory.update.UpdateManifest
import com.indigo.mobileobservatory.util.FileLogger
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UpdatePhase {
    IDLE,
    CHECKING,
    UP_TO_DATE,
    AVAILABLE,
    DOWNLOADING,
    PERMISSION_REQUIRED,
    INSTALL_LAUNCHED,
    ERROR
}

data class UpdateUiState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val manifest: UpdateManifest? = null,
    val progress: Float = 0f,
    val statusText: String? = null,
    val promptVisible: Boolean = false,
    val autoCheckEnabled: Boolean = true
) {
    val isBusy: Boolean
        get() = phase == UpdatePhase.CHECKING || phase == UpdatePhase.DOWNLOADING
}

/**
 * Drives the update feed lookup, APK download, and system installer hand-off.
 * Installation is always confirmed by the user through the platform installer;
 * Android does not allow silent in-place updates for sideloaded applications.
 */
class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context = application.applicationContext
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val feed = UpdateFeed()
    private val downloader = ApkDownloader()

    private val _state = MutableStateFlow(
        UpdateUiState(autoCheckEnabled = prefs.getBoolean(PREF_AUTO_CHECK, true))
    )
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private var checkJob: Job? = null
    private var downloadJob: Job? = null
    private var pendingApk: File? = null
    private var pendingVersionCode = 0

    init {
        if (_state.value.autoCheckEnabled && isAutoCheckDue()) {
            checkForUpdates(manual = false)
        }
    }

    fun checkForUpdates(manual: Boolean) {
        if (checkJob?.isActive == true || downloadJob?.isActive == true) return
        _state.update {
            it.copy(
                phase = UpdatePhase.CHECKING,
                statusText = if (manual) context.getString(R.string.update_checking) else it.statusText,
                promptVisible = false
            )
        }
        checkJob = viewModelScope.launch {
            val manifest = withContext(Dispatchers.IO) { feed.latest(feedUrls()) }
            prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply()
            if (manifest == null) {
                FileLogger.w(TAG, "Update check failed: no reachable manifest")
                _state.update {
                    if (manual) {
                        it.copy(
                            phase = UpdatePhase.ERROR,
                            statusText = context.getString(R.string.update_check_failed)
                        )
                    } else {
                        it.copy(phase = UpdatePhase.IDLE)
                    }
                }
                return@launch
            }

            val currentVersionCode = BuildConfig.VERSION_CODE
            if (!manifest.isNewerThan(currentVersionCode)) {
                FileLogger.i(TAG, "Update check: ${manifest.versionName} is not newer than $currentVersionCode")
                _state.update {
                    if (manual) {
                        it.copy(
                            phase = UpdatePhase.UP_TO_DATE,
                            statusText = context.getString(R.string.update_up_to_date)
                        )
                    } else {
                        it.copy(phase = UpdatePhase.IDLE)
                    }
                }
                return@launch
            }

            FileLogger.i(
                TAG,
                "Update available: ${manifest.versionName} (${manifest.versionCode})"
            )
            _state.update {
                it.copy(
                    phase = UpdatePhase.AVAILABLE,
                    manifest = manifest,
                    promptVisible = true,
                    statusText = context.getString(R.string.update_available_short, manifest.versionName)
                )
            }
        }
    }

    fun downloadAndInstall() {
        val manifest = _state.value.manifest ?: return
        if (downloadJob?.isActive == true || checkJob?.isActive == true) return

        val existing = pendingApk
        if (existing != null && existing.exists() && pendingVersionCode == manifest.versionCode) {
            installVerifiedPackage(existing, manifest)
            return
        }

        _state.update {
            it.copy(
                phase = UpdatePhase.DOWNLOADING,
                progress = 0f,
                promptVisible = true,
                statusText = context.getString(R.string.update_downloading, 0)
            )
        }
        downloadJob = viewModelScope.launch {
            val destination = File(
                context.cacheDir,
                "$UPDATE_DIR/IndigoObservatory_${manifest.versionCode}.apk"
            )
            val verification = try {
                withContext(Dispatchers.IO) {
                    downloader.download(
                        url = manifest.apkUrl,
                        destination = destination,
                        expectedSha256 = manifest.sha256
                    ) { fraction ->
                        _state.update {
                            it.copy(
                                progress = fraction,
                                statusText = context.getString(
                                    R.string.update_downloading,
                                    (fraction * 100).toInt()
                                )
                            )
                        }
                    }
                    UpdateInstaller.verifyApk(context, destination, manifest.versionCode)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                FileLogger.e(TAG, "Update download failed: ${error.message}")
                _state.update {
                    it.copy(
                        phase = UpdatePhase.ERROR,
                        promptVisible = true,
                        statusText = context.getString(
                            R.string.update_download_failed,
                            error.message ?: error.javaClass.simpleName
                        )
                    )
                }
                return@launch
            }

            when (verification) {
                is ApkVerification.Valid -> {
                    pendingApk = destination
                    pendingVersionCode = manifest.versionCode
                    installVerifiedPackage(destination, manifest)
                }
                is ApkVerification.Invalid -> {
                    FileLogger.e(TAG, "Update package rejected: ${verification.reason}")
                    destination.delete()
                    _state.update {
                        it.copy(
                            phase = UpdatePhase.ERROR,
                            promptVisible = true,
                            statusText = context.getString(
                                R.string.update_verify_failed,
                                verification.reason
                            )
                        )
                    }
                }
            }
        }
    }

    fun openInstallPermissionSettings() {
        try {
            context.startActivity(UpdateInstaller.unknownSourcesSettingsIntent(context))
        } catch (error: ActivityNotFoundException) {
            FileLogger.e(TAG, "Unknown-sources settings unavailable: ${error.message}")
            _state.update {
                it.copy(
                    phase = UpdatePhase.ERROR,
                    statusText = context.getString(R.string.update_install_failed)
                )
            }
        }
    }

    fun dismissPrompt() {
        if (_state.value.manifest?.isRequiredFor(BuildConfig.VERSION_CODE) == true) return
        _state.update { it.copy(promptVisible = false) }
    }

    fun setAutoCheckEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(PREF_AUTO_CHECK, enabled).apply()
        _state.update { it.copy(autoCheckEnabled = enabled) }
    }

    private fun installVerifiedPackage(apk: File, manifest: UpdateManifest) {
        if (!UpdateInstaller.canInstall(context)) {
            _state.update {
                it.copy(
                    phase = UpdatePhase.PERMISSION_REQUIRED,
                    promptVisible = true,
                    statusText = context.getString(R.string.update_permission_required)
                )
            }
            return
        }
        try {
            context.startActivity(UpdateInstaller.installIntent(context, apk))
            _state.update {
                it.copy(
                    phase = UpdatePhase.INSTALL_LAUNCHED,
                    manifest = manifest,
                    promptVisible = false,
                    statusText = context.getString(R.string.update_install_started)
                )
            }
        } catch (error: ActivityNotFoundException) {
            FileLogger.e(TAG, "Installer activity unavailable: ${error.message}")
            _state.update {
                it.copy(
                    phase = UpdatePhase.ERROR,
                    promptVisible = true,
                    statusText = context.getString(R.string.update_install_failed)
                )
            }
        }
    }

    private fun feedUrls(): List<String> = listOf(
        BuildConfig.UPDATE_MANIFEST_URL,
        BuildConfig.UPDATE_MANIFEST_FALLBACK_URL
    ).distinct()

    private fun isAutoCheckDue(): Boolean {
        val lastCheck = prefs.getLong(PREF_LAST_CHECK, 0L)
        return System.currentTimeMillis() - lastCheck >= AUTO_CHECK_INTERVAL_MS
    }

    companion object {
        private const val TAG = "UpdateVM"
        private const val PREFS_NAME = "mobile_observatory"
        private const val PREF_AUTO_CHECK = "update_auto_check"
        private const val PREF_LAST_CHECK = "update_last_check_ms"
        private const val UPDATE_DIR = "updates"
        private const val AUTO_CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L
    }
}
