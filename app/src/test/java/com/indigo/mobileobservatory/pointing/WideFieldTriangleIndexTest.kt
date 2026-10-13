package com.indigo.mobileobservatory.pointing

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WideFieldTriangleIndexTest {
    private val stars = listOf(40.0 to 20.0, 30.0 to 25.0, 50.0 to 16.0, 35.0 to 10.0, 47.0 to 29.0, 55.0 to 23.0)
        .mapIndexed { i, p -> PhoneCatalogStar(p.first, p.second, 1.0 + i * .2, i, null) }

    private fun encoded(bucket: FovBucket, input: List<PhoneCatalogStar> = stars): ByteArray =
        ByteArrayOutputStream().also { WideFieldIndexCodec.write(it, input, bucket, WideFieldTriangleIndex.build(input, bucket)) }.toByteArray()

    @Test fun binaryRoundTripPreservesKeysVertexOrderAndCandidateOrder() {
        val original = WideFieldTriangleIndex.build(stars, FovBucket.WF_B)
        val restored = WideFieldIndexCodec.read(ByteArrayInputStream(encoded(FovBucket.WF_B)), stars, FovBucket.WF_B)
        assertArrayEquals(original.keys, restored.keys)
        repeat(original.recordCount) { row -> repeat(3) { assertEquals(original.vertex(row, it), restored.vertex(row, it)) } }
        val key = original.keys[0]
        val a = (key ushr 20).toInt(); val b = ((key ushr 10) and 1023).toInt(); val c = (key and 1023).toInt()
        assertEquals(original.candidates(a, b, c).toList(), restored.candidates(a, b, c).toList())
        assertEquals(original.recordCount * 20L, restored.residentArrayBytes)
    }

    @Test fun rejectsDifferentCatalogAndWrongBucketInsteadOfUsingOldCoordinates() {
        val bytes = encoded(FovBucket.WF_B)
        assertThrows(IllegalArgumentException::class.java) {
            WideFieldIndexCodec.read(ByteArrayInputStream(bytes), stars.mapIndexed { i, star -> if (i == 0) star.copy(raDeg = 41.0) else star }, FovBucket.WF_B)
        }
        assertThrows(IllegalArgumentException::class.java) {
            WideFieldIndexCodec.read(ByteArrayInputStream(bytes), stars, FovBucket.WF_C)
        }
    }

    @Test fun rejectsPayloadMutationTruncationAndTrailingGarbage() {
        val bytes = encoded(FovBucket.WF_B)
        val changed = bytes.clone().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        assertThrows(IllegalArgumentException::class.java) { WideFieldIndexCodec.read(ByteArrayInputStream(changed), stars, FovBucket.WF_B) }
        assertThrows(java.io.IOException::class.java) { WideFieldIndexCodec.read(ByteArrayInputStream(bytes.copyOf(bytes.size - 4)), stars, FovBucket.WF_B) }
        assertThrows(IllegalArgumentException::class.java) { WideFieldIndexCodec.read(ByteArrayInputStream(bytes + byteArrayOf(0)), stars, FovBucket.WF_B) }
    }

    @Test fun loadsOnlyDemandedBucketsAndEvictsLeastRecentlyUsedThirdBucket() {
        val opened = mutableListOf<FovBucket>()
        val indexes = LazyWideFieldIndexes(stars, { bucket -> opened += bucket; ByteArrayInputStream(encoded(bucket)) })
        assertTrue(opened.isEmpty())
        val first = indexes.get(FovBucket.WF_B)
        assertSame(first, indexes.get(FovBucket.WF_B))
        indexes.get(FovBucket.WF_A); indexes.get(FovBucket.WF_B); indexes.get(FovBucket.WF_C)
        assertEquals(listOf(FovBucket.WF_B, FovBucket.WF_A, FovBucket.WF_C), opened)
        assertEquals(listOf("WF_B", "WF_C"), indexes.residentBuckets())
        indexes.get(FovBucket.WF_A)
        assertEquals(4, opened.size)
        assertEquals(2, indexes.residentBuckets().size)
        indexes.clear()
        assertTrue(indexes.residentBuckets().isEmpty()); assertEquals(0, indexes.residentArrayBytes())
    }

    @Test fun corruptOrMissingAssetRebuildsOnlyThatBucketFromCurrentCatalog() {
        val indexes = LazyWideFieldIndexes(stars, { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
        val rebuilt = indexes.get(FovBucket.WF_C)
        assertArrayEquals(WideFieldTriangleIndex.build(stars, FovBucket.WF_C).keys, rebuilt.keys)
        assertEquals("runtime", indexes.snapshotLoads().single().origin)
        assertNotNull(indexes.snapshotLoads().single().rejectedAsset)
        assertEquals(listOf("WF_C"), indexes.residentBuckets())
    }

    @Test fun checkedInHygResourcesMatchRuntimeGeometryAndCurrentCatalogFingerprint() {
        val catalog = PhoneBrightStarCatalog.fromCsvLines(File("src/main/assets/catalog/phone_hyg_v41_m6.csv").readLines())
        FovBucket.values().forEach { bucket ->
            val actual = File("src/main/assets/catalog/wf-index/${bucket.name.lowercase()}.wfidx").inputStream().use {
                WideFieldIndexCodec.read(it, catalog.stars, bucket)
            }
            val rebuilt = WideFieldTriangleIndex.build(catalog.stars, bucket)
            assertArrayEquals("${bucket.name} quantized geometry", rebuilt.keys, actual.keys)
            repeat(actual.recordCount) { row -> repeat(3) { column ->
                assertEquals("${bucket.name} row $row column $column", rebuilt.vertex(row, column), actual.vertex(row, column))
            } }
        }
    }
}
