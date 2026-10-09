package ch.stadtlaerm.app.labor

import ch.stadtlaerm.dsp.EventFeatures
import ch.stadtlaerm.dsp.Iso
import ch.stadtlaerm.dsp.NoiseEvent
import ch.stadtlaerm.dsp.classify.ClassifierResult
import java.io.File
import java.io.FileOutputStream
import java.time.ZoneId
import java.util.Locale

// LABOR BUILD ONLY (app/src/labor/).

/** A tiny JSON object writer (compact, keys in insertion order). NaN/∞ become null. */
class Json {
    private val sb = StringBuilder("{")
    private var first = true

    private fun key(k: String) {
        if (!first) sb.append(',')
        first = false
        str(sb, k); sb.append(':')
    }

    fun put(k: String, v: String?): Json { key(k); if (v == null) sb.append("null") else str(sb, v); return this }
    fun put(k: String, v: Long?): Json { key(k); sb.append(v?.toString() ?: "null"); return this }
    fun put(k: String, v: Int?): Json { key(k); sb.append(v?.toString() ?: "null"); return this }
    fun put(k: String, v: Boolean?): Json { key(k); sb.append(v?.toString() ?: "null"); return this }
    fun put(k: String, v: Double?): Json { key(k); num(sb, v); return this }
    fun put(k: String, v: Double?, decimals: Int): Json { key(k); num(sb, v, decimals); return this }
    fun putRaw(k: String, json: String): Json { key(k); sb.append(json); return this }
    fun putStrings(k: String, v: List<String>): Json {
        key(k); sb.append('[')
        v.forEachIndexed { i, s -> if (i > 0) sb.append(','); str(sb, s) }
        sb.append(']'); return this
    }
    fun putObjects(k: String, v: List<Json>): Json {
        key(k); sb.append('[')
        v.forEachIndexed { i, o -> if (i > 0) sb.append(','); sb.append(o.build()) }
        sb.append(']'); return this
    }

    fun build(): String = "$sb}"

    companion object {
        fun str(sb: StringBuilder, s: String) {
            sb.append('"')
            for (c in s) when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format(Locale.ROOT, "\\u%04x", c.code))
                else -> sb.append(c)
            }
            sb.append('"')
        }

        fun num(sb: StringBuilder, v: Double?, decimals: Int = 3) {
            if (v == null || v.isNaN() || v.isInfinite()) { sb.append("null"); return }
            val t = String.format(Locale.ROOT, "%.${decimals}f", v)
            sb.append(if ('.' in t) t.trimEnd('0').trimEnd('.') else t)
        }
    }
}

/**
 * The lines of `manifest.jsonl` (one JSON object per line, appended; every line has `type`,
 * `session` and `time`). Times are ISO-8601 with the zone offset.
 */
object ManifestLines {
    const val INPUT_RATE = 48_000
    const val OUTPUT_RATE = 16_000

    private fun base(type: String, session: String, nowMs: Long, zone: ZoneId) =
        Json().put("type", type).put("session", session).put("time", Iso.format(nowMs, zone, millis = true))

    data class SessionInfo(
        val deviceModel: String,
        val androidRelease: String,
        val sdkInt: Int,
        val appVersion: String,
        val appVersionCode: Int,
        val audioSource: String,
        val encoding: String,
        val effects: List<String>,
        val calibrationId: Long?,
        val calibrationOffsetDb: Double,
        val calibrated: Boolean,
        val eventExcessDb: Double,
        val localFloorWindowSeconds: Double,
        val eventMinLevelDb: Double,
        val windLfShareMin: Double,
        val windFlutterMinDb: Double,
        val classifierEnabled: Boolean,
        val classifierNormalize: Boolean,
        val classifierIntervalSeconds: Double,
        val clips: Boolean,
        val continuous: Boolean,
        val clipEvery: Int,
        val maxBytes: Long,
    )

    fun sessionStart(session: String, nowMs: Long, zone: ZoneId, i: SessionInfo): String =
        base("session_start", session, nowMs, zone)
            .put("zone", zone.id)
            .put("device", i.deviceModel)
            .put("android", i.androidRelease).put("sdk", i.sdkInt)
            .put("app", i.appVersion).put("appCode", i.appVersionCode)
            .put("audioSource", i.audioSource).put("encoding", i.encoding).putStrings("effects", i.effects)
            .put("calibrationId", i.calibrationId).put("calibrationOffsetDb", i.calibrationOffsetDb).put("calibrated", i.calibrated)
            // Detector v2 (0.4.0): start at local floor + excess; wind rule thresholds.
            .put("detector", "v2").put("eventExcessDb", i.eventExcessDb).put("localFloorWindowS", i.localFloorWindowSeconds)
            .put("eventFloorDb", i.eventMinLevelDb)
            .put("windLfShareMin", i.windLfShareMin).put("windFlutterMinDb", i.windFlutterMinDb)
            .put("classifier", i.classifierEnabled).put("classifierLevelAdjustment", i.classifierNormalize)
            .put("classifierIntervalS", i.classifierIntervalSeconds)
            .put("inputSampleRate", INPUT_RATE)
            .put("clips", i.clips).put("continuous", i.continuous).put("clipEvery", i.clipEvery).put("maxBytes", i.maxBytes)
            .build()

    /**
     * The engine's sample clock: from here on, input sample s (48 kHz, counted from the start of
     * the measurement) was recorded at `anchorEpochMs + (s − anchorSample)·1000/48000`.
     */
    fun clock(session: String, nowMs: Long, zone: ZoneId, anchorSample: Long, anchorEpochMs: Long, correctionMs: Long?): String =
        base("clock", session, nowMs, zone)
            .put("anchorSample", anchorSample).put("anchorEpochMs", anchorEpochMs)
            .put("anchorTime", Iso.format(anchorEpochMs, zone, millis = true))
            .put("correctionMs", correctionMs)
            .build()

    /**
     * One classifier run as a JSON object: `time` = end of the 0.975 s window (the run describes
     * the audio before it), top-5 AudioSet labels with scores, the category decision for this
     * window and its score (`category` null if the window was ignored because it touched
     * silenced/invalid audio), the input gain applied before the model (dB, 0 if the level
     * adjustment is off), LAF at the window end and the highest LAFmax within the window (dB(A)).
     * With [clipStartSample], `atMsInClip` = position of the window end in the clip.
     */
    fun classifierResult(r: ClassifierResult, zone: ZoneId, clipStartSample: Long? = null): Json {
        val j = Json().put("time", Iso.format(r.endEpochMs, zone, millis = true))
        if (clipStartSample != null) j.put("atMsInClip", (r.endSample - clipStartSample) * 1000 / INPUT_RATE)
        val d = r.decision
        return j.putObjects("top5", r.top.take(5).map { Json().put("label", it.label).put("score", it.score.toDouble(), 4) })
            .put("category", d?.dominant)
            .put("categoryScore", d?.dominantScore?.toDouble(), 4)
            .put("gainDb", r.inputGainDb, 1)
            .put("lafDb", r.lafDb, 1)
            .put("lafMaxDb", r.lafMaxDb, 1)
            .put("ignored", r.ignoredInvalid)
            .put("endSample", r.endSample)
    }

    /** A line of `classifier/<yyyyMMdd_HH>.jsonl`: [classifierResult] plus the session id. */
    fun classifierLine(session: String, r: ClassifierResult, zone: ZoneId): String =
        classifierResult(r, zone).put("session", session).build()

    fun clip(
        session: String, nowMs: Long, zone: ZoneId,
        eventId: Long?, file: String, event: NoiseEvent?, clip: AssembledClip, outputSamples: Int,
        trace: List<ClassifierResult> = emptyList(),
    ): String {
        val pre = clip.preRollSamples
        val j = base("clip", session, nowMs, zone)
            .put("eventId", eventId)
            .put("file", file)
        if (event != null) {
            val endMs = event.startEpochMs + Math.round(event.durationSeconds * 1000)
            j.put("eventStart", Iso.format(event.startEpochMs, zone, millis = true))
                .put("eventEnd", Iso.format(endMs, zone, millis = true))
                .put("durationS", event.durationSeconds)
                .put("lafMaxDb", event.lafMaxDb).put("selDb", event.selDb).put("backgroundDb", event.backgroundDb)
                .put("category", event.dominantCategory).put("categoryScore", event.dominantScore.toDouble())
                .putObjects("top3", event.topLabels.take(3).map { Json().put("label", it.label).put("score", it.score.toDouble()) })
            event.features?.let { j.putRaw("features", features(it).build()) }
        } else {
            j.put("eventStart", null as String?).put("eventEnd", null as String?)
        }
        return j.put("offsetOfEventStartInClipMs", pre * 1000 / INPUT_RATE)
            .put("clipDurationMs", outputSamples * 1000L / OUTPUT_RATE)
            .put("sampleRate", OUTPUT_RATE)
            .put("truncated", clip.truncated)
            .put("postRollCut", clip.endedEarly)
            .put("eventStartSample", clip.eventStartSample).put("eventEndSample", clip.eventEndSample)
            .put("clipStartSample", clip.clipStartSample)
            // The classifier's per-second results whose window ends inside the clip.
            .putObjects("classifierTrace", trace.map { classifierResult(it, zone, clip.clipStartSample) })
            .build()
    }

    /**
     * The detector-v2 numbers of an event (since 0.4.0): local floor (dB(A)), excess (dB), rise and
     * decay (s, null if not measurable), jaggedness, mid-band rise (dB), LF share (0…1), LF flutter
     * (dB), the wind flag and the shape (hump, jagged, impulse, long). Definitions: android/README.md.
     */
    fun features(f: EventFeatures): Json = Json()
        .put("localFloorDb", f.localFloorDb, 2).put("excessDb", f.excessDb, 2)
        .put("riseS", f.riseS, 3).put("decayS", f.decayS, 3).put("jaggedness", f.jaggedness, 4)
        .put("midBandRiseDb", f.midBandRiseDb, 2).put("lfShare", f.lfShare, 4).put("lfFlutterDb", f.lfFlutterDb, 2)
        .put("wind", f.wind).put("shape", f.shape)

    /**
     * Every event (also those without a clip, since 0.4.0) when it is complete: input sample span,
     * start/end time and [features]. Written from `AudioTap.onEventEnded`, before the event is
     * stored, so it carries no database id; match it to `clip` lines by `eventStartSample`.
     */
    fun event(
        session: String, nowMs: Long, zone: ZoneId, startSample: Long, endSample: Long, startEpochMs: Long, endEpochMs: Long,
        f: EventFeatures,
    ): String =
        base("event", session, nowMs, zone)
            .put("eventStart", Iso.format(startEpochMs, zone, millis = true))
            .put("eventEnd", Iso.format(endEpochMs, zone, millis = true))
            .put("durationS", (endSample - startSample).toDouble() / INPUT_RATE)
            .put("eventStartSample", startSample).put("eventEndSample", endSample)
            .putRaw("features", features(f).build())
            .build()

    /**
     * A continuous file was opened. Its 16 kHz sample j (= AAC presentation time j/16000 s)
     * corresponds to input sample `startInputSample + 3·j + 2`; wall-clock time via the clock lines.
     */
    fun continuousOpen(session: String, nowMs: Long, zone: ZoneId, file: String, startInputSample: Long, startEpochMs: Long): String =
        base("continuous_open", session, nowMs, zone)
            .put("file", file)
            .put("start", Iso.format(startEpochMs, zone, millis = true)).put("startEpochMs", startEpochMs)
            .put("startInputSample", startInputSample)
            .put("sampleRate", OUTPUT_RATE).put("channels", 1).put("codec", "AAC-LC").put("bitRate", 64_000)
            .put("mapping", "inputSample = startInputSample + 3*j + 2 for 16 kHz sample j (AAC pts j/16000 s)")
            .build()

    fun continuousClose(session: String, nowMs: Long, zone: ZoneId, file: String, samples: Long, bytes: Long, reason: String): String =
        base("continuous_close", session, nowMs, zone)
            .put("file", file).put("samples", samples).put("durationS", samples.toDouble() / OUTPUT_RATE)
            .put("bytes", bytes).put("reason", reason)
            .build()

    /** Blocks were dropped (writer too slow): the gap is filled with silence so the mapping holds. */
    fun gap(session: String, nowMs: Long, zone: ZoneId, file: String?, fromInputSample: Long, toInputSample: Long, filled: Boolean): String =
        base("gap", session, nowMs, zone)
            .put("file", file).put("fromInputSample", fromInputSample).put("toInputSample", toInputSample)
            .put("durationMs", (toInputSample - fromInputSample) * 1000 / INPUT_RATE).put("filledWithSilence", filled)
            .build()

    fun storageFull(session: String, nowMs: Long, zone: ZoneId, usedBytes: Long, maxBytes: Long): String =
        base("storage_full", session, nowMs, zone).put("usedBytes", usedBytes).put("maxBytes", maxBytes).build()

    data class Counts(
        val clipsWritten: Long = 0,
        val clipsSkippedRate: Long = 0,
        val clipsDroppedBusy: Long = 0,
        val clipsDroppedQueue: Long = 0,
        val clipsDroppedStorage: Long = 0,
        val clipsWithoutEventId: Long = 0,
        val clipErrors: Long = 0,
        val continuousFiles: Long = 0,
        val continuousBlocksDropped: Long = 0,
    )

    fun sessionStop(session: String, nowMs: Long, zone: ZoneId, c: Counts, storageFull: Boolean): String =
        base("session_stop", session, nowMs, zone)
            .put("clipsWritten", c.clipsWritten).put("clipsSkippedRate", c.clipsSkippedRate)
            .put("clipsDroppedBusy", c.clipsDroppedBusy).put("clipsDroppedQueue", c.clipsDroppedQueue)
            .put("clipsDroppedStorage", c.clipsDroppedStorage).put("clipsWithoutEventId", c.clipsWithoutEventId)
            .put("clipErrors", c.clipErrors)
            .put("continuousFiles", c.continuousFiles).put("continuousBlocksDropped", c.continuousBlocksDropped)
            .put("storageFull", storageFull)
            .build()
}

/** Appends lines to `manifest.jsonl` (process-wide lock, flushed per line). */
object ManifestFile {
    private val lock = Any()

    fun append(file: File, line: String) {
        require('\n' !in line)
        synchronized(lock) {
            file.parentFile?.mkdirs()
            FileOutputStream(file, true).use { it.write((line + "\n").toByteArray(Charsets.UTF_8)) }
        }
    }
}

/**
 * `classifier/<yyyyMMdd_HH>.jsonl`: one line per classifier run for the whole measurement, one
 * file per local wall-clock hour of the run's time (lines of a later measurement in the same hour
 * are appended). Keeps the current file open; call from one thread only.
 */
class ClassifierLog(private val dir: File, private val zone: ZoneId) : AutoCloseable {
    private var name: String? = null
    private var out: FileOutputStream? = null

    /** The file name for a run at [epochMs]. */
    fun fileName(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(zone).format(HourRoller.HOUR_NAME) + ".jsonl"

    /** Appends [line] to the file of hour [epochMs]; returns the bytes written. */
    fun append(epochMs: Long, line: String): Long {
        require('\n' !in line)
        val n = fileName(epochMs)
        if (n != name || out == null) {
            close()
            dir.mkdirs()
            out = FileOutputStream(File(dir, n), true)
            name = n
        }
        val bytes = (line + "\n").toByteArray(Charsets.UTF_8)
        out!!.write(bytes)
        return bytes.size.toLong()
    }

    override fun close() {
        try { out?.close() } catch (_: Exception) {}
        out = null
        name = null
    }
}
