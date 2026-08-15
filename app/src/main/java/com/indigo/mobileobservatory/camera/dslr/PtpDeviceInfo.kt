package com.indigo.mobileobservatory.camera.dslr

data class PtpDeviceInfo(
    val standardVersion: Int,
    val vendorExtensionId: Long,
    val vendorExtensionVersion: Int,
    val vendorExtensionDesc: String,
    val functionalMode: Int,
    val operations: List<Int>,
    val events: List<Int>,
    val properties: List<Int>,
    val captureFormats: List<Int>,
    val imageFormats: List<Int>,
    val manufacturer: String,
    val model: String,
    val deviceVersion: String,
    val serialNumber: String
) {
    fun hasOperation(code: Int): Boolean = operations.any { it == code }
    fun hasProperty(code: Int): Boolean = properties.any { it == code }

    companion object {
        fun parse(payload: ByteArray): PtpDeviceInfo {
            val reader = PtpReader(payload)
            return PtpDeviceInfo(
                standardVersion = reader.u16(),
                vendorExtensionId = reader.u32(),
                vendorExtensionVersion = reader.u16(),
                vendorExtensionDesc = reader.ptpString(),
                functionalMode = reader.u16(),
                operations = reader.u16Array(),
                events = reader.u16Array(),
                properties = reader.u16Array(),
                captureFormats = reader.u16Array(),
                imageFormats = reader.u16Array(),
                manufacturer = reader.ptpString(),
                model = reader.ptpString(),
                deviceVersion = reader.ptpString(),
                serialNumber = reader.ptpString()
            )
        }

        fun encode(info: PtpDeviceInfo): ByteArray {
            val writer = PtpWriter()
            writer.u16(info.standardVersion)
            writer.u32(info.vendorExtensionId)
            writer.u16(info.vendorExtensionVersion)
            writer.ptpString(info.vendorExtensionDesc)
            writer.u16(info.functionalMode)
            writer.u16Array(info.operations)
            writer.u16Array(info.events)
            writer.u16Array(info.properties)
            writer.u16Array(info.captureFormats)
            writer.u16Array(info.imageFormats)
            writer.ptpString(info.manufacturer)
            writer.ptpString(info.model)
            writer.ptpString(info.deviceVersion)
            writer.ptpString(info.serialNumber)
            return writer.toByteArray()
        }
    }
}

data class PtpPropertyDesc(
    val code: Int,
    val dataType: Int,
    val writable: Boolean,
    val defaultValue: Long,
    val currentValue: Long,
    val enumValues: List<Long> = emptyList(),
    val rangeMin: Long? = null,
    val rangeMax: Long? = null,
    val rangeStep: Long? = null
) {
    companion object {
        fun parse(payload: ByteArray): PtpPropertyDesc {
            val reader = PtpReader(payload)
            val code = reader.u16()
            val dataType = reader.u16()
            val writable = reader.u8() != 0
            val defaultValue = readValue(reader, dataType)
            val currentValue = readValue(reader, dataType)
            val form = if (reader.remaining() > 0) reader.u8() else PtpConstants.FORM_NONE
            var enumValues = emptyList<Long>()
            var rangeMin: Long? = null
            var rangeMax: Long? = null
            var rangeStep: Long? = null
            when (form) {
                PtpConstants.FORM_RANGE -> {
                    rangeMin = readValue(reader, dataType)
                    rangeMax = readValue(reader, dataType)
                    rangeStep = readValue(reader, dataType)
                }
                PtpConstants.FORM_ENUM -> {
                    val count = reader.u16()
                    enumValues = List(count) { readValue(reader, dataType) }
                }
            }
            return PtpPropertyDesc(
                code = code,
                dataType = dataType,
                writable = writable,
                defaultValue = defaultValue,
                currentValue = currentValue,
                enumValues = enumValues,
                rangeMin = rangeMin,
                rangeMax = rangeMax,
                rangeStep = rangeStep
            )
        }

        private fun readValue(reader: PtpReader, dataType: Int): Long = when (dataType) {
            PtpConstants.TYPE_INT8 -> reader.u8().toByte().toLong()
            PtpConstants.TYPE_UINT8 -> reader.u8().toLong()
            PtpConstants.TYPE_INT16 -> reader.u16().toShort().toLong()
            PtpConstants.TYPE_UINT16 -> reader.u16().toLong()
            PtpConstants.TYPE_INT32 -> reader.u32().toInt().toLong()
            PtpConstants.TYPE_UINT32 -> reader.u32()
            else -> error("Unsupported PTP data type 0x${dataType.toString(16)}")
        }

        fun encodeValue(dataType: Int, value: Long): ByteArray {
            val writer = PtpWriter()
            when (dataType) {
                PtpConstants.TYPE_INT8, PtpConstants.TYPE_UINT8 -> writer.u8(value.toInt())
                PtpConstants.TYPE_INT16, PtpConstants.TYPE_UINT16 -> writer.u16(value.toInt())
                PtpConstants.TYPE_INT32, PtpConstants.TYPE_UINT32 -> writer.u32(value)
                else -> error("Unsupported PTP data type 0x${dataType.toString(16)}")
            }
            return writer.toByteArray()
        }

        fun decodeValue(dataType: Int, payload: ByteArray): Long =
            readValue(PtpReader(payload), dataType)
    }
}
