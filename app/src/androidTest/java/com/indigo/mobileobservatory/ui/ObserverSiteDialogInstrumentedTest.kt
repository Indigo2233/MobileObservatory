package com.indigo.mobileobservatory.ui

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.pointing.PhoneSiteProvider
import com.indigo.mobileobservatory.ui.screens.ObserverSiteDialog
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ObserverSiteDialogInstrumentedTest {
    @get:Rule val compose = createComposeRule()
    @Test fun manualCoordinatesAreAValidExplicitChoiceAndNoDefaultsCanBeCommitted() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE)
        val previous = prefs.all.filterKeys { it.startsWith("observer_site_") }
        val visible = mutableStateOf(true)
        try {
            compose.setContent { MaterialTheme { if (visible.value) ObserverSiteDialog(null, onDismiss = { visible.value = false }) } }
            compose.onNodeWithText(context.getString(R.string.wf_observer_manual)).performClick()
            compose.onNodeWithText(context.getString(R.string.wf_observer_use)).assertIsNotEnabled()
            compose.onNodeWithTag("observer-latitude").performTextReplacement("91")
            compose.onNodeWithTag("observer-longitude").performTextReplacement("121.5")
            compose.onNodeWithText(context.getString(R.string.wf_observer_use)).assertIsNotEnabled()
            compose.onNodeWithTag("observer-latitude").performTextReplacement("31.2")
            compose.onNodeWithText(context.getString(R.string.wf_observer_use)).assertIsEnabled().performClick()
            compose.onNodeWithText(context.getString(R.string.wf_observer_site)).assertDoesNotExist()
            assertEquals(31.2, PhoneSiteProvider.savedSite(context)!!.site.latitudeDeg, 0.0)
        } finally {
            val editor = prefs.edit()
            prefs.all.keys.filter { it.startsWith("observer_site_") }.forEach { editor.remove(it) }
            previous.forEach { (key, value) -> when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                else -> Unit
            } }
            editor.commit()
        }
    }
}
