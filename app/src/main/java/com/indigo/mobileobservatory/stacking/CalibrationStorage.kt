package com.indigo.mobileobservatory.stacking

import org.json.JSONObject
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID

internal object CalibrationJson {
    fun capture(c: CalibrationCapture): JSONObject = JSONObject().apply {
        put("camera", c.camera ?: JSONObject.NULL); put("exposureUs", c.exposureUs ?: JSONObject.NULL)
        put("gain", c.gain ?: JSONObject.NULL); put("offset", c.offset ?: JSONObject.NULL)
        put("roiX", c.roiX ?: JSONObject.NULL); put("roiY", c.roiY ?: JSONObject.NULL)
        put("hardwareBin", c.hardwareBin ?: JSONObject.NULL); put("filter", c.filter ?: JSONObject.NULL)
        put("readout", c.readout ?: JSONObject.NULL); put("temperatureC", c.temperatureC ?: JSONObject.NULL)
    }
    fun parseCapture(j: JSONObject): CalibrationCapture {
        fun text(k: String) = if (j.isNull(k) || !j.has(k)) null else j.getString(k)
        fun number(k: String) = if (j.isNull(k) || !j.has(k)) null else j.getDouble(k)
        fun integer(k: String) = if (j.isNull(k) || !j.has(k)) null else j.getInt(k)
        return CalibrationCapture(text("camera"), number("exposureUs"), number("gain"), number("offset"),
            integer("roiX"), integer("roiY"), integer("hardwareBin"), text("filter"), text("readout"), number("temperatureC"))
    }
    fun descriptor(d: CalibrationDescriptor): JSONObject = JSONObject().apply {
        put("schema", 1); put("id", d.id); put("name", d.name); put("type", d.type.name)
        put("geometry", JSONObject().apply { put("width", d.geometry.width); put("height", d.geometry.height)
            put("layout", d.geometry.layout); put("validBits", d.geometry.validBits) })
        put("capture", capture(d.capture)); put("sourceIdentity", d.sourceIdentity)
        put("frameCount", d.frameCount); put("createdAtMillis", d.createdAtMillis)
        put("biasId", d.biasId ?: JSONObject.NULL)
        put("samples", if (d.type == CalibrationType.FLAT) "normalized_response" else "normalized_sensor_mean")
        put("combine", "streaming_arithmetic_mean_no_rejection")
    }
    fun parseDescriptor(j: JSONObject): CalibrationDescriptor {
        require(j.getInt("schema") == 1) { "calibration_schema" }
        val g = j.getJSONObject("geometry")
        val type = CalibrationType.valueOf(j.getString("type"))
        require(j.getString("samples") == if (type == CalibrationType.FLAT) "normalized_response" else "normalized_sensor_mean") {
            "calibration_sample_domain"
        }
        return CalibrationDescriptor(j.getString("id"), j.getString("name"), type,
            CalibrationGeometry(g.getInt("width"), g.getInt("height"), g.getString("layout"), g.getInt("validBits")),
            parseCapture(j.getJSONObject("capture")), j.getString("sourceIdentity"), j.getLong("frameCount"),
            j.getLong("createdAtMillis"), if (j.isNull("biasId")) null else j.getString("biasId"))
    }
}

/** App-private, versioned Float32 masters with whole-file SHA-256 integrity. List reads headers
 * only. Save is atomic and never overwrites a master; cancellation cleans every partial output. */
class CalibrationLibrary(private val directory: File) {
    private val magic = "INDIGOCAL1".toByteArray(Charsets.US_ASCII)
    private val maximumMetadata = 64 * 1024
    private fun file(id: String): File {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,80}"))) { "calibration_id" }
        return File(directory, "$id.icm")
    }

    fun list(): List<CalibrationDescriptor> = directory.listFiles().orEmpty().filter { it.isFile && it.extension == "icm" }
        .mapNotNull { file -> runCatching { DataInputStream(BufferedInputStream(file.inputStream())).use { input ->
            readHeader(input, file.length()).also { require(file.name == "${it.id}.icm") }
        } }.getOrNull() }.sortedByDescending { it.createdAtMillis }

    fun save(master: CalibrationMaster, cancelCheck: () -> Unit = {}): CalibrationDescriptor {
        val target = file(master.descriptor.id)
        require(!target.exists()) { "calibration_already_exists" }
        check(directory.isDirectory || directory.mkdirs()) { "calibration_storage" }
        val json = CalibrationJson.descriptor(master.descriptor).toString().toByteArray(Charsets.UTF_8)
        require(json.size <= maximumMetadata) { "calibration_metadata_size" }
        check(directory.usableSpace > master.memoryBytes + json.size + 1024 * 1024) { "storage_full" }
        val temporary = File(directory, ".${master.descriptor.id}-${UUID.randomUUID()}.partial")
        try {
            cancelCheck()
            FileOutputStream(temporary).use { fileOutput ->
                val digest = MessageDigest.getInstance("SHA-256")
                val hashing = DigestOutputStream(BufferedOutputStream(fileOutput, 65536), digest)
                val out = DataOutputStream(hashing)
                out.write(magic); out.writeInt(json.size); out.write(json)
                val buffer = ByteBuffer.allocate(64 * 1024).order(ByteOrder.BIG_ENDIAN)
                var index = 0
                while (index < master.descriptor.geometry.sampleCount) {
                    cancelCheck(); buffer.clear()
                    while (buffer.remaining() >= 4 && index < master.descriptor.geometry.sampleCount) buffer.putFloat(master.sample(index++))
                    out.write(buffer.array(), 0, buffer.position())
                }
                out.flush()
                val checksum = digest.digest(); hashing.on(false); out.write(checksum); out.flush()
                fileOutput.fd.sync()
            }
            cancelCheck()
            check(!target.exists() && temporary.renameTo(target)) { "calibration_storage" }
            return master.descriptor
        } finally { temporary.delete() }
    }

    fun load(id: String, cancelCheck: () -> Unit = {}): CalibrationMaster {
        val source = file(id)
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(BufferedInputStream(source.inputStream(), 65536), digest).use { hashing ->
            val input = DataInputStream(hashing)
            val descriptor = readHeader(input, source.length())
            require(descriptor.id == id) { "calibration_id_mismatch" }
            cancelCheck()
            val samples = FloatArray(descriptor.geometry.sampleCount)
            val bytes = ByteArray(64 * 1024)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
            var index = 0
            while (index < samples.size) {
                cancelCheck()
                val count = minOf(bytes.size / 4, samples.size - index)
                input.readFully(bytes, 0, count * 4); buffer.rewind()
                repeat(count) { samples[index++] = buffer.float }
            }
            val checksum = digest.digest(); hashing.on(false)
            val expected = ByteArray(32); input.readFully(expected)
            require(MessageDigest.isEqual(checksum, expected) && input.read() == -1) { "calibration_corrupt_master" }
            cancelCheck()
            return CalibrationMaster(descriptor, samples)
        }
    }

    fun delete(id: String) { val file = file(id); check(!file.exists() || file.delete()) { "calibration_storage" } }

    private fun readHeader(input: DataInputStream, length: Long): CalibrationDescriptor {
        val signature = ByteArray(magic.size); input.readFully(signature)
        require(signature.contentEquals(magic)) { "calibration_file_format" }
        val size = input.readInt()
        require(size in 1..maximumMetadata) { "calibration_metadata_size" }
        val bytes = ByteArray(size); input.readFully(bytes)
        val descriptor = CalibrationJson.parseDescriptor(JSONObject(String(bytes, Charsets.UTF_8)))
        require(length == magic.size + 4L + size + descriptor.geometry.sampleCount * 4L + 32L) { "calibration_truncated_master" }
        return descriptor
    }
}

/** Capture provenance for newly recorded SER/PSER. The SER header itself has no exposure/gain.
 * Header fingerprint ignores changing frame count but includes acquisition date/CFA/depth.
 * A present invalid/stale context throws: user confirmation cannot bless mixed capture settings. */
object CalibrationRecordingContext {
    private fun sidecar(file: File) = File(file.parentFile, "${file.name}.capture.json")
    fun writeForRecording(file: File, key: StackSessionKey, temperatureC: Double? = null) {
        val json = JSONObject().apply {
            put("schema", 1); put("valid", true); put("headerIdentity", headerIdentity(file))
            put("capture", CalibrationJson.capture(CalibrationCapture.from(key, temperatureC)))
        }
        write(file, json)
    }
    fun readForRecording(file: File): CalibrationCapture? {
        val source = sidecar(file)
        if (!source.exists()) return null
        require(source.isFile && source.length() in 1..65536L) { "calibration_capture_changed" }
        try {
            val json = JSONObject(source.readText(Charsets.UTF_8))
            require(json.getInt("schema") == 1 && json.getBoolean("valid") && json.getString("headerIdentity") == headerIdentity(file)) {
                "calibration_capture_changed"
            }
            return CalibrationJson.parseCapture(json.getJSONObject("capture"))
        } catch (error: Exception) { throw IllegalArgumentException("calibration_capture_changed", error) }
    }
    fun invalidate(file: File, reason: String) {
        try {
            write(file, JSONObject().apply { put("schema", 1); put("valid", false); put("reason", reason.take(1024)) })
        } catch (failure: Exception) {
            // On a full volume, creating the temporary file can fail while the previous context
            // still says valid=true. Truncation needs no extra blocks and forces an explicit
            // read error; deleting the sidecar would incorrectly make the sequence "unknown".
            try { FileOutputStream(sidecar(file)).use { it.fd.sync() } }
            catch (fallback: Exception) { failure.addSuppressed(fallback) }
            throw failure
        }
    }
    private fun write(file: File, json: JSONObject) {
        val target = sidecar(file)
        val temporary = File(target.parentFile, ".${target.name}-${UUID.randomUUID()}.partial")
        try {
            FileOutputStream(temporary).use { output ->
                output.write(json.toString().toByteArray(Charsets.UTF_8)); output.fd.sync()
            }
            // Atomic replacement on Android/Linux; no window in which mixed data looks unknown.
            java.nio.file.Files.move(temporary.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
    private fun headerIdentity(file: File): String {
        val bytes = ByteArray(178)
        file.inputStream().use { DataInputStream(it).readFully(bytes) }
        val signature = String(bytes, 0, 14, Charsets.US_ASCII).trimEnd('\u0000')
        require(signature == "LUCAM-RECORDER" || signature == "PSER-RECORDER") { "calibration_source_format" }
        for (i in 38..41) bytes[i] = 0
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
