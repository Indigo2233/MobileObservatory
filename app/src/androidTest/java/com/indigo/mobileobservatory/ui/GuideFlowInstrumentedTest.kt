package com.indigo.mobileobservatory.ui

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.camera.*
import com.indigo.mobileobservatory.guide.GuideCalibrationState
import com.indigo.mobileobservatory.mount.*
import com.indigo.mobileobservatory.ui.screens.GuideScreen
import com.indigo.mobileobservatory.ui.theme.MobileObservatoryTheme
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.*

/** Production VM, capture pipeline and OnStep protocol; all motion stays inside the loopback fixture. */
class GuideFlowInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<WorkflowTestActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun selectionCalibrationGuidingAndExposureStopCompleteThroughTheRealVm() = withFlow { vm, endpoint ->
        compose.onNodeWithTag("guide-auto-select").performClick()
        compose.onNodeWithTag("guide-hfr", useUnmergedTree = true).assertTextContains("px", substring = true)
        compose.onNodeWithTag("guide-star-summary").performClick()
        compose.onNodeWithTag("guide-hfr-detail").assertTextContains("px", substring = true)
        compose.onNodeWithTag("guide-profile-axis").assertExists()
        instrumentation.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
        compose.onNodeWithTag("guide-start-stop").performClick()
        compose.waitUntil(15_000) { vm.guideDisplayFrame.value?.profile?.saturated == true ||
            vm.guideCalibrationState.value == GuideCalibrationState.FAILED }
        assertNotNull("A temporarily saturated selected star remains visible", vm.guideDisplayFrame.value?.primary)
        assertTrue("Calibration waits for target reacquisition", vm.guideCalibrating.value)
        assertTrue(requireNotNull(vm.guideDisplayFrame.value?.profile).saturated)
        compose.waitUntil(45_000) { vm.guideRunning.value || vm.guideCalibrationState.value == GuideCalibrationState.FAILED }
        assertEquals(vm.guideStatus.value, GuideCalibrationState.COMPLETE, vm.guideCalibrationState.value)
        assertTrue(vm.guideStatus.value, vm.guideRunning.value)
        assertTrue(endpoint.pulses > 12)
        endpoint.drift(2.5f, -1.5f)
        compose.waitUntil(15_000) { vm.guideHistory.value.any { it.raPulseMs != 0 || it.decPulseMs != 0 } }
        compose.onNodeWithTag("guide-start-stop").performClick()
        compose.waitUntil(5000) { !vm.guideRunning.value && !vm.guideAutoStarting.value }
        compose.onNodeWithTag("guide-exposure").performClick()
        compose.waitUntil(5000) { !vm.guideExposing.value && !vm.guideExposureRequested.value }
        assertNotNull(vm.guideDisplayFrame.value)
    }

    @Test fun stoppingDuringCalibrationCancelsThePendingGuideStart() = withFlow { vm, _ ->
        compose.onNodeWithTag("guide-auto-select").performClick()
        compose.onNodeWithTag("guide-start-stop").performClick()
        compose.waitUntil(10_000) { vm.guideCalibrating.value }
        compose.onNodeWithTag("guide-start-stop").performClick()
        compose.waitUntil(5000) { !vm.guideCalibrating.value && !vm.guideAutoStarting.value }
        assertFalse(vm.guideRunning.value)
        assertTrue(vm.guideExposing.value)
    }

    private fun withFlow(check: (CameraViewModel, Endpoint) -> Unit) {
        val base = instrumentation.targetContext.applicationContext
        val preferenceName = "guide-flow-${System.nanoTime()}"
        val app = object : Application() {
            init { attachBaseContext(base) }
            override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences(preferenceName, mode)
        }
        app.getSharedPreferences("mobile_observatory", Context.MODE_PRIVATE).edit().putBoolean("update_auto_check", false).commit()
        val store = ViewModelStore()
        Endpoint().use { endpoint ->
            lateinit var vm: CameraViewModel
            instrumentation.runOnMainSync {
                vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java]
                val module = vm.javaClass.getDeclaredField("mountModule").apply { isAccessible = true }.get(vm) as MountModule
                module.setMountProtocol(MountProtocolType.LX200_ONSTEP)
                module.setMountTransport(MountTransportType.TCP); module.setMountAccessMode(MountAccessMode.READ_WRITE)
                module.setMountHost("127.0.0.1"); module.setMountPort(endpoint.port.toString()); module.connectMount()
                val camera = MovingCamera(endpoint)
                val manager = vm.guideCameraManager
                manager.javaClass.getDeclaredField("activeCamera").apply { isAccessible = true }.set(manager, camera)
                @Suppress("UNCHECKED_CAST")
                val connection = manager.javaClass.getDeclaredField("_connectionState").apply { isAccessible = true }
                    .get(manager) as MutableStateFlow<ConnectionState>
                connection.value = ConnectionState.Connected(camera.cameraInfo)
                vm.setGuideExposure(50_000f); vm.setGuideCalibrationPulseMs(300); vm.setGuideFocalLength(1000f)
            }
            try {
                compose.setContent { MobileObservatoryTheme { Surface(Modifier.fillMaxSize()) { GuideScreen(vm) {} } } }
                compose.waitUntil(20_000) { vm.mountConnectionState.value is MountConnectionState.Connected &&
                    vm.guideDisplayFrame.value?.stars?.isNotEmpty() == true && vm.guideExposing.value && !vm.guideGainWriteInProgress.value }
                check(vm, endpoint)
            } finally {
                instrumentation.runOnMainSync { store.clear() }
                base.deleteSharedPreferences(preferenceName)
            }
        }
    }

    private class MovingCamera(private val endpoint: Endpoint,
        private val source: SimulatedCamera = SimulatedCamera("Guide flow fixture").apply { setPixelFormat(PixelFormat.MONO8) }
    ) : Camera by source {
        override val cameraInfo = source.cameraInfo.copy(pixelSizeUm = 2.9f)
        override fun startCapture(callback: FrameCallback) = source.startCapture(object : FrameCallback {
            override fun onFrame(frame: FrameData) {
                source.recycleBuffer(frame.data)
                val pixels = ByteArray(frame.width * frame.height) { 10 }
                val position = endpoint.position()
                for ((x, y, amplitude) in listOf(Triple(200f, 200f, 120), Triple(340f, 280f, 80), Triple(100f, 100f, 60))) {
                    val cx = x + position.first; val cy = y + position.second
                    for (py in cy.roundToInt() - 8..cy.roundToInt() + 8) for (px in cx.roundToInt() - 8..cx.roundToInt() + 8) {
                        if (px !in 0 until frame.width || py !in 0 until frame.height) continue
                        val strength = if (amplitude == 120 && endpoint.temporarilySaturated()) 400 else amplitude
                        pixels[py * frame.width + px] =
                            (10 + strength * exp(-((px - cx).pow(2) + (py - cy).pow(2)) / 8)).roundToInt().coerceIn(0, 255).toByte()
                    }
                }
                callback.onFrame(frame.copy(data = pixels, pixelFormat = PixelFormat.MONO8))
            }
        })
        override fun recycleBuffer(buf: ByteArray) { }
    }

    private class Endpoint : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port get() = server.localPort
        @Volatile var pulses = 0
        @Volatile private var client: Socket? = null
        private var x = 0f; private var y = 0f
        @Volatile private var saturatedUntil = 0L
        private var offset = "+00"; private var date = "10/27/26"; private var time = "00:00:00"
        @Synchronized fun position() = x to y
        fun temporarilySaturated() = android.os.SystemClock.elapsedRealtime() < saturatedUntil
        @Synchronized fun drift(dx: Float, dy: Float) { x += dx; y += dy }
        @Synchronized private fun pulse(command: String): String {
            val duration = command.substring(4).dropLast(1).toInt()
            when (command[3]) {
                'e' -> x += duration * .01f
                'w' -> x -= duration * .01f
                'n' -> y += duration * .009f
                's' -> y -= duration * .009f
            }
            pulses++
            // Reject several completed frames once, then reacquire the same visible target.
            if (pulses == 3) saturatedUntil = android.os.SystemClock.elapsedRealtime() + duration + 1200
            return "1"
        }
        private val worker = Thread {
            try {
                val socket = server.accept().also { client = it }
                val input = socket.getInputStream(); val output = socket.getOutputStream(); val packet = StringBuilder()
                while (true) {
                    val next = input.read(); if (next < 0) break
                    packet.append(next.toChar()); if (next.toChar() != '#') continue
                    val command = packet.toString(); packet.setLength(0)
                    val reply = when {
                        command.startsWith(":MG") -> pulse(command)
                        command == ":GVP#" -> "On-Step#"
                        command == ":GR#" -> "05:00:00#"
                        command == ":GD#" -> "+00*00:00#"
                        command == ":Gt#" || command == ":Gg#" -> "+30*00#"
                        command == ":GG#" -> "$offset#"
                        command == ":GC#" -> "$date#"
                        command == ":GL#" -> "$time#"
                        command.startsWith(":SG") -> { offset = command.substring(3).dropLast(1); "1" }
                        command.startsWith(":SC") -> { date = command.substring(3).dropLast(1); "1" }
                        command.startsWith(":SL") -> { time = command.substring(3).dropLast(1); "1" }
                        else -> ""
                    }
                    if (reply.isNotEmpty()) { output.write(reply.toByteArray()); output.flush() }
                }
            } catch (_: java.io.IOException) { }
        }.apply { isDaemon = true; start() }
        override fun close() { server.close(); client?.close(); worker.join(1000) }
    }
}
