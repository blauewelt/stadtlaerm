package ch.stadtlaerm.dsp

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Offline replay (test utility): feeds a WAV file through [MeasurementEngine] at 48 kHz — 16 kHz
 * files (the Labor build's clips, the fixtures) are upsampled ×3 with [Upsampler3] — and collects
 * the events, minutes and ticks. No classifier (the dsp module has no model); everything else is
 * the production engine.
 *
 * From the command line (prints the events CSV, then the minutes CSV, per file):
 * ```
 * ./gradlew :dsp:replay --args="clip1.wav clip2.wav"            # default settings
 * ./gradlew :dsp:replay --args="--excess 8 --window 20 --min-history 3 clip.wav"
 * ```
 */
object Replay {
    val ZONE: ZoneId = ZoneId.of("Europe/Zurich")
    /** Default start of a replay: 23:00:00 local time, so that a short file stays in one minute. */
    val START_MS: Long = LocalDateTime.of(2026, 10, 2, 23, 0, 0).atZone(ZONE).toInstant().toEpochMilli()

    class Result(
        val events: List<NoiseEvent>,
        val minutes: List<MinuteRecord>,
        val ticks: List<LafTick>,
        val closed: List<Triple<Long, Long, EventFeatures>>,
    ) {
        fun eventsCsv(): String = Csv.events(events)
        fun minutesCsv(): String = Csv.minutes(minutes, emptyList())
    }

    fun config(
        excessDb: Double = EngineConfig.DEFAULT_EVENT_EXCESS_DB,
        localWindowS: Double = EngineConfig.DEFAULT_LOCAL_FLOOR_WINDOW_SECONDS,
        minHistoryS: Double = 5.0,
    ) = EngineConfig(
        zone = ZONE, classifierEnabled = false, eventExcessDb = excessDb,
        localFloorWindowSeconds = localWindowS, localFloorMinHistorySeconds = minHistoryS,
    )

    /** Replays [signal48k] (48 kHz, full scale ±1). */
    fun run(signal48k: FloatArray, config: EngineConfig = config(), startMs: Long = START_MS): Result {
        val events = ArrayList<NoiseEvent>()
        val minutes = ArrayList<MinuteRecord>()
        val ticks = ArrayList<LafTick>()
        val closed = ArrayList<Triple<Long, Long, EventFeatures>>()
        val clock = SimClock(startMs)
        val engine = MeasurementEngine(
            config, null,
            object : MeasurementEngine.Listener {
                override fun onEvent(event: NoiseEvent) { events += event }
                override fun onMinute(minute: MinuteRecord) { minutes += minute }
                override fun onTick(tick: LafTick) { ticks += tick }
                override fun onEventClosed(startSample: Long, endSample: Long, features: EventFeatures) {
                    closed += Triple(startSample, endSample, features)
                }
            },
            clock::now,
        )
        TestSignals.feed(engine, signal48k, clock = clock)
        engine.stop()
        return Result(events, minutes.sortedBy { it.startEpochMs }, ticks, closed)
    }

    /** Replays a WAV file (16 kHz is upsampled to 48 kHz, 48 kHz is used as is). */
    fun runWav(file: File, config: EngineConfig = config(), startMs: Long = START_MS): Result {
        val (x, rate) = Wav.read(file)
        val signal = when (rate) {
            48_000 -> x
            16_000 -> Upsampler3.process(x)
            else -> error("${file.name}: $rate Hz (only 16 kHz and 48 kHz are supported)")
        }
        return run(signal, config, startMs)
    }
}

/**
 * 16 -> 48 kHz polyphase interpolation: zero-stuffing ×3, then the decimator's own anti-aliasing
 * FIR (241 taps, Kaiser β = 7, 7.5 kHz) with gain 3, evaluated per output phase (≈ 80 taps per
 * output sample); the group delay (120 samples at 48 kHz) is removed, so output sample 3·j is
 * input sample j.
 */
object Upsampler3 {
    private val taps = DecimatingResampler().taps

    fun process(x: FloatArray): FloatArray = FloatArray(x.size * 3).also { process(x, 0, it.size, it) }

    /** Output samples [from, to) (48 kHz indices) of the upsampled [x] into out[0 until to − from]. */
    fun process(x: FloatArray, from: Int, to: Int, out: FloatArray) {
        val n = taps.size
        val delay = (n - 1) / 2
        for (o in from until to) {
            val t = o + delay // index in the delayed (causal) output
            var acc = 0.0
            // y[t] = 3 · Σ_j h[j] · u[t − j], u[3m] = x[m], else 0.
            var j = t % 3
            while (j < n) {
                val m = (t - j) / 3
                if (m in x.indices) acc += taps[j] * x[m]
                j += 3
            }
            out[o - from] = (3 * acc).toFloat()
        }
    }
}

/** Minimal mono 16-bit PCM WAV reader/writer (test utility). */
object Wav {
    fun read(file: File): Pair<FloatArray, Int> {
        val b = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(String(b.array(), 0, 4) == "RIFF" && String(b.array(), 8, 4) == "WAVE") { "${file.name}: not a WAV file" }
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        while (pos + 8 <= b.limit()) {
            val id = String(b.array(), pos, 4)
            val size = b.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    require(b.getShort(body).toInt() == 1) { "${file.name}: only PCM" }
                    channels = b.getShort(body + 2).toInt()
                    rate = b.getInt(body + 4)
                    bits = b.getShort(body + 14).toInt()
                }
                "data" -> {
                    require(channels == 1 && bits == 16) { "${file.name}: only mono 16-bit (got $channels ch, $bits bit)" }
                    val n = minOf(size, b.limit() - body) / 2
                    return FloatArray(n) { b.getShort(body + 2 * it) / 32768f } to rate
                }
            }
            pos = body + size + (size and 1)
        }
        error("${file.name}: no data chunk")
    }

    fun write(file: File, x: FloatArray, rate: Int) {
        val data = ByteBuffer.allocate(x.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (v in x) data.putShort(Math.round(v.coerceIn(-1f, 32767f / 32768f) * 32768f).toShort())
        val out = ByteArrayOutputStream()
        val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        h.put("RIFF".toByteArray()).putInt(36 + x.size * 2).put("WAVE".toByteArray())
        h.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        h.put("data".toByteArray()).putInt(x.size * 2)
        out.write(h.array()); out.write(data.array())
        file.parentFile?.mkdirs()
        file.writeBytes(out.toByteArray())
    }
}

/**
 * The three synthetic replay fixtures (16 kHz, mono, 16-bit), deterministic. Regenerate with
 * `STADTLAERM_WRITE_FIXTURES=1 ./gradlew :dsp:test --tests '*ReplayTest*'` (then update the
 * expected CSVs the same way, see [ReplayTest]).
 */
object ReplayFixtures {
    const val RATE = 16_000
    /** Background: white noise at −70 dBFS (≈ 41 dB(A) uncalibrated), the "hum". */
    const val BG_RMS = 3e-4

    /** 6 s background, a smooth 6 s hump of 300–3000 Hz noise peaking ≈ 12 dB above it, 3 s background. */
    const val HUMP_START_S = 6.0
    const val HUMP_LENGTH_S = 6.0
    /** Power ratio hump/background at the top. */
    const val HUMP_PEAK_RATIO = 15.0

    /** 6 s background, one 0.4 s thump of < 80 Hz noise (raised-cosine envelope), 3.6 s background. */
    const val THUMP_START_S = 6.0
    const val THUMP_LENGTH_S = 0.4

    /** 8 s of a steady 1 kHz tone (amplitude 0.01) over the background: no event, the floor is the tone. */
    const val TONE_AMPLITUDE = 0.01

    private fun noise(seconds: Double, rms: Double, seed: Int) = TestSignals.whiteNoise(rms, seconds, seed = seed, fs = RATE)

    private fun filtered(x: FloatArray, vararg sections: Biquad): FloatArray =
        FloatArray(x.size) { i -> var v = x[i].toDouble(); for (s in sections) v = s.process(v); v.toFloat() }

    private fun rmsOf(x: FloatArray) = sqrt(x.sumOf { it.toDouble() * it } / x.size)

    fun hump(): FloatArray {
        val total = HUMP_START_S + HUMP_LENGTH_S + 3.0
        val bg = noise(total, BG_RMS, 11)
        val band = filtered(
            noise(HUMP_LENGTH_S, 1.0, 12),
            Butterworth.highpass(300.0, RATE), Butterworth.highpass(300.0, RATE),
            Butterworth.lowpass(3000.0, RATE), Butterworth.lowpass(3000.0, RATE),
        )
        val scale = BG_RMS * sqrt(HUMP_PEAK_RATIO) / rmsOf(band)
        val start = (HUMP_START_S * RATE).toInt()
        for (i in band.indices) {
            // Power envelope sin²: amplitude envelope sin.
            val env = sin(PI * i / band.size)
            bg[start + i] += (band[i] * scale * env).toFloat()
        }
        return bg
    }

    fun thump(): FloatArray {
        val total = THUMP_START_S + THUMP_LENGTH_S + 3.6
        val bg = noise(total, BG_RMS, 21)
        val low = filtered(
            noise(THUMP_LENGTH_S, 1.0, 22),
            Butterworth.lowpass(80.0, RATE), Butterworth.lowpass(80.0, RATE), Butterworth.highpass(25.0, RATE),
        )
        val scale = 0.05 / rmsOf(low)
        val start = (THUMP_START_S * RATE).toInt()
        for (i in low.indices) {
            val env = 0.5 - 0.5 * kotlin.math.cos(2 * PI * i / low.size)
            bg[start + i] += (low[i] * scale * env).toFloat()
        }
        return bg
    }

    fun tone(): FloatArray {
        val bg = noise(8.0, BG_RMS, 31)
        for (i in bg.indices) bg[i] += (TONE_AMPLITUDE * sin(2 * PI * 1000.0 * i / RATE)).toFloat()
        return bg
    }

    val all: Map<String, () -> FloatArray> = linkedMapOf("hump" to ::hump, "wind_thump" to ::thump, "steady_tone" to ::tone)
}

/** CLI: `./gradlew :dsp:replay --args="[--excess dB] [--window s] [--min-history s] file.wav …"`. */
object ReplayMain {
    @JvmStatic
    fun main(args: Array<String>) {
        var excess = EngineConfig.DEFAULT_EVENT_EXCESS_DB
        var window = EngineConfig.DEFAULT_LOCAL_FLOOR_WINDOW_SECONDS
        var minHistory = 5.0
        val files = ArrayList<File>()
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--excess" -> excess = args[++i].toDouble()
                "--window" -> window = args[++i].toDouble()
                "--min-history" -> minHistory = args[++i].toDouble()
                else -> files += File(args[i])
            }
            i++
        }
        require(files.isNotEmpty()) { "usage: replay [--excess dB] [--window s] [--min-history s] file.wav …" }
        for (f in files) {
            val r = Replay.runWav(f, Replay.config(excess, window, minHistory))
            println("# ${f.path}: ${r.events.size} events (${r.events.count { it.wind }} wind)")
            print(r.eventsCsv())
            print(r.minutesCsv())
        }
    }
}

/**
 * Batch replay of Labor clips (test utility, nothing is written into the repository): reads the
 * Labor `manifest.jsonl`, replays every clip in its clips directory once per (excess, floor)
 * setting, picks the v2 event that overlaps the manifest's event span most (ties: the louder one)
 * and writes one CSV row per clip and setting.
 *
 * ```
 * ./gradlew :dsp:replayBatch --args="--manifest /path/manifest.jsonl --clips /path/clips --out /tmp/v2.csv \
 *     [--excess 6.5,5,8] [--floor 30,20] [--min-history 3] [--window 30]"
 * ```
 */
object ReplayBatch {
    private class Clip(val file: String, val eventId: Long?, val offsetS: Double, val durationS: Double)

    @JvmStatic
    fun main(args: Array<String>) {
        var manifest: File? = null
        var clips: File? = null
        var out: File? = null
        var excess = listOf(EngineConfig.DEFAULT_EVENT_EXCESS_DB)
        var floors = listOf(EngineConfig.DEFAULT_EVENT_MIN_LEVEL_DB)
        var minHistory = 3.0
        var window = EngineConfig.DEFAULT_LOCAL_FLOOR_WINDOW_SECONDS
        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--manifest" -> manifest = File(args[++i])
                "--clips" -> clips = File(args[++i])
                "--out" -> out = File(args[++i])
                "--excess" -> excess = args[++i].split(",").map { it.toDouble() }
                "--floor" -> floors = args[++i].split(",").map { it.toDouble() }
                "--min-history" -> minHistory = args[++i].toDouble()
                "--window" -> window = args[++i].toDouble()
                else -> error("unknown argument ${args[i]}")
            }
            i++
        }
        requireNotNull(manifest) { "--manifest missing" }; requireNotNull(clips) { "--clips missing" }; requireNotNull(out) { "--out missing" }
        val list = manifest.readLines().mapNotNull { line ->
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line) }.getOrNull() as? kotlinx.serialization.json.JsonObject
                ?: return@mapNotNull null
            fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (s("type") != "clip") return@mapNotNull null
            Clip(
                s("file")!!.substringAfterLast('/'), s("eventId")?.toLongOrNull(),
                s("offsetOfEventStartInClipMs")!!.toDouble() / 1000, s("durationS")?.toDoubleOrNull() ?: 0.0,
            )
        }
        val settings = excess.flatMap { e -> floors.map { f -> e to f } }
        val header = listOf(
            "clip", "event_id", "excess_setting", "floor_setting", "detected", "n_v2_events", "manifest_start_s", "manifest_dur_s",
            "v2_start_s", "v2_dur_s", "v2_lafmax_db", "local_floor_db", "excess_db", "rise_s", "decay_s", "jaggedness",
            "mid_band_rise_db", "lf_share", "lf_flutter_db", "wind", "shape",
        )
        val t0 = System.nanoTime()
        val rows = list.parallelStream().map { c ->
            val f = File(clips, c.file)
            if (!f.isFile) return@map listOf("${c.file},${c.eventId ?: ""},,,missing")
            val (x, rate) = Wav.read(f)
            val signal = if (rate == 16_000) Upsampler3.process(x) else x
            val clipS = signal.size / 48_000.0
            val mStart = c.offsetS
            val mEnd = minOf(c.offsetS + c.durationS, clipS)
            settings.map { (e, fl) ->
                val cfg = Replay.config(e, window, minHistory).copy(eventMinLevelDb = fl)
                val r = Replay.run(signal, cfg)
                fun overlap(ev: NoiseEvent): Double {
                    val s = (ev.startEpochMs - Replay.START_MS) / 1000.0
                    return minOf(mEnd, s + ev.durationSeconds) - maxOf(mStart, s)
                }
                // A zero-length manifest span (truncated/odd) still matches an event containing its start.
                val best = r.events.filter { overlap(it) >= 0.0 && (overlap(it) > 0 || mEnd <= mStart) }
                    .maxWithOrNull(compareBy<NoiseEvent>({ overlap(it) }, { it.lafMaxDb }))
                val ft = best?.features
                listOf(
                    c.file, c.eventId?.toString() ?: "", Csv.num(e), Csv.num(fl), (best != null).toString(), r.events.size.toString(),
                    Csv.num(mStart, 3), Csv.num(c.durationS, 3),
                    best?.let { Csv.num((it.startEpochMs - Replay.START_MS) / 1000.0, 3) } ?: "", best?.let { Csv.num(it.durationSeconds, 3) } ?: "",
                    best?.let { Csv.num(it.lafMaxDb, 2) } ?: "", ft?.let { Csv.num(it.localFloorDb, 2) } ?: "", ft?.let { Csv.num(it.excessDb, 2) } ?: "",
                    ft?.let { Csv.num(it.riseS, 3) } ?: "", ft?.let { Csv.num(it.decayS, 3) } ?: "", ft?.let { Csv.num(it.jaggedness, 4) } ?: "",
                    ft?.let { Csv.num(it.midBandRiseDb, 2) } ?: "", ft?.let { Csv.num(it.lfShare, 4) } ?: "", ft?.let { Csv.num(it.lfFlutterDb, 3) } ?: "",
                    ft?.wind?.toString() ?: "", ft?.shape ?: "",
                ).joinToString(",")
            }
        }.toList().flatten()
        out.parentFile?.mkdirs()
        out.writeText((listOf(header.joinToString(",")) + rows).joinToString("\n", postfix = "\n"))
        println("${list.size} clips x ${settings.size} settings -> ${out.path} in ${(System.nanoTime() - t0) / 1_000_000_000} s")
    }
}

/**
 * Whole-night replay of the Labor build's continuous recording (test utility; audio stays where
 * it is): the hour files, decoded beforehand to 16 kHz mono WAVs named like the M4As
 * (`ffmpeg -i 20261007_23.m4a -ac 1 -ar 16000 20261007_23.wav`), are placed on the session's
 * input-sample axis with the manifest's `continuous_open` lines (16 kHz sample j = input sample
 * startInputSample + 3·j + 2; [skip] decoder-priming samples are dropped from the start of each
 * file — 2049 for MediaCodec AAC decoded by ffmpeg, found by cross-correlating clips), gaps are
 * filled with silence, and the stream is fed through one engine per (excess, floor) setting.
 * Writes `<out>.events.csv` (every v2 event, with the manifest event it overlaps most) and
 * `<out>.clips.csv` (every manifest clip event with the v2 event overlapping it most).
 *
 * ```
 * ./gradlew :dsp:replayNight --args="--manifest m.jsonl --wavs dir --session 20261007_220455 --out /tmp/night \
 *     [--excess 6.5,5,8] [--floor 30,20] [--skip 2049]"
 * ```
 */
object ReplayNight {
    private class Open(val file: String, val startInputSample: Long)
    private class Ev(val id: Long, val start: Long, val end: Long)
    private class V2(val start: Long, val end: Long, val event: NoiseEvent)

    @JvmStatic
    fun main(args: Array<String>) {
        val a = HashMap<String, String>()
        var i = 0
        while (i < args.size) { a[args[i].removePrefix("--")] = args[i + 1]; i += 2 }
        val session = a.getValue("session")
        val wavs = File(a.getValue("wavs"))
        val out = a.getValue("out")
        val skip = (a["skip"] ?: "2049").toInt()
        val excess = (a["excess"] ?: "6.5").split(",").map { it.toDouble() }
        val floors = (a["floor"] ?: "30").split(",").map { it.toDouble() }
        val window = (a["window"] ?: "30").toDouble()
        val opens = ArrayList<Open>()
        val clipEvents = ArrayList<Ev>()
        var anchorMs = Replay.START_MS
        for (line in File(a.getValue("manifest")).readLines()) {
            val o = runCatching { kotlinx.serialization.json.Json.parseToJsonElement(line) }.getOrNull() as? kotlinx.serialization.json.JsonObject ?: continue
            fun s(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (s("session") != session) continue
            when (s("type")) {
                "continuous_open" -> opens += Open(s("file")!!.substringAfterLast('/').removeSuffix(".m4a"), s("startInputSample")!!.toLong())
                "clip" -> clipEvents += Ev(s("eventId")!!.toLong(), s("eventStartSample")!!.toLong(), s("eventEndSample")!!.toLong())
                "clock" -> if (s("correctionMs") == null) anchorMs = s("anchorEpochMs")!!.toLong() - s("anchorSample")!!.toLong() / 48
            }
        }
        val settings = excess.flatMap { e -> floors.map { f -> e to f } }
        val results = settings.parallelStream().map { (e, f) -> Triple(e, f, run(opens, wavs, skip, e, f, window, anchorMs)) }.toList()
        val ev = StringBuilder("excess_setting,floor_setting,start_sample,end_sample,start,matched_event_id," +
            "duration_s,lafmax_db,background_db," + Csv.EVENT_FEATURE_COLUMNS.joinToString(",") + "\n")
        val cl = StringBuilder("event_id,excess_setting,floor_setting,detected,v2_start_sample,v2_dur_s,v2_lafmax_db," +
            Csv.EVENT_FEATURE_COLUMNS.joinToString(",") + "\n")
        for ((e, f, v2) in results) {
            fun overlap(x: V2, c: Ev) = minOf(x.end, c.end) - maxOf(x.start, c.start)
            for (x in v2) {
                val m = clipEvents.filter { overlap(x, it) > 0 }.maxByOrNull { overlap(x, it) }
                ev.append("${Csv.num(e)},${Csv.num(f)},${x.start},${x.end},${x.event.startIso},${m?.id ?: ""},")
                    .append("${Csv.num(x.event.durationSeconds, 3)},${Csv.num(x.event.lafMaxDb, 2)},${Csv.num(x.event.backgroundDb, 2)},")
                    .append(Csv.featureFields(x.event).joinToString(",")).append("\n")
            }
            for (c in clipEvents) {
                // Manifest events of 0 samples (none expected) still match an event containing their start.
                val best = v2.filter { overlap(it, c) > 0 || (c.end <= c.start && c.start in it.start until it.end) }
                    .maxWithOrNull(compareBy<V2>({ overlap(it, c) }, { it.event.lafMaxDb }))
                cl.append("${c.id},${Csv.num(e)},${Csv.num(f)},${best != null},${best?.start ?: ""},")
                    .append("${best?.let { Csv.num(it.event.durationSeconds, 3) } ?: ""},${best?.let { Csv.num(it.event.lafMaxDb, 2) } ?: ""},")
                    .append(best?.let { Csv.featureFields(it.event).joinToString(",") } ?: List(Csv.EVENT_FEATURE_COLUMNS.size) { "" }.joinToString(","))
                    .append("\n")
            }
        }
        File("$out.events.csv").writeText(ev.toString())
        File("$out.clips.csv").writeText(cl.toString())
        println("${settings.size} settings: ${results.map { it.third.size }} v2 events; ${clipEvents.size} manifest events -> $out.*.csv")
    }

    private fun run(opens: List<Open>, wavs: File, skip: Int, excess: Double, floor: Double, window: Double, anchorMs: Long): List<V2> {
        val found = ArrayList<V2>()
        val clock = SimClock(anchorMs)
        val cfg = Replay.config(excess, window, 5.0).copy(eventMinLevelDb = floor)
        val engine = MeasurementEngine(cfg, null, object : MeasurementEngine.Listener {
            override fun onEventEmitted(event: NoiseEvent, startSample: Long, endSample: Long) { found += V2(startSample, endSample, event) }
        }, clock::now)
        val block = FloatArray(6000)
        var pos = 0L // input samples fed so far
        fun feed(buf: FloatArray, n: Int) {
            var p = 0
            while (p < n) {
                val k = minOf(6000, n - p)
                System.arraycopy(buf, p, block, 0, k)
                clock.advance(k); engine.process(block, k); p += k
            }
            pos += n
        }
        val chunk = FloatArray(48_000 * 10)
        for (o in opens) {
            val f = File(wavs, "${o.file}.wav")
            if (!f.isFile) { System.err.println("missing ${f.path}"); continue }
            val x = Wav.read(f).first
            // 48 kHz output index of decoded sample d (after the priming skip): 3·(d − skip) + 2 + startInputSample.
            val firstOut = o.startInputSample + 2
            if (firstOut > pos) { // gap: silence (treated as invalid audio by the engine)
                java.util.Arrays.fill(chunk, 0f)
                while (pos < firstOut) feed(chunk, minOf(chunk.size.toLong(), firstOut - pos).toInt())
            }
            val total = (x.size - skip) * 3
            var o48 = (pos - firstOut).toInt().coerceAtLeast(0) // overlap with the previous file is dropped
            val xs = x.copyOfRange(skip, x.size)
            while (o48 < total) {
                val n = minOf(chunk.size, total - o48)
                Upsampler3.process(xs, o48, o48 + n, chunk)
                feed(chunk, n)
                o48 += n
            }
        }
        engine.stop()
        return found
    }
}
