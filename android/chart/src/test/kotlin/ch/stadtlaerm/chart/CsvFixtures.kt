package ch.stadtlaerm.chart

import ch.stadtlaerm.dsp.MinuteRecord
import ch.stadtlaerm.dsp.NoiseEvent
import ch.stadtlaerm.dsp.classify.LabelScore
import java.io.File
import java.time.OffsetDateTime

/**
 * Reads the app's own CSV exports (Csv.kt) back into records, by column name. Used to render the
 * chart with a real night when STADTLAERM_REAL_DATA points at a directory with minuten.csv and
 * ereignisse.csv; such data stays outside the repository.
 */
object CsvFixtures {
    /** Directory with real exports, or null (tests depending on it are skipped). */
    fun realDataDir(): File? = System.getProperty("stadtlaerm.realData")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

    private fun parseLine(line: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun rows(file: File): List<Map<String, String>> {
        val lines = file.readLines().filter { it.isNotBlank() }
        val header = parseLine(lines.first())
        return lines.drop(1).map { l -> header.zip(parseLine(l)).toMap() }
    }

    private fun Map<String, String>.d(k: String): Double = this[k]?.takeIf { it.isNotEmpty() }?.toDouble() ?: Double.NaN
    private fun Map<String, String>.epoch(k: String): Long = OffsetDateTime.parse(getValue(k)).toInstant().toEpochMilli()

    fun minutes(file: File): List<MinuteRecord> = rows(file).map { r ->
        val shares = r.filterKeys { it.startsWith("share_") }.mapNotNull { (k, v) -> v.takeIf { it.isNotEmpty() }?.let { k.removePrefix("share_") to it.toDouble() } }.toMap()
        MinuteRecord(
            startEpochMs = r.epoch("start"), startIso = r.getValue("start"), durationSeconds = r.d("duration_s"),
            laeqDb = r.d("laeq_db"), lafMaxDb = r.d("lafmax_db"), lafMinDb = r.d("lafmin_db"), l1Db = r.d("l1_db"),
            l10Db = r.d("l10_db"), l50Db = r.d("l50_db"), l90Db = r.d("l90_db"), eventCount = r.getValue("event_count").toInt(),
            dominantCategory = r["dominant_category"]?.ifEmpty { null }, categoryShares = shares,
            classifierFrames = r["classifier_frames"]?.toIntOrNull() ?: 0, calibrationId = r["calibration_id"]?.toLongOrNull(),
            calibrationOffsetDb = r.d("calibration_offset_db"), audioSource = r["audio_source"] ?: "",
            calibrated = r["calibrated"] == "true", validSeconds = r.d("valid_s").takeUnless { it.isNaN() } ?: r.d("duration_s"),
            clockCorrections = r["clock_corrections"]?.toIntOrNull() ?: 0,
        )
    }

    fun events(file: File): List<NoiseEvent> = rows(file).map { r ->
        NoiseEvent(
            startEpochMs = r.epoch("start"), startIso = r.getValue("start"), durationSeconds = r.d("duration_s"),
            lafMaxDb = r.d("lafmax_db"), selDb = r.d("sel_db"), backgroundDb = r.d("background_db"), thresholdDb = r.d("threshold_db"),
            dominantCategory = r["dominant_category"]?.ifEmpty { null }, dominantScore = r.d("dominant_score").toFloat(),
            topLabels = (1..3).mapNotNull { i -> r["label$i"]?.takeIf { it.isNotEmpty() }?.let { LabelScore(it, r.d("score$i").toFloat()) } },
            classifierFrames = r["classifier_frames"]?.toIntOrNull() ?: 0, calibrationId = r["calibration_id"]?.toLongOrNull(),
            audioSource = r["audio_source"] ?: "", calibrated = r["calibrated"] == "true",
            minLevelDb = r.d("min_level_db"),
        )
    }

    /** The real night as chart data for [window] (neighbour times computed from the same files). */
    fun realData(dir: File, window: TimeWindow, nowMs: Long): ChartData {
        val mins = minutes(File(dir, "minuten.csv"))
        val evs = events(File(dir, "ereignisse.csv"))
        val valid = mins.filter { ChartModel.isValid(it) }
        return ChartData(
            window = window,
            minutes = mins.filter { it.startEpochMs in window },
            events = evs.filter { it.startEpochMs in window },
            nowMs = nowMs,
            prevValidEndMs = valid.filter { it.startEpochMs < window.startMs }.maxOfOrNull { it.startEpochMs + Math.round(it.durationSeconds * 1000) },
            nextValidStartMs = valid.filter { it.startEpochMs >= window.endMs }.minOfOrNull { it.startEpochMs },
        )
    }
}
