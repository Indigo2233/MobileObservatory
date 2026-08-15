package com.indigo.mobileobservatory.camera.dslr

internal fun ptpU8(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF

internal fun ptpU16(data: ByteArray, offset: Int): Int =
    ptpU8(data, offset) or (ptpU8(data, offset + 1) shl 8)

internal fun ptpU32(data: ByteArray, offset: Int): Long =
    ptpU16(data, offset).toLong() or (ptpU16(data, offset + 2).toLong() shl 16)

internal fun ptpPutU16(data: ByteArray, offset: Int, value: Int) {
    data[offset] = (value and 0xFF).toByte()
    data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

internal fun ptpPutU32(data: ByteArray, offset: Int, value: Long) {
    ptpPutU16(data, offset, (value and 0xFFFF).toInt())
    ptpPutU16(data, offset + 2, ((value ushr 16) and 0xFFFF).toInt())
}

class PtpReader(private val data: ByteArray, start: Int = 0) {
    var offset: Int = start
        private set

    fun remaining(): Int = data.size - offset

    fun u8(): Int {
        require(remaining() >= 1) { "PTP buffer underrun (u8)" }
        return ptpU8(data, offset).also { offset += 1 }
    }

    fun u16(): Int {
        require(remaining() >= 2) { "PTP buffer underrun (u16)" }
        return ptpU16(data, offset).also { offset += 2 }
    }

    fun u32(): Long {
        require(remaining() >= 4) { "PTP buffer underrun (u32)" }
        return ptpU32(data, offset).also { offset += 4 }
    }

    fun bytes(count: Int): ByteArray {
        require(count >= 0 && remaining() >= count) { "PTP buffer underrun ($count bytes)" }
        return data.copyOfRange(offset, offset + count).also { offset += count }
    }

    fun ptpString(): String {
        if (remaining() < 1) return ""
        val units = u8()
        if (units == 0) return ""
        val raw = bytes(units * 2)
        val chars = CharArray(units) { index ->
            (ptpU16(raw, index * 2) and 0xFFFF).toChar()
        }
        val terminator = chars.indexOf('\u0000').let { if (it < 0) chars.size else it }
        return String(chars, 0, terminator)
    }

    fun u16Array(): List<Int> {
        val count = u32().toInt().coerceAtLeast(0)
        return List(count) { u16() }
    }
}

class PtpWriter {
    private val bytes = ArrayList<Byte>(64)

    fun u8(value: Int) {
        bytes.add((value and 0xFF).toByte())
    }

    fun u16(value: Int) {
        u8(value)
        u8(value ushr 8)
    }

    fun u32(value: Long) {
        u16(value.toInt())
        u16((value ushr 16).toInt())
    }

    fun ptpString(value: String) {
        if (value.isEmpty()) {
            u8(0)
            return
        }
        val units = value.length + 1
        u8(units)
        for (ch in value) u16(ch.code)
        u16(0)
    }

    fun u16Array(values: List<Int>) {
        u32(values.size.toLong())
        values.forEach { u16(it) }
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
}

data class PtpContainer(
    val type: Int,
    val code: Int,
    val transactionId: Int,
    val payload: ByteArray = ByteArray(0)
) {
    fun toBytes(): ByteArray {
        val length = PtpConstants.HEADER_SIZE + payload.size
        val out = ByteArray(length)
        ptpPutU32(out, 0, length.toLong())
        ptpPutU16(out, 4, type)
        ptpPutU16(out, 6, code)
        ptpPutU32(out, 8, transactionId.toLong() and 0xFFFFFFFFL)
        if (payload.isNotEmpty()) payload.copyInto(out, PtpConstants.HEADER_SIZE)
        return out
    }

    companion object {
        fun command(code: Int, transactionId: Int, params: LongArray = longArrayOf()): PtpContainer {
            val payload = ByteArray(params.size * 4)
            params.forEachIndexed { index, param -> ptpPutU32(payload, index * 4, param and 0xFFFFFFFFL) }
            return PtpContainer(PtpConstants.CONTAINER_COMMAND, code, transactionId, payload)
        }

        fun data(code: Int, transactionId: Int, payload: ByteArray): PtpContainer =
            PtpContainer(PtpConstants.CONTAINER_DATA, code, transactionId, payload)

        fun response(code: Int, transactionId: Int, params: LongArray = longArrayOf()): PtpContainer {
            val payload = ByteArray(params.size * 4)
            params.forEachIndexed { index, param -> ptpPutU32(payload, index * 4, param and 0xFFFFFFFFL) }
            return PtpContainer(PtpConstants.CONTAINER_RESPONSE, code, transactionId, payload)
        }

        fun parse(bytes: ByteArray, size: Int = bytes.size): PtpContainer {
            require(size >= PtpConstants.HEADER_SIZE) { "PTP container shorter than header: $size" }
            val length = ptpU32(bytes, 0).toInt()
            require(length >= PtpConstants.HEADER_SIZE) { "PTP container length $length" }
            val usable = minOf(size, bytes.size, length)
            val payload = if (usable > PtpConstants.HEADER_SIZE) {
                bytes.copyOfRange(PtpConstants.HEADER_SIZE, usable)
            } else {
                ByteArray(0)
            }
            return PtpContainer(
                type = ptpU16(bytes, 4),
                code = ptpU16(bytes, 6),
                transactionId = ptpU32(bytes, 8).toInt(),
                payload = payload
            )
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PtpContainer) return false
        return type == other.type &&
            code == other.code &&
            transactionId == other.transactionId &&
            payload.contentEquals(other.payload)
    }

    override fun hashCode(): Int {
        var result = type
        result = 31 * result + code
        result = 31 * result + transactionId
        result = 31 * result + payload.contentHashCode()
        return result
    }
}
