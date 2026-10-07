package ch.stadtlaerm.app.labor

import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import ch.stadtlaerm.app.BuildConfig
import ch.stadtlaerm.app.audio.AudioTap
import ch.stadtlaerm.app.audio.AudioTapSession
import ch.stadtlaerm.app.data.CalibrationRepository
import ch.stadtlaerm.dsp.NoiseEvent
import ch.stadtlaerm.dsp.classify.ClassifierResult
import kotlinx.coroutines.flow.update
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// LABOR BUILD ONLY (app/src/labor/). This is the only code of the project that writes audio.
// It is not compiled into the public app; see PRIVACY.md → «Labor-Build».

/**
 * The Labor build's audio recorder: event clips (WAV, 16 kHz) and/or a continuous recording
 * (AAC-LC 64 kbit/s, one M4A per wall-clock hour), plus `manifest.jsonl` (with the classifier's
 * per-second results for every clip) and `classifier/<yyyyMMdd_HH>.jsonl` (every classifier run of
 * the measurement), all under getExternalFilesDir(null)/audio/.
 *
 * Threads: the capture thread only copies (ring buffer, clip assembly, one block copy into a
 * bounded queue) and never waits; if a queue is full the data is dropped and counted. Resampling,
 * WAV writing and AAC encoding run on two background-priority threads, so they cannot starve the
 * measurement. One instance per measurement session.
 */
class LaborRecorder(context: Context, private val settings: LaborSettings) : AudioTap {

    companion object {
        private const val TAG = "LaborRecorder"
        /** Completed clips waiting for the writer (each ≤ 60 s at 48 kHz ≈ 11.5 MB). */
        const val MAX_QUEUED_CLIPS = 2
        /** Capture blocks (125 ms) waiting for the AAC encoder: 20 s. */
        const val AAC_QUEUE_BLOCKS = 160
        /** Longest gap (dropped blocks) that is filled with silence instead of starting a new file. */
        const val MAX_GAP_FILL_SAMPLES = 60L * 48_000
        /** After stop: how long the writer waits for the database ids of the last events. */
        const val STOP_WAIT_MS = 6_000L
        /**
         * A finished clip waits at most this long for the classifier result whose window ends at
         * or after the clip's end (classification runs once per second, inference takes a moment),
         * so that its trace is complete.
         */
        const val TRACE_WAIT_MS = 2_500L
        /** Classifier results kept for clip traces (a clip is at most 60 s, written ≤ 10 s later). */
        const val TRACE_KEEP_SAMPLES = 150L * 48_000
        private const val INPUT_RATE = 48_000
    }

    private val zone: ZoneId = ZoneId.systemDefault()
    private val dirs = Labor.dirs(context.applicationContext).also { it.ensure() }
    val sessionId: String = Instant.now().atZone(zone).format(HourRoller.SECOND_NAME)
    private val budget = StorageBudget(settings.maxBytes, dirs.usedBytes())
    private val sampler = ClipRateSampler(settings.clipEvery)

    @Volatile private var active = true
    private val stopped = AtomicBoolean(false)
    private val fullReported = AtomicBoolean(false)
    /** Input samples seen (capture thread). Same count as MeasurementEngine.totalSamples. */
    private var position = 0L
    @Volatile private var anchorSample = 0L
    @Volatile private var anchorEpochMs = Long.MIN_VALUE
    private var pendingCorrectionMs: Long? = null

    // Counters for the manifest.
    private val clipsWritten = AtomicLong()
    private val clipsDroppedQueue = AtomicLong()
    private val clipsDroppedStorage = AtomicLong()
    private val clipsWithoutId = AtomicLong()
    private val clipErrors = AtomicLong()
    private val continuousFiles = AtomicLong()
    private val aacBlocksDropped = AtomicLong()
    @Volatile private var droppedBusyFinal = 0L
    @Volatile private var skippedRateFinal = 0L

    // ---- Writer thread (manifest + clips) -------------------------------------------------

    private sealed interface Msg
    private class Line(val text: String) : Msg
    private class SessionStart(val s: AudioTapSession) : Msg
    private class ClipAudio(val clip: AssembledClip) : Msg
    private class Stored(val id: Long, val event: NoiseEvent, val startSample: Long) : Msg
    private class Cls(val result: ClassifierResult) : Msg
    private object AacDone : Msg
    private object Stop : Msg

    private val queue = LinkedBlockingQueue<Msg>()
    private val queuedClips = AtomicInteger()

    private val assembler: ClipAssembler? = if (settings.clips) {
        ClipAssembler(sampleRate = INPUT_RATE, accept = { sampler.next() }, sink = ::onClipAssembled)
    } else null

    // ---- AAC thread -----------------------------------------------------------------------

    private class Block(val startSample: Long, val data: FloatArray, val count: Int, val wallClockMs: Long)

    private val aacQueue: ArrayBlockingQueue<Block>? = if (settings.continuous) ArrayBlockingQueue(AAC_QUEUE_BLOCKS) else null
    @Volatile private var aacStopRequested = false

    private val writerThread = Thread(::writerLoop, "stadtlaerm-labor-writer").apply { start() }
    private val aacThread: Thread? = aacQueue?.let { q -> Thread({ aacLoop(q) }, "stadtlaerm-labor-aac").apply { start() } }

    init {
        Labor.currentSession = sessionId
        Labor.status.value = RecorderStatus(
            recording = settings.clips || settings.continuous, clips = settings.clips, continuous = settings.continuous,
        )
    }

    /** Status updates only while this is the newest session (an old one may still be finishing). */
    private fun status(f: (RecorderStatus) -> RecorderStatus) {
        if (Labor.currentSession == sessionId) Labor.status.update(f)
    }

    // ---- AudioTap (capture thread unless noted) -------------------------------------------

    override fun onSessionStart(session: AudioTapSession) {
        queue.offer(SessionStart(session))
    }

    override fun onBlock(samples: FloatArray, count: Int, sampleRate: Int, wallClockMs: Long) {
        val start = position
        position += count
        if (!active) return
        if (!Labor.settings.value.recordAudio) { deactivate(); return }
        if (budget.full) { storageFull(); return }
        assembler?.onBlock(samples, count)
        aacQueue?.let { q ->
            if (!q.offer(Block(start, samples.copyOf(count), count, wallClockMs))) aacBlocksDropped.incrementAndGet()
        }
    }

    override fun onClockAnchor(anchorSample: Long, anchorEpochMs: Long) {
        this.anchorSample = anchorSample
        this.anchorEpochMs = anchorEpochMs
        val corr = pendingCorrectionMs
        pendingCorrectionMs = null
        queue.offer(Line(ManifestLines.clock(sessionId, System.currentTimeMillis(), zone, anchorSample, anchorEpochMs, corr)))
    }

    override fun onClockCorrection(correctionMs: Long) {
        pendingCorrectionMs = correctionMs
    }

    override fun onEventStarted(startSample: Long) { if (active) assembler?.onCandidate(startSample) }
    override fun onEventConfirmed(startSample: Long) { if (active) assembler?.onConfirmed(startSample) }
    override fun onEventDiscarded(startSample: Long) { if (active) assembler?.onDiscarded(startSample) }
    override fun onEventEnded(startSample: Long, endSample: Long) { if (active) assembler?.onEnded(startSample, endSample) }

    /** Every classifier run (capture thread): logged and kept for the clip traces. */
    override fun onClassifierResult(wallClockMs: Long, result: ClassifierResult) {
        if (active) queue.offer(Cls(result))
    }

    /** From a database coroutine, possibly after [onSessionStop]. */
    override fun onEventStored(eventId: Long, event: NoiseEvent, startSample: Long, endSample: Long) {
        if (assembler != null) queue.offer(Stored(eventId, event, startSample))
    }

    /** From the thread that stops the measurement, after capture has ended. Never blocks. */
    override fun onSessionStop() {
        if (!stopped.compareAndSet(false, true)) return
        val a = assembler
        if (a != null) {
            if (active) a.flush()
            droppedBusyFinal = a.droppedBusy
        }
        skippedRateFinal = sampler.skipped
        active = false
        aacStopRequested = true
        queue.offer(Stop)
    }

    private fun onClipAssembled(clip: AssembledClip) {
        if (queuedClips.get() >= MAX_QUEUED_CLIPS) { clipsDroppedQueue.incrementAndGet(); publishDrops(); return }
        queuedClips.incrementAndGet()
        queue.offer(ClipAudio(clip))
    }

    /** «Audio aufzeichnen» was switched off during the measurement: stop recording now. */
    private fun deactivate() {
        active = false
        assembler?.flush() // completes confirmed clips; the setting was off, so they are dropped below
        aacStopRequested = true
        status { it.copy(recording = false) }
    }

    private fun storageFull() {
        if (!fullReported.compareAndSet(false, true)) return
        active = false
        aacStopRequested = true
        queue.offer(Line(ManifestLines.storageFull(sessionId, System.currentTimeMillis(), zone, budget.usedBytes, budget.maxBytes)))
        status { it.copy(recording = false, storageFull = true) }
    }

    private fun publishDrops() {
        val d = clipsDroppedQueue.get() + clipsDroppedStorage.get() + aacBlocksDropped.get()
        status { it.copy(droppedThisSession = d) }
    }

    private fun epochMsAt(sample: Long): Long {
        val ms = anchorEpochMs
        return if (ms == Long.MIN_VALUE) System.currentTimeMillis() else ms + Math.floorDiv((sample - anchorSample) * 1000L, INPUT_RATE.toLong())
    }

    // ---- Writer thread --------------------------------------------------------------------

    /** A clip whose event id is known, waiting (≤ [TRACE_WAIT_MS]) for its last classifier results. */
    private class ReadyClip(val clip: AssembledClip, val id: Long?, val event: NoiseEvent?, val notBefore: Long)

    private fun writerLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        var started = false
        var classifierOn = false
        val early = ArrayList<String>()
        val awaitingId = LinkedHashMap<Long, AssembledClip>()
        val stored = object : LinkedHashMap<Long, Stored>() {
            // Events without a clip (clip rate, busy, storage) are never claimed: keep only recent ones.
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Stored>?) = size > 32
        }
        val ready = ArrayList<ReadyClip>()
        val recent = ArrayDeque<ClassifierResult>()
        var lastClassifiedSample = Long.MIN_VALUE
        val classifierLog = ClassifierLog(dirs.classifier, zone)
        var deadline = Long.MAX_VALUE
        var aacDone = aacQueue == null

        fun line(text: String) {
            if (!started) { early += text; return }
            try { ManifestFile.append(dirs.manifest, text) } catch (e: Exception) { Log.w(TAG, "manifest: ${e.javaClass.simpleName}") }
        }

        fun clipReady(clip: AssembledClip, id: Long?, event: NoiseEvent?) {
            val wait = if (classifierOn) TRACE_WAIT_MS else 0L
            ready += ReadyClip(clip, id, event, System.currentTimeMillis() + wait)
        }

        fun writeReady(force: Boolean) {
            if (ready.isEmpty()) return
            val now = System.currentTimeMillis()
            val it = ready.iterator()
            while (it.hasNext()) {
                val r = it.next()
                val clipEnd = r.clip.clipStartSample + r.clip.length
                if (force || now >= r.notBefore || lastClassifiedSample >= clipEnd) {
                    it.remove()
                    val trace = recent.filter { c -> c.endSample > r.clip.clipStartSample && c.endSample <= clipEnd }
                    writeClip(r.clip, r.id, r.event, ::line, trace)
                }
            }
        }

        fun classified(r: ClassifierResult) {
            recent.addLast(r)
            if (r.endSample > lastClassifiedSample) lastClassifiedSample = r.endSample
            while (recent.isNotEmpty() && recent.first().endSample < lastClassifiedSample - TRACE_KEEP_SAMPLES) recent.removeFirst()
            if (budget.full) return
            try {
                val bytes = classifierLog.append(r.endEpochMs, ManifestLines.classifierLine(sessionId, r, zone))
                if (!budget.account(bytes)) storageFull()
            } catch (e: Exception) {
                Log.w(TAG, "classifier log: ${e.javaClass.simpleName}")
            }
        }

        try {
            while (true) {
                writeReady(force = deadline != Long.MAX_VALUE)
                if (deadline != Long.MAX_VALUE && awaitingId.isEmpty() && ready.isEmpty() && aacDone && queue.isEmpty()) break
                // After stop: wait up to STOP_WAIT_MS for event ids, and up to 10 s more for the
                // AAC thread to close its file (its last manifest lines come through this queue).
                val m = if (deadline == Long.MAX_VALUE) {
                    if (ready.isEmpty()) queue.take() else queue.poll(250, TimeUnit.MILLISECONDS)
                } else {
                    val until = if (aacDone) deadline else deadline + 10_000
                    val wait = until - System.currentTimeMillis()
                    if (wait <= 0) null else queue.poll(wait, TimeUnit.MILLISECONDS)
                }
                if (m == null) {
                    if (deadline == Long.MAX_VALUE) continue
                    if (aacDone || System.currentTimeMillis() >= deadline + 10_000) break
                    continue
                }
                when (m) {
                    is SessionStart -> {
                        started = true
                        classifierOn = m.s.classifierEnabled
                        line(ManifestLines.sessionStart(sessionId, m.s.startedAtMs, zone, sessionInfo(m.s)))
                        early.forEach { line(it) }; early.clear()
                    }
                    is Line -> line(m.text)
                    is Cls -> classified(m.result)
                    is ClipAudio -> {
                        queuedClips.decrementAndGet()
                        val s = stored.remove(m.clip.eventStartSample)
                        if (s != null) clipReady(m.clip, s.id, s.event)
                        else awaitingId[m.clip.eventStartSample] = m.clip
                    }
                    is Stored -> {
                        val c = awaitingId.remove(m.startSample)
                        if (c != null) clipReady(c, m.id, m.event) else stored[m.startSample] = m
                    }
                    AacDone -> aacDone = true
                    Stop -> deadline = System.currentTimeMillis() + STOP_WAIT_MS
                }
            }
            // Clips whose event never reached the database (should not happen): keep them anyway.
            for (c in awaitingId.values) { clipsWithoutId.incrementAndGet(); clipReady(c, null, null) }
            writeReady(force = true)
            if (!started) { started = true; early.forEach { line(it) } }
            line(
                ManifestLines.sessionStop(
                    sessionId, System.currentTimeMillis(), zone,
                    ManifestLines.Counts(
                        clipsWritten = clipsWritten.get(), clipsSkippedRate = skippedRateFinal,
                        clipsDroppedBusy = droppedBusyFinal, clipsDroppedQueue = clipsDroppedQueue.get(),
                        clipsDroppedStorage = clipsDroppedStorage.get(), clipsWithoutEventId = clipsWithoutId.get(),
                        clipErrors = clipErrors.get(),
                        continuousFiles = continuousFiles.get(), continuousBlocksDropped = aacBlocksDropped.get(),
                    ),
                    storageFull = budget.full,
                )
            )
        } catch (_: InterruptedException) {
        } finally {
            classifierLog.close()
            status { it.copy(recording = false) }
            Labor.filesVersion.update { it + 1 }
        }
    }

    private fun sessionInfo(s: AudioTapSession) = ManifestLines.SessionInfo(
        deviceModel = CalibrationRepository.deviceModel,
        androidRelease = Build.VERSION.RELEASE ?: "?", sdkInt = Build.VERSION.SDK_INT,
        appVersion = BuildConfig.VERSION_NAME, appVersionCode = BuildConfig.VERSION_CODE,
        audioSource = s.audioSource, encoding = s.encoding, effects = s.effects,
        calibrationId = s.calibrationId, calibrationOffsetDb = s.calibrationOffsetDb, calibrated = s.calibrated,
        eventThresholdDb = s.eventThresholdDb, eventMinLevelDb = s.eventMinLevelDb,
        classifierEnabled = s.classifierEnabled, classifierNormalize = s.classifierNormalize,
        classifierIntervalSeconds = s.classifierIntervalSeconds,
        clips = settings.clips, continuous = settings.continuous, clipEvery = settings.clipEvery, maxBytes = settings.maxBytes,
    )

    private fun writeClip(
        clip: AssembledClip, eventId: Long?, event: NoiseEvent?, line: (String) -> Unit, trace: List<ClassifierResult>,
    ) {
        // Switched off in the meantime: the person no longer wants recordings.
        if (!Labor.settings.value.recordAudio) return
        try {
            val pcm = Resample16k.convert(clip.chunks, clip.length)
            if (!budget.tryReserve(Wav.fileBytes(pcm.size))) {
                clipsDroppedStorage.incrementAndGet(); storageFull(); publishDrops(); return
            }
            val startMs = event?.startEpochMs ?: epochMsAt(clip.eventStartSample)
            val stamp = Instant.ofEpochMilli(startMs).atZone(zone).format(HourRoller.SECOND_NAME)
            val name = HourRoller.unique("ev_${eventId ?: "na"}_$stamp", "wav") { File(dirs.clips, it).exists() }
            dirs.clips.mkdirs()
            Wav.write(File(dirs.clips, name), pcm, pcm.size, ManifestLines.OUTPUT_RATE)
            line(ManifestLines.clip(sessionId, System.currentTimeMillis(), zone, eventId, "clips/$name", event, clip, pcm.size, trace))
            clipsWritten.incrementAndGet()
            Labor.clipWritten(eventId)
            status { it.copy(clipsThisSession = clipsWritten.get()) }
        } catch (e: Exception) {
            clipErrors.incrementAndGet()
            Log.w(TAG, "clip write failed: ${e.javaClass.simpleName}")
        }
    }

    // ---- AAC thread -----------------------------------------------------------------------

    private fun aacLoop(q: ArrayBlockingQueue<Block>) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        val roller = HourRoller(zone)
        var rs: Resample16k? = null
        var resamplerStart = 0L // input sample of the resampler's first input
        var outCount = 0L       // 16 kHz samples emitted by the resampler since resamplerStart
        var expected = -1L      // next input sample expected
        var writer: AacFileWriter? = null
        var fileName = ""
        var fileStartOut = 0L
        val tmp = FloatArray(48_000 / 3 + 2)
        val zeros = FloatArray(6_000)
        var failures = 0 // consecutive encoder/file errors; after 3 the continuous recording gives up

        fun close(reason: String) {
            val w = writer ?: return
            writer = null
            w.close()
            if (w.file.exists()) {
                queue.offer(Line(ManifestLines.continuousClose(sessionId, System.currentTimeMillis(), zone, "continuous/$fileName", w.samplesWritten, w.file.length(), reason)))
                Labor.filesVersion.update { it + 1 }
            }
        }

        fun open(wallMs: Long) {
            val startInput = resamplerStart + 3 * outCount
            fileName = HourRoller.unique(roller.baseName(wallMs), "m4a") { File(dirs.continuous, it).exists() }
            dirs.continuous.mkdirs()
            writer = AacFileWriter(File(dirs.continuous, fileName), account = { b -> budget.account(b) })
            fileStartOut = outCount
            continuousFiles.incrementAndGet()
            queue.offer(Line(ManifestLines.continuousOpen(sessionId, System.currentTimeMillis(), zone, "continuous/$fileName", startInput, epochMsAt(startInput + 2))))
        }

        fun feed(data: FloatArray, count: Int) {
            val r = rs ?: return
            var p = 0
            while (p < count) {
                val k = minOf(count - p, 48_000)
                val chunk = if (p == 0 && k == count) data else data.copyOfRange(p, p + k)
                val n = r.process(chunk, k, tmp)
                writer?.write(tmp, n)
                outCount += n
                p += k
            }
        }

        try {
            while (true) {
                val b = q.poll(200, TimeUnit.MILLISECONDS)
                if (b == null) { if (aacStopRequested) break else continue }
                if (budget.full) { close("storage_full"); storageFull(); continue }
                if (!active && !aacStopRequested) continue
                if (failures >= 3) continue
                try {
                    if (expected >= 0 && b.startSample > expected) {
                        val gap = b.startSample - expected
                        val fill = writer != null && gap <= MAX_GAP_FILL_SAMPLES
                        queue.offer(Line(ManifestLines.gap(sessionId, System.currentTimeMillis(), zone, if (writer != null) "continuous/$fileName" else null, expected, b.startSample, fill)))
                        if (fill) {
                            var left = gap
                            while (left > 0) { val k = minOf(left, zeros.size.toLong()).toInt(); feed(zeros, k); left -= k }
                        } else {
                            close("gap"); rs = null
                        }
                    }
                    if (rs == null) { rs = Resample16k(48_000); resamplerStart = b.startSample; outCount = 0 }
                    if (roller.needsNewFile(b.wallClockMs) || writer == null) {
                        close("hour")
                        open(b.wallClockMs)
                    }
                    feed(b.data, b.count)
                    expected = b.startSample + b.count
                    if (writer?.capReached == true) { close("storage_full"); storageFull() }
                    failures = 0
                } catch (e: Exception) {
                    failures++
                    Log.w(TAG, "continuous: ${e.javaClass.simpleName}")
                    try { close("error") } catch (_: Exception) {}
                    rs = null
                }
            }
            // Measurement stopped: emit the filter's last outputs, then write the MP4 index.
            try {
                rs?.let { r -> if (writer != null) { val n = r.flush(tmp); writer?.write(tmp, n) } }
            } catch (_: Exception) {}
            close(if (budget.full) "storage_full" else "stop")
        } catch (_: InterruptedException) {
            try { close("interrupted") } catch (_: Exception) {}
        } finally {
            q.clear()
            publishDrops()
            queue.offer(AacDone)
        }
    }
}
