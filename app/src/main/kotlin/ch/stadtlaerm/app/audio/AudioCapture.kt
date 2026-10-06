package ch.stadtlaerm.app.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import android.util.Log
import ch.stadtlaerm.dsp.Acoustics

/** Which microphone path is used. Calibration is stored per source. */
object AudioSourceSelector {
    const val UNPROCESSED = "UNPROCESSED"
    const val VOICE_RECOGNITION = "VOICE_RECOGNITION"

    fun unprocessedSupported(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    /** UNPROCESSED if the device declares support for it, else VOICE_RECOGNITION. */
    fun select(context: Context): String =
        if (unprocessedSupported(context)) UNPROCESSED else VOICE_RECOGNITION

    fun androidSource(name: String): Int = when (name) {
        UNPROCESSED -> MediaRecorder.AudioSource.UNPROCESSED
        else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    fun labelDe(name: String): String = when (name) {
        UNPROCESSED -> "UNPROCESSED (unbearbeitet)"
        VOICE_RECOGNITION -> "VOICE_RECOGNITION (Fallback)"
        else -> name
    }
}

data class CaptureInfo(
    val source: String,
    val sampleRate: Int,
    val encoding: String,
    /** e.g. "AGC: deaktiviert", "NS: nicht vorhanden". */
    val effects: List<String>,
)

/**
 * Microphone capture on a dedicated thread: 48 kHz, mono, PCM float (16-bit fallback).
 *
 * PRIVACY: audio exists only in the reusable [FloatArray] block (125 ms) handed to [onBlock]
 * and in AudioRecord's own internal buffer. Nothing is written to disk, logged or sent anywhere.
 * [onBlock] must consume the samples synchronously; the array is overwritten by the next read.
 */
class AudioCapture(
    private val context: Context,
    private val onBlock: (samples: FloatArray, count: Int) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    companion object {
        private const val TAG = "AudioCapture"
        const val BLOCK = Acoustics.SAMPLE_RATE / 8 // 125 ms
    }

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null
    private val effects = ArrayList<AudioEffect>()

    @SuppressLint("MissingPermission") // checked by the caller before starting
    fun start(sourceName: String): CaptureInfo {
        check(!running) { "already running" }
        val source = AudioSourceSelector.androidSource(sourceName)
        var encoding = AudioFormat.ENCODING_PCM_FLOAT
        var rec = build(source, encoding)
        if (rec == null) {
            encoding = AudioFormat.ENCODING_PCM_16BIT
            rec = build(source, encoding) ?: throw IllegalStateException("AudioRecord konnte nicht geöffnet werden")
        }
        record = rec
        val fx = disableEffects(rec.audioSessionId)
        val info = CaptureInfo(
            source = sourceName,
            sampleRate = rec.sampleRate,
            encoding = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) "PCM_FLOAT" else "PCM_16BIT",
            effects = fx,
        )
        try {
            if (rec.sampleRate != Acoustics.SAMPLE_RATE) {
                throw IllegalStateException("Abtastrate ${rec.sampleRate} Hz statt 48 kHz")
            }
            rec.startRecording()
            if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Mikrofon ist belegt (Aufnahme startet nicht)")
            }
        } catch (e: Exception) {
            // Release everything: the caller only keeps a reference after a successful start.
            effects.forEach { try { it.release() } catch (_: Exception) {} }
            effects.clear()
            try { rec.stop() } catch (_: Exception) {}
            rec.release(); record = null
            throw e
        }
        running = true
        val isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
        thread = Thread({ loop(rec, isFloat) }, "stadtlaerm-capture").apply { start() }
        return info
    }

    @SuppressLint("MissingPermission")
    private fun build(source: Int, encoding: Int): AudioRecord? {
        val fmt = AudioFormat.Builder()
            .setEncoding(encoding)
            .setSampleRate(Acoustics.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build()
        val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
        val min = AudioRecord.getMinBufferSize(Acoustics.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, encoding)
        if (min <= 0) return null
        // ~0.5 s of internal buffer gives slack if the processing thread is briefly delayed.
        val size = maxOf(min, Acoustics.SAMPLE_RATE / 2 * bytesPerSample)
        return try {
            val r = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(fmt)
                .setBufferSizeInBytes(size)
                .build()
            if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord build failed: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Explicitly disables platform AGC / noise suppression / echo cancellation if present. */
    private fun disableEffects(session: Int): List<String> {
        val out = ArrayList<String>()
        fun handle(name: String, available: Boolean, create: () -> AudioEffect?) {
            if (!available) { out += "$name: nicht vorhanden"; return }
            try {
                val e = create()
                if (e == null) { out += "$name: nicht erstellbar"; return }
                e.enabled = false
                effects += e
                out += "$name: deaktiviert" + if (e.enabled) " (fehlgeschlagen!)" else ""
            } catch (ex: Exception) {
                out += "$name: Fehler"
            }
        }
        handle("AGC", AutomaticGainControl.isAvailable()) { AutomaticGainControl.create(session) }
        handle("NS", NoiseSuppressor.isAvailable()) { NoiseSuppressor.create(session) }
        handle("AEC", AcousticEchoCanceler.isAvailable()) { AcousticEchoCanceler.create(session) }
        return out
    }

    private fun loop(rec: AudioRecord, isFloat: Boolean) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val buf = FloatArray(BLOCK)
        val shorts = if (isFloat) null else ShortArray(BLOCK)
        try {
            while (running) {
                var filled = 0
                while (filled < BLOCK && running) {
                    val n = if (isFloat) {
                        rec.read(buf, filled, BLOCK - filled, AudioRecord.READ_BLOCKING)
                    } else {
                        rec.read(shorts!!, filled, BLOCK - filled, AudioRecord.READ_BLOCKING).also { k ->
                            for (i in filled until filled + maxOf(k, 0)) buf[i] = shorts[i] / 32768f
                        }
                    }
                    if (n < 0) throw IllegalStateException("AudioRecord.read Fehler $n")
                    filled += n
                }
                if (filled == BLOCK) onBlock(buf, BLOCK)
            }
        } catch (e: Exception) {
            if (running) onError(e.message ?: e.javaClass.simpleName)
        } finally {
            buf.fill(0f)
        }
    }

    fun stop() {
        running = false
        try { record?.stop() } catch (_: Exception) {}
        thread?.join(2000)
        thread = null
        effects.forEach { try { it.release() } catch (_: Exception) {} }
        effects.clear()
        record?.release()
        record = null
    }
}
