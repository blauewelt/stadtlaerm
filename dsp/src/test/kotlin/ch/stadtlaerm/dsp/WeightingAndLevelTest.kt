package ch.stadtlaerm.dsp

import kotlin.math.E
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WeightingAndLevelTest {

    private val aw = AWeighting(48_000)

    @Test
    fun aWeightingMatchesIecNominalAt48k() {
        // IEC 61672-1 nominal values (table 3).
        assertEquals(-19.1, aw.magnitudeDb(100.0), 0.3, "100 Hz")
        assertEquals(0.0, aw.magnitudeDb(1000.0), 1e-9, "1 kHz must be exactly 0 dB")
        assertEquals(1.0, aw.magnitudeDb(4000.0), 0.3, "4 kHz")
        assertEquals(-2.5, aw.magnitudeDb(10000.0), 1.0, "10 kHz")
    }

    @Test
    fun aWeightingMatchesAnalogPrototypeAcrossBand() {
        val bands = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 10000.0)
        for (f in bands) {
            val err = aw.magnitudeDb(f) - AWeighting.analogDb(f)
            assertTrue(abs(err) < 0.5, "error at $f Hz = $err dB")
        }
    }

    /** Simulated (time domain) response must match the analytic response. */
    @Test
    fun aWeightingTimeDomainMatchesResponse() {
        for (f in doubleArrayOf(100.0, 1000.0, 4000.0, 10000.0)) {
            val x = TestSignals.sine(f, 0.5, 2.0)
            val w = AWeighting(48_000)
            var sIn = 0.0; var sOut = 0.0
            for (i in x.indices) {
                val y = w.process(x[i].toDouble())
                if (i >= 48_000) { sIn += x[i].toDouble() * x[i]; sOut += y * y }
            }
            val gainDb = 10 * log10(sOut / sIn)
            assertEquals(w.magnitudeDb(f), gainDb, 0.05, "time-domain gain at $f Hz")
        }
    }

    @Test
    fun defaultOffsetFollowsCdd() {
        assertEquals(112.35, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, 0.01)
    }

    @Test
    fun laeqOfKnownSineWithOffset() {
        val amplitude = 0.1
        val offset = 100.0
        val expected = 20 * log10(amplitude / sqrt(2.0)) + offset // 76.99 dB
        // Direct: A-weighting + mean square over 1 s after settling.
        val x = TestSignals.sine(1000.0, amplitude, 2.0)
        val w = AWeighting()
        var s = 0.0
        for (i in x.indices) {
            val y = w.process(x[i].toDouble())
            if (i >= 48_000) s += y * y
        }
        assertEquals(expected, Acoustics.db(s / 48_000, offset), 0.01)

        // Through the full engine: per-second LAeq and minute LAeq.
        val seconds = ArrayList<SecondResult>()
        val minutes = ArrayList<MinuteRecord>()
        val engine = MeasurementEngine(
            EngineConfig(calibrationOffsetDb = offset, classifierEnabled = false),
            startEpochMs = 1_700_000_000_000L, mapper = null,
            listener = object : MeasurementEngine.Listener {
                override fun onSecond(second: SecondResult) { seconds += second }
                override fun onMinute(minute: MinuteRecord) { minutes += minute }
            },
        )
        TestSignals.feed(engine, TestSignals.sine(1000.0, amplitude, 10.0))
        engine.stop()
        assertTrue(seconds.size >= 9)
        for (sr in seconds.drop(1)) assertEquals(expected, sr.laeqDb, 0.02)
        // Steady sine: LAFmax ≈ LAeq + small ripple (Fast weighting of a 1 kHz sine).
        assertEquals(expected, seconds.last().lafMaxDb, 0.2)
        assertEquals(1, minutes.size)
        assertEquals(expected, minutes[0].laeqDb, 0.02)
    }

    @Test
    fun fastTimeWeightingStepResponse() {
        val tw = ExponentialTimeWeighting(ExponentialTimeWeighting.FAST_TAU, 48_000)
        var v = 0.0
        repeat(6000) { v = tw.process(1.0) } // 125 ms of a unit step in mean square
        assertEquals(1 - 1 / E, v, 1e-3)
        // Decay: after another 125 ms of silence → e^-1 of the start value.
        val start = tw.process(1.0)
        repeat(6000) { v = tw.process(0.0) }
        assertEquals(start / E, v, 1e-3)
    }

    @Test
    fun energyAverage() {
        assertEquals(63.0103, Acoustics.energyAverage(doubleArrayOf(60.0, 60.0, 60.0, 60.0 + 10 * log10(5.0))), 1e-3)
    }
}
