package com.indigo.mobileobservatory.camera.dslr

data class PtpResult(
    val responseCode: Int,
    val data: ByteArray = ByteArray(0)
) {
    val ok: Boolean get() = responseCode == PtpConstants.RC_OK

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PtpResult) return false
        return responseCode == other.responseCode && data.contentEquals(other.data)
    }

    override fun hashCode(): Int = 31 * responseCode + data.contentHashCode()
}

interface PtpWire {
    fun transact(code: Int, params: LongArray = longArrayOf(), dataOut: ByteArray? = null): PtpResult
}

class PtpException(message: String, val responseCode: Int? = null) : RuntimeException(message)

class PtpSession(
    private val wire: PtpWire,
    private val sessionId: Long = 1L
) {
    var deviceInfo: PtpDeviceInfo? = null
        private set

    fun open(): PtpDeviceInfo {
        val opened = wire.transact(PtpConstants.OC_OPEN_SESSION, longArrayOf(sessionId))
        if (!opened.ok && opened.responseCode != PtpConstants.RC_SESSION_NOT_OPEN) {
            // Some bodies return SessionAlreadyOpen; still try GetDeviceInfo.
            if (opened.responseCode != PtpConstants.RC_SESSION_ALREADY_OPEN) {
                throw PtpException(
                    "OpenSession failed: 0x${opened.responseCode.toString(16)}",
                    opened.responseCode
                )
            }
        }
        val infoResult = wire.transact(PtpConstants.OC_GET_DEVICE_INFO)
        if (!infoResult.ok) {
            throw PtpException(
                "GetDeviceInfo failed: 0x${infoResult.responseCode.toString(16)}",
                infoResult.responseCode
            )
        }
        return PtpDeviceInfo.parse(infoResult.data).also { deviceInfo = it }
    }

    fun close() {
        runCatching { wire.transact(PtpConstants.OC_CLOSE_SESSION) }
        deviceInfo = null
    }

    fun getPropertyDesc(propertyCode: Int): PtpPropertyDesc {
        val result = wire.transact(
            PtpConstants.OC_GET_DEVICE_PROP_DESC,
            longArrayOf(propertyCode.toLong() and 0xFFFF)
        )
        if (!result.ok) {
            throw PtpException(
                "GetDevicePropDesc 0x${propertyCode.toString(16)} failed: 0x${result.responseCode.toString(16)}",
                result.responseCode
            )
        }
        return PtpPropertyDesc.parse(result.data)
    }

    fun getPropertyValue(propertyCode: Int): PtpResult {
        return requireOk(
            wire.transact(
                PtpConstants.OC_GET_DEVICE_PROP_VALUE,
                longArrayOf(propertyCode.toLong() and 0xFFFF)
            ),
            "GetDevicePropValue 0x${propertyCode.toString(16)}"
        )
    }

    fun setPropertyValue(propertyCode: Int, dataType: Int, value: Long): PtpResult {
        return requireOk(
            wire.transact(
                PtpConstants.OC_SET_DEVICE_PROP_VALUE,
                longArrayOf(propertyCode.toLong() and 0xFFFF),
                PtpPropertyDesc.encodeValue(dataType, value)
            ),
            "SetDevicePropValue 0x${propertyCode.toString(16)}"
        )
    }

    fun operation(code: Int, params: LongArray = longArrayOf(), dataOut: ByteArray? = null): PtpResult =
        wire.transact(code, params, dataOut)

    fun storageIds(): List<Long> = u32List(requireOk(wire.transact(PtpConstants.OC_GET_STORAGE_IDS), "GetStorageIDs").data)

    fun objectHandles(storageId: Long = 0xFFFFFFFFL, format: Int = 0): List<Long> =
        u32List(
            requireOk(
                wire.transact(
                    PtpConstants.OC_GET_OBJECT_HANDLES,
                    longArrayOf(storageId, format.toLong() and 0xFFFF, 0L)
                ),
                "GetObjectHandles"
            ).data
        )

    fun allObjectHandles(): List<Long> {
        val combined = runCatching { objectHandles() }.getOrNull()
        if (!combined.isNullOrEmpty()) return combined
        return storageIds().flatMap { id ->
            runCatching { objectHandles(id) }.getOrDefault(emptyList())
        }
    }

    fun getObject(handle: Long): ByteArray {
        val result = when (wire) {
            is PtpTransport -> wire.transact(
                PtpConstants.OC_GET_OBJECT,
                longArrayOf(handle and 0xFFFFFFFFL),
                null,
                20_000
            )
            else -> wire.transact(PtpConstants.OC_GET_OBJECT, longArrayOf(handle and 0xFFFFFFFFL))
        }
        return requireOk(result, "GetObject").data
    }

    private fun requireOk(result: PtpResult, label: String): PtpResult {
        if (!result.ok) {
            throw PtpException("$label failed: 0x${result.responseCode.toString(16)}", result.responseCode)
        }
        return result
    }

    private fun u32List(payload: ByteArray): List<Long> {
        val reader = PtpReader(payload)
        val count = reader.u32().toInt().coerceAtLeast(0)
        return List(count) { reader.u32() }
    }
}
