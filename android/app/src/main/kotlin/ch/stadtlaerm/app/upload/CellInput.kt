package ch.stadtlaerm.app.upload

import ch.stadtlaerm.dsp.geo.Lv95

/**
 * The placement step without a map: the contributor types or pastes where the phone is, and the
 * app turns it into a hectare **on the phone** (server/DESIGN.md §2.2). Accepted, in this order:
 *
 * - a hectare id: `h26833_12479`
 * - WGS84 decimal degrees as a map app copies them: `47.3769, 8.5417` (latitude first; swapped
 *   automatically if clearly given as longitude, latitude), also with decimal commas `47,3769 8,5417`
 * - Swiss LV95 coordinates as map.geo.admin.ch shows them: `2'683'304, 1'247'925` (E, N), and the
 *   older LV03 form `683'304, 247'925`
 *
 * Nothing typed here is stored or sent: only the resulting hectare id is kept.
 */
object CellInput {

    sealed interface Parsed {
        data class Ok(val cell: String, val how: String) : Parsed
        data class Error(val message: String) : Parsed
    }

    private val NUMBER = Regex("-?\\d+(?:[.,]\\d+)?")

    fun parse(text: String): Parsed {
        val t = text.trim()
        if (t.isEmpty()) return Parsed.Error("Bitte Koordinaten eingeben.")
        if (Lv95.isCellId(t.lowercase())) return check(t.lowercase(), "Hektare")

        // Swiss thousands separators (2'683'304) and spaces inside numbers are removed first.
        val cleaned = t.replace(Regex("(?<=\\d)['’ʼ\u2009\u202F](?=\\d)"), "")
        val nums = NUMBER.findAll(cleaned).map { it.value.replace(',', '.').toDouble() }.toList()
        if (nums.size != 2) return Parsed.Error("Zwei Zahlen erwartet, z. B. «47.3769, 8.5417» oder «2'683'304, 1'247'925».")
        val (a, b) = nums
        return when {
            // LV95 E, N (or N, E)
            a in 2_400_000.0..2_900_000.0 && b in 1_000_000.0..1_400_000.0 -> check(Lv95.lv95ToCell(a, b), "LV95")
            b in 2_400_000.0..2_900_000.0 && a in 1_000_000.0..1_400_000.0 -> check(Lv95.lv95ToCell(b, a), "LV95")
            // LV03 y, x: add the LV95 false origin
            a in 400_000.0..900_000.0 && b in 0.0..400_000.0 -> check(Lv95.lv95ToCell(a + 2_000_000, b + 1_000_000), "LV03")
            // WGS84: Switzerland is at 45.8–47.9° N, 5.9–10.5° E
            a in 45.0..48.5 && b in 5.0..11.0 -> check(Lv95.cellId(a, b), "WGS84")
            b in 45.0..48.5 && a in 5.0..11.0 -> check(Lv95.cellId(b, a), "WGS84")
            else -> Parsed.Error("Diese Koordinaten liegen nicht in der Schweiz.")
        }
    }

    private fun check(cell: String, how: String): Parsed =
        if (Lv95.isInServerRange(cell)) Parsed.Ok(cell, how) else Parsed.Error("Diese Hektare liegt nicht in der Schweiz.")
}
