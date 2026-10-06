package ch.stadtlaerm.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Second-order IIR section, transposed direct form II, double precision state. */
class Biquad(
    val b0: Double, val b1: Double, val b2: Double,
    val a1: Double, val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0; z2 = 0.0
    }

    /** Complex response at normalised angular frequency w (rad/sample), returned as (re, im). */
    fun response(w: Double): Pair<Double, Double> {
        val c1 = cos(w); val s1 = -sin(w)
        val c2 = cos(2 * w); val s2 = -sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = b1 * s1 + b2 * s2
        val dr = 1.0 + a1 * c1 + a2 * c2
        val di = a1 * s1 + a2 * s2
        val den = dr * dr + di * di
        return Pair((nr * dr + ni * di) / den, (ni * dr - nr * di) / den)
    }
}

/** A frequency weighting filter operating sample by sample. */
interface FrequencyWeighting {
    val sampleRate: Int
    fun process(x: Double): Double
    fun reset()
    /** Magnitude response of the digital filter in dB at [freqHz]. */
    fun magnitudeDb(freqHz: Double): Double
}

/** Z-weighting: flat (identity), used for calibration diagnostics. */
class ZWeighting(override val sampleRate: Int = Acoustics.SAMPLE_RATE) : FrequencyWeighting {
    override fun process(x: Double): Double = x
    override fun reset() {}
    override fun magnitudeDb(freqHz: Double): Double = 0.0
}

/**
 * A-weighting per IEC 61672-1.
 *
 * Analog prototype: four zeros at s = 0, poles at 20.598997 Hz (double), 107.65265 Hz,
 * 737.86223 Hz and 12194.217 Hz (double). Digitised with the bilinear transform into three
 * biquads (the two "zeros at infinity" map to z = −1) and normalised to exactly 0 dB at 1 kHz.
 *
 * The bilinear transform compresses the frequency axis near Nyquist, which pulls the response
 * down by ≈ 1.2 dB at 10 kHz when fs = 48 kHz. To compensate, the high pole pair is placed at an
 * effective analog frequency chosen by a one-dimensional search (done once, at construction)
 * that minimises the maximum deviation from the IEC analog response over the 1/3-octave
 * centre frequencies 20 Hz…10 kHz (only those below 0.42·fs). At 48 kHz this yields ≈ 13.86 kHz
 * and keeps the error within ±0.23 dB from 20 Hz to 10 kHz (−1.3 dB at 12.5 kHz, −4.6 dB at
 * 16 kHz — still inside the IEC 61672-1 class 1 tolerance there). The same code works at other
 * sample rates (e.g. a future ESP32 sensor at 32 kHz).
 */
class AWeighting(override val sampleRate: Int = Acoustics.SAMPLE_RATE) : FrequencyWeighting {

    companion object {
        const val F1 = 20.598997
        const val F2 = 107.65265
        const val F3 = 737.86223
        const val F4 = 12194.217

        /** IEC 61672-1 analog A-weighting in dB, normalised to 0 dB at 1 kHz. */
        fun analogDb(f: Double): Double = rawAnalogDb(f) - rawAnalogDb(1000.0)

        private fun rawAnalogDb(f: Double): Double {
            val f2 = f * f
            val ra = (F4 * F4 * f2 * f2) /
                ((f2 + F1 * F1) * sqrt((f2 + F2 * F2) * (f2 + F3 * F3)) * (f2 + F4 * F4))
            return 20.0 * log10(ra)
        }

        private val THIRD_OCTAVES = doubleArrayOf(
            20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0,
            400.0, 500.0, 630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0,
            5000.0, 6300.0, 8000.0, 10000.0,
        )

        private fun bilinearPole(fHz: Double, fs: Double): Double {
            val w = 2.0 * PI * fHz
            return (2.0 * fs - w) / (2.0 * fs + w)
        }

        private fun buildSections(fs: Double, f4Effective: Double): Array<Biquad> {
            val p1 = bilinearPole(F1, fs)
            val p2 = bilinearPole(F2, fs)
            val p3 = bilinearPole(F3, fs)
            val p4 = bilinearPole(f4Effective, fs)
            return arrayOf(
                // Double pole at F1 with two zeros at z = 1.
                Biquad(1.0, -2.0, 1.0, -2.0 * p1, p1 * p1),
                // Poles at F2 and F3 with two zeros at z = 1.
                Biquad(1.0, -2.0, 1.0, -(p2 + p3), p2 * p3),
                // Double pole at F4 with two zeros at z = −1 (bilinear image of s = ∞).
                Biquad(1.0, 2.0, 1.0, -2.0 * p4, p4 * p4),
            )
        }

        private fun cascadeMagnitude(sections: Array<Biquad>, f: Double, fs: Double): Double {
            val w = 2.0 * PI * f / fs
            var mag = 1.0
            for (s in sections) {
                val (re, im) = s.response(w)
                mag *= hypot(re, im)
            }
            return mag
        }

        private fun maxError(fs: Double, f4e: Double): Double {
            val sec = buildSections(fs, f4e)
            val g = 1.0 / cascadeMagnitude(sec, 1000.0, fs)
            var worst = 0.0
            for (f in THIRD_OCTAVES) {
                if (f > 0.42 * fs) continue
                val digital = 20.0 * log10(g * cascadeMagnitude(sec, f, fs))
                worst = maxOf(worst, abs(digital - analogDb(f)))
            }
            return worst
        }

        /** Effective analog frequency for the high pole pair (see class doc). */
        fun effectiveHighPole(fs: Double): Double {
            var best = F4
            var bestErr = maxError(fs, F4)
            var f = F4
            val upper = F4 * 1.6
            while (f <= upper) {
                val e = maxError(fs, f)
                if (e < bestErr) { bestErr = e; best = f }
                f += 5.0
            }
            return best
        }
    }

    val highPoleEffectiveHz: Double = effectiveHighPole(sampleRate.toDouble())
    private val sections: Array<Biquad> = buildSections(sampleRate.toDouble(), highPoleEffectiveHz)
    private val gain: Double = 1.0 / cascadeMagnitude(sections, 1000.0, sampleRate.toDouble())

    // Hot path: unrolled access to the three sections.
    private val s0 = sections[0]
    private val s1 = sections[1]
    private val s2 = sections[2]

    override fun process(x: Double): Double = s2.process(s1.process(s0.process(x * gain)))

    override fun reset() {
        sections.forEach { it.reset() }
    }

    override fun magnitudeDb(freqHz: Double): Double =
        20.0 * log10(gain * cascadeMagnitude(sections, freqHz, sampleRate.toDouble()))
}

/** Convenience: dB of an amplitude ratio. */
internal fun ampDb(x: Double) = 20.0 * log10(x)
internal fun Double.dbToPower() = 10.0.pow(this / 10.0)
