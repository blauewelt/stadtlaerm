package ch.stadtlaerm.app.labor

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.math.roundToInt

// LABOR BUILD ONLY (app/src/labor/). Not part of the public app; see PRIVACY.md → «Labor-Build».

/** Minimal RIFF/WAVE writer: mono, 16-bit PCM. */
object Wav {
    const val HEADER_BYTES = 44

    /** Float sample (nominal ±1) to 16-bit PCM: ×32767, rounded, clipped to [−32768, 32767]. */
    fun toPcm16(x: Float): Short {
        if (x.isNaN()) return 0
        val v = (x * 32767f).roundToInt()
        return v.coerceIn(-32768, 32767).toShort()
    }

    /** The 44-byte canonical header for [dataBytes] of PCM data. */
    fun header(sampleRate: Int, dataBytes: Int, channels: Int = 1, bitsPerSample: Int = 16): ByteArray {
        val b = ByteArray(HEADER_BYTES)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
        fun le32(at: Int, v: Int) { for (i in 0 until 4) b[at + i] = (v ushr (8 * i)).toByte() }
        fun le16(at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v ushr 8).toByte() }
        val blockAlign = channels * bitsPerSample / 8
        ascii(0, "RIFF"); le32(4, 36 + dataBytes); ascii(8, "WAVE")
        ascii(12, "fmt "); le32(16, 16); le16(20, 1) // PCM
        le16(22, channels); le32(24, sampleRate); le32(28, sampleRate * blockAlign)
        le16(32, blockAlign); le16(34, bitsPerSample)
        ascii(36, "data"); le32(40, dataBytes)
        return b
    }

    fun fileBytes(sampleCount: Int): Long = HEADER_BYTES + 2L * sampleCount

    fun write(out: OutputStream, samples: FloatArray, count: Int, sampleRate: Int) {
        out.write(header(sampleRate, count * 2))
        val buf = ByteArray(8192)
        var p = 0
        for (i in 0 until count) {
            val s = toPcm16(samples[i]).toInt()
            buf[p++] = s.toByte(); buf[p++] = (s ushr 8).toByte()
            if (p == buf.size) { out.write(buf); p = 0 }
        }
        if (p > 0) out.write(buf, 0, p)
    }

    /** Writes [file] atomically (temporary file, then rename), so a crash never leaves half a clip. */
    fun write(file: File, samples: FloatArray, count: Int, sampleRate: Int) {
        val tmp = File(file.parentFile, file.name + ".part")
        FileOutputStream(tmp).use { fos ->
            BufferedOutputStream(fos).use { write(it, samples, count, sampleRate) }
        }
        if (!tmp.renameTo(file)) { tmp.delete(); throw java.io.IOException("rename failed") }
    }
}
