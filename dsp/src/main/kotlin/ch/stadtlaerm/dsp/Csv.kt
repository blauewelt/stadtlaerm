package ch.stadtlaerm.dsp

import java.util.Locale

/** CSV export (RFC 4180 quoting, '.' decimal separator, UTF-8). */
object Csv {
    fun field(s: String?): String {
        if (s == null) return ""
        return if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + s.replace("\"", "\"\"") + "\""
        } else s
    }

    fun num(v: Double, decimals: Int = 1): String =
        if (v.isNaN() || v.isInfinite()) "" else String.format(Locale.ROOT, "%.${decimals}f", v)

    fun minutes(records: List<MinuteRecord>, bucketIds: List<String>): String {
        val sb = StringBuilder()
        val header = listOf(
            "start", "duration_s", "valid_s", "coverage", "laeq_db", "lafmax_db", "lafmin_db", "l1_db", "l10_db", "l50_db", "l90_db",
            "event_count", "dominant_category",
        ) + bucketIds.map { "share_$it" } + listOf(
            "classifier_frames", "calibration_id", "calibration_offset_db", "audio_source", "calibrated",
            "clock_corrections",
        )
        sb.append(header.joinToString(",")).append("\n")
        for (r in records) {
            val row = listOf(
                field(r.startIso), num(r.durationSeconds, 1), num(r.validSeconds, 1), num(r.coverage, 3), num(r.laeqDb), num(r.lafMaxDb), num(r.lafMinDb),
                num(r.l1Db), num(r.l10Db), num(r.l50Db), num(r.l90Db), r.eventCount.toString(),
                field(r.dominantCategory),
            ) + bucketIds.map { id -> r.categoryShares[id]?.let { num(it, 3) } ?: "" } + listOf(
                r.classifierFrames.toString(), r.calibrationId?.toString() ?: "", num(r.calibrationOffsetDb, 2),
                field(r.audioSource), r.calibrated.toString(), r.clockCorrections.toString(),
            )
            sb.append(row.joinToString(",")).append("\n")
        }
        return sb.toString()
    }

    fun events(events: List<NoiseEvent>): String {
        val sb = StringBuilder()
        sb.append(
            listOf(
                "start", "duration_s", "lafmax_db", "sel_db", "background_db", "threshold_db",
                "dominant_category", "dominant_score",
                "label1", "score1", "label2", "score2", "label3", "score3",
                "classifier_frames", "calibration_id", "audio_source", "calibrated",
            ).joinToString(",")
        ).append("\n")
        for (e in events) {
            val labels = (0 until 3).flatMap { i ->
                val l = e.topLabels.getOrNull(i)
                listOf(field(l?.label), l?.let { num(it.score.toDouble(), 3) } ?: "")
            }
            val row = listOf(
                field(e.startIso), num(e.durationSeconds, 3), num(e.lafMaxDb), num(e.selDb), num(e.backgroundDb),
                num(e.thresholdDb), field(e.dominantCategory), num(e.dominantScore.toDouble(), 3),
            ) + labels + listOf(
                e.classifierFrames.toString(), e.calibrationId?.toString() ?: "", field(e.audioSource),
                e.calibrated.toString(),
            )
            sb.append(row.joinToString(",")).append("\n")
        }
        return sb.toString()
    }
}
