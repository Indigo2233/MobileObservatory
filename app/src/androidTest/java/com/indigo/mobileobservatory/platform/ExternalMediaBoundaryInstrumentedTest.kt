package com.indigo.mobileobservatory.platform

import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.os.Bundle
import android.os.ResultReceiver
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.accessibilityservice.AccessibilityService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.indigo.mobileobservatory.R
import com.indigo.mobileobservatory.ExternalMediaActivity
import com.indigo.mobileobservatory.recording.MediaCategory
import com.indigo.mobileobservatory.recording.MediaLibrary
import java.io.File
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExternalMediaBoundaryInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private val caller get() = instrumentation.context
    private lateinit var sourceBytes: ByteArray
    private var originalAutoCheck: Boolean? = null
    private var originalFiles = emptySet<String>()
    private lateinit var fixtureToken: String
    private val fixtureStem get() = "platform-boundary-$fixtureToken"
    private val callerButton get() = "Open shared file $fixtureStem.png"
    private val sourceUri get() = ExternalCallerFileProvider.uri(fixtureToken)

    @Before fun prepareSourceInExternalCaller() {
        fixtureToken = UUID.randomUUID().toString().replace("-", "")
        val prefs = target.getSharedPreferences("mobile_observatory", 0)
        originalAutoCheck = if (prefs.contains("update_auto_check")) prefs.getBoolean("update_auto_check", true) else null
        prefs.edit().putBoolean("update_auto_check", false).commit()
        originalFiles = libraryFiles().map { it.file.absolutePath }.toSet()
        val bitmap = Bitmap.createBitmap(24, 18, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(40, 70, 110)) }
        sourceBytes = ByteArrayOutputStream().use { bytes -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bytes.toByteArray() }
        bitmap.recycle()
    }

    @After fun cleanupOwnFixtureCopies() {
        try {
            // Wait for the production viewer to finish before removing its imported copy.
            val viewers = externalViewers()
            instrumentation.runOnMainSync { viewers.forEach { it.finish() } }
            waitUntil("External viewer destruction") { externalViewers().isEmpty() }
            val closed = CountDownLatch(1)
            val closeResult = AtomicInteger(android.app.Activity.RESULT_CANCELED)
            val receiver = object : ResultReceiver(null) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    closeResult.set(resultCode); closed.countDown()
                }
            }
            // Only the framework class crosses into the caller's dependency-free process.
            val parcel = android.os.Parcel.obtain()
            val remoteReceiver = try {
                receiver.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                ResultReceiver.CREATOR.createFromParcel(parcel)
            } finally { parcel.recycle() }
            caller.startActivity(Intent().setComponent(ComponentName(caller.packageName, ExternalMediaCallerActivity::class.java.name))
                .putExtra("source_uri", sourceUri.toString()).putExtra("close_fixture", true).putExtra("close_receiver", remoteReceiver)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue("Caller fixture did not finish its cleanup", closed.await(10, TimeUnit.SECONDS))
            assertEquals("Caller could not remove its own fixture", android.app.Activity.RESULT_OK, closeResult.get())
            libraryFiles().filter { it.file.absolutePath !in originalFiles && it.file.name.contains(fixtureStem) }.forEach { it.file.delete() }
        } finally {
            val edit = target.getSharedPreferences("mobile_observatory", 0).edit()
            originalAutoCheck?.let { edit.putBoolean("update_auto_check", it) } ?: edit.remove("update_auto_check")
            edit.commit()
        }
    }

    @Test fun viewCopiesAReadOnlyContentUriAndSystemBackReturnsToTheExternalCaller() {
        openFromCaller(Intent.ACTION_VIEW)
        val imported = waitForImportedCopy()
        assertArrayEquals(sourceBytes, imported.readBytes())
        try {
            target.contentResolver.openOutputStream(sourceUri)?.close()
            fail("External source was writable")
        } catch (_: SecurityException) { } catch (_: java.io.FileNotFoundException) { }
        assertTrue(instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        waitForCaller()
        assertNotNull(text("Source kept: ${sourceDigest()}"))
        assertArrayEquals(sourceBytes, imported.readBytes())
    }

    @Test fun shareUsesTheSameLibraryAndToolbarUpStaysInTheObservatoryMediaParent() {
        openFromCaller(Intent.ACTION_SEND)
        waitForImportedCopy()
        click(waitNode { description(target.getString(R.string.external_media_up)) })
        waitUntil { activePackage() == target.packageName && text(target.getString(R.string.media_library_title)) != null }
        assertNull(text("External media caller"))
        assertArrayEquals(sourceBytes, waitForImportedCopy().readBytes())
    }

    @Test fun aRealSystemEdgeBackCanBeCancelledThenCommittedAtTheExternalBoundary() {
        openFromCaller(Intent.ACTION_VIEW)
        waitForImportedCopy()
        SystemEdgeBackGesture(instrumentation).use { gestures ->
            gestures.perform(commit = false, ready = ::viewerReady)
            waitUntil("Cancelled gesture keeps the current external file") { viewerReady() }
            assertNotNull(description(target.getString(R.string.external_media_up)))
            gestures.perform(commit = true, ready = ::viewerReady)
            waitForCaller()
        }
    }

    @Test fun mp4AndUnrelatedBinaryIntentsDoNotResolveToThisViewer() {
        val uri = sourceUri
        for (mime in listOf("video/mp4", "application/zip", "application/octet-stream")) {
            val candidates = target.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime), 0)
            assertFalse(candidates.any { it.activityInfo.name == ExternalMediaActivity::class.java.name })
        }
    }

    private fun openFromCaller(action: String) {
        val uri = sourceUri
        caller.startActivity(Intent().setComponent(ComponentName(caller.packageName, ExternalMediaCallerActivity::class.java.name))
            .putExtra("source_uri", uri.toString()).putExtra("open_action", action).putExtra("target_package", target.packageName)
            .putExtra("target_activity_class", ExternalMediaActivity::class.java.name)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        waitForCaller()
        click(waitNode { description(callerButton) })
        waitUntil("Current token imported and displayed by the production viewer") { viewerReady() }
    }

    private fun waitForImportedCopy(): File {
        waitUntil("Current token in the media library") { libraryFiles().any { it.file.absolutePath !in originalFiles && it.file.name.contains(fixtureStem) } }
        return libraryFiles().first { it.file.absolutePath !in originalFiles && it.file.name.contains(fixtureStem) }.file
    }
    private fun viewerReady() = activePackage() == target.packageName && text(fixtureStem) != null && externalViewers(Stage.RESUMED).isNotEmpty()
    private fun waitForCaller() = waitForStablePlatformWindow(instrumentation, caller.packageName) { description(callerButton) != null }
    private fun externalViewers(vararg stages: Stage = arrayOf(Stage.CREATED, Stage.STARTED, Stage.RESUMED, Stage.PAUSED, Stage.STOPPED)): List<ExternalMediaActivity> {
        var viewers = emptyList<ExternalMediaActivity>()
        instrumentation.runOnMainSync {
            viewers = stages.flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }.filterIsInstance<ExternalMediaActivity>().distinct()
        }
        return viewers
    }
    private fun libraryFiles() = MediaLibrary.list(target.getExternalFilesDir("recordings"), target.getExternalFilesDir("captures"), MediaCategory.RECENT)
    private fun sourceDigest() = MessageDigest.getInstance("SHA-256").digest(sourceBytes).joinToString("") { "%02x".format(it) }
    private fun activePackage() = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
    private fun text(value: String) = findNode { it.text?.toString()?.contains(value, ignoreCase = true) == true }
    private fun description(value: String) = findNode { it.contentDescription?.toString() == value }
    private fun findNode(matches: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (matches(node)) return node
            for (i in 0 until node.childCount) node.getChild(i)?.let { child -> find(child)?.let { return it } }
            return null
        }
        return instrumentation.uiAutomation.rootInActiveWindow?.let(::find)
    }
    private fun waitNode(find: () -> AccessibilityNodeInfo?): AccessibilityNodeInfo {
        var found: AccessibilityNodeInfo? = null
        waitUntil("Current platform control") { find().also { found = it } != null }
        return requireNotNull(found)
    }
    private fun waitUntil(state: String = "Expected platform navigation state", condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 20_000
        do {
            if (condition()) return
            SystemClock.sleep(50)
        } while (SystemClock.uptimeMillis() < end)
        fail("$state did not appear; active package=${activePackage()}")
    }
    private fun click(node: AccessibilityNodeInfo) {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), action, bounds.exactCenterX(), bounds.exactCenterY(), 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            assertTrue(instrumentation.uiAutomation.injectInputEvent(event, true)); event.recycle()
        }
        instrumentation.waitForIdleSync()
    }
}
