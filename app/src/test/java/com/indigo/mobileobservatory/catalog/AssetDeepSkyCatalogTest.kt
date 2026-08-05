package com.indigo.mobileobservatory.catalog

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetDeepSkyCatalogTest {

    @Test
    fun parsesGeneratedRow() {
        val line = "M 31|Galaxy|0.712319|41.269056|3.44|177.83|M 31;NGC 224;Andromeda Galaxy;仙女座星系"

        val obj = AssetDeepSkyCatalog.parseLine(line)!!

        assertEquals("M 31", obj.id)
        assertEquals("Galaxy", obj.type)
        assertEquals(0.712319, obj.raHours, 1e-6)
        assertEquals(41.269056, obj.decDeg, 1e-6)
        assertEquals(3.44, obj.magnitude!!, 1e-6)
        assertEquals(177.83, obj.sizeArcmin!!, 1e-6)
        assertEquals(listOf("M 31", "NGC 224", "Andromeda Galaxy", "仙女座星系"), obj.aliases)
    }

    @Test
    fun skipsCommentsAndBlanks() {
        assertNull(AssetDeepSkyCatalog.parseLine("# header"))
        assertNull(AssetDeepSkyCatalog.parseLine("   "))
        assertNull(AssetDeepSkyCatalog.parseLine("M 31|Galaxy"))
    }

    @Test
    fun toleratesMissingMagnitudeAndSize() {
        val obj = AssetDeepSkyCatalog.parseLine("C 99|Dark Nebula|12.521944|-63.743333|||C 99;Coalsack")!!

        assertNull(obj.magnitude)
        assertNull(obj.sizeArcmin)
        assertEquals(-63.743333, obj.decDeg, 1e-6)
    }

    @Test
    fun engineDesignationsPrefixProperNamesOnly() {
        val obj = AssetDeepSkyCatalog.parseLine(
            "M 31|Galaxy|0.712319|41.269056|3.44|177.83|M 31;NGC 224;Andromeda Galaxy"
        )!!

        assertEquals(listOf("M 31", "NGC 224", "NAME Andromeda Galaxy"), obj.engineDesignations())
    }

    /** Guards the shipped asset: coordinates and coverage users depend on. */
    @Test
    fun shippedAssetCoversCommonObjects() {
        val asset = File("src/main/assets/${AssetDeepSkyCatalog.ASSET_PATH}")
        assertTrue("missing ${asset.absolutePath}", asset.exists())

        val objects = asset.inputStream().use(AssetDeepSkyCatalog::parse)
        assertTrue("expected a full catalog, got ${objects.size}", objects.size > 12000)

        val byId = objects.associateBy { it.id }
        val m31 = byId["M 31"]
        assertNotNull(m31)
        // J2000 00h42m44s +41°16'
        assertEquals(0.7123, m31!!.raHours, 0.001)
        assertEquals(41.269, m31.decDeg, 0.01)

        val m42 = byId["M 42"]
        assertNotNull(m42)
        assertEquals(5.5879, m42!!.raHours, 0.001)
        assertEquals(-5.3897, m42.decDeg, 0.01)

        listOf("M 1", "M 13", "M 45", "M 51", "M 104", "M 110", "NGC 7000", "IC 434")
            .forEach { assertNotNull("missing $it", byId[it]) }
    }

    @Test
    fun searchRanksBrightNamedObjectFirst() {
        val asset = File("src/main/assets/${AssetDeepSkyCatalog.ASSET_PATH}")
        val objects = asset.inputStream().use(AssetDeepSkyCatalog::parse)

        assertEquals("M 31", CatalogSearch.search(objects, "m31").first().id)
        assertEquals("M 31", CatalogSearch.search(objects, "M 31").first().id)
        assertEquals("M 42", CatalogSearch.search(objects, "猎户座大星云").first().id)
        assertEquals("M 45", CatalogSearch.search(objects, "pleiades").first().id)
        assertTrue(CatalogSearch.search(objects, "ngc 7000").any { it.id == "NGC 7000" })
    }
}
