package com.indigo.mobileobservatory.astrometry

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.indigo.mobileobservatory.mount.MountCoordinates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

class AstapRunner(private val context: Context) {
    private val d50Manager = D50Manager(context)

    suspend fun copyUriToCache(uri: Uri): File = withContext(Dispatchers.IO) {
        val name = displayName(uri).sanitizeFileName()
        val ext = name.substringAfterLast('.', "fits")
        val target = File(context.cacheDir, "platesolve_${System.currentTimeMillis()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Unable to open selected file.")
        target
    }

    suspend fun solve(
        inputFile: File,
        fovDeg: Double,
        mountCoordinates: MountCoordinates? = null,
        searchRadiusDeg: Double? = null
    ): PlateSolveResult = withContext(Dispatchers.IO) {
        val status = d50Manager.status()
        if (!status.installed) {
            return@withContext PlateSolveResult(false, "D50 database is not installed.")
        }

        val bundledExecutable = astapExecutable()
        if (!bundledExecutable.exists()) {
            return@withContext PlateSolveResult(false, "ASTAP CLI is missing from native libraries.")
        }
        val workDir = status.directory
        val executable = File(workDir, "astap_cli")
        try {
            if (!executable.exists() || executable.length() != bundledExecutable.length()) {
                bundledExecutable.copyTo(executable, overwrite = true)
            }
            executable.setExecutable(true, false)
        } catch (e: Throwable) {
            return@withContext PlateSolveResult(
                false,
                "Unable to prepare ASTAP executable: ${e.message}",
                log = e.stackTraceToString()
            )
        }

        val start = System.currentTimeMillis()
        val before = workDir.listFiles()?.associateWith { it.lastModified() }.orEmpty()
        val dbFiles = workDir.listFiles { file ->
            file.isFile && file.name.startsWith("d50_", true) && file.name.endsWith(".1476", true)
        }.orEmpty()
        val effectiveSearchRadiusDeg = searchRadiusDeg ?: if (mountCoordinates != null) 10.0 else 180.0
        val command = mutableListOf(
            executable.absolutePath,
            "-f", inputFile.absolutePath,
            "-r", "%.6f".format(Locale.US, effectiveSearchRadiusDeg.coerceIn(0.0, 180.0)),
            "-fov", "%.6f".format(Locale.US, fovDeg.coerceIn(0.2, 90.0))
        )
        if (mountCoordinates != null) {
            command += listOf(
                "-ra", "%.6f".format(Locale.US, mountCoordinates.raHours),
                "-spd", "%.6f".format(Locale.US, mountCoordinates.decDeg + 90.0)
            )
        }
        val diagHeader = buildString {
            appendLine("ABIs=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("bundledASTAP=${bundledExecutable.absolutePath} exists=${bundledExecutable.exists()} canExec=${bundledExecutable.canExecute()}")
            appendLine("ASTAP=${executable.absolutePath} exists=${executable.exists()} canExec=${executable.canExecute()}")
            appendLine("workDir=${workDir.absolutePath}")
            appendLine("D50 files=${dbFiles.size} bytes=${dbFiles.sumOf { it.length() }}")
            appendLine("input=${inputFile.absolutePath} bytes=${inputFile.length()}")
            if (mountCoordinates != null) {
                appendLine("mountHint=raHours=${mountCoordinates.raHours} decDeg=${mountCoordinates.decDeg} radiusDeg=$effectiveSearchRadiusDeg")
            }
            appendLine("command=${command.joinToString(" ")}")
        }

        val process = try {
            ProcessBuilder(command)
                .directory(workDir)
                .redirectErrorStream(true)
                .start()
        } catch (e: Throwable) {
            return@withContext PlateSolveResult(
                success = false,
                message = "ASTAP could not start: ${e.message}",
                elapsedMs = elapsed(start),
                log = diagHeader + e.stackTraceToString()
            )
        }

        val output = StringBuilder(diagHeader)
        val readerThread = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                lines.forEach { output.appendLine(it) }
            }
        }.apply { start() }

        val finished = process.waitFor(6, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            return@withContext PlateSolveResult(false, "ASTAP solve timed out.", elapsedMs = elapsed(start), log = output.toString())
        }
        readerThread.join(1000)

        val dims = imageDimensions(inputFile)
        val candidates = candidateWcsFiles(inputFile, workDir, before)
        output.appendLine("exitCode=${process.exitValue()}")
        output.appendLine("imageDims=${dims.first}x${dims.second}")
        output.appendLine("candidateWcsFiles=${candidates.joinToString { "${it.name}:${it.length()}" }}")
        val parsed = FitsWcsParser.parseCandidates(candidates, dims.first, dims.second)
        val log = output.toString()
        if (parsed != null) {
            return@withContext PlateSolveResult(
                success = true,
                message = "Solved",
                raDeg = parsed.raDeg,
                decDeg = parsed.decDeg,
                raHms = formatRa(parsed.raDeg),
                decDms = formatDec(parsed.decDeg),
                fovWidthDeg = parsed.fovWidthDeg,
                fovHeightDeg = parsed.fovHeightDeg,
                rotationDeg = parsed.rotationDeg,
                arcsecPerPixel = parsed.arcsecPerPixel,
                elapsedMs = elapsed(start),
                wcsHeaderPath = parsed.source.absolutePath,
                log = log
            )
        }

        val exitCode = process.exitValue()
        return@withContext PlateSolveResult(
            success = false,
            message = if (exitCode == 0) "ASTAP finished but no WCS solution was found." else "ASTAP failed with exit code $exitCode.",
            elapsedMs = elapsed(start),
            log = log
        )
    }

    private fun astapExecutable(): File {
        return File(context.applicationInfo.nativeLibraryDir, "libastap_cli.so")
    }

    private fun candidateWcsFiles(inputFile: File, workDir: File, before: Map<File, Long>): List<File> {
        val stem = inputFile.nameWithoutExtension
        val dirs = listOf(inputFile.parentFile, workDir).filterNotNull().distinctBy { it.absolutePath }
        val candidates = mutableListOf<File>()
        dirs.forEach { dir ->
            dir.listFiles()?.forEach { file ->
                val wasModified = file.lastModified() > (before[file] ?: 0L)
                val related = file.nameWithoutExtension.equals(stem, true) ||
                    file.name.contains(stem, ignoreCase = true)
                val usefulExt = file.extension.lowercase(Locale.US) in setOf("fit", "fits", "wcs", "ini", "txt")
                if (file.isFile && usefulExt && (related || wasModified)) candidates.add(file)
            }
        }
        if (inputFile.extension.lowercase(Locale.US) in setOf("fit", "fits")) candidates.add(inputFile)
        return candidates.distinctBy { it.absolutePath }.sortedByDescending { it.lastModified() }
    }

    private fun imageDimensions(file: File): Pair<Int, Int> {
        if (file.extension.equals("fit", true) || file.extension.equals("fits", true)) {
            val header = file.inputStream().use { input ->
                val bytes = ByteArray(2880 * 16)
                val count = input.read(bytes)
                if (count > 0) String(bytes, 0, count, Charsets.US_ASCII) else ""
            }
            val width = Regex("NAXIS1\\s*=\\s*([0-9]+)").find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            val height = Regex("NAXIS2\\s*=\\s*([0-9]+)").find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            return width to height
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth to options.outHeight
    }

    private fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) return cursor.getString(idx)
        }
        return uri.lastPathSegment ?: "selected.fits"
    }

    private fun elapsed(start: Long): Long = System.currentTimeMillis() - start

    private fun String.sanitizeFileName(): String {
        return replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "selected.fits" }
    }

    private fun formatRa(deg: Double): String {
        val totalSeconds = deg / 15.0 * 3600.0
        val h = (totalSeconds / 3600.0).toInt()
        val m = ((totalSeconds - h * 3600.0) / 60.0).toInt()
        val s = totalSeconds - h * 3600.0 - m * 60.0
        return "%02dh %02dm %05.2fs".format(Locale.US, h, m, s)
    }

    private fun formatDec(deg: Double): String {
        val sign = if (deg < 0) "-" else "+"
        val abs = deg.absoluteValue
        val d = abs.toInt()
        val mFloat = (abs - d) * 60.0
        val m = mFloat.toInt()
        val s = (mFloat - m) * 60.0
        return "%s%02dd %02dm %05.2fs".format(Locale.US, sign, d, m, s)
    }
}
