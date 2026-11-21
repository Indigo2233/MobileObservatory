package com.indigo.mobileobservatory.recording

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.zip.CRC32

/** Edits a finished recording without decoding or re-encoding any retained sample. */
internal object SerFrameEditor {
    private const val HEADER_BYTES = 178
    private const val FRAME_COUNT_OFFSET = 38
    private const val TIMESTAMP_BYTES = 8L

    data class Layout(
        val frames: Long,
        val frameBytes: Long,
        val timestampOffset: Long?,
        val size: Long,
        val modified: Long,
        val headerChecksum: Long
    )

    fun readLayout(file: File): Layout = RandomAccessFile(file, "r").use { input ->
        val bytes = ByteArray(HEADER_BYTES)
        input.readFully(bytes)
        val signature = String(bytes, 0, 14, Charsets.US_ASCII).trimEnd('\u0000')
        val packed = signature == "PSER-RECORDER"
        require(packed || signature == "LUCAM-RECORDER") { "frame_edit_invalid_ser" }
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val color = header.getInt(18)
        val order = header.getInt(22)
        val width = header.getInt(26)
        val height = header.getInt(30)
        val bits = header.getInt(34)
        val frames = header.getInt(FRAME_COUNT_OFFSET).toLong()
        require(color in 0..101 && order in 0..1 && width > 0 && height > 0 && frames > 0 && bits in 1..16) {
            "frame_edit_invalid_ser"
        }
        require(!packed || color < 100 && bits in listOf(8, 10, 12, 14, 16)) { "frame_edit_invalid_ser" }
        val pixels = width.toLong() * height
        val frameBytes = if (packed) Math.addExact(Math.multiplyExact(pixels, bits.toLong()), 7) / 8
            else Math.multiplyExact(pixels, (if (color >= 100) 3L else 1L) * (if (bits > 8) 2 else 1))
        val end = Math.addExact(HEADER_BYTES.toLong(), Math.multiplyExact(frameBytes, frames))
        val size = input.length()
        val trailing = size - end
        require(trailing == 0L || trailing == frames * TIMESTAMP_BYTES) { "frame_edit_invalid_ser" }
        Layout(frames, frameBytes, end.takeIf { trailing != 0L }, size, file.lastModified(),
            CRC32().apply { update(bytes) }.value)
    }

    @Synchronized
    fun removeExcluded(
        file: File,
        expected: Layout,
        selection: RecordingFrameSelection,
        sourceBusy: () -> Boolean = { false },
        checkCancelled: () -> Unit = {},
        progress: (copiedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ) {
        require(selection.total == expected.frames && selection.count in 1 until expected.frames) { "stack_no_kept_frames" }
        check(!sourceBusy()) { "recording_active" }
        check(readLayout(file) == expected) { "source_changed" }
        val parent = checkNotNull(file.absoluteFile.parentFile)
        val total = HEADER_BYTES + selection.count * (expected.frameBytes + if (expected.timestampOffset != null) TIMESTAMP_BYTES else 0)
        check(parent.usableSpace >= total) { "storage_full" }
        val temporary = File(parent, ".frame-edit-${UUID.randomUUID()}.partial")
        try {
            RandomAccessFile(file, "r").use { input ->
                FileOutputStream(temporary).use { output ->
                    val header = ByteArray(HEADER_BYTES)
                    input.readFully(header)
                    // Capture provenance fingerprints ignore this field. All other header bytes stay intact.
                    ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                        .putInt(FRAME_COUNT_OFFSET, selection.count.toInt())
                    output.write(header)
                    var copied = HEADER_BYTES.toLong()
                    val buffer = ByteArray(256 * 1024)
                    fun copyRanges(base: Long, unit: Long) {
                        for (range in selection.kept) {
                            input.seek(base + range.first * unit)
                            var remaining = (range.last - range.first + 1) * unit
                            while (remaining > 0) {
                                checkCancelled()
                                val length = minOf(remaining, buffer.size.toLong()).toInt()
                                input.readFully(buffer, 0, length)
                                output.write(buffer, 0, length)
                                remaining -= length
                                copied += length
                                progress(copied, total)
                            }
                        }
                    }
                    copyRanges(HEADER_BYTES.toLong(), expected.frameBytes)
                    expected.timestampOffset?.let { copyRanges(it, TIMESTAMP_BYTES) }
                    output.fd.sync()
                }
            }
            checkCancelled()
            val actual = readLayout(temporary)
            check(actual.frames == selection.count && actual.frameBytes == expected.frameBytes &&
                actual.size == total) { "frame_edit_invalid_ser" }
            check(!sourceBusy()) { "recording_active" }
            check(readLayout(file) == expected) { "source_changed" }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            temporary.delete()
        }
    }
}
