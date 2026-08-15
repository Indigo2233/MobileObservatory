package com.indigo.mobileobservatory.camera.dslr

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import com.indigo.mobileobservatory.camera.CameraBrand
import com.indigo.mobileobservatory.util.FileLogger

data class PtpEndpoints(
    val usbInterface: UsbInterface,
    val bulkOut: UsbEndpoint,
    val bulkIn: UsbEndpoint,
    val interruptIn: UsbEndpoint?
)

object DslrUsb {
    private const val TAG = "DslrUsb"

    fun brandForVendor(vendorId: Int): CameraBrand? = when (vendorId) {
        PtpConstants.NIKON_VENDOR_ID -> CameraBrand.NIKON
        PtpConstants.CANON_VENDOR_ID -> CameraBrand.CANON
        PtpConstants.SONY_VENDOR_ID -> CameraBrand.SONY
        else -> null
    }

    fun isGuideForbidden(brand: CameraBrand): Boolean = when (brand) {
        CameraBrand.NIKON, CameraBrand.CANON, CameraBrand.SONY -> true
        else -> false
    }

    fun findPtpInterface(device: UsbDevice): PtpEndpoints? {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            if (usbInterface.interfaceClass != PtpConstants.USB_CLASS_STILL_IMAGE) continue
            if (usbInterface.interfaceSubclass != PtpConstants.USB_STILL_IMAGE_SUBCLASS) continue
            if (usbInterface.interfaceProtocol != PtpConstants.USB_STILL_IMAGE_PROTOCOL) continue
            var bulkOut: UsbEndpoint? = null
            var bulkIn: UsbEndpoint? = null
            var interruptIn: UsbEndpoint? = null
            for (epIndex in 0 until usbInterface.endpointCount) {
                val endpoint = usbInterface.getEndpoint(epIndex)
                when {
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_OUT -> bulkOut = endpoint
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK &&
                        endpoint.direction == UsbConstants.USB_DIR_IN -> bulkIn = endpoint
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT &&
                        endpoint.direction == UsbConstants.USB_DIR_IN -> interruptIn = endpoint
                }
            }
            if (bulkOut != null && bulkIn != null) {
                return PtpEndpoints(usbInterface, bulkOut, bulkIn, interruptIn)
            }
        }
        FileLogger.w(
            TAG,
            "No PTP still-image interface on VID=0x${device.vendorId.toString(16)} " +
                "PID=0x${device.productId.toString(16)}"
        )
        return null
    }

    fun isSupportedMainCamera(device: UsbDevice): Boolean {
        val brand = brandForVendor(device.vendorId) ?: return false
        if (brand != CameraBrand.NIKON) return false
        return findPtpInterface(device) != null
    }
}

class PtpTransport(
    private val connection: UsbDeviceConnection,
    private val endpoints: PtpEndpoints,
    private val timeoutMs: Int = 5_000
) : PtpWire {
    private val lock = Any()
    private var nextTransactionId = 1

    fun claim() {
        if (!connection.claimInterface(endpoints.usbInterface, true)) {
            throw PtpException("Failed to claim PTP interface ${endpoints.usbInterface.id}")
        }
    }

    fun release() {
        runCatching { connection.releaseInterface(endpoints.usbInterface) }
    }

    override fun transact(code: Int, params: LongArray, dataOut: ByteArray?): PtpResult =
        transact(code, params, dataOut, timeoutMs)

    fun transact(code: Int, params: LongArray, dataOut: ByteArray?, timeoutMs: Int): PtpResult {
        synchronized(lock) {
            val transactionId = nextTransactionId++
            writeFully(PtpContainer.command(code, transactionId, params).toBytes(), timeoutMs)
            if (dataOut != null) {
                writeFully(PtpContainer.data(code, transactionId, dataOut).toBytes(), timeoutMs)
            }
            val first = readContainerSkippingEvents(timeoutMs)
            return when (first.type) {
                PtpConstants.CONTAINER_DATA -> {
                    val response = readContainerSkippingEvents(timeoutMs)
                    if (response.type != PtpConstants.CONTAINER_RESPONSE) {
                        throw PtpException("Expected PTP response after data, got type ${response.type}")
                    }
                    PtpResult(response.code, first.payload)
                }
                PtpConstants.CONTAINER_RESPONSE -> PtpResult(first.code, ByteArray(0))
                else -> throw PtpException("Unexpected PTP container type ${first.type} for 0x${code.toString(16)}")
            }
        }
    }

    private fun writeFully(bytes: ByteArray, timeoutMs: Int) {
        var offset = 0
        while (offset < bytes.size) {
            val remaining = bytes.size - offset
            val written = connection.bulkTransfer(
                endpoints.bulkOut,
                bytes,
                offset,
                remaining,
                timeoutMs
            )
            if (written <= 0) throw PtpException("PTP bulk OUT failed: $written")
            offset += written
        }
    }

    private fun readContainerSkippingEvents(timeoutMs: Int): PtpContainer {
        repeat(8) {
            val container = readContainer(timeoutMs)
            if (container.type != PtpConstants.CONTAINER_EVENT) return container
        }
        throw PtpException("PTP bulk IN filled with events")
    }

    private fun readContainer(timeoutMs: Int): PtpContainer {
        val header = ByteArray(PtpConstants.HEADER_SIZE)
        readFully(header, PtpConstants.HEADER_SIZE, timeoutMs = timeoutMs)
        val length = ptpU32(header, 0).toInt()
        if (length < PtpConstants.HEADER_SIZE || length > 24_000_000) {
            throw PtpException("PTP container length $length")
        }
        if (length == PtpConstants.HEADER_SIZE) return PtpContainer.parse(header)
        val full = ByteArray(length)
        header.copyInto(full)
        readFully(full, length, already = PtpConstants.HEADER_SIZE, timeoutMs = timeoutMs)
        return PtpContainer.parse(full)
    }

    private fun readFully(buffer: ByteArray, length: Int, already: Int = 0, timeoutMs: Int) {
        var offset = already
        while (offset < length) {
            val sliceLength = length - offset
            val read = connection.bulkTransfer(
                endpoints.bulkIn,
                buffer,
                offset,
                sliceLength,
                timeoutMs
            )
            if (read <= 0) throw PtpException("PTP bulk IN failed: $read at $offset/$length")
            offset += read
        }
    }
}
