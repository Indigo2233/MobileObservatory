package com.indigo.mobileobservatory.accessories.zwo

object ZwoEafUsbIds {
    const val vendorId = 0x03C3
    const val productId = 0x1F10

    fun isEaf(vendorId: Int, productId: Int): Boolean =
        vendorId == this.vendorId && productId == this.productId
}

data class ZwoEafIdentity(
    val model: String,
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val firmwarePatch: Int,
    val serial: String
) {
    val firmware: String
        get() = "$firmwareMajor.$firmwareMinor.$firmwarePatch"
}

data class ZwoEafStatus(
    val position: Int,
    val moving: Boolean,
    val maxStep: Int,
    val backlash: Int,
    val beep: Boolean,
    val reverse: Boolean,
    val handControllerActive: Boolean,
    val temperatureC: Float?,
    val errorCode: Int
)

/**
 * Packet codec for the 16-byte HID reports used by the ZWO EAFN firmware.
 * Report ID 3 is written and report ID 1 is read.
 */
object ZwoEafProtocol {
    const val reportLength = 16
    const val outputReportId = 3
    const val inputReportId = 1
    const val selectorStatus = 0x03
    const val selectorIdentity = 0x04
    const val selectorSerial = 0x0C
    const val maxTravel = 600_000
    const val defaultFineStep = 10
    const val defaultCoarseStep = 50

    fun query(selector: Int): ByteArray {
        require(selector in 0..0xFF)
        return ByteArray(reportLength).also {
            it[0] = outputReportId.toByte()
            it[1] = 0x7E
            it[2] = 0x5A
            it[3] = 0x02
            it[4] = selector.toByte()
        }
    }

    fun parseIdentity(identityReport: ByteArray, serialReport: ByteArray): ZwoEafIdentity? {
        if (!isReply(identityReport, selectorIdentity) ||
            !isReply(serialReport, selectorSerial)
        ) return null
        val model = identityReport.copyOfRange(8, 16)
            .takeWhile { it != 0.toByte() }
            .toByteArray()
            .decodeToString()
            .trim()
        val serial = serialReport.copyOfRange(4, 12)
            .joinToString(separator = "") { "%02x".format(it.toInt() and 0xFF) }
        if (model.isBlank() || serial.all { it == '0' }) return null
        return ZwoEafIdentity(
            model = model,
            firmwareMajor = identityReport.u8(4),
            firmwareMinor = identityReport.u8(5),
            firmwarePatch = identityReport.u8(6),
            serial = serial
        )
    }

    fun supportsVerifiedProtocol(identity: ZwoEafIdentity): Boolean {
        val firmwareNumber = identity.firmwareMajor * 10_000 +
            identity.firmwareMinor * 100 + identity.firmwarePatch
        return identity.model == "EAFN" && firmwareNumber >= 30_306
    }

    fun parseStatus(report: ByteArray): ZwoEafStatus? {
        if (!isReply(report, selectorStatus)) return null
        val position = u24(report[7], report[8], report[9])
        val maxStep = (report.u8(6) shl 16) or
            (report.u8(14) shl 8) or report.u8(15)
        if (position !in 0..maxTravel || maxStep !in 1..maxTravel) return null
        val flags = report.u8(13)
        val handControllerActive = flags and 0x04 != 0
        val rawTemperature = (report.u8(11) shl 8) or report.u8(12)
        val temperature = rawTemperature / 100f - 300f
        return ZwoEafStatus(
            position = position,
            moving = report.u8(4) == 1,
            maxStep = maxStep,
            backlash = report.u8(5),
            beep = flags and 0x01 != 0,
            reverse = flags and 0x02 != 0,
            handControllerActive = handControllerActive,
            temperatureC = temperature.takeIf { it > -273f && !handControllerActive },
            errorCode = if (report.u8(4) > 1) report.u8(4) else flags ushr 4
        )
    }

    fun moveCommand(status: ZwoEafStatus, target: Int): ByteArray {
        require(!status.moving && !status.handControllerActive && status.errorCode == 0)
        require(target in 0..status.maxStep)
        return stateCommand(status).also {
            it[4] = 1
            putU24(it, 7, target)
        }
    }

    fun stopCommand(status: ZwoEafStatus): ByteArray = stateCommand(status)

    fun resetPositionCommand(status: ZwoEafStatus, position: Int): ByteArray {
        require(!status.moving && !status.handControllerActive && status.errorCode == 0)
        require(position in 0..status.maxStep)
        return stateCommand(status.copy(position = position)).also { it[10] = 1 }
    }

    fun settingsCommand(
        status: ZwoEafStatus,
        reverse: Boolean = status.reverse,
        backlash: Int = status.backlash,
        maxStep: Int = status.maxStep
    ): ByteArray {
        require(!status.moving && !status.handControllerActive && status.errorCode == 0)
        require(backlash in 0..0xFF)
        require(maxStep in 1..maxTravel && maxStep >= status.position)
        return stateCommand(
            status.copy(reverse = reverse, backlash = backlash, maxStep = maxStep)
        ).also {
            if (maxStep != status.maxStep) it[10] = 2
        }
    }

    fun isReply(report: ByteArray, selector: Int): Boolean =
        report.size >= reportLength &&
            report.u8(0) == inputReportId &&
            report.u8(1) == 0x7E &&
            report.u8(2) == 0x5A &&
            report.u8(3) == selector

    private fun stateCommand(status: ZwoEafStatus): ByteArray = ByteArray(reportLength).also {
        it[0] = outputReportId.toByte()
        it[1] = 0x7E
        it[2] = 0x5A
        it[3] = selectorStatus.toByte()
        it[5] = status.backlash.toByte()
        it[6] = (status.maxStep ushr 16).toByte()
        putU24(it, 7, status.position)
        it[13] = ((if (status.beep) 1 else 0) or
            (if (status.reverse) 2 else 0)).toByte()
        it[14] = (status.maxStep ushr 8).toByte()
        it[15] = status.maxStep.toByte()
    }

    private fun putU24(target: ByteArray, offset: Int, value: Int) {
        target[offset] = (value ushr 16).toByte()
        target[offset + 1] = (value ushr 8).toByte()
        target[offset + 2] = value.toByte()
    }

    private fun u24(a: Byte, b: Byte, c: Byte): Int =
        ((a.toInt() and 0xFF) shl 16) or
            ((b.toInt() and 0xFF) shl 8) or
            (c.toInt() and 0xFF)

    private fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF
}
