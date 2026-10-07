package ch.stadtlaerm.app.audio

import ch.stadtlaerm.dsp.NoiseEvent
import ch.stadtlaerm.dsp.classify.ClassifierResult

/**
 * A hook through which a diagnostics build can see the audio and the event lifecycle.
 *
 * PRIVACY: the public build uses [NoAudioTap], which does nothing. The only implementation that
 * writes audio is the recorder of the «Labor» build, and it lives exclusively in the `labor`
 * flavour source set (`app/src/labor/`), so it is not compiled into the public APK at all.
 * See PRIVACY.md → «Labor-Build».
 *
 * Sample indices count input samples (48 kHz) since the start of the measurement, exactly like
 * [ch.stadtlaerm.dsp.MeasurementEngine.totalSamples]. All calls except [onSessionStart],
 * [onEventStored] and [onSessionStop] come from the capture thread and must return immediately.
 */
interface AudioTap {
    fun onSessionStart(session: AudioTapSession)
    /** Every capture block, after the engine has processed it. [samples] is reused afterwards. */
    fun onBlock(samples: FloatArray, count: Int, sampleRate: Int, wallClockMs: Long)
    /** The engine's sample clock: epochMs(s) = [anchorEpochMs] + (s − [anchorSample])·1000/fs. */
    fun onClockAnchor(anchorSample: Long, anchorEpochMs: Long)
    fun onClockCorrection(correctionMs: Long)
    /** Event candidate (not yet confirmed) starting at [startSample]. */
    fun onEventStarted(startSample: Long)
    fun onEventConfirmed(startSample: Long)
    fun onEventDiscarded(startSample: Long)
    fun onEventEnded(startSample: Long, endSample: Long)
    /**
     * One classifier run (top-5 labels and scores, category decision, input gain, LAF), at
     * [wallClockMs] = the engine's time of the window end. Numbers and label names only.
     */
    fun onClassifierResult(wallClockMs: Long, result: ClassifierResult)
    /** The event was stored in the database with row id [eventId]. */
    fun onEventStored(eventId: Long, event: NoiseEvent, startSample: Long, endSample: Long)
    fun onSessionStop()

    /** Short status appended to the measurement notification (Labor: «Aufnahme läuft»), or null. */
    val notificationNote: String? get() = null
}

/** Measurement chain of a session (for the diagnostics manifest). */
data class AudioTapSession(
    val startedAtMs: Long,
    val audioSource: String,
    val encoding: String,
    val effects: List<String>,
    val calibrationId: Long?,
    val calibrationOffsetDb: Double,
    val calibrated: Boolean,
    /** Event detection: threshold above the background and the absolute floor (dB). */
    val eventThresholdDb: Double,
    val eventMinLevelDb: Double,
    val classifierEnabled: Boolean,
    val classifierNormalize: Boolean,
    val classifierIntervalSeconds: Double,
)

/** The public build's tap: does nothing, holds nothing, writes nothing. */
object NoAudioTap : AudioTap {
    override fun onSessionStart(session: AudioTapSession) {}
    override fun onBlock(samples: FloatArray, count: Int, sampleRate: Int, wallClockMs: Long) {}
    override fun onClockAnchor(anchorSample: Long, anchorEpochMs: Long) {}
    override fun onClockCorrection(correctionMs: Long) {}
    override fun onEventStarted(startSample: Long) {}
    override fun onEventConfirmed(startSample: Long) {}
    override fun onEventDiscarded(startSample: Long) {}
    override fun onEventEnded(startSample: Long, endSample: Long) {}
    override fun onClassifierResult(wallClockMs: Long, result: ClassifierResult) {}
    override fun onEventStored(eventId: Long, event: NoiseEvent, startSample: Long, endSample: Long) {}
    override fun onSessionStop() {}
}
