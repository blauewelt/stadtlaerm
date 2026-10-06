package ch.stadtlaerm.app.ui

import ch.stadtlaerm.dsp.calibration.CalibrationMeasurement
import ch.stadtlaerm.dsp.calibration.CalibrationResult

enum class CalMode { REFERENCE, CALIBRATOR, NOISE_FLOOR }

/**
 * One calibration measurement and the rules that keep it trustworthy (no Android types, so it is
 * unit-tested on the JVM). A run is aborted — and its result can never be saved — when the app
 * goes to the background, when Android silences the microphone, or when digital silence shows up.
 */
class CalibrationRun(val mode: CalMode, val seconds: Int) {
    companion object {
        const val REASON_BACKGROUND =
            "Kalibrierung abgebrochen: Die App war im Hintergrund. Bitte mit eingeschaltetem Bildschirm neu messen."
        const val REASON_SILENCED =
            "Kalibrierung abgebrochen: Das Mikrofon wurde vom System stummgeschaltet (Anruf, Sprachassistent). Bitte neu messen."
        const val REASON_INCOMPLETE = "Kalibrierung abgebrochen."
    }

    sealed interface Outcome {
        data class Done(val result: CalibrationResult) : Outcome
        data class Aborted(val reason: String) : Outcome
    }

    val measurement = CalibrationMeasurement(seconds, analyzeTone = mode == CalMode.CALIBRATOR)

    @Volatile var abortReason: String? = null
        private set

    /** Capture thread. */
    fun onBlock(samples: FloatArray, count: Int) {
        if (abortReason != null) return
        measurement.process(samples, count)
        if (measurement.invalidated) abort(REASON_SILENCED)
    }

    fun onMicSilenced(silenced: Boolean) {
        if (silenced) abort(REASON_SILENCED)
    }

    /** Lifecycle ON_STOP: the app is no longer visible. */
    fun onAppStopped() = abort(REASON_BACKGROUND)

    @Synchronized
    fun abort(reason: String) {
        if (abortReason == null) {
            abortReason = reason
            measurement.invalidate()
        }
    }

    val shouldStop: Boolean get() = abortReason != null || measurement.isComplete

    fun outcome(): Outcome {
        abortReason?.let { return Outcome.Aborted(it) }
        if (!measurement.isComplete || measurement.invalidated) return Outcome.Aborted(REASON_INCOMPLETE)
        return Outcome.Done(measurement.result())
    }
}
