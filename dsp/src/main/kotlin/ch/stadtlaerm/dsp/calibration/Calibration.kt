package ch.stadtlaerm.dsp.calibration

import ch.stadtlaerm.dsp.AWeighting
import ch.stadtlaerm.dsp.Acoustics
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 FFT. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n == im.size && n > 0 && (n and (n - 1)) == 0) { "size must be a power of two" }
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val ncr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = ncr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/**
 * Tone analysis of one frame: dominant frequency (parabolic interpolation on the log power
 * spectrum, Hann window) and tonality = fraction of the power within ±4 bins of the peak.
 * A clean calibrator tone gives tonality close to 1; broadband noise gives ≪ 0.5.
 */
object ToneAnalyzer {
    data class Tone(val frequencyHz: Double, val tonality: Double)

    fun analyze(frame: DoubleArray, sampleRate: Int): Tone {
        val n = frame.size
        val re = DoubleArray(n) { frame[it] * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }
        val im = DoubleArray(n)
        Fft.transform(re, im)
        val half = n / 2
        val p = DoubleArray(half + 1) { re[it] * re[it] + im[it] * im[it] }
        val minBin = maxOf(3, (20.0 * n / sampleRate).toInt())
        var k = minBin
        for (i in minBin..half) if (p[i] > p[k]) k = i
        var total = 0.0
        for (i in minBin..half) total += p[i]
        var peak = 0.0
        for (i in maxOf(minBin, k - 4)..minOf(half, k + 4)) peak += p[i]
        var delta = 0.0
        if (k in (minBin + 1) until half && p[k - 1] > 0 && p[k + 1] > 0 && p[k] > 0) {
            val a = ln(p[k - 1]); val b = ln(p[k]); val c = ln(p[k + 1])
            val den = a - 2 * b + c
            if (den != 0.0) delta = 0.5 * (a - c) / den
        }
        val freq = (k + delta) * sampleRate / n
        return Tone(freq, if (total > 0) peak / total else 0.0)
    }
}

/**
 * Measures the raw (offset 0) A- and Z-weighted equivalent level of the microphone signal over a
 * fixed duration, plus the 1 s level series and (optionally) a tone analysis for calibrator use.
 * The first [settleSeconds] are discarded (filter settling, button tap).
 *
 * Privacy: holds at most one 8192-sample analysis frame (≈ 0.17 s) of raw audio in memory.
 */
class CalibrationMeasurement(
    val durationSeconds: Int,
    val analyzeTone: Boolean = false,
    val sampleRate: Int = Acoustics.SAMPLE_RATE,
    val settleSeconds: Double = 0.5,
) {
    private val aw = AWeighting(sampleRate)
    private val settleSamples = (settleSeconds * sampleRate).toLong()
    private val targetSamples = durationSeconds.toLong() * sampleRate
    private var seen = 0L
    private var measured = 0L
    private var sumA = 0.0
    private var sumZ = 0.0
    private var secSumA = 0.0
    private var secCount = 0
    private val secondLevels = ArrayList<Double>()

    private val frameSize = 8192
    private val frame = DoubleArray(frameSize)
    private var framePos = 0
    private val tones = ArrayList<ToneAnalyzer.Tone>()

    val isComplete: Boolean get() = measured >= targetSamples
    val progress: Double get() = measured.toDouble() / targetSamples

    /** Most recent completed 1 s raw A level (offset 0), or NaN. */
    val lastSecondRawDb: Double get() = secondLevels.lastOrNull() ?: Double.NaN

    fun process(block: FloatArray, count: Int = block.size) {
        for (i in 0 until count) {
            val x = block[i].toDouble()
            val a = aw.process(x)
            seen++
            if (seen <= settleSamples || isComplete) continue
            sumA += a * a; sumZ += x * x; measured++
            secSumA += a * a; secCount++
            if (secCount == sampleRate) {
                secondLevels.add(Acoustics.db(secSumA / secCount))
                secSumA = 0.0; secCount = 0
            }
            if (analyzeTone) {
                frame[framePos++] = x
                if (framePos == frameSize) {
                    tones.add(ToneAnalyzer.analyze(frame, sampleRate))
                    framePos = 0
                }
            }
        }
    }

    fun result(): CalibrationResult {
        val n = maxOf(1L, measured)
        val freqs = tones.map { it.frequencyHz }.sorted()
        return CalibrationResult(
            rawLaeqDb = Acoustics.db(sumA / n),
            rawLzeqDb = Acoustics.db(sumZ / n),
            secondLevelsRawDb = secondLevels.toDoubleArray(),
            stdDevDb = CalibrationMath.stdDev(secondLevels.toDoubleArray()),
            measuredSeconds = measured.toDouble() / sampleRate,
            toneFrequencyHz = if (freqs.isEmpty()) null else freqs[freqs.size / 2],
            tonality = if (tones.isEmpty()) null else tones.map { it.tonality }.average(),
        )
    }
}

data class CalibrationResult(
    /** A-weighted Leq with offset 0 (i.e. dB re full scale mean square). */
    val rawLaeqDb: Double,
    val rawLzeqDb: Double,
    val secondLevelsRawDb: DoubleArray,
    val stdDevDb: Double,
    val measuredSeconds: Double,
    val toneFrequencyHz: Double?,
    val tonality: Double?,
)

enum class CalibrationWarning {
    /** Std. deviation of 1 s levels > 2 dB: the noise was not steady enough. */
    UNSTEADY,
    /** Reference level < 50 dB(A): too close to the phone's noise floor. */
    TOO_QUIET,
    /** Calibrator: dominant frequency outside 1 kHz ± 5 %. */
    FREQUENCY_OFF,
    /** Calibrator: signal not tonal (coupling problem or background noise). */
    NOT_TONAL,
    /** Resulting offset deviates > 20 dB from the CDD default: probably a mistake. */
    IMPLAUSIBLE_OFFSET,
}

object CalibrationMath {
    const val MAX_STD_DEV_DB = 2.0
    const val MIN_REFERENCE_DB = 50.0
    const val CALIBRATOR_FREQ_HZ = 1000.0
    const val CALIBRATOR_FREQ_TOLERANCE = 0.05
    const val MIN_TONALITY = 0.8
    const val MAX_DEVIATION_FROM_DEFAULT_DB = 20.0

    /** Reference meter method: offset = reference reading − measured raw LAeq. */
    fun referenceOffset(referenceLaeqDb: Double, measuredRawLaeqDb: Double): Double =
        referenceLaeqDb - measuredRawLaeqDb

    /** Acoustic calibrator: offset = nominal level − measured raw LAeq (A = 0 dB at 1 kHz). */
    fun calibratorOffset(nominalDb: Double, measuredRawLaeqDb: Double): Double =
        nominalDb - measuredRawLaeqDb

    fun stdDev(values: DoubleArray): Double {
        if (values.size < 2) return 0.0
        val m = values.average()
        var s = 0.0
        for (v in values) s += (v - m) * (v - m)
        return sqrt(s / (values.size - 1))
    }

    fun referenceWarnings(result: CalibrationResult, referenceLaeqDb: Double?): List<CalibrationWarning> {
        val w = ArrayList<CalibrationWarning>()
        if (result.stdDevDb > MAX_STD_DEV_DB) w += CalibrationWarning.UNSTEADY
        if (referenceLaeqDb != null) {
            if (referenceLaeqDb < MIN_REFERENCE_DB) w += CalibrationWarning.TOO_QUIET
            if (implausible(referenceOffset(referenceLaeqDb, result.rawLaeqDb))) w += CalibrationWarning.IMPLAUSIBLE_OFFSET
        }
        return w
    }

    fun calibratorWarnings(result: CalibrationResult, nominalDb: Double): List<CalibrationWarning> {
        val w = ArrayList<CalibrationWarning>()
        val f = result.toneFrequencyHz
        if (f == null || abs(f - CALIBRATOR_FREQ_HZ) > CALIBRATOR_FREQ_HZ * CALIBRATOR_FREQ_TOLERANCE) {
            w += CalibrationWarning.FREQUENCY_OFF
        }
        if ((result.tonality ?: 0.0) < MIN_TONALITY) w += CalibrationWarning.NOT_TONAL
        if (implausible(calibratorOffset(nominalDb, result.rawLaeqDb))) w += CalibrationWarning.IMPLAUSIBLE_OFFSET
        return w
    }

    fun implausible(offsetDb: Double): Boolean =
        abs(offsetDb - Acoustics.DEFAULT_CALIBRATION_OFFSET_DB) > MAX_DEVIATION_FROM_DEFAULT_DB
}
