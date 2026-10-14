package com.indigo.mobileobservatory.platform

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo

/** Waits through Activity/SystemUI window replacement; a navigation setting alone is insufficient. */
internal fun waitForStablePlatformWindow(
    instrumentation: Instrumentation,
    expectedPackage: String,
    ready: () -> Boolean = { true }
): Rect {
    val automation = instrumentation.uiAutomation
    val originalFlags = automation.serviceInfo.flags
    automation.serviceInfo = automation.serviceInfo.apply {
        flags = flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
    }
    val deadline = SystemClock.uptimeMillis() + 10_000
    var previous: Pair<Int, Rect>? = null
    var stableSince = 0L
    var lastPackage: String? = null
    try {
        do {
            val active = automation.rootInActiveWindow
            lastPackage = active?.packageName?.toString()
            val frame = if (!ready()) null else {
                val root = active?.takeIf { it.packageName?.toString() == expectedPackage }
                    ?: automation.windows.asSequence()
                        .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                        .mapNotNull { it.root }
                        .firstOrNull { it.packageName?.toString() == expectedPackage }
                root?.let {
                    val bounds = Rect(); it.getBoundsInScreen(bounds)
                    if (bounds.width() >= 64 && bounds.height() >= 64) it.windowId to bounds else null
                }
            }
            val now = SystemClock.uptimeMillis()
            if (frame == null) {
                previous = null
            } else if (frame != previous) {
                previous = frame; stableSince = now
            } else if (now - stableSince >= 250) {
                return Rect(frame.second)
            }
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < deadline)
        throw AssertionError("No stable $expectedPackage window; active package=$lastPackage, last frame=$previous")
    } finally {
        automation.serviceInfo = automation.serviceInfo.apply { flags = originalFlags }
    }
}
