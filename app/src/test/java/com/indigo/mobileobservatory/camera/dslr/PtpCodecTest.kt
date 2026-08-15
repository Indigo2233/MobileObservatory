package com.indigo.mobileobservatory.camera.dslr

import com.indigo.mobileobservatory.camera.CameraBrand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PtpContainerTest {

    @Test
    fun `command container round-trips header and parameters`() {
        val original = PtpContainer.command(
            PtpConstants.OC_OPEN_SESSION,
            transactionId = 7,
            params = longArrayOf(1L, 0x12345678L)
        )
        val parsed = PtpContainer.parse(original.toBytes())

        assertEquals(PtpConstants.CONTAINER_COMMAND, parsed.type)
        assertEquals(PtpConstants.OC_OPEN_SESSION, parsed.code)
        assertEquals(7, parsed.transactionId)
        assertEquals(8, parsed.payload.size)
        assertEquals(1, ptpU32(parsed.payload, 0).toInt())
        assertEquals(0x12345678, ptpU32(parsed.payload, 4).toInt())
    }

    @Test
    fun `device info dataset round-trips strings and opcode lists`() {
        val original = PtpDeviceInfo(
            standardVersion = 0x0064,
            vendorExtensionId = 10,
            vendorExtensionVersion = 0x0064,
            vendorExtensionDesc = "Nikon",
            functionalMode = 0,
            operations = listOf(
                PtpConstants.OC_GET_DEVICE_INFO,
                PtpConstants.OC_OPEN_SESSION,
                0x9201
            ),
            events = listOf(0x4002),
            properties = listOf(
                PtpConstants.PROP_EXPOSURE_INDEX,
                PtpConstants.PROP_EXPOSURE_TIME
            ),
            captureFormats = listOf(0x3001),
            imageFormats = listOf(0x3000, 0x3001),
            manufacturer = "Nikon Corporation",
            model = "D5100",
            deviceVersion = "1.01",
            serialNumber = "ABC123"
        )

        val parsed = PtpDeviceInfo.parse(PtpDeviceInfo.encode(original))
        assertEquals(original, parsed)
        assertTrue(parsed.hasOperation(0x9201))
        assertTrue(parsed.hasProperty(PtpConstants.PROP_EXPOSURE_INDEX))
    }

    @Test
    fun `property desc parses writable ISO enumeration`() {
        val payload = PtpWriter().apply {
            u16(PtpConstants.PROP_EXPOSURE_INDEX)
            u16(PtpConstants.TYPE_UINT16)
            u8(1)
            u16(100)
            u16(400)
            u8(PtpConstants.FORM_ENUM)
            u16(4)
            u16(100)
            u16(200)
            u16(400)
            u16(800)
        }.toByteArray()

        val desc = PtpPropertyDesc.parse(payload)
        assertEquals(PtpConstants.PROP_EXPOSURE_INDEX, desc.code)
        assertTrue(desc.writable)
        assertEquals(100L, desc.defaultValue)
        assertEquals(400L, desc.currentValue)
        assertEquals(listOf(100L, 200L, 400L, 800L), desc.enumValues)
    }
}

class PtpSessionTest {

    @Test
    fun `open session reads device info and ISO desc`() {
        val info = PtpDeviceInfo(
            standardVersion = 100,
            vendorExtensionId = 10,
            vendorExtensionVersion = 100,
            vendorExtensionDesc = "",
            functionalMode = 0,
            operations = listOf(PtpConstants.OC_GET_DEVICE_INFO, PtpConstants.OC_OPEN_SESSION),
            events = emptyList(),
            properties = listOf(PtpConstants.PROP_EXPOSURE_INDEX),
            captureFormats = emptyList(),
            imageFormats = emptyList(),
            manufacturer = "Nikon Corporation",
            model = "D5100",
            deviceVersion = "1.00",
            serialNumber = "1"
        )
        val isoPayload = PtpWriter().apply {
            u16(PtpConstants.PROP_EXPOSURE_INDEX)
            u16(PtpConstants.TYPE_UINT16)
            u8(1)
            u16(100)
            u16(200)
            u8(PtpConstants.FORM_ENUM)
            u16(2)
            u16(100)
            u16(200)
        }.toByteArray()
        val wire = RecordingPtpWire(info, mapOf(PtpConstants.PROP_EXPOSURE_INDEX to isoPayload))
        val session = PtpSession(wire)

        val opened = session.open()
        assertEquals("D5100", opened.model)
        val iso = session.getPropertyDesc(PtpConstants.PROP_EXPOSURE_INDEX)
        assertEquals(listOf(100L, 200L), iso.enumValues)
        session.close()
        assertEquals(
            listOf(
                PtpConstants.OC_OPEN_SESSION,
                PtpConstants.OC_GET_DEVICE_INFO,
                PtpConstants.OC_GET_DEVICE_PROP_DESC,
                PtpConstants.OC_CLOSE_SESSION
            ),
            wire.codes
        )
    }

    @Test
    fun `open session accepts already-open response`() {
        val info = PtpDeviceInfo(
            standardVersion = 100,
            vendorExtensionId = 0,
            vendorExtensionVersion = 0,
            vendorExtensionDesc = "",
            functionalMode = 0,
            operations = emptyList(),
            events = emptyList(),
            properties = emptyList(),
            captureFormats = emptyList(),
            imageFormats = emptyList(),
            manufacturer = "Nikon",
            model = "D5100",
            deviceVersion = "",
            serialNumber = ""
        )
        val wire = RecordingPtpWire(
            info,
            openResponse = PtpConstants.RC_SESSION_ALREADY_OPEN
        )
        val session = PtpSession(wire)
        assertEquals("D5100", session.open().model)
    }
}

class DslrUsbTest {

    @Test
    fun `only Nikon is treated as a supported main-camera vendor for M0`() {
        assertEquals(CameraBrand.NIKON, DslrUsb.brandForVendor(PtpConstants.NIKON_VENDOR_ID))
        assertEquals(CameraBrand.CANON, DslrUsb.brandForVendor(PtpConstants.CANON_VENDOR_ID))
        assertEquals(CameraBrand.SONY, DslrUsb.brandForVendor(PtpConstants.SONY_VENDOR_ID))
        assertEquals(null, DslrUsb.brandForVendor(0x03C3))
        assertTrue(DslrUsb.isGuideForbidden(CameraBrand.NIKON))
        assertFalse(DslrUsb.isGuideForbidden(CameraBrand.ZWO))
    }
}

class PtpHelpersTest {

    @Test
    fun `jpeg extract skips a live view header`() {
        val jpeg = ByteArray(32) { 0x11 }
        jpeg[0] = 0xFF.toByte()
        jpeg[1] = 0xD8.toByte()
        jpeg[30] = 0xFF.toByte()
        jpeg[31] = 0xD9.toByte()
        val payload = ByteArray(64) + jpeg
        val extracted = PtpJpeg.extract(payload)
        assertTrue(extracted.contentEquals(jpeg))
    }

    @Test
    fun `exposure time uses microseconds when the max native value is large`() {
        val values = listOf(125L, 10_000L, 30_000_000L)
        assertEquals(10_000f, PtpExposureTime.toMicroseconds(10_000L, values), 0f)
        assertEquals(10_000L, PtpExposureTime.fromMicroseconds(9_500f, values))
    }

    @Test
    fun `exposure time scales 0_0001 second Nikon units`() {
        val values = listOf(100L, 10_000L, 300_000L)
        assertEquals(10_000f, PtpExposureTime.toMicroseconds(100L, values), 0f)
        assertEquals(30_000_000f, PtpExposureTime.toMicroseconds(300_000L, values), 0f)
        assertEquals(100L, PtpExposureTime.fromMicroseconds(10_000f, values))
    }

    @Test
    fun `nikon live view uses listed opcodes then falls back for Nikon bodies`() {
        val listed = emptyInfo().copy(
            manufacturer = "Canon",
            operations = listOf(0x9201, 0x9202, 0x9203)
        )
        assertEquals(NikonLiveViewOps(0x9201, 0x9202, 0x9203), NikonLiveView.detect(listed))

        val fallback = emptyInfo().copy(manufacturer = "Nikon Corporation", vendorExtensionId = 10)
        assertEquals(
            NikonLiveViewOps(
                PtpConstants.OC_NIKON_START_LIVE_VIEW,
                PtpConstants.OC_NIKON_END_LIVE_VIEW,
                PtpConstants.OC_NIKON_GET_LIVE_VIEW_IMAGE
            ),
            NikonLiveView.detect(fallback)
        )
        assertEquals(null, NikonLiveView.detect(emptyInfo().copy(manufacturer = "Canon")))
    }

    private fun emptyInfo() = PtpDeviceInfo(
        standardVersion = 100,
        vendorExtensionId = 0,
        vendorExtensionVersion = 0,
        vendorExtensionDesc = "",
        functionalMode = 0,
        operations = emptyList(),
        events = emptyList(),
        properties = emptyList(),
        captureFormats = emptyList(),
        imageFormats = emptyList(),
        manufacturer = "",
        model = "",
        deviceVersion = "",
        serialNumber = ""
    )
}

class PtpSessionWriteTest {

    @Test
    fun `set property value writes a typed data phase`() {
        val info = PtpDeviceInfo(
            standardVersion = 100,
            vendorExtensionId = 10,
            vendorExtensionVersion = 100,
            vendorExtensionDesc = "",
            functionalMode = 0,
            operations = emptyList(),
            events = emptyList(),
            properties = emptyList(),
            captureFormats = emptyList(),
            imageFormats = emptyList(),
            manufacturer = "Nikon",
            model = "D5100",
            deviceVersion = "",
            serialNumber = ""
        )
        val wire = RecordingPtpWire(info)
        val session = PtpSession(wire)
        session.setPropertyValue(PtpConstants.PROP_EXPOSURE_INDEX, PtpConstants.TYPE_UINT16, 400L)
        assertEquals(listOf(PtpConstants.OC_SET_DEVICE_PROP_VALUE), wire.codes)
        assertEquals(PtpConstants.PROP_EXPOSURE_INDEX.toLong(), wire.lastParams.first())
        val encoded = requireNotNull(wire.lastDataOut)
        assertEquals(2, encoded.size)
        assertEquals(400, ptpU16(encoded, 0))
        val read = session.getPropertyValue(PtpConstants.PROP_EXPOSURE_INDEX)
        assertEquals(400L, PtpPropertyDesc.decodeValue(PtpConstants.TYPE_UINT16, read.data))
    }

    @Test
    fun `object handles request all associations with zero`() {
        val info = PtpDeviceInfo(
            standardVersion = 100,
            vendorExtensionId = 0,
            vendorExtensionVersion = 0,
            vendorExtensionDesc = "",
            functionalMode = 0,
            operations = emptyList(),
            events = emptyList(),
            properties = emptyList(),
            captureFormats = emptyList(),
            imageFormats = emptyList(),
            manufacturer = "Nikon",
            model = "D5100",
            deviceVersion = "",
            serialNumber = ""
        )
        val wire = RecordingPtpWire(info)
        val handles = PtpSession(wire).objectHandles()
        assertEquals(listOf(0x12L), handles)
        assertEquals(0L, wire.lastParams[2])
    }
}

private class RecordingPtpWire(
    private val info: PtpDeviceInfo,
    private val propertyPayloads: Map<Int, ByteArray> = emptyMap(),
    private val openResponse: Int = PtpConstants.RC_OK
) : PtpWire {
    val codes = mutableListOf<Int>()
    var lastParams: LongArray = longArrayOf()
    var lastDataOut: ByteArray? = null
    private val writtenValues = mutableMapOf<Int, ByteArray>()

    override fun transact(code: Int, params: LongArray, dataOut: ByteArray?): PtpResult {
        codes += code
        lastParams = params
        lastDataOut = dataOut
        return when (code) {
            PtpConstants.OC_OPEN_SESSION -> PtpResult(openResponse)
            PtpConstants.OC_CLOSE_SESSION -> PtpResult(PtpConstants.RC_OK)
            PtpConstants.OC_GET_DEVICE_INFO -> PtpResult(PtpConstants.RC_OK, PtpDeviceInfo.encode(info))
            PtpConstants.OC_GET_DEVICE_PROP_DESC -> {
                val property = params.first().toInt() and 0xFFFF
                val payload = propertyPayloads[property] ?: return PtpResult(0x200A)
                PtpResult(PtpConstants.RC_OK, payload)
            }
            PtpConstants.OC_SET_DEVICE_PROP_VALUE -> {
                val property = params.first().toInt() and 0xFFFF
                if (dataOut != null) writtenValues[property] = dataOut
                PtpResult(PtpConstants.RC_OK)
            }
            PtpConstants.OC_GET_DEVICE_PROP_VALUE -> {
                val property = params.first().toInt() and 0xFFFF
                PtpResult(PtpConstants.RC_OK, writtenValues[property] ?: byteArrayOf(0, 0))
            }
            PtpConstants.OC_GET_OBJECT_HANDLES -> {
                val writer = PtpWriter()
                writer.u32(1)
                writer.u32(0x12)
                PtpResult(PtpConstants.RC_OK, writer.toByteArray())
            }
            PtpConstants.OC_GET_STORAGE_IDS -> {
                val writer = PtpWriter()
                writer.u32(1)
                writer.u32(0x00010001)
                PtpResult(PtpConstants.RC_OK, writer.toByteArray())
            }
            PtpConstants.OC_GET_OBJECT -> PtpResult(PtpConstants.RC_OK, byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            else -> PtpResult(0x2005)
        }
    }
}
