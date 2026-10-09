package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.calibration.Recalibration
import ch.stadtlaerm.dsp.classify.LabelScore
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random
import java.util.Random as JRandom
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Detector v2 (engine 0.4.0): local floor, excess trigger, features, wind, shape, counts. */
class DetectorV2Test {
    private val zone = ZoneId.of("Europe/Zurich")
    private val fs = Acoustics.SAMPLE_RATE

    private fun ms(h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(2026, 10, 2, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    // ---- Local floor ----------------------------------------------------------------------------

    @Test
    fun localFloorIsTheL90OfTheTrailingWindow() {
        val r = Random(5)
        for (windowS in listOf(10.0, 30.0, 60.0)) {
            val est = LocalFloorEstimator(windowS, 5.0)
            val all = ArrayList<Double>()
            val n = (windowS * 8).toInt()
            repeat(3 * n) { i ->
                val v = 40 + 10 * r.nextDouble() + (if (i % 37 == 0) 30.0 else 0.0)
                all += v
                est.add(v)
                if (all.size < 40) {
                    assertTrue(est.value.isNaN(), "no floor before 5 s of history")
                } else {
                    val window = all.takeLast(n).toDoubleArray()
                    assertEquals(Percentiles.exceedanceLevel(window, 90.0), est.value, 1e-12)
                }
            }
        }
    }

    @Test
    fun quickSelectPercentileMatchesSortedPercentile() {
        val r = Random(9)
        for (size in 1..60) {
            val a = DoubleArray(size) { Math.round(r.nextDouble() * 20) / 2.0 } // with ties
            for (p in listOf(0.0, 10.0, 37.5, 50.0, 90.0, 100.0)) {
                val sorted = a.sortedArray()
                assertEquals(Percentiles.percentileSorted(sorted, p), QuickSelect.percentile(a.copyOf(), size, p), 1e-12, "n=$size p=$p")
            }
        }
    }

    @Test
    fun butterworthSectionsAreMinus3dBAtTheCorner() {
        fun db(b: Biquad, f: Double): Double { val (re, im) = b.response(2 * PI * f / fs); return 20 * log10(hypot(re, im)) }
        assertEquals(-3.01, db(Butterworth.lowpass(200.0, fs), 200.0), 0.01)
        assertEquals(-3.01, db(Butterworth.highpass(20.0, fs), 20.0), 0.01)
        assertEquals(-3.01, db(Butterworth.lowpass(8000.0, fs), 8000.0), 0.01)
        assertEquals(0.0, db(Butterworth.lowpass(200.0, fs), 20.0), 0.05)
        // 2nd order: |H|² = 1 / (1 + (f/fc)⁴) → −12.3 dB an octave above the corner.
        assertEquals(-12.3, db(Butterworth.lowpass(200.0, fs), 400.0), 0.1)
        assertEquals(0.0, db(Butterworth.highpass(250.0, fs), 2500.0), 0.05)
    }

    // ---- Trigger ---------------------------------------------------------------------------------

    private class Collector : EventDetector.Listener {
        val closed = ArrayList<DetectedEvent>()
        var candidates = 0
        override fun onCandidateStart(startSample: Long) { candidates++ }
        override fun onClosed(event: DetectedEvent) { closed += event }
    }

    @Test
    fun excessTriggerOverTheFrozenLocalFloor() {
        val c = Collector()
        val d = EventDetector(excessDb = 6.5, listener = c)
        var i = 0L
        fun feed(v: Double, n: Int) = repeat(n) { i++; d.onTick(i * 6000, v, v, v) }
        d.floorDb = 40.0
        feed(46.4, 8) // 6.4 dB over the floor: nothing
        assertEquals(0, c.candidates)
        feed(46.5, 4) // exactly floor + excess: starts
        assertEquals(1, c.candidates)
        d.floorDb = 60.0 // the floor moves during the event: the event keeps 40
        d.backgroundDb = 30.0
        feed(43.5, 4) // ≥ floor + excess − 3 (hysteresis): continues
        feed(43.4, 1) // ends
        assertEquals(1, c.closed.size)
        val e = c.closed[0]
        assertEquals(1.0, e.durationSeconds, 1e-9)
        assertEquals(40.0, e.localFloorDb)
        assertEquals(6.5, e.thresholdDb)
        // No 5-min background yet at the start: the local floor is recorded as background_db.
        assertEquals(40.0, e.backgroundDb)
        feed(70.0, 8); feed(50.0, 1) // next one: floor 60, background 30
        assertEquals(2, c.closed.size)
        assertEquals(60.0, c.closed[1].localFloorDb)
        assertEquals(30.0, c.closed[1].backgroundDb)
    }

    @Test
    fun noLocalFloorNoDetection() {
        val c = Collector()
        val d = EventDetector(listener = c)
        repeat(40) { d.onTick((it + 1L) * 6000, 90.0, 90.0, 90.0) }
        assertEquals(0, c.candidates)
    }

    @Test
    fun legacyThresholdMigration() {
        assertEquals(6.5, ThresholdMigration.excessFromLegacyThreshold(10.0)) // old default → new default
        assertEquals(3.5, ThresholdMigration.excessFromLegacyThreshold(7.0))
        assertEquals(3.0, ThresholdMigration.excessFromLegacyThreshold(5.0)) // at least 3
        assertEquals(16.5, ThresholdMigration.excessFromLegacyThreshold(20.0))
        assertEquals(EngineConfig.DEFAULT_EVENT_EXCESS_DB, ThresholdMigration.excessFromLegacyThreshold(ThresholdMigration.LEGACY_DEFAULT_THRESHOLD_DB))
    }

    // ---- Features on synthetic tick curves ------------------------------------------------------

    /** Pushes [levels] (LAF per tick) with band energies; returns the tracker. */
    private fun tracker(
        levels: List<Double>,
        band: (Int) -> Double = { 1.0 }, low: (Int) -> Double = { 1.0 }, total: (Int) -> Double = { 10.0 },
    ): EventFeatureTracker {
        val t = EventFeatureTracker(2600)
        levels.forEachIndexed { i, l -> t.push(l, 10.0.pow(l / 10), band(i), low(i), total(i)) }
        return t
    }

    @Test
    fun riseDecayAndJaggednessOfATriangle() {
        // 20 ticks at the floor (40), a 1 dB/tick ramp up to 50 and back down, 20 ticks at 40.
        val up = (1..10).map { 40.0 + it }
        val down = (9 downTo 0).map { 40.0 + it }
        val levels = List(20) { 40.0 } + up + down + List(20) { 40.0 }
        val t = tracker(levels)
        // Event: the ticks at ≥ 46.5 (start) … ≥ 43.5 (end).
        val start = 20L + 6 // 47 dB
        val end = 20L + 10 + 6 // last tick ≥ 43.5 is 44 at index 35
        val f = t.compute(start, end, end + 20, 40.0, 50.0, (end - start) * 0.125, WindRule())
        assertEquals(10.0, f.excessDb)
        // 41 → 49 dB on the way up: 8 ticks; 49 → 41 on the way down: 8 ticks.
        assertEquals(1.0, f.riseS, 1e-9)
        assertEquals(1.0, f.decayS, 1e-9)
        // Second differences are 0 except −2 dB at the peak: sqrt(4 / (n − 2)) / excess.
        val n = (end - start).toInt()
        assertEquals(sqrt(4.0 / (n - 2)) / 10.0, f.jaggedness, 1e-9)
        assertEquals(EventShape.HUMP, f.shape)
    }

    @Test
    fun decayIsUnknownIfTheTailEndsTooEarly() {
        val levels = List(20) { 40.0 } + (1..10).map { 40.0 + it } + (9 downTo 0).map { 40.0 + it }
        val t = tracker(levels)
        val f = t.compute(26, 36, 37, 40.0, 50.0, 1.25, WindRule())
        assertTrue(f.decayS.isNaN())
        assertEquals(1.0, f.riseS, 1e-9)
    }

    @Test
    fun midBandRiseLfShareAndFlutter() {
        val levels = List(40) { 40.0 } + List(16) { 55.0 } + List(8) { 40.0 }
        // Band energy 1 before, 10 during: +10 dB. LF share 0.25.
        val t = tracker(levels, band = { if (it >= 40) 10.0 else 1.0 }, low = { 2.5 }, total = { 10.0 })
        val f = t.compute(40, 56, 60, 40.0, 55.0, 2.0, WindRule())
        assertEquals(10.0, f.midBandRiseDb, 1e-9)
        assertEquals(0.25, f.lfShare, 1e-12)
        assertEquals(0.0, f.lfFlutterDb, 1e-12) // constant LF level
        assertFalse(f.wind)

        // A step in the LF level (on/offset of a sound) is not flutter …
        val step = tracker(levels, low = { i -> if (i in 44..51) 100.0 else 1.0 })
        assertEquals(0.0, step.compute(40, 56, 60, 40.0, 55.0, 2.0, WindRule()).lfFlutterDb, 1e-9)
        // … a 2 Hz back-and-forth of ±7 dB is (RMS 7/√2 ≈ 4.9 dB) …
        val flutter = tracker(levels, low = { i -> 10.0.pow(0.7 * kotlin.math.sin(PI * i / 2)) })
        val ff = flutter.compute(40, 56, 60, 40.0, 55.0, 2.0, WindRule())
        // (A little less: the median window shrinks to the value itself at the event's edges.)
        assertTrue(ff.lfFlutterDb in 4.3..7 / sqrt(2.0), "flutter ${ff.lfFlutterDb}")
        assertTrue(ff.wind)
        // … and so is random turbulence (σ = 5 dB).
        val rnd = JRandom(3)
        val g = DoubleArray(64) { rnd.nextGaussian() * 5 }
        val turb = tracker(levels, low = { i -> 10.0.pow(g[i] / 10) }).compute(40, 56, 60, 40.0, 55.0, 2.0, WindRule())
        assertTrue(turb.lfFlutterDb in 3.8..8.0, "turbulence ${turb.lfFlutterDb}")
        // LF-dominated energy is wind too.
        assertTrue(tracker(levels, low = { 9.6 }, total = { 10.0 }).compute(40, 56, 60, 40.0, 55.0, 2.0, WindRule()).wind)
        assertFalse(tracker(levels, low = { 9.4 }, total = { 10.0 }).compute(40, 56, 60, 40.0, 55.0, 2.0, WindRule()).wind)
        // Thresholds are settings.
        assertTrue(tracker(levels, low = { 9.4 }, total = { 10.0 }).compute(40, 56, 60, 40.0, 55.0, 2.0, WindRule(0.9, 3.8)).wind)
    }

    @Test
    fun windRuleAndShapeThresholds() {
        val w = WindRule()
        assertTrue(w.isWind(0.95, 0.0))
        assertTrue(w.isWind(0.5, 3.8))
        assertFalse(w.isWind(0.949, 3.79))
        assertFalse(w.isWind(Double.NaN, Double.NaN))
        assertEquals(EventShape.LONG, EventShape.classify(30.0, 0.1, 1.0))
        assertEquals(EventShape.IMPULSE, EventShape.classify(1.0, 0.2, 1.0))
        assertEquals(EventShape.JAGGED, EventShape.classify(3.0, 0.2, 0.5)) // abrupt but too long for an impulse
        assertEquals(EventShape.JAGGED, EventShape.classify(5.0, 2.0, 0.3))
        assertEquals(EventShape.HUMP, EventShape.classify(5.0, 2.0, 0.29))
        assertEquals(EventShape.HUMP, EventShape.classify(5.0, Double.NaN, Double.NaN))
        assertEquals("Buckel", EventShape.nameDe(EventShape.HUMP))
    }

    // ---- Engine: counts per minute, held minutes, tail -------------------------------------------

    private class Rec : MeasurementEngine.Listener {
        val minutes = ArrayList<MinuteRecord>()
        val events = ArrayList<NoiseEvent>()
        val seconds = ArrayList<SecondResult>()
        val log = ArrayList<String>()
        var engine: MeasurementEngine? = null
        override fun onMinute(minute: MinuteRecord) { minutes += minute; log += "minute ${minute.startIso}" }
        override fun onEvent(event: NoiseEvent) { events += event; log += "event ${event.startIso} at ${engine!!.totalSamples}" }
        override fun onSecond(second: SecondResult) { seconds += second }
        override fun onEventClosed(startSample: Long, endSample: Long, features: EventFeatures) { log += "closed $endSample at ${engine!!.totalSamples}" }
    }

    private fun lowThump(seconds: Double, rms: Double, seed: Int): FloatArray {
        val x = TestSignals.whiteNoise(1.0, seconds, seed = seed)
        val lp1 = Butterworth.lowpass(80.0, fs); val lp2 = Butterworth.lowpass(80.0, fs); val hp = Butterworth.highpass(25.0, fs)
        val y = FloatArray(x.size) { hp.process(lp2.process(lp1.process(x[it].toDouble()))).toFloat() }
        val r = sqrt(y.sumOf { it.toDouble() * it } / y.size)
        for (i in y.indices) y[i] = (y[i] / r * rms * (0.5 - 0.5 * kotlin.math.cos(2 * PI * i / y.size))).toFloat()
        return y
    }

    @Test
    fun windAndBurstsAreCountedSeparatelyInTheMinuteTheyStart() {
        val clock = SimClock(ms(23, 0))
        val rec = Rec()
        val e = MeasurementEngine(EngineConfig(zone = zone, classifierEnabled = false), null, rec, clock::now).also { rec.engine = it }
        val bg = 1e-4
        // 30 s noise; a 1 kHz burst (2 s); 10 s; an LF thump starting at 59.8 s (0.4 s); 30 s noise.
        val signal = TestSignals.concat(
            TestSignals.whiteNoise(bg, 30.0, seed = 1),
            TestSignals.add(TestSignals.sine(1000.0, 0.01, 2.0), TestSignals.whiteNoise(bg, 2.0, seed = 2)),
            TestSignals.whiteNoise(bg, 27.8, seed = 3),
            TestSignals.add(lowThump(0.4, 0.05, 4), TestSignals.whiteNoise(bg, 0.4, seed = 5)),
            TestSignals.whiteNoise(bg, 30.0, seed = 6),
        )
        TestSignals.feed(e, signal, clock = clock)
        e.stop()
        assertEquals(2, rec.events.size, rec.events.toString())
        val (burst, wind) = rec.events
        assertFalse(burst.wind); assertEquals(null, burst.dominantCategory)
        assertTrue(wind.wind, "${wind.features}"); assertEquals(WindRule.CATEGORY, wind.dominantCategory)
        assertTrue(wind.startIso.startsWith("2026-10-02T23:00:59"), wind.startIso)
        // Both counted in minute 23:00 (the thump starts there and is only decided after 23:01:00).
        assertEquals(listOf(1, 0), rec.minutes.map { it.eventCount })
        assertEquals(listOf(1, 0), rec.minutes.map { it.windEventCount })
        // The held minute was emitted after the thump's decision, still before minute 23:01.
        val iMinute = rec.log.indexOfFirst { it.startsWith("minute 2026-10-02T23:00") }
        val iClosed = rec.log.indexOfLast { it.startsWith("closed") }
        assertTrue(iClosed < iMinute, rec.log.toString())
        // The minute carries the median local floor: the background (white noise at 32.4 dB
        // unweighted; A-weighted ≈ 3 dB less at 48 kHz, and L90 is below the mean).
        val bgDb = 20 * log10(bg) + Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
        for (m in rec.minutes) assertTrue(m.localFloorDb in bgDb - 4.5..bgDb - 2.0, "${m.localFloorDb} vs $bgDb")
        // The local floor is reported every second once 5 s of history exist.
        assertTrue(rec.seconds.take(4).all { it.localFloorDb.isNaN() }) // seconds end at 1.5, 2.5, … s
        assertFalse(rec.seconds[4].localFloorDb.isNaN())
    }

    @Test
    fun eventIsReportedAfterItsTailWithFeatures() {
        val clock = SimClock(ms(23, 0))
        val rec = Rec()
        val e = MeasurementEngine(EngineConfig(zone = zone, classifierEnabled = false), null, rec, clock::now).also { rec.engine = it }
        val bg = 1e-4
        // A slow 4 s hump (power sin²) of 1 kHz, 20 dB over the noise at the top.
        val hump = TestSignals.sine(1000.0, bg * 10 * sqrt(2.0), 4.0)
        for (i in hump.indices) hump[i] = (hump[i] * kotlin.math.sin(PI * i / hump.size)).toFloat()
        val signal = TestSignals.concat(
            TestSignals.whiteNoise(bg, 10.0, seed = 1),
            TestSignals.add(hump, TestSignals.whiteNoise(bg, 4.0, seed = 2)),
            TestSignals.whiteNoise(bg, 10.0, seed = 3),
        )
        TestSignals.feed(e, signal, clock = clock)
        e.stop()
        val ev = rec.events.single()
        val f = ev.features!!
        val bgDb = 20 * log10(bg) + Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
        assertTrue(f.localFloorDb in bgDb - 4.5..bgDb - 2.0, "${f.localFloorDb} vs $bgDb")
        assertTrue(f.riseS > 0.7 && !f.decayS.isNaN(), "$f")
        assertEquals(EventShape.HUMP, f.shape)
        // closed (with features) comes before the event, both within the tail limit after the end.
        val closed = rec.log.first { it.startsWith("closed") }.split(" ")
        val endSample = closed[1].toLong()
        val at = closed[3].toLong()
        assertTrue(at - endSample in 0..(5 * fs + fs / 8), "closed ${at - endSample} samples after the end")
        assertTrue(rec.log.indexOf(rec.log.first { it.startsWith("closed") }) < rec.log.indexOf(rec.log.first { it.startsWith("event") }))
    }

    // ---- Re-evaluation with a later calibration -------------------------------------------------

    private fun features(floor: Double, excess: Double, wind: Boolean = false) =
        EventFeatures(floor, excess, 2.0, 2.5, 0.05, 8.0, 0.3, 1.0, wind, EventShape.HUMP)

    @Test
    fun localFloorFollowsReEvaluationWithoutCompounding() {
        val t = ms(23, 10)
        val m0 = MinuteRecord(
            t, Iso.format(t, zone), 60.0, 50.0, 60.0, 40.0, 58.0, 55.0, 48.0, 44.0, 2, null, emptyMap(), 0, null,
            Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, "UNPROCESSED", false, localFloorDb = 45.0, windEventCount = 1,
        )
        val b = Recalibration.Target(3L, 118.0, "UNPROCESSED")
        val c = Recalibration.Target(4L, 121.0, "UNPROCESSED")
        val ab = Recalibration.recalibrate(m0, b)
        assertEquals(45.0 + 118.0 - Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, ab.localFloorDb, 1e-9)
        val abc = Recalibration.recalibrate(ab, c)
        val ac = Recalibration.recalibrate(m0, c)
        assertEquals(ac.localFloorDb, abc.localFloorDb, 1e-9)
        assertEquals(45.0 + 121.0 - Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, ac.localFloorDb, 1e-9)
        assertEquals(1, abc.windEventCount)
        assertTrue(Recalibration.recalibrate(m0.copy(localFloorDb = Double.NaN), b).localFloorDb.isNaN())

        val e0 = NoiseEvent(
            t, Iso.format(t, zone, true), 3.0, 60.0, 62.0, 44.0, 6.5, "road_traffic", 0.5f, listOf(LabelScore("Car", 0.5f)), 2,
            null, "UNPROCESSED", false, 30.0, features = features(48.0, 12.0),
        )
        val eb = Recalibration.recalibrate(e0, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, b)
        assertEquals(eb.lafMaxDb - 12.0, eb.features!!.localFloorDb, 1e-9)
        assertEquals(12.0, eb.features!!.excessDb)
        val ebc = Recalibration.recalibrate(eb, 118.0, c)
        assertEquals(Recalibration.recalibrate(e0, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, c), ebc)
        assertNull(Recalibration.recalibrate(e0.copy(features = null), Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, b).features)
    }

    // ---- Night summary ----------------------------------------------------------------------------

    @Test
    fun nightSummaryReportsHumAndBurstsWithoutWind() {
        fun minute(t: Long, l90: Double) = MinuteRecord(
            t, Iso.format(t, zone), 60.0, l90 + 6, l90 + 20, l90 - 2, l90 + 15, l90 + 8, l90 + 3, l90, 1, null, emptyMap(), 0, null,
            112.35, "UNPROCESSED", false, localFloorDb = l90 + 1,
        )
        fun event(t: Long, max: Double, cat: String, wind: Boolean) = NoiseEvent(
            t, Iso.format(t, zone, true), 2.0, max, max + 1, 30.0, 6.5, if (wind) WindRule.CATEGORY else cat, 0.5f, emptyList(), 1,
            null, "UNPROCESSED", false, 30.0, features = features(32.0, max - 32.0, wind),
        )
        val minutes = listOf(minute(ms(23, 0), 30.0), minute(ms(23, 1), 34.0), minute(ms(23, 2), 31.0))
        val events = listOf(
            event(ms(23, 0, 10), 45.0, "road_traffic", false),
            event(ms(23, 0, 30), 70.0, "unclassified", true), // loudest, but wind
            event(ms(23, 1, 10), 50.0, "loud_vehicle", false),
            event(ms(23, 2, 10), 40.0, "road_traffic", true),
            NoiseEvent( // stored before v0.4.0: no features, never wind
                ms(23, 2, 30), Iso.format(ms(23, 2, 30), zone, true), 2.0, 48.0, 49.0, 30.0, 10.0, "road_traffic", 0.5f,
                emptyList(), 1, null, "UNPROCESSED", false,
            ),
        )
        val n = NightSummarizer.summarize(minutes, events, zone, eventMinLevelDb = 30.0).single()
        assertEquals(3, n.eventCount)
        assertEquals(2, n.windEventCount)
        assertEquals(31.0, n.humDb) // median of the minute L90s
        assertEquals(50.0, n.loudestEvent!!.lafMaxDb)
        assertEquals(mapOf("road_traffic" to 2, "loud_vehicle" to 1), n.eventsByCategory)
        assertEquals(3 / (180.0 / 3600), n.eventsPerHour, 1e-9)
        // Wind below the floor is not counted anywhere.
        assertEquals(1, NightSummarizer.summarize(minutes, events, zone, eventMinLevelDb = 45.0).single().windEventCount)
    }
}
