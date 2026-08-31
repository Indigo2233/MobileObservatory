package com.indigo.mobileobservatory.accessories.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiPowerProtocolTest {
    @Test
    fun parsesIdentityAndFirmware() {
        assertEquals(GeminiPowerProtocol.ADV3_IDENTITY,
            GeminiPowerProtocol.parseIdentity("*HGeminiPowerBoxPlusAdv3#"))
        assertEquals(308, GeminiPowerProtocol.parseFirmware("*V308#"))
        assertEquals(GeminiPowerProtocol.V3_IDENTITY,
            GeminiPowerProtocol.parseIdentity("*HGeminiPowerBoxPlusV3#"))
        assertEquals(203, GeminiPowerProtocol.parseFirmware("*V203#"))
        assertNull(GeminiPowerProtocol.parseIdentity("*HGeminiFlatPanel#"))
    }

    @Test
    fun discoversRuntimeChannelCountsAndSensorsFromStatus() {
        val telemetry = GeminiPowerProtocol.parseStatus(
            "*G101D011U1A0T1A1M0B2M45C100C23.5S12.4T61.2H4.7D12.3V2.1C25.8P#"
        )!!

        assertEquals(listOf(true, false, true), telemetry.dcOutputs)
        assertEquals(listOf(false, true, true), telemetry.usbOutputs)
        assertEquals(GeminiStatusFormat.EXTENDED, telemetry.statusFormat)
        assertTrue(telemetry.ambientSensorAttached)
        assertFalse(telemetry.deviceTemperatureSensorAttached)
        assertEquals(DewHeaterMode.MANUAL_PWM, telemetry.dewHeaters[0].mode)
        assertEquals(45.0, telemetry.dewHeaters[0].outputValue, 0.0)

        val capabilities = GeminiPowerProtocol.discoverCapabilities(
            GeminiPowerProtocol.ADV3_IDENTITY,
            308,
            telemetry
        )
        assertEquals(3, capabilities.dcOutputs.size)
        assertEquals(3, capabilities.usbOutputs.size)
        assertTrue(capabilities.hasAmbientSensor)
        assertFalse(capabilities.hasDeviceTemperatureSensor)
        assertTrue(capabilities.canControlOutputs)
        assertEquals(DewHeaterMode.entries.toSet(), capabilities.dewHeaters[0].supportedModes)
        assertTrue(capabilities.dewHeaters[0].supportsEnabledSwitch)
        assertEquals(100, capabilities.dewHeaters[0].manualOutputMaximum)
    }

    @Test
    fun rejectsMalformedStatus() {
        assertNull(GeminiPowerProtocol.parseStatus("*G1012D01U1A0T1A1M0B2M45C100C23S12T61H4D12V2C25P#"))
        assertNull(GeminiPowerProtocol.parseStatus("*G10D01U1A0T1A9M0B2M45C100C23S12T61H4D12V2C25P#"))
    }

    @Test
    fun skipsShortAcknowledgementBeforeFirstTelemetryFrame() {
        val valid = "*G101D011U1A0T1A1M0B2M45C100C23.5S12.4T61.2H4.7D12.3V2.1C25.8P#"
        val selected = GeminiPowerProtocol.firstValidStatus(listOf("*G", valid))

        assertEquals(listOf(true, false, true), selected?.dcOutputs)
        assertTrue(GeminiPowerProtocol.parseStatusDetailed("*G").error!!.contains("terminator"))
    }

    @Test
    fun parsesCompactFirmwareStatusCapturedFromHardware() {
        val telemetry = GeminiPowerProtocol.parseStatus(
            "*G1111101011A10B2S117T 26.60H\n69.50#"
        )!!

        assertEquals(listOf(true, true, true, true), telemetry.dcOutputs)
        assertTrue(telemetry.usbMasterEnabled!!)
        assertTrue(telemetry.usbOutputs.isEmpty())
        assertEquals(0, telemetry.usbControlCount)
        assertEquals(GeminiStatusFormat.COMPACT, telemetry.statusFormat)
        assertEquals(DewHeaterMode.MANUAL_PWM, telemetry.dewHeaters[0].mode)
        assertFalse(telemetry.dewHeaters[0].enabled)
        assertEquals(10.0, telemetry.dewHeaters[0].outputValue, 0.0)
        assertEquals(DewHeaterMode.MANUAL_PWM, telemetry.dewHeaters[1].mode)
        assertFalse(telemetry.dewHeaters[1].enabled)
        assertEquals(2.0, telemetry.dewHeaters[1].outputValue, 0.0)
        assertEquals(11.7, telemetry.deviceTemperatureC!!, 0.0)
        assertEquals(26.6, telemetry.ambientTemperatureC!!, 0.0)
        assertEquals(69.5, telemetry.humidityPercent!!, 0.0)
        assertNull(telemetry.outputCurrentA)
        assertNull(telemetry.outputPowerW)
    }

    @Test
    fun parsesSubsequentCompactFirmwareStatusCapturedFromHardware() {
        val telemetry = GeminiPowerProtocol.parseStatus(
            "*G1111101011A10B2S112T 26.80H\n63.70#"
        )!!

        assertEquals(listOf(true, true, true, true), telemetry.dcOutputs)
        assertTrue(telemetry.usbMasterEnabled!!)
        assertTrue(telemetry.usbOutputs.isEmpty())
        assertEquals(11.2, telemetry.deviceTemperatureC!!, 0.0)
        assertEquals(26.8, telemetry.ambientTemperatureC!!, 0.0)
        assertEquals(63.7, telemetry.humidityPercent!!, 0.0)
    }

    @Test
    fun parsesCompactFirmwareStatusWithNonBinaryOutputStateCapturedFromHardware() {
        val telemetry = GeminiPowerProtocol.parseStatus(
            "*G0001002011A10B2S117T 27.30H\n67.10#"
        )!!

        assertEquals(GeminiStatusFormat.COMPACT, telemetry.statusFormat)
        assertEquals(listOf(false, false, false, true), telemetry.dcOutputs)
        assertFalse(telemetry.usbMasterEnabled!!)
        assertTrue(telemetry.usbOutputs.isEmpty())
        assertEquals(DewHeaterMode.BINARY_SWITCH, telemetry.dewHeaters[0].mode)
        assertFalse(telemetry.dewHeaters[0].enabled)
        assertEquals(DewHeaterMode.MANUAL_PWM, telemetry.dewHeaters[1].mode)
        assertFalse(telemetry.dewHeaters[1].enabled)
        assertEquals(11.7, telemetry.deviceTemperatureC!!, 0.0)
        assertEquals(27.3, telemetry.ambientTemperatureC!!, 0.0)
        assertEquals(67.1, telemetry.humidityPercent!!, 0.0)
    }

    @Test
    fun parsesCompactControlStateCapturedFromBuild21Log() {
        val telemetry = GeminiPowerProtocol.parseStatus(
            "*G0010112121A0B0S142T 28.90H 81.20#"
        )!!

        assertEquals(listOf(false, false, true, false), telemetry.dcOutputs)
        assertTrue(telemetry.usbMasterEnabled!!)
        assertTrue(telemetry.usbOutputs.isEmpty())
        assertEquals(
            DewHeaterState(true, DewHeaterMode.BINARY_SWITCH, 0.0),
            telemetry.dewHeaters[0]
        )
        assertEquals(
            DewHeaterState(true, DewHeaterMode.BINARY_SWITCH, 0.0),
            telemetry.dewHeaters[1]
        )
    }

    @Test
    fun exposesOnlyProtocolBackedOutputsFromCompactStatus() {
        val telemetry = GeminiPowerProtocol.parseStatus(
            "*G0001002011A10B2S117T 27.30H\n67.10#"
        )!!

        val capabilities = GeminiPowerProtocol.discoverCapabilities(
            "GeminiPowerBoxFuture",
            1,
            telemetry
        )

        assertEquals(listOf(2, 3, 4, 5), capabilities.dcOutputs.map { it.commandAddress })
        assertEquals(6, capabilities.usbMasterOutput?.commandAddress)
        assertTrue(capabilities.usbOutputs.isEmpty())
        assertEquals(DewHeaterMode.entries.toSet(), capabilities.dewHeaters[0].supportedModes)
        assertTrue(capabilities.dewHeaters[0].supportsEnabledSwitch)
        assertEquals(255, capabilities.dewHeaters[0].manualOutputMaximum)
    }

    @Test
    fun parsesCompactDewStatePositionsProvenByControlCommands() {
        val telemetry = GeminiPowerProtocol.parseStatus(
            "*G0010112121A0B0S142T 28.90H 81.20#"
        )!!

        assertTrue(telemetry.usbMasterEnabled!!)
        assertTrue(telemetry.usbOutputs.isEmpty())
        assertEquals(
            DewHeaterState(true, DewHeaterMode.BINARY_SWITCH, 0.0),
            telemetry.dewHeaters[0]
        )
        assertEquals(
            DewHeaterState(true, DewHeaterMode.BINARY_SWITCH, 0.0),
            telemetry.dewHeaters[1]
        )
    }

    @Test
    fun supportsFullCompactManualPwmRange() {
        val telemetry = GeminiPowerProtocol.parseStatus(
            "*G0010112121A255B128S142T 28.90H 81.20#"
        )!!
        val capabilities = GeminiPowerProtocol.discoverCapabilities(
            GeminiPowerProtocol.V3_IDENTITY,
            203,
            telemetry
        )

        assertEquals(255.0, telemetry.dewHeaters[0].outputValue, 0.0)
        assertEquals(128.0, telemetry.dewHeaters[1].outputValue, 0.0)
        assertEquals(255, capabilities.dewHeaters[0].manualOutputMaximum)
        assertEquals(">X255#", GeminiPowerProtocol.dewPowerCommand(capabilities.dewHeaters[0], 255))
        assertEquals(">Y255#", GeminiPowerProtocol.dewPowerCommand(capabilities.dewHeaters[1], 255))
    }

    @Test
    fun selectsDewControlFromCurrentMode() {
        val capability = DewHeaterCapability(
            index = 0,
            commandAddress = 6,
            supportedModes = DewHeaterMode.entries.toSet(),
            manualOutputMaximum = 255
        )

        assertEquals(
            DewHeaterControlType.ENABLED_SWITCH,
            capability.controlType(DewHeaterMode.AUTOMATIC)
        )
        assertEquals(
            DewHeaterControlType.MANUAL_VALUE,
            capability.controlType(DewHeaterMode.MANUAL_PWM)
        )
        assertEquals(
            DewHeaterControlType.ENABLED_SWITCH,
            capability.controlType(DewHeaterMode.BINARY_SWITCH)
        )
    }

    @Test
    fun keepsExtendedDewOutputRangeAtOneHundred() {
        assertNull(
            GeminiPowerProtocol.parseStatus(
                "*G101D011U1A0T1A1M0B2M101C100C23.5S12.4T61.2H4.7D12.3V2.1C25.8P#"
            )
        )
        val capability = DewHeaterCapability(0, 6, DewHeaterMode.entries.toSet())
        assertTrue(runCatching {
            GeminiPowerProtocol.dewPowerCommand(capability, 101)
        }.isFailure)
    }

    @Test
    fun enablesControlsForPlusV3Firmware203FromLiveCompactStatus() {
        val telemetry = GeminiPowerProtocol.parseStatus(
            "*G1111101011A10B2S112T 26.80H\n63.70#"
        )!!

        val supported = GeminiPowerProtocol.discoverCapabilities(
            GeminiPowerProtocol.V3_IDENTITY,
            203,
            telemetry
        )
        val older = GeminiPowerProtocol.discoverCapabilities(
            GeminiPowerProtocol.V3_IDENTITY,
            202,
            telemetry
        )

        assertTrue(supported.canControlOutputs)
        assertEquals(4, supported.dcOutputs.size)
        assertTrue(supported.usbOutputs.isEmpty())
        assertEquals(1, supported.controllableUsbGroupCount)
        assertEquals(2, supported.dewHeaters.size)
        assertEquals(listOf(2, 3, 4, 5), supported.dcOutputs.map { it.commandAddress })
        assertEquals(6, supported.usbMasterOutput?.commandAddress)
        assertEquals(
            DewHeaterMode.entries.toSet(),
            supported.dewHeaters[0].supportedModes
        )
        assertTrue(supported.dewHeaters[0].supportsEnabledSwitch)
        assertTrue(older.canControlOutputs)
    }

    @Test
    fun discoversCompactCapabilitiesForUnknownGeminiIdentity() {
        val telemetry = GeminiPowerProtocol.parseStatus(
            "*G111110101121A10B2S112T26.80H63.70#"
        )!!

        val capabilities = GeminiPowerProtocol.discoverCapabilities(
            "GeminiPowerBoxFuture",
            1,
            telemetry
        )

        assertTrue(capabilities.canControlOutputs)
        assertEquals(4, capabilities.dcOutputs.size)
        assertNull(capabilities.usbMasterOutput)
        assertEquals(listOf(6, 7, 8), capabilities.usbOutputs.map { it.commandAddress })
        assertEquals(3, capabilities.controllableUsbGroupCount)
        assertEquals(
            DewHeaterMode.entries.toSet(),
            capabilities.dewHeaters[0].supportedModes
        )
        assertTrue(capabilities.dewHeaters[0].supportsEnabledSwitch)
    }

    @Test
    fun encodesCommandsFromDiscoveredCapabilities() {
        val output = PowerOutputCapability(PowerOutputKind.DC, 0, 2)
        val usb = PowerOutputCapability(PowerOutputKind.USB, 5, 12)
        val heater = DewHeaterCapability(1, 7, DewHeaterMode.entries.toSet())

        assertEquals(">O2#", GeminiPowerProtocol.outputCommand(output, true))
        assertEquals(">C2#", GeminiPowerProtocol.outputCommand(output, false))
        assertEquals(">O12#", GeminiPowerProtocol.outputCommand(usb, true))
        assertEquals(">C12#", GeminiPowerProtocol.outputCommand(usb, false))
        assertEquals(">Z21#", GeminiPowerProtocol.dewEnabledCommand(heater, true))
        assertEquals(">M21#", GeminiPowerProtocol.dewModeCommand(heater, DewHeaterMode.MANUAL_PWM))
        assertEquals(">Y65#", GeminiPowerProtocol.dewPowerCommand(heater, 65))
    }

    @Test
    fun enablesCompactUsbMasterBeforeAnIndividualPort() {
        val master = PowerOutputCapability(PowerOutputKind.USB, -1, 6)
        val usb1 = PowerOutputCapability(PowerOutputKind.USB, 0, 7)

        assertEquals(
            listOf(">O6#", ">O7#"),
            GeminiPowerProtocol.usbOutputCommandPlan(
                capability = usb1,
                masterCapability = master,
                masterEnabled = false,
                enabled = true
            )
        )
        assertEquals(
            listOf(">O7#"),
            GeminiPowerProtocol.usbOutputCommandPlan(
                capability = usb1,
                masterCapability = master,
                masterEnabled = true,
                enabled = true
            )
        )
    }

    @Test
    fun individualUsbDisableDoesNotToggleItsMaster() {
        val master = PowerOutputCapability(PowerOutputKind.USB, -1, 6)
        val usb6 = PowerOutputCapability(PowerOutputKind.USB, 5, 12)

        assertEquals(
            listOf(">C12#"),
            GeminiPowerProtocol.usbOutputCommandPlan(
                capability = usb6,
                masterCapability = master,
                masterEnabled = false,
                enabled = false
            )
        )
    }
}
