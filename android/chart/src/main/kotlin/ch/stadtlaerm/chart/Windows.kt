package ch.stadtlaerm.chart

import ch.stadtlaerm.dsp.NightSummarizer
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/** The three chart ranges. */
enum class RangeMode { NIGHT, DAY, WEEK }

/**
 * One chart window [startMs, endMs) in local time.
 *
 * [anchor] identifies it: the evening date for a night, the date for a day, the Monday for a week.
 */
data class TimeWindow(val mode: RangeMode, val anchor: LocalDate, val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
    operator fun contains(t: Long): Boolean = t in startMs until endMs
}

/** A labelled x position. */
data class AxisLabel(val ms: Long, val text: String)

/** A time span [startMs, endMs). */
data class Span(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = endMs - startMs
}

/**
 * Window arithmetic in the local [zone] (DST-aware). A night is 22:00–06:00 local time, as in
 * [NightSummarizer]; a day 00:00–24:00; a week Monday 00:00 – next Monday 00:00.
 */
class Windows(val zone: ZoneId) {

    fun of(mode: RangeMode, date: LocalDate): TimeWindow = when (mode) {
        RangeMode.NIGHT -> TimeWindow(
            mode, date,
            ms(date.atTime(LocalTime.of(NightSummarizer.NIGHT_START_HOUR, 0)).atZone(zone)),
            ms(date.plusDays(1).atTime(LocalTime.of(NightSummarizer.NIGHT_END_HOUR, 0)).atZone(zone)),
        )
        RangeMode.DAY -> TimeWindow(mode, date, ms(date.atStartOfDay(zone)), ms(date.plusDays(1).atStartOfDay(zone)))
        RangeMode.WEEK -> {
            val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            TimeWindow(mode, monday, ms(monday.atStartOfDay(zone)), ms(monday.plusWeeks(1).atStartOfDay(zone)))
        }
    }

    /** [n] windows later (negative: earlier). */
    fun shift(w: TimeWindow, n: Int): TimeWindow = when (w.mode) {
        RangeMode.NIGHT, RangeMode.DAY -> of(w.mode, w.anchor.plusDays(n.toLong()))
        RangeMode.WEEK -> of(w.mode, w.anchor.plusWeeks(n.toLong()))
    }

    /**
     * The window of [mode] containing [t]. For a night outside the night period, the most recent
     * night that has started (at 14:00 on Tuesday: the night Monday → Tuesday).
     */
    fun containing(mode: RangeMode, t: Long): TimeWindow {
        val local = Instant.ofEpochMilli(t).atZone(zone)
        return when (mode) {
            RangeMode.NIGHT -> of(mode, if (local.hour >= NightSummarizer.NIGHT_START_HOUR) local.toLocalDate() else local.toLocalDate().minusDays(1))
            else -> of(mode, local.toLocalDate())
        }
    }

    /**
     * Window shown when the screen opens: the running night if [nowMs] is inside a night, otherwise
     * the most recent night with data, otherwise the most recent night.
     */
    fun defaultWindow(nowMs: Long, latestNightWithData: LocalDate?): TimeWindow {
        if (NightSummarizer.nightOf(nowMs, zone) != null) return containing(RangeMode.NIGHT, nowMs)
        val latest = containing(RangeMode.NIGHT, nowMs)
        return if (latestNightWithData != null && latestNightWithData <= latest.anchor) of(RangeMode.NIGHT, latestNightWithData) else latest
    }

    /** Switches range keeping roughly the same time: the window of [mode] containing the old midpoint (never the future). */
    fun switchMode(w: TimeWindow, mode: RangeMode, nowMs: Long): TimeWindow {
        if (mode == w.mode) return w
        val mid = w.startMs + w.durationMs / 2
        return containing(mode, minOf(mid, nowMs))
    }

    /** Later windows are allowed only while they have started. */
    fun canGoNext(w: TimeWindow, nowMs: Long): Boolean = shift(w, 1).startMs <= nowMs

    /** «Nacht Mo 6.→Di 7.10.», «Di 7.10.», «30.9.–6.10.» */
    fun label(w: TimeWindow): String = when (w.mode) {
        RangeMode.NIGHT -> {
            val a = w.anchor
            val b = a.plusDays(1)
            val first = if (a.monthValue == b.monthValue) "${a.dayOfMonth}." else "${a.dayOfMonth}.${a.monthValue}."
            "Nacht ${weekday(a)} $first→${weekday(b)} ${b.dayOfMonth}.${b.monthValue}."
        }
        RangeMode.DAY -> "${weekday(w.anchor)} ${w.anchor.dayOfMonth}.${w.anchor.monthValue}."
        RangeMode.WEEK -> {
            val sunday = w.anchor.plusDays(6)
            "${w.anchor.dayOfMonth}.${w.anchor.monthValue}.–${sunday.dayOfMonth}.${sunday.monthValue}."
        }
    }

    /** Vertical grid positions of the x axis. */
    fun gridTicks(w: TimeWindow): List<Long> = when (w.mode) {
        RangeMode.NIGHT -> hourly(w)
        RangeMode.DAY -> (0..8).map { h -> if (h == 8) w.endMs else ms(w.anchor.atTime(h * 3, 0).atZone(zone)) }.distinct()
        RangeMode.WEEK -> (0..7).map { d -> ms(w.anchor.plusDays(d.toLong()).atStartOfDay(zone)) }
    }

    /**
     * x-axis labels: hours for a night (22, 23, 0, … 6; a DST night repeats or skips an hour),
     * every 3 h for a day (0 … 24), weekday names centred on each day for a week.
     */
    fun axisLabels(w: TimeWindow): List<AxisLabel> = when (w.mode) {
        RangeMode.NIGHT -> hourly(w).map { AxisLabel(it, Instant.ofEpochMilli(it).atZone(zone).hour.toString()) }
        RangeMode.DAY -> gridTicks(w).mapIndexed { i, t -> AxisLabel(t, if (t == w.endMs) "24" else (i * 3).toString()) }
        RangeMode.WEEK -> (0 until 7).map { d ->
            val day = w.anchor.plusDays(d.toLong())
            val s = ms(day.atStartOfDay(zone))
            val e = ms(day.plusDays(1).atStartOfDay(zone))
            AxisLabel(s + (e - s) / 2, weekday(day))
        }
    }

    /** Night periods (22–06) intersecting [w], clipped to it. */
    fun nightSpans(w: TimeWindow): List<Span> {
        val first = Instant.ofEpochMilli(w.startMs).atZone(zone).toLocalDate().minusDays(1)
        val last = Instant.ofEpochMilli(w.endMs).atZone(zone).toLocalDate()
        val out = ArrayList<Span>()
        var d = first
        while (d <= last) {
            val n = of(RangeMode.NIGHT, d)
            val s = maxOf(n.startMs, w.startMs)
            val e = minOf(n.endMs, w.endMs)
            if (e > s) out += Span(s, e)
            d = d.plusDays(1)
        }
        return out
    }

    private fun hourly(w: TimeWindow): List<Long> {
        val out = ArrayList<Long>()
        var t = w.startMs
        while (t <= w.endMs) { out += t; t += HOUR_MS }
        return out
    }

    fun localDate(t: Long): LocalDate = Instant.ofEpochMilli(t).atZone(zone).toLocalDate()

    companion object {
        const val MINUTE_MS = 60_000L
        const val HOUR_MS = 3_600_000L
        private val WEEKDAYS = arrayOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")
        fun weekday(d: LocalDate): String = WEEKDAYS[d.dayOfWeek.value - 1]
        private fun ms(z: ZonedDateTime): Long = z.toInstant().toEpochMilli()
    }
}
