package ch.stadtlaerm.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** Windowed-sinc FIR design helpers. */
object FirDesign {

    /** Zeroth-order modified Bessel function of the first kind (series expansion). */
    fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val half = x / 2.0
        var k = 1
        while (k < 200) {
            term *= (half / k) * (half / k)
            sum += term
            if (term < 1e-17 * sum) break
            k++
        }
        return sum
    }

    /** Linear-phase low-pass, Kaiser window, unity DC gain. */
    fun kaiserLowpass(numTaps: Int, cutoffHz: Double, sampleRate: Double, beta: Double): DoubleArray {
        require(numTaps % 2 == 1) { "use an odd number of taps (type I FIR)" }
        val m = (numTaps - 1) / 2.0
        val fc = cutoffHz / sampleRate
        val i0b = besselI0(beta)
        val h = DoubleArray(numTaps) { n ->
            val t = n - m
            val sinc = if (t == 0.0) 2 * fc else sin(2 * PI * fc * t) / (PI * t)
            val r = t / m
            val w = besselI0(beta * sqrt(maxOf(0.0, 1 - r * r))) / i0b
            sinc * w
        }
        val s = h.sum()
        for (i in h.indices) h[i] /= s
        return h
    }

    /** Magnitude response in dB of FIR [h] at [freqHz]. */
    fun magnitudeDb(h: DoubleArray, freqHz: Double, sampleRate: Double): Double {
        val w = 2 * PI * freqHz / sampleRate
        var re = 0.0
        var im = 0.0
        for (n in h.indices) {
            re += h[n] * cos(w * n)
            im -= h[n] * sin(w * n)
        }
        return 20 * log10(maxOf(hypot(re, im), 1e-30))
    }
}

/**
 * Streaming anti-aliasing low-pass + decimation by an integer factor (48 kHz → 16 kHz for the
 * classifier). Only every [factor]-th output is computed (polyphase-equivalent cost).
 *
 * Default filter: 241 taps, Kaiser β = 7, cutoff 7.5 kHz at 48 kHz → passband flat to 7 kHz
 * (±0.01 dB), ≥ 70 dB attenuation from 8 kHz (the new Nyquist) upwards.
 */
class DecimatingResampler(
    val inputRate: Int = Acoustics.SAMPLE_RATE,
    val factor: Int = 3,
    numTaps: Int = 241,
    cutoffHz: Double = 7500.0,
    beta: Double = 7.0,
) {
    val outputRate: Int = inputRate / factor
    val taps: DoubleArray = FirDesign.kaiserLowpass(numTaps, cutoffHz, inputRate.toDouble(), beta)
    private val n = taps.size
    // Each sample is stored twice (at pos and pos + n) so the filter window is always contiguous.
    private val history = DoubleArray(2 * n)
    private var pos = 0
    private var phase = 0

    init {
        require(inputRate % factor == 0)
    }

    /** Processes [count] input samples and appends the decimated output to [out]. */
    fun process(input: FloatArray, count: Int, out: FloatRingBuffer) {
        for (i in 0 until count) {
            val x = input[i].toDouble()
            history[pos] = x
            history[pos + n] = x
            pos++
            if (pos == n) pos = 0
            phase++
            if (phase == factor) {
                phase = 0
                // history[pos .. pos+n-1] holds the last n samples, oldest first.
                var acc = 0.0
                var k = n - 1
                var j = pos
                while (k >= 0) {
                    acc += taps[k] * history[j]
                    k--; j++
                }
                out.write(acc.toFloat())
            }
        }
    }

    /** Convenience for tests: decimates a whole array. */
    fun processAll(input: FloatArray): FloatArray {
        val ring = FloatRingBuffer(input.size / factor + 1)
        process(input, input.size, ring)
        val out = FloatArray(ring.available)
        ring.copyLatest(out, out.size)
        return out
    }

    fun reset() {
        history.fill(0.0); pos = 0; phase = 0
    }

    fun magnitudeDb(freqHz: Double): Double = FirDesign.magnitudeDb(taps, freqHz, inputRate.toDouble())

    /** Group delay in input samples. */
    val delaySamples: Int get() = (n - 1) / 2
}

/**
 * Ring buffer holding the most recent decimated samples. Its capacity bounds how much audio is
 * ever held in memory for classification (privacy: ~1 s at 16 kHz, never written to disk).
 */
class FloatRingBuffer(val capacity: Int) {
    private val data = FloatArray(capacity)
    private var next = 0
    var available = 0
        private set

    fun write(v: Float) {
        data[next] = v
        next++
        if (next == capacity) next = 0
        if (available < capacity) available++
    }

    /** Copies the latest [count] samples (oldest first) into [dest]; false if not enough data. */
    fun copyLatest(dest: FloatArray, count: Int): Boolean {
        if (count > available || count > dest.size) return false
        var src = next - count
        if (src < 0) src += capacity
        for (i in 0 until count) {
            dest[i] = data[src]
            src++
            if (src == capacity) src = 0
        }
        return true
    }

    fun clear() {
        data.fill(0f); next = 0; available = 0
    }
}

internal fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
    var s = 0.0
    for (i in from until to) s += x[i].toDouble() * x[i]
    return sqrt(s / maxOf(1, to - from))
}

internal fun absMax(x: FloatArray): Float {
    var m = 0f
    for (v in x) if (abs(v) > m) m = abs(v)
    return m
}
