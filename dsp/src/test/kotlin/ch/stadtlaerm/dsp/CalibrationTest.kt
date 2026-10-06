package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.calibration.CalibrationMath
import ch.stadtlaerm.dsp.calibration.CalibrationMeasurement
import ch.stadtlaerm.dsp.calibration.CalibrationWarning
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalibrationTest {

    private fun measure(signal: FloatArray, seconds: Int, tone: Boolean) =
        CalibrationMeasurement(seconds, analyzeTone = tone).also { it.process(signal) }.result()

    @Test
    fun referenceMeterOffsetMath() {
        assertEquals(112.0, CalibrationMath.referenceOffset(62.0, -50.0), 1e-12)
        // Simulate: steady noise; meter shows 65.0 dB(A).
        val noise = TestSignals.whiteNoise(0.003, 31.0)
        val m = CalibrationMeasurement(30)
        m.process(noise)
        assertTrue(m.isComplete)
        val r = m.result()
        assertEquals(30.0, r.measuredSeconds, 1e-9)
        assertEquals(30, r.secondLevelsRawDb.size)
        assertTrue(r.stdDevDb < 0.2, "steady noise std ${r.stdDevDb}")
        val offset = CalibrationMath.referenceOffset(65.0, r.rawLaeqDb)
        assertTrue(CalibrationMath.referenceWarnings(r, 65.0).isEmpty())

        // Applying the offset in the engine reproduces the meter reading.
        val seconds = ArrayList<SecondResult>()
        val engine = MeasurementEngine(
            EngineConfig(calibrationOffsetDb = offset, calibrated = true, classifierEnabled = false),
            0L, null,
            object : MeasurementEngine.Listener { override fun onSecond(second: SecondResult) { seconds += second } },
        )
        TestSignals.feed(engine, noise)
        val avg = Acoustics.energyAverage(seconds.drop(1).map { it.laeqDb }.toDoubleArray())
        assertEquals(65.0, avg, 0.1)
    }

    @Test
    fun referenceWarnings() {
        // Alternating loud / quiet 2 s segments → unsteady.
        val parts = (0 until 16).map { i -> TestSignals.whiteNoise(if (i % 2 == 0) 0.01 else 0.002, 2.0, seed = i) }
        val r = measure(TestSignals.concat(*parts.toTypedArray()), 30, false)
        assertTrue(r.stdDevDb > 2.0)
        val w = CalibrationMath.referenceWarnings(r, 45.0)
        assertTrue(CalibrationWarning.UNSTEADY in w)
        assertTrue(CalibrationWarning.TOO_QUIET in w)
    }

    @Test
    fun acousticCalibratorOffsetMath() {
        val amp = 0.2
        val r = measure(TestSignals.sine(1000.0, amp, 11.0), 10, true)
        val expectedRaw = 20 * log10(amp / sqrt(2.0))
        assertEquals(expectedRaw, r.rawLaeqDb, 0.01) // A = 0 dB at 1 kHz
        assertEquals(1000.0, r.toneFrequencyHz!!, 2.0)
        assertTrue(r.tonality!! > 0.95, "tonality ${r.tonality}")
        val offset = CalibrationMath.calibratorOffset(94.0, r.rawLaeqDb)
        assertEquals(94.0 - expectedRaw, offset, 0.01)
        assertTrue(CalibrationMath.calibratorWarnings(r, 94.0).isEmpty())
        // 114 dB calibrator setting with the same signal → +20 dB offset (implausible here).
        assertEquals(offset + 20, CalibrationMath.calibratorOffset(114.0, r.rawLaeqDb), 1e-9)
    }

    @Test
    fun calibratorChecksRejectWrongSignals() {
        val wrongFreq = measure(TestSignals.sine(1200.0, 0.2, 11.0), 10, true)
        assertTrue(CalibrationWarning.FREQUENCY_OFF in CalibrationMath.calibratorWarnings(wrongFreq, 94.0))
        val noise = measure(TestSignals.whiteNoise(0.1, 11.0), 10, true)
        assertTrue(CalibrationWarning.NOT_TONAL in CalibrationMath.calibratorWarnings(noise, 94.0))
        // 1.04 kHz is within ±5 %.
        val ok = measure(TestSignals.sine(1040.0, 0.2, 11.0), 10, true)
        assertFalse(CalibrationWarning.FREQUENCY_OFF in CalibrationMath.calibratorWarnings(ok, 94.0))
    }

    @Test
    fun clippedSignalIsFlagged() {
        // E.g. a 114 dB calibrator on a phone with CDD sensitivity (full scale ≈ 109 dB SPL).
        val clippedTone = TestSignals.sine(1000.0, 1.5, 11.0).map { it.coerceIn(-1f, 1f) }.toFloatArray()
        val r = measure(clippedTone, 10, true)
        assertTrue(r.clippedFraction > 0.1, "clipped fraction ${r.clippedFraction}")
        assertTrue(CalibrationWarning.CLIPPING in CalibrationMath.calibratorWarnings(r, 114.0))
        assertTrue(CalibrationWarning.CLIPPING in CalibrationMath.referenceWarnings(r, 100.0))
        val clean = measure(TestSignals.sine(1000.0, 0.5, 11.0), 10, true)
        assertEquals(0.0, clean.clippedFraction, 0.0)
        assertFalse(CalibrationWarning.CLIPPING in CalibrationMath.calibratorWarnings(clean, 94.0))
    }

    @Test
    fun implausibleOffsetIsFlagged() {
        assertFalse(CalibrationMath.implausible(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB + 8))
        assertTrue(CalibrationMath.implausible(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB - 25))
    }
}
