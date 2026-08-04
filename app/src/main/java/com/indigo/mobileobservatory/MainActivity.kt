package com.indigo.mobileobservatory

import android.os.Bundle
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

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FileLogger.init(applicationContext)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

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
                        }
                    )
                }
            }
        }
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
