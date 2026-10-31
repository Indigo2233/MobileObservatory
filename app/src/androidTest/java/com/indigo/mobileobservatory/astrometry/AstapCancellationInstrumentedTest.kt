package com.indigo.mobileobservatory.astrometry

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.indigo.mobileobservatory.polar.PolarSamplePhase
import com.indigo.mobileobservatory.polar.PolarSampleSession
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/** Uses Android's real Process/pipe lifecycle and the production ASTAP runner.
 * The test command holds stdout open until cancellation, without needing a star catalogue. */
class AstapCancellationInstrumentedTest {
    private lateinit var root: File
    private lateinit var context: Context
    private lateinit var input: File
    private var oldHandler: Thread.UncaughtExceptionHandler? = null
    private val uncaught = ConcurrentLinkedQueue<Throwable>()

    @Before fun prepare() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        root = java.nio.file.Files.createTempDirectory(app.cacheDir.toPath(), "astap-cancel-").toFile()
        context = object : ContextWrapper(app) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
            override fun getExternalFilesDir(type: String?) = File(root, "external").apply { mkdirs() }
        }
        File(context.filesDir, "astap/d20_0101.1476").apply { parentFile!!.mkdirs(); writeBytes(ByteArray(0)) }
        input = File(context.cacheDir, "selected.fits")
        val header = listOf("SIMPLE" to "T", "BITPIX" to "16", "NAXIS" to "2", "NAXIS1" to "384", "NAXIS2" to "256")
            .joinToString("") { (key, value) -> (key.padEnd(8) + "= " + value).padEnd(80) } + "END".padEnd(80)
        input.outputStream().use {
            it.write(header.padEnd(2880).toByteArray(Charsets.US_ASCII))
            it.write(ByteArray(((384 * 256 * 2 + 2879) / 2880) * 2880))
        }
        oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, failure ->
            if (failure.stackTrace.any { it.className.startsWith(AstapRunner::class.java.name) }) {
                uncaught += failure
            } else oldHandler?.uncaughtException(thread, failure)
        }
    }

    @After fun cleanup() {
        Thread.setDefaultUncaughtExceptionHandler(oldHandler)
        root.deleteRecursively()
    }

    @Test fun repeatedCancellationOfAnImportedImageClosesTheProcessWithoutAnUncaughtRead(): Unit = runBlocking {
        val original = input.readBytes()
        val session = PolarSampleSession()
        session.import(0, input)
        repeat(20) { pass ->
            val process = AtomicReference<Process>()
            val runner = AstapRunner(context) { builder ->
                builder.command("/system/bin/sh", "-c", "echo ASTAP-cancellation-fixture; exec /system/bin/sleep 60")
                    .start().also { process.set(it) }
            }
            val task = launch {
                session.execute(0, { input }, { 1.8 }, { file, fov -> runner.solve(file, fov, null, null) })
            }
            withTimeout(5_000) { while (process.get() == null) delay(5) }
            // Give the production output reader time to enter its blocking read.
            delay(150)
            withTimeout(3_000) {
                repeat(if (pass % 2 == 0) 1 else 5) { task.cancel() }
                task.join()
            }
            assertTrue(task.isCancelled)
            withTimeout(3_000) { while (process.get().isAlive) delay(5) }
            assertEquals(PolarSamplePhase.CANCELLED, session.samples.value[0].phase)
            assertEquals(input, session.samples.value[0].file)
            assertTrue(session.samples.value[0].imported)
            assertNull(session.samples.value[0].result)
            assertTrue("Uncaught output-reader failure at pass $pass: ${uncaught.joinToString { it.stackTraceToString() }}", uncaught.isEmpty())
            assertFalse(context.cacheDir.walkTopDown().any { it.name == "process.log" })
        }
        assertArrayEquals(original, input.readBytes())
        // Reuse the same sample with the real packaged CLI after the cancellations.
        val result = withTimeout(30_000) {
            session.execute(0, { input }, { 1.8 }, { file, fov -> AstapRunner(context).solve(file, fov, null, null) })
        }
        assertFalse(result.log, result.success)
        assertEquals(PolarSamplePhase.FAILED, session.samples.value[0].phase)
        assertTrue(result.log, result.log.contains("exitCode="))
        assertArrayEquals(original, input.readBytes())
    }

    @Test fun cancellationImmediatelyAfterLaunchStillClosesTheProcessAndTemporaryOutput(): Unit = runBlocking {
        val process = AtomicReference<Process>()
        lateinit var task: Job
        val runner = AstapRunner(context) { builder ->
            builder.command("/system/bin/sleep", "60").start().also {
                process.set(it)
                task.cancel()
            }
        }
        task = launch(start = CoroutineStart.LAZY) { runner.solve(input, 1.8, null, null) }
        task.start()
        withTimeout(3_000) { task.join() }
        assertNotNull(process.get())
        withTimeout(3_000) { while (process.get().isAlive) delay(5) }
        assertTrue(task.isCancelled)
        assertTrue(uncaught.isEmpty())
        assertFalse(context.cacheDir.walkTopDown().any { it.name == "process.log" })
    }

    @Test fun normalExitRetainsBothOutputStreamsAndCleansTheTemporaryLog(): Unit = runBlocking {
        val runner = AstapRunner(context) { builder ->
            builder.command("/system/bin/sh", "-c", "echo solver-stdout; echo solver-stderr >&2; exit 2").start()
        }
        val result = withTimeout(3_000) { runner.solve(input, 1.8, null, null) }
        assertFalse(result.success)
        assertTrue(result.log, result.log.contains("solver-stdout"))
        assertTrue(result.log, result.log.contains("solver-stderr"))
        assertTrue(result.log, result.log.contains("exitCode=2"))
        assertFalse(context.cacheDir.walkTopDown().any { it.name == "process.log" })
    }

    @Test fun launchFailureIsReportedByTheSolveAndCleansTheTemporaryLog(): Unit = runBlocking {
        val runner = AstapRunner(context) { builder ->
            builder.command("/does-not-exist/astap").start()
        }
        val result = withTimeout(3_000) { runner.solve(input, 1.8, null, null) }
        assertFalse(result.success)
        assertTrue(result.message, result.message.startsWith("ASTAP could not start"))
        assertTrue(uncaught.isEmpty())
        assertFalse(context.cacheDir.walkTopDown().any { it.name == "process.log" })
    }
}
