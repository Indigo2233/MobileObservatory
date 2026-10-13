package com.indigo.mobileobservatory.mount

import com.indigo.mobileobservatory.pointing.AxisReadingKind
import com.indigo.mobileobservatory.pointing.PointingMountCapabilities
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MountPointingAccessTest {
    @Test fun readOnlyDowngradesWritesIndependentlyAndRetainsActualReadCapabilities() {
        val access = MountPointingAccess()
        val raw = PointingMountCapabilities(readCoordinates = true, goto = true, sync = false, tracking = true,
            manualMove = true, home = true, setHome = true, readSite = true, writeSite = true, readTime = true, writeTime = true)
        access.connected(raw, "MOUNT_MODEL")
        access.setAccessMode(MountAccessMode.READ_ONLY)
        val cap = access.capabilities.value
        assertTrue(cap.readCoordinates && cap.readSite && cap.readTime)
        assertFalse(cap.goto || cap.sync || cap.tracking || cap.manualMove || cap.home || cap.setHome || cap.writeSite || cap.writeTime)
        assertEquals(AxisReadingKind.NONE, cap.axisReading)
        access.setAccessMode(MountAccessMode.READ_WRITE)
        assertEquals(raw, access.capabilities.value)
        access.disconnected(); assertEquals(PointingMountCapabilities(), access.capabilities.value)
    }

    @Test fun modelZeroConnectionAndModeChangesRejectPreviouslyCapturedAxisSamples() {
        val access = MountPointingAccess()
        val capabilities = PointingMountCapabilities(readCoordinates = true, axisReading = AxisReadingKind.MOTOR_COUNTER)
        val axes = MountRawAxisCoordinates(12.0, 30.0, .001, AxisReadingKind.MOTOR_COUNTER)
        access.connected(capabilities, "EQUATORIAL")
        val first = access.revision.value
        access.observe(axes, first, 100, 110); assertNotNull(access.axisObservation.value)
        access.modelChanged(); assertNull(access.axisObservation.value)
        access.observe(axes, first, 200, 210); assertNull(access.axisObservation.value)
        val second = access.revision.value
        access.observe(axes, second, 200, 210); assertNotNull(access.axisObservation.value)
        access.modelChanged(zero = true); assertTrue(access.revision.value.zero > second.zero)
        access.connected(capabilities, "ALTAZ"); assertEquals("ALTAZ", access.revision.value.mode)
        access.disconnected(); access.connected(capabilities, "ALTAZ")
        assertTrue(access.revision.value.connection > first.connection)
        access.observe(axes, first, 300, 310); assertNull(access.axisObservation.value)
    }

    @Test fun motorReadOnlyOpenSkipsInitializeAndCounterIsNeverAnAuxiliaryEncoder() {
        val fake = MotorReplies()
        val adapter = SkyWatcherMotorAdapter(fake::exchange, site = MountSite(31.0, 121.0),
            now = { Instant.parse("2026-01-01T00:00:00Z") }, initializeMotors = false)
        adapter.open()
        assertFalse(fake.sent.any { it.startsWith(":F") })
        val coordinates = adapter.readCoordinatesWithAxes()
        assertEquals(AxisReadingKind.MOTOR_COUNTER, coordinates.axes!!.kind)
        assertFalse(adapter.pointingCapabilities.observesReleasedClutch)
        assertTrue(adapter.pointingCapabilities.siteUsesLocalConfiguration)
        assertFalse(adapter.pointingCapabilities.writeSite || adapter.pointingCapabilities.readTime || adapter.pointingCapabilities.writeTime)
        fake.ra += 1000
        assertEquals(.36, adapter.readCoordinatesWithAxes().axes!!.axis1Deg - coordinates.axes!!.axis1Deg, 1e-9)
    }

    @Test fun controllerReadOnlyRejectsEveryWriteButAllowsReadingReleaseAndTrackingSafetyStop() = runBlocking {
        val fake = MotorReplies()
        val adapter = SkyWatcherMotorAdapter(fake::exchange, initializeMotors = false).also { it.open() }
        var clock = 100L
        val controller = Lx200MountController { clock += 10; clock }
        controller.attachSessionForTesting(adapter)
        controller.setAccessMode(MountAccessMode.READ_ONLY)
        controller.readCoordinates()
        assertEquals(AxisReadingKind.MOTOR_COUNTER, controller.axisObservation.value!!.kind)
        val before = fake.sent.size
        listOf<suspend () -> Unit>(
            { controller.slewTo(MountCoordinates(5.0, 40.0)) }, { controller.syncTo(MountCoordinates(5.0, 40.0)) },
            { controller.startMove(MountDirection.NORTH) }, { controller.setMoveRate(MountSlewRate.DEFAULT) },
            { controller.setTrackingRate(MountTrackingRate.SIDEREAL) }, { controller.setTrackingRate(MountTrackingRate.OFF) },
            { controller.goHome() }, { controller.setHomeHere() }, { controller.setSite(MountSite(30.0, 120.0)) },
            { controller.setTime(MountTime(1_000_000)) }
        ).forEach { write ->
            try { write(); fail("Read-only operation reached the adapter") } catch (_: IllegalStateException) {}
        }
        assertEquals(before, fake.sent.size)
        controller.stopMove(MountDirection.NORTH)
        controller.stopTrackingForSafety()
        assertTrue(fake.sent.drop(before).any { it.startsWith(":K1") })
        assertTrue(fake.sent.drop(before).any { it.startsWith(":K2") })
        val revision = controller.modelRevision.value
        controller.setLocalSiteConfiguration(MountSite(30.0, 120.0))
        assertTrue(controller.modelRevision.value.model > revision.model)
        assertNull(controller.axisObservation.value)
    }

    @Test fun controllerSyncZeroAndReconnectBreakMotorDeltaEvenWhenTheBoardModelNameHasEncoders() = runBlocking {
        val fake = MotorReplies()
        val adapter = SkyWatcherMotorAdapter(fake::exchange, initializeMotors = false).also { it.open() }
        var clock = 100L
        val controller = Lx200MountController { clock += 10; clock }
        controller.attachSessionForTesting(adapter); controller.readCoordinates()
        val original = controller.axisObservation.value!!
        controller.syncTo(MountCoordinates(5.0, 40.0))
        assertNull(controller.axisObservation.value)
        controller.readCoordinates(); assertNull(controller.axisObservation.value!!.deltaFrom(original))
        val afterSync = controller.axisObservation.value!!
        controller.setHomeHere(); controller.readCoordinates()
        assertNull(controller.axisObservation.value!!.deltaFrom(afterSync))
        val connected = controller.modelRevision.value
        controller.disconnect(); controller.attachSessionForTesting(adapter)
        assertTrue(controller.modelRevision.value.connection > connected.connection)
        assertFalse(controller.pointingCapabilities.value.observesReleasedClutch)
    }

    @Test fun highLevelSynScanNeverInventsSyncOrAxisTelemetryFromItsModelLabel() {
        val adapter = SkyWatcherAdapter { payload, _ -> when (payload.toString(Charsets.US_ASCII)) {
            "Kx" -> "x#".toByteArray(); "J" -> byteArrayOf(1, '#'.code.toByte())
            "m" -> byteArrayOf(0x02, '#'.code.toByte()); "e" -> "00000000,00000000#".toByteArray()
            else -> "#".toByteArray()
        } }
        adapter.open()
        val controller = Lx200MountController { 100 }
        controller.attachSessionForTesting(adapter)
        assertFalse(controller.pointingCapabilities.value.sync)
        assertEquals(AxisReadingKind.NONE, controller.pointingCapabilities.value.axisReading)
        assertFalse(controller.pointingCapabilities.value.observesReleasedClutch)
        assertTrue(controller.pointingCapabilities.value.readTime && controller.pointingCapabilities.value.writeTime)
    }

    @Test fun wirePolicyAllowsImplementedQueriesAndStopsAndRejectsUnknownOrMotionPackets() {
        listOf(":GR#", ":GEP#", ":MountInfo#", ":Q#", ":qR#", ":Td#", ":ST0#", ":j1\r", ":L2\r", ":K1\r", "Kx", "e")
            .forEach { assertTrue(it, MountReadOnlyWirePolicy.permits(it.toByteArray())) }
        listOf(":GUNKNOWN#", ":CM#", ":MS#", ":Sr12:00:00#", ":ST1#", ":F1\r", ":E1000000\r", ":J1\r", "r00000000,00000000", "H")
            .forEach { assertFalse(it, MountReadOnlyWirePolicy.permits(it.toByteArray())) }
        assertTrue(MountReadOnlyWirePolicy.permits(byteArrayOf('T'.code.toByte(), 0)))
        assertFalse(MountReadOnlyWirePolicy.permits(byteArrayOf('T'.code.toByte(), 2)))
        assertTrue(MountReadOnlyWirePolicy.permits(byteArrayOf('P'.code.toByte(), 2, 16, 36, 0, 0, 0, 0)))
        assertFalse(MountReadOnlyWirePolicy.permits(byteArrayOf('P'.code.toByte(), 2, 16, 36, 4, 0, 0, 0)))
    }

    @Test fun rejectedOtherManualDirectionCannotStopTheOwningPressOrAnotherGoto() {
        val owned = MountMotionState(MountMotionType.MANUAL, "East", manualDirection = MountDirection.EAST)
        assertTrue(owned.permitsManualRelease(MountDirection.EAST)); assertTrue(owned.permitsManualRelease(null))
        assertFalse(owned.permitsManualRelease(MountDirection.NORTH)); assertFalse(owned.permitsManualRelease(MountDirection.WEST))
        assertFalse(MountMotionState(MountMotionType.GOTO).permitsManualRelease(null))
    }

    private class MotorReplies {
        val sent = ArrayList<String>()
        var ra = SkyWatcherMotorCodec.POSITION_OFFSET
        var dec = SkyWatcherMotorCodec.POSITION_OFFSET + 250_000
        fun exchange(payload: ByteArray): ByteArray {
            val command = payload.toString(Charsets.US_ASCII); sent += command
            val value = when (command[1]) {
                'e' -> 0x040000L; 'a' -> 1_000_000L; 'b' -> 16_000_000L; 'g' -> 16L
                'j' -> if (command[2] == '1') ra else dec
                'f' -> return "=101\r".toByteArray()
                'E' -> { val target = SkyWatcherMotorCodec.hexToInt(command.substring(3).trim()); if (command[2] == '1') ra = target else dec = target; return "=\r".toByteArray() }
                else -> return "=\r".toByteArray()
            }
            return "=${SkyWatcherMotorCodec.intToHex(value, 6)}\r".toByteArray()
        }
    }
}
