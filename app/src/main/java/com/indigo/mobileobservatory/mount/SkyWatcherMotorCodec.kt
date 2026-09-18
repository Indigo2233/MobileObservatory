package com.indigo.mobileobservatory.mount

import java.util.Locale

internal enum class SkyWatcherAxis(val id: Int) {
    RA(1),
    DEC(2)
}

internal enum class SkyWatcherMotorError(val code: Int, val label: String) {
    UNKNOWN_COMMAND(0, "Unknown command"),
    COMMAND_LENGTH(1, "Command length error"),
    MOTOR_NOT_STOPPED(2, "Motor not stopped"),
    INVALID_CHARACTER(3, "Invalid character"),
    NOT_INITIALIZED(4, "Motor not initialized"),
    DRIVER_SLEEPING(5, "Motor driver sleeping"),
    PEC_TRAINING(7, "PEC training is running"),
    NO_PEC_DATA(8, "No valid PEC data");

    companion object {
        fun fromCode(code: Int): SkyWatcherMotorError? = entries.firstOrNull { it.code == code }
    }
}

/**
 * Sky-Watcher motor-controller framing used by Wi-Fi modules on UDP 11880.
 *
 * 24-bit values are sent as little-endian hex pairs: 0x123456 → "563412".
 */
internal object SkyWatcherMotorCodec {
    const val POSITION_OFFSET = 0x800000L
    const val STATUS_ACTIVE_MASK = 0x010
    const val STATUS_NOT_INITIALIZED = 0x100

    fun command(cmd: Char, axis: SkyWatcherAxis, data: Long? = null, digits: Int = 6): ByteArray {
        val payload = if (data == null) "" else intToHex(data, digits)
        return ":$cmd${axis.id}$payload\r".toByteArray(Charsets.US_ASCII)
    }

    fun command(cmd: Char, axis: SkyWatcherAxis, mode: Char, directionDigit: Char): ByteArray {
        return ":$cmd${axis.id}$mode$directionDigit\r".toByteArray(Charsets.US_ASCII)
    }

    fun instantStop(axis: SkyWatcherAxis): ByteArray = command('L', axis)

    fun parseReply(raw: ByteArray): String {
        val text = raw.toString(Charsets.US_ASCII).trimEnd('\r', '\n', '\u0000')
        require(text.isNotEmpty()) { "Empty Sky-Watcher motor response." }
        when (text.first()) {
            '=' -> return text.substring(1)
            '!' -> {
                val code = runCatching { hexToInt(text.substring(1)).toInt() }.getOrDefault(-1)
                val error = SkyWatcherMotorError.fromCode(code)
                error(error?.label ?: "Sky-Watcher motor error $text")
            }
            else -> error("Unexpected Sky-Watcher motor response: $text")
        }
    }

    fun isMotorReply(raw: ByteArray): Boolean {
        val text = raw.toString(Charsets.US_ASCII).trimStart()
        return text.startsWith("=") || text.startsWith("!")
    }

    fun intToHex(data: Long, digits: Int): String {
        require(digits in listOf(0, 1, 2, 4, 6)) { "Unsupported SynScan hex width $digits." }
        if (digits == 0) return ""
        val hex = data.toString(16).uppercase(Locale.US).padStart(digits, '0')
        return hex.chunked(2).asReversed().joinToString("")
    }

    fun hexToInt(data: String): Long {
        val cleaned = data.trim()
        if (cleaned.isEmpty()) return 0L
        if (cleaned.length == 3) return cleaned.toLong(16)
        require(cleaned.length <= 6) { "SynScan value too long: $cleaned" }
        val hex = if (cleaned.length % 2 == 0) {
            cleaned.chunked(2).asReversed().joinToString("")
        } else {
            cleaned
        }
        return hex.toLong(16)
    }

    fun isAxisActive(status: Long): Boolean = (status and STATUS_ACTIVE_MASK.toLong()) != 0L

    fun needsInitialize(status: Long): Boolean =
        status == 0L || status == STATUS_NOT_INITIALIZED.toLong()
}
