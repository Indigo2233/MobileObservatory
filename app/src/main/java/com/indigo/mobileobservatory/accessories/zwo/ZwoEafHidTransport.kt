package com.indigo.mobileobservatory.accessories.zwo

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.indigo.mobileobservatory.util.FileLogger

class ZwoEafHidTransport {
    companion object {
        private const val tag = "ZWO-EAF-HID"
        private const val hidSetReportRequestType = 0x21
        private const val hidSetReportRequest = 0x09
        private const val hidGetReportRequestType = 0xA1
        private const val hidGetReportRequest = 0x01
        private const val outputReportValue = 0x0200 or ZwoEafProtocol.outputReportId
        private const val inputReportValue = 0x0100 or ZwoEafProtocol.inputReportId
        private const val transferTimeoutMs = 250
        private const val readAttempts = 10
    }

    private var connection: UsbDeviceConnection? = null
    private var claimedInterface: UsbInterface? = null

    @Synchronized
    fun open(context: Context, device: UsbDevice): Boolean {
        close()
        if (!ZwoEafUsbIds.isEaf(device.vendorId, device.productId)) return false
        FileLogger.i(
            tag,
            "Opening VID=0x${device.vendorId.toString(16)} " +
                "PID=0x${device.productId.toString(16)} interfaces=${device.interfaceCount}"
        )
        logInterfaces(device)
        val usbInterface = findHidInterface(device) ?: run {
            FileLogger.w(tag, "HID interface missing")
            return false
        }
        val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
        val newConnection = usbManager.openDevice(device) ?: run {
            FileLogger.w(tag, "UsbManager.openDevice returned null permission=${usbManager.hasPermission(device)}")
            return false
        }
        if (!newConnection.claimInterface(usbInterface, true)) {
            FileLogger.w(tag, "claimInterface failed interface=${usbInterface.id}")
            newConnection.close()
            return false
        }
        connection = newConnection
        claimedInterface = usbInterface
        FileLogger.i(tag, "Opened interface=${usbInterface.id}")
        return true
    }

    @Synchronized
    fun query(selector: Int): ByteArray? {
        val query = ZwoEafProtocol.query(selector)
        repeat(3) { queryAttempt ->
            if (!writeOnce(query, "query selector=0x${selector.toString(16)}")) {
                return null
            }
            repeat(readAttempts) { readAttempt ->
                val reply = readInput()
                if (reply != null && ZwoEafProtocol.isReply(reply, selector)) {
                    FileLogger.d(tag, "RX selector=0x${selector.toString(16)} ${hex(reply)}")
                    return reply
                }
                if (reply != null) {
                    FileLogger.w(
                        tag,
                        "Stale HID reply selector=0x${selector.toString(16)} " +
                            "attempt=${readAttempt + 1} ${hex(reply)}"
                    )
                }
                Thread.sleep(10)
            }
            FileLogger.w(tag, "Query timed out selector=0x${selector.toString(16)} attempt=${queryAttempt + 1}")
        }
        return null
    }

    /** Sends a state-changing packet exactly once. */
    @Synchronized
    fun command(report: ByteArray, description: String): Boolean =
        writeOnce(report, description)

    @Synchronized
    fun close() {
        val activeConnection = connection
        val activeInterface = claimedInterface
        connection = null
        claimedInterface = null
        if (activeConnection != null) {
            if (activeInterface != null) {
                runCatching { activeConnection.releaseInterface(activeInterface) }
                    .onFailure { FileLogger.w(tag, "releaseInterface failed: ${it.message}") }
            }
            runCatching { activeConnection.close() }
                .onFailure { FileLogger.w(tag, "USB close failed: ${it.message}") }
        }
    }

    private fun writeOnce(report: ByteArray, description: String): Boolean {
        if (report.size != ZwoEafProtocol.reportLength ||
            report.firstOrNull()?.toInt()?.and(0xFF) != ZwoEafProtocol.outputReportId
        ) {
            FileLogger.w(tag, "Rejected invalid output report for $description")
            return false
        }
        val activeConnection = connection ?: return false
        val interfaceId = claimedInterface?.id ?: return false
        val written = activeConnection.controlTransfer(
            hidSetReportRequestType,
            hidSetReportRequest,
            outputReportValue,
            interfaceId,
            report,
            report.size,
            transferTimeoutMs
        )
        FileLogger.d(tag, "TX $description result=$written ${hex(report)}")
        if (written != report.size) {
            FileLogger.w(tag, "Short HID output report for $description: $written/${report.size}")
            return false
        }
        return true
    }

    private fun readInput(): ByteArray? {
        val activeConnection = connection ?: return null
        val interfaceId = claimedInterface?.id ?: return null
        val report = ByteArray(ZwoEafProtocol.reportLength)
        report[0] = ZwoEafProtocol.inputReportId.toByte()
        val received = activeConnection.controlTransfer(
            hidGetReportRequestType,
            hidGetReportRequest,
            inputReportValue,
            interfaceId,
            report,
            report.size,
            transferTimeoutMs
        )
        if (received < ZwoEafProtocol.reportLength) {
            if (received >= 0) FileLogger.w(tag, "Short HID input report: $received/${report.size}")
            return null
        }
        return report
    }

    private fun findHidInterface(device: UsbDevice): UsbInterface? {
        for (index in 0 until device.interfaceCount) {
            val candidate = device.getInterface(index)
            if (candidate.interfaceClass == UsbConstants.USB_CLASS_HID) return candidate
        }
        return null
    }

    private fun logInterfaces(device: UsbDevice) {
        for (index in 0 until device.interfaceCount) {
            val usbInterface = device.getInterface(index)
            val endpoints = (0 until usbInterface.endpointCount).joinToString { endpointIndex ->
                val endpoint = usbInterface.getEndpoint(endpointIndex)
                "0x${endpoint.address.toString(16)}/type=${endpoint.type}/max=${endpoint.maxPacketSize}"
            }
            FileLogger.i(
                tag,
                "Interface[$index] id=${usbInterface.id} class=${usbInterface.interfaceClass} " +
                    "subclass=${usbInterface.interfaceSubclass} protocol=${usbInterface.interfaceProtocol} " +
                    "endpoints=[$endpoints]"
            )
        }
    }

    private fun hex(report: ByteArray): String =
        report.joinToString(separator = " ") { "%02X".format(it.toInt() and 0xFF) }
}
