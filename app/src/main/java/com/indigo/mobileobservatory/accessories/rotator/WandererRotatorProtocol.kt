package com.indigo.mobileobservatory.accessories.rotator

import kotlin.math.roundToInt

/** Pure WandererRotator wire codec. USB serial I/O stays in the adapter. */
object WandererRotatorProtocol {
    const val baudRate = 19_200
    const val handshakeCommand = "1500001"
    const val setZeroCommand = "1500002"
    const val stopCommand = "Stop"
    private const val moveOffset = 1_000_000

    enum class Model(
        val wireName: String,
        val displayName: String,
        val stepsPerDegree: Int,
        val minimumFirmware: Int
    ) {
        MINI("WandererRotatorMini", "WandererRotator Mini", 1142, 20240226),
        LITE_V1("WandererRotatorLite", "WandererRotator Lite V1", 1155, 20240403),
        LITE_V2("WandererRotatorLiteV2", "WandererRotator Lite V2", 1199, 20240226);

        companion object {
            fun fromWireName(value: String): Model? = values().firstOrNull {
                it.wireName == value.trim()
            }
        }
    }

    data class Handshake(
        val model: Model,
        val firmware: Int,
        val mechanicalAngleMilliDegrees: Int,
        val backlashDegrees: Double,
        val reversed: Boolean
    ) {
        val angleDegrees: Double
            get() = normalizeAngle(mechanicalAngleMilliDegrees / 1000.0)
    }

    data class MoveCompletion(
        val movedDegrees: Double,
        val mechanicalAngleMilliDegrees: Int
    ) {
        val angleDegrees: Double
            get() = normalizeAngle(mechanicalAngleMilliDegrees / 1000.0)
    }

    private val handshakePattern = Regex(
        "(WandererRotatorLiteV2|WandererRotatorLite|WandererRotatorMini)\\s*A\\s*" +
            "(\\d+)\\s*A\\s*(-?\\d+)\\s*A\\s*" +
            "(-?(?:\\d+(?:\\.\\d*)?|\\.\\d+))\\s*A\\s*([01])\\s*A"
    )
    private val moveCompletionPattern =
        Regex("(-?\\d+(?:\\.\\d+)?)\\s*A\\s*(-?\\d+)\\s*A")

    fun parseHandshake(payload: String): Handshake? {
        val match = handshakePattern.find(payload) ?: return null
        val model = Model.fromWireName(match.groupValues[1]) ?: return null
        val firmware = match.groupValues[2].toIntOrNull() ?: return null
        val mechanicalAngle = match.groupValues[3].toIntOrNull() ?: return null
        val backlash = match.groupValues[4].toDoubleOrNull() ?: return null
        val reversed = match.groupValues[5] == "1"
        return Handshake(model, firmware, mechanicalAngle, backlash, reversed)
    }

    fun parseMoveCompletion(payload: String): MoveCompletion? {
        val match = moveCompletionPattern.findAll(payload).lastOrNull() ?: return null
        val moved = match.groupValues[1].toDoubleOrNull() ?: return null
        val mechanicalAngle = match.groupValues[2].toIntOrNull() ?: return null
        return MoveCompletion(moved, mechanicalAngle)
    }

    fun isFirmwareSupported(handshake: Handshake): Boolean =
        handshake.firmware >= handshake.model.minimumFirmware

    fun encodeMove(deltaDegrees: Double, model: Model): String {
        require(deltaDegrees.isFinite()) { "Rotation delta is invalid." }
        val steps = (deltaDegrees * model.stepsPerDegree).roundToInt()
        require(steps != 0) { "Rotation delta rounds to zero steps." }
        return (moveOffset + steps).toString()
    }

    fun encodeReverse(reversed: Boolean): String = if (reversed) "1700001" else "1700000"

    fun normalizeAngle(value: Double): Double = ((value % 360.0) + 360.0) % 360.0

    fun shortestDelta(fromDegrees: Double, toDegrees: Double): Double {
        val from = normalizeAngle(fromDegrees)
        val to = normalizeAngle(toDegrees)
        return ((to - from + 540.0) % 360.0) - 180.0
    }
}
