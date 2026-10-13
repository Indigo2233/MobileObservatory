package com.indigo.mobileobservatory.platform

import android.app.Instrumentation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assume.assumeTrue

/** Injects into SystemUI's edge region, never calls the app's Back callback directly. */
internal class SystemEdgeBackGesture(private val instrumentation: Instrumentation) : AutoCloseable {
    private val automation get() = instrumentation.uiAutomation
    private val original = Settings.Secure.getInt(instrumentation.targetContext.contentResolver, "navigation_mode", -1)
    private val originalHardKeyboardIme = Settings.Secure.getString(instrumentation.targetContext.contentResolver, "show_ime_with_hard_keyboard")

    init {
        assumeTrue("System gesture test needs a supported emulator navigation overlay", original in setOf(0, 2))
        try {
            if (original != 2) shell("cmd overlay enable --user 0 com.android.internal.systemui.navbar.gestural")
            val deadline = SystemClock.uptimeMillis() + 5000
            while (Settings.Secure.getInt(instrumentation.targetContext.contentResolver, "navigation_mode", -1) != 2 && SystemClock.uptimeMillis() < deadline)
                SystemClock.sleep(50)
            assumeTrue("Gesture navigation overlay could not be activated", Settings.Secure.getInt(instrumentation.targetContext.contentResolver, "navigation_mode", -1) == 2)
            shell("settings put secure show_ime_with_hard_keyboard 1")
        } catch (failure: Throwable) {
            if (original == 0) shell("cmd overlay enable --user 0 com.android.internal.systemui.navbar.threebutton")
            throw failure
        }
    }

    fun perform(commit: Boolean, ready: () -> Boolean = { true }) {
        val bounds = waitForStablePlatformWindow(instrumentation, instrumentation.targetContext.packageName, ready)
        val width = bounds.width().toFloat(); val y = bounds.top + bounds.height() * .55f
        val downTime = SystemClock.uptimeMillis()
        fun event(action: Int, x: Float) {
            val motion = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            motion.source = InputDevice.SOURCE_TOUCHSCREEN
            try { check(automation.injectInputEvent(motion, true)) { "System edge gesture injection failed" } }
            finally { motion.recycle() }
            SystemClock.sleep(35)
        }
        event(MotionEvent.ACTION_DOWN, bounds.left + 1f)
        listOf(.04f, .08f, .14f, .22f, .30f).forEach { event(MotionEvent.ACTION_MOVE, bounds.left + width * it) }
        if (commit) {
            event(MotionEvent.ACTION_MOVE, bounds.left + width * .55f)
            event(MotionEvent.ACTION_UP, bounds.left + width * .65f)
        } else {
            listOf(.22f, .14f, .07f, .015f).forEach { event(MotionEvent.ACTION_MOVE, bounds.left + width * it) }
            event(MotionEvent.ACTION_UP, bounds.left + 1f)
        }
        instrumentation.waitForIdleSync()
    }

    private fun shell(command: String): String = automation.executeShellCommand(command).use { descriptor ->
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
    }

    override fun close() {
        if (original == 0) shell("cmd overlay enable --user 0 com.android.internal.systemui.navbar.threebutton")
        if (originalHardKeyboardIme == null) shell("settings delete secure show_ime_with_hard_keyboard")
        else shell("settings put secure show_ime_with_hard_keyboard $originalHardKeyboardIme")
    }
}
