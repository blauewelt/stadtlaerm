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
 * 16 → 48 kHz polyphase interpolation: zero-stuffing ×3, then the decimator's own anti-aliasing
 * FIR (241 taps, Kaiser β = 7, 7.5 kHz) with gain 3, evaluated per output phase (≈ 80 taps per
 * output sample); the group delay (120 samples at 48 kHz) is removed, so output sample 3·j is
 * input sample j.
 */
object Upsampler3 {
    private val taps = DecimatingResampler().taps

    fun process(x: FloatArray): FloatArray {
        val n = taps.size
        val delay = (n - 1) / 2
        val out = FloatArray(x.size * 3)
        for (o in out.indices) {
            val t = o + delay // index in the delayed (causal) output
            var acc = 0.0
            // y[t] = 3 · Σ_j h[j] · u[t − j], u[3m] = x[m], else 0.
            var j = t % 3
            while (j < n) {
                val m = (t - j) / 3
                if (m in x.indices) acc += taps[j] * x[m]
                j += 3
            }
            out[o] = (3 * acc).toFloat()
        }
        return out
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
