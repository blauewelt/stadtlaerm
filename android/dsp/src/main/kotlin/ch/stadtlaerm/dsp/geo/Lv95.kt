package ch.stadtlaerm.dsp.geo

import kotlin.math.floor

/**
 * WGS84 ↔ Swiss LV95 (CH1903+) and the hectare cell ids of server/DESIGN.md §3.
 *
 * swisstopo, "Approximate formulas for the transformation between Swiss projection coordinates
 * and WGS84": accuracy about 1 m inside Switzerland, far below the 100 m cell. This is a 1:1 port
 * of the website's `docs/map/lv95.js` (same constants, same order of operations), so the app and
 * the map always compute the same cell for the same point. Both are tested against swisstopo's
 * reference points and against each other (dsp/src/test/.../geo/Lv95Test.kt).
 *
 * Pure Kotlin, no Android: the app snaps the contributor's point to a cell **before** anything is
 * sent; coordinates never leave the phone (DESIGN.md §2.2).
 */
object Lv95 {

    /** LV95 easting/northing in metres. */
    data class En(val e: Double, val n: Double)

    /** WGS84 latitude/longitude in decimal degrees. */
    data class LatLon(val lat: Double, val lon: Double)

    /** WGS84 latitude/longitude in decimal degrees → LV95 in metres. */
    fun wgs84ToLv95(lat: Double, lon: Double): En {
        // auxiliary values: differences to Bern in units of 10 000"
        val p = (lat * 3600 - 169028.66) / 10000
        val l = (lon * 3600 - 26782.5) / 10000
        val e = 2600072.37 +
            211455.93 * l -
            10938.51 * l * p -
            0.36 * l * p * p -
            44.54 * l * l * l
        val n = 1200147.07 +
            308807.95 * p +
            3745.25 * l * l +
            76.63 * p * p -
            194.56 * l * l * p +
            119.79 * p * p * p
        return En(e, n)
    }

    /** LV95 easting/northing in metres → WGS84 in decimal degrees. */
    fun lv95ToWgs84(e: Double, n: Double): LatLon {
        // auxiliary values: differences to Bern in units of 1000 km
        val y = (e - 2600000) / 1000000
        val x = (n - 1200000) / 1000000
        val lon = 2.6779094 +
            4.728982 * y +
            0.791484 * y * x +
            0.1306 * y * x * x -
            0.0436 * y * y * y
        val lat = 16.9023892 +
            3.238272 * x -
            0.270978 * y * y -
            0.002528 * x * x -
            0.0447 * y * y * x -
            0.0140 * x * x * x
        // result is in units of 10 000"; * 100 / 36 gives degrees
        return LatLon(lat * 100 / 36, lon * 100 / 36)
    }

    private val CELL_RE = Regex("^h(\\d{5})_(\\d{5})$")

    /** "h26824_12473" → south-west corner (2 682 400, 1 247 300), or null if malformed. */
    fun parseCell(cellId: String): En? {
        val m = CELL_RE.matchEntire(cellId) ?: return null
        return En(m.groupValues[1].toInt() * 100.0, m.groupValues[2].toInt() * 100.0)
    }

    /** True for a well-formed hectare id (`h` + 5 digits + `_` + 5 digits). */
    fun isCellId(cellId: String): Boolean = CELL_RE.matches(cellId)

    /** LV95 point → id of the hectare that contains it. */
    fun lv95ToCell(e: Double, n: Double): String =
        "h${floor(e / 100).toLong()}_${floor(n / 100).toLong()}"

    /** WGS84 point → id of the hectare that contains it (what the app sends). */
    fun cellId(lat: Double, lon: Double): String {
        val p = wgs84ToLv95(lat, lon)
        return lv95ToCell(p.e, p.n)
    }

    /**
     * The four corners of a hectare in WGS84, SW, SE, NE, NW (the website's order).
     * @throws IllegalArgumentException on a malformed id.
     */
    fun cellCorners(cellId: String): List<LatLon> {
        val sw = parseCell(cellId) ?: throw IllegalArgumentException("not a hectare cell id: $cellId")
        return listOf(
            sw.e to sw.n,
            sw.e + 100 to sw.n,
            sw.e + 100 to sw.n + 100,
            sw.e to sw.n + 100,
        ).map { (e, n) -> lv95ToWgs84(e, n) }
    }

    /** Centre of a hectare in WGS84. @throws IllegalArgumentException on a malformed id. */
    fun cellCenter(cellId: String): LatLon {
        val sw = parseCell(cellId) ?: throw IllegalArgumentException("not a hectare cell id: $cellId")
        return lv95ToWgs84(sw.e + 50, sw.n + 50)
    }

    /**
     * Rough box around Switzerland in LV95 (the server's `CELL_E_RANGE`/`CELL_N_RANGE`,
     * server/stadtlaerm_server/schemas.py): the server refuses cells outside it.
     */
    fun isInServerRange(cellId: String): Boolean {
        val sw = parseCell(cellId) ?: return false
        val e = (sw.e / 100).toInt()
        val n = (sw.n / 100).toInt()
        return e in 24800 until 28400 && n in 10700 until 13000
    }
}
