package com.indigo.mobileobservatory.astrometry

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import com.indigo.mobileobservatory.mount.MountCoordinates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

class AstapRunner(private val context: Context) : ImagePlateSolver {
    companion object {
        // ASTAP instances share catalog/configuration state. Serialize all callers,
        // including image tasks that are temporarily covered by another screen.
        private val solveLock = Mutex()
    }
    private val d50Manager = D50Manager(context)
    override val ready: Boolean get() = d50Manager.status().installed

    suspend fun copyUriToCache(uri: Uri, sourceLastModifiedMs: Long? = null): File = withContext(Dispatchers.IO) {
        val name = displayName(uri).sanitizeFileName()
        val ext = name.substringAfterLast('.', "fits")
        val target = File.createTempFile("platesolve_", ".$ext", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Unable to open selected file.")
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            sourceLastModifiedMs?.takeIf { it > 0 }?.let { time ->
                check(target.setLastModified(time)) { "Unable to preserve the selected image timestamp." }
            }
            target
        } catch (failure: Throwable) { target.delete(); throw failure }
    }

    override suspend fun solve(
        inputFile: File,
        fovDeg: Double,
        mountCoordinates: MountCoordinates?,
        searchRadiusDeg: Double?
    ): PlateSolveResult = solveLock.withLock {
        solveExclusive(inputFile, fovDeg, mountCoordinates, searchRadiusDeg)
    }

    private suspend fun solveExclusive(
        inputFile: File, fovDeg: Double, mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?
    ): PlateSolveResult = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        val first = solveAttempt(inputFile, fovDeg, mountCoordinates, searchRadiusDeg)
        val original = retryDenseJpegSolve(inputFile, first) { maxStars ->
            solveAttempt(inputFile, fovDeg, mountCoordinates, searchRadiusDeg, maxStars = maxStars)
        }
        if (!astapNeedsSum2Retry(original)) return@withContext original
        val sum = AstapSum2Input.inspect(inputFile) ?: return@withContext original
        val base = createAstapOutputBase(context.cacheDir)
        val temporary = File(base.parentFile, "input-sum2.fits")
        try {
            val task = kotlinx.coroutines.currentCoroutineContext()
            sum.write(temporary) { task.ensureActive() }
            val retry = solveAttempt(temporary, fovDeg, mountCoordinates, searchRadiusDeg, base, 1)
            val restored = sum.restore(retry)
            val attempt = if (sum.outputBitpix == 32) "int32_sum2" else "uint16_sum2"
            restored.copy(elapsedMs = elapsed(started), log = original.log +
                "\nattempt=$attempt originalDims=${sum.width}x${sum.height} solverDims=${sum.width / 2}x${sum.height / 2}\n" + restored.log)
        } finally { temporary.delete() }
    }

    private suspend fun solveAttempt(
        inputFile: File,
        fovDeg: Double,
        mountCoordinates: MountCoordinates?,
        searchRadiusDeg: Double?,
        outputOverride: File? = null,
        solverBinning: Int? = null,
        maxStars: Int? = null
    ): PlateSolveResult = withContext(Dispatchers.IO) {
        val status = d50Manager.status()
        val database = status.database
        if (!status.installed || database == null) {
            return@withContext PlateSolveResult(false, "An ASTAP D20 or D50 database is not installed.")
        }

        val executable = astapNativeExecutable(File(context.applicationInfo.nativeLibraryDir))
        if (!executable.exists()) {
            return@withContext PlateSolveResult(false, "ASTAP CLI is missing from native libraries.")
        }
        if (isForbiddenAppDataExecutable(executable, context.filesDir, context.cacheDir)) {
            return@withContext PlateSolveResult(
                false,
                "ASTAP must run from native libraries, not app data: ${executable.absolutePath}"
            )
        }
        // Android 10+ blocks exec from writable app data (files/cache). Run the
        // packaged native library in place; it is already extracted with +x.
        runCatching { executable.setExecutable(true, true) }
        val workDir = status.directory
        deleteStaleCopiedAstapExecutables(workDir)

        val start = System.currentTimeMillis()
        val outputBase = outputOverride ?: createAstapOutputBase(context.cacheDir)
        val dbFiles = workDir.listFiles { file ->
            file.isFile && file.name.startsWith(database.filePrefix, true) && file.name.endsWith(".1476", true)
        }.orEmpty()
        val effectiveSearchRadiusDeg = astapSearchRadiusDeg(mountCoordinates, searchRadiusDeg)
        val command = buildAstapCommand(
            executable = executable,
            inputFile = inputFile,
            fovDeg = fovDeg,
            databaseDir = workDir,
            database = database,
            mountCoordinates = mountCoordinates,
            searchRadiusDeg = effectiveSearchRadiusDeg,
            outputBase = outputBase,
            solverBinning = solverBinning,
            maxStars = maxStars
        )
        val diagHeader = buildString {
            appendLine("ABIs=${Build.SUPPORTED_ABIS.joinToString()}")
            appendLine("ASTAP=${executable.absolutePath} exists=${executable.exists()} canExec=${executable.canExecute()}")
            appendLine("workDir=${workDir.absolutePath}")
            appendLine("${database.displayName} files=${dbFiles.size} bytes=${dbFiles.sumOf { it.length() }}")
            appendLine("input=${inputFile.absolutePath} bytes=${inputFile.length()}")
            appendLine("outputBase=${outputBase.absolutePath}")
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

        val finished = try { awaitSolverProcess(process, TimeUnit.MINUTES.toMillis(6)) }
            catch (cancel: kotlinx.coroutines.CancellationException) {
                readerThread.join(1_000)
                throw cancel
            }
        if (!finished) {
            process.destroyForcibly()
            return@withContext PlateSolveResult(false, "ASTAP solve timed out.", elapsedMs = elapsed(start), log = output.toString())
        }
        readerThread.join(1000)

        val dims = imageDimensions(inputFile)
        val exitCode = process.exitValue()
        val candidates = astapOutputFiles(outputBase)
        output.appendLine("exitCode=$exitCode")
        output.appendLine("imageDims=${dims.first}x${dims.second}")
        output.appendLine("candidateWcsFiles=${candidates.joinToString { "${it.name}:${it.length()}" }}")
        output.appendLine("PLTSOLVD=${candidates.firstOrNull { it.extension == "ini" }?.let { FitsHeaderReader.read(it)["PLTSOLVD"] } ?: "missing"}")
        val parsed = parseAstapSolution(exitCode, candidates, dims.first, dims.second)
        output.appendLine("solutionSource=${parsed?.source?.absolutePath ?: "none"}")
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
                log = log,
                imageWcs = SolvedImageWcs.parse(FitsHeaderReader.read(parsed.source), dims.first, dims.second)
            )
        }

        return@withContext PlateSolveResult(
            success = false,
            message = if (exitCode == 0) "ASTAP finished but no WCS solution was found." else "ASTAP failed with exit code $exitCode.",
            elapsedMs = elapsed(start),
            log = log
        )
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
        if (uri.scheme == "file") return uri.lastPathSegment ?: "selected.fits"
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

internal const val ASTAP_NATIVE_LIBRARY = "libastap_cli.so"

/** Retry the same JPEG once when ASTAP's default star limit was reached without a match. */
internal suspend fun retryDenseJpegSolve(
    inputFile: File,
    first: PlateSolveResult,
    retry: suspend (Int) -> PlateSolveResult
): PlateSolveResult {
    if (first.success || (!inputFile.extension.equals("jpg", true) && !inputFile.extension.equals("jpeg", true)) ||
        !first.log.contains("No solution found", ignoreCase = true)) return first
    val selected = Regex("(\\d+) stars?,\\s*\\d+ quads selected in the image", RegexOption.IGNORE_CASE)
        .find(first.log)?.groupValues?.get(1)?.toIntOrNull() ?: return first
    if (selected < 450) return first
    val second = retry(1000)
    return second.copy(elapsedMs = first.elapsedMs + second.elapsedMs,
        log = first.log + "\nattempt=dense_jpeg maxStars=1000\n" + second.log)
}

internal fun astapNativeExecutable(nativeLibraryDir: File): File =
    File(nativeLibraryDir, ASTAP_NATIVE_LIBRARY)

internal fun astapSearchRadiusDeg(mountCoordinates: MountCoordinates?, searchRadiusDeg: Double?): Double =
    searchRadiusDeg ?: if (mountCoordinates != null) 10.0 else 180.0

internal fun isForbiddenAppDataExecutable(executable: File, vararg writableRoots: File): Boolean {
    val path = executable.absolutePath.replace('\\', '/').lowercase(Locale.US)
    return writableRoots.any { root ->
        val prefix = root.absolutePath.replace('\\', '/').lowercase(Locale.US).trimEnd('/')
        path == prefix || path.startsWith("$prefix/")
    }
}

internal fun deleteStaleCopiedAstapExecutables(workDir: File) {
    listOf("astap_cli", "astap").forEach { name ->
        File(workDir, name).takeIf { it.isFile }?.delete()
    }
}

internal fun buildAstapCommand(
    executable: File,
    inputFile: File,
    fovDeg: Double,
    databaseDir: File,
    database: AstapDatabase,
    mountCoordinates: MountCoordinates?,
    searchRadiusDeg: Double,
    outputBase: File,
    solverBinning: Int? = null,
    maxStars: Int? = null
): List<String> {
    val command = mutableListOf(
        executable.absolutePath,
        "-f", inputFile.absolutePath,
        "-o", outputBase.absolutePath,
        "-d", databaseDir.absolutePath,
        "-D", database.displayName.lowercase(Locale.US),
        "-r", "%.6f".format(Locale.US, searchRadiusDeg.coerceIn(0.0, 180.0)),
        "-fov", "%.6f".format(Locale.US, if (fovDeg == 0.0) 0.0 else fovDeg.coerceIn(0.2, 90.0))
    )
    solverBinning?.let { command += listOf("-z", it.toString()) }
    maxStars?.let { command += listOf("-s", it.toString()) }
    if (mountCoordinates != null) {
        command += listOf(
            "-ra", "%.6f".format(Locale.US, mountCoordinates.raHours),
            "-spd", "%.6f".format(Locale.US, mountCoordinates.decDeg + 90.0)
        )
    }
    return command
}
