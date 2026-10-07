package ch.stadtlaerm.dsp

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The diagnostics hooks of [MeasurementEngine.Listener] (v0.3.3, used by the Labor recorder):
 * lifecycle order and sample indices, the clock anchor, and that [MeasurementEngine.Listener.onEvent]
 * is still called exactly as before.
 */
class EngineHooksTest {
    private val fs = Acoustics.SAMPLE_RATE

    private class Rec : MeasurementEngine.Listener {
        val log = ArrayList<String>()
        val events = ArrayList<NoiseEvent>()
        val emitted = ArrayList<Triple<NoiseEvent, Long, Long>>()
        val anchors = ArrayList<Pair<Long, Long>>()
        override fun onEvent(event: NoiseEvent) { events += event }
        override fun onClockAnchored(anchorSample: Long, anchorEpochMs: Long) { anchors += anchorSample to anchorEpochMs }
        override fun onEventCandidate(startSample: Long) { log += "candidate $startSample" }
        override fun onEventConfirmed(startSample: Long) { log += "confirmed $startSample" }
        override fun onEventDiscarded(startSample: Long) { log += "discarded $startSample" }
        override fun onEventClosed(startSample: Long, endSample: Long) { log += "closed $startSample $endSample" }
    }

    /** Overrides only [onEvent]: the default [onEventEmitted] must forward to it. */
    private class OldStyle : MeasurementEngine.Listener {
        val events = ArrayList<NoiseEvent>()
        override fun onEvent(event: NoiseEvent) { events += event }
    }

    private fun signal(): FloatArray = TestSignals.concat(
        TestSignals.whiteNoise(1e-3, 40.0, seed = 1),
        // 0.1 s blip: a candidate that is discarded (too short).
        TestSignals.add(TestSignals.sine(800.0, 0.008, 0.1), TestSignals.whiteNoise(1e-3, 0.1, seed = 2)),
        TestSignals.whiteNoise(1e-3, 5.0, seed = 3),
        // 2 s tone: a real event.
        TestSignals.add(TestSignals.sine(800.0, 0.05, 2.0), TestSignals.whiteNoise(1e-3, 2.0, seed = 4)),
        TestSignals.whiteNoise(1e-3, 8.0, seed = 5),
    )

    @Test
    fun lifecycleHooksCarrySampleIndicesAndOnEventIsUnchanged() {
        val start = 1_790_000_000_000L
        val clock = SimClock(start)
        val rec = Rec()
        val e = MeasurementEngine(EngineConfig(zone = ZoneId.of("Europe/Zurich"), classifierEnabled = false), null, rec, clock::now)
        TestSignals.feed(e, signal(), clock = clock)
        e.stop()

        // Anchored once, at the end of the first block, with the wall clock at its delivery.
        assertEquals(listOf(6000L to start + 125), rec.anchors)

        val kinds = rec.log.map { it.substringBefore(' ') }
        assertEquals(listOf("candidate", "discarded", "candidate", "confirmed", "closed"), kinds, rec.log.toString())
        val discardedStart = rec.log[1].split(' ')[1].toLong()
        assertEquals(rec.log[0].split(' ')[1].toLong(), discardedStart)
        val (_, s, end) = rec.log[4].split(' ')
        val evStart = s.toLong()
        val evEnd = end.toLong()
        assertEquals(rec.log[2].split(' ')[1].toLong(), evStart)
        // The tone starts at 40.1 + 5.0 s; the detector works on 125 ms ticks.
        val toneStart = ((45.1) * fs).toLong()
        assertTrue(evStart in toneStart - fs / 8..toneStart + fs / 8, "start $evStart vs $toneStart")
        assertTrue(evEnd > evStart + fs, "end $evEnd")

        assertEquals(1, rec.events.size)
        assertEquals(e.epochMsAt(evStart), rec.events[0].startEpochMs)

        // Same input, a listener that only knows onEvent: identical events.
        val clock2 = SimClock(start)
        val old = OldStyle()
        val e2 = MeasurementEngine(EngineConfig(zone = ZoneId.of("Europe/Zurich"), classifierEnabled = false), null, old, clock2::now)
        TestSignals.feed(e2, signal(), clock = clock2)
        e2.stop()
        assertEquals(rec.events, old.events)
    }

    @Test
    fun reAnchoringIsReported() {
        val start = 1_790_000_000_000L
        val clock = SimClock(start, rate = 1.02) // wall clock runs 2 % fast → > 0.5 s drift per minute
        val rec = Rec()
        val e = MeasurementEngine(EngineConfig(classifierEnabled = false), null, rec, clock::now)
        TestSignals.feed(e, TestSignals.whiteNoise(1e-3, 150.0), clock = clock)
        assertTrue(rec.anchors.size >= 2, rec.anchors.toString())
        // Every reported anchor reproduces the engine's mapping at that moment's anchor sample.
        val (lastSample, lastMs) = rec.anchors.last()
        assertEquals(lastMs, e.epochMsAt(lastSample))
        assertEquals(6000L, rec.anchors.first().first)
    }

    @Test
    fun classifierResultsCarryTop5GainAndLevel() {
        val start = 1_790_000_000_000L
        val clock = SimClock(start)
        val mapper = TestSignals.mapper()
        val motorcycle = mapper.labels.indexOf("Motorcycle")
        val results = ArrayList<ch.stadtlaerm.dsp.classify.ClassifierResult>()
        val classifications = ArrayList<Long>()
        val e = MeasurementEngine(
            EngineConfig(zone = ZoneId.of("Europe/Zurich"), classifierEnabled = true), mapper,
            object : MeasurementEngine.Listener {
                override fun onClassifierResult(result: ch.stadtlaerm.dsp.classify.ClassifierResult) { results += result }
                override fun onClassification(endEpochMs: Long, decision: ch.stadtlaerm.dsp.classify.CategoryDecision, top: List<ch.stadtlaerm.dsp.classify.LabelScore>) {
                    classifications += endEpochMs
                }
            },
            clock::now,
        )
        // 20 s quiet, 3 s loud tone (≈ +30 dB), 10 s quiet.
        val signal = TestSignals.concat(
            TestSignals.whiteNoise(1e-3, 20.0, seed = 1),
            TestSignals.add(TestSignals.sine(1000.0, 0.05, 3.0), TestSignals.whiteNoise(1e-3, 3.0, seed = 2)),
            TestSignals.whiteNoise(1e-3, 10.0, seed = 3),
        )
        val window = FloatArray(15_600)
        val buf = FloatArray(6000)
        var p = 0
        var gain = 0.0
        while (p < signal.size) {
            val n = minOf(6000, signal.size - p)
            signal.copyInto(buf, 0, p, p + n)
            clock.advance(n)
            e.process(buf, n)
            p += n
            if (e.classifierDue()) {
                val end = e.copyClassifierWindow(window)
                val scores = FloatArray(mapper.labels.size) { 0.001f * (it % 7) }
                scores[motorcycle] = 0.8f
                gain += 1.0
                e.onClassifierFrame(ch.stadtlaerm.dsp.classify.ClassifierFrame(end, e.classifierWindowLength48k, scores, inputGainDb = gain))
            }
        }
        e.stop()
        assertTrue(results.size >= 25, "results ${results.size}")
        // Every valid frame is reported, with the same timestamp as onClassification.
        assertEquals(classifications, results.filter { !it.ignoredInvalid }.map { it.endEpochMs })
        for ((i, r) in results.withIndex()) {
            assertEquals(5, r.top.size)
            assertEquals("Motorcycle", r.top.first().label)
            assertEquals(0.8f, r.top.first().score)
            assertEquals(i + 1.0, r.inputGainDb)
            assertEquals("loud_vehicle", r.decision?.dominant)
            assertEquals(e.epochMsAt(r.endSample), r.endEpochMs)
            assertEquals(r.endSample - e.classifierWindowLength48k, r.startSample)
            assertTrue(!r.lafDb.isNaN() && !r.lafMaxDb.isNaN(), r.toString())
            assertTrue(r.lafMaxDb >= r.lafDb - 0.01, r.toString())
        }
        // Windows inside the tone are ≈ 30 dB louder than the quiet ones.
        val quiet = results.first { it.endSample < 15L * fs }
        val loud = results.first { it.startSample > (20.2 * fs).toLong() && it.endSample < (22.8 * fs).toLong() }
        assertTrue(loud.lafMaxDb - quiet.lafMaxDb > 20, "quiet ${quiet.lafMaxDb} loud ${loud.lafMaxDb}")
    }

    @Test
    fun windowsOverlappingInvalidAudioAreReportedAsIgnored() {
        val clock = SimClock(1_790_000_000_000L)
        val mapper = TestSignals.mapper()
        val results = ArrayList<ch.stadtlaerm.dsp.classify.ClassifierResult>()
        var classifications = 0
        val e = MeasurementEngine(
            EngineConfig(classifierEnabled = true), mapper,
            object : MeasurementEngine.Listener {
                override fun onClassifierResult(result: ch.stadtlaerm.dsp.classify.ClassifierResult) { results += result }
                override fun onClassification(endEpochMs: Long, decision: ch.stadtlaerm.dsp.classify.CategoryDecision, top: List<ch.stadtlaerm.dsp.classify.LabelScore>) { classifications++ }
            },
            clock::now,
        )
        TestSignals.feed(e, TestSignals.whiteNoise(1e-3, 5.0), clock = clock)
        // The microphone is silenced for 0.5 s; the last 0.975 s then overlap invalid audio.
        e.setMicSilenced(true)
        TestSignals.feed(e, TestSignals.whiteNoise(1e-3, 0.5, seed = 2), clock = clock)
        e.setMicSilenced(false)
        TestSignals.feed(e, TestSignals.whiteNoise(1e-3, 0.25, seed = 3), clock = clock)
        val window = FloatArray(15_600)
        val end = e.copyClassifierWindow(window)
        e.onClassifierFrame(ch.stadtlaerm.dsp.classify.ClassifierFrame(end, e.classifierWindowLength48k, FloatArray(mapper.labels.size)))
        assertEquals(0, classifications)
        assertEquals(1, results.size)
        assertTrue(results[0].ignoredInvalid)
        assertEquals(null, results[0].decision)
    }
}
