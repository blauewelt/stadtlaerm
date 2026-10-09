package ch.stadtlaerm.dsp

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Detector v2 end to end on the three synthetic replay fixtures (16 kHz WAVs in
 * `src/test/resources/replay/`, upsampled to 48 kHz and fed through the production engine):
 * the outputs must match the committed expected CSVs (`replay/expected/`), and each fixture is
 * checked against what it was built to show.
 *
 * `STADTLAERM_WRITE_FIXTURES=1 ./gradlew :dsp:test --tests '*ReplayTest*'` rewrites the WAVs and the
 * expected CSVs (review the diff before committing). Every run writes the actual outputs to
 * `dsp/build/replay/`.
 */
class ReplayTest {
    private val dir = File(System.getProperty("stadtlaerm.replayDir") ?: "src/test/resources/replay")
    private val write = System.getProperty("stadtlaerm.writeFixtures") == "1"
    private val outDir = File("build/replay")

    private fun replay(name: String): Replay.Result {
        val wav = File(dir, "$name.wav")
        if (write) Wav.write(wav, ReplayFixtures.all.getValue(name)(), ReplayFixtures.RATE)
        val r = Replay.runWav(wav)
        outDir.mkdirs()
        File(outDir, "$name.events.csv").writeText(r.eventsCsv())
        File(outDir, "$name.minutes.csv").writeText(r.minutesCsv())
        val expected = File(dir, "expected")
        if (write) {
            expected.mkdirs()
            File(expected, "$name.events.csv").writeText(r.eventsCsv())
            File(expected, "$name.minutes.csv").writeText(r.minutesCsv())
        }
        assertCsvMatches(File(expected, "$name.events.csv").readText(), r.eventsCsv(), "$name events")
        assertCsvMatches(File(expected, "$name.minutes.csv").readText(), r.minutesCsv(), "$name minutes")
        return r
    }

    /** Same header and rows; numbers within 0.05 (rounding of the last digit across JVMs), text exact. */
    private fun assertCsvMatches(expected: String, actual: String, what: String) {
        val e = expected.trimEnd().lines()
        val a = actual.trimEnd().lines()
        assertEquals(e.size, a.size, "$what: rows\n$actual")
        assertEquals(e[0], a[0], "$what: header")
        for (r in 1 until e.size) {
            val ef = e[r].split(",")
            val af = a[r].split(",")
            assertEquals(ef.size, af.size, "$what row $r")
            for (c in ef.indices) {
                val x = ef[c].toDoubleOrNull()
                val y = af[c].toDoubleOrNull()
                if (x != null && y != null) assertTrue(abs(x - y) <= 0.05 + 1e-9, "$what row $r ${e[0].split(",")[c]}: $x vs $y")
                else assertEquals(ef[c], af[c], "$what row $r ${e[0].split(",")[c]}")
            }
        }
    }

    /** LAF of the background alone (L90 of the ticks before [untilS]). */
    private fun floorBefore(r: Replay.Result, untilS: Double): Double {
        val t0 = r.ticks.first().epochMs - 125
        val l = r.ticks.filter { it.valid && it.epochMs - t0 <= untilS * 1000 }.map { it.lafDb }.toDoubleArray()
        return Percentiles.exceedanceLevel(l, 90.0)
    }

    @Test
    fun smoothHumpIsOneBurstWithHumpShape() {
        val r = replay("hump")
        val ev = r.events.single()
        val f = ev.features!!
        // Local floor = the background before the hump.
        assertEquals(floorBefore(r, ReplayFixtures.HUMP_START_S), f.localFloorDb, 0.5)
        // Excess: the hump peaks at 10·log10(1 + 15) ≈ 12 dB over the background.
        assertEquals(10 * log10(1 + ReplayFixtures.HUMP_PEAK_RATIO), f.excessDb, 1.0)
        assertEquals(ev.lafMaxDb - f.localFloorDb, f.excessDb, 1e-9)
        // Trigger: LAF ≥ floor + 6.5 dB, i.e. power envelope sin²(πt/T) ≥ (10^0.65 − 1)/15.
        val tCross = ReplayFixtures.HUMP_LENGTH_S / PI * asin(sqrt((Math.pow(10.0, 0.65) - 1) / ReplayFixtures.HUMP_PEAK_RATIO))
        val startS = (ev.startEpochMs - Replay.START_MS) / 1000.0
        assertEquals(ReplayFixtures.HUMP_START_S + tCross, startS, 0.3, "start $startS")
        assertEquals(6.5, ev.thresholdDb)
        // Shape: smooth, rise of seconds (vehicle-like: ≥ 0.7 s), mid band rises, no wind.
        assertEquals(EventShape.HUMP, f.shape)
        assertTrue(f.riseS in 1.0..3.0, "rise ${f.riseS}")
        assertTrue(f.decayS in 1.0..3.5, "decay ${f.decayS}")
        assertTrue(f.jaggedness < 0.1, "jaggedness ${f.jaggedness}")
        assertTrue(f.midBandRiseDb >= 2.0, "mid band ${f.midBandRiseDb}")
        assertTrue(f.lfShare < 0.2, "lfShare ${f.lfShare}")
        assertTrue(f.lfFlutterDb < 3.8, "flutter ${f.lfFlutterDb}")
        assertEquals(false, f.wind)
        assertEquals(null, ev.dominantCategory) // classifier off in the replay
        // Minute record: one burst, no wind, the local floor as hum.
        val m = r.minutes.single()
        assertEquals(1, m.eventCount)
        assertEquals(0, m.windEventCount)
        assertEquals(f.localFloorDb, m.localFloorDb, 1.0)
        // A higher excess setting (above the hump's ≈ 12 dB) finds nothing.
        val (x, _) = Wav.read(File(dir, "hump.wav"))
        assertEquals(0, Replay.run(Upsampler3.process(x), Replay.config(excessDb = 13.0)).events.size)
    }

    @Test
    fun lowFrequencyThumpIsWind() {
        val r = replay("wind_thump")
        val ev = r.events.single()
        val f = ev.features!!
        assertTrue(f.lfShare >= 0.95, "lfShare ${f.lfShare}")
        assertEquals(true, f.wind)
        assertEquals(WindRule.CATEGORY, ev.dominantCategory)
        assertEquals(EventShape.IMPULSE, f.shape)
        assertTrue(f.riseS < EventShape.IMPULSE_MAX_RISE_S, "rise ${f.riseS}")
        // Counted as wind, not as a burst.
        val m = r.minutes.single()
        assertEquals(0, m.eventCount)
        assertEquals(1, m.windEventCount)
        // And it is in the CSV with the flag.
        val csv = r.eventsCsv().lines()
        val h = csv[0].split(",")
        assertEquals("true", csv[1].split(",")[h.indexOf("wind")])
        assertEquals("wind", csv[1].split(",")[h.indexOf("dominant_category")])
    }

    @Test
    fun steadyToneIsTheFloorNotAnEvent() {
        val r = replay("steady_tone")
        assertEquals(0, r.events.size)
        val toneDb = 20 * log10(ReplayFixtures.TONE_AMPLITUDE / sqrt(2.0)) + Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
        val m = r.minutes.single()
        assertEquals(toneDb, m.localFloorDb, 0.5)
        assertEquals(0, m.eventCount)
        assertEquals(0, m.windEventCount)
        assertEquals(0, r.closed.size)
    }

    @Test
    fun upsamplerKeepsLevelAndTiming() {
        val x = FloatArray(16_000) { (0.1 * kotlin.math.sin(2 * PI * 1000.0 * it / 16_000)).toFloat() }
        val y = Upsampler3.process(x)
        assertEquals(48_000, y.size)
        val rmsIn = sqrt(x.drop(2000).take(12_000).sumOf { it.toDouble() * it } / 12_000)
        val rmsOut = sqrt(y.drop(6000).take(36_000).sumOf { it.toDouble() * it } / 36_000)
        assertEquals(0.0, 20 * log10(rmsOut / rmsIn), 0.05)
        // No delay: output sample 3·j is input sample j.
        for (j in 4000 until 4010) assertEquals(x[j].toDouble(), y[3 * j].toDouble(), 2e-3)
    }

    @Test
    fun wavRoundTrip() {
        val f = File(outDir, "roundtrip.wav")
        val x = FloatArray(100) { (it - 50) / 64f }
        Wav.write(f, x, 16_000)
        val (y, rate) = Wav.read(f)
        assertEquals(16_000, rate)
        for (i in x.indices) assertEquals(x[i], y[i], 1f / 32768)
    }
}
