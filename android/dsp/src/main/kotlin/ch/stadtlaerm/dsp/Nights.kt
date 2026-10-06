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
    val eventCount: Int,
    /** Events with LAFmax ≥ background + [NightSummarizer.strongMarginDb]. */
    val strongEventCount: Int,
    /** Time share per bucket id, weighted by measured duration of minutes with classifier data. */
    val categoryShares: Map<String, Double>,
    val eventsByCategory: Map<String, Int>,
    val loudestEvent: NoiseEvent?,
    /** True only if every minute of the night was measured with a calibrated offset. */
    val allCalibrated: Boolean,
    /** Minutes left out because less than half of them had valid audio (mic silenced etc.). */
    val excludedMinutes: Int = 0,
) {
    /** Share of the night covered by valid audio (0…1). */
    val coverage: Double get() = if (nominalSeconds > 0) (measuredSeconds / nominalSeconds).coerceIn(0.0, 1.0) else 0.0
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

    /** Groups minutes and events into nights, newest first. Minutes are assigned by their start. */
    fun summarize(
        minutes: List<MinuteRecord>,
        events: List<NoiseEvent>,
        zone: ZoneId,
        startHour: Int = NIGHT_START_HOUR,
        endHour: Int = NIGHT_END_HOUR,
    ): List<NightSummary> {
        val minutesByNight = minutes.groupBy { nightOf(it.startEpochMs, zone, startHour, endHour) }
        val eventsByNight = events.groupBy { nightOf(it.startEpochMs, zone, startHour, endHour) }
        val nights = (minutesByNight.keys + eventsByNight.keys).filterNotNull().toSortedSet().reversed()
        return nights.map { night ->
            val allMinutes = minutesByNight[night].orEmpty()
            val ms = allMinutes.filter { it.coverage >= MIN_MINUTE_COVERAGE && !it.laeqDb.isNaN() }
            val evs = eventsByNight[night].orEmpty()
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
            )
        }
    }
}
