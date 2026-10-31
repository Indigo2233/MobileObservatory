package com.indigo.mobileobservatory

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.indigo.mobileobservatory.ui.screens.CameraScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import com.indigo.mobileobservatory.util.FileLogger
import com.indigo.mobileobservatory.media.MediaNavigationContract
import com.indigo.mobileobservatory.media.MediaNavigationRequest

class MainActivity : ComponentActivity() {
    private var mediaNavigationRequest by mutableStateOf<MediaNavigationRequest?>(null)

    private fun receiveMediaNavigation(intent: Intent) {
        if (intent.action != MediaNavigationContract.ACTION_OPEN_MEDIA) return
        val requestedPath = intent.getStringExtra(MediaNavigationContract.EXTRA_FILE_PATH)
        val file = requestedPath?.let { MediaNavigationContract.ownedFile(this, it) }
        if (requestedPath != null && file == null) return
        mediaNavigationRequest = MediaNavigationRequest(android.os.SystemClock.elapsedRealtimeNanos(), file?.absolutePath)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        receiveMediaNavigation(intent)
        FileLogger.init(applicationContext)
        FileLogger.i(
            "App",
            "Indigo Observatory ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}) " +
                "buildId=${BuildConfig.SOURCE_BUILD_ID} " +
                "pid=${android.os.Process.myPid()} action=${intent?.action} " +
                "restored=${savedInstanceState != null}"
        )
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContent {
            val prefs = remember { getSharedPreferences("mobile_observatory", MODE_PRIVATE) }
            var redNightMode by remember { mutableStateOf(prefs.getBoolean("red_night_mode", false)) }
            MobileObservatoryTheme(redNightMode = redNightMode) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val viewModel: CameraViewModel = viewModel()
                    CameraScreen(
                        viewModel = viewModel,
                        redNightMode = redNightMode,
                        onRedNightModeChange = { enabled ->
                            redNightMode = enabled
                            prefs.edit().putBoolean("red_night_mode", enabled).apply()
                        },
                        mediaNavigationRequest = mediaNavigationRequest,
                        onMediaNavigationConsumed = { id ->
                            if (mediaNavigationRequest?.id == id) {
                                mediaNavigationRequest = null
                                setIntent(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                            }
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveMediaNavigation(intent)
        FileLogger.i("App", "Received intent action=${intent.action} in existing activity")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val controller = WindowInsetsControllerCompat(window, window.decorView)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
}
