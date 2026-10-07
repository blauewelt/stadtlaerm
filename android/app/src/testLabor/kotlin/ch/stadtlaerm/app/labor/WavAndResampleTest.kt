package ch.stadtlaerm.app.labor

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WavAndResampleTest {

    private fun le(bytes: ByteArray) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    private fun ascii(b: ByteArray, at: Int) = String(b, at, 4, Charsets.US_ASCII)

    @Test
    fun headerFieldsAndSampleCount() {
        val x = FloatArray(1000) { (it % 7 - 3) / 10f }
        val out = ByteArrayOutputStream()
        Wav.write(out, x, 999, 16_000) // count < size: only 999 samples
        val b = out.toByteArray()
        assertEquals(44 + 999 * 2, b.size)
        assertEquals(Wav.fileBytes(999), b.size.toLong())
        val h = le(b)
        assertEquals("RIFF", ascii(b, 0)); assertEquals(36 + 999 * 2, h.getInt(4)); assertEquals("WAVE", ascii(b, 8))
        assertEquals("fmt ", ascii(b, 12)); assertEquals(16, h.getInt(16)); assertEquals(1, h.getShort(20).toInt())
        assertEquals(1, h.getShort(22).toInt()) // mono
        assertEquals(16_000, h.getInt(24)); assertEquals(32_000, h.getInt(28)) // byte rate
        assertEquals(2, h.getShort(32).toInt()); assertEquals(16, h.getShort(34).toInt())
        assertEquals("data", ascii(b, 36)); assertEquals(999 * 2, h.getInt(40))
        // Samples follow in order, little endian.
        for (i in 0 until 999) assertEquals(Wav.toPcm16(x[i]), h.getShort(44 + 2 * i), "sample $i")
    }

    @Test
    fun scalingAndClipping() {
        assertEquals(0, Wav.toPcm16(0f).toInt())
        assertEquals(32767, Wav.toPcm16(1f).toInt())
        assertEquals(-32767, Wav.toPcm16(-1f).toInt())
        assertEquals(16384, Wav.toPcm16(0.5f).toInt()) // 16383.5 rounds up
        assertEquals(-16383, Wav.toPcm16(-0.5f).toInt()) // −16383.5 rounds towards +∞
        assertEquals(1, Wav.toPcm16(1f / 32767f).toInt())
        // Clipping, not wrap-around.
        assertEquals(32767, Wav.toPcm16(1.5f).toInt())
        assertEquals(-32768, Wav.toPcm16(-2f).toInt())
        assertEquals(32767, Wav.toPcm16(Float.POSITIVE_INFINITY).toInt())
        assertEquals(-32768, Wav.toPcm16(Float.NEGATIVE_INFINITY).toInt())
        assertEquals(0, Wav.toPcm16(Float.NaN).toInt())
    }

    @Test
    fun fileWriteIsAtomic() {
        val dir = java.nio.file.Files.createTempDirectory("wav").toFile()
        try {
            val f = File(dir, "ev_1_20261007_120000.wav")
            Wav.write(f, FloatArray(160) { 0.25f }, 160, 16_000)
            assertEquals(44L + 320, f.length())
            assertFalse(File(dir, f.name + ".part").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resamplerDelayIsCompensated() {
        // An impulse at input sample 3·j + 2 must come out at output sample j.
        for (j in listOf(0, 1, 57, 1000)) {
            val n = 48_000
            val x = FloatArray(n); x[3 * j + 2] = 1f
            val y = Resample16k.convert(listOf(x), n)
            assertEquals((n + 120) / 3 - 40, y.size)
            val peak = y.indices.maxBy { abs(y[it]) }
            assertEquals(j, peak, "impulse at ${3 * j + 2}")
        }
    }

    @Test
    fun resamplerChunkedEqualsWholeAndKeepsLevel() {
        val fs = 48_000
        val x = FloatArray(fs) { (0.5 * sin(2 * PI * 1000.0 * it / fs)).toFloat() }
        val whole = Resample16k.convert(listOf(x), x.size)
        val chunks = x.toList().chunked(7_000) { it.toFloatArray() }
        val chunked = Resample16k.convert(chunks, x.size)
        assertEquals(whole.size, chunked.size)
        for (i in whole.indices) assertEquals(whole[i], chunked[i], 1e-6f)
        // 1 kHz passes the anti-alias filter unchanged (RMS 0.5/√2), away from the edges.
        var s = 0.0
        for (i in 1000 until 15_000) s += whole[i].toDouble() * whole[i]
        val rms = sqrt(s / 14_000)
        assertTrue(abs(rms - 0.5 / sqrt(2.0)) < 1e-3, "rms $rms")
    }

    @Test
    fun streamingResamplerMatchesConvert() {
        val x = FloatArray(30_000) { ((it * 7919) % 2000 - 1000) / 1000f }
        val r = Resample16k(6_000)
        val out = ArrayList<Float>()
        val tmp = FloatArray(6_000 / 3 + 2)
        for (p in 0 until 30_000 step 6_000) {
            val n = r.process(x.copyOfRange(p, p + 6_000), 6_000, tmp)
            for (i in 0 until n) out += tmp[i]
        }
        val n = r.flush(tmp)
        for (i in 0 until n) out += tmp[i]
        val ref = Resample16k.convert(listOf(x), x.size)
        assertEquals(ref.size, out.size)
        for (i in ref.indices) assertEquals(ref[i], out[i], 1e-6f)
    }
}
