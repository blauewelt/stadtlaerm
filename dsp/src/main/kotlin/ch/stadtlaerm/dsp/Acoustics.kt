package ch.stadtlaerm.dsp

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

/**
 * Shared constants and level helpers.
 *
 * Levels are computed as `10·log10(meanSquare) + calibrationOffsetDb`, where `meanSquare` is
 * the mean square of the (weighted) signal in digital full-scale units (float samples in [-1, 1]).
 */
object Acoustics {
    const val SAMPLE_RATE = 48_000

    /** Android CDD sensitivity guideline: 90 dB SPL at 1 kHz should yield RMS 2500 of 32768. */
    const val CDD_REFERENCE_SPL_DB = 90.0
    const val CDD_REFERENCE_RMS = 2500.0 / 32768.0

    /**
     * Default (uncalibrated) offset ≈ 112.35 dB: 90 dB SPL − 20·log10(2500/32768).
     * Data measured with this offset is flagged as "uncalibrated".
     */
    val DEFAULT_CALIBRATION_OFFSET_DB: Double = CDD_REFERENCE_SPL_DB - 20.0 * log10(CDD_REFERENCE_RMS)

    /** Floor used to avoid log(0); corresponds to roughly −200 dBFS. */
    private const val MIN_MEAN_SQUARE = 1e-20

    fun db(meanSquare: Double, offsetDb: Double = 0.0): Double =
        10.0 * log10(max(meanSquare, MIN_MEAN_SQUARE)) + offsetDb

    fun meanSquareFromDb(levelDb: Double, offsetDb: Double = 0.0): Double =
        10.0.pow((levelDb - offsetDb) / 10.0)

    /** Energetic (power) average of levels weighted by durations. */
    fun energyAverage(levelsDb: DoubleArray, weights: DoubleArray? = null): Double {
        require(weights == null || weights.size == levelsDb.size)
        if (levelsDb.isEmpty()) return Double.NaN
        var sum = 0.0
        var wsum = 0.0
        for (i in levelsDb.indices) {
            val w = weights?.get(i) ?: 1.0
            sum += w * 10.0.pow(levelsDb[i] / 10.0)
            wsum += w
        }
        return if (wsum > 0) 10.0 * log10(sum / wsum) else Double.NaN
    }
}
