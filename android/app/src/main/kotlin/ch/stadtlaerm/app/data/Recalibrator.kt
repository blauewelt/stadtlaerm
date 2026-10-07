package ch.stadtlaerm.app.data

import androidx.room.withTransaction
import ch.stadtlaerm.app.data.Mappers.toEntity
import ch.stadtlaerm.app.data.Mappers.toEvent
import ch.stadtlaerm.app.data.Mappers.toRecord
import ch.stadtlaerm.dsp.Acoustics
import ch.stadtlaerm.dsp.calibration.Recalibration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** Entity-level re-evaluation: the arithmetic is [Recalibration] (pure Kotlin, dsp module). */
object RecalibrationMapping {
    fun minute(e: MinuteEntity, target: Recalibration.Target): MinuteEntity =
        Recalibration.recalibrate(e.toRecord(), target).toEntity().copy(id = e.id)

    /**
     * Events store no offset; it is that of their calibration ([offsets] by id), or the default for
     * events measured without one. Calibrations are never deleted, so an unknown id cannot occur
     * in practice; it would be treated like the default.
     */
    fun event(e: EventEntity, offsets: Map<Long, Double>, target: Recalibration.Target): EventEntity {
        val current = e.calibrationId?.let { offsets[it] } ?: Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
        return Recalibration.recalibrate(e.toEvent(), current, target).toEntity().copy(id = e.id)
    }
}

/**
 * Re-evaluates stored minutes and events with a newer calibration («Alte Messungen neu bewerten»)
 * in one database transaction (all or nothing), in batches, reporting progress. Runs in the app's
 * scope so that leaving the screen does not cancel it.
 */
class Recalibrator(private val db: AppDatabase, private val scope: CoroutineScope) {
    sealed interface State {
        data object Idle : State
        data class Running(val done: Int, val total: Int) : State {
            val fraction: Float get() = if (total > 0) done.toFloat() / total else 0f
        }
        data class Done(val minutes: Int, val events: Int, val target: Recalibration.Target) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    val running: Boolean get() = _state.value is State.Running

    /** (minutes, events) a re-evaluation with [target] would change. */
    fun counts(target: Recalibration.Target): Flow<Pair<Int, Int>> {
        val dao = db.measurements()
        return combine(
            dao.minutesToRecalibrate(target.audioSource, target.calibrationId),
            dao.eventsToRecalibrate(target.audioSource, target.calibrationId),
        ) { m, e -> m to e }
    }

    /** Starts a re-evaluation in the background; false if one is already running. */
    fun start(target: Recalibration.Target): Boolean {
        synchronized(this) {
            if (running) return false
            _state.value = State.Running(0, 0)
        }
        scope.launch {
            _state.value = try {
                run(target)
            } catch (e: Exception) {
                State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
        return true
    }

    fun acknowledge() {
        if (!running) _state.value = State.Idle
    }

    private suspend fun run(target: Recalibration.Target): State {
        val dao = db.measurements()
        return db.withTransaction {
            val minutes = dao.minutesInScope(target.audioSource, target.calibrationId)
            val events = dao.eventsInScope(target.audioSource, target.calibrationId)
            val offsets = db.calibrations().all().associate { it.id to it.offsetDb }
            val total = minutes.size + events.size
            var done = 0
            _state.value = State.Running(0, total)
            for (batch in minutes.chunked(BATCH)) {
                dao.updateMinutes(batch.map { RecalibrationMapping.minute(it, target) })
                done += batch.size
                _state.value = State.Running(done, total)
            }
            for (batch in events.chunked(BATCH)) {
                dao.updateEvents(batch.map { RecalibrationMapping.event(it, offsets, target) })
                done += batch.size
                _state.value = State.Running(done, total)
            }
            State.Done(minutes.size, events.size, target)
        }
    }

    companion object {
        const val BATCH = 500
    }
}
