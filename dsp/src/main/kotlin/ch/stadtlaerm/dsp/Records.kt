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
)

/** Per-minute aggregate — the main stored record. */
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
)

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
)

object Iso {
    private val withMillis: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
    private val seconds: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX")

    fun format(epochMs: Long, zone: ZoneId, millis: Boolean = false): String =
        Instant.ofEpochMilli(epochMs).atZone(zone).format(if (millis) withMillis else seconds)
}
