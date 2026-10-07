package ch.stadtlaerm.chart

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WindowsTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val win = Windows(zone)
    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    private fun hours(w: TimeWindow) = w.durationMs / 3_600_000.0

    @Test
    fun nightWindowIs22To06Local() {
        val w = win.of(RangeMode.NIGHT, LocalDate.of(2026, 10, 6))
        assertEquals(ms(2026, 10, 6, 22), w.startMs)
        assertEquals(ms(2026, 10, 7, 6), w.endMs)
        assertEquals("Nacht Di 6.→Mi 7.10.", win.label(w))
        assertEquals("Nacht Mi 30.9.→Do 1.10.", win.label(win.of(RangeMode.NIGHT, LocalDate.of(2026, 9, 30))))
        assertEquals(listOf("22", "23", "0", "1", "2", "3", "4", "5", "6"), win.axisLabels(w).map { it.text })
    }

    @Test
    fun dstNightsAreNineAndSevenHours() {
        // Last Sunday of October 2026 = 25 Oct: the night Sat 24 → Sun 25 has 9 hours.
        val autumn = win.of(RangeMode.NIGHT, LocalDate.of(2026, 10, 24))
        assertEquals(9.0, hours(autumn))
        assertEquals(listOf("22", "23", "0", "1", "2", "2", "3", "4", "5", "6"), win.axisLabels(autumn).map { it.text })
        // Last Sunday of March 2027 = 28 Mar: 7 hours.
        val spring = win.of(RangeMode.NIGHT, LocalDate.of(2027, 3, 27))
        assertEquals(7.0, hours(spring))
        assertEquals(listOf("22", "23", "0", "1", "3", "4", "5", "6"), win.axisLabels(spring).map { it.text })
        // Same lengths as the night summaries use.
        assertEquals(9 * 3600.0, ch.stadtlaerm.dsp.NightSummarizer.nightLengthSeconds(LocalDate.of(2026, 10, 24), zone))
    }

    @Test
    fun dayAndWeekWindowsAcrossDst() {
        val day = win.of(RangeMode.DAY, LocalDate.of(2026, 10, 25))
        assertEquals(25.0, hours(day))
        assertEquals("So 25.10.", win.label(day))
        assertEquals(listOf("0", "3", "6", "9", "12", "15", "18", "21", "24"), win.axisLabels(day).map { it.text })
        assertEquals(day.endMs, win.gridTicks(day).last())

        val week = win.of(RangeMode.WEEK, LocalDate.of(2026, 10, 22)) // a Thursday
        assertEquals(LocalDate.of(2026, 10, 19), week.anchor)
        assertEquals(7 * 24 + 1.0, hours(week))
        assertEquals("19.10.–25.10.", win.label(week))
        assertEquals(listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"), win.axisLabels(week).map { it.text })
        assertEquals(8, win.gridTicks(week).size)
        // Monday 28 Sep – Sunday 4 Oct 2026 (a week across a month boundary).
        assertEquals("28.9.–4.10.", win.label(win.of(RangeMode.WEEK, LocalDate.of(2026, 10, 1))))
    }

    @Test
    fun shiftingMovesByWholeWindows() {
        val n = win.of(RangeMode.NIGHT, LocalDate.of(2026, 10, 24))
        assertEquals(LocalDate.of(2026, 10, 25), win.shift(n, 1).anchor)
        assertEquals(n.endMs + 16 * 3_600_000L, win.shift(n, 1).startMs) // Sun 06:00 → Sun 22:00
        val w = win.of(RangeMode.WEEK, LocalDate.of(2026, 10, 19))
        assertEquals(w.endMs, win.shift(w, 1).startMs)
        assertEquals(w.startMs, win.shift(w, -1).endMs)
    }

    @Test
    fun currentNightWhileInsideItAndLatestNightOtherwise() {
        // 02:30 on Wednesday: inside the night Tue → Wed.
        val inside = ms(2026, 10, 7, 2, 30)
        assertEquals(LocalDate.of(2026, 10, 6), win.containing(RangeMode.NIGHT, inside).anchor)
        assertEquals(LocalDate.of(2026, 10, 6), win.defaultWindow(inside, latestNightWithData = LocalDate.of(2026, 10, 2)).anchor)
        // 23:10: the night that has just started.
        assertEquals(LocalDate.of(2026, 10, 7), win.defaultWindow(ms(2026, 10, 7, 23, 10), null).anchor)
        // 14:00: not in a night → most recent night with data.
        val afternoon = ms(2026, 10, 7, 14)
        assertEquals(LocalDate.of(2026, 10, 3), win.defaultWindow(afternoon, LocalDate.of(2026, 10, 3)).anchor)
        assertEquals(LocalDate.of(2026, 10, 6), win.defaultWindow(afternoon, null).anchor)
        // Data claimed in the future (clock change) is ignored.
        assertEquals(LocalDate.of(2026, 10, 6), win.defaultWindow(afternoon, LocalDate.of(2026, 10, 9)).anchor)
    }

    @Test
    fun nextIsDisabledWhenTheNextWindowHasNotStarted() {
        val now = ms(2026, 10, 7, 2, 30)
        val running = win.containing(RangeMode.NIGHT, now)
        assertFalse(win.canGoNext(running, now))
        assertTrue(win.canGoNext(win.shift(running, -1), now))
        val today = win.containing(RangeMode.DAY, now)
        assertFalse(win.canGoNext(today, now))
        // At 14:00 the latest night is over but the next one has not begun.
        val afternoon = ms(2026, 10, 7, 14)
        assertFalse(win.canGoNext(win.containing(RangeMode.NIGHT, afternoon), afternoon))
        assertFalse(win.canGoNext(win.containing(RangeMode.WEEK, afternoon), afternoon))
    }

    @Test
    fun switchingModeKeepsTheTimeButNeverTheFuture() {
        val now = ms(2026, 10, 9, 12)
        val night = win.of(RangeMode.NIGHT, LocalDate.of(2026, 10, 6)) // midpoint Wed 02:00
        assertEquals(LocalDate.of(2026, 10, 7), win.switchMode(night, RangeMode.DAY, now).anchor)
        assertEquals(LocalDate.of(2026, 10, 5), win.switchMode(night, RangeMode.WEEK, now).anchor)
        val week = win.of(RangeMode.WEEK, LocalDate.of(2026, 10, 5)) // midpoint Thu 12:00, before now (Fri)
        assertEquals(LocalDate.of(2026, 10, 8), win.switchMode(week, RangeMode.DAY, now).anchor)
        assertEquals(LocalDate.of(2026, 10, 7), win.switchMode(week, RangeMode.NIGHT, now).anchor)
        // A week whose midpoint is in the future is clamped to now.
        val earlyNow = ms(2026, 10, 6, 9)
        assertEquals(LocalDate.of(2026, 10, 6), win.switchMode(week, RangeMode.DAY, earlyNow).anchor)
    }

    @Test
    fun nightSpansInDayAndWeek() {
        val day = win.of(RangeMode.DAY, LocalDate.of(2026, 10, 7))
        val spans = win.nightSpans(day)
        assertEquals(2, spans.size)
        assertEquals(day.startMs, spans[0].startMs)
        assertEquals(ms(2026, 10, 7, 6), spans[0].endMs)
        assertEquals(ms(2026, 10, 7, 22), spans[1].startMs)
        assertEquals(day.endMs, spans[1].endMs)
        assertEquals(8, win.nightSpans(win.of(RangeMode.WEEK, LocalDate.of(2026, 10, 5))).size)
        val night = win.of(RangeMode.NIGHT, LocalDate.of(2026, 10, 6))
        assertEquals(listOf(Span(night.startMs, night.endMs)), win.nightSpans(night))
    }

    @Test
    fun weekdayAbbreviations() {
        assertEquals("Fr", Windows.weekday(LocalDate.of(2026, 10, 9)))
        assertEquals("Sa", Windows.weekday(Instant.ofEpochMilli(ms(2026, 10, 10, 1)).atZone(zone).toLocalDate()))
    }
}
