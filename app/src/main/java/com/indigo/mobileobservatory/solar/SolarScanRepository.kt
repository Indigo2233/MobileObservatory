package com.indigo.mobileobservatory.solar

import com.indigo.mobileobservatory.media.MediaTrash
import com.indigo.mobileobservatory.media.MediaUsageRegistry
import com.indigo.mobileobservatory.recording.SerFrameEditor
import com.indigo.mobileobservatory.util.FitsHeaderParser
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.logging.Level
import java.util.logging.Logger
import java.util.zip.CRC32
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

data class SolarSourceIdentity(
    val fileName: String,
    val size: Long,
    val modifiedUtcMs: Long,
    val digest: String
) {
    fun matches(file: File): Boolean = file.isFile &&
        file.name == fileName &&
        file.length() == size &&
        SolarSourceIdentity.of(file).digest == digest

    companion object {
        fun of(file: File): SolarSourceIdentity = of(file, file.name)

        internal fun of(file: File, publishedName: String): SolarSourceIdentity {
            require(file.isFile) { "Solar source is missing." }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(publishedName.toByteArray(Charsets.UTF_8))
            digest.update(file.length().toString().toByteArray(Charsets.US_ASCII))
            file.inputStream().buffered(64 * 1024).use { input ->
                val buffer = ByteArray(64 * 1024)
                val total = file.length()
                if (total <= 2L * 1024L * 1024L) {
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                    }
                } else {
                    var remaining = 1024L * 1024L
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        remaining -= count
                    }
                    java.io.RandomAccessFile(file, "r").use { random ->
                        random.seek(file.length() - 1024L * 1024L)
                        remaining = 1024L * 1024L
                        while (remaining > 0) {
                            val count = random.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) break
                            digest.update(buffer, 0, count)
                            remaining -= count
                        }
                    }
                }
            }
            return SolarSourceIdentity(
                publishedName,
                file.length(),
                file.lastModified(),
                digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
            )
        }
    }
}

enum class SolarSourceDispositionState {
    KEPT,
    MOVED_TO_TRASH,
    DELETED,
    FAILED,
    SKIPPED
}

data class SolarScanLegRecord(
    val number: Int,
    val direction: SolarScanDirection,
    val source: SolarSourceIdentity?,
    val recordingFrames: Int,
    val droppedFrames: Int,
    val reconstruction: SolarReconstructionSummary?,
    val sourceDisposition: SolarSourceDispositionState,
    val failure: String? = null,
    val coverage: SolarScanCoverageReport? = null,
    val analysis: SolarSpectrumAnalysis? = null,
    val reconstructionSettings: SolarReconstructionSettings? = null,
    val sourceDispositionCancelled: Boolean = false,
    val sourceMissing: Boolean = false
)

data class SolarScanTaskRecord(
    val id: String,
    val createdAtUtc: String,
    val completedAtUtc: String?,
    val title: String,
    val configuration: SolarScanConfiguration,
    val analysis: SolarSpectrumAnalysis?,
    val reconstructionSettings: SolarReconstructionSettings,
    val legs: List<SolarScanLegRecord>,
    val sourcePolicy: SolarSourcePolicy,
    val warnings: List<String>,
    val resultDirectory: File,
    val batchId: String? = null
)

data class SolarManifestWriteResult(
    val record: SolarScanTaskRecord,
    val manifest: File
) {
    val resultDirectory: File get() = record.resultDirectory
}

/** Atomic result publication. The directory rename is the commit point. */
class SolarScanRepository(
    private val recordingsDir: File,
    capturesDir: File,
    private val recoveryPermits: File = File(capturesDir, ".solar-disposition")
) {
    private val solarRoot = File(capturesDir, "SOLAR")
    private val stagingRoot = File(capturesDir, ".solar-staging")
    val trashDirectory = File(recordingsDir, ".trash")

    fun newTaskId(now: Instant = Instant.now()): String {
        val stamp = now.toString()
            .replace("-", "")
            .replace(":", "")
            .replace(".", "")
            .take(15)
        return "solar-$stamp-${UUID.randomUUID().toString().take(8)}"
    }

    fun createStaging(taskId: String): File {
        require(taskId.matches(Regex("[A-Za-z0-9._-]{3,96}"))) { "Invalid solar task id." }
        check(stagingRoot.isDirectory || stagingRoot.mkdirs()) { "Cannot create solar staging directory." }
        val directory = File(stagingRoot, taskId)
        check(!directory.exists()) { "Solar staging directory already exists." }
        check(directory.mkdirs()) { "Cannot create solar task staging directory." }
        return directory
    }

    fun commit(
        stagingDirectory: File,
        taskId: String,
        record: SolarScanTaskRecord
    ): SolarManifestWriteResult {
        require(stagingDirectory.isDirectory)
        require(taskId == record.id) { "Solar task id and manifest id differ." }
        require(stagingDirectory.parentFile?.canonicalFile == stagingRoot.canonicalFile) {
            "Solar staging directory is outside the repository."
        }
        val destination = File(solarRoot, taskId)
        check(!destination.exists()) { "Solar result already exists." }
        check(solarRoot.isDirectory || solarRoot.mkdirs()) { "Cannot create solar result root." }
        val published = relocateProducts(record.copy(resultDirectory = stagingDirectory), destination)
        writeManifest(stagingDirectory, published)
        if (!stagingDirectory.renameTo(destination)) {
            Files.move(
                stagingDirectory.toPath(),
                destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE
            )
        }
        return SolarManifestWriteResult(published, File(published.resultDirectory, MANIFEST_NAME))
    }

    fun updateManifest(record: SolarScanTaskRecord) {
        val directory = record.resultDirectory.canonicalFile
        check(directory.parentFile?.canonicalFile == solarRoot.canonicalFile) {
            "Solar manifest update is outside the committed result root."
        }
        check(directory.isDirectory) { "Solar result directory is missing." }
        writeManifest(directory, relocateProducts(record, directory))
    }

    fun listTasks(): List<SolarScanTaskRecord> {
        if (!solarRoot.isDirectory) return emptyList()
        return solarRoot.listFiles().orEmpty()
            .filter { it.isDirectory }
            .mapNotNull(::readTask)
            .sortedByDescending { it.createdAtUtc }
    }

    fun readTask(directory: File): SolarScanTaskRecord? = runCatching {
        val manifest = File(directory, MANIFEST_NAME)
        if (!manifest.isFile) return null
        relocateProducts(parseTask(manifest.readText(Charsets.UTF_8), directory), directory)
    }.getOrNull()

    fun recoverStaging(): List<File> {
        val directories = stagingRoot.listFiles().orEmpty().filter { it.isDirectory }
        val recovered = mutableListOf<File>()
        for (staging in directories) {
            val manifest = File(staging, MANIFEST_NAME)
            if (!manifest.isFile) {
                staging.deleteRecursively()
                continue
            }
            val task = runCatching { parseTask(manifest.readText(), staging) }.getOrNull() ?: continue
            val destination = File(solarRoot, task.id)
            if (destination.exists()) {
                staging.deleteRecursively()
                continue
            }
            solarRoot.mkdirs()
            if (staging.renameTo(destination)) {
                writeManifest(destination, relocateProducts(task, destination))
                recovered += destination
            }
        }
        return recovered
    }

    internal fun markSimulationSourceComplete(partial: File): File {
        val sealed = File(partial.parentFile, partial.name.removeSuffix(".partial") + ".sealed")
        try {
            Files.move(partial.toPath(), sealed.toPath())
        } catch (failure: IOException) {
            // The recordings volume may be unavailable while app-private storage still works.
            runCatching {
                Files.createDirectories(recoveryPermits.toPath())
                Files.createFile(completionMarker(partial).toPath())
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
        return sealed
    }

    private fun completionMarker(partial: File): File = File(recoveryPermits, "${partial.name}.complete")

    /** Publish only recordings with an explicit completion marker. */
    fun cleanupIncompleteSources(): Int {
        val sources = File(recordingsDir, "SER")
        if (!sources.isDirectory) return 0
        var removed = 0
        sources.listFiles().orEmpty()
            .filter { it.isFile && it.name.startsWith(".solar-") }
            .forEach { partial ->
                val marker = completionMarker(partial)
                val isSealed = partial.name.endsWith(".ser.sealed")
                val isPartial = partial.name.endsWith(".ser.partial")
                if (isSealed || isPartial && marker.isFile) {
                    val source = File(sources, partial.name.removePrefix(".").substringBeforeLast('.'))
                    try {
                        Files.move(partial.toPath(), source.toPath())
                    } catch (_: IOException) {
                        // Keep the sealed file for another recovery attempt if publishing fails.
                        return@forEach
                    }
                    if (isPartial) {
                        runCatching { Files.deleteIfExists(marker.toPath()) }.onFailure {
                            logger.log(Level.WARNING, "Could not remove the completion marker for ${source.name}", it)
                        }
                    }
                } else if (isPartial) {
                    // Legacy partials predate the completion marker. Preserve any whose
                    // trailer may represent a completed recording, without publishing it.
                    val mayBeComplete = try {
                        SerFrameEditor.readLayout(partial).timestampOffset != null
                    } catch (_: EOFException) {
                        false
                    } catch (_: IllegalArgumentException) {
                        false
                    } catch (_: ArithmeticException) {
                        false
                    } catch (_: IOException) {
                        true
                    }
                    if (!mayBeComplete && partial.delete()) removed++
                }
            }
        return removed
    }

    private fun recoveryPermit(taskId: String): File {
        require(taskId.matches(Regex("[A-Za-z0-9._-]{3,96}")))
        return File(recoveryPermits, "$taskId.pending")
    }

    /** Establish recovery authorization before starting the worker. Publication never grants it. */
    internal fun authorizeDispositionRecovery(taskId: String) {
        val permit = recoveryPermit(taskId)
        Files.createDirectories(recoveryPermits.toPath())
        Files.createFile(permit.toPath())
    }

    internal fun revokeRecoveryPermit(taskId: String) {
        Files.deleteIfExists(recoveryPermit(taskId).toPath())
    }

    /** Runs during startup, before any new worker can create its staging directory. */
    internal fun cleanOrphanedRecoveryPermits(storageMounted: () -> Boolean, activeTaskId: () -> String?) {
        if (!storageMounted()) return
        val captures = solarRoot.parentFile?.listFiles() ?: return
        val savedTaskIds = mutableSetOf<String>()
        for (root in listOf(solarRoot, stagingRoot)) {
            val directory = captures.firstOrNull { it == root } ?: continue
            val tasks = directory.listFiles() ?: return
            tasks.mapTo(savedTaskIds) { it.name }
        }
        recoveryPermits.listFiles().orEmpty().forEach { permit ->
            if (!permit.isFile || !permit.name.endsWith(".pending")) return@forEach
            val taskId = permit.name.removeSuffix(".pending")
            if (!storageMounted()) return
            if (taskId == activeTaskId() || taskId in savedTaskIds) return@forEach
            cleanTerminalRecoveryPermit(taskId)
        }
    }

    private fun cleanTerminalRecoveryPermit(taskId: String) {
        try {
            revokeRecoveryPermit(taskId)
        } catch (failure: Exception) {
            logger.log(Level.WARNING, "Could not remove the recovery permit for $taskId", failure)
        }
    }

    /**
     * Completes source disposition after a process stop that occurred between result
     * publication and the final manifest update.
     */
    fun reconcilePendingDispositions(): List<File> {
        val changed = mutableListOf<File>()
        for (task in listTasks()) {
            if (task.sourcePolicy == SolarSourcePolicy.KEEP || task.resultDirectory.name != task.id) continue
            val mayDispose = runCatching { recoveryPermit(task.id).isFile }.getOrDefault(false)
            var taskChanged = false
            val legs = task.legs.map { leg ->
                val identity = leg.source ?: return@map leg
                if (!leg.hasPendingDisposition() && !leg.sourceMissing) return@map leg
                val path = recordedSource(identity)
                val updated = if (mayDispose && leg.hasPendingDisposition()) {
                    disposeCommittedSource(task, leg, path, recovering = true)
                } else {
                    reconcileWithoutDisposal(task, leg, path)
                }
                if (updated != leg) taskChanged = true
                updated
            }
            if (taskChanged) {
                updateManifest(task.copy(legs = legs))
                changed += task.resultDirectory
            }
            if (mayDispose && legs.none { it.hasPendingDisposition() }) cleanTerminalRecoveryPermit(task.id)
        }
        return changed
    }

    private fun reconcileWithoutDisposal(
        task: SolarScanTaskRecord,
        leg: SolarScanLegRecord,
        source: File
    ): SolarScanLegRecord {
        val identity = checkNotNull(leg.source)
        val trashed = task.sourcePolicy == SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS &&
            runCatching { identity.matches(File(trashDirectory, identity.fileName)) }.getOrDefault(false)
        val sourceAbsent = Files.notExists(source.toPath())
        val sourceMatches = if (sourceAbsent) false else if (leg.sourceMissing || trashed) {
            runCatching { identity.matches(source) }.getOrNull()
        } else null
        return when {
            sourceMatches == true -> leg.copy(sourceMissing = false)
            trashed && (sourceAbsent || sourceMatches == false) -> leg.copy(
                sourceDisposition = SolarSourceDispositionState.MOVED_TO_TRASH,
                sourceMissing = false
            )
            // A missing file could also have been renamed or removed outside this task.
            sourceAbsent -> leg.copy(sourceMissing = true)
            else -> leg
        }
    }

    /**
     * Saves the outcomes already reached on STOP, including terminal keeps for any source
     * that recovery could otherwise still dispose of.
     */
    internal fun disposeCommittedSources(
        task: SolarScanTaskRecord,
        dispositionWarnings: (List<SolarScanLegRecord>) -> List<String> = { emptyList() },
        onRecoveryClosed: () -> Unit = {},
        commitDisposition: (() -> Boolean) -> Boolean = { it() },
        checkActive: () -> Unit
    ): SolarScanTaskRecord {
        val legs = task.legs.toMutableList()
        var persisted = task
        try {
            checkActive()
            for ((index, leg) in task.legs.withIndex()) {
                if (!leg.hasPendingDisposition()) continue
                legs[index] = disposeCommittedSource(
                    task,
                    leg,
                    recordedSource(checkNotNull(leg.source)),
                    commitDisposition = commitDisposition,
                    checkActive = checkActive
                )
            }
            checkActive()
            val updated = task.copy(legs = legs, warnings = task.warnings + dispositionWarnings(legs))
            try {
                updateManifest(updated)
            } catch (failure: Exception) {
                checkActive()
                throw failure
            }
            persisted = updated
            checkActive()
            if (task.sourcePolicy != SolarSourcePolicy.KEEP && legs.none { it.hasPendingDisposition() }) {
                cleanTerminalRecoveryPermit(updated.id)
            }
            return updated
        } catch (cancelled: CancellationException) {
            keepPendingSourcesAfterStop(persisted.copy(legs = legs), persisted, onRecoveryClosed)
            throw cancelled
        }
    }

    internal fun keepPendingSourcesAfterStop(
        task: SolarScanTaskRecord,
        persisted: SolarScanTaskRecord = task,
        onRecoveryClosed: () -> Unit = {}
    ) {
        if (task.sourcePolicy == SolarSourcePolicy.KEEP) return
        val keptLegs = task.legs.map { leg ->
            if (leg.hasPendingDisposition()) leg.copy(
                sourceDisposition = SolarSourceDispositionState.SKIPPED,
                failure = leg.failure ?: DISPOSITION_CANCELLED,
                sourceDispositionCancelled = true
            ) else leg
        }
        val stopped = task.copy(legs = keptLegs)
        val revokeFailure = runCatching { revokeRecoveryPermit(task.id) }.exceptionOrNull()
        if (revokeFailure == null) onRecoveryClosed()
        if (stopped == persisted && revokeFailure == null) return
        try {
            updateManifest(stopped)
        } catch (failure: Exception) {
            revokeFailure?.let(failure::addSuppressed)
            throw failure
        }
        if (revokeFailure != null) onRecoveryClosed()
    }

    private fun recordedSource(identity: SolarSourceIdentity): File =
        File(File(recordingsDir, "SER"), identity.fileName)

    private fun SolarScanLegRecord.hasPendingDisposition(): Boolean =
        source != null && !sourceDispositionCancelled && when (sourceDisposition) {
            SolarSourceDispositionState.KEPT, SolarSourceDispositionState.FAILED -> true
            SolarSourceDispositionState.SKIPPED -> failure != DISPOSITION_CANCELLED
            SolarSourceDispositionState.MOVED_TO_TRASH, SolarSourceDispositionState.DELETED -> false
        }

    internal fun disposeCommittedSource(
        task: SolarScanTaskRecord,
        leg: SolarScanLegRecord,
        source: File,
        recovering: Boolean = false,
        commitDisposition: (() -> Boolean) -> Boolean = { it() },
        checkActive: () -> Unit = {}
    ): SolarScanLegRecord {
        checkActive()
        val priorFailure = leg.failure?.takeUnless(::isDispositionFailure)
        if (task.sourcePolicy == SolarSourcePolicy.KEEP) {
            return leg.copy(sourceDisposition = SolarSourceDispositionState.KEPT, failure = priorFailure)
        }
        fun failed(reason: String) = leg.copy(
            sourceDisposition = SolarSourceDispositionState.FAILED,
            failure = "result_validation:$reason"
        )
        val identity = leg.source ?: return failed("source_identity_missing")
        val reconstruction = leg.reconstruction ?: return failed("result_missing")
        val coverage = leg.coverage
        if (coverage != null) {
            if (!coverage.completeDiskCovered) return failed("coverage_incomplete")
            if (coverage.accelerationOverlapsDisk) return failed("coverage_acceleration_overlap")
            if (coverage.decelerationOverlapsDisk) return failed("coverage_deceleration_overlap")
        }
        val root = runCatching { task.resultDirectory.canonicalFile }.getOrNull()
            ?: return failed("result_root_invalid")
        val settings = leg.reconstructionSettings ?: task.reconstructionSettings
        if (settings.products.isEmpty()) return failed("result_missing")
        val products = mutableMapOf<SolarOutputProduct, File>()
        fun invalidProduct(product: SolarOutputProduct) =
            failed("result_product_invalid:${product.name.lowercase()}")
        for (product in settings.products) {
            val path = when (product) {
                SolarOutputProduct.DISPLAY_PNG -> reconstruction.displayPng
                SolarOutputProduct.LINEAR_PNG -> reconstruction.linearPng
                SolarOutputProduct.LINEAR_FITS -> reconstruction.linearFits
            }
            if (path.isNullOrBlank()) return invalidProduct(product)
            val file = runCatching { File(path).canonicalFile }.getOrNull()
                ?: return invalidProduct(product)
            if (!file.toPath().startsWith(root.toPath())) return invalidProduct(product)
            products[product] = file
        }
        val lease = try {
            MediaUsageRegistry.acquire(products.values)
        } catch (_: IllegalArgumentException) {
            val missing = products.entries.firstOrNull { !it.value.isFile }
            return missing?.let { invalidProduct(it.key) } ?: failed("result_products_unavailable")
        }
        lease.use {
            for ((product, file) in products) {
                checkActive()
                val valid = try {
                    reconstruction.integrity[product]?.matches(file) ?: validateLegacyProduct(
                        file,
                        product,
                        reconstruction.width,
                        reconstruction.height
                    )
                } catch (_: IOException) {
                    false
                }
                if (!valid) return invalidProduct(product)
            }
            checkActive()
            var identityMismatch = false
            var recoveredState: SolarSourceDispositionState? = null
            val canDispose = {
                checkActive()
                val matches = try {
                    identity.matches(source)
                } catch (_: IOException) {
                    false
                } catch (_: IllegalArgumentException) {
                    false
                }
                if (!matches) {
                    if (recovering && !source.exists()) {
                        recoveredState = when (task.sourcePolicy) {
                            SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS ->
                                if (File(trashDirectory, identity.fileName).isFile) {
                                    SolarSourceDispositionState.MOVED_TO_TRASH
                                } else SolarSourceDispositionState.FAILED
                            SolarSourcePolicy.DELETE_AFTER_COMMIT -> SolarSourceDispositionState.DELETED
                            SolarSourcePolicy.KEEP -> SolarSourceDispositionState.KEPT
                        }
                    } else {
                        identityMismatch = true
                    }
                }
                checkActive()
                matches
            }
            val disposition = disposeSource(source, task.sourcePolicy, commitDisposition, canDispose)
            if (identityMismatch) return failed("source_identity")
            val state = recoveredState ?: disposition
            return leg.copy(
                sourceDisposition = state,
                sourceMissing = false,
                failure = priorFailure ?: "source_disposition_failed"
                    .takeIf { state == SolarSourceDispositionState.FAILED }
            )
        }
    }

    private fun validateLegacyProduct(
        file: File,
        product: SolarOutputProduct,
        width: Int,
        height: Int
    ): Boolean = runCatching {
        when (product) {
            SolarOutputProduct.DISPLAY_PNG,
            SolarOutputProduct.LINEAR_PNG -> validatePng(file, width, height)
            SolarOutputProduct.LINEAR_FITS -> validateFits(file, width, height)
        }
    }.getOrDefault(false)

    private fun validatePng(file: File, width: Int, height: Int): Boolean =
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < 57L) return false
            val signature = ByteArray(8)
            input.readFully(signature)
            val expectedSignature = byteArrayOf(
                0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
            )
            if (!signature.contentEquals(expectedSignature)) return false
            var sawHeader = false
            var sawData = false
            val buffer = ByteArray(64 * 1024)
            while (input.filePointer < input.length()) {
                if (input.length() - input.filePointer < 12L) return false
                val length = input.readInt()
                if (length < 0 || length.toLong() > input.length() - input.filePointer - 4L) {
                    return false
                }
                val type = ByteArray(4)
                input.readFully(type)
                val crc = CRC32().apply { update(type) }
                var remaining = length
                val header = if (type.contentEquals("IHDR".toByteArray(Charsets.US_ASCII))) {
                    if (length != 13) return false
                    ByteArray(length)
                } else null
                var headerOffset = 0
                while (remaining > 0) {
                    val count = minOf(buffer.size, remaining)
                    input.readFully(buffer, 0, count)
                    crc.update(buffer, 0, count)
                    if (header != null) {
                        buffer.copyInto(header, headerOffset, 0, count)
                        headerOffset += count
                    }
                    remaining -= count
                }
                if ((input.readInt().toLong() and 0xffff_ffffL) != crc.value) return false
                val name = String(type, Charsets.US_ASCII)
                when (name) {
                    "IHDR" -> {
                        val fileWidth = ((header!![0].toInt() and 255) shl 24) or
                            ((header[1].toInt() and 255) shl 16) or
                            ((header[2].toInt() and 255) shl 8) or
                            (header[3].toInt() and 255)
                        val fileHeight = ((header[4].toInt() and 255) shl 24) or
                            ((header[5].toInt() and 255) shl 16) or
                            ((header[6].toInt() and 255) shl 8) or
                            (header[7].toInt() and 255)
                        if (fileWidth != width || fileHeight != height) return false
                        sawHeader = true
                    }
                    "IDAT" -> if (length > 0) sawData = true
                    "IEND" -> {
                        if (length != 0 || !sawHeader || !sawData) return false
                        return true
                    }
                }
            }
            false
        }

    private fun validateFits(file: File, width: Int, height: Int): Boolean =
        RandomAccessFile(file, "r").use { input ->
            val header = FitsHeaderParser.read(input)
            val dataStart = input.filePointer
            val bytes = width.toLong() * height.toLong() * 2L
            val padded = (bytes + 2_879L) / 2_880L * 2_880L
            header["NAXIS"] == "2" &&
                header["NAXIS1"]?.toIntOrNull() == width &&
                header["NAXIS2"]?.toIntOrNull() == height &&
                header["BITPIX"] == "16" &&
                input.length() >= dataStart + padded
        }

    private fun disposeSource(
        source: File,
        policy: SolarSourcePolicy,
        commitDisposition: (() -> Boolean) -> Boolean,
        canDispose: () -> Boolean
    ): SolarSourceDispositionState = when (policy) {
        SolarSourcePolicy.KEEP -> SolarSourceDispositionState.KEPT
        SolarSourcePolicy.TRASH_AFTER_SEVEN_DAYS ->
            if (MediaTrash.moveIf(source, trashDirectory, commitDisposition, canDispose)) {
                SolarSourceDispositionState.MOVED_TO_TRASH
            } else SolarSourceDispositionState.FAILED
        SolarSourcePolicy.DELETE_AFTER_COMMIT ->
            if (MediaTrash.deleteIf(source, commitDisposition, canDispose)) {
                SolarSourceDispositionState.DELETED
            } else SolarSourceDispositionState.FAILED
    }

    private fun isDispositionFailure(failure: String): Boolean =
        failure == "source_disposition_failed" ||
            failure.startsWith("source_disposition:") ||
            failure.startsWith("result_validation:")

    private fun writeManifest(directory: File, record: SolarScanTaskRecord) {
        val target = File(directory, MANIFEST_NAME)
        val temporary = File(directory, ".$MANIFEST_NAME.${UUID.randomUUID()}.tmp")
        temporary.writeText(taskJson(record).toString(2), Charsets.UTF_8)
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: Exception) {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun taskJson(record: SolarScanTaskRecord): JSONObject = JSONObject().apply {
        put("schema", SCHEMA)
        put("id", record.id)
        put("resultRoot", record.resultDirectory.absolutePath)
        put("createdAtUtc", record.createdAtUtc)
        put("completedAtUtc", record.completedAtUtc)
        put("title", record.title)
        record.batchId?.let { put("batchId", it) }
        put("configuration", configurationJson(record.configuration))
        record.analysis?.let { analysis ->
            put("analysis", analysisJson(analysis))
        }
        put("reconstructionSettings", reconstructionSettingsJson(record.reconstructionSettings))
        put("sourcePolicy", record.sourcePolicy.name)
        put("warnings", JSONArray(record.warnings))
        put("legs", JSONArray().apply {
            record.legs.forEach { leg ->
                put(JSONObject().apply {
                    put("number", leg.number)
                    put("direction", leg.direction.name)
                    leg.source?.let { put("source", identityJson(it)) }
                    put("recordingFrames", leg.recordingFrames)
                    put("droppedFrames", leg.droppedFrames)
                    put("sourceDisposition", leg.sourceDisposition.name)
                    if (leg.sourceDispositionCancelled) put("sourceDispositionCancelled", true)
                    if (leg.sourceMissing) put("sourceMissing", true)
                    put("failure", leg.failure)
                    leg.analysis?.let { put("analysis", analysisJson(it)) }
                    leg.reconstructionSettings?.let {
                        put("reconstructionSettings", reconstructionSettingsJson(it))
                    }
                    leg.coverage?.let { coverage ->
                        put("coverage", JSONObject().apply {
                            put("firstContactFraction", coverage.firstContactFraction)
                            put("lastContactFraction", coverage.lastContactFraction)
                            put("startBlankArcsec", coverage.startBlankArcsec)
                            put("endBlankArcsec", coverage.endBlankArcsec)
                            put("completeDiskCovered", coverage.completeDiskCovered)
                            put("accelerationOverlapsDisk", coverage.accelerationOverlapsDisk)
                            put("decelerationOverlapsDisk", coverage.decelerationOverlapsDisk)
                            put("stableCoverageFraction", coverage.stableCoverageFraction)
                        })
                    }
                    leg.reconstruction?.let { summary ->
                        put("reconstruction", JSONObject().apply {
                            put("source", summary.source)
                            put("displayPng", summary.displayPng)
                            put("linearPng", summary.linearPng)
                            put("linearFits", summary.linearFits)
                            put("width", summary.width)
                            put("height", summary.height)
                            put("frameStride", summary.frameStride)
                            put("sourceFrames", summary.sourceFrames)
                            put("warnings", JSONArray(summary.warnings))
                            put("corrections", correctionReportJson(summary.corrections))
                            if (summary.integrity.isNotEmpty()) {
                                put("artifactIntegrity", JSONObject().apply {
                                    summary.integrity.forEach { (product, integrity) ->
                                        put(product.name, JSONObject().apply {
                                            put("byteCount", integrity.byteCount)
                                            put("sha256", integrity.sha256)
                                        })
                                    }
                                })
                            }
                            summary.memory?.let { memory ->
                                put("memory", JSONObject().apply {
                                    memory.headroomBytes?.let { put("headroomBytes", it) }
                                    put("firstSampledAtUtc", memory.firstSampledAtUtc)
                                    put("sampleCount", memory.sampleCount)
                                    put("maxIntervalMs", memory.maxIntervalMs)
                                })
                            }
                            put("completedAtUtc", summary.completedAtUtc)
                        })
                    }
                })
            }
        })
    }

    private fun relocateProducts(
        record: SolarScanTaskRecord,
        destination: File
    ): SolarScanTaskRecord {
        val sourceRoot = record.resultDirectory.absoluteFile
        val destinationRoot = destination.canonicalFile
        fun relocate(path: String?): String? = path?.let {
            runCatching {
                val relative = sourceRoot.toPath().relativize(File(it).absoluteFile.toPath())
                val target = File(destinationRoot, relative.toString()).canonicalFile
                require(target.toPath().startsWith(destinationRoot.toPath()))
                target.absolutePath
            }.getOrNull()
        }
        return record.copy(
            legs = record.legs.map { leg ->
                leg.copy(
                    reconstruction = leg.reconstruction?.copy(
                        displayPng = relocate(leg.reconstruction.displayPng),
                        linearPng = relocate(leg.reconstruction.linearPng),
                        linearFits = relocate(leg.reconstruction.linearFits)
                    )
                )
            },
            resultDirectory = destinationRoot
        )
    }

    private fun parseTask(text: String, directory: File): SolarScanTaskRecord {
        val json = JSONObject(text)
        require(json.getString("schema") == SCHEMA)
        val config = parseConfiguration(json.getJSONObject("configuration"))
        val analysis = json.optJSONObject("analysis")?.let(::parseAnalysis)
        val settings = parseReconstructionSettings(json.getJSONObject("reconstructionSettings"))
        val legs = json.getJSONArray("legs").let { array ->
            List(array.length()) { index ->
                val leg = array.getJSONObject(index)
                SolarScanLegRecord(
                    number = leg.getInt("number"),
                    direction = SolarScanDirection.valueOf(leg.getString("direction")),
                    source = leg.optJSONObject("source")?.let(::parseIdentity),
                    recordingFrames = leg.getInt("recordingFrames"),
                    droppedFrames = leg.getInt("droppedFrames"),
                    reconstruction = leg.optJSONObject("reconstruction")?.let { value ->
                        SolarReconstructionSummary(
                            source = value.getString("source"),
                            displayPng = value.optNullableString("displayPng"),
                            linearPng = value.optNullableString("linearPng"),
                            linearFits = value.optNullableString("linearFits"),
                            width = value.getInt("width"),
                            height = value.getInt("height"),
                            frameStride = value.getInt("frameStride"),
                            sourceFrames = value.getInt("sourceFrames"),
                            warnings = value.getJSONArray("warnings").toStringList(),
                            corrections = value.optJSONObject("corrections")
                                ?.let(::parseCorrectionReport)
                                ?: SolarCorrectionReport(),
                            integrity = value.optJSONObject("artifactIntegrity")?.let { integrity ->
                                integrity.keys().asSequence().associate { name ->
                                    val product = SolarOutputProduct.valueOf(name)
                                    val artifact = integrity.getJSONObject(name)
                                    product to SolarProductIntegrity(
                                        byteCount = artifact.getLong("byteCount"),
                                        sha256 = artifact.getString("sha256")
                                    )
                                }
                            }.orEmpty(),
                            memory = value.optJSONObject("memory")?.let { memory ->
                                SolarMemorySampleSummary(
                                    headroomBytes = memory.optNullableLong("headroomBytes"),
                                    firstSampledAtUtc = memory.getString("firstSampledAtUtc"),
                                    sampleCount = memory.optInt("sampleCount", 0),
                                    maxIntervalMs = memory.optLong("maxIntervalMs", 0L)
                                )
                            },
                            completedAtUtc = value.getString("completedAtUtc")
                        )
                    },
                    sourceDisposition = SolarSourceDispositionState.valueOf(leg.getString("sourceDisposition")),
                    sourceDispositionCancelled = leg.optBoolean("sourceDispositionCancelled"),
                    sourceMissing = leg.optBoolean("sourceMissing"),
                    failure = leg.optNullableString("failure"),
                    coverage = leg.optJSONObject("coverage")?.let { coverage ->
                        SolarScanCoverageReport(
                            firstContactFraction = coverage.getDouble("firstContactFraction"),
                            lastContactFraction = coverage.getDouble("lastContactFraction"),
                            startBlankArcsec = coverage.getDouble("startBlankArcsec"),
                            endBlankArcsec = coverage.getDouble("endBlankArcsec"),
                            completeDiskCovered = coverage.getBoolean("completeDiskCovered"),
                            accelerationOverlapsDisk = coverage.getBoolean("accelerationOverlapsDisk"),
                            decelerationOverlapsDisk = coverage.getBoolean("decelerationOverlapsDisk"),
                            stableCoverageFraction = coverage.getDouble("stableCoverageFraction")
                        )
                    },
                    analysis = leg.optJSONObject("analysis")?.let(::parseAnalysis),
                    reconstructionSettings = leg.optJSONObject("reconstructionSettings")
                        ?.let(::parseReconstructionSettings)
                )
            }
        }
        val storedRoot = json.optString("resultRoot")
            .takeIf { it.isNotBlank() }
            ?.let(::File)
            ?: directory
        return SolarScanTaskRecord(
            id = json.getString("id"),
            createdAtUtc = json.getString("createdAtUtc"),
            completedAtUtc = json.optNullableString("completedAtUtc"),
            title = json.getString("title"),
            configuration = config,
            analysis = analysis,
            reconstructionSettings = settings,
            legs = legs,
            sourcePolicy = SolarSourcePolicy.valueOf(json.getString("sourcePolicy")),
            warnings = json.getJSONArray("warnings").toStringList(),
            resultDirectory = storedRoot,
            batchId = json.optNullableString("batchId")
        )
    }

    private fun reconstructionSettingsJson(settings: SolarReconstructionSettings): JSONObject =
        JSONObject().apply {
            put("lineShiftPixels", settings.lineShiftPixels)
            put("flipHorizontal", settings.flipHorizontal)
            put("flipVertical", settings.flipVertical)
            put("rotationDegrees", settings.rotationDegrees)
            put("frameStride", settings.frameStride)
            put("products", JSONArray(settings.products.map { it.name }))
            put("scanCorrection", JSONObject().apply {
                put("bandingAxis", settings.scanCorrection.bandingAxis.name)
                settings.scanCorrection.bandingWidthPixels?.let {
                    put("bandingWidthPixels", it)
                }
                put("alignScanRows", settings.scanCorrection.alignScanRows)
                put("correctPeriodicOscillation", settings.scanCorrection.correctPeriodicOscillation)
            })
            put("flat", JSONObject().apply {
                put("synthetic", settings.flat.synthetic)
                put("syntheticStrength", settings.flat.syntheticStrength)
                settings.flat.physicalSourcePath?.let { put("physicalSourcePath", it) }
            })
        }

    private fun parseReconstructionSettings(json: JSONObject): SolarReconstructionSettings {
        val scan = json.optJSONObject("scanCorrection")
        val flat = json.optJSONObject("flat")
        return SolarReconstructionSettings(
            lineShiftPixels = json.getDouble("lineShiftPixels"),
            flipHorizontal = json.getBoolean("flipHorizontal"),
            flipVertical = json.getBoolean("flipVertical"),
            rotationDegrees = json.getInt("rotationDegrees"),
            frameStride = json.getInt("frameStride"),
            scanCorrection = scan?.let {
                SolarScanCorrectionSettings(
                    bandingAxis = runCatching {
                        SolarBandingAxis.valueOf(it.optString("bandingAxis", SolarBandingAxis.OFF.name))
                    }.getOrDefault(SolarBandingAxis.OFF),
                    bandingWidthPixels = it.optInt("bandingWidthPixels", 0).takeIf { width -> width > 0 },
                    alignScanRows = it.optBoolean("alignScanRows", false),
                    correctPeriodicOscillation = it.optBoolean(
                        "correctPeriodicOscillation",
                        false
                    )
                )
            } ?: SolarScanCorrectionSettings(),
            flat = flat?.let {
                SolarFlatSettings(
                    synthetic = it.optBoolean("synthetic", false),
                    syntheticStrength = it.optInt("syntheticStrength", 100),
                    physicalSourcePath = it.optNullableString("physicalSourcePath")
                )
            } ?: SolarFlatSettings(),
            products = json.getJSONArray("products").toStringList()
                .map(SolarOutputProduct::valueOf).toSet()
        )
    }

    private fun correctionReportJson(report: SolarCorrectionReport): JSONObject =
        JSONObject().apply {
            put("geometryApplied", report.geometryApplied)
            put("bandingApplied", report.bandingApplied.name)
            report.bandingWidthPixels?.let { put("bandingWidthPixels", it) }
            put("scanRowsAligned", report.scanRowsAligned)
            report.oscillationPeriodPixels?.let { put("oscillationPeriodPixels", it) }
            report.oscillationAmplitudePixels?.let { put("oscillationAmplitudePixels", it) }
            report.syntheticFlatStrength?.let { put("syntheticFlatStrength", it) }
            put("physicalFlatApplied", report.physicalFlatApplied)
        }

    private fun parseCorrectionReport(json: JSONObject): SolarCorrectionReport =
        SolarCorrectionReport(
            geometryApplied = json.optBoolean("geometryApplied", false),
            bandingApplied = runCatching {
                SolarBandingAxis.valueOf(
                    json.optString("bandingApplied", SolarBandingAxis.OFF.name)
                )
            }.getOrDefault(SolarBandingAxis.OFF),
            bandingWidthPixels = json.optInt("bandingWidthPixels", 0).takeIf { it > 0 },
            scanRowsAligned = json.optBoolean("scanRowsAligned", false),
            oscillationPeriodPixels = json.optNullableDouble("oscillationPeriodPixels"),
            oscillationAmplitudePixels = json.optNullableDouble("oscillationAmplitudePixels"),
            syntheticFlatStrength = json.optInt("syntheticFlatStrength", 0).takeIf { it > 0 },
            physicalFlatApplied = json.optBoolean("physicalFlatApplied", false)
        )

    private fun configurationJson(config: SolarScanConfiguration): JSONObject = JSONObject().apply {
        put("endpointA", endpointJson(config.endpointA))
        put("endpointB", endpointJson(config.endpointB))
        put("scanDurationMs", config.scanDurationMs)
        put("diskCrossingMs", config.diskCrossingMs)
        put("diskDiameterArcsec", config.diskDiameterArcsec)
        put("startBlankArcsec", config.startBlankArcsec)
        put("endBlankArcsec", config.endBlankArcsec)
        put("positionUncertaintyArcsec", config.positionUncertaintyArcsec)
        put("frameRate", config.frameRate)
        put("exposureUs", config.exposureUs)
        put("roi", roiJson(config.roi))
        put("pixelBytes", config.pixelBytes)
        put("legCount", config.legCount)
        put("continuous", config.continuous)
        put("sourcePolicy", config.sourcePolicy.name)
        put("products", JSONArray(config.products.map { it.name }))
    }

    private fun parseConfiguration(json: JSONObject): SolarScanConfiguration =
        SolarScanConfiguration(
            endpointA = parseEndpoint(json.getJSONObject("endpointA")),
            endpointB = parseEndpoint(json.getJSONObject("endpointB")),
            scanDurationMs = json.getLong("scanDurationMs"),
            diskCrossingMs = json.getLong("diskCrossingMs"),
            diskDiameterArcsec = json.getDouble("diskDiameterArcsec"),
            startBlankArcsec = json.getDouble("startBlankArcsec"),
            endBlankArcsec = json.getDouble("endBlankArcsec"),
            positionUncertaintyArcsec = json.getDouble("positionUncertaintyArcsec"),
            frameRate = json.getDouble("frameRate"),
            exposureUs = json.getLong("exposureUs"),
            roi = parseRoi(json.getJSONObject("roi")),
            pixelBytes = json.getInt("pixelBytes"),
            legCount = json.getInt("legCount"),
            continuous = json.getBoolean("continuous"),
            sourcePolicy = SolarSourcePolicy.valueOf(json.getString("sourcePolicy")),
            products = json.getJSONArray("products").toStringList()
                .map(SolarOutputProduct::valueOf).toSet()
        )

    private fun parseAnalysis(json: JSONObject): SolarSpectrumAnalysis {
        val traceJson = json.getJSONObject("trace")
        return SolarSpectrumAnalysis(
            trace = SolarTrace(
                width = traceJson.getInt("width"),
                constantRow = traceJson.getDouble("constantRow"),
                linear = traceJson.getDouble("linear"),
                quadratic = traceJson.getDouble("quadratic"),
                illuminatedFirst = traceJson.getInt("illuminatedFirst"),
                illuminatedLast = traceJson.getInt("illuminatedLast")
            ),
            confidence = json.getDouble("confidence"),
            lineDepth = json.getDouble("lineDepth"),
            suggestedRoi = parseRoi(json.getJSONObject("suggestedRoi")),
            validColumns = json.getInt("validColumns"),
            residualPixels = json.getDouble("residualPixels")
        )
    }

    private fun analysisJson(analysis: SolarSpectrumAnalysis): JSONObject = JSONObject().apply {
        put("confidence", analysis.confidence)
        put("lineDepth", analysis.lineDepth)
        put("validColumns", analysis.validColumns)
        put("residualPixels", analysis.residualPixels)
        put("trace", JSONObject().apply {
            put("width", analysis.trace.width)
            put("constantRow", analysis.trace.constantRow)
            put("linear", analysis.trace.linear)
            put("quadratic", analysis.trace.quadratic)
            put("illuminatedFirst", analysis.trace.illuminatedFirst)
            put("illuminatedLast", analysis.trace.illuminatedLast)
        })
        put("suggestedRoi", roiJson(analysis.suggestedRoi))
    }

    private fun endpointJson(endpoint: SolarScanEndpoint) = JSONObject().apply {
        put("raHours", endpoint.raHours)
        put("decDegrees", endpoint.decDegrees)
    }

    private fun parseEndpoint(json: JSONObject) = SolarScanEndpoint(
        json.getDouble("raHours"),
        json.getDouble("decDegrees")
    )

    private fun roiJson(roi: SolarRoi) = JSONObject().apply {
        put("x", roi.x)
        put("y", roi.y)
        put("width", roi.width)
        put("height", roi.height)
    }

    private fun parseRoi(json: JSONObject) =
        SolarRoi(json.getInt("x"), json.getInt("y"), json.getInt("width"), json.getInt("height"))

    private fun identityJson(identity: SolarSourceIdentity) = JSONObject().apply {
        put("fileName", identity.fileName)
        put("size", identity.size)
        put("modifiedUtcMs", identity.modifiedUtcMs)
        put("digest", identity.digest)
    }

    private fun parseIdentity(json: JSONObject) = SolarSourceIdentity(
        json.getString("fileName"),
        json.getLong("size"),
        json.getLong("modifiedUtcMs"),
        json.getString("digest")
    )

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else getDouble(key)

    private fun JSONArray.toStringList(): List<String> =
        List(length()) { getString(it) }

    companion object {
        private val logger = Logger.getLogger(SolarScanRepository::class.java.name)
        const val MANIFEST_NAME = "solar-scan.json"
        const val SCHEMA = "indigo-solar-scan-1"
        internal const val DISPOSITION_CANCELLED = "source_disposition:cancelled"
    }
}
