package com.indigo.mobileobservatory.accessories.oasis

import kotlin.math.ln

object OasisUsbIds {
    const val vendorId = 0x338F
    const val focuserProductId = 0xA0F0
    const val filterWheelProductId = 0x0FE0

    fun isFocuser(productId: Int) = productId == focuserProductId
    fun isFilterWheel(productId: Int) = productId == filterWheelProductId
}

data class OasisHidMessage(
    val command: Int,
    val payload: ByteArray
)

object OasisHidProtocol {
    const val reportLength = 65
    private const val maxPayloadLength = reportLength - 3

    fun encode(command: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        require(command in 0..0xFF)
        require(payload.size <= maxPayloadLength)
        return ByteArray(reportLength).also { report ->
            report[1] = command.toByte()
            report[2] = payload.size.toByte()
            payload.copyInto(report, destinationOffset = 3)
        }
    }

    fun endpointReport(report: ByteArray, maxPacketSize: Int): ByteArray {
        return if (report.size == maxPacketSize + 1 && report.firstOrNull() == 0.toByte()) {
            report.copyOfRange(1, report.size)
        } else {
            report
        }
    }

    fun decode(report: ByteArray, length: Int = report.size): OasisHidMessage? {
        if (length < 2) return null
        val hasReportId = report[0].toInt() and 0xFF == 0
        val headerOffset = if (hasReportId) 1 else 0
        if (length < headerOffset + 2) return null
        val command = report[headerOffset].toInt() and 0xFF
        val payloadLength = report[headerOffset + 1].toInt() and 0xFF
        if (payloadLength > length - headerOffset - 2) return null
        return OasisHidMessage(
            command = command,
            payload = report.copyOfRange(headerOffset + 2, headerOffset + 2 + payloadLength)
        )
    }

    fun int32(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte()
    )

    fun int32(payload: ByteArray, offset: Int): Int? {
        if (offset < 0 || offset + 4 > payload.size) return null
        return ((payload[offset].toInt() and 0xFF) shl 24) or
            ((payload[offset + 1].toInt() and 0xFF) shl 16) or
            ((payload[offset + 2].toInt() and 0xFF) shl 8) or
            (payload[offset + 3].toInt() and 0xFF)
    }

    fun uint8(payload: ByteArray, offset: Int): Int? =
        payload.getOrNull(offset)?.toInt()?.and(0xFF)
}

enum class OasisFocuserGeneration {
    FIRST,
    ROSE
}

data class OasisFocuserStatus(
    val position: Int,
    val moving: Boolean,
    val temperatureC: Float?
)

data class OasisFocuserConfig(
    val maxStep: Int,
    val backlash: Int,
    val speed: Int,
    val reverseDirection: Int,
    val backlashDirection: Int,
    val beepOnMove: Int,
    val beepOnStartup: Int,
    val bluetoothOn: Int
)

object OasisFocuserProtocol {
    const val defaultFineStep = 10
    const val defaultCoarseStep = 50

    const val commandSetZero = 0x34
    const val commandMoveRelative = 0x35
    const val commandMoveTo = 0x36
    const val commandStop = 0x37
    const val commandSync = 0x38
    const val commandGetConfigFirst = 0x30
    const val commandSetConfigFirst = 0x31
    const val commandGetStatusFirst = 0x32
    const val commandGetConfigRose = 0x3A
    const val commandSetConfigRose = 0x3B
    const val commandGetStatusRose = 0x3C

    const val maskMaxStep = 1 shl 0
    const val maskBacklash = 1 shl 1
    const val maskSpeed = 1 shl 2
    const val maskReverseDirection = 1 shl 3
    const val maskBacklashDirection = 1 shl 4

    fun statusCommand(generation: OasisFocuserGeneration): Int = when (generation) {
        OasisFocuserGeneration.FIRST -> commandGetStatusFirst
        OasisFocuserGeneration.ROSE -> commandGetStatusRose
    }

    fun configCommand(generation: OasisFocuserGeneration): Int = when (generation) {
        OasisFocuserGeneration.FIRST -> commandGetConfigFirst
        OasisFocuserGeneration.ROSE -> commandGetConfigRose
    }

    fun setConfigCommand(generation: OasisFocuserGeneration): Int = when (generation) {
        OasisFocuserGeneration.FIRST -> commandSetConfigFirst
        OasisFocuserGeneration.ROSE -> commandSetConfigRose
    }

    fun relativeMovePayload(steps: Int): ByteArray {
        require(steps != Int.MIN_VALUE)
        val direction = if (steps >= 0) 1 else 0
        val magnitude = if (steps >= 0) steps else -steps
        return byteArrayOf(direction.toByte()) + OasisHidProtocol.int32(magnitude)
    }

    fun expectedStatusLength(generation: OasisFocuserGeneration): Int = when (generation) {
        OasisFocuserGeneration.FIRST -> 14
        OasisFocuserGeneration.ROSE -> 40
    }

    fun expectedConfigLength(generation: OasisFocuserGeneration): Int = when (generation) {
        OasisFocuserGeneration.FIRST -> 18
        OasisFocuserGeneration.ROSE -> 40
    }

    fun parseStatus(payload: ByteArray): OasisFocuserStatus? {
        if (payload.size < 14) return null
        val moving = OasisHidProtocol.uint8(payload, 8) != 0
        val position = OasisHidProtocol.int32(payload, 10) ?: return null
        val externalTemperature = OasisHidProtocol.int32(payload, 4)
            ?.takeUnless { it == Int.MIN_VALUE }
            ?.let { (it and 0xFFFF).toShort().toInt() / 100f }
        val boardTemperature = OasisHidProtocol.int32(payload, 0)
            ?.let(::ntcTemperature)
        return OasisFocuserStatus(position, moving, externalTemperature ?: boardTemperature)
    }

    fun parseConfig(payload: ByteArray): OasisFocuserConfig? {
        if (payload.size < 18) return null
        return OasisFocuserConfig(
            maxStep = OasisHidProtocol.int32(payload, 4) ?: return null,
            backlash = OasisHidProtocol.int32(payload, 8) ?: return null,
            speed = OasisHidProtocol.uint8(payload, 12) ?: return null,
            reverseDirection = OasisHidProtocol.uint8(payload, 13) ?: return null,
            backlashDirection = OasisHidProtocol.uint8(payload, 14) ?: return null,
            beepOnMove = OasisHidProtocol.uint8(payload, 15) ?: return null,
            beepOnStartup = OasisHidProtocol.uint8(payload, 16) ?: return null,
            bluetoothOn = OasisHidProtocol.uint8(payload, 17) ?: return null
        )
    }

    fun configPayload(
        generation: OasisFocuserGeneration,
        mask: Int,
        config: OasisFocuserConfig
    ): ByteArray {
        val payload = ByteArray(expectedConfigLength(generation))
        OasisHidProtocol.int32(mask).copyInto(payload, destinationOffset = 0)
        OasisHidProtocol.int32(config.maxStep).copyInto(payload, destinationOffset = 4)
        OasisHidProtocol.int32(config.backlash).copyInto(payload, destinationOffset = 8)
        payload[12] = config.speed.toByte()
        payload[13] = config.reverseDirection.toByte()
        payload[14] = config.backlashDirection.toByte()
        payload[15] = config.beepOnMove.toByte()
        payload[16] = config.beepOnStartup.toByte()
        payload[17] = config.bluetoothOn.toByte()
        return payload
    }

    private fun ntcTemperature(raw: Int): Float? {
        if (raw !in 1..0xFFE) return null
        val resistance = (0xFFF - raw).toDouble() / raw.toDouble()
        val kelvin = 1.0 / (ln(resistance) / 3950.0 + 1.0 / 298.15)
        return (kelvin - 273.15).toFloat()
    }
}

data class OasisFilterWheelStatus(
    val state: Int,
    val position: Int,
    val temperatureC: Float?
)

object OasisFilterWheelProtocol {
    const val commandGetConfig = 0x30
    const val commandSetConfig = 0x31
    const val commandGetStatus = 0x32
    const val commandFactoryReset = 0x33
    const val commandGetSlotCount = 0x50
    const val commandGetSlotName = 0x51
    const val commandSetSlotName = 0x52
    const val commandSetPosition = 0x57
    const val commandCalibrate = 0x58

    const val statusIdle = 0
    const val statusMoving = 1
    const val statusCalibrating = 2
    const val statusBenchmarking = 3

    fun parseStatus(payload: ByteArray): OasisFilterWheelStatus? {
        if (payload.size < 6) return null
        val rawTemperature = OasisHidProtocol.int32(payload, 0)
        return OasisFilterWheelStatus(
            state = OasisHidProtocol.uint8(payload, 4) ?: return null,
            position = OasisHidProtocol.uint8(payload, 5) ?: return null,
            temperatureC = rawTemperature?.let(::ntcTemperature)
        )
    }

    fun slotNamePayload(slot: Int, name: String): ByteArray {
        require(slot in 1..0xFF)
        val encodedName = name.encodeToByteArray()
        val payload = ByteArray(17)
        payload[0] = slot.toByte()
        encodedName.copyInto(
            payload,
            destinationOffset = 1,
            endIndex = minOf(16, encodedName.size)
        )
        return payload
    }

    fun slotNameQueryPayload(slot: Int): ByteArray {
        require(slot in 1..0xFF)
        return ByteArray(17).also { it[0] = slot.toByte() }
    }

    fun parseSlotName(payload: ByteArray): String? {
        if (payload.size < 17) return null
        val nullIndex = payload.copyOfRange(1, payload.size).indexOf(0)
        val end = if (nullIndex < 0) payload.size else nullIndex + 1
        return payload.copyOfRange(1, end).decodeToString().trim()
    }

    private fun ntcTemperature(raw: Int): Float? {
        if (raw !in 1..0xFFE) return null
        val resistance = (0xFFF - raw).toDouble() / raw.toDouble()
        val kelvin = 1.0 / (ln(resistance) / 3950.0 + 1.0 / 298.15)
        return (kelvin - 273.15).toFloat()
    }
}
