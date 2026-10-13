package com.indigo.mobileobservatory.pointing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.DigestOutputStream
import java.security.MessageDigest
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/** Compact immutable rows. Each key's original generation order is preserved. */
internal class WideFieldTriangleIndex(val keys: LongArray, private val vertices: IntArray) {
    val recordCount get() = keys.size
    val residentArrayBytes get() = keys.size.toLong() * 8L + vertices.size.toLong() * 4L
    fun vertex(row: Int, column: Int) = vertices[row * 3 + column]

    fun candidates(a: Int, b: Int, c: Int): Sequence<Int> = sequence {
        for (da in -1..1) for (db in -1..1) for (dc in -1..1) {
            val aa = a + da; val bb = b + db; val cc = c + dc
            if (aa < 0 || bb < 0 || cc < 0) continue
            val wanted = packKey(aa, bb, cc)
            var low = 0; var high = keys.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (keys[middle] < wanted) low = middle + 1 else high = middle
            }
            while (low < keys.size && keys[low] == wanted) yield(low++)
        }
    }

    companion object {
        const val STEP_DEG = 0.35
        const val ANCHOR_MAGNITUDE = 4.5
        fun packKey(a: Int, b: Int, c: Int): Long = (a.toLong() shl 20) or (b.toLong() shl 10) or c.toLong()

        fun build(stars: List<PhoneCatalogStar>, bucket: FovBucket): WideFieldTriangleIndex {
            val vectors = stars.map { star ->
                val ra = Math.toRadians(star.raDeg); val dec = Math.toRadians(star.decDeg)
                doubleArrayOf(cos(dec) * cos(ra), cos(dec) * sin(ra), sin(dec))
            }
            fun distance(a: Int, b: Int) = acos(vectors[a].indices.sumOf { vectors[a][it] * vectors[b][it] }.coerceIn(-1.0, 1.0))
            val anchors = stars.indices.filter { stars[it].magnitude <= ANCHOR_MAGNITUDE }
            val rows = ArrayList<Row>()
            for (anchor in anchors) {
                val neighbours = anchors.asSequence().filter { it != anchor }.sortedBy { distance(anchor, it) }
                    .take(bucket.neighbourCount).toList()
                for (i in 0 until neighbours.size - 1) for (j in i + 1 until neighbours.size) {
                    val b = neighbours[i]; val c = neighbours[j]
                    val sides = doubleArrayOf(distance(anchor, b), distance(anchor, c), distance(b, c)).also { it.sort() }
                    if (sides[2] > Math.toRadians(bucket.maxSideDeg)) continue
                    rows += Row(packKey((Math.toDegrees(sides[0]) / STEP_DEG).toInt(),
                        (Math.toDegrees(sides[1]) / STEP_DEG).toInt(), (Math.toDegrees(sides[2]) / STEP_DEG).toInt()), anchor, b, c)
                }
            }
            rows.sortWith(compareBy { it.key }) // Stable: tie order matches the old per-key insertion order.
            val ids = IntArray(rows.size * 3)
            val keys = LongArray(rows.size)
            rows.forEachIndexed { i, row -> keys[i] = row.key; ids[i * 3] = row.a; ids[i * 3 + 1] = row.b; ids[i * 3 + 2] = row.c }
            return WideFieldTriangleIndex(keys, ids)
        }
        private data class Row(val key: Long, val a: Int, val b: Int, val c: Int)
    }
}

/** Header and payload identities fail closed; a rejected asset is rebuilt from this catalog. */
internal object WideFieldIndexCodec {
    const val VERSION = 1
    const val MAGIC = 0x57464958 // WFIX, network byte order.
    private const val MAX_RECORDS = 1_000_000
    const val HEADER_BYTES = 104

    fun fingerprint(stars: List<PhoneCatalogStar>): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digestOutput(digest).use { output ->
            output.writeInt(stars.size)
            stars.forEach { output.writeDouble(it.raDeg); output.writeDouble(it.decDeg); output.writeDouble(it.magnitude); output.writeInt(it.hip ?: -1) }
        }
        return digest.digest()
    }

    fun read(input: InputStream, stars: List<PhoneCatalogStar>, bucket: FovBucket): WideFieldTriangleIndex = DataInputStream(input.buffered()).use { data ->
        require(data.readInt() == MAGIC && data.readInt() == VERSION) { "index_format" }
        require(data.readInt() == stars.size) { "index_star_count" }
        val catalogHash = ByteArray(32).also(data::readFully)
        require(MessageDigest.isEqual(catalogHash, fingerprint(stars))) { "index_catalog_identity" }
        require(data.readInt() == bucket.ordinal && data.readDouble() == bucket.maxSideDeg &&
            data.readInt() == bucket.neighbourCount && data.readDouble() == WideFieldTriangleIndex.STEP_DEG) { "index_bucket_parameters" }
        val count = data.readInt()
        require(count in 0..MAX_RECORDS && count <= stars.size.toLong() * bucket.neighbourCount * (bucket.neighbourCount - 1) / 2) { "index_record_bound" }
        val payloadHash = ByteArray(32).also(data::readFully)
        val digest = MessageDigest.getInstance("SHA-256")
        val keys = LongArray(count); val ids = IntArray(count * 3)
        val bytes = ByteArray(24); val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        repeat(count) { row ->
            data.readFully(bytes); digest.update(bytes)
            val a = buffer.getInt(0); val b = buffer.getInt(4); val c = buffer.getInt(8)
            require(a in 0..1023 && a <= b && b <= c && c <= (bucket.maxSideDeg / WideFieldTriangleIndex.STEP_DEG).toInt()) { "index_key" }
            keys[row] = WideFieldTriangleIndex.packKey(a, b, c)
            require(row == 0 || keys[row] >= keys[row - 1]) { "index_sort" }
            repeat(3) { column ->
                val star = buffer.getInt(12 + column * 4)
                require(star in stars.indices && stars[star].magnitude <= WideFieldTriangleIndex.ANCHOR_MAGNITUDE) { "index_star_reference" }
                ids[row * 3 + column] = star
            }
            require(ids[row * 3] != ids[row * 3 + 1] && ids[row * 3] != ids[row * 3 + 2] && ids[row * 3 + 1] != ids[row * 3 + 2]) { "index_duplicate_vertex" }
        }
        require(data.read() == -1 && MessageDigest.isEqual(payloadHash, digest.digest())) { "index_payload_identity" }
        WideFieldTriangleIndex(keys, ids)
    }

    fun write(output: OutputStream, stars: List<PhoneCatalogStar>, bucket: FovBucket, index: WideFieldTriangleIndex) {
        val digest = MessageDigest.getInstance("SHA-256")
        digestOutput(digest).use { data -> writeRows(data, index) }
        DataOutputStream(output).use { data ->
            data.writeInt(MAGIC); data.writeInt(VERSION); data.writeInt(stars.size); data.write(fingerprint(stars))
            data.writeInt(bucket.ordinal); data.writeDouble(bucket.maxSideDeg); data.writeInt(bucket.neighbourCount)
            data.writeDouble(WideFieldTriangleIndex.STEP_DEG); data.writeInt(index.recordCount); data.write(digest.digest())
            writeRows(data, index)
        }
    }

    private fun writeRows(data: DataOutputStream, index: WideFieldTriangleIndex) {
        repeat(index.recordCount) { row ->
            val key = index.keys[row]
            data.writeInt((key ushr 20).toInt()); data.writeInt(((key ushr 10) and 1023L).toInt()); data.writeInt((key and 1023L).toInt())
            repeat(3) { data.writeInt(index.vertex(row, it)) }
        }
    }

    private fun digestOutput(digest: MessageDigest) = DataOutputStream(DigestOutputStream(object : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }, digest))
}

data class WideFieldIndexLoad(val bucket: String, val origin: String, val records: Int,
    val arrayBytes: Long, val elapsedNanos: Long, val rejectedAsset: String? = null)

/** A scale search may cross a boundary; at most two immutable bucket arrays remain cached. */
internal class LazyWideFieldIndexes(private val stars: List<PhoneCatalogStar>,
    private val resource: ((FovBucket) -> InputStream?)? = null, private val capacity: Int = 2) {
    private val cache = LinkedHashMap<FovBucket, WideFieldTriangleIndex>(4, .75f, true)
    private val loads = ArrayList<WideFieldIndexLoad>()
    init { require(capacity in 1..FovBucket.values().size) }

    @Synchronized fun get(bucket: FovBucket, onLoad: (WideFieldIndexLoad) -> Unit = {}): WideFieldTriangleIndex {
        cache[bucket]?.let { return it }
        val started = System.nanoTime()
        var rejected: String? = null
        val asset = try { resource?.invoke(bucket)?.use { WideFieldIndexCodec.read(it, stars, bucket) } }
            catch (failure: java.io.IOException) { rejected = "index_io"; null }
            catch (failure: IllegalArgumentException) { rejected = failure.message ?: "index_invalid"; null }
        val index = asset ?: WideFieldTriangleIndex.build(stars, bucket)
        cache[bucket] = index
        while (cache.size > capacity) cache.remove(cache.keys.first())
        val load = WideFieldIndexLoad(bucket.name, if (asset == null) "runtime" else "asset", index.recordCount,
            index.residentArrayBytes, System.nanoTime() - started, rejected)
        loads += load
        onLoad(load)
        if (loads.size > 100) loads.removeAt(0)
        return index
    }
    @Synchronized fun snapshotLoads() = loads.toList()
    @Synchronized fun residentBuckets() = cache.keys.map { it.name }
    @Synchronized fun residentArrayBytes() = cache.values.sumOf { it.residentArrayBytes }
    @Synchronized fun clear() { cache.clear(); loads.clear() }
}
