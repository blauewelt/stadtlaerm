package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.classify.CategoryMapper
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

object TestSignals {
    const val FS = Acoustics.SAMPLE_RATE

    fun sine(freq: Double, amplitude: Double, seconds: Double, fs: Int = FS, phase: Double = 0.0): FloatArray =
        FloatArray((seconds * fs).toInt()) { (amplitude * sin(2 * PI * freq * it / fs + phase)).toFloat() }

    fun whiteNoise(rms: Double, seconds: Double, seed: Int = 1, fs: Int = FS): FloatArray {
        val r = Random(seed)
        // Uniform in [-a, a] has rms a/sqrt(3).
        val a = rms * kotlin.math.sqrt(3.0)
        return FloatArray((seconds * fs).toInt()) { ((r.nextDouble() * 2 - 1) * a).toFloat() }
    }

    fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var p = 0
        for (a in parts) { a.copyInto(out, p); p += a.size }
        return out
    }

    fun add(a: FloatArray, b: FloatArray): FloatArray = FloatArray(a.size) { a[it] + b[it] }

    fun assetsDir(): File = File(System.getProperty("stadtlaerm.assets") ?: "../app/src/main/assets")

    fun labels(): List<String> = CategoryMapper.parseLabels(File(assetsDir(), "yamnet_labels.txt").readText())

    fun mapper(): CategoryMapper = CategoryMapper.fromJson(File(assetsDir(), "categories.json").readText(), labels())

    /** Feeds a signal to the engine in realistic 125 ms blocks, advancing [clock] per block. */
    fun feed(engine: MeasurementEngine, signal: FloatArray, block: Int = 6000, clock: SimClock? = null) {
        var p = 0
        val buf = FloatArray(block)
        while (p < signal.size) {
            val n = minOf(block, signal.size - p)
            signal.copyInto(buf, 0, p, p + n)
            clock?.advance(n)
            engine.process(buf, n)
            p += n
        }
    }
}

/**
 * Simulated wall clock: the time at which a block is delivered = start + samples delivered so far
 * (including that block), optionally running at [rate] relative to the audio clock.
 */
class SimClock(private val startMs: Long, var rate: Double = 1.0, private val fs: Int = Acoustics.SAMPLE_RATE) {
    private var elapsedMs = 0.0
    var extraMs = 0L

    fun advance(samples: Int) {
        elapsedMs += samples * 1000.0 / fs * rate
    }

    fun now(): Long = startMs + Math.round(elapsedMs) + extraMs
}
