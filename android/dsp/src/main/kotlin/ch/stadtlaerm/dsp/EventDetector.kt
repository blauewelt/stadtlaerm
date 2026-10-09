package ch.stadtlaerm.dsp

import kotlin.math.log10
import kotlin.math.pow

/**
 * Trailing background estimate: L90 of the LAF samples over the last [windowSeconds].
 * Returns NaN until at least [minHistorySeconds] of samples are available.
 */
class BackgroundEstimator(
    val windowSeconds: Double = 300.0,
    val minHistorySeconds: Double = 30.0,
    val tickSeconds: Double = 0.125,
) {
    private val ring = DoubleRing((windowSeconds / tickSeconds).toInt())
    private val minSamples = (minHistorySeconds / tickSeconds).toInt()

    fun add(lafDb: Double) = ring.add(lafDb)

    fun l90(): Double {
        if (ring.size < minSamples) return Double.NaN
        return Percentiles.exceedanceLevel(ring.toArray(), 90.0)
    }

    fun clear() = ring.clear()
}

/** An event as delimited by the detector (timing in input samples, levels in dB). */
data class DetectedEvent(
    val startSample: Long,
    val endSample: Long,
    val durationSeconds: Double,
    val lafMaxDb: Double,
    /** Sound exposure level LAE (re 1 s). */
    val selDb: Double,
    /** 5-min background (L90) at the start, for continuity with v0.3 (not used for detection). */
    val backgroundDb: Double,
    /** The excess setting in force (dB above the local floor; before v0.4.0: above the background). */
    val thresholdDb: Double,
    /** Absolute floor the event's LAFmax had to reach (see [EventDetector.minLevelDb]). */
    val minLevelDb: Double = Double.NEGATIVE_INFINITY,
    /** Local floor (L90 of LAF over the trailing local window) at the start, frozen for the event. */
    val localFloorDb: Double = Double.NaN,
)

/**
 * Single-event detector working on 125 ms LAF ticks (detector v2, engine 0.4.0).
 *
 * - Starts when LAF ≥ local floor + [excessDb] (the local floor is frozen at the start).
 * - Continues while LAF ≥ floor + excess − [hysteresisDb].
 * - Kept only if it lasted ≥ [minDurationSeconds] **and** its LAFmax reached [minLevelDb] (an
 *   absolute floor, so that keystrokes 10 dB above a 20 dB background are not events);
 *   force-closed after [maxDurationSeconds]. A candidate that never reaches the floor is
 *   discarded like a too-short one. Both conditions only ever become true during an event (the
 *   duration and the running maximum grow), so confirming as soon as both hold keeps exactly the
 *   events that satisfy them at the end, while the engine can still count events at confirmation.
 * - SEL is integrated from the un-time-weighted per-tick LAeq: LAE = 10·log10(Σ 10^(Leq_i/10)·Δt / 1 s).
 */
class EventDetector(
    /** Start threshold above the local floor (dB). Read at event start. */
    var excessDb: Double = 6.5,
    val hysteresisDb: Double = 3.0,
    val minDurationSeconds: Double = 0.5,
    val maxDurationSeconds: Double = 300.0,
    /** Absolute floor for LAFmax in dB; −∞ disables it. Read at event start. */
    var minLevelDb: Double = Double.NEGATIVE_INFINITY,
    val tickSamples: Int = Acoustics.SAMPLE_RATE / 8,
    val sampleRate: Int = Acoustics.SAMPLE_RATE,
    private val listener: Listener,
) {
    interface Listener {
        /** LAF crossed the start threshold (event not yet confirmed). */
        fun onCandidateStart(startSample: Long) {}
        /** Candidate reached the minimum duration and the level floor: it will be reported as an event. */
        fun onConfirmed(startSample: Long) {}
        /** Candidate ended before reaching the minimum duration or the level floor. */
        fun onDiscarded(startSample: Long) {}
        fun onClosed(event: DetectedEvent)
    }

    private val tickSeconds = tickSamples.toDouble() / sampleRate
    private val minTicks = kotlin.math.ceil(minDurationSeconds / tickSeconds - 1e-9).toInt()
    private val maxTicks = (maxDurationSeconds / tickSeconds).toInt()

    /** Current local floor (dB); NaN disables detection. */
    var floorDb: Double = Double.NaN

    /** Current 5-min background (dB), recorded with the event for continuity; NaN → the floor is recorded. */
    var backgroundDb: Double = Double.NaN

    private var active = false
    private var startSample = 0L
    private var floorAtStart = 0.0
    private var bgAtStart = 0.0
    private var excessAtStart = 0.0
    private var minLevelAtStart = Double.NEGATIVE_INFINITY
    private var ticks = 0
    private var lafMax = Double.NEGATIVE_INFINITY
    private var energy = 0.0
    private var confirmed = false

    val isActive: Boolean get() = active
    val activeStartSample: Long? get() = if (active) startSample else null
    /** A candidate that has not (yet) reached the minimum duration. */
    val isUnconfirmedCandidate: Boolean get() = active && !confirmed
    /** The local floor frozen for the running candidate (NaN if none). */
    val activeFloorDb: Double get() = if (active) floorAtStart else Double.NaN

    /**
     * Ends a running event at [endSample] because the following audio is invalid (e.g. the
     * microphone was silenced). Confirmed events are reported, unconfirmed candidates discarded.
     */
    fun interrupt(endSample: Long) {
        if (active) close(endSample)
    }

    /**
     * @param tickEndSample sample index at the end of this tick
     * @param lafDb LAF at the end of the tick
     * @param tickLafMaxDb maximum LAF within the tick
     * @param tickLeqDb LAeq over the tick
     */
    fun onTick(tickEndSample: Long, lafDb: Double, tickLafMaxDb: Double, tickLeqDb: Double) {
        if (!active) {
            val floor = floorDb
            if (floor.isNaN()) return
            if (lafDb >= floor + excessDb) {
                active = true
                startSample = tickEndSample - tickSamples
                floorAtStart = floor
                bgAtStart = if (backgroundDb.isNaN()) floor else backgroundDb
                excessAtStart = excessDb
                minLevelAtStart = minLevelDb
                ticks = 0
                lafMax = Double.NEGATIVE_INFINITY
                energy = 0.0
                confirmed = false
                listener.onCandidateStart(startSample)
                accumulate(tickLafMaxDb, tickLeqDb)
            }
            return
        }
        if (lafDb < floorAtStart + excessAtStart - hysteresisDb) {
            close(tickEndSample - tickSamples)
            return
        }
        accumulate(tickLafMaxDb, tickLeqDb)
        if (ticks >= maxTicks) close(tickEndSample)
    }

    private fun accumulate(tickLafMaxDb: Double, tickLeqDb: Double) {
        ticks++
        if (tickLafMaxDb > lafMax) lafMax = tickLafMaxDb
        energy += 10.0.pow(tickLeqDb / 10.0) * tickSeconds
        if (!confirmed && ticks >= minTicks && lafMax >= minLevelAtStart) {
            confirmed = true
            listener.onConfirmed(startSample)
        }
    }

    /** Closes a running event (e.g. when measurement stops). */
    fun flush(endSample: Long) {
        if (active) close(endSample)
    }

    private fun close(endSample: Long) {
        active = false
        if (!confirmed) {
            listener.onDiscarded(startSample)
            return
        }
        val duration = ticks * tickSeconds
        listener.onClosed(
            DetectedEvent(
                startSample = startSample,
                endSample = maxOf(endSample, startSample + ticks.toLong() * tickSamples),
                durationSeconds = duration,
                lafMaxDb = lafMax,
                selDb = 10.0 * log10(energy),
                backgroundDb = bgAtStart,
                thresholdDb = excessAtStart,
                minLevelDb = minLevelAtStart,
                localFloorDb = floorAtStart,
            )
        )
    }
}
