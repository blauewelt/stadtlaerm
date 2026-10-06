package ch.stadtlaerm.dsp

import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResamplerTest {
    private fun rmsOf(x: FloatArray, skip: Int): Double {
        var s = 0.0
        for (i in skip until x.size) s += x[i].toDouble() * x[i]
        return sqrt(s / (x.size - skip))
    }

    private fun attenuationDb(freq: Double): Double {
        val r = DecimatingResampler()
        val x = TestSignals.sine(freq, 0.9, 1.0)
        val y = r.processAll(x)
        assertEquals(16_000, y.size)
        return 20 * log10(rmsOf(y, 400) / rmsOf(x, 0))
    }

    @Test
    fun stopbandAttenuationAtLeast60dB() {
        for (f in doubleArrayOf(8_500.0, 10_000.0, 12_000.0, 16_000.0, 20_000.0)) {
            val a = attenuationDb(f)
            assertTrue(a <= -60.0, "attenuation at $f Hz only $a dB")
        }
        // Analytic response over the whole stopband (≥ 8 kHz, the 16 kHz Nyquist).
        val r = DecimatingResampler()
        var f = 8_000.0
        while (f <= 24_000.0) {
            assertTrue(r.magnitudeDb(f) <= -60.0, "response at $f Hz = ${r.magnitudeDb(f)} dB")
            f += 25.0
        }
    }

    @Test
    fun passbandIsFlat() {
        assertEquals(0.0, attenuationDb(1_000.0), 0.05)
        val r = DecimatingResampler()
        for (f in doubleArrayOf(50.0, 500.0, 2_000.0, 4_000.0, 6_000.0, 7_000.0)) {
            assertEquals(0.0, r.magnitudeDb(f), 0.05, "passband at $f Hz")
        }
    }

    @Test
    fun streamingEqualsBatchAcrossOddBlockSizes() {
        val x = TestSignals.whiteNoise(0.1, 0.5)
        val batch = DecimatingResampler().processAll(x)
        val r = DecimatingResampler()
        val ring = FloatRingBuffer(batch.size)
        var p = 0
        val sizes = intArrayOf(1, 7, 333, 4097, 6000)
        var k = 0
        while (p < x.size) {
            val n = minOf(sizes[k++ % sizes.size], x.size - p)
            r.process(x.copyOfRange(p, p + n), n, ring)
            p += n
        }
        val out = FloatArray(ring.available)
        ring.copyLatest(out, out.size)
        assertEquals(batch.size, out.size)
        for (i in out.indices) assertEquals(batch[i], out[i], 0f)
    }

    @Test
    fun ringBufferKeepsOnlyLatest() {
        val ring = FloatRingBuffer(5)
        for (i in 1..12) ring.write(i.toFloat())
        val out = FloatArray(3)
        assertTrue(ring.copyLatest(out, 3))
        assertEquals(listOf(10f, 11f, 12f), out.toList())
        assertTrue(!ring.copyLatest(FloatArray(6), 6))
    }
}
