package ch.stadtlaerm.chart

import ch.stadtlaerm.dsp.EventFeatures
import ch.stadtlaerm.dsp.EventShape
import ch.stadtlaerm.dsp.Iso
import ch.stadtlaerm.dsp.WindRule
import ch.stadtlaerm.dsp.MinuteRecord
import ch.stadtlaerm.dsp.NoiseEvent
import ch.stadtlaerm.dsp.classify.LabelScore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Deterministic synthetic measurement data for tests and screenshots (never real recordings).
 * Levels are plausible for an open window on a Zürich street; the generator only needs to look
 * right in the chart and exercise its edge cases (gaps, DST, loud weekend nights).
 */
object SyntheticData {
    val zone: ZoneId = ZoneId.of("Europe/Zurich")

    fun ms(dt: LocalDateTime): Long = dt.atZone(zone).toInstant().toEpochMilli()

    data class Ev(
        val at: LocalDateTime, val lafMax: Double, val category: String, val durationS: Double = 3.0, val label: String? = null,
        /** Wind on the microphone (detector v2): drawn as a hollow dot, counted nowhere. */
        val wind: Boolean = false,
    )

    private val topLabel = mapOf(
        "loud_vehicle" to "Motorcycle", "road_traffic" to "Car passing by", "rail_tram" to "Rail transport",
        "aircraft" to "Aircraft", "voices" to "Shout", "music" to "Music", "construction" to "Jackhammer",
        "unclassified" to "Wind",
    )

    fun event(e: Ev, background: Double, calibrated: Boolean = false): NoiseEvent {
        val t = ms(e.at)
        val label = e.label ?: topLabel[e.category]
        return NoiseEvent(
            startEpochMs = t, startIso = Iso.format(t, zone, millis = true), durationSeconds = e.durationS,
            lafMaxDb = e.lafMax, selDb = e.lafMax + 10 * log10(e.durationS) - 3, backgroundDb = background, thresholdDb = 10.0,
            dominantCategory = if (e.wind) WindRule.CATEGORY else e.category, dominantScore = if (e.wind) 0f else 0.6f,
            topLabels = listOfNotNull(label?.let { LabelScore(it, 0.6f) }), classifierFrames = 3,
            calibrationId = null, audioSource = "UNPROCESSED", calibrated = calibrated, minLevelDb = 30.0,
            features = if (e.wind) EventFeatures(background + 2, e.lafMax - background - 2, 0.25, 0.6, 0.45, 2.0, 0.97, 4.2, true, EventShape.IMPULSE) else null,
        )
    }

    /**
     * Minutes from [from] (inclusive) to [to] (exclusive) with background L90 = [l90](t), skipping
     * [gaps]; the minutes in which [events] start get their energy added.
     */
    fun minutes(
        from: LocalDateTime, to: LocalDateTime, l90: (LocalDateTime) -> Double, events: List<Ev>,
        gaps: List<Pair<LocalDateTime, LocalDateTime>> = emptyList(), seed: Int = 1, calibrated: Boolean = false,
    ): List<MinuteRecord> {
        val r = Random(seed)
        val out = ArrayList<MinuteRecord>()
        var t = from
        while (t < to) {
            val cur = t
            t = t.plusMinutes(1)
            if (gaps.any { (a, b) -> cur >= a && cur < b }) continue
            val bg = l90(cur) + r.nextDouble(-0.8, 0.8)
            val l10 = bg + 3.0 + r.nextDouble(0.0, 2.5)
            val l50 = bg + 1.5
            var energy = 10.0.pow((bg + 2.2 + r.nextDouble(0.0, 1.2)) / 10)
            var lafMax = l10 + 4 + r.nextDouble(0.0, 4.0)
            var count = 0
            for (e in events) {
                if (!e.at.isBefore(cur) && e.at.isBefore(cur.plusMinutes(1))) {
                    energy += 10.0.pow((e.lafMax + 10 * log10(e.durationS) - 3) / 10) / 60
                    lafMax = maxOf(lafMax, e.lafMax)
                    count++
                }
            }
            val start = ms(cur)
            out += MinuteRecord(
                startEpochMs = start, startIso = Iso.format(start, zone), durationSeconds = 60.0,
                laeqDb = 10 * log10(energy), lafMaxDb = lafMax, lafMinDb = bg - 2, l1Db = lafMax - 2, l10Db = l10, l50Db = l50,
                l90Db = bg, eventCount = count, dominantCategory = "road_traffic", categoryShares = mapOf("road_traffic" to 1.0),
                classifierFrames = 60, calibrationId = null, calibrationOffsetDb = 112.35, audioSource = "UNPROCESSED",
                calibrated = calibrated, validSeconds = 60.0,
            )
        }
        return out
    }

    // ---- Friday night 9 → 10 October 2026 --------------------------------------------------

    val friday: LocalDate = LocalDate.of(2026, 10, 9)
    private fun fri(h: Int, m: Int, s: Int = 0) =
        if (h >= 12) friday.atTime(h, m, s) else friday.plusDays(1).atTime(h, m, s)

    /** 18 events in the night; 5 Töff & Poser between 23:00 and 02:00 at 72–85 dB. */
    val fridayEvents: List<Ev> = listOf(
        Ev(fri(22, 12), 61.0, "road_traffic"),
        Ev(fri(22, 31), 58.5, "voices", 4.0),
        Ev(fri(22, 47), 64.0, "road_traffic"),
        Ev(fri(23, 5), 78.0, "loud_vehicle", 5.0),
        Ev(fri(23, 18), 59.0, "voices", 6.0),
        Ev(fri(23, 40, 30), 85.0, "loud_vehicle", 7.5, "Accelerating, revving, vroom"),
        Ev(fri(23, 58), 66.0, "road_traffic"),
        Ev(fri(0, 20), 72.0, "loud_vehicle", 4.0),
        Ev(fri(0, 33), 62.5, "voices", 8.0),
        Ev(fri(0, 51), 57.0, "unclassified", 1.5),
        Ev(fri(1, 10), 81.0, "loud_vehicle", 6.0),
        Ev(fri(1, 26), 63.0, "voices", 5.0),
        Ev(fri(1, 50), 76.0, "loud_vehicle", 4.5),
        Ev(fri(2, 40), 60.0, "road_traffic"),
        Ev(fri(4, 15), 62.0, "road_traffic"),
        Ev(fri(4, 52), 65.0, "road_traffic"),
        Ev(fri(5, 20), 63.5, "rail_tram", 9.0),
        // "Aircraft at 06:00": the night window is [22:00, 06:00), so it starts just before.
        Ev(fri(5, 59, 20), 67.0, "aircraft", 25.0),
        // A wind episode around 02:10 (not counted: 18 events stay 18).
        Ev(fri(2, 5, 10), 58.0, "unclassified", 0.6, wind = true),
        Ev(fri(2, 7, 40), 63.0, "unclassified", 0.9, wind = true),
        Ev(fri(2, 9, 5), 55.0, "unclassified", 0.5, wind = true),
        Ev(fri(2, 12, 30), 61.0, "unclassified", 1.2, wind = true),
    )

    /** L90 38–42 until ~02:30, then rising to 48 by 05:30. */
    fun fridayL90(t: LocalDateTime): Double {
        val h = hoursSince(friday.atTime(22, 0), t)
        val base = 40.0 + 2.0 * sin(h * 1.3)
        return when {
            h < 4.5 -> base
            h < 7.5 -> base + (48.0 - base) * (h - 4.5) / 3.0
            else -> 48.0
        }
    }

    val fridayGap = fri(3, 10) to fri(3, 35)

    fun fridayNight(): ChartData {
        val from = friday.atTime(21, 30)
        val to = friday.plusDays(1).atTime(6, 30)
        val mins = minutes(from, to, ::fridayL90, fridayEvents, gaps = listOf(fridayGap), seed = 9)
        val evs = fridayEvents.map { event(it, fridayL90(it.at)) }
        val w = Windows(zone).of(RangeMode.NIGHT, friday)
        return ChartData(w, mins.filter { it.startEpochMs in w }, evs.filter { it.startEpochMs in w }, nowMs = ms(friday.plusDays(1).atTime(9, 0)))
    }

    // ---- Day and week ------------------------------------------------------------------------

    /** Typical urban day: quiet night (≈ 38 dB), morning rise, 50–55 dB daytime, evening decline. */
    fun dayL90(t: LocalDateTime, loudNight: Boolean = false): Double {
        val h = t.hour + t.minute / 60.0
        val night = if (loudNight) 45.0 else 38.5
        return when {
            h < 5.0 -> night + 1.5 * sin(h)
            h < 8.0 -> night + (52.0 - night) * (h - 5.0) / 3.0
            h < 19.0 -> 52.0 + 2.5 * sin(h * 0.9)
            h < 23.0 -> 52.0 - (52.0 - (night + 3)) * (h - 19.0) / 4.0
            else -> night + 3
        }
    }

    fun dayEvents(date: LocalDate, seed: Int, loudNight: Boolean = false): List<Ev> {
        val r = Random(seed)
        val out = ArrayList<Ev>()
        repeat(26) {
            val h = r.nextInt(6, 22)
            out += Ev(date.atTime(h, r.nextInt(60), r.nextInt(60)), 60.0 + r.nextDouble(0.0, 12.0), "road_traffic")
        }
        repeat(3) { out += Ev(date.atTime(r.nextInt(7, 18), r.nextInt(60)), 64.0 + r.nextDouble(0.0, 8.0), "construction", 20.0) }
        repeat(4) { out += Ev(date.atTime(r.nextInt(5, 23), r.nextInt(60)), 62.0 + r.nextDouble(0.0, 6.0), "rail_tram", 8.0) }
        repeat(if (loudNight) 14 else 4) {
            val h = listOf(22, 23, 0, 1, 2)[r.nextInt(5)]
            out += Ev(date.atTime(h, r.nextInt(60), r.nextInt(60)), 55.0 + r.nextDouble(0.0, 12.0), "voices", 5.0)
        }
        repeat(if (loudNight) 6 else 2) {
            val h = listOf(21, 22, 23, 0, 1)[r.nextInt(5)]
            out += Ev(date.atTime(h, r.nextInt(60), r.nextInt(60)), 72.0 + r.nextDouble(0.0, 13.0), "loud_vehicle", 5.0)
        }
        out += Ev(date.atTime(6, 5), 66.0, "aircraft", 25.0)
        return out
    }

    val day: LocalDate = LocalDate.of(2026, 10, 7) // a Wednesday

    fun dayData(): ChartData {
        val evs = dayEvents(day, 3)
        val mins = minutes(day.atStartOfDay(), day.plusDays(1).atStartOfDay(), { dayL90(it) }, evs, seed = 3)
        val w = Windows(zone).of(RangeMode.DAY, day)
        return ChartData(w, mins, evs.map { event(it, dayL90(it.at)) }, nowMs = ms(day.plusDays(1).atTime(8, 0)))
    }

    val weekMonday: LocalDate = LocalDate.of(2026, 10, 5)

    /** Monday 5 – Sunday 11 Oct 2026; the nights Fri→Sat and Sat→Sun are louder; Wednesday 10–14 h unmeasured. */
    fun weekData(): ChartData {
        val allMins = ArrayList<MinuteRecord>()
        val allEvents = ArrayList<NoiseEvent>()
        for (d in 0 until 7) {
            val date = weekMonday.plusDays(d.toLong())
            // Early hours belong to the previous evening's night: loud after Friday and Saturday.
            val loudEarly = date.dayOfWeek.value in 6..7
            val loudLate = date.dayOfWeek.value in 5..6
            val l90 = { t: LocalDateTime -> dayL90(t, loudNight = if (t.hour < 12) loudEarly else loudLate) }
            val evs = dayEvents(date, 100 + d, loudNight = loudEarly || loudLate)
            val gaps = if (d == 2) listOf(date.atTime(10, 0) to date.atTime(14, 0)) else emptyList()
            allMins += minutes(date.atStartOfDay(), date.plusDays(1).atStartOfDay(), l90, evs, gaps, seed = 100 + d)
            allEvents += evs.filter { e -> gaps.none { (a, b) -> e.at >= a && e.at < b } }.map { event(it, l90(it.at)) }
        }
        val w = Windows(zone).of(RangeMode.WEEK, weekMonday)
        return ChartData(w, allMins, allEvents.sortedBy { it.startEpochMs }, nowMs = ms(weekMonday.plusDays(8).atTime(9, 0)))
    }

    fun emptyNight(): ChartData {
        val w = Windows(zone).of(RangeMode.NIGHT, LocalDate.of(2026, 9, 20))
        return ChartData(w, emptyList(), emptyList(), nowMs = ms(LocalDate.of(2026, 10, 7).atTime(12, 0)))
    }

    private fun hoursSince(a: LocalDateTime, b: LocalDateTime): Double =
        java.time.Duration.between(a, b).toMinutes() / 60.0
}
