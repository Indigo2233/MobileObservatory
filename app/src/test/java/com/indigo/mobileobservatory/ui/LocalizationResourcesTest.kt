package com.indigo.mobileobservatory.ui

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalizationResourcesTest {
    @Test
    fun englishAndChineseExposeTheSameKeys() {
        val english = stringsFrom("src/main/res/values/strings.xml")
        val chinese = stringsFrom("src/main/res/values-zh-rCN/strings.xml")

        assertEquals(english.keys, chinese.keys)
        assertTrue(english.isNotEmpty())
    }

    @Test
    fun chineseCoreFlowsAreTranslatedAndContainNoMojibake() {
        val chinese = stringsFrom("src/main/res/values-zh-rCN/strings.xml")
        val coreKeys = setOf(
            "connect",
            "disconnect",
            "cancel_connection",
            "back_to_mount",
            "execute_goto",
            "stop_mount",
            "nearby_devices_permission_required",
            "plate_solve",
            "plate_solve_image"
        )

        coreKeys.forEach { key ->
            val text = requireNotNull(chinese[key])
            assertFalse("$key still uses the English fallback", text == stringsFrom("src/main/res/values/strings.xml")[key])
            assertFalse("$key contains a replacement character", text.contains('\uFFFD'))
            assertFalse("$key contains common mojibake", text.contains("瑙") || text.contains("鍥"))
        }
    }

    private fun stringsFrom(path: String): Map<String, String> {
        val file = File(path)
        assertTrue("Missing resource file: ${file.absolutePath}", file.isFile)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val nodes = document.getElementsByTagName("string")
        return buildMap {
            repeat(nodes.length) { index ->
                val node = nodes.item(index)
                put(node.attributes.getNamedItem("name").nodeValue, node.textContent)
            }
        }
    }
}
