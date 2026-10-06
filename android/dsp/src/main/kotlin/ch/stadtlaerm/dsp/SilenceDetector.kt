package ch.stadtlaerm.dsp

/**
 * Detects audio that cannot come from a working microphone.
 *
 * On Android 10+ the system silences an app's capture (delivers exact zeros) while a phone call or
 * a voice assistant owns the microphone. Real microphone signals always contain analog and
 * quantisation noise, so a run of ≥ [ZERO_RUN_SAMPLES] exactly-zero samples (10 ms at 48 kHz) or a
 * block mean square below [FLOOR_DBFS] (far below the noise of any microphone and below 16-bit
 * quantisation noise at ≈ −101 dBFS) marks the block as invalid.
 */
object SilenceDetector {
    const val ZERO_RUN_SAMPLES = 480
    const val FLOOR_DBFS = -130.0
    val FLOOR_MEAN_SQUARE: Double = Math.pow(10.0, FLOOR_DBFS / 10.0)

    /** Stateless check of a single block. */
    fun isDigitalSilence(block: FloatArray, count: Int = block.size): Boolean {
        if (count == 0) return false
        var run = 0
        var maxRun = 0
        var sum = 0.0
        for (i in 0 until count) {
            val x = block[i]
            if (x == 0f) { run++; if (run > maxRun) maxRun = run } else run = 0
            sum += x.toDouble() * x
        }
        return maxRun >= minOf(ZERO_RUN_SAMPLES, count) || sum / count < FLOOR_MEAN_SQUARE
    }
}
