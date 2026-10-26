package com.indigo.mobileobservatory.ui

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.runner.RunWith

/** Exercises the production Activity, app-scoped Chinese resources and landscape transition. */
@RunWith(AndroidJUnit4::class)
class GuideMainActivityInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private var autoCheck: Boolean? = null
    private var locales: LocaleList? = null

    @get:Rule(order = 0) val environment = object : ExternalResource() {
        override fun before() {
            val prefs = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
            autoCheck = if (prefs.contains("update_auto_check")) prefs.getBoolean("update_auto_check", true) else null
            prefs.edit().putBoolean("update_auto_check", false).commit()
            if (Build.VERSION.SDK_INT >= 33) {
                val manager = app.getSystemService(LocaleManager::class.java)
                locales = manager.applicationLocales
                manager.applicationLocales = LocaleList.forLanguageTags("zh-CN")
            }
        }
        override fun after() {
            val edit = app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit()
            autoCheck?.let { edit.putBoolean("update_auto_check", it) } ?: edit.remove("update_auto_check")
            edit.commit()
            if (Build.VERSION.SDK_INT >= 33) locales?.let { app.getSystemService(LocaleManager::class.java).applicationLocales = it }
        }
    }
    @get:Rule(order = 1) val compose = createAndroidComposeRule<MainActivity>()

    @Test fun chineseMainActivityCanOpenGuideAndRotateToLandscape() {
        compose.onNodeWithTag("workspace-TOOLS").performClick()
        compose.onNodeWithTag("tool-guide").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        compose.onNodeWithTag("guide-image").assertExists()
        compose.onNodeWithTag("guide-graph").assertExists()
        compose.onNodeWithTag("guide-scatter").assertExists()
        compose.onNodeWithText("设备连接").assertIsDisplayed()
        saveGuideScreenshot("chinese-main-landscape")
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("校准").performScrollTo().performClick()
        compose.onNodeWithTag("guide-steps-input").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("guide-steps-increase").assertIsDisplayed()
        compose.onNodeWithTag("guide-settings-close").assertIsDisplayed()
        saveGuideScreenshot("settings-chinese-landscape")
        compose.onNodeWithTag("guide-settings-close").performClick()
        compose.onNodeWithTag("guide-image").assertIsDisplayed()
    }
}
