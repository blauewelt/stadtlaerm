package ch.stadtlaerm.app.labor

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

// LABOR BUILD ONLY (app/src/labor/).

/**
 * One continuous-recording file: 16 kHz mono PCM → AAC-LC 64 kbit/s (MediaCodec) → MP4/M4A
 * (MediaMuxer). The MP4 index is written by [close]; a process that dies before that loses only
 * this file. Presentation time of sample j is j/16000 s. Call from one thread only.
 *
 * @param account called with the size of every encoded packet; return false to stop (storage cap).
 */
class AacFileWriter(
    val file: File,
    private val account: (Long) -> Boolean,
    val sampleRate: Int = 16_000,
    val bitRate: Int = 64_000,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var muxerStarted = false
    private val info = MediaCodec.BufferInfo()
    var samplesWritten = 0L
        private set
    var bytesWritten = 0L
        private set
    /** The storage cap was hit while writing this file. */
    var capReached = false
        private set

    init {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        try {
            codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            try { codec.release() } catch (_: Exception) {}
            try { muxer.release() } catch (_: Exception) {}
            file.delete()
            throw e
        }
    }

    /** Encodes [count] float samples (16 kHz) of [samples]. */
    fun write(samples: FloatArray, count: Int) {
        var p = 0
        while (p < count) {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx < 0) { drain(false); continue }
            val buf = codec.getInputBuffer(idx)!!
            buf.clear()
            buf.order(ByteOrder.LITTLE_ENDIAN)
            val n = minOf(count - p, buf.remaining() / 2)
            for (i in 0 until n) buf.putShort(Wav.toPcm16(samples[p + i]))
            val ptsUs = samplesWritten * 1_000_000L / sampleRate
            codec.queueInputBuffer(idx, 0, n * 2, ptsUs, 0)
            samplesWritten += n
            p += n
            drain(false)
        }
    }

    private fun drain(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val idx = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                // At the end of the stream wait at most ~1 s for the encoder's last packets.
                idx == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream || ++waits > 100) return
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start(); muxerStarted = true
                }
                idx >= 0 -> {
                    val out = codec.getOutputBuffer(idx)!!
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (!config && info.size > 0 && muxerStarted) {
                        out.position(info.offset); out.limit(info.offset + info.size)
                        muxer.writeSampleData(track, out, info)
                        bytesWritten += info.size
                        if (!account(info.size.toLong())) capReached = true
                    }
                    codec.releaseOutputBuffer(idx, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Ends the stream and writes the MP4 index. Safe to call once; releases everything. */
    fun close() {
        try {
            val idx = codec.dequeueInputBuffer(100_000)
            if (idx >= 0) {
                codec.queueInputBuffer(idx, 0, 0, samplesWritten * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                drain(true)
            }
        } catch (_: Exception) {
        }
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { if (muxerStarted) muxer.stop() } catch (_: Exception) {}
        try { muxer.release() } catch (_: Exception) {}
        // An empty file (nothing encoded) would not be a valid MP4.
        if (!muxerStarted) file.delete()
    }
}
