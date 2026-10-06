package ch.stadtlaerm.dsp

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.log10
import kotlin.math.pow

/** Summary of one night period (default 22:00–06:00, the Swiss night period). */
data class NightSummary(
    /** Date on which the night starts (the evening). */
    val nightOf: LocalDate,
    val measuredSeconds: Double,
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
)

object NightSummarizer {
    const val NIGHT_START_HOUR = 22
    const val NIGHT_END_HOUR = 6
    const val strongMarginDb = 15.0

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
        val nominal = ((24 - startHour + endHour) % 24) * 3600.0
        val minutesByNight = minutes.groupBy { nightOf(it.startEpochMs, zone, startHour, endHour) }
        val eventsByNight = events.groupBy { nightOf(it.startEpochMs, zone, startHour, endHour) }
        val nights = (minutesByNight.keys + eventsByNight.keys).filterNotNull().toSortedSet().reversed()
        return nights.map { night ->
            val ms = minutesByNight[night].orEmpty()
            val evs = eventsByNight[night].orEmpty()
            var energy = 0.0
            var dur = 0.0
            val shareSum = LinkedHashMap<String, Double>()
            var classifiedDur = 0.0
            for (m in ms) {
                energy += 10.0.pow(m.laeqDb / 10.0) * m.durationSeconds
                dur += m.durationSeconds
                if (m.categoryShares.isNotEmpty()) {
                    classifiedDur += m.durationSeconds
                    for ((k, v) in m.categoryShares) shareSum[k] = (shareSum[k] ?: 0.0) + v * m.durationSeconds
                }
            }
            val shares = if (classifiedDur > 0) shareSum.mapValues { it.value / classifiedDur } else emptyMap()
            val byCat = evs.groupingBy { it.dominantCategory ?: "n/a" }.eachCount()
            NightSummary(
                nightOf = night,
                measuredSeconds = dur,
                nominalSeconds = nominal,
                laeqDb = if (dur > 0) 10 * log10(energy / dur) else Double.NaN,
                eventCount = evs.size,
                strongEventCount = evs.count { it.lafMaxDb >= it.backgroundDb + strongMarginDb },
                categoryShares = shares,
                eventsByCategory = byCat,
                loudestEvent = evs.maxByOrNull { it.lafMaxDb },
                allCalibrated = ms.isNotEmpty() && ms.all { it.calibrated },
            )
        }
    }
}
