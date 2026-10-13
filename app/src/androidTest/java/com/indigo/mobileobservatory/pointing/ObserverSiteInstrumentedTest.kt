package com.indigo.mobileobservatory.pointing

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.indigo.mobileobservatory.astro.ObserverSite
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ObserverSiteInstrumentedTest {
    private fun isolated(): Pair<Context, () -> Unit> {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val name = "observer-site-test-${System.nanoTime()}"
        val ctx = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int) = base.getSharedPreferences(name, mode)
            override fun getSystemService(name: String): Any = throw AssertionError("Manual site must not touch the location provider")
        }
        return ctx to { base.deleteSharedPreferences(name); Unit }
    }
    @Test fun missingSiteHasNoZeroCoordinateDefaultAndManualSelectionNeedsNoGps() = runBlocking {
        val (context, cleanup) = isolated()
        try {
            assertNull(PhoneSiteProvider.savedSite(context))
            val selected = ObserverSite(31.2, 121.5, 12.3)
            PhoneSiteProvider.saveSite(context, selected)
            assertEquals(selected, PhoneSiteProvider.savedSite(context)!!.site)
            assertEquals("manual", PhoneSiteProvider.savedSite(context)!!.origin)
            assertEquals(selected, PhoneSiteProvider.currentSite(context))
        } finally { cleanup() }
    }
    @Test fun invalidCoordinatesAreRejectedAndLegacyExplicitSiteIsPreserved() {
        val (context, cleanup) = isolated()
        try {
            assertThrows(IllegalArgumentException::class.java) { PhoneSiteProvider.saveSite(context, ObserverSite(-91.0, 0.0)) }
            assertNull(PhoneSiteProvider.savedSite(context))
            context.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit()
                .putFloat("polar_latitude_deg", 31f).putFloat("polar_longitude_deg", 121f).commit()
            assertEquals(ObserverSite(31.0, 121.0), PhoneSiteProvider.savedSite(context)!!.site)
        } finally { cleanup() }
    }
    @Test fun lateAutomaticGpsCannotReplaceManualChoiceButExplicitGpsCan() {
        val (context, cleanup) = isolated()
        try {
            val manual = ObserverSite(31.2, 121.5)
            val gps = ObserverSite(30.0, 120.0)
            PhoneSiteProvider.saveSite(context, manual)
            assertEquals(manual, PhoneSiteProvider.acceptPhoneSite(context, gps, false, 1_700_000_000_000))
            assertEquals("manual", PhoneSiteProvider.savedSite(context)!!.origin)
            assertEquals(gps, PhoneSiteProvider.acceptPhoneSite(context, gps, true, 1_700_000_000_001))
            assertEquals("gps", PhoneSiteProvider.savedSite(context)!!.origin)
        } finally { cleanup() }
    }
}
