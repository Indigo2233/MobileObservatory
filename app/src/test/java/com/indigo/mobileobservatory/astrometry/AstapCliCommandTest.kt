package com.indigo.mobileobservatory.astrometry

import com.indigo.mobileobservatory.mount.MountCoordinates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class AstapCliCommandTest {
    private val nativeLib = File("/data/app/com.indigo.mobileobservatory/lib/arm64/libastap_cli.so")
    private val filesAstap = File("/data/user/0/com.indigo.mobileobservatory/files/astap")
    private val filesDir = File("/data/user/0/com.indigo.mobileobservatory/files")
    private val cacheDir = File("/data/user/0/com.indigo.mobileobservatory/cache")

    @Test
    fun `native executable is libastap_cli in nativeLibraryDir not filesDir`() {
        val executable = astapNativeExecutable(File("/data/app/pkg/lib/arm64"))
        assertEquals("libastap_cli.so", executable.name)
        assertEquals(ASTAP_NATIVE_LIBRARY, executable.name)
        assertFalse(isForbiddenAppDataExecutable(executable, filesDir, cacheDir))
    }

    @Test
    fun `copied filesDir binary is forbidden because Android denies exec there`() {
        val copied = File(filesAstap, "astap_cli")
        assertTrue(
            "regression: ProcessBuilder on files/astap/astap_cli yields error=13",
            isForbiddenAppDataExecutable(copied, filesDir, cacheDir)
        )
        assertTrue(isForbiddenAppDataExecutable(File(filesAstap, "astap"), filesDir, cacheDir))
        assertTrue(isForbiddenAppDataExecutable(File(cacheDir, "astap_cli"), filesDir, cacheDir))
        assertFalse(isForbiddenAppDataExecutable(nativeLib, filesDir, cacheDir))
    }

    @Test
    fun `command argv0 is the bundled native library and databases stay in filesDir`() {
        val input = File("/cache/frame.fits")
        val command = buildAstapCommand(
            executable = nativeLib,
            inputFile = input,
            fovDeg = 1.2,
            databaseDir = filesAstap,
            database = AstapDatabase.D20,
            mountCoordinates = null,
            searchRadiusDeg = astapSearchRadiusDeg(null, null)
        )

        assertEquals(nativeLib.absolutePath, command.first())
        assertTrue(command.first().endsWith(ASTAP_NATIVE_LIBRARY))
        assertFalse(command.first().replace('\\', '/').contains("/files/astap/"))
        assertFalse(command.any { it.endsWith("astap_cli") && !it.endsWith(ASTAP_NATIVE_LIBRARY) })
        assertEquals(filesAstap.absolutePath, valueAfter(command, "-d"))
        assertEquals("d20", valueAfter(command, "-D"))
        assertEquals(input.absolutePath, valueAfter(command, "-f"))
        assertEquals("1.200000", valueAfter(command, "-fov"))
        assertEquals("180.000000", valueAfter(command, "-r"))
        assertFalse(command.contains("-ra"))
    }

    @Test
    fun `mount hint uses ten degree search and south pole distance`() {
        val mount = MountCoordinates(raHours = 12.5, decDeg = -10.0)
        val command = buildAstapCommand(
            executable = nativeLib,
            inputFile = File("/cache/frame.fits"),
            fovDeg = 0.5,
            databaseDir = filesAstap,
            database = AstapDatabase.D50,
            mountCoordinates = mount,
            searchRadiusDeg = astapSearchRadiusDeg(mount, null)
        )

        assertEquals("d50", valueAfter(command, "-D"))
        assertEquals("12.500000", valueAfter(command, "-ra"))
        assertEquals("80.000000", valueAfter(command, "-spd"))
        assertEquals("10.000000", valueAfter(command, "-r"))
    }

    @Test
    fun `explicit search radius overrides the mount default`() {
        val mount = MountCoordinates(raHours = 1.0, decDeg = 0.0)
        assertEquals(30.0, astapSearchRadiusDeg(mount, 30.0), 0.0)
        assertEquals(10.0, astapSearchRadiusDeg(mount, null), 0.0)
        assertEquals(180.0, astapSearchRadiusDeg(null, null), 0.0)
    }

    @Test
    fun `fov and search radius are clamped to ASTAP limits`() {
        val wide = buildAstapCommand(
            executable = nativeLib,
            inputFile = File("/cache/frame.fits"),
            fovDeg = 120.0,
            databaseDir = filesAstap,
            database = AstapDatabase.D20,
            mountCoordinates = null,
            searchRadiusDeg = 400.0
        )
        val narrow = buildAstapCommand(
            executable = nativeLib,
            inputFile = File("/cache/frame.fits"),
            fovDeg = 0.01,
            databaseDir = filesAstap,
            database = AstapDatabase.D20,
            mountCoordinates = null,
            searchRadiusDeg = -5.0
        )
        assertEquals("90.000000", valueAfter(wide, "-fov"))
        assertEquals("180.000000", valueAfter(wide, "-r"))
        assertEquals("0.200000", valueAfter(narrow, "-fov"))
        assertEquals("0.000000", valueAfter(narrow, "-r"))
    }

    @Test
    fun `stale copied astap binaries in the database dir are removed`() {
        val workDir = createTempDirectory("astap-regression").toFile()
        try {
            File(workDir, "astap_cli").writeText("copied-cli")
            File(workDir, "astap").writeText("copied-astap")
            File(workDir, "d20_0100.1476").writeText("database")
            deleteStaleCopiedAstapExecutables(workDir)
            assertFalse(File(workDir, "astap_cli").exists())
            assertFalse(File(workDir, "astap").exists())
            assertTrue(File(workDir, "d20_0100.1476").isFile)
        } finally {
            workDir.deleteRecursively()
        }
    }

    private fun valueAfter(command: List<String>, flag: String): String {
        val index = command.indexOf(flag)
        assertTrue("$flag missing from $command", index >= 0 && index + 1 < command.size)
        return command[index + 1]
    }
}
