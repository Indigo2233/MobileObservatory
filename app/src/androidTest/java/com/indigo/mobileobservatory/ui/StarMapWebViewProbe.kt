package com.indigo.mobileobservatory.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class StarMapWebViewProbe(root: View) {
    private val web = requireNotNull(findWeb(root))

    fun evaluate(script: String): String {
        val value = AtomicReference<String>()
        val done = CountDownLatch(1)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            web.evaluateJavascript(script) { value.set(it); done.countDown() }
        }
        assertTrue("WebView evaluation timed out", done.await(10, TimeUnit.SECONDS))
        return value.get()
    }

    /** Keep the JS source-age check on the same controlled clock as the native selector. */
    fun freezeUtcNow(utcMs: Long) { evaluate("Date.now = function () { return $utcMs; }; true;") }

    private fun findWeb(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) findWeb(view.getChildAt(index))?.let { return it }
        return null
    }
}
