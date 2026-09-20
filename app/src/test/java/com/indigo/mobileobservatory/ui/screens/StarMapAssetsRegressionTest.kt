package com.indigo.mobileobservatory.ui.screens

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StarMapAssetsRegressionTest {
    @Test
    fun starMapHasNoCenterReticle() {
        val html = read("index.html")
        val css = read("styles.css")
        val js = read("app.js")
        assertFalse(html.contains("mount-reticle"))
        assertFalse(css.contains("mount-reticle"))
        assertFalse(js.contains("applyMountReticle"))
        assertFalse(js.contains("mountReticle"))
        assertTrue(html.contains("id=\"fov-frame\""))
        assertTrue(html.contains("id=\"eyepiece-fov\""))
    }

    @Test
    fun telescopeFovIsSolidAndPreviewFovIsDashed() {
        val css = read("styles.css")
        val fovFrame = cssBlock(css, "#fov-frame")
        val eyepiece = cssBlock(css, "#eyepiece-fov")
        assertTrue(fovFrame.contains("dashed"))
        assertFalse(fovFrame.contains("solid"))
        assertTrue(eyepiece.contains("solid"))
        assertFalse(eyepiece.contains("dashed"))
        assertTrue(eyepiece.contains("border-radius: 50%"))
    }

    @Test
    fun overlayLabelsDistinguishTelescopeAndPreview() {
        val js = read("app.js")
        assertTrue(js.contains("\"预览 \""))
        assertTrue(js.contains("\"望远镜 \""))
    }

    @Test
    fun draggingPausesFollowMountInsteadOfRecentering() {
        val js = read("app.js")
        assertTrue(js.contains("function pauseFollowAfterUserPan()"))
        assertTrue(js.contains("function installFollowPauseOnPan()"))
        assertTrue(js.contains("userPointerActive"))
        assertTrue(js.contains("notifyAndroid(\"onFollowMountChanged\", \"false\")"))
        assertTrue(js.contains("if (followMount && stel && !userPointerActive)"))
        assertTrue(js.contains("if (userPointerActive && enabled) return;"))
        assertTrue(js.contains("let followMount = false;"))
    }

    @Test
    fun skyAppearanceControlsEquatorialAndHorizonGrids() {
        val js = read("app.js")
        assertTrue(js.contains("function applySkyAppearance()"))
        assertTrue(js.contains("setSkyAppearance:"))
        assertTrue(js.contains("equatorial_jnow"))
        assertTrue(js.contains("azimuthal"))
        assertTrue(js.contains("lines_visible"))
        assertTrue(js.contains("labels_visible"))
        assertTrue(js.contains("hints_visible"))
        assertTrue(js.contains("applySkyAppearance();"))
    }

    private fun read(name: String): String {
        val candidates = listOf(
            File("src/stellarium/assets/stellarium/$name"),
            File("app/src/stellarium/assets/stellarium/$name")
        )
        val file = candidates.firstOrNull { it.isFile }
        assertTrue("missing stellarium/$name", file != null)
        return file!!.readText()
    }

    private fun cssBlock(css: String, selector: String): String {
        var from = 0
        while (from < css.length) {
            val start = css.indexOf("\n$selector {", from)
            assertTrue("$selector missing", start >= 0)
            val open = css.indexOf('{', start)
            val close = css.indexOf('}', open)
            val block = css.substring(open, close + 1)
            if (block.contains("border:")) return block
            from = close + 1
        }
        throw AssertionError("$selector has no border rule")
    }
}
