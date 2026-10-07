package ch.stadtlaerm.dsp.classify

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * A sound-source classifier operating on a mono waveform window. Implementations: YAMNet via
 * LiteRT (app module). Planned: EfficientAT MobileNet, later a fine-tuned head on urban data.
 */
interface SoundClassifier : AutoCloseable {
    /** Raw output label names, index-aligned with [classify] scores. */
    val labels: List<String>
    /** Expected input sample rate (Hz). */
    val inputSampleRate: Int
    /** Expected number of samples per window. */
    val inputLength: Int
    /** Returns one score per label for a window of exactly [inputLength] float samples in [-1, 1]. */
    fun classify(window: FloatArray): FloatArray
}

/**
 * Classifier output tied to the measurement timeline (end of window in 48 kHz input samples).
 * [inputGainDb]: the gain [ClassifierPreprocessor.normalize] applied to this window (0 if off).
 */
class ClassifierFrame(
    val endSample: Long,
    val windowSamples48k: Long,
    val scores: FloatArray,
    val inputGainDb: Double = 0.0,
)

/**
 * One classifier run as seen by the engine, for diagnostics (numbers and label names only).
 *
 * @param top the 5 best AudioSet labels of this window
 * @param decision the category decision for this window; null if the window was ignored
 *   because it overlapped silenced/invalid audio ([ignoredInvalid])
 * @param lafDb LAF (dB(A), with the calibration offset) of the last 125 ms tick ending at or
 *   before the window end; NaN if unknown
 * @param lafMaxDb highest tick LAFmax within the window; NaN if unknown
 */
data class ClassifierResult(
    val endSample: Long,
    val windowSamples48k: Long,
    val endEpochMs: Long,
    val top: List<LabelScore>,
    val decision: CategoryDecision?,
    val inputGainDb: Double,
    val lafDb: Double,
    val lafMaxDb: Double,
    val ignoredInvalid: Boolean,
) {
    val startSample: Long get() = endSample - windowSamples48k
}

/**
 * Input conditioning for the classifier.
 *
 * YAMNet was trained on YouTube audio, which is far louder than what a phone microphone with
 * CDD sensitivity delivers at night (a 45 dB(A) street is ≈ −65 dBFS). Measured with the real
 * model, speech at −60 dBFS is already partly reported as "Silence" and at −70 dBFS almost
 * entirely. Therefore the window is (optionally) amplified so its RMS reaches [targetRmsDbfs],
 * with the gain capped at [maxGainDb] and never below 0 dB, and finally clipped to [-1, 1].
 * This only affects classification; levels are always computed from the unscaled signal.
 */
object ClassifierPreprocessor {
    const val YAMNET_SAMPLE_RATE = 16_000
    const val YAMNET_INPUT_SAMPLES = 15_600 // 0.975 s

    /** Applies the gain in place and returns the gain in dB that was applied. */
    fun normalize(window: FloatArray, targetRmsDbfs: Double = -30.0, maxGainDb: Double = 40.0): Double {
        var s = 0.0
        for (v in window) s += v.toDouble() * v
        val r = sqrt(s / window.size)
        if (r <= 0.0) return 0.0
        val currentDb = 20 * log10(r)
        val gainDb = (targetRmsDbfs - currentDb).coerceIn(0.0, maxGainDb)
        if (gainDb == 0.0) return 0.0
        val g = Math.pow(10.0, gainDb / 20.0).toFloat()
        for (i in window.indices) window[i] = (window[i] * g).coerceIn(-1f, 1f)
        return gainDb
    }
}
