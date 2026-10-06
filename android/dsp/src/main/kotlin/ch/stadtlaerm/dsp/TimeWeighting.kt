package ch.stadtlaerm.dsp

import kotlin.math.exp

/**
 * Exponential time weighting applied to the squared (weighted) signal:
 * `y[n] = y[n−1] + α·(x²[n] − y[n−1])`, `α = 1 − exp(−1 / (τ·fs))`.
 * The step response reaches 1 − 1/e after τ. Fast: τ = 125 ms; Slow: τ = 1 s.
 */
class ExponentialTimeWeighting(val tauSeconds: Double, val sampleRate: Int = Acoustics.SAMPLE_RATE) {
    companion object {
        const val FAST_TAU = 0.125
        const val SLOW_TAU = 1.0
    }

    private val alpha = 1.0 - exp(-1.0 / (tauSeconds * sampleRate))

    /** Current time-weighted mean square. */
    var value: Double = 0.0
        private set

    /** Feeds one already-squared sample and returns the new time-weighted mean square. */
    fun process(squared: Double): Double {
        value += alpha * (squared - value)
        return value
    }

    fun seed(meanSquare: Double) {
        value = meanSquare
    }

    fun reset() {
        value = 0.0
    }
}
