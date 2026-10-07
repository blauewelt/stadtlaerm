package ch.stadtlaerm.chart

import ch.stadtlaerm.dsp.Iso
import ch.stadtlaerm.dsp.MinuteRecord
import ch.stadtlaerm.dsp.NightSummarizer
import ch.stadtlaerm.dsp.NoiseEvent
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ChartModelTest {
    private val zone = SyntheticData.zone
    private val win = Windows(zone)
    private val night = win.of(RangeMode.NIGHT, LocalDate.of(2026, 10, 9))
    private val after = night.endMs + 3 * 3_600_000L

    private fun minute(t: Long, laeq: Double, l10: Double = laeq + 3, l90: Double = laeq - 3, coverage: Double = 1.0, calibrated: Boolean = true) =
        MinuteRecord(
            t, Iso.format(t, zone), 60.0, laeq, laeq + 10, l90 - 2, laeq + 8, l10, laeq, l90, 0, null, emptyMap(), 0, null, 112.35,
            "UNPROCESSED", calibrated, validSeconds = 60.0 * coverage,
        )

    private fun event(t: Long, max: Double, cat: String = "road_traffic") = NoiseEvent(
        t, Iso.format(t, zone, true), 2.0, max, max, 40.0, 10.0, cat, 0.5f, emptyList(), 1, null, "UNPROCESSED", true,
    )

    private fun at(h: Int, m: Int) = SyntheticData.ms(if (h >= 12) LocalDateTime.of(2026, 10, 9, h, m) else LocalDateTime.of(2026, 10, 10, h, m))
    private fun minutes(fromH: Int, fromM: Int, count: Int, laeq: (Int) -> Double = { 40.0 }) =
        (0 until count).map { i -> minute(at(fromH, fromM) + i * 60_000L, laeq(i)) }

    private fun build(ms: List<MinuteRecord>, evs: List<NoiseEvent> = emptyList(), w: TimeWindow = night, highlight: String = "loud_vehicle", floor: Double = 45.0, now: Long = after) =
        ChartModel.build(ChartData(w, ms, evs, now), zone, highlight, floor)

    // ---- y range ---------------------------------------------------------------------------

    @Test
    fun yRangeFollowsTheRule() {
        // L90 min 37 → floor5(32) = 30; top = max(LAeq 45, L10 48, event 77) + 5 = 82 → 85.
        val m = build(minutes(22, 0, 30) { 40.0 + it % 6 }, listOf(event(at(22, 10), 77.0)))
        assertEquals(YRange(30.0, 85.0), m.yRange)
        assertEquals(listOf(30.0, 40.0, 50.0, 60.0, 70.0, 80.0), m.yRange.gridlines())
    }

    @Test
    fun yRangeIsAtLeast30dBAndClamped() {
        val quiet = build(minutes(22, 0, 10) { 40.0 })
        // 37 − 5 = 32 → 30; 43 + 5 = 48 → 50; widened to 30 dB.
        assertEquals(YRange(30.0, 60.0), quiet.yRange)
        val veryQuiet = ChartModel.yRange(listOf(SeriesPoint(0, 60_000, 5.0, 6.0, 2.0, 10.0, true, true, 60.0)), emptyList())
        assertEquals(YRange(0.0, 30.0), veryQuiet)
        val veryLoud = ChartModel.yRange(listOf(SeriesPoint(0, 60_000, 110.0, 112.0, 105.0, 118.0, true, true, 60.0)), listOf(event(0, 130.0)))
        assertEquals(YRange(90.0, 120.0), veryLoud)
    }

    @Test
    fun yRangeUsesOnlyDrawnEvents() {
        // A 90 dB event below… no: an event below the floor is not drawn and must not stretch the axis.
        val ms = minutes(22, 0, 30) { 30.0 }
        val m = build(ms, listOf(event(at(22, 5), 44.0)), floor = 45.0)
        assertEquals(0, m.otherEvents.size)
        assertEquals(YRange(20.0, 50.0), m.yRange)
    }

    // ---- gaps --------------------------------------------------------------------------------

    @Test
    fun lineBreaksAtEveryMissingOrInvalidMinute() {
        val ms = minutes(22, 0, 10).toMutableList()
        ms.removeAt(4) // one missing minute
        ms[7] = ms[7].copy(validSeconds = 20.0) // coverage 0.33 < 0.5
        val m = build(ms)
        // 22:00–22:03 | (22:04 missing) 22:05–22:07 | (22:08 invalid) 22:09
        assertEquals(listOf(4, 3, 1), m.segments.map { it.size })
        // Never interpolated: no segment spans the missing minute.
        for (seg in m.segments) seg.zipWithNext { a, b -> assertTrue(b.startMs - a.endMs <= 30_000) }
        // Short breaks (< 10 min) are not "gaps" with a rectangle.
        assertTrue(m.gaps.none { it.span.startMs < at(22, 10) })
    }

    @Test
    fun minutesWithNaNLevelsAreGaps() {
        val ms = minutes(22, 0, 5).toMutableList()
        ms[2] = ms[2].copy(laeqDb = Double.NaN, validSeconds = 0.0)
        assertEquals(listOf(2, 2), build(ms).segments.map { it.size })
    }

    @Test
    fun gapSpansAreAtLeastTenMinutesAndClipToNow() {
        val ms = minutes(22, 0, 60) + minutes(23, 9, 51) /* 9 min hole */ + minutes(0, 25, 60) /* 25 min hole */
        val m = build(ms, now = at(1, 40))
        val spans = m.gaps.map { it.span }
        // Holes: 23:00–23:09 (9 min, no rectangle), 00:00–00:25, 01:25–now (01:40 → 15 min, at data end).
        assertEquals(listOf(Span(at(0, 0), at(0, 25)), Span(at(1, 25), at(1, 40))), spans)
        assertTrue(m.gaps.last().atDataEnd)
        // The time after "now" is not a gap.
        assertTrue(spans.all { it.endMs <= at(1, 40) })
    }

    @Test
    fun gapTextsTellInterruptionsFromStartAndEnd() {
        // Night 22–06, data 23:54–05:00, earlier data until 20:55, nothing after.
        val ms = (0 until (5 * 60 + 6)).map { i -> minute(at(23, 54) + i * 60_000L, 30.0) }
        val prev = SyntheticData.ms(LocalDateTime.of(2026, 10, 9, 20, 55))
        val m = ChartModel.build(ChartData(night, ms, emptyList(), after, prevValidEndMs = prev, nextValidStartMs = null), zone, "loud_vehicle", 45.0)
        assertEquals(listOf("unterbrochen 20:55–23:54", "Messung bis 05:00"), m.summary.gapTexts)
        val first = ChartModel.build(ChartData(night, ms, emptyList(), after), zone, "loud_vehicle", 45.0)
        assertEquals("Messung ab 23:54", first.summary.gapTexts.first())
    }

    @Test
    fun gapLabelRule() {
        assertEquals(GapLabel.ONE_LINE, GapLabel.choose(100f, 80f, 45f, 8f))
        assertEquals(GapLabel.TWO_LINES, GapLabel.choose(60f, 80f, 45f, 8f))
        assertEquals(GapLabel.NONE, GapLabel.choose(50f, 80f, 45f, 8f))
    }

    // ---- events --------------------------------------------------------------------------------

    @Test
    fun eventFloorFiltersStoredEventsAtReadTime() {
        val evs = listOf(event(at(22, 1), 38.0), event(at(22, 2), 45.0), event(at(22, 3), 70.0, "loud_vehicle"))
        val m = build(minutes(22, 0, 10), evs, floor = 45.0)
        assertEquals(1, m.otherEvents.size)
        assertEquals(1, m.highlightedEvents.size)
        assertEquals(2, m.summary.eventCount)
        assertEquals(1, m.summary.highlightCount)
        // Same count as the night list for the same floor.
        val night = NightSummarizer.summarize(minutes(22, 0, 10), evs, zone, eventMinLevelDb = 45.0).single()
        assertEquals(night.eventCount, m.summary.eventCount)
    }

    @Test
    fun highlightSplitsEvents() {
        val evs = listOf(event(at(22, 1), 60.0, "voices"), event(at(22, 2), 61.0, "loud_vehicle"), event(at(22, 3), 62.0, "voices"))
        val m = build(minutes(22, 0, 10), evs, highlight = "voices")
        assertEquals(2, m.highlightedEvents.size)
        assertEquals(listOf("loud_vehicle"), m.otherEvents.map { it.dominantCategory })
    }

    @Test
    fun weekShowsOnlyLoudEventsCappedAt300() {
        val d = SyntheticData.weekData()
        val w = d.window
        val many = (0 until 400).map { i -> event(w.startMs + i * 1_200_000L, 61.0 + (i % 20)) } +
            (0 until 50).map { i -> event(w.startMs + i * 3_600_000L + 600_000, 55.0) } // below 60: never drawn
        val m = ChartModel.build(d.copy(events = many), zone, "loud_vehicle", 45.0)
        val drawn = m.otherEvents + m.highlightedEvents
        assertEquals(300, drawn.size)
        assertTrue(m.eventsCapped)
        assertTrue(drawn.all { it.lafMaxDb >= 66.0 }) // the loudest 300
        assertEquals(450, m.summary.eventCount) // the summary counts all events ≥ the floor
        val few = ChartModel.build(d.copy(events = many.take(10)), zone, "loud_vehicle", 45.0)
        assertTrue(!few.eventsCapped)
        // A floor above 60 dB raises the week threshold too.
        val high = ChartModel.build(d.copy(events = many), zone, "loud_vehicle", 75.0)
        assertTrue((high.otherEvents + high.highlightedEvents).all { it.lafMaxDb >= 75.0 })
    }

    // ---- hit testing -----------------------------------------------------------------------------

    @Test
    fun tapNearAnEventPrefersTheEvent() {
        val ms = minutes(22, 0, 60) { 40.0 }
        val e = event(at(22, 30), 70.0, "loud_vehicle")
        val m = build(ms, listOf(e))
        val g = ChartGeometry(m, PlotRect(40f, 10f, 1040f, 610f))
        val ex = g.xOf(e.startEpochMs)
        val ey = g.yOf(70.0)
        val hit = g.hitTest(ex + 20f, ey + 20f, eventRadiusPx = 42f) // 16 dp at 2.625
        assertIs<Selection.Event>(hit)
        assertEquals(e, hit.event)
        // Farther away: the nearest minute.
        val miss = g.hitTest(ex + 20f, g.yOf(40.0), eventRadiusPx = 42f)
        assertIs<Selection.Point>(miss)
        val t = g.timeAt(ex + 20f)
        assertTrue(t in miss.point.startMs until miss.point.endMs)
        // Outside the plot: nothing.
        assertEquals(null, g.hitTest(5f, 5f, 42f))
    }

    @Test
    fun highlightedEventWinsATie() {
        val t = at(22, 30)
        val a = event(t, 70.0, "road_traffic")
        val b = event(t, 70.0, "loud_vehicle")
        val g = ChartGeometry(build(minutes(22, 0, 60), listOf(a, b)), PlotRect(40f, 10f, 1040f, 610f))
        val hit = g.hitTest(g.xOf(t), g.yOf(70.0), 42f)
        assertIs<Selection.Event>(hit)
        assertEquals("loud_vehicle", hit.event.dominantCategory)
        assertTrue(hit.highlighted)
    }

    @Test
    fun tapInAGapSaysNoData() {
        val ms = minutes(22, 0, 30) + minutes(1, 0, 30)
        val g = ChartGeometry(build(ms), PlotRect(0f, 0f, 960f, 600f))
        assertIs<Selection.NoData>(g.hitTest(g.xOf(at(23, 30)), 300f, 42f))
    }

    @Test
    fun geometryPutsEventsInsideThePlotAndBandsTheRightWayUp() {
        val d = SyntheticData.fridayNight()
        val m = ChartModel.build(d, zone, "loud_vehicle", 45.0)
        val p = PlotRect(60f, 20f, 1000f, 600f)
        val g = ChartGeometry(m, p)
        for (dot in g.otherDots + g.highlightDots) assertTrue(p.contains(dot.x, dot.y), "dot off plot: $dot")
        for (band in g.bands) {
            val n = band.size / 4
            for (i in 0 until n) {
                val upper = band[2 * i + 1]
                val lower = band[band.size - 2 * i - 1]
                assertTrue(upper <= lower + 0.01f, "band inverted") // smaller y = higher level
            }
        }
        // The 25-minute gap at 03:10 is a gap and splits the line.
        assertTrue(m.gaps.any { it.span.startMs == SyntheticData.ms(SyntheticData.fridayGap.first) && it.span.endMs == SyntheticData.ms(SyntheticData.fridayGap.second) })
        assertEquals(2, m.segments.size)
        assertEquals(18, m.summary.eventCount)
        assertEquals(5, m.summary.highlightCount)
    }

    // ---- week aggregation ----------------------------------------------------------------------

    @Test
    fun weeklyHourAggregation() {
        val w = win.of(RangeMode.WEEK, LocalDate.of(2026, 10, 5))
        val h0 = w.startMs
        // Hour 0: 40 valid minutes, 20 at 40 dB and 20 at 50 dB, plus 20 invalid ones (ignored).
        val ms = (0 until 20).map { minute(h0 + it * 60_000L, 40.0, l10 = 42.0 + it % 3, l90 = 36.0 + it % 2) } +
            (20 until 40).map { minute(h0 + it * 60_000L, 50.0, l10 = 55.0, l90 = 45.0) } +
            (40 until 60).map { minute(h0 + it * 60_000L, 90.0, coverage = 0.2) } +
            // Hour 1: only 29 valid minutes → gap.
            (60 until 89).map { minute(h0 + it * 60_000L, 45.0) }
        val pts = ChartModel.hourlyPoints(w, ms, nowMs = w.startMs + 3 * 3_600_000L)
        val p0 = pts[0]
        assertTrue(p0.valid)
        assertEquals(10 * log10((10.0.pow(4) * 20 + 10.0.pow(5) * 20) / 40), p0.laeq, 1e-9)
        assertEquals(55.0, p0.l10) // max of minute L10
        assertEquals(36.0, p0.l90) // min of minute L90
        assertEquals(40 * 60.0, p0.validSeconds)
        assertTrue(!pts[1].valid)
        // Hours up to now only (plus none after).
        assertEquals(4, pts.size)
    }

    @Test
    fun weeklyEnergyMeanIsCoverageWeighted() {
        val w = win.of(RangeMode.WEEK, LocalDate.of(2026, 10, 5))
        val ms = (0 until 30).map { minute(w.startMs + it * 60_000L, 40.0) } +
            (30 until 60).map { minute(w.startMs + it * 60_000L, 60.0, coverage = 0.5) }
        val p = ChartModel.hourlyPoints(w, ms, w.endMs)[0]
        assertEquals(10 * log10((1e4 * 60 * 30 + 1e6 * 30 * 30) / (60.0 * 30 + 30.0 * 30)), p.laeq, 1e-9)
    }

    @Test
    fun summaryLaeqMatchesNightSummary() {
        val d = SyntheticData.fridayNight()
        val m = ChartModel.build(d, zone, "loud_vehicle", 45.0)
        val n = NightSummarizer.summarize(d.minutes, d.events, zone, eventMinLevelDb = 45.0).single()
        assertEquals(n.laeqDb, m.summary.laeqDb, 1e-9)
        assertEquals(n.coverage, m.summary.coverage, 1e-9)
        assertNotNull(m.summary.loudest)
        assertEquals(85.0, m.summary.loudest!!.lafMaxDb)
    }

    @Test
    fun tooltipTexts() {
        val d = SyntheticData.fridayNight()
        val m = ChartModel.build(d, zone, "loud_vehicle", 45.0)
        val e = d.events.maxBy { it.lafMaxDb }
        val lines = tooltipLines(Selection.Event(e, true), m)
        assertEquals("23:40:30 · Töff & Poser", lines[0])
        assertTrue("LAFmax 85.0 dB(A)" in lines && "unkalibriert" in lines, lines.toString())
        assertEquals(listOf("03:20", "keine Messung"), tooltipLines(Selection.NoData(SyntheticData.ms(LocalDateTime.of(2026, 10, 10, 3, 20))), m))
    }
}
