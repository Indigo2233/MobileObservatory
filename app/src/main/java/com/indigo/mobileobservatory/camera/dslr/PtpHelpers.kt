package com.indigo.mobileobservatory.camera.dslr

object PtpJpeg {
    fun extract(payload: ByteArray): ByteArray? {
        val start = indexOf(payload, byteArrayOf(0xFF.toByte(), 0xD8.toByte())) ?: return null
        val eoi = indexOf(payload, byteArrayOf(0xFF.toByte(), 0xD9.toByte()), start + 2)
        val end = if (eoi != null) eoi + 2 else payload.size
        if (end - start < 24) return null
        return payload.copyOfRange(start, end)
    }

    private fun indexOf(data: ByteArray, needle: ByteArray, from: Int = 0): Int? {
        if (needle.isEmpty() || data.size - from < needle.size) return null
        outer@ for (i in from..data.size - needle.size) {
            for (j in needle.indices) {
                if (data[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }
}

object PtpExposureTime {
    fun toMicroseconds(native: Long, enumValues: List<Long>): Float {
        val positive = (enumValues + native).filter { it > 0L && it != 0xFFFFFFFFL }
        val max = positive.maxOrNull() ?: return native.toFloat()
        val us = when {
            max >= 1_000_000L -> native.toFloat()
            max >= 50_000L -> native * 100f
            else -> native.toFloat()
        }
        return us.coerceAtLeast(1f)
    }

    fun fromMicroseconds(us: Float, enumValues: List<Long>): Long {
        val legal = enumValues.filter { it > 0L && it != 0xFFFFFFFFL }
        if (legal.isEmpty()) return us.toLong().coerceAtLeast(1L)
        return legal.minBy { kotlin.math.abs(toMicroseconds(it, legal) - us) }
    }
}

data class NikonLiveViewOps(val start: Int, val end: Int, val getImage: Int)

object NikonLiveView {
    fun detect(info: PtpDeviceInfo?): NikonLiveViewOps? {
        if (info == null) return null
        val start = listOf(
            PtpConstants.OC_NIKON_START_LIVE_VIEW,
            0x90C7
        ).firstOrNull { info.hasOperation(it) }
        val end = listOf(
            PtpConstants.OC_NIKON_END_LIVE_VIEW,
            0x90C8
        ).firstOrNull { info.hasOperation(it) }
        val getImage = listOf(
            PtpConstants.OC_NIKON_GET_LIVE_VIEW_IMAGE,
            0x90C4
        ).firstOrNull { info.hasOperation(it) }
        if (start != null && end != null && getImage != null) {
            return NikonLiveViewOps(start, end, getImage)
        }
        val nikon = info.manufacturer.contains("Nikon", ignoreCase = true) ||
            info.vendorExtensionId == 10L
        return if (nikon) {
            NikonLiveViewOps(
                PtpConstants.OC_NIKON_START_LIVE_VIEW,
                PtpConstants.OC_NIKON_END_LIVE_VIEW,
                PtpConstants.OC_NIKON_GET_LIVE_VIEW_IMAGE
            )
        } else {
            null
        }
    }
}
