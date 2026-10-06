package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.classify.ClassifierFrame
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EventDetectorTest {
    private val tick = 6000

    private class Collector : EventDetector.Listener {
        val closed = ArrayList<DetectedEvent>()
        var discarded = 0
        var confirmed = 0
        override fun onClosed(event: DetectedEvent) { closed += event }
        override fun onDiscarded(startSample: Long) { discarded++ }
        override fun onConfirmed(startSample: Long) { confirmed++ }
    }

    private fun run(levels: List<Double>, bg: Double = 50.0): Collector {
        val c = Collector()
        val d = EventDetector(thresholdDb = 10.0, listener = c)
        d.backgroundDb = bg
        levels.forEachIndexed { i, l -> d.onTick((i + 1L) * tick, l, l, l) }
        d.flush(levels.size.toLong() * tick)
        return c
    }

    private fun rep(v: Double, n: Int) = List(n) { v }

    @Test
    fun burstOnBackgroundIsOneEvent() {
        val c = run(rep(50.0, 40) + rep(70.0, 16) + rep(50.0, 40))
        assertEquals(1, c.closed.size)
        val e = c.closed[0]
        assertEquals(2.0, e.durationSeconds, 1e-9)
        assertEquals(70.0, e.lafMaxDb, 1e-9)
        assertEquals(70.0 + 10 * log10(2.0), e.selDb, 1e-6) // LAE = LAeq,T + 10·log10(T / 1 s)
        assertEquals(50.0, e.backgroundDb)
        assertEquals(40L * tick, e.startSample)
    }

    @Test
    fun shortBurstIsDiscarded() {
        val c = run(rep(50.0, 40) + rep(70.0, 3) + rep(50.0, 40)) // 0.375 s
        assertEquals(0, c.closed.size)
        assertEquals(1, c.discarded)
        val c2 = run(rep(50.0, 40) + rep(70.0, 4) + rep(50.0, 40)) // exactly 0.5 s
        assertEquals(1, c2.closed.size)
    }

    @Test
    fun hysteresisKeepsOneEvent() {
        // 70 dB, then 58 dB (below start 60 but above end 57), then background.
        val c = run(rep(50.0, 40) + rep(70.0, 8) + rep(58.0, 8) + rep(70.0, 4) + rep(50.0, 20))
        assertEquals(1, c.closed.size)
        assertEquals(20 * 0.125, c.closed[0].durationSeconds, 1e-9)
        // Without the hysteresis band (drop to 56) it would be two events.
        val c2 = run(rep(50.0, 40) + rep(70.0, 8) + rep(56.0, 8) + rep(70.0, 4) + rep(50.0, 20))
        assertEquals(2, c2.closed.size)
    }

    @Test
    fun noBackgroundNoDetection() {
        val c = run(rep(90.0, 40), bg = Double.NaN)
        assertEquals(0, c.closed.size)
    }

    /** End to end: 48 kHz synthetic background + 1 kHz burst through the full engine. */
    @Test
    fun engineDetectsSyntheticBurstAndAttachesClassification() {
        val zone = ZoneId.of("Europe/Zurich")
        val start = LocalDateTime.of(2026, 10, 2, 23, 59, 20).atZone(zone).toInstant().toEpochMilli()
        val bgRms = 1e-3
        val toneAmp = 0.05
        val signal = TestSignals.concat(
            TestSignals.whiteNoise(bgRms, 60.0, seed = 1),
            TestSignals.add(TestSignals.sine(1000.0, toneAmp, 2.0), TestSignals.whiteNoise(bgRms, 2.0, seed = 2)),
            TestSignals.whiteNoise(bgRms, 60.0, seed = 3),
        )
        val mapper = TestSignals.mapper()
        val motorcycle = mapper.labels.indexOf("Motorcycle")
        val events = ArrayList<NoiseEvent>()
        val minutes = ArrayList<MinuteRecord>()
        val clock = SimClock(start)
        val engine = MeasurementEngine(
            EngineConfig(zone = zone, classifierEnabled = true, calibrationId = 7, audioSource = "UNPROCESSED"),
            wallClock = clock::now, mapper = mapper,
            listener = object : MeasurementEngine.Listener {
                override fun onEvent(event: NoiseEvent) { events += event }
                override fun onMinute(minute: MinuteRecord) { minutes += minute }
            },
        )
        val window = FloatArray(15_600)
        var p = 0
        val buf = FloatArray(6000)
        while (p < signal.size) {
            val n = minOf(6000, signal.size - p)
            signal.copyInto(buf, 0, p, p + n)
            clock.advance(n)
            engine.process(buf, n)
            p += n
            if (engine.classifierDue()) {
                val end = engine.copyClassifierWindow(window)
                // Fake classifier: "Motorcycle" whenever the window is loud.
                val scores = FloatArray(mapper.labels.size)
                val loud = sqrt(window.map { it * it }.average()) > 0.01
                scores[if (loud) motorcycle else 500] = 0.9f
                engine.onClassifierFrame(ClassifierFrame(end, engine.classifierWindowLength48k, scores))
            }
        }
        engine.stop()

        assertEquals(1, events.size, "events: $events")
        val e = events[0]
        val toneLevel = 20 * log10(toneAmp / sqrt(2.0)) + Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
        assertEquals(toneLevel, e.lafMaxDb, 0.5)
        // LAF-based duration includes the Fast decay tail (≈ 35 dB/s): 83 → 59 dB takes ≈ 0.7 s.
        assertTrue(e.durationSeconds in 2.0..3.0, "duration ${e.durationSeconds}")
        assertEquals(toneLevel + 10 * log10(2.0), e.selDb, 0.7)
        assertEquals("loud_vehicle", e.dominantCategory)
        assertEquals("Motorcycle", e.topLabels.first().label)
        assertTrue(e.classifierFrames >= 2)
        assertEquals(7L, e.calibrationId)
        assertTrue(e.startIso.startsWith("2026-10-03T00:00:"), e.startIso)
        assertTrue(e.startIso.endsWith("+02:00"))

        // Minutes: partial 23:59:20.5…00:00, full 00:00…00:01, partial at stop.
        assertEquals(3, minutes.size, minutes.joinToString("\n"))
        assertEquals("2026-10-03T00:00:00+02:00", minutes[1].startIso)
        assertEquals(60.0, minutes[1].durationSeconds, 0.13)
        assertEquals(1, minutes[1].eventCount)
        for (m in minutes) {
            assertTrue(m.l1Db >= m.l10Db && m.l10Db >= m.l50Db && m.l50Db >= m.l90Db)
            assertTrue(m.lafMaxDb >= m.l1Db && m.l90Db >= m.lafMinDb)
            assertEquals(false, m.calibrated)
            assertEquals(1.0, m.categoryShares.values.sum(), 1e-9)
        }
        assertTrue(minutes[1].categoryShares.getValue("loud_vehicle") > 0.0)
    }
}
