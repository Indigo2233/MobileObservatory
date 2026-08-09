package com.indigo.mobileobservatory.accessories.oasis

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.indigo.mobileobservatory.util.FileLogger

class OasisHidTransport {
    companion object {
        private const val tag = "OasisHid"
        private const val defaultTimeoutMs = 100
        private const val longTimeoutCommand = 0x20
        private const val longTimeoutMs = 1_000
        private const val drainTimeoutMs = 10
        private const val queryAttempts = 10
        private const val hidSetReportRequestType = 0x21
        private const val hidSetReportRequest = 0x09
        private const val hidOutputReportValue = 0x0200
        private const val hidGetReportRequestType = 0xA1
        private const val hidGetReportRequest = 0x01
        private const val hidInputReportValue = 0x0100
    }

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null
    private var inputEndpoint: UsbEndpoint? = null
    private var outputEndpoint: UsbEndpoint? = null

    @Synchronized
    fun open(context: Context, device: UsbDevice): Boolean {
        close()
        FileLogger.i(
            tag,
            "Opening VID=0x${device.vendorId.toString(16)} PID=0x${device.productId.toString(16)} interfaces=${device.interfaceCount}"
        )
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val newConnection = usbManager.openDevice(device) ?: run {
            FileLogger.w(tag, "UsbManager.openDevice returned null")
            return false
        }
        val usbInterface = findInterface(device)
        if (usbInterface == null || !newConnection.claimInterface(usbInterface, true)) {
            FileLogger.w(tag, "HID interface unavailable or claim failed")
            newConnection.close()
            return false
        }
        claimedInterface = usbInterface
        inputEndpoint = endpointFor(usbInterface, UsbConstants.USB_DIR_IN)
        outputEndpoint = endpointFor(usbInterface, UsbConstants.USB_DIR_OUT)
        if (inputEndpoint == null) {
            FileLogger.w(tag, "HID input endpoint missing")
            newConnection.releaseInterface(usbInterface)
            newConnection.close()
            claimedInterface = null
            return false
        }
        connection = newConnection
        FileLogger.i(
            tag,
            "Opened interface=${usbInterface.id} class=${usbInterface.interfaceClass} in=${endpointDescription(inputEndpoint)} out=${endpointDescription(outputEndpoint)}"
        )
        return true
    }

    @Synchronized
    fun query(
        command: Int,
        payload: ByteArray = byteArrayOf()
    ): OasisHidMessage? = exchange(command, payload)

    @Synchronized
    fun command(command: Int, payload: ByteArray = byteArrayOf()): Boolean {
        val response = exchange(command, payload) ?: return false
        val result = response.payload.firstOrNull()?.toInt()?.and(0xFF)
        if (result != 0) {
            FileLogger.w(tag, "Command 0x${command.toString(16)} rejected result=$result")
            return false
        }
        return true
    }

    @Synchronized
    fun close() {
        val activeConnection = connection
        val activeInterface = claimedInterface
        connection = null
        claimedInterface = null
        inputEndpoint = null
        outputEndpoint = null
        if (activeConnection != null) {
            if (activeInterface != null) {
                runCatching { activeConnection.releaseInterface(activeInterface) }
                    .onFailure { FileLogger.w(tag, "HID interface release failed: ${it.message}") }
            }
            runCatching { activeConnection.close() }
                .onFailure { FileLogger.w(tag, "USB connection close failed: ${it.message}") }
        }
    }

    private fun exchange(command: Int, payload: ByteArray): OasisHidMessage? {
        repeat(queryAttempts) { attempt ->
            drainInput()
            val writeMode = if (attempt == 3 || attempt == 6) {
                WriteMode.CONTROL
            } else {
                WriteMode.ENDPOINT
            }
            if (!write(OasisHidProtocol.encode(command, payload), writeMode)) {
                FileLogger.w(tag, "Write failed command=0x${command.toString(16)} attempt=${attempt + 1}")
                Thread.sleep(10)
                return@repeat
            }
            val report = ByteArray(OasisHidProtocol.reportLength)
            val received = read(report, responseTimeoutMs(command), writeMode)
            if (received > 0) {
                val message = OasisHidProtocol.decode(report, received)
                if (message?.command == command) return message
                FileLogger.w(
                    tag,
                    "Unexpected response command=${message?.command} length=${message?.payload?.size} expected=0x${command.toString(16)} attempt=${attempt + 1}"
                )
            } else {
                FileLogger.w(tag, "Read timed out command=0x${command.toString(16)} attempt=${attempt + 1}")
            }
            Thread.sleep(10)
        }
        return null
    }

    private fun read(report: ByteArray, timeoutMs: Int, writeMode: WriteMode): Int {
        val activeConnection = connection ?: return -1
        val endpoint = inputEndpoint ?: return -1
        val endpointLength = minOf(report.size, endpoint.maxPacketSize)
        val endpointReceived = activeConnection.bulkTransfer(
            endpoint,
            report,
            endpointLength,
            timeoutMs
        )
        if (endpointReceived > 0 || writeMode != WriteMode.CONTROL) {
            return endpointReceived
        }
        val interfaceId = claimedInterface?.id ?: return endpointReceived
        val controlReport = ByteArray(endpointLength)
        val controlReceived = activeConnection.controlTransfer(
            hidGetReportRequestType,
            hidGetReportRequest,
            hidInputReportValue,
            interfaceId,
            controlReport,
            controlReport.size,
            timeoutMs
        )
        if (controlReceived > 0) {
            controlReport.copyInto(report, endIndex = controlReceived)
        }
        return controlReceived
    }

    private fun drainInput() {
        val endpoint = inputEndpoint ?: return
        val report = ByteArray(OasisHidProtocol.reportLength)
        var drained = 0
        while (drained < queryAttempts) {
            val count = connection?.bulkTransfer(endpoint, report, report.size, drainTimeoutMs) ?: -1
            if (count <= 0) break
            drained++
        }
        if (drained > 0) FileLogger.i(tag, "Discarded $drained stale HID report(s)")
    }

    private fun write(report: ByteArray, mode: WriteMode): Boolean {
        val activeConnection = connection ?: return false
        val endpoint = outputEndpoint
        val transferReport: ByteArray
        val written = if (mode == WriteMode.ENDPOINT && endpoint != null) {
            val endpointReport = OasisHidProtocol.endpointReport(report, endpoint.maxPacketSize)
            transferReport = endpointReport
            activeConnection.bulkTransfer(
                endpoint,
                endpointReport,
                endpointReport.size,
                defaultTimeoutMs
            )
        } else {
            val interfaceId = claimedInterface?.id ?: return false
            val controlReport = OasisHidProtocol.endpointReport(
                report,
                OasisHidProtocol.reportLength - 1
            )
            transferReport = controlReport
            activeConnection.controlTransfer(
                hidSetReportRequestType,
                hidSetReportRequest,
                hidOutputReportValue,
                interfaceId,
                controlReport,
                controlReport.size,
                defaultTimeoutMs
            )
        }
        val expectedLength = transferReport.size
        if (written != expectedLength) {
            FileLogger.w(tag, "Short HID write mode=$mode: $written/$expectedLength")
            return false
        }
        return true
    }

    private fun responseTimeoutMs(command: Int): Int {
        return if (command == longTimeoutCommand) longTimeoutMs else defaultTimeoutMs
    }

    private fun findInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val candidate = device.getInterface(index)
            if (candidate.interfaceClass == UsbConstants.USB_CLASS_HID &&
                endpointFor(candidate, UsbConstants.USB_DIR_IN) != null
            ) {
                return candidate
            }
        }
        return null
    }

    private fun endpointFor(usbInterface: UsbInterface, direction: Int): UsbEndpoint? {
        for (index in 0 until usbInterface.endpointCount) {
            val endpoint = usbInterface.getEndpoint(index)
            if (endpoint.direction == direction &&
                (endpoint.type == UsbConstants.USB_ENDPOINT_XFER_INT ||
                    endpoint.type == UsbConstants.USB_ENDPOINT_XFER_BULK)
            ) {
                return endpoint
            }
        }
        return null
    }

    private fun endpointDescription(endpoint: UsbEndpoint?): String = endpoint?.let {
        "0x${it.address.toString(16)} type=${it.type} maxPacket=${it.maxPacketSize} interval=${it.interval}"
    } ?: "none"

    private enum class WriteMode {
        ENDPOINT,
        CONTROL
    }
}
