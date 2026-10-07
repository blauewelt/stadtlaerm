package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.classify.LabelScore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** One 125 ms LAF sample. */
data class LafTick(
    val endSample: Long,
    val epochMs: Long,
    val lafDb: Double,
    val lafMaxDb: Double,
    val leqDb: Double,
    /** False while the microphone is silenced (or recovering from it); such ticks are not evaluated. */
    val valid: Boolean = true,
)

/** Per-second result. */
data class SecondResult(
    val endSample: Long,
    val epochMs: Long,
    val laeqDb: Double,
    val lafMaxDb: Double,
    /** Unweighted (Z) Leq over the second, for diagnostics. */
    val lzeqDb: Double,
    /** Energetic average of the last ≤ 60 per-second LAeq values. */
    val laeqRunning60sDb: Double,
    /** Current event background (L90 of trailing window), NaN until enough history. */
    val backgroundDb: Double,
    /** Fraction of this second's 125 ms ticks that were valid (levels above use only those). */
    val validFraction: Double = 1.0,
)

/**
 * Per-minute aggregate — the main stored record.
 *
 * All level fields are computed from valid audio only (see [validSeconds]); they are NaN when the
 * minute contains no valid audio at all.
 */
data class MinuteRecord(
    val startEpochMs: Long,
    /** ISO-8601 with offset, e.g. 2026-10-06T22:01:00+02:00. */
    val startIso: String,
    val durationSeconds: Double,
    val laeqDb: Double,
    val lafMaxDb: Double,
    val lafMinDb: Double,
    val l1Db: Double,
    val l10Db: Double,
    val l50Db: Double,
    val l90Db: Double,
    val eventCount: Int,
    /** Bucket with the largest time share (incl. "unclassified"), null if the classifier was off. */
    val dominantCategory: String?,
    /** Fraction of classifier frames per bucket id (sums to 1 if any frames). */
    val categoryShares: Map<String, Double>,
    val classifierFrames: Int,
    val calibrationId: Long?,
    val calibrationOffsetDb: Double,
    val audioSource: String,
    val calibrated: Boolean,
    /**
     * Seconds of valid audio in this minute. Time during which the microphone was silenced by the
     * system (call, voice assistant) or delivered digital silence — plus a 0.5 s recovery guard —
     * is excluded from every level, percentile, event and the background.
     */
    val validSeconds: Double = durationSeconds,
    /** Number of times the sample clock was re-anchored to the wall clock in this minute. */
    val clockCorrections: Int = 0,
    /**
     * Levels as originally measured, kept once the minute has been re-evaluated with a later
     * calibration («nachträglich kalibriert», v0.3.2); null for minutes never re-evaluated.
     */
    val original: MinuteLevels? = null,
    /**
     * Calibration the [original] levels were measured with: its id, or
     * [ch.stadtlaerm.dsp.calibration.Recalibration.FROM_DEFAULT] for the uncalibrated default.
     * Null if never re-evaluated. Always describes the original measurement, not an intermediate
     * re-evaluation.
     */
    val recalibratedFromId: String? = null,
    /** Offset the [original] levels were measured with; null if never re-evaluated. */
    val recalibrationOffsetDb: Double? = null,
) {
    /** Fraction of the minute's duration with valid audio (0…1). */
    val coverage: Double get() = if (durationSeconds > 0) (validSeconds / durationSeconds).coerceIn(0.0, 1.0) else 0.0

    /** True if the levels were re-evaluated with a calibration made after the measurement. */
    val recalibrated: Boolean get() = recalibratedFromId != null

    /** The level fields of this record (NaN where missing). */
    val levels: MinuteLevels get() = MinuteLevels(laeqDb, lafMaxDb, lafMinDb, l1Db, l10Db, l50Db, l90Db)
}

/** The calibration-dependent level fields of a minute (dB, NaN where missing). */
data class MinuteLevels(
    val laeqDb: Double,
    val lafMaxDb: Double,
    val lafMinDb: Double,
    val l1Db: Double,
    val l10Db: Double,
    val l50Db: Double,
    val l90Db: Double,
) {
    /** Every level shifted by [deltaDb]; NaN stays NaN. */
    fun shifted(deltaDb: Double) = MinuteLevels(
        laeqDb + deltaDb, lafMaxDb + deltaDb, lafMinDb + deltaDb, l1Db + deltaDb, l10Db + deltaDb, l50Db + deltaDb, l90Db + deltaDb,
    )
}

/** The calibration-dependent level fields of an event (dB). */
data class EventLevels(val lafMaxDb: Double, val selDb: Double, val backgroundDb: Double) {
    fun shifted(deltaDb: Double) = EventLevels(lafMaxDb + deltaDb, selDb + deltaDb, backgroundDb + deltaDb)
}

/** A detected noise event with its classification. */
data class NoiseEvent(
    val startEpochMs: Long,
    val startIso: String,
    val durationSeconds: Double,
    val lafMaxDb: Double,
    /** Sound exposure level LAE re 1 s. */
    val selDb: Double,
    val backgroundDb: Double,
    val thresholdDb: Double,
    /** Category id, "unclassified", or null if the classifier was off. */
    val dominantCategory: String?,
    val dominantScore: Float,
    val topLabels: List<LabelScore>,
    val classifierFrames: Int,
    val calibrationId: Long?,
    val audioSource: String,
    val calibrated: Boolean,
    /**
     * Absolute LAFmax floor that was in force when the event was detected (setting
     * "Mindestpegel"); NaN for events recorded before v0.3.0, which had no floor.
     */
    val minLevelDb: Double = Double.NaN,
    /** Levels as originally measured, once re-evaluated with a later calibration (v0.3.2); else null. */
    val original: EventLevels? = null,
    /** Calibration id (or "default") of the [original] levels; null if never re-evaluated. */
    val recalibratedFromId: String? = null,
    /** Offset of the [original] levels; null if never re-evaluated. */
    val recalibrationOffsetDb: Double? = null,
) {
    /** True if the levels were re-evaluated with a calibration made after the measurement. */
    val recalibrated: Boolean get() = recalibratedFromId != null

    /** The level fields of this event. */
    val levels: EventLevels get() = EventLevels(lafMaxDb, selDb, backgroundDb)

    /**
     * Read-time filter with the *current* floor, so that events stored before the floor existed
     * (or with a lower one) are treated the same way everywhere (night list, chart).
     */
    fun reachesFloor(floorDb: Double): Boolean = lafMaxDb >= floorDb
}

object Iso {
    private val withMillis: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val seconds: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    fun format(epochMs: Long, zone: ZoneId, millis: Boolean = false): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(if (millis) withMillis else seconds)
}
