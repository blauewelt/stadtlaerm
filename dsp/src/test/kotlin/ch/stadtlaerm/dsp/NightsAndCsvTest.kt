package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.classify.ClassifierPreprocessor
import ch.stadtlaerm.dsp.classify.LabelScore
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NightsAndCsvTest {
    private val zone = ZoneId.of("Europe/Zurich")

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()

    private fun minute(t: Long, laeq: Double, shares: Map<String, Double> = mapOf("road_traffic" to 1.0)) = MinuteRecord(
        t, Iso.format(t, zone), 60.0, laeq, laeq + 5, laeq - 5, laeq + 4, laeq + 2, laeq, laeq - 2, 0,
        shares.maxByOrNull { it.value }?.key, shares, 60, 1L, 110.0, "UNPROCESSED", true,
    )

    private fun event(t: Long, max: Double, bg: Double, cat: String) = NoiseEvent(
        t, Iso.format(t, zone, true), 2.0, max, max + 1, bg, 10.0, cat, 0.8f,
        listOf(LabelScore("Motorcycle", 0.8f)), 2, 1L, "UNPROCESSED", true,
    )

    @Test
    fun nightAssignment() {
        assertEquals(LocalDate.of(2026, 10, 2), NightSummarizer.nightOf(ms(2026, 10, 2, 22, 0), zone))
        assertEquals(LocalDate.of(2026, 10, 2), NightSummarizer.nightOf(ms(2026, 10, 3, 5, 59), zone))
        assertNull(NightSummarizer.nightOf(ms(2026, 10, 3, 6, 0), zone))
        assertNull(NightSummarizer.nightOf(ms(2026, 10, 2, 21, 59), zone))
    }

    @Test
    fun nightSummary() {
        val minutes = listOf(
            minute(ms(2026, 10, 2, 21, 59), 70.0), // evening, not night
            minute(ms(2026, 10, 2, 22, 0), 50.0),
            minute(ms(2026, 10, 2, 22, 1), 60.0, mapOf("loud_vehicle" to 0.5, "unclassified" to 0.5)),
            minute(ms(2026, 10, 3, 5, 59), 50.0),
            minute(ms(2026, 10, 3, 22, 30), 40.0), // next night (Saturday)
        )
        val events = listOf(
            event(ms(2026, 10, 2, 23, 10), 80.0, 50.0, "loud_vehicle"), // strong (≥ bg + 15)
            event(ms(2026, 10, 3, 1, 0), 62.0, 50.0, "road_traffic"), // not strong
            event(ms(2026, 10, 3, 12, 0), 99.0, 50.0, "music"), // daytime: ignored
        )
        val nights = NightSummarizer.summarize(minutes, events, zone)
        assertEquals(2, nights.size)
        assertEquals(LocalDate.of(2026, 10, 3), nights[0].nightOf) // newest first
        val fri = nights[1]
        assertEquals(LocalDate.of(2026, 10, 2), fri.nightOf)
        assertEquals(180.0, fri.measuredSeconds)
        assertEquals(8 * 3600.0, fri.nominalSeconds)
        val expected = 10 * log10((1e5 + 1e6 + 1e5) / 3)
        assertEquals(expected, fri.laeqDb, 1e-9)
        assertEquals(2, fri.eventCount)
        assertEquals(1, fri.strongEventCount)
        assertEquals(80.0, fri.loudestEvent!!.lafMaxDb)
        assertEquals(mapOf("loud_vehicle" to 1, "road_traffic" to 1), fri.eventsByCategory)
        assertEquals(1.0 / 6, fri.categoryShares.getValue("loud_vehicle"), 1e-9)
        assertTrue(fri.allCalibrated)
    }

    @Test
    fun csvQuotesLabelsWithCommas() {
        val t = ms(2026, 10, 2, 23, 10)
        val e = event(t, 80.0, 50.0, "road_traffic").copy(
            topLabels = listOf(LabelScore("Vehicle horn, car horn, honking", 0.5f), LabelScore("Car", 0.3f)),
        )
        val csv = Csv.events(listOf(e)).lines()
        assertTrue(csv[1].contains("\"Vehicle horn, car horn, honking\",0.500,Car,0.300,,"), csv[1])
        assertTrue(csv[1].startsWith("2026-10-02T23:10:00.000+02:00,2.000,80.0,81.0,50.0,10.0,road_traffic"), csv[1])
        val mcsv = Csv.minutes(listOf(minute(t, 55.0)), listOf("road_traffic", "unclassified")).lines()
        assertEquals(
            "start,duration_s,laeq_db,lafmax_db,lafmin_db,l1_db,l10_db,l50_db,l90_db,event_count,dominant_category," +
                "share_road_traffic,share_unclassified,classifier_frames,calibration_id,calibration_offset_db,audio_source,calibrated",
            mcsv[0],
        )
        assertEquals("2026-10-02T23:10:00+02:00,60.0,55.0,60.0,50.0,59.0,57.0,55.0,53.0,0,road_traffic,1.000,,60,1,110.00,UNPROCESSED,true", mcsv[1])
    }

    @Test
    fun classifierNormalisation() {
        fun rmsDb(x: FloatArray) = 10 * log10(x.map { it.toDouble() * it }.average())
        val quiet = TestSignals.whiteNoise(1e-3, 0.975, fs = 16_000) // −60 dBFS
        assertEquals(30.0, ClassifierPreprocessor.normalize(quiet), 0.2)
        assertEquals(-30.0, rmsDb(quiet), 0.2)
        val veryQuiet = TestSignals.whiteNoise(1e-5, 0.975, fs = 16_000) // −100 dBFS → capped +40 dB
        assertEquals(40.0, ClassifierPreprocessor.normalize(veryQuiet), 1e-9)
        val loud = TestSignals.whiteNoise(0.3, 0.975, fs = 16_000)
        val copy = loud.copyOf()
        assertEquals(0.0, ClassifierPreprocessor.normalize(loud)) // never attenuates
        assertTrue(loud.contentEquals(copy))
    }
}
