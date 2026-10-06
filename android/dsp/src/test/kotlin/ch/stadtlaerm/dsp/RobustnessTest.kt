package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.calibration.CalibrationMeasurement
import ch.stadtlaerm.dsp.classify.ClassifierFrame
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Review fixes v0.1.1: silenced microphone, clock anchoring, event classification, nights. */
class RobustnessTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val fs = Acoustics.SAMPLE_RATE

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi, s).atZone(zone).toInstant().toEpochMilli()

    private class Rec : MeasurementEngine.Listener {
        val minutes = ArrayList<MinuteRecord>()
        val events = ArrayList<NoiseEvent>()
        val ticks = ArrayList<LafTick>()
        val corrections = ArrayList<Long>()
        var engine: MeasurementEngine? = null
        val eventEmitSamples = ArrayList<Long>()
        override fun onMinute(minute: MinuteRecord) { minutes += minute }
        override fun onEvent(event: NoiseEvent) { events += event; eventEmitSamples += engine!!.totalSamples }
        override fun onTick(tick: LafTick) { ticks += tick }
        override fun onClockCorrection(correctionMs: Long) { corrections += correctionMs }
    }

    private fun engine(config: EngineConfig, clock: SimClock, rec: Rec, withMapper: Boolean = false) =
        MeasurementEngine(config, if (withMapper) TestSignals.mapper() else null, rec, clock::now).also { rec.engine = it }

    // ---- 1. Silenced microphone ---------------------------------------------------------------

    @Test
    fun digitalSilenceIsExcludedFromLevelsEventsAndBackground() {
        val start = ms(2026, 10, 2, 23, 0, 0)
        val clock = SimClock(start)
        val rec = Rec()
        val e = engine(EngineConfig(zone = zone, classifierEnabled = false), clock, rec)
        val noiseRms = 1e-3 // ≈ −60 dBFS → ≈ 52 dB(A) with the default offset
        // 60 s noise, 20 s of exact zeros (a phone call took the mic), 40 s noise.
        val signal = TestSignals.concat(
            TestSignals.whiteNoise(noiseRms, 60.0, seed = 1),
            FloatArray(20 * fs),
            TestSignals.whiteNoise(noiseRms, 40.0, seed = 2),
        )
        TestSignals.feed(e, signal, clock = clock)
        e.stop()

        assertEquals(0, rec.events.size, "silence and recovery must not create events: ${rec.events}")
        assertEquals(2, rec.minutes.size)
        val (m1, m2) = rec.minutes
        assertEquals(1.0, m1.coverage, 1e-9)
        // Minute 2: 60 s total, 20 s silent + 0.5 s guard (+ ≤ 1 tick) invalid.
        assertEquals(60.0 - 20.5, m2.validSeconds, 0.13)
        assertTrue(m2.coverage in 0.6..0.7, "coverage ${m2.coverage}")
        // Levels are those of the noise, not of the zeros (≈ −88 dB(A) or lower).
        assertEquals(m1.laeqDb, m2.laeqDb, 0.3)
        assertEquals(m1.l90Db, m2.l90Db, 0.5)
        assertEquals(m1.lafMinDb, m2.lafMinDb, 1.5)
        assertTrue(m2.lafMinDb > 40.0, "LAFmin ${m2.lafMinDb}")
        // Invalid ticks are reported as such.
        val invalid = rec.ticks.count { !it.valid }
        assertTrue(invalid in 164..165, "invalid ticks $invalid") // 160 silent ticks + 4 guard ticks (0.5 s)
    }

    @Test
    fun platformSilencingFlagInvalidatesEvenNonZeroAudio() {
        val clock = SimClock(ms(2026, 10, 2, 23, 0, 0))
        val rec = Rec()
        val e = engine(EngineConfig(zone = zone, classifierEnabled = false), clock, rec)
        val noise = TestSignals.whiteNoise(1e-3, 40.0)
        val loud = TestSignals.whiteNoise(0.05, 10.0, seed = 9) // would be an event if valid
        TestSignals.feed(e, noise, clock = clock)
        e.setMicSilenced(true)
        TestSignals.feed(e, loud, clock = clock)
        e.setMicSilenced(false)
        TestSignals.feed(e, TestSignals.whiteNoise(1e-3, 10.0, seed = 3), clock = clock)
        e.stop()
        assertEquals(0, rec.events.size)
        val total = rec.minutes.sumOf { it.validSeconds }
        assertEquals(60.0 - 0.5 - 10.0 - 0.5, total, 0.26) // minus warm-up, silenced 10 s, guard
    }

    @Test
    fun quietButRealNoiseIsValid() {
        // −100 dBFS noise (16-bit-quantisation-like) is quiet but plausible: must stay valid.
        val block = TestSignals.whiteNoise(1e-5, 0.125)
        assertFalse(SilenceDetector.isDigitalSilence(block))
        assertTrue(SilenceDetector.isDigitalSilence(FloatArray(6000)))
        val partly = block.copyOf().also { java.util.Arrays.fill(it, 1000, 1480, 0f) } // 10 ms of zeros
        assertTrue(SilenceDetector.isDigitalSilence(partly))
    }

    @Test
    fun calibrationIsInvalidatedBySilence() {
        val m = CalibrationMeasurement(10)
        m.process(FloatArray(fs / 2)) // start-up zeros during settle time are tolerated
        m.process(TestSignals.whiteNoise(0.01, 3.0))
        assertFalse(m.invalidated)
        m.process(FloatArray(6000))
        assertTrue(m.invalidated)
        val m2 = CalibrationMeasurement(10)
        m2.process(TestSignals.whiteNoise(0.01, 2.0))
        m2.invalidate() // e.g. app went to the background
        assertTrue(m2.invalidated)
    }

    @Test
    fun nightsDropLowCoverageMinutes() {
        fun minute(t: Long, laeq: Double, valid: Double) = MinuteRecord(
            t, Iso.format(t, zone), 60.0, laeq, laeq, laeq, laeq, laeq, laeq, laeq, 0, null, emptyMap(), 0, null,
            112.35, "UNPROCESSED", false, validSeconds = valid,
        )
        val minutes = listOf(
            minute(ms(2026, 10, 2, 23, 0), 50.0, 60.0),
            minute(ms(2026, 10, 2, 23, 1), 50.0, 40.0), // 67 % → kept, weighted by 40 s
            minute(ms(2026, 10, 2, 23, 2), -88.0, 10.0), // 17 % → dropped
            minute(ms(2026, 10, 2, 23, 3), Double.NaN, 0.0), // no valid audio
        )
        val n = NightSummarizer.summarize(minutes, emptyList(), zone).single()
        assertEquals(100.0, n.measuredSeconds, 1e-9)
        assertEquals(50.0, n.laeqDb, 1e-9)
        assertEquals(2, n.excludedMinutes)
        assertEquals(100.0 / (8 * 3600), n.coverage, 1e-12)
    }

    // ---- 2. Timestamps ---------------------------------------------------------------------------

    @Test
    fun timeIsAnchoredAtFirstDeliveredBlock() {
        val created = ms(2026, 10, 2, 23, 0, 0)
        val clock = SimClock(created)
        val rec = Rec()
        val e = engine(EngineConfig(zone = zone, classifierEnabled = false), clock, rec)
        clock.extraMs = 5_000 // e.g. the model took 5 s to load before the first block arrived
        TestSignals.feed(e, TestSignals.whiteNoise(1e-3, 70.0), clock = clock)
        e.stop()
        // First block (6000 samples) delivered at created + 125 ms + 5 s; recording starts at sample 24000.
        assertEquals(created + 5_000 + 125 + 375, rec.minutes[0].startEpochMs)
        assertEquals(ms(2026, 10, 2, 23, 1), rec.minutes[1].startEpochMs)
        assertTrue(rec.corrections.isEmpty())
    }

    @Test
    fun driftIsCorrectedOncePerMinuteAndMinutesStayAligned() {
        val start = ms(2026, 10, 2, 23, 0, 0)
        val clock = SimClock(start, rate = 1.012) // wall clock runs 1.2 % faster: 720 ms per minute
        val rec = Rec()
        val e = engine(EngineConfig(zone = zone, classifierEnabled = false), clock, rec)
        TestSignals.feed(e, TestSignals.whiteNoise(1e-3, 240.0), clock = clock)
        e.stop()
        assertTrue(rec.corrections.size >= 3, "corrections ${rec.corrections}")
        assertTrue(rec.corrections.all { it in 500..1500 }, "${rec.corrections}")
        assertTrue(rec.minutes.drop(1).sumOf { it.clockCorrections } >= 3)
        for (m in rec.minutes.drop(1)) assertEquals(0L, m.startEpochMs % 60_000L, m.startIso)
        // Sample time never ends up more than ~0.75 s away from the wall clock.
        assertTrue(kotlin.math.abs(clock.now() - e.epochMsAt(e.totalSamples)) < 750)

        // Small drift (0.1 % = 60 ms/min) stays below 500 ms over 4 minutes: no correction.
        val clock2 = SimClock(start, rate = 1.001)
        val rec2 = Rec()
        val e2 = engine(EngineConfig(zone = zone, classifierEnabled = false), clock2, rec2)
        TestSignals.feed(e2, TestSignals.whiteNoise(1e-3, 240.0), clock = clock2)
        e2.stop()
        assertTrue(rec2.corrections.isEmpty())
        assertTrue(rec2.minutes.all { it.clockCorrections == 0 })
    }

    // ---- 4. Classification of short events -----------------------------------------------------

    @Test
    fun defaultClassifierIntervalIsOneSecond() {
        assertEquals(1.0, EngineConfig().classifierIntervalSeconds)
    }

    /**
     * With a long interval (5 s), a short pass-by falls between two classifications. The engine
     * must classify immediately at event end instead of storing it unclassified or waiting.
     */
    @Test
    fun shortEventBetweenClassificationsIsClassifiedAtEventEnd() {
        val mapper = TestSignals.mapper()
        val motorcycle = mapper.labels.indexOf("Motorcycle")
        val clock = SimClock(ms(2026, 10, 2, 23, 0, 0))
        val rec = Rec()
        val e = MeasurementEngine(
            EngineConfig(zone = zone, classifierIntervalSeconds = 5.0), mapper, rec, clock::now,
        ).also { rec.engine = it }
        val bg = 1e-3
        // Periodic windows end at ≈ 1.5 s + k·5 s. Burst 0.8 s at 42.0…42.8 s lies between 41.5 and 46.5.
        val signal = TestSignals.concat(
            TestSignals.whiteNoise(bg, 42.0, seed = 1),
            TestSignals.add(TestSignals.sine(800.0, 0.05, 0.8), TestSignals.whiteNoise(bg, 0.8, seed = 2)),
            TestSignals.whiteNoise(bg, 10.0, seed = 3),
        )
        val window = FloatArray(15_600)
        val frameEnds = ArrayList<Long>()
        var p = 0
        val buf = FloatArray(6000)
        while (p < signal.size) {
            val n = minOf(6000, signal.size - p)
            signal.copyInto(buf, 0, p, p + n)
            clock.advance(n)
            e.process(buf, n)
            p += n
            if (e.classifierDue()) {
                val end = e.copyClassifierWindow(window)
                frameEnds += end
                val loud = sqrt(window.map { it * it }.average()) > 0.005
                val scores = FloatArray(mapper.labels.size)
                scores[if (loud) motorcycle else 500] = 0.9f
                e.onClassifierFrame(ClassifierFrame(end, e.classifierWindowLength48k, scores))
            }
        }
        e.stop()
        assertEquals(1, rec.events.size)
        val ev = rec.events[0]
        assertEquals("loud_vehicle", ev.dominantCategory)
        assertEquals(1, ev.classifierFrames)
        // The extra classification happened right at event end, not at the next 5 s slot.
        val eventEndSample = ((42.0 + ev.durationSeconds) * fs).toLong()
        val extra = frameEnds.first { it > 42 * fs }
        assertTrue(extra - eventEndSample <= fs / 4, "extra frame ${extra - eventEndSample} samples after end")
        assertTrue(rec.eventEmitSamples[0] - eventEndSample <= fs / 4)
    }

    @Test
    fun overlappingClassificationIsUsedImmediately() {
        val mapper = TestSignals.mapper()
        val motorcycle = mapper.labels.indexOf("Motorcycle")
        val clock = SimClock(ms(2026, 10, 2, 23, 0, 0))
        val rec = Rec()
        val e = MeasurementEngine(EngineConfig(zone = zone), mapper, rec, clock::now).also { rec.engine = it }
        val signal = TestSignals.concat(
            TestSignals.whiteNoise(1e-3, 40.0, seed = 1),
            TestSignals.add(TestSignals.sine(800.0, 0.05, 2.0), TestSignals.whiteNoise(1e-3, 2.0, seed = 2)),
            TestSignals.whiteNoise(1e-3, 5.0, seed = 3),
        )
        val window = FloatArray(15_600)
        var p = 0
        val buf = FloatArray(6000)
        while (p < signal.size) {
            val n = minOf(6000, signal.size - p)
            signal.copyInto(buf, 0, p, p + n)
            clock.advance(n)
            e.process(buf, n)
            p += n
            if (e.classifierDue()) {
                val end = e.copyClassifierWindow(window)
                val scores = FloatArray(mapper.labels.size)
                scores[if (sqrt(window.map { it * it }.average()) > 0.005) motorcycle else 500] = 0.9f
                e.onClassifierFrame(ClassifierFrame(end, e.classifierWindowLength48k, scores))
            }
        }
        e.stop()
        val ev = rec.events.single()
        assertTrue(ev.classifierFrames >= 2)
        assertEquals("loud_vehicle", ev.dominantCategory)
        val endSample = ((40.0 + ev.durationSeconds) * fs).toLong()
        assertTrue(rec.eventEmitSamples[0] - endSample <= fs / 4, "emitted ${rec.eventEmitSamples[0] - endSample} samples late")
    }

    // ---- 6. Small fixes -------------------------------------------------------------------------

    @Test
    fun eventIsCountedInTheMinuteItStarts() {
        val clock = SimClock(ms(2026, 10, 2, 23, 0, 0))
        val rec = Rec()
        val e = engine(EngineConfig(zone = zone, classifierEnabled = false), clock, rec)
        // Wall clock starts at 23:00:00 + 125 ms for the first block → sample 0 is 23:00:00.000.
        // Burst from 59.8 s to 61.8 s: starts in minute 23:00, confirmed only after 23:01:00.
        val signal = TestSignals.concat(
            TestSignals.whiteNoise(1e-3, 59.8, seed = 1),
            TestSignals.add(TestSignals.sine(1000.0, 0.05, 2.0), TestSignals.whiteNoise(1e-3, 2.0, seed = 2)),
            TestSignals.whiteNoise(1e-3, 30.0, seed = 3),
        )
        TestSignals.feed(e, signal, clock = clock)
        e.stop()
        assertEquals(1, rec.events.size)
        assertTrue(rec.events[0].startIso.startsWith("2026-10-02T23:00:59"), rec.events[0].startIso)
        assertEquals(listOf(1, 0), rec.minutes.map { it.eventCount })
        assertEquals(listOf(ms(2026, 10, 2, 23, 0) + 500, ms(2026, 10, 2, 23, 1)), rec.minutes.map { it.startEpochMs })
    }

    @Test
    fun nightLengthFollowsDst() {
        assertEquals(8 * 3600.0, NightSummarizer.nightLengthSeconds(LocalDate.of(2026, 10, 2), zone))
        assertEquals(7 * 3600.0, NightSummarizer.nightLengthSeconds(LocalDate.of(2026, 3, 28), zone)) // spring forward
        assertEquals(9 * 3600.0, NightSummarizer.nightLengthSeconds(LocalDate.of(2026, 10, 24), zone)) // fall back
    }

    @Suppress("unused")
    private fun db(x: Double) = 20 * log10(x)
}
