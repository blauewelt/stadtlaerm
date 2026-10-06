package ch.stadtlaerm.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import ch.stadtlaerm.app.audio.AudioCapture
import ch.stadtlaerm.app.audio.AudioSourceSelector
import ch.stadtlaerm.app.container
import ch.stadtlaerm.app.data.CalibrationEntity
import ch.stadtlaerm.dsp.Acoustics
import ch.stadtlaerm.dsp.calibration.CalibrationMath
import ch.stadtlaerm.dsp.calibration.CalibrationResult
import ch.stadtlaerm.dsp.calibration.CalibrationWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class CalUiState(
    val running: CalMode? = null,
    val progress: Float = 0f,
    val remainingSeconds: Int = 0,
    /** Last 1 s level with the currently active offset (live feedback). */
    val currentDb: Double? = null,
    val referenceResult: CalibrationResult? = null,
    val referenceWindowSeconds: Int = 30,
    val calibratorResult: CalibrationResult? = null,
    val calibratorNominalDb: Double = 94.0,
    val noiseFloorDb: Double? = null,
    val message: String? = null,
)

class CalibrationViewModel(app: Application) : AndroidViewModel(app) {
    private val c = app.container
    val source: String = AudioSourceSelector.select(app)
    val deviceModel: String = ch.stadtlaerm.app.data.CalibrationRepository.deviceModel

    val active: StateFlow<CalibrationEntity?> =
        c.calibrations.activeFlow(source).stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val history: StateFlow<List<CalibrationEntity>> =
        c.calibrations.history().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _state = MutableStateFlow(CalUiState())
    val state: StateFlow<CalUiState> = _state

    private var capture: AudioCapture? = null
    private var run: CalibrationRun? = null

    private val activeOffset: Double get() = active.value?.offsetDb ?: Acoustics.DEFAULT_CALIBRATION_OFFSET_DB

    fun setReferenceWindow(seconds: Int) { _state.value = _state.value.copy(referenceWindowSeconds = seconds) }
    fun setCalibratorNominal(db: Double) { _state.value = _state.value.copy(calibratorNominalDb = db) }

    fun start(mode: CalMode) {
        if (_state.value.running != null) return
        if (c.live.value.running || c.live.value.starting) {
            _state.value = _state.value.copy(message = "Bitte zuerst die laufende Messung stoppen.")
            return
        }
        val seconds = when (mode) {
            CalMode.REFERENCE -> _state.value.referenceWindowSeconds
            CalMode.CALIBRATOR -> 10
            CalMode.NOISE_FLOOR -> 20
        }
        val r = CalibrationRun(mode, seconds)
        val m = r.measurement
        run = r
        // A new run replaces the previous result of the same method: it cannot be saved anymore.
        _state.value = when (mode) {
            CalMode.REFERENCE -> _state.value.copy(referenceResult = null)
            CalMode.CALIBRATOR -> _state.value.copy(calibratorResult = null)
            CalMode.NOISE_FLOOR -> _state.value.copy(noiseFloorDb = null)
        }.copy(running = mode, progress = 0f, remainingSeconds = seconds, currentDb = null, message = null)
        var lastPosted = -1
        val cap = AudioCapture(getApplication(), onBlock = { buf, n ->
            // Capture thread: feed the measurement, post progress about every second.
            r.onBlock(buf, n)
            val elapsed = (m.progress * seconds).toInt()
            if (elapsed != lastPosted || m.isComplete) {
                lastPosted = elapsed
                val raw = m.lastSecondRawDb
                _state.update {
                    it.copy(
                        progress = m.progress.toFloat().coerceIn(0f, 1f),
                        remainingSeconds = (seconds - elapsed).coerceAtLeast(0),
                        currentDb = if (raw.isNaN()) null else raw + activeOffset,
                    )
                }
            }
            if (r.shouldStop) viewModelScope.launch { finish(mode) }
        }, onError = { msg -> viewModelScope.launch { abort("Aufnahmefehler: $msg") } },
            onSilenced = { silenced ->
                r.onMicSilenced(silenced)
                if (r.shouldStop) viewModelScope.launch { finish(mode) }
            })
        try {
            c.calibrationActive.value = true
            cap.start(source)
            capture = cap
        } catch (e: Exception) {
            c.calibrationActive.value = false
            _state.value = _state.value.copy(running = null, message = "Mikrofon konnte nicht geöffnet werden: ${e.message}")
        }
    }

    private suspend fun stopCapture() {
        val cap = capture ?: return
        capture = null
        withContext(Dispatchers.Default) { cap.stop() }
        c.calibrationActive.value = false
    }

    private var finishing = false

    private suspend fun finish(mode: CalMode) {
        // The capture thread may request finish() several times; handle it once.
        if (_state.value.running != mode || finishing) return
        finishing = true
        try { stopCapture() } finally { finishing = false }
        val current = run ?: return
        run = null
        when (val outcome = current.outcome()) {
            is CalibrationRun.Outcome.Aborted ->
                _state.value = _state.value.copy(running = null, progress = 0f, message = outcome.reason)
            is CalibrationRun.Outcome.Done -> {
                val r = outcome.result
                _state.value = when (mode) {
                    CalMode.REFERENCE -> _state.value.copy(running = null, referenceResult = r)
                    CalMode.CALIBRATOR -> _state.value.copy(running = null, calibratorResult = r)
                    CalMode.NOISE_FLOOR -> _state.value.copy(running = null, noiseFloorDb = r.rawLaeqDb + activeOffset)
                }
            }
        }
    }

    /** Lifecycle ON_STOP of the screen: a calibration must not continue in the background. */
    fun onAppStopped() {
        val r = run ?: return
        r.onAppStopped()
        viewModelScope.launch { finish(r.mode) }
    }

    fun cancel() = viewModelScope.launch { abort(null) }

    private suspend fun abort(msg: String?) {
        stopCapture()
        run = null
        _state.value = _state.value.copy(running = null, progress = 0f, message = msg)
    }

    fun referenceWarnings(referenceDb: Double?): List<CalibrationWarning> =
        _state.value.referenceResult?.let { CalibrationMath.referenceWarnings(it, referenceDb) } ?: emptyList()

    fun calibratorWarnings(): List<CalibrationWarning> =
        _state.value.calibratorResult?.let { CalibrationMath.calibratorWarnings(it, _state.value.calibratorNominalDb) } ?: emptyList()

    fun saveReference(referenceDb: Double, notes: String) = viewModelScope.launch {
        val r = _state.value.referenceResult ?: return@launch
        val offset = CalibrationMath.referenceOffset(referenceDb, r.rawLaeqDb)
        val id = c.calibrations.save(
            source, offset, CalibrationEntity.METHOD_REFERENCE, notes,
            referenceDb = referenceDb, measuredRawDb = r.rawLaeqDb, stdDevDb = r.stdDevDb,
            durationSeconds = r.measuredSeconds, warnings = referenceWarnings(referenceDb).map { it.name },
        )
        _state.value = _state.value.copy(referenceResult = null, message = "Kalibrierung #$id gespeichert: Offset ${Fmt.db(offset, 2)} dB")
    }

    fun saveCalibrator(notes: String) = viewModelScope.launch {
        val r = _state.value.calibratorResult ?: return@launch
        val nominal = _state.value.calibratorNominalDb
        val offset = CalibrationMath.calibratorOffset(nominal, r.rawLaeqDb)
        val id = c.calibrations.save(
            source, offset, CalibrationEntity.METHOD_CALIBRATOR, notes,
            measuredRawDb = r.rawLaeqDb, stdDevDb = r.stdDevDb, durationSeconds = r.measuredSeconds,
            calibratorNominalDb = nominal, toneFrequencyHz = r.toneFrequencyHz, tonality = r.tonality,
            warnings = calibratorWarnings().map { it.name },
        )
        _state.value = _state.value.copy(calibratorResult = null, message = "Kalibrierung #$id gespeichert: Offset ${Fmt.db(offset, 2)} dB")
    }

    fun saveManual(offsetDb: Double, notes: String) = viewModelScope.launch {
        val id = c.calibrations.save(
            source, offsetDb, CalibrationEntity.METHOD_MANUAL, notes,
            warnings = if (CalibrationMath.implausible(offsetDb)) listOf(CalibrationWarning.IMPLAUSIBLE_OFFSET.name) else emptyList(),
        )
        _state.value = _state.value.copy(message = "Kalibrierung #$id gespeichert: Offset ${Fmt.db(offsetDb, 2)} dB")
    }

    fun resetToDefault() = viewModelScope.launch {
        c.calibrations.save(source, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, CalibrationEntity.METHOD_RESET, "Zurückgesetzt auf CDD-Standard")
        _state.value = _state.value.copy(message = "Auf Standard-Offset zurückgesetzt (unkalibriert).")
    }

    suspend fun exportHistory(): File = withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().cacheDir, "exports").apply { mkdirs() }
        File(dir, "stadtlaerm-kalibrierungen.json").apply { writeText(c.calibrations.exportJson()) }
    }

    fun clearMessage() { _state.value = _state.value.copy(message = null) }

    override fun onCleared() {
        capture?.stop()
        capture = null
        c.calibrationActive.value = false
    }
}
