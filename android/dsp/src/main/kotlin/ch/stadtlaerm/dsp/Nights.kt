package ch.stadtlaerm.dsp

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.log10
import kotlin.math.pow

/** Summary of one night period (default 22:00–06:00, the Swiss night period). */
data class NightSummary(
    /** Date on which the night starts (the evening). */
    val nightOf: LocalDate,
    /** Seconds of valid audio in the minutes used (minutes with < 50 % coverage are excluded). */
    val measuredSeconds: Double,
    /** Actual length of the night in local time: 8 h, or 7 h / 9 h on DST-change nights. */
    val nominalSeconds: Double,
    val laeqDb: Double,
    /** Bursts: events (after the floor) without wind on the microphone (since v0.4.0). */
    val eventCount: Int,
    /** Bursts with LAFmax ≥ background + [NightSummarizer.strongMarginDb]. */
    val strongEventCount: Int,
    /** Time share per bucket id, weighted by measured duration of minutes with classifier data. */
    val categoryShares: Map<String, Double>,
    /** Bursts per category (wind events are not included). */
    val eventsByCategory: Map<String, Int>,
    /** The loudest burst (wind events are not considered). */
    val loudestEvent: NoiseEvent?,
    /** True only if every minute of the night was measured with a calibrated offset. */
    val allCalibrated: Boolean,
    /** Minutes left out because less than half of them had valid audio (mic silenced etc.). */
    val excludedMinutes: Int = 0,
    /** Bursts (events after the floor, without wind) per hour of valid measurement; NaN without valid time. */
    val eventsPerHour: Double = Double.NaN,
    /** "Dynamik": median over the valid minutes of L10 − L90 (dB); NaN without valid minutes. */
    val dynamicsDb: Double = Double.NaN,
    /** True if any minute of the night was re-evaluated with a later calibration («nachträglich kalibriert»). */
    val anyRecalibrated: Boolean = false,
    /** «Hintergrund (L90)», the hum under the bursts: median over the valid minutes of their L90 (dB). */
    val humDb: Double = Double.NaN,
    /** Events (after the floor) flagged as wind on the microphone: not in [eventCount]. */
    val windEventCount: Int = 0,
) {
    /** Share of the night covered by valid audio (0…1). */
    val coverage: Double get() = if (nominalSeconds > 0) (measuredSeconds / nominalSeconds).coerceIn(0.0, 1.0) else 0.0
}

/** Burstiness figures shared by the night list and the chart. */
object Dynamics {
    /**
     * The hum («Hintergrund (L90)»): median over [minutes] of their L90 — the steady level the
     * bursts stand out from (e.g. the distant highway). Minutes without L90 are skipped.
     */
    fun medianL90(minutes: List<MinuteRecord>): Double {
        val d = minutes.mapNotNull { m -> m.l90Db.takeUnless { it.isNaN() } }.sorted()
        if (d.isEmpty()) return Double.NaN
        val n = d.size
        return if (n % 2 == 1) d[n / 2] else (d[n / 2 - 1] + d[n / 2]) / 2
    }

    /** Events per hour of valid measurement time. */
    fun eventsPerHour(events: Int, validSeconds: Double): Double =
        if (validSeconds > 0) events / (validSeconds / 3600.0) else Double.NaN

    /**
     * Median over [minutes] of L10 − L90: how far the level swings within a minute. Steady traffic
     * gives a few dB, a quiet street with passing bursts much more. Minutes without both values are
     * skipped; the median of an even count is the mean of the middle two.
     */
    fun medianSpread(minutes: List<MinuteRecord>): Double {
        val d = minutes.mapNotNull { m -> (m.l10Db - m.l90Db).takeUnless { it.isNaN() } }.sorted()
        if (d.isEmpty()) return Double.NaN
        val n = d.size
        return if (n % 2 == 1) d[n / 2] else (d[n / 2 - 1] + d[n / 2]) / 2
    }
}

object NightSummarizer {
    const val NIGHT_START_HOUR = 22
    const val NIGHT_END_HOUR = 6
    const val strongMarginDb = 15.0
    /** Minutes with less valid audio than this fraction are left out of night summaries. */
    const val MIN_MINUTE_COVERAGE = 0.5

    /** Length of the night starting on [night] in local time (DST-aware). */
    fun nightLengthSeconds(night: LocalDate, zone: ZoneId, startHour: Int = NIGHT_START_HOUR, endHour: Int = NIGHT_END_HOUR): Double {
        val start = night.atTime(LocalTime.of(startHour, 0)).atZone(zone)
        val endDate = if (endHour <= startHour) night.plusDays(1) else night
        val end = endDate.atTime(LocalTime.of(endHour, 0)).atZone(zone)
        return Duration.between(start, end).seconds.toDouble()
    }

    /** The evening date of the night containing [epochMs], or null outside the night period. */
    fun nightOf(epochMs: Long, zone: ZoneId, startHour: Int = NIGHT_START_HOUR, endHour: Int = NIGHT_END_HOUR): LocalDate? {
        val t = Instant.ofEpochMilli(epochMs).atZone(zone)
        return when {
            t.hour >= startHour -> t.toLocalDate()
            t.hour < endHour -> t.toLocalDate().minusDays(1)
            else -> null
        }
    }

    /**
     * Groups minutes and events into nights, newest first. Minutes are assigned by their start.
     * Only events with LAFmax ≥ [eventMinLevelDb] are counted (the floor is applied at read time
     * too, so events stored before it existed are treated like new ones). Wind events are counted
     * separately ([NightSummary.windEventCount]) and left out of everything else.
     */
    fun summarize(
        minutes: List<MinuteRecord>,
        events: List<NoiseEvent>,
        zone: ZoneId,
        startHour: Int = NIGHT_START_HOUR,
        endHour: Int = NIGHT_END_HOUR,
        eventMinLevelDb: Double = Double.NEGATIVE_INFINITY,
    ): List<NightSummary> {
        val minutesByNight = minutes.groupBy { nightOf(it.startEpochMs, zone, startHour, endHour) }
        val eventsByNight = events.filter { it.reachesFloor(eventMinLevelDb) }.groupBy { nightOf(it.startEpochMs, zone, startHour, endHour) }
        val nights = (minutesByNight.keys + eventsByNight.keys).filterNotNull().toSortedSet().reversed()
        return nights.map { night ->
            val allMinutes = minutesByNight[night].orEmpty()
            val ms = allMinutes.filter { it.coverage >= MIN_MINUTE_COVERAGE && !it.laeqDb.isNaN() }
            val (wind, evs) = eventsByNight[night].orEmpty().partition { it.wind }
            var energy = 0.0
            var dur = 0.0
            val shareSum = LinkedHashMap<String, Double>()
            var classifiedDur = 0.0
            for (m in ms) {
                // Levels of a minute describe its valid seconds only, so weight by those.
                energy += 10.0.pow(m.laeqDb / 10.0) * m.validSeconds
                dur += m.validSeconds
                if (m.categoryShares.isNotEmpty()) {
                    classifiedDur += m.validSeconds
                    for ((k, v) in m.categoryShares) shareSum[k] = (shareSum[k] ?: 0.0) + v * m.validSeconds
                }
            }
            val shares = if (classifiedDur > 0) shareSum.mapValues { it.value / classifiedDur } else emptyMap()
            val byCat = evs.groupingBy { it.dominantCategory ?: "n/a" }.eachCount()
            NightSummary(
                nightOf = night,
                measuredSeconds = dur,
                nominalSeconds = nightLengthSeconds(night, zone, startHour, endHour),
                laeqDb = if (dur > 0) 10 * log10(energy / dur) else Double.NaN,
                eventCount = evs.size,
                strongEventCount = evs.count { it.lafMaxDb >= it.backgroundDb + strongMarginDb },
                categoryShares = shares,
                eventsByCategory = byCat,
                loudestEvent = evs.maxByOrNull { it.lafMaxDb },
                allCalibrated = ms.isNotEmpty() && ms.all { it.calibrated },
                excludedMinutes = allMinutes.size - ms.size,
                eventsPerHour = Dynamics.eventsPerHour(evs.size, dur),
                dynamicsDb = Dynamics.medianSpread(ms),
                anyRecalibrated = allMinutes.any { it.recalibrated },
                humDb = Dynamics.medianL90(ms),
                windEventCount = wind.size,
            )
        }
    }
}
