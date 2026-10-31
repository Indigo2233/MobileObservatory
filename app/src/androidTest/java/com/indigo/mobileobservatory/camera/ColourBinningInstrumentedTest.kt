package com.indigo.mobileobservatory.camera

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.stacking.LiveStackPhase
import com.indigo.mobileobservatory.ui.viewmodel.CameraViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/** Real connection/settings/callback path with an SDK adapter whose even-bin CFA is unknown. */
@RunWith(AndroidJUnit4::class)
class ColourBinningInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as Application
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)

    @Test fun connectedEvenBinColourUsesSoftwareBinAndCanStartLiveStack() = runBlocking {
        workspace(initialBin = 2, connect = true) { vm, camera ->
            await { camera.isCapturing.value && vm.binningUsesSoftware.value }
            await { vm.scienceCaptureReady.value }
            assertEquals(2, vm.binning.value)
            assertEquals(1, camera.currentHardwareBin)
            assertEquals(listOf(1), camera.binWrites.toList())
            main { vm.startLiveStack() }
            assertEquals(vm.stacking.task.value.message, LiveStackPhase.RUNNING, vm.stacking.live.state.value.phase)
            val key = requireNotNull(vm.stacking.live.state.value.key)
            assertEquals(1, key.hardwareBin)
            assertEquals(2, key.softwareBin)
            assertTrue(key.format.isBayer)
            await { vm.stacking.live.state.value.received > 0 }
            assertNotEquals(LiveStackPhase.FAILED, vm.stacking.live.state.value.phase)
        }
    }

    @Test fun selectingAnAlreadyDisplayedUnsafeBinRechecksTheCamera() = runBlocking {
        workspace(initialBin = 2, connect = false) { vm, camera ->
            main {
                @Suppress("UNCHECKED_CAST")
                val displayedBin = vm.javaClass.getDeclaredField("_binning").apply { isAccessible = true }.get(vm) as MutableStateFlow<Int>
                displayedBin.value = 2
                vm.setBinning(2)
            }
            await { vm.binningUsesSoftware.value }
            assertEquals(2, vm.binning.value)
            assertEquals(1, camera.currentHardwareBin)
            assertEquals(listOf(1), camera.binWrites.toList())
        }
    }

    private suspend fun workspace(initialBin: Int, connect: Boolean,
        test: suspend (CameraViewModel, BinningCamera) -> Unit) {
        val store = ViewModelStore()
        lateinit var vm: CameraViewModel
        val camera = BinningCamera(initialBin)
        try {
            main { vm = ViewModelProvider(store, ViewModelProvider.AndroidViewModelFactory(app))[CameraViewModel::class.java] }
            await { vm.stacking.calibration.state.value.initialized }
            main {
                val manager = vm.cameraManager
                manager.javaClass.getDeclaredField("activeCamera").apply { isAccessible = true }.set(manager, camera)
                if (connect) {
                    @Suppress("UNCHECKED_CAST")
                    val connection = manager.javaClass.getDeclaredField("_connectionState").apply { isAccessible = true }
                        .get(manager) as MutableStateFlow<ConnectionState>
                    connection.value = ConnectionState.Connected(requireNotNull(camera.cameraInfo))
                }
            }
            test(vm, camera)
        } finally { main { store.clear() }; camera.close() }
    }

    private class BinningCamera(initialBin: Int, private val delegate: SimulatedCamera =
        SimulatedCamera("Colour bin fallback fixture")) : Camera by delegate, CameraBinningCapable {
        override val recordsLiveViewAsScience = true
        override val sciencePixelTraits get() = CameraScienceLayouts.fullContainer(currentPixelFormat)
        override val supportedHardwareBins = listOf(1, 2)
        @Volatile override var currentHardwareBin = initialBin
        private var nextRun = 0L
        @Volatile private var activeRun: Long? = null
        override val scienceCaptureGeneration get() = activeRun
        val binWrites = CopyOnWriteArrayList<Int>()
        override fun startCapture(callback: FrameCallback) {
            val run = ++nextRun
            activeRun = run
            delegate.startCapture(object : FrameCallback {
                override fun onFrame(frame: FrameData) = callback.onFrame(frame.copy(captureGeneration = run))
            })
        }
        override fun stopCapture() { activeRun = null; delegate.stopCapture() }
        override fun close() { activeRun = null; delegate.close() }
        override fun setHardwareBin(bin: Int): Boolean {
            check(!isCapturing.value)
            activeRun = null
            binWrites += bin
            currentHardwareBin = bin
            return true
        }
    }

    private suspend fun await(test: () -> Boolean) = withTimeout(15_000) { while (!test()) delay(20) }
}
