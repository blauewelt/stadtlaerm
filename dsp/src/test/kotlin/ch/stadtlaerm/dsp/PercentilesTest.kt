package ch.stadtlaerm.dsp

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PercentilesTest {
    @Test
    fun exceedanceSemanticsOnRamp() {
        // 0..100 dB ramp, 101 values: L10 is exceeded 10 % of the time → 90 dB.
        val v = DoubleArray(101) { it.toDouble() }.also { it.shuffle(Random(3)) }
        val s = Percentiles.stats(v)
        assertEquals(90.0, s.l10, 1e-9)
        assertEquals(50.0, s.l50, 1e-9)
        assertEquals(10.0, s.l90, 1e-9)
        assertEquals(99.0, s.l1, 1e-9)
        assertEquals(0.0, s.min); assertEquals(100.0, s.max)
    }

    @Test
    fun orderingOnSyntheticLafData() {
        // 480 LAF samples: background ~45 dB with noise, a few loud passes up to 80 dB.
        val r = Random(7)
        val v = DoubleArray(480) { 45 + r.nextDouble() * 4 }
        for (i in 100 until 130) v[i] = 70 + r.nextDouble() * 10
        val s = Percentiles.stats(v)
        assertTrue(s.l1 >= s.l10 && s.l10 >= s.l50 && s.l50 >= s.l90, "$s")
        assertTrue(s.l10 < 70) // only 6 % of samples are loud
        assertTrue(s.l1 > 70)
        assertTrue(s.l90 in 45.0..46.0)
    }
}
