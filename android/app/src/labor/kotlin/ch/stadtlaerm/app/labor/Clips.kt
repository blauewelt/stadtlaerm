package ch.stadtlaerm.app.labor

import java.io.File
import java.io.RandomAccessFile
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// LABOR BUILD ONLY (app/src/labor/). Reading the recorded clips back for playback (v0.3.4).
// Pure Kotlin (no Android types), unit-tested on the JVM in app/src/testLabor/.

/** A minimal JSON reader for the manifest lines: objects → Map, arrays → List, numbers → Double. */
object MiniJson {
    class ParseException(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i != text.length) throw ParseException("trailing data at ${p.i}")
        return v
    }

    /** The object on [line], or null if it is not a JSON object (or not valid JSON). */
    @Suppress("UNCHECKED_CAST")
    fun obj(line: String): Map<String, Any?>? = try { parse(line) as? Map<String, Any?> } catch (_: Exception) { null }

    private class Parser(val s: String) {
        var i = 0
        fun ws() { while (i < s.length && s[i].isWhitespace()) i++ }
        fun fail(what: String): Nothing = throw ParseException("$what at $i")

        fun value(): Any? {
            if (i >= s.length) fail("unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else fail("unexpected '$c'")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("expected $word")
            i += word.length
            return v
        }

        fun num(): Double {
            val start = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] in ".eE+-")) i++
            return s.substring(start, i).toDoubleOrNull() ?: fail("bad number")
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("bad escape")
                        when (val e = s[i++]) {
                            '"' -> sb.append('"'); '\\' -> sb.append('\\'); '/' -> sb.append('/')
                            'b' -> sb.append('\b'); 'f' -> sb.append('\u000c')
                            'n' -> sb.append('\n'); 'r' -> sb.append('\r'); 't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("bad \\u")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar()); i += 4
                            }
                            else -> fail("bad escape '$e'")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws(); out += value(); ws()
                if (i >= s.length) fail("unterminated array")
                when (s[i++]) { ',' -> continue; ']' -> return out; else -> fail("expected , or ]") }
            }
        }

        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') fail("expected key")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') fail("expected :")
                i++
                ws()
                out[k] = value()
                ws()
                if (i >= s.length) fail("unterminated object")
                when (s[i++]) { ',' -> continue; '}' -> return out; else -> fail("expected , or }") }
            }
        }
    }
}

/** An AudioSet label and its score. */
data class LabelScoreJ(val label: String, val score: Double)

/**
 * One classifier run inside a clip (an entry of the manifest's `classifierTrace`): the model window
 * (0.975 s) ends at [atMs] in the clip.
 */
data class TraceEntry(
    val atMs: Long,
    val top: List<LabelScoreJ>,
    val category: String?,
    val categoryScore: Double?,
    val gainDb: Double?,
    val lafDb: Double?,
    val lafMaxDb: Double?,
    val ignored: Boolean,
) {
    val top3: List<LabelScoreJ> get() = top.take(3)
}

/** A clip as described by its `clip` line in manifest.jsonl (or, without one, by its file name). */
data class ClipEntry(
    /** Path relative to the audio folder, e.g. `clips/ev_42_20261008_234012.wav` (the clip reference). */
    val ref: String,
    val eventId: Long?,
    /** Event start (epoch ms); from the manifest, else from the file name (seconds). */
    val eventStartMs: Long?,
    /** Event duration in seconds (null without manifest line). */
    val durationS: Double?,
    val lafMaxDb: Double?,
    val category: String?,
    val categoryScore: Double?,
    /** Where the event starts in the clip (the pre-roll actually recorded, ≤ 5 s). */
    val preRollMs: Long?,
    val clipDurationMs: Long?,
    /** The clip was cut at 60 s: the event runs past its end. */
    val truncated: Boolean,
    /** The post-roll was cut short because the measurement stopped. */
    val postRollCut: Boolean,
    /** Event length within the clip (from the sample indices), ms. */
    val eventSpanMs: Long?,
    val top3: List<LabelScoreJ>,
    val trace: List<TraceEntry>,
    /** False if the clip has no manifest line (only the file was found). */
    val fromManifest: Boolean = true,
    /** File size in bytes (0 if unknown/missing). */
    val bytes: Long = 0,
) {
    val hasTrace: Boolean get() = trace.isNotEmpty()
    val fileName: String get() = ref.substringAfterLast('/')
}

/** The clips, indexed by event id and by reference. */
class ClipIndex(entries: List<ClipEntry>) {
    /** All clips, oldest first (by event time; clips without a time last, by name). */
    val entries: List<ClipEntry> = entries.sortedWith(timeOrder)
    private val byRef: Map<String, ClipEntry> = this.entries.associateBy { it.ref }
    private val byEvent: Map<Long, ClipEntry> = this.entries.filter { it.eventId != null }.associateBy { it.eventId!! }

    val size: Int get() = entries.size
    val totalBytes: Long get() = entries.sumOf { it.bytes }

    fun byRef(ref: String): ClipEntry? = byRef[ref]
    fun byEventId(id: Long): ClipEntry? = byEvent[id]

    /**
     * The clip reference for the database event [eventId] starting at [eventStartMs], or null. If
     * the clip knows its event start, it must agree within [toleranceMs] (guards against a
     * manifest from another database).
     */
    fun clipRefFor(eventId: Long, eventStartMs: Long, toleranceMs: Long = 2_000): String? {
        val e = byEvent[eventId] ?: return null
        val s = e.eventStartMs
        if (s != null && kotlin.math.abs(s - eventStartMs) > toleranceMs) return null
        return e.ref
    }

    companion object {
        // Order matters: EMPTY's constructor uses timeOrder.
        val timeOrder: Comparator<ClipEntry> =
            compareBy<ClipEntry>({ it.eventStartMs == null }, { it.eventStartMs ?: 0L }, { it.ref })
        val EMPTY = ClipIndex(emptyList())
    }
}

/** Builds a [ClipIndex] from manifest lines and the files in the clips folder. */
object ClipIndexReader {
    private val FILE_NAME = Regex("""^ev_(\d+|na)_(\d{8}_\d{6})(_\d+)?\.wav$""")
    private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    private const val INPUT_RATE = 48_000L

    /**
     * [files]: clip file name → size of the WAV files present (null: do not check the disk). Clip
     * lines whose file is missing are left out; files without a line are added from their name.
     * Unreadable lines are skipped. A later line for the same file replaces an earlier one.
     */
    fun read(lines: Sequence<String>, files: Map<String, Long>?, zone: ZoneId): ClipIndex {
        val found = LinkedHashMap<String, ClipEntry>()
        for (line in lines) {
            if (!line.contains("\"clip\"")) continue // cheap pre-filter: most lines are not clips
            val o = MiniJson.obj(line) ?: continue
            if (o["type"] != "clip") continue
            val e = entry(o) ?: continue
            found[e.ref] = e
        }
        val out = ArrayList<ClipEntry>()
        for (e in found.values) {
            if (files == null) { out += e; continue }
            val size = files[e.fileName] ?: continue
            out += e.copy(bytes = size)
        }
        if (files != null) {
            val known = found.values.map { it.fileName }.toSet()
            for ((name, size) in files) {
                if (name in known) continue
                fromFileName(name, zone)?.let { out += it.copy(bytes = size) }
            }
        }
        return ClipIndex(out)
    }

    fun read(manifest: File, clipsDir: File, zone: ZoneId): ClipIndex {
        val files = clipsDir.listFiles()?.filter { it.isFile && it.name.endsWith(".wav") }?.associate { it.name to it.length() } ?: emptyMap()
        if (files.isEmpty()) return ClipIndex.EMPTY
        return if (manifest.isFile) manifest.bufferedReader(Charsets.UTF_8).useLines { read(it, files, zone) }
        else read(emptySequence(), files, zone)
    }

    private fun Map<String, Any?>.d(k: String): Double? = (this[k] as? Double)?.takeUnless { it.isNaN() }
    private fun Map<String, Any?>.l(k: String): Long? = d(k)?.toLong()
    private fun Map<String, Any?>.s(k: String): String? = this[k] as? String
    private fun Map<String, Any?>.b(k: String): Boolean = this[k] == true

    @Suppress("UNCHECKED_CAST")
    private fun labels(v: Any?): List<LabelScoreJ> = (v as? List<Any?>)?.mapNotNull { x ->
        val m = x as? Map<String, Any?> ?: return@mapNotNull null
        val label = m["label"] as? String ?: return@mapNotNull null
        LabelScoreJ(label, (m["score"] as? Double) ?: 0.0)
    } ?: emptyList()

    private fun isoMs(s: String?): Long? = s?.let { try { OffsetDateTime.parse(it).toInstant().toEpochMilli() } catch (_: Exception) { null } }

    @Suppress("UNCHECKED_CAST")
    fun entry(o: Map<String, Any?>): ClipEntry? {
        val file = o.s("file") ?: return null
        val clipStart = o.l("clipStartSample")
        val evStartSample = o.l("eventStartSample")
        val evEndSample = o.l("eventEndSample")
        val trace = (o["classifierTrace"] as? List<Any?>)?.mapNotNull { x ->
            val t = x as? Map<String, Any?> ?: return@mapNotNull null
            val at = t.l("atMsInClip")
                ?: run {
                    val end = t.l("endSample")
                    if (end != null && clipStart != null) (end - clipStart) * 1000 / INPUT_RATE else null
                }
                ?: return@mapNotNull null
            TraceEntry(
                atMs = at, top = labels(t["top5"]), category = t.s("category"), categoryScore = t.d("categoryScore"),
                gainDb = t.d("gainDb"), lafDb = t.d("lafDb"), lafMaxDb = t.d("lafMaxDb"), ignored = t.b("ignored"),
            )
        }?.sortedBy { it.atMs } ?: emptyList()
        val spanMs = if (evStartSample != null && evEndSample != null && evEndSample >= evStartSample)
            (evEndSample - evStartSample) * 1000 / INPUT_RATE
        else o.d("durationS")?.let { Math.round(it * 1000) }
        return ClipEntry(
            ref = file,
            eventId = o.l("eventId"),
            eventStartMs = isoMs(o.s("eventStart")),
            durationS = o.d("durationS"),
            lafMaxDb = o.d("lafMaxDb"),
            category = o.s("category"),
            categoryScore = o.d("categoryScore"),
            preRollMs = o.l("offsetOfEventStartInClipMs"),
            clipDurationMs = o.l("clipDurationMs"),
            truncated = o.b("truncated"),
            postRollCut = o.b("postRollCut"),
            eventSpanMs = spanMs,
            top3 = labels(o["top3"]),
            trace = trace,
        )
    }

    /** A clip without manifest line: event id and start (to the second) from `ev_<id>_<yyyyMMdd_HHmmss>.wav`. */
    fun fromFileName(name: String, zone: ZoneId): ClipEntry? {
        val m = FILE_NAME.find(name) ?: return null
        val id = m.groupValues[1].toLongOrNull()
        val start = try { LocalDateTime.parse(m.groupValues[2], STAMP).atZone(zone).toInstant().toEpochMilli() } catch (_: Exception) { null }
        return ClipEntry(
            ref = "clips/$name", eventId = id, eventStartMs = start, durationS = null, lafMaxDb = null,
            category = null, categoryScore = null, preRollMs = null, clipDurationMs = null,
            truncated = false, postRollCut = false, eventSpanMs = null, top3 = emptyList(), trace = emptyList(),
            fromManifest = false,
        )
    }
}

/**
 * The clips the player steps through with «‹» / «›»: [refs] ordered by event time (via [index];
 * unknown references keep their given order after the known ones).
 */
class ClipPlaylist(refs: List<String>, index: ClipIndex) {
    val refs: List<String> = run {
        val distinct = refs.distinct()
        val pos = distinct.withIndex().associate { it.value to it.index }
        distinct.sortedWith(
            compareBy<String>({ index.byRef(it)?.eventStartMs == null }, { index.byRef(it)?.eventStartMs ?: 0L }, { pos[it] ?: 0 })
        )
    }

    fun indexOf(ref: String): Int = refs.indexOf(ref)
    fun previous(ref: String): String? = indexOf(ref).let { if (it > 0) refs[it - 1] else null }
    fun next(ref: String): String? = indexOf(ref).let { if (it >= 0 && it < refs.size - 1) refs[it + 1] else null }
}

/** Which classifier run is shown for a playback position. */
object TraceLookup {
    /** Length of the model window (YAMNet: 15 600 samples at 16 kHz). */
    const val WINDOW_MS = 975L
    /** Runs are about 1 s apart; a position is covered by the run ending within this after it. */
    const val SLACK_MS = 1_000L

    /**
     * The run for playback position [positionMs]: the first run whose window ends at or after the
     * position (within [SLACK_MS]), i.e. the second being heard; after the last run, that run for
     * up to [SLACK_MS]; else null (e.g. a gap where the classifier was ignored or off). [trace]
     * must be sorted by [TraceEntry.atMs].
     */
    fun at(trace: List<TraceEntry>, positionMs: Long): TraceEntry? {
        if (trace.isEmpty()) return null
        var lo = 0
        var hi = trace.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (trace[mid].atMs < positionMs) lo = mid + 1 else hi = mid }
        if (lo < trace.size && trace[lo].atMs - positionMs <= SLACK_MS) return trace[lo]
        val prev = trace.getOrNull(lo - 1) ?: return null
        return prev.takeIf { positionMs - it.atMs <= SLACK_MS }
    }
}

/**
 * The player's progress bar: fractions (0…1) of the clip for the event span. Before
 * [eventStart] is the pre-roll (shaded), up to [eventEnd] the event (highlighted), after it the
 * post-roll (shaded). [truncated]: the event runs past the end of the clip (no post-roll).
 */
data class ClipBar(val eventStart: Float, val eventEnd: Float, val truncated: Boolean) {
    val preRoll: Float get() = eventStart
    val postRoll: Float get() = 1f - eventEnd

    companion object {
        /** The whole clip is the event (no timing known). */
        val UNKNOWN = ClipBar(0f, 1f, false)

        /**
         * [clipMs]: clip length (from the WAV file); [preRollMs]: where the event starts in the clip;
         * [eventMs]: event length; [flaggedTruncated]: the manifest's `truncated`.
         */
        fun of(clipMs: Long, preRollMs: Long?, eventMs: Long?, flaggedTruncated: Boolean = false): ClipBar {
            if (clipMs <= 0 || preRollMs == null) return UNKNOWN
            val start = (preRollMs.toDouble() / clipMs).coerceIn(0.0, 1.0)
            if (eventMs == null) return ClipBar(start.toFloat(), 1f, flaggedTruncated)
            val endMs = preRollMs + eventMs
            val end = (endMs.toDouble() / clipMs).coerceIn(start, 1.0)
            return ClipBar(start.toFloat(), end.toFloat(), flaggedTruncated || endMs > clipMs)
        }
    }
}

/** The format of a WAV file, read from its header; null if it is not a playable PCM WAV. */
data class WavInfo(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val dataBytes: Long) {
    val durationMs: Long get() = if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) 0
        else dataBytes * 1000 / (sampleRate.toLong() * channels * (bitsPerSample / 8))

    companion object {
        /** Checks [file]: RIFF/WAVE, PCM `fmt `, a non-empty `data` chunk that fits the file. */
        fun read(file: File): WavInfo? {
            if (!file.isFile || file.length() < Wav.HEADER_BYTES) return null
            return try {
                RandomAccessFile(file, "r").use { raf ->
                    val head = ByteArray(12)
                    raf.readFully(head)
                    if (String(head, 0, 4, Charsets.US_ASCII) != "RIFF" || String(head, 8, 4, Charsets.US_ASCII) != "WAVE") return null
                    var fmt: WavInfo? = null
                    val hdr = ByteArray(8)
                    while (raf.filePointer + 8 <= raf.length()) {
                        raf.readFully(hdr)
                        val id = String(hdr, 0, 4, Charsets.US_ASCII)
                        val size = le32(hdr, 4)
                        if (id == "fmt ") {
                            if (size < 16) return null
                            val f = ByteArray(16); raf.readFully(f)
                            if (le16(f, 0) != 1) return null // PCM only
                            fmt = WavInfo(sampleRate = le32(f, 4).toInt(), channels = le16(f, 2), bitsPerSample = le16(f, 14), dataBytes = 0)
                            raf.seek(raf.filePointer + (size - 16) + (size and 1))
                        } else if (id == "data") {
                            val f = fmt ?: return null
                            val available = raf.length() - raf.filePointer
                            if (size <= 0 || available <= 0) return null
                            // A file cut short (crash while copying) plays what is there.
                            return f.copy(dataBytes = minOf(size, available))
                        } else {
                            raf.seek(raf.filePointer + size + (size and 1))
                        }
                    }
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun le32(b: ByteArray, at: Int): Long =
            (b[at].toLong() and 0xFF) or ((b[at + 1].toLong() and 0xFF) shl 8) or
                ((b[at + 2].toLong() and 0xFF) shl 16) or ((b[at + 3].toLong() and 0xFF) shl 24)

        private fun le16(b: ByteArray, at: Int): Int = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    }
}

/** «Diese Nacht» in the clip list: the night (22:00–06:00) that is running or ended last. */
object ClipNights {
    /** The date the current/last night started on: the local date 22 h before [nowMs]. */
    fun currentNightOf(nowMs: Long, zone: ZoneId): java.time.LocalDate =
        java.time.Instant.ofEpochMilli(nowMs).atZone(zone).minusHours(22).toLocalDate()
}
