package com.indigo.mobileobservatory.accessories.power

import kotlin.math.ln

enum class DewHeaterMode { AUTOMATIC, MANUAL_PWM, BINARY_SWITCH }

enum class DewHeaterControlType { MANUAL_VALUE, ENABLED_SWITCH, STATUS_ONLY }

enum class PowerOutputKind { DC, USB }

enum class GeminiStatusFormat { EXTENDED, COMPACT }

data class PowerOutputCapability(
    val kind: PowerOutputKind,
    val index: Int,
    val commandAddress: Int
)

data class DewHeaterCapability(
    val index: Int,
    val commandAddress: Int,
    val supportedModes: Set<DewHeaterMode>,
    val supportsEnabledSwitch: Boolean = true,
    val manualOutputMaximum: Int = 100
) {
    fun controlType(mode: DewHeaterMode?): DewHeaterControlType = when {
        supportedModes == setOf(DewHeaterMode.MANUAL_PWM) -> DewHeaterControlType.MANUAL_VALUE
        mode == DewHeaterMode.MANUAL_PWM -> DewHeaterControlType.MANUAL_VALUE
        supportsEnabledSwitch &&
            (mode == DewHeaterMode.AUTOMATIC || mode == DewHeaterMode.BINARY_SWITCH) ->
            DewHeaterControlType.ENABLED_SWITCH
        else -> DewHeaterControlType.STATUS_ONLY
    }
}

data class GeminiPowerCapabilities(
    val identity: String,
    val firmware: Int,
    val dcOutputs: List<PowerOutputCapability>,
    val usbMasterOutput: PowerOutputCapability?,
    val usbOutputs: List<PowerOutputCapability>,
    val dewHeaters: List<DewHeaterCapability>,
    val hasAmbientSensor: Boolean,
    val hasDeviceTemperatureSensor: Boolean,
    val canControlOutputs: Boolean
) {
    val controllableUsbGroupCount: Int
        get() = usbOutputs.size + if (usbMasterOutput == null) 0 else 1
}

data class DewHeaterState(
    val enabled: Boolean,
    val mode: DewHeaterMode?,
    val outputValue: Double
)

data class GeminiPowerTelemetry(
    val statusFormat: GeminiStatusFormat,
    val dcOutputs: List<Boolean>,
    val usbMasterEnabled: Boolean? = null,
    val usbOutputs: List<Boolean>,
    val usbControlCount: Int = usbOutputs.size,
    val dewHeaters: List<DewHeaterState>,
    val ambientSensorAttached: Boolean,
    val deviceTemperatureSensorAttached: Boolean,
    val deviceTemperatureC: Double?,
    val ambientTemperatureC: Double?,
    val humidityPercent: Double?,
    val dewPointC: Double?,
    val inputVoltageV: Double?,
    val outputCurrentA: Double?,
    val outputPowerW: Double?
)

data class GeminiStatusParseResult(
    val telemetry: GeminiPowerTelemetry? = null,
    val error: String? = null
)

/**
 * Wire codec for Gemini Power Box controllers.
 *
 * Channel counts come from the live `*G` status frame. Command addresses are
 * protocol addresses and stay outside the UI/state layer.
 */
object GeminiPowerProtocol {
    const val BAUD_RATE = 19200
    const val ADV3_IDENTITY = "GeminiPowerBoxPlusAdv3"
    const val V3_IDENTITY = "GeminiPowerBoxPlusV3"

    private val statusTerminators = charArrayOf(
        'D', 'U', 'A', 'T', 'A', 'M', 'B', 'M', 'C', 'C', 'S', 'T', 'H', 'D', 'V', 'C', 'P'
    )
    private val compactStatus = Regex(
        "^([012]{4,32})A(\\d{1,3})B(\\d{1,3})S([+-]?\\d+(?:\\.\\d+)?)" +
            "T\\s*([+-]?\\d+(?:\\.\\d+)?)H\\s*(\\d+(?:\\.\\d+)?)$"
    )

    fun parseIdentity(frame: String): String? =
        normalizedPayload(frame, "*H")?.takeIf { it.startsWith("GeminiPowerBox") }

    fun parseFirmware(frame: String): Int? =
        normalizedPayload(frame, "*V")?.takeWhile { it.isDigit() }?.toIntOrNull()

    fun parseStatus(frame: String): GeminiPowerTelemetry? = parseStatusDetailed(frame).telemetry

    fun firstValidStatus(frames: Iterable<String>): GeminiPowerTelemetry? =
        frames.firstNotNullOfOrNull { parseStatus(it) }

    fun parseStatusDetailed(frame: String): GeminiStatusParseResult {
        fun invalid(reason: String) = GeminiStatusParseResult(error = reason)

        val payload = normalizedPayload(frame, "*G")
            ?: return invalid("frame does not start with *G")
        parseCompactStatus(payload)?.let { return GeminiStatusParseResult(telemetry = it) }
        val leadingBits = payload.takeWhile { it == '0' || it == '1' }
        if (leadingBits.length >= 4 && payload.getOrNull(leadingBits.length) == 'A') {
            return invalid("invalid compact Gemini status fields")
        }
        val fields = ArrayList<String>(statusTerminators.size)
        var position = 0
        statusTerminators.forEachIndexed { index, terminator ->
            val end = payload.indexOf(terminator, position)
            if (end < 0) return invalid("missing field terminator $terminator at index $index")
            fields += payload.substring(position, end)
            position = end + 1
        }
        if (position != payload.length) return invalid("trailing data after final P")

        val dc = parseBits(fields[0]) ?: return invalid("invalid DC bits '${fields[0]}'")
        val usb = parseBits(fields[1]) ?: return invalid("invalid USB bits '${fields[1]}'")
        val ambientAttached = parseBoolean(fields[2])
            ?: return invalid("invalid AHT20 flag '${fields[2]}'")
        val deviceTemperatureAttached = parseBoolean(fields[3])
            ?: return invalid("invalid DS18B20 flag '${fields[3]}'")
        val heater1 = parseHeater(fields[4], fields[5], fields[8])
            ?: return invalid("invalid dew channel 1 fields '${fields[4]},${fields[5]},${fields[8]}'")
        val heater2 = parseHeater(fields[6], fields[7], fields[9])
            ?: return invalid("invalid dew channel 2 fields '${fields[6]},${fields[7]},${fields[9]}'")
        val deviceTemperature = finiteDouble(fields[10])
            ?: return invalid("invalid device temperature '${fields[10]}'")
        val ambientTemperature = finiteDouble(fields[11])
            ?: return invalid("invalid ambient temperature '${fields[11]}'")
        val humidity = finiteDouble(fields[12])?.takeIf { it in 0.0..100.0 }
            ?: return invalid("invalid humidity '${fields[12]}'")
        val dewPoint = finiteDouble(fields[13]) ?: return invalid("invalid dew point '${fields[13]}'")
        val voltage = finiteDouble(fields[14]) ?: return invalid("invalid voltage '${fields[14]}'")
        val current = finiteDouble(fields[15]) ?: return invalid("invalid current '${fields[15]}'")
        val power = finiteDouble(fields[16]) ?: return invalid("invalid power '${fields[16]}'")

        return GeminiStatusParseResult(
            telemetry = GeminiPowerTelemetry(
                statusFormat = GeminiStatusFormat.EXTENDED,
                dcOutputs = dc,
                usbOutputs = usb,
                dewHeaters = listOf(heater1, heater2),
                ambientSensorAttached = ambientAttached,
                deviceTemperatureSensorAttached = deviceTemperatureAttached,
                deviceTemperatureC = deviceTemperature,
                ambientTemperatureC = ambientTemperature,
                humidityPercent = humidity,
                dewPointC = dewPoint,
                inputVoltageV = voltage,
                outputCurrentA = current,
                outputPowerW = power
            )
        )
    }

    fun discoverCapabilities(
        identity: String,
        firmware: Int,
        telemetry: GeminiPowerTelemetry
    ): GeminiPowerCapabilities {
        val recognizedFamily = identity.startsWith("GeminiPowerBox")
        val hasControllableOutputs = telemetry.dcOutputs.isNotEmpty() ||
            telemetry.usbOutputs.isNotEmpty() || telemetry.dewHeaters.isNotEmpty()
        return GeminiPowerCapabilities(
            identity = identity,
            firmware = firmware,
            dcOutputs = telemetry.dcOutputs.indices.map {
                PowerOutputCapability(PowerOutputKind.DC, it, 2 + it)
            },
            usbMasterOutput = telemetry.usbMasterEnabled?.let {
                PowerOutputCapability(PowerOutputKind.USB, -1, 6)
            },
            usbOutputs = (0 until telemetry.usbControlCount).map {
                PowerOutputCapability(
                    PowerOutputKind.USB,
                    it,
                    if (telemetry.usbMasterEnabled != null) 7 + it else 6 + it
                )
            },
            dewHeaters = telemetry.dewHeaters.indices.map {
                DewHeaterCapability(
                    index = it,
                    commandAddress = 6 + it,
                    supportedModes = DewHeaterMode.entries.toSet(),
                    supportsEnabledSwitch = true,
                    manualOutputMaximum = when (telemetry.statusFormat) {
                        GeminiStatusFormat.COMPACT -> 255
                        GeminiStatusFormat.EXTENDED -> 100
                    }
                )
            },
            hasAmbientSensor = telemetry.ambientSensorAttached,
            hasDeviceTemperatureSensor = telemetry.deviceTemperatureSensorAttached,
            canControlOutputs = recognizedFamily && hasControllableOutputs
        )
    }

    fun mergeUsbOutputStates(
        telemetry: GeminiPowerTelemetry,
        previous: List<Boolean>,
        requested: Map<Int, Boolean>
    ): List<Boolean> {
        if (telemetry.usbOutputs.size >= telemetry.usbControlCount) {
            return telemetry.usbOutputs.take(telemetry.usbControlCount)
        }
        return List(telemetry.usbControlCount) { index ->
            requested[index]
                ?: telemetry.usbOutputs.getOrNull(index)
                ?: previous.getOrNull(index)
                ?: false
        }
    }

    fun outputCommand(capability: PowerOutputCapability, enabled: Boolean): String =
        ">${if (enabled) 'O' else 'C'}${capability.commandAddress}#"

    fun usbOutputCommandPlan(
        capability: PowerOutputCapability,
        masterCapability: PowerOutputCapability?,
        masterEnabled: Boolean?,
        enabled: Boolean
    ): List<String> = buildList {
        if (enabled && masterCapability != null && masterEnabled != true) {
            add(outputCommand(masterCapability, true))
        }
        add(outputCommand(capability, enabled))
    }.distinct()

    fun dewEnabledCommand(capability: DewHeaterCapability, enabled: Boolean): String {
        require(capability.supportsEnabledSwitch)
        val channel = capability.commandAddress - 5
        require(channel in 1..2)
        return ">Z${channel}${if (enabled) 1 else 0}#"
    }

    fun dewModeCommand(capability: DewHeaterCapability, mode: DewHeaterMode): String {
        require(mode in capability.supportedModes)
        val channel = capability.commandAddress - 5
        require(channel in 1..2)
        val modeCode = when (mode) {
            DewHeaterMode.AUTOMATIC -> 0
            DewHeaterMode.MANUAL_PWM -> 1
            DewHeaterMode.BINARY_SWITCH -> 2
        }
        return ">M$channel$modeCode#"
    }

    fun dewPowerCommand(capability: DewHeaterCapability, value: Int): String {
        require(value in 0..capability.manualOutputMaximum)
        val command = when (capability.commandAddress) {
            6 -> 'X'
            7 -> 'Y'
            else -> error("Unsupported Gemini dew channel ${capability.commandAddress}")
        }
        return ">$command$value#"
    }

    private fun normalizedPayload(frame: String, prefix: String): String? {
        val normalized = frame.trim().removeSuffix("#").trim()
        if (!normalized.startsWith(prefix)) return null
        return normalized.removePrefix(prefix)
    }

    private fun parseBits(value: String): List<Boolean>? {
        if (value.isEmpty() || value.length > 32 || value.any { it != '0' && it != '1' }) return null
        return value.map { it == '1' }
    }

    private fun parseBoolean(value: String): Boolean? = when (value) {
        "0" -> false
        "1" -> true
        else -> null
    }

    private fun parseHeater(enabled: String, mode: String, output: String): DewHeaterState? {
        val parsedEnabled = parseBoolean(enabled) ?: return null
        val parsedMode = when (mode.toIntOrNull()) {
            0 -> DewHeaterMode.AUTOMATIC
            1 -> DewHeaterMode.MANUAL_PWM
            2 -> DewHeaterMode.BINARY_SWITCH
            else -> return null
        }
        val parsedOutput = finiteDouble(output)?.takeIf { it in 0.0..100.0 } ?: return null
        return DewHeaterState(parsedEnabled, parsedMode, parsedOutput)
    }

    /**
     * Compact layout: four DC states, one or more reported USB states, two
     * enabled/mode pairs for the dew channels, and the probe-presence flag.
     * A single USB state is the legacy aggregate USB switch. Multiple states
     * are exposed individually from the frame without using a model table.
     */
    private fun parseCompactStatus(payload: String): GeminiPowerTelemetry? {
        val match = compactStatus.matchEntire(payload) ?: return null
        val outputField = match.groupValues[1]
        val compactTailLength = 5
        if (outputField.length < 4 + 1 + compactTailLength) return null
        val dewOffset = outputField.length - compactTailLength
        val dcOutputs = parseBits(outputField.substring(0, 4)) ?: return null
        val usbStatus = parseBits(outputField.substring(4, dewOffset)) ?: return null
        val usbMasterEnabled = usbStatus.singleOrNull()
        val usbOutputs = if (usbMasterEnabled == null) usbStatus else emptyList()
        val dewA = finiteDouble(match.groupValues[2])?.takeIf { it in 0.0..255.0 } ?: return null
        val dewB = finiteDouble(match.groupValues[3])?.takeIf { it in 0.0..255.0 } ?: return null
        val heaterA = parseCompactHeater(
            outputField[dewOffset],
            outputField[dewOffset + 1],
            dewA
        ) ?: return null
        val heaterB = parseCompactHeater(
            outputField[dewOffset + 2],
            outputField[dewOffset + 3],
            dewB
        ) ?: return null
        val deviceTemperatureProbeAttached = parseBoolean(outputField[dewOffset + 4].toString())
            ?: return null
        val deviceTemperatureRaw = parseCompactDeviceTemperature(match.groupValues[4]) ?: return null
        val ambientTemperature = finiteDouble(match.groupValues[5]) ?: return null
        val humidity = finiteDouble(match.groupValues[6])?.takeIf { it in 0.0..100.0 } ?: return null
        val deviceTemperatureAttached = deviceTemperatureProbeAttached && deviceTemperatureRaw > -126.5

        return GeminiPowerTelemetry(
            statusFormat = GeminiStatusFormat.COMPACT,
            dcOutputs = dcOutputs,
            usbMasterEnabled = usbMasterEnabled,
            usbOutputs = usbOutputs,
            usbControlCount = usbOutputs.size,
            dewHeaters = listOf(heaterA, heaterB),
            ambientSensorAttached = true,
            deviceTemperatureSensorAttached = deviceTemperatureAttached,
            deviceTemperatureC = deviceTemperatureRaw.takeIf { deviceTemperatureAttached },
            ambientTemperatureC = ambientTemperature,
            humidityPercent = humidity,
            dewPointC = calculateDewPoint(ambientTemperature, humidity),
            inputVoltageV = null,
            outputCurrentA = null,
            outputPowerW = null
        )
    }

    private fun parseCompactHeater(
        enabled: Char,
        mode: Char,
        outputValue: Double
    ): DewHeaterState? {
        val parsedMode = when (mode) {
            '0' -> DewHeaterMode.AUTOMATIC
            '1' -> DewHeaterMode.MANUAL_PWM
            '2' -> DewHeaterMode.BINARY_SWITCH
            else -> return null
        }
        val parsedEnabled = when (enabled) {
            '0' -> false
            '1' -> true
            else -> return null
        }
        return DewHeaterState(parsedEnabled, parsedMode, outputValue)
    }

    private fun parseCompactDeviceTemperature(value: String): Double? {
        val parsed = finiteDouble(value) ?: return null
        return if ('.' in value) parsed else parsed / 10.0
    }

    private fun calculateDewPoint(temperatureC: Double, humidityPercent: Double): Double? {
        if (humidityPercent <= 0.0) return null
        val a = 17.62
        val b = 243.12
        val gamma = ln(humidityPercent / 100.0) + a * temperatureC / (b + temperatureC)
        return b * gamma / (a - gamma)
    }

    private fun finiteDouble(value: String): Double? =
        value.toDoubleOrNull()?.takeIf { it.isFinite() }
}
