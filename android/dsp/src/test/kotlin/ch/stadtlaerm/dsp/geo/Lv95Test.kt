package ch.stadtlaerm.dsp.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lv95 against the reference points of server/DESIGN.md §3 and against the website's lv95.js. */
class Lv95Test {

    private fun dms(d: Int, m: Int, s: Double) = d + m / 60.0 + s / 3600.0

    private data class Ref(val name: String, val e: Double, val n: Double, val lat: Double, val lon: Double, val tolM: Double)

    // server/DESIGN.md §3, copied exactly: LV95 E, N; φ, λ (ETRF93); tolerance in metres.
    private val points = listOf(
        Ref("swisstopo worked example", 2700000.00, 1100000.00, dms(46, 2, 38.87), dms(8, 43, 49.79), 1.0),
        Ref("AGNES ZIMM (Zimmerwald)", 2602030.740, 1191775.030, dms(46, 52, 37.540569), dms(7, 27, 54.983511), 1.0),
        Ref("AGNES ETH2 (ETH Zürich)", 2680910.112, 1251259.201, dms(47, 24, 25.842486), dms(8, 30, 38.194637), 1.0),
        Ref("AGNES LOMO (Locarno Monti)", 2704160.863, 1114349.376, dms(46, 10, 21.225556), dms(8, 47, 14.732003), 1.0),
        Ref("AGNES GENE (Genève)", 2498930.196, 1122714.152, dms(46, 14, 53.692140), dms(6, 7, 41.065513), 3.0),
    )

    /** Ground distance in metres between two WGS84 points (local flat approximation; same as the JS test). */
    private fun groundMetres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6378137.0
        val rad = PI / 180
        val dn = (lat2 - lat1) * rad * r
        val de = (lon2 - lon1) * rad * r * cos((lat1 + lat2) / 2 * rad)
        return hypot(dn, de)
    }

    @Test
    fun lv95ToWgs84WithinToleranceAtAllReferencePoints() {
        for (p in points) {
            val g = Lv95.lv95ToWgs84(p.e, p.n)
            val d = groundMetres(g.lat, g.lon, p.lat, p.lon)
            assertTrue(d <= p.tolM, "${p.name}: ${"%.3f".format(d)} m off (tolerance ${p.tolM} m)")
        }
    }

    @Test
    fun wgs84ToLv95WithinToleranceAtAllReferencePoints() {
        for (p in points) {
            val c = Lv95.wgs84ToLv95(p.lat, p.lon)
            val d = hypot(c.e - p.e, c.n - p.n)
            assertTrue(d <= p.tolM, "${p.name}: ${"%.3f".format(d)} m off (tolerance ${p.tolM} m)")
        }
    }

    @Test
    fun roundTripLv95Wgs84Lv95StaysWithin1mOverZuerich() {
        var worst = 0.0
        var e = 2676000.0
        while (e <= 2694000.0) {
            var n = 1237000.0
            while (n <= 1255000.0) {
                val g = Lv95.lv95ToWgs84(e, n)
                val back = Lv95.wgs84ToLv95(g.lat, g.lon)
                worst = max(worst, hypot(back.e - e, back.n - n))
                n += 1500.0
            }
            e += 1500.0
        }
        assertTrue(worst < 1.0, "worst round-trip error $worst m")
    }

    @Test
    fun roundTripWgs84Lv95Wgs84AtReferencePoints() {
        for (p in points) {
            val c = Lv95.wgs84ToLv95(p.lat, p.lon)
            val g = Lv95.lv95ToWgs84(c.e, c.n)
            val d = groundMetres(g.lat, g.lon, p.lat, p.lon)
            assertTrue(d <= p.tolM, "${p.name}: $d m")
        }
    }

    @Test
    fun cellIdOfEthZuerichEqualsTheWebsite() {
        // From the table: ETH2 is at E 2 680 910.112, N 1 251 259.201 → hectare h26809_12512.
        // docs/map/lv95.js (branch map-web) gives the same for the station's WGS84 coordinates
        // (its unit test asserts "h26809_12512").
        val eth = points[2]
        assertEquals("h26809_12512", Lv95.lv95ToCell(eth.e, eth.n))
        assertEquals("h26809_12512", Lv95.cellId(eth.lat, eth.lon))
    }

    @Test
    fun cellIdsAndParsing() {
        assertEquals(Lv95.En(2682400.0, 1247300.0), Lv95.parseCell("h26824_12473"))
        assertEquals("h26824_12473", Lv95.lv95ToCell(2682400.0, 1247300.0))
        assertEquals("h26824_12473", Lv95.lv95ToCell(2682499.99, 1247399.99))
        assertEquals("h26825_12473", Lv95.lv95ToCell(2682500.0, 1247300.0))
        assertNull(Lv95.parseCell("h2682_12473"))
        assertNull(Lv95.parseCell("x26824_12473"))
        assertNull(Lv95.parseCell("h26824_12473 "))
        assertTrue(Lv95.isInServerRange("h26824_12473"))
        assertFalse(Lv95.isInServerRange("h20000_12473"))
        assertFalse(Lv95.isInServerRange("nonsense"))
    }

    @Test
    fun cellCornersAreTheHectareCornersInOrder() {
        val id = "h26824_12473"
        val corners = Lv95.cellCorners(id)
        assertEquals(4, corners.size)
        val expected = listOf(2682400.0 to 1247300.0, 2682500.0 to 1247300.0, 2682500.0 to 1247400.0, 2682400.0 to 1247400.0)
        corners.forEachIndexed { i, g ->
            val c = Lv95.wgs84ToLv95(g.lat, g.lon)
            val d = hypot(c.e - expected[i].first, c.n - expected[i].second)
            assertTrue(d < 1.0, "corner $i: $d m")
        }
        assertTrue(corners[1].lon > corners[0].lon) // SE east of SW
        assertTrue(corners[3].lat > corners[0].lat) // NW north of SW
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val side = groundMetres(a.lat, a.lon, b.lat, b.lon)
            assertTrue(abs(side - 100) < 1, "side $i: $side m")
        }
        val ctr = Lv95.cellCenter(id)
        assertEquals(id, Lv95.cellId(ctr.lat, ctr.lon))
        assertFailsWith<IllegalArgumentException> { Lv95.cellCorners("nonsense") }
    }

    /**
     * Cross-check with the website. Expected values were computed with `docs/map/lv95.js` from
     * branch map-web (node, 2026-10-08) for ten Zürich points; Kotlin must give the same cell and
     * the same LV95 coordinates to the millimetre (identical formulas, identical operation order).
     */
    @Test
    fun agreesWithTheJavaScriptPortOnZuerichFixture() {
        data class Js(val lat: Double, val lon: Double, val cell: String, val e: Double, val n: Double)
        val fixture = listOf(
            Js(47.3769, 8.5417, "h26833_12479", 2683304.035, 1247925.597),
            Js(47.3667, 8.5500, "h26839_12468", 2683946.863, 1246800.497),
            Js(47.3910, 8.5130, "h26811_12494", 2681115.298, 1249463.058),
            Js(47.4085, 8.5498, "h26838_12514", 2683865.969, 1251447.251),
            Js(47.3502, 8.5203, "h26817_12449", 2681728.921, 1244934.831),
            Js(47.3995, 8.4870, "h26791_12503", 2679139.798, 1250381.483),
            Js(47.3600, 8.5800, "h26862_12460", 2686223.563, 1246088.179),
            Js(47.3800, 8.5300, "h26824_12482", 2682415.719, 1248257.877),
            Js(47.4230, 8.5040, "h26803_12530", 2680387.554, 1253011.334),
            Js(47.3330, 8.4960, "h26799_12429", 2679918.741, 1242997.670),
        )
        for (p in fixture) {
            assertEquals(p.cell, Lv95.cellId(p.lat, p.lon), "cell of ${p.lat}, ${p.lon}")
            val c = Lv95.wgs84ToLv95(p.lat, p.lon)
            assertEquals(p.e, c.e, 0.001)
            assertEquals(p.n, c.n, 0.001)
        }
        // cellToCorners("h26824_12473") from lv95.js, SW first
        val sw = Lv95.cellCorners("h26824_12473")[0]
        assertEquals(47.37138360265193, sw.lat, 1e-12)
        assertEquals(8.529616735606698, sw.lon, 1e-12)
    }
}
