package ch.stadtlaerm.app.labor

import ch.stadtlaerm.dsp.DecimatingResampler
import ch.stadtlaerm.dsp.FloatRingBuffer

// LABOR BUILD ONLY (app/src/labor/).

/**
 * 48 kHz → 16 kHz with the app's anti-aliasing decimator ([DecimatingResampler], the same filter
 * as the classifier path), with its group delay removed: output sample j corresponds to input
 * sample 3·j + 2 counted from the first input sample fed (2 input samples = 0.04 ms).
 *
 * @param maxInputPerCall upper bound for `count` in [process].
 */
class Resample16k(maxInputPerCall: Int) {
    private val dec = DecimatingResampler(48_000, 3)
    private val ring = FloatRingBuffer(maxInputPerCall / 3 + 2)
    private var toSkip = dec.delaySamples / dec.factor // 40 outputs = 120 input samples
    private val zeros = FloatArray(dec.delaySamples)

    /** Resamples [count] samples of [input] into [out] (size ≥ count/3 + 2); returns the output count. */
    fun process(input: FloatArray, count: Int, out: FloatArray): Int {
        ring.clear()
        dec.process(input, count, ring)
        var n = ring.available
        ring.copyLatest(out, n)
        if (toSkip > 0 && n > 0) {
            val s = minOf(toSkip, n)
            out.copyInto(out, 0, s, n)
            n -= s
            toSkip -= s
        }
        return n
    }

    /** Pushes the filter's delay worth of zeros through, emitting the last real outputs. */
    fun flush(out: FloatArray): Int = process(zeros, zeros.size, out)

    companion object {
        /** Whole-buffer conversion (event clips): exactly ⌊(n + 120) / 3⌋ − 40 ≈ n/3 outputs. */
        fun convert(chunks: List<FloatArray>, length: Int): FloatArray {
            val maxChunk = maxOf(chunks.maxOfOrNull { it.size } ?: 0, 120)
            val r = Resample16k(maxChunk)
            val out = FloatArray(length / 3 + 64)
            val tmp = FloatArray(maxChunk / 3 + 2)
            var n = 0
            var left = length
            for (c in chunks) {
                if (left <= 0) break
                val k = minOf(c.size, left)
                val m = r.process(c, k, tmp)
                tmp.copyInto(out, n, 0, m); n += m
                left -= k
            }
            val m = r.flush(tmp)
            tmp.copyInto(out, n, 0, m); n += m
            return out.copyOf(n)
        }
    }
}
