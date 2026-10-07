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
    fun nightSummaryAppliesEventFloorAtReadTime() {
        val minutes = listOf(minute(ms(2026, 10, 2, 23, 0), 30.0))
        val events = listOf(
            event(ms(2026, 10, 2, 23, 10), 38.0, 22.0, "unclassified"), // keystroke-like, old event (no floor stored)
            event(ms(2026, 10, 2, 23, 20), 45.0, 22.0, "voices"), // exactly at the floor: kept
            event(ms(2026, 10, 3, 1, 0), 72.0, 30.0, "loud_vehicle"),
        )
        val all = NightSummarizer.summarize(minutes, events, zone).single()
        assertEquals(3, all.eventCount)
        val floored = NightSummarizer.summarize(minutes, events, zone, eventMinLevelDb = 45.0).single()
        assertEquals(2, floored.eventCount)
        assertEquals(mapOf("voices" to 1, "loud_vehicle" to 1), floored.eventsByCategory)
        assertEquals(72.0, floored.loudestEvent!!.lafMaxDb)
        // A night whose only events are below the floor still exists (it has minutes) with 0 events.
        val high = NightSummarizer.summarize(minutes, events, zone, eventMinLevelDb = 80.0).single()
        assertEquals(0, high.eventCount)
        assertNull(high.loudestEvent)
    }

    @Test
    fun eventsPerHourAndDynamics() {
        // 30 valid minutes (0.5 h) with L10 − L90 spreads 2, 4, 6 … (minute() gives L10 − L90 = 4 dB).
        val base = ms(2026, 10, 2, 23, 0)
        val minutes = (0 until 30).map { i ->
            minute(base + i * 60_000L, 40.0).copy(l10Db = 40.0 + (i % 3) * 2, l90Db = 38.0 - (i % 3) * 2)
        } + minute(base + 30 * 60_000L, 40.0).copy(validSeconds = 10.0) // < 50 %: not counted
        val events = (0 until 12).map { event(base + it * 120_000L, 60.0, 40.0, "road_traffic") } +
            event(base + 61_000L, 35.0, 30.0, "voices") // below the floor
        val n = NightSummarizer.summarize(minutes, events, zone, eventMinLevelDb = 45.0).single()
        assertEquals(24.0, n.eventsPerHour, 1e-9) // 12 events in 0.5 h of valid measurement
        // Spreads: 2, 6, 10 each ten times → median 6.
        assertEquals(6.0, n.dynamicsDb, 1e-9)
        assertEquals(5.0, Dynamics.medianSpread(listOf(minute(base, 40.0).copy(l10Db = 44.0, l90Db = 40.0), minute(base, 40.0).copy(l10Db = 46.0, l90Db = 40.0))))
        assertTrue(Dynamics.eventsPerHour(3, 0.0).isNaN())
        assertTrue(Dynamics.medianSpread(emptyList()).isNaN())
    }

    @Test
    fun csvQuotesLabelsWithCommas() {
        val t = ms(2026, 10, 2, 23, 10)
        val e = event(t, 80.0, 50.0, "road_traffic").copy(
            topLabels = listOf(LabelScore("Vehicle horn, car horn, honking", 0.5f), LabelScore("Car", 0.3f)),
        )
        val csv = Csv.events(listOf(e)).lines()
        assertTrue(csv[1].contains("\"Vehicle horn, car horn, honking\",0.500,Car,0.300,,"), csv[1])
        // Events recorded before v0.3.0 have no floor: empty min_level_db.
        assertTrue(csv[1].startsWith("2026-10-02T23:10:00.000+02:00,2.000,80.0,81.0,50.0,10.0,,road_traffic"), csv[1])
        assertTrue(csv[0].startsWith("start,duration_s,lafmax_db,sel_db,background_db,threshold_db,min_level_db,dominant_category,"), csv[0])
        val withFloor = Csv.events(listOf(e.copy(minLevelDb = 45.0))).lines()
        assertTrue(withFloor[1].startsWith("2026-10-02T23:10:00.000+02:00,2.000,80.0,81.0,50.0,10.0,45.0,road_traffic"), withFloor[1])
        val mcsv = Csv.minutes(listOf(minute(t, 55.0)), listOf("road_traffic", "unclassified")).lines()
        assertEquals(
            "start,duration_s,valid_s,coverage,laeq_db,lafmax_db,lafmin_db,l1_db,l10_db,l50_db,l90_db,event_count,dominant_category," +
                "share_road_traffic,share_unclassified,classifier_frames,calibration_id,calibration_offset_db,audio_source,calibrated," +
                "clock_corrections,orig_laeq_db,orig_lafmax_db,orig_lafmin_db,orig_l1_db,orig_l10_db,orig_l50_db,orig_l90_db," +
                "recalibrated_from_id,recalibration_offset_db",
            mcsv[0],
        )
        assertEquals("2026-10-02T23:10:00+02:00,60.0,60.0,1.000,55.0,60.0,50.0,59.0,57.0,55.0,53.0,0,road_traffic,1.000,,60,1,110.00,UNPROCESSED,true,0,,,,,,,,,", mcsv[1])
    }

    @Test
    fun csvAppendsRecalibrationColumns() {
        val t = ms(2026, 10, 2, 23, 10)
        val target = ch.stadtlaerm.dsp.calibration.Recalibration.Target(7L, 121.95, "UNPROCESSED")
        val raw = minute(t, 55.0).copy(calibrationId = null, calibrationOffsetDb = 112.35, calibrated = false)
        val m = ch.stadtlaerm.dsp.calibration.Recalibration.recalibrate(raw, target)
        val mcsv = Csv.minutes(listOf(m), listOf("road_traffic")).lines()
        val header = mcsv[0].split(",")
        // Existing columns keep their order; the new ones are appended.
        assertEquals(
            listOf("orig_laeq_db", "orig_lafmax_db", "orig_lafmin_db", "orig_l1_db", "orig_l10_db", "orig_l50_db", "orig_l90_db",
                "recalibrated_from_id", "recalibration_offset_db"),
            header.takeLast(9),
        )
        assertEquals("clock_corrections", header[header.size - 10])
        val row = mcsv[1].split(",")
        assertEquals(header.size, row.size)
        assertEquals("64.6", row[header.indexOf("laeq_db")])
        assertEquals("7", row[header.indexOf("calibration_id")])
        assertEquals("121.95", row[header.indexOf("calibration_offset_db")])
        assertEquals("true", row[header.indexOf("calibrated")])
        assertEquals(listOf("55.0", "60.0", "50.0", "59.0", "57.0", "55.0", "53.0", "default", "112.35"), row.takeLast(9))

        val e = ch.stadtlaerm.dsp.calibration.Recalibration.recalibrate(event(t, 60.0, 40.0, "road_traffic"), 110.0, target)
        val ecsv = Csv.events(listOf(e)).lines()
        val eh = ecsv[0].split(",")
        assertEquals(listOf("orig_lafmax_db", "orig_sel_db", "orig_background_db", "recalibrated_from_id", "recalibration_offset_db"), eh.takeLast(5))
        assertEquals("calibrated", eh[eh.size - 6])
        val er = ecsv[1].split(",")
        assertEquals(eh.size, er.size)
        assertEquals("72.0", er[eh.indexOf("lafmax_db")])
        assertEquals(listOf("60.0", "61.0", "40.0", "1", "110.00"), er.takeLast(5))
        // Not re-evaluated: the new columns are empty.
        assertTrue(Csv.events(listOf(event(t, 60.0, 40.0, "road_traffic"))).lines()[1].endsWith(",true,,,,,"))
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

    /** The classifier gain is applied to a copied window only and never reaches the levels. */
    @Test
    fun classifierNormalisationDoesNotAffectLevels() {
        val signal = TestSignals.whiteNoise(3e-4, 70.0) // ≈ −70 dBFS: maximum classifier gain
        fun run(takeWindows: Boolean): List<MinuteRecord> {
            val minutes = ArrayList<MinuteRecord>()
            val clock = SimClock(1_700_000_000_000L)
            val engine = MeasurementEngine(
                EngineConfig(zone = zone), TestSignals.mapper(),
                object : MeasurementEngine.Listener { override fun onMinute(minute: MinuteRecord) { minutes += minute } },
                clock::now,
            )
            val window = FloatArray(ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)
            var p = 0
            var gains = 0
            while (p < signal.size) {
                val n = minOf(6000, signal.size - p)
                clock.advance(n)
                engine.process(signal.copyOfRange(p, p + n), n)
                p += n
                if (takeWindows && engine.classifierDue() && engine.copyClassifierWindow(window) > 0) {
                    if (ClassifierPreprocessor.normalize(window) > 0.0) gains++
                }
            }
            engine.stop()
            if (takeWindows) assertTrue(gains > 30, "normalisation ran $gains times")
            return minutes
        }
        val plain = run(false)
        val withClassifierWindows = run(true)
        assertTrue(plain.isNotEmpty())
        assertEquals(plain.map { it.laeqDb }, withClassifierWindows.map { it.laeqDb })
        assertEquals(plain.map { it.l90Db }, withClassifierWindows.map { it.l90Db })
        assertEquals(plain.map { it.lafMaxDb }, withClassifierWindows.map { it.lafMaxDb })
    }
}
