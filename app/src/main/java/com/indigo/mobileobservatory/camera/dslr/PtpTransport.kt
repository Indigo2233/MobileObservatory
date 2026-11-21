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

internal class PtpTransactionCounter(private var nextValue: Int = 0) {
    fun next(): Int = nextValue++

    fun reset() {
        nextValue = 0
    }
}

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
    private val timeoutMs: Int = 10_000
) : PtpWire {
    private val lock = Any()
    private val transactionIds = PtpTransactionCounter()

    fun claim() {
        if (!connection.claimInterface(endpoints.usbInterface, true)) {
            throw PtpException("Failed to claim PTP interface ${endpoints.usbInterface.id}")
        }
    }

    fun release() {
        runCatching { connection.releaseInterface(endpoints.usbInterface) }
    }

    fun prepareNikonSession() {
        val resetResult = runCatching {
            connection.controlTransfer(
                PTP_CONTROL_REQUEST_TYPE_CLASS_OUT,
                PTP_CLASS_REQUEST_DEVICE_RESET,
                0,
                0,
                null,
                0,
                timeoutMs
            )
        }.getOrElse { result ->
            FileLogger.w(TAG, "PTP device reset failed: ${result.message}")
            -1
        }
        FileLogger.i(TAG, "PTP device reset result=$resetResult")
        if (resetResult >= 0) {
            try {
                Thread.sleep(NIKON_RESET_SETTLE_MS)
            } catch (error: InterruptedException) {
                Thread.currentThread().interrupt()
                throw PtpException("PTP reset interrupted")
            }
        }
        clearEndpointHalt(endpoints.bulkIn, "IN")
        clearEndpointHalt(endpoints.bulkOut, "OUT")
        drainInput()
    }

    override fun beginSession() {
        transactionIds.reset()
    }

    override fun transact(code: Int, params: LongArray, dataOut: ByteArray?): PtpResult =
        transact(code, params, dataOut, timeoutMs)

    fun transact(code: Int, params: LongArray, dataOut: ByteArray?, timeoutMs: Int): PtpResult {
        synchronized(lock) {
            val transactionId = transactionIds.next()
            val operation = "0x${code.toString(16).padStart(4, '0')} tx=$transactionId"
            writeFully(PtpContainer.command(code, transactionId, params).toBytes(), timeoutMs, operation)
            if (dataOut != null) {
                writeFully(PtpContainer.data(code, transactionId, dataOut).toBytes(), timeoutMs, operation)
            }
            val first = readContainerSkippingEvents(timeoutMs, operation)
            return when (first.type) {
                PtpConstants.CONTAINER_DATA -> {
                    val response = readContainerSkippingEvents(timeoutMs, operation)
                    if (response.type != PtpConstants.CONTAINER_RESPONSE) {
                        throw PtpException(
                            "$operation expected PTP response after data, got type ${response.type}"
                        )
                    }
                    PtpResult(response.code, first.payload)
                }
                PtpConstants.CONTAINER_RESPONSE -> PtpResult(first.code, ByteArray(0))
                else -> throw PtpException("$operation unexpected PTP container type ${first.type}")
            }
        }
    }

    private fun writeFully(bytes: ByteArray, timeoutMs: Int, operation: String) {
        var offset = 0
        var recoveryAttempted = false
        while (offset < bytes.size) {
            val remaining = bytes.size - offset
            val written = connection.bulkTransfer(
                endpoints.bulkOut,
                bytes,
                offset,
                remaining,
                timeoutMs
            )
            if (written <= 0) {
                if (!recoveryAttempted) {
                    recoveryAttempted = true
                    clearEndpointHalt(endpoints.bulkOut, "OUT")
                    continue
                }
                throw PtpException("$operation PTP bulk OUT failed: $written")
            }
            offset += written
        }
    }

    private fun readContainerSkippingEvents(timeoutMs: Int, operation: String): PtpContainer {
        repeat(8) {
            val container = readContainer(timeoutMs, operation)
            if (container.type != PtpConstants.CONTAINER_EVENT) return container
        }
        throw PtpException("$operation PTP bulk IN filled with events")
    }

    private fun readContainer(timeoutMs: Int, operation: String): PtpContainer {
        val header = ByteArray(PtpConstants.HEADER_SIZE)
        readFully(header, PtpConstants.HEADER_SIZE, timeoutMs = timeoutMs, operation = operation)
        val length = ptpU32(header, 0).toInt()
        if (length < PtpConstants.HEADER_SIZE || length > 24_000_000) {
            throw PtpException("$operation PTP container length $length")
        }
        if (length == PtpConstants.HEADER_SIZE) return PtpContainer.parse(header)
        val full = ByteArray(length)
        header.copyInto(full)
        readFully(
            full,
            length,
            already = PtpConstants.HEADER_SIZE,
            timeoutMs = timeoutMs,
            operation = operation
        )
        return PtpContainer.parse(full)
    }

    private fun readFully(
        buffer: ByteArray,
        length: Int,
        already: Int = 0,
        timeoutMs: Int,
        operation: String
    ) {
        var offset = already
        var recoveryAttempted = false
        while (offset < length) {
            val sliceLength = length - offset
            val read = connection.bulkTransfer(
                endpoints.bulkIn,
                buffer,
                offset,
                sliceLength,
                timeoutMs
            )
            if (read <= 0) {
                if (!recoveryAttempted) {
                    recoveryAttempted = true
                    clearEndpointHalt(endpoints.bulkIn, "IN")
                    drainInput()
                    continue
                }
                throw PtpException("$operation PTP bulk IN failed: $read at $offset/$length")
            }
            offset += read
        }
    }

    private fun clearEndpointHalt(endpoint: UsbEndpoint, direction: String): Boolean {
        val result = runCatching {
            connection.controlTransfer(
                USB_CONTROL_REQUEST_TYPE_STANDARD_OUT,
                USB_STANDARD_REQUEST_CLEAR_FEATURE,
                USB_FEATURE_ENDPOINT_HALT,
                endpoint.address,
                null,
                0,
                timeoutMs
            )
        }.getOrElse { -1 }
        FileLogger.i(
            TAG,
            "PTP bulk $direction clear halt address=0x${endpoint.address.toString(16)} result=$result"
        )
        return result >= 0
    }

    private fun drainInput() {
        val buffer = ByteArray(endpoints.bulkIn.maxPacketSize.coerceAtLeast(64))
        var total = 0
        repeat(MAX_DRAIN_PACKETS) {
            val read = runCatching {
                connection.bulkTransfer(
                    endpoints.bulkIn,
                    buffer,
                    buffer.size,
                    DRAIN_TIMEOUT_MS
                )
            }.getOrDefault(-1)
            if (read <= 0) {
                if (total > 0) FileLogger.i(TAG, "PTP bulk IN drained $total stale bytes")
                return
            }
            total += read
        }
        FileLogger.w(TAG, "PTP bulk IN drain reached packet limit after $total bytes")
    }

    companion object {
        private const val TAG = "PtpTransport"
        private const val PTP_CONTROL_REQUEST_TYPE_CLASS_OUT = 0x21
        private const val PTP_CLASS_REQUEST_DEVICE_RESET = 0x66
        private const val USB_CONTROL_REQUEST_TYPE_STANDARD_OUT = 0x02
        private const val USB_STANDARD_REQUEST_CLEAR_FEATURE = 0x01
        private const val USB_FEATURE_ENDPOINT_HALT = 0x00
        private const val NIKON_RESET_SETTLE_MS = 2_500L
        private const val MAX_DRAIN_PACKETS = 8
        private const val DRAIN_TIMEOUT_MS = 100
    }
}
