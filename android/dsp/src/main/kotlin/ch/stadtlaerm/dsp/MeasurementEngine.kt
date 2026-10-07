package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.classify.CategoryDecision
import ch.stadtlaerm.dsp.classify.CategoryMapper
import ch.stadtlaerm.dsp.classify.ClassifierFrame
import ch.stadtlaerm.dsp.classify.ClassifierPreprocessor
import ch.stadtlaerm.dsp.classify.LabelScore
import java.time.ZoneId
import kotlin.math.abs

data class EngineConfig(
    val sampleRate: Int = Acoustics.SAMPLE_RATE,
    val calibrationOffsetDb: Double = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB,
    val calibrated: Boolean = false,
    val calibrationId: Long? = null,
    val audioSource: String = "unknown",
    val eventThresholdDb: Double = 10.0,
    val eventHysteresisDb: Double = 3.0,
    val eventMinDurationSeconds: Double = 0.5,
    val eventMaxDurationSeconds: Double = 300.0,
    /** Events are only kept if their LAFmax reaches this absolute level (dB, with the offset). */
    val eventMinLevelDb: Double = DEFAULT_EVENT_MIN_LEVEL_DB,
    val backgroundWindowSeconds: Double = 300.0,
    val backgroundMinHistorySeconds: Double = 30.0,
    val classifierEnabled: Boolean = true,
    /** Classify once per second by default (short motorbike pass-bys must be covered). */
    val classifierIntervalSeconds: Double = DEFAULT_CLASSIFIER_INTERVAL_SECONDS,
    /** Fallback only: max wait for a classifier frame after an event ends (normally ≤ 1 tick). */
    val eventClassificationTimeoutSeconds: Double = 3.0,
    /** After silenced/invalid audio, this much further audio is also invalid (filter recovery). */
    val invalidGuardSeconds: Double = 0.5,
    /** Re-anchor the sample clock to the wall clock when they differ by more than this. */
    val maxClockDriftMs: Long = 500,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    init {
        require(sampleRate % 8 == 0) { "sample rate must be divisible by 8 (125 ms ticks)" }
        require(sampleRate % 3 == 0) { "sample rate must be divisible by 3 (classifier decimation)" }
    }

    companion object {
        const val DEFAULT_CLASSIFIER_INTERVAL_SECONDS = 1.0
        const val DEFAULT_EVENT_MIN_LEVEL_DB = 45.0
    }
}

/**
 * The measurement pipeline (pure Kotlin, no Android dependencies).
 *
 * Input: mono float blocks at [EngineConfig.sampleRate] (48 kHz) in any block size.
 * Per sample: A-weighting → square → Fast (125 ms) exponential averaging; Z (unweighted)
 * mean square for diagnostics; the unweighted signal also goes through the anti-aliasing
 * decimator into a 1 s, 16 kHz ring buffer for the classifier.
 * Every 125 ms ("tick"): validity check, LAF sample, event detection. Every second: LAeq,1s,
 * LAFmax,1s, background. At each wall-clock minute boundary: a [MinuteRecord].
 *
 * **Validity.** A tick is invalid while the microphone is silenced by the system
 * ([setMicSilenced], from Android's recording callback) or when it contains digital silence
 * ([SilenceDetector]); the following [EngineConfig.invalidGuardSeconds] are invalid too, while
 * the filters recover. Invalid ticks are excluded from LAeq, LAF statistics, percentiles, the
 * event background and event detection (a running event is closed), and classifier windows that
 * overlap them are ignored. Minutes record how many seconds were valid.
 *
 * **Time.** Sample time is anchored to [wallClock] when the first audio block is delivered, and
 * once per minute re-anchored if the sample clock has drifted from the wall clock by more than
 * [EngineConfig.maxClockDriftMs]. The drift estimate is the minimum over the minute of
 * (wall time at block delivery − sample time of block end), which removes scheduling latency.
 * Minute boundaries stay aligned to wall-clock minutes.
 *
 * Privacy: the only audio held is the decimator history (241 samples) and the 1 s classifier
 * ring buffer. Nothing here writes audio anywhere.
 *
 * Not thread-safe except for [setMicSilenced]: call everything else from the capture thread.
 */
class MeasurementEngine(
    val config: EngineConfig,
    private val mapper: CategoryMapper?,
    private val listener: Listener,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    interface Listener {
        fun onTick(tick: LafTick) {}
        fun onSecond(second: SecondResult) {}
        fun onMinute(minute: MinuteRecord) {}
        fun onEvent(event: NoiseEvent) {}
        fun onClassification(endEpochMs: Long, decision: CategoryDecision, top: List<LabelScore>) {}
        /** The sample clock was re-anchored by [correctionMs] (wall − sample time). */
        fun onClockCorrection(correctionMs: Long) {}
    }

    private val fs = config.sampleRate
    private val offset = config.calibrationOffsetDb
    private val tickLen = fs / 8
    private val warmupTicks = 4 // 0.5 s: lets the A-filter and the Fast integrator settle
    private val guardSamples = (config.invalidGuardSeconds * fs).toLong()

    private val aWeighting = AWeighting(fs)
    private val fast = ExponentialTimeWeighting(ExponentialTimeWeighting.FAST_TAU, fs)
    private val decimator = DecimatingResampler(fs, 3)
    private val classifierRing = FloatRingBuffer(fs / 3) // 1 s at 16 kHz
    private val classifierWindowSamples48k = ClassifierPreprocessor.YAMNET_INPUT_SAMPLES.toLong() * 3

    private val classifierActive = config.classifierEnabled && mapper != null
    private val classifierIntervalSamples = (config.classifierIntervalSeconds * fs).toLong()
    private var lastClassifierSample = Long.MIN_VALUE / 2
    private var forceClassification = false

    var totalSamples = 0L
        private set
    private var tickCount = 0L

    // Clock
    private var anchored = false
    private var anchorSample = 0L
    private var anchorMs = 0L
    private var minDriftMs = Long.MAX_VALUE

    // Validity
    @Volatile private var micSilenced = false
    private var silencedSeenInTick = false
    private var zeroRun = 0
    private var tickMaxZeroRun = 0
    private var guardUntilSample = Long.MIN_VALUE
    /** Recent invalid intervals [start, end) in samples, oldest first (pruned to ~10 s). */
    private val invalidIntervals = ArrayDeque<LongArray>()

    // Tick accumulators
    private var tickPos = 0
    private var tickSumA = 0.0
    private var tickSumZ = 0.0
    private var tickMaxY = 0.0

    // Second accumulators (valid ticks only, except the tick counter)
    private var secTicks = 0
    private var secValidTicks = 0
    private var secSumA = 0.0
    private var secSumZ = 0.0
    private var secMaxY = 0.0
    private val last60s = DoubleRing(60)

    // Minute accumulators
    private var minuteStartMs = 0L
    private var nextMinuteBoundaryMs = 0L
    private var minuteSumA = 0.0
    private var minuteSamples = 0L
    private var minuteValidTicks = 0
    private var minuteMaxY = 0.0
    private var minuteLafs = DoubleArray(1024)
    private var minuteLafCount = 0
    private var minuteEvents = 0
    private var minuteClockCorrections = 0
    private val bucketIds: List<String> = mapper?.bucketIds ?: emptyList()
    private val minuteBucketCounts = IntArray(bucketIds.size)
    private var minuteFrames = 0
    private var recording = false

    /**
     * A closed minute whose last seconds contained a still-unconfirmed event candidate: it is
     * emitted once the candidate is confirmed (and counted here, where it started) or discarded.
     */
    private var heldMinute: MinuteRecord? = null

    private val background = BackgroundEstimator(
        config.backgroundWindowSeconds, config.backgroundMinHistorySeconds, 0.125,
    )

    // Event classification bookkeeping
    private class EventAcc(val startSample: Long, labelCount: Int) {
        val sum = FloatArray(labelCount)
        var frames = 0
        var detected: DetectedEvent? = null
    }

    private var activeAcc: EventAcc? = null
    private val pending = ArrayList<EventAcc>()
    private val labelCount = mapper?.labels?.size ?: 0

    private val detector = EventDetector(
        thresholdDb = config.eventThresholdDb,
        hysteresisDb = config.eventHysteresisDb,
        minDurationSeconds = config.eventMinDurationSeconds,
        maxDurationSeconds = config.eventMaxDurationSeconds,
        minLevelDb = config.eventMinLevelDb,
        tickSamples = tickLen,
        sampleRate = fs,
        listener = object : EventDetector.Listener {
            override fun onCandidateStart(startSample: Long) {
                activeAcc = EventAcc(startSample, labelCount)
            }

            override fun onConfirmed(startSample: Long) {
                val held = heldMinute
                if (held != null) {
                    // A minute is only held for the candidate that started in it: count it there.
                    heldMinute = null
                    listener.onMinute(held.copy(eventCount = held.eventCount + 1))
                } else if (recording) {
                    minuteEvents++
                }
            }

            override fun onDiscarded(startSample: Long) {
                activeAcc = null
                releaseHeldMinute()
            }

            override fun onClosed(event: DetectedEvent) {
                val acc = activeAcc ?: EventAcc(event.startSample, labelCount)
                activeAcc = null
                acc.detected = event
                if (!classifierActive || acc.frames > 0) {
                    // Classifier results overlapping the event are already attached: emit now.
                    emitEvent(acc)
                } else {
                    // None overlapped (short event between two classifications): request one
                    // classification right now, which covers the last 0.975 s of the event.
                    pending.add(acc)
                    forceClassification = true
                }
            }
        },
    )

    /** Wall-clock time (epoch ms) of input sample index [sample]. */
    fun epochMsAt(sample: Long): Long = anchorMs + Math.floorDiv((sample - anchorSample) * 1000L, fs.toLong())

    /** Called by the platform when the system silences / un-silences this app's microphone. */
    fun setMicSilenced(silenced: Boolean) {
        micSilenced = silenced
    }

    /** Feeds [count] samples of [block]; [wallClock] is read once, as the block's delivery time. */
    fun process(block: FloatArray, count: Int = block.size) {
        val deliveredMs = wallClock()
        val blockEnd = totalSamples + count
        if (!anchored) {
            anchored = true
            anchorSample = blockEnd
            anchorMs = deliveredMs
        }
        val drift = deliveredMs - epochMsAt(blockEnd)
        if (drift < minDriftMs) minDriftMs = drift
        if (micSilenced) silencedSeenInTick = true

        for (i in 0 until count) {
            val xf = block[i]
            if (xf == 0f) {
                zeroRun++
                if (zeroRun > tickMaxZeroRun) tickMaxZeroRun = zeroRun
            } else {
                zeroRun = 0
            }
            val x = xf.toDouble()
            val a = aWeighting.process(x)
            val a2 = a * a
            tickSumA += a2
            tickSumZ += x * x
            val y = fast.process(a2)
            if (y > tickMaxY) tickMaxY = y
            tickPos++
            totalSamples++
            if (tickPos == tickLen) {
                endTick()
                if (micSilenced) silencedSeenInTick = true
            }
        }
        if (classifierActive) decimator.process(block, count, classifierRing)
    }

    private fun resetTick() {
        tickPos = 0; tickSumA = 0.0; tickSumZ = 0.0; tickMaxY = 0.0
        tickMaxZeroRun = zeroRun // a zero run continuing from the previous tick still counts
        silencedSeenInTick = false
    }

    private fun tickIsInvalid(endSample: Long): Boolean {
        val digitalSilence = tickMaxZeroRun >= SilenceDetector.ZERO_RUN_SAMPLES ||
            tickSumZ / tickLen < SilenceDetector.FLOOR_MEAN_SQUARE
        val start = endSample - tickLen
        if (silencedSeenInTick || micSilenced || digitalSilence) {
            guardUntilSample = endSample + guardSamples
            addInvalid(start, guardUntilSample)
            return true
        }
        return endSample <= guardUntilSample
    }

    private fun addInvalid(start: Long, end: Long) {
        val last = invalidIntervals.lastOrNull()
        if (last != null && start <= last[1]) last[1] = maxOf(last[1], end) else invalidIntervals.addLast(longArrayOf(start, end))
        while (invalidIntervals.size > 1 && invalidIntervals.first()[1] < totalSamples - 10L * fs) invalidIntervals.removeFirst()
    }

    private fun overlapsInvalid(start: Long, end: Long): Boolean =
        invalidIntervals.any { it[0] < end && start < it[1] }

    private fun endTick() {
        tickCount++
        if (tickCount <= warmupTicks) {
            resetTick()
            if (tickCount == warmupTicks.toLong()) startRecording()
            return
        }
        val endSample = totalSamples
        val nowMs = epochMsAt(endSample)
        val laf = Acoustics.db(fast.value, offset)
        val lafMaxTick = Acoustics.db(tickMaxY, offset)
        val leqTick = Acoustics.db(tickSumA / tickLen, offset)
        val valid = !tickIsInvalid(endSample)

        secTicks++
        minuteSamples += tickLen
        if (valid) {
            secValidTicks++
            secSumA += tickSumA; secSumZ += tickSumZ
            if (tickMaxY > secMaxY) secMaxY = tickMaxY
            minuteSumA += tickSumA; minuteValidTicks++
            if (tickMaxY > minuteMaxY) minuteMaxY = tickMaxY
            if (minuteLafCount == minuteLafs.size) minuteLafs = minuteLafs.copyOf(minuteLafs.size * 2)
            minuteLafs[minuteLafCount++] = laf
            background.add(laf)
            detector.onTick(endSample, laf, lafMaxTick, leqTick)
        } else {
            detector.interrupt(endSample - tickLen)
        }
        listener.onTick(LafTick(endSample, nowMs, laf, lafMaxTick, leqTick, valid))
        resetTick()

        if (secTicks == 8) endSecond(endSample, nowMs)
        if (nowMs >= nextMinuteBoundaryMs) endMinute(nextMinuteBoundaryMs)
        finalizePending(force = false)
    }

    private fun startRecording() {
        recording = true
        val t = epochMsAt(totalSamples)
        minuteStartMs = t
        nextMinuteBoundaryMs = (t / 60_000L + 1) * 60_000L
    }

    private fun endSecond(endSample: Long, nowMs: Long) {
        val validSamples = secValidTicks.toLong() * tickLen
        val msA = if (validSamples > 0) secSumA / validSamples else Double.NaN
        if (validSamples > 0) last60s.add(msA)
        val running = if (last60s.size > 0) last60s.toArray().average() else Double.NaN
        val bg = background.l90()
        detector.backgroundDb = bg
        listener.onSecond(
            SecondResult(
                endSample = endSample,
                epochMs = nowMs,
                laeqDb = if (validSamples > 0) Acoustics.db(msA, offset) else Double.NaN,
                lafMaxDb = if (validSamples > 0) Acoustics.db(secMaxY, offset) else Double.NaN,
                lzeqDb = if (validSamples > 0) Acoustics.db(secSumZ / validSamples, offset) else Double.NaN,
                laeqRunning60sDb = if (running.isNaN()) Double.NaN else Acoustics.db(running, offset),
                backgroundDb = bg,
                validFraction = secValidTicks / 8.0,
            )
        )
        secTicks = 0; secValidTicks = 0; secSumA = 0.0; secSumZ = 0.0; secMaxY = 0.0
    }

    private fun releaseHeldMinute() {
        val held = heldMinute ?: return
        heldMinute = null
        listener.onMinute(held)
    }

    private fun endMinute(boundaryMs: Long) {
        releaseHeldMinute()
        if (minuteSamples > 0) {
            val stats = Percentiles.stats(minuteLafs.copyOf(minuteLafCount))
            val validSamples = minuteValidTicks.toLong() * tickLen
            val shares = LinkedHashMap<String, Double>()
            var dominant: String? = null
            if (classifierActive && minuteFrames > 0) {
                var best = -1
                for (b in bucketIds.indices) {
                    shares[bucketIds[b]] = minuteBucketCounts[b].toDouble() / minuteFrames
                    if (best < 0 || minuteBucketCounts[b] > minuteBucketCounts[best]) best = b
                }
                dominant = bucketIds[best]
            }
            val record = MinuteRecord(
                startEpochMs = minuteStartMs,
                startIso = Iso.format(minuteStartMs, config.zone),
                durationSeconds = minuteSamples.toDouble() / fs,
                laeqDb = if (validSamples > 0) Acoustics.db(minuteSumA / validSamples, offset) else Double.NaN,
                lafMaxDb = if (validSamples > 0) Acoustics.db(minuteMaxY, offset) else Double.NaN,
                lafMinDb = stats.min,
                l1Db = stats.l1,
                l10Db = stats.l10,
                l50Db = stats.l50,
                l90Db = stats.l90,
                eventCount = minuteEvents,
                dominantCategory = dominant,
                categoryShares = shares,
                classifierFrames = minuteFrames,
                calibrationId = config.calibrationId,
                calibrationOffsetDb = offset,
                audioSource = config.audioSource,
                calibrated = config.calibrated,
                validSeconds = validSamples.toDouble() / fs,
                clockCorrections = minuteClockCorrections,
            )
            // An event that started in this minute but is not yet confirmed must be counted here.
            if (recording && detector.isUnconfirmedCandidate) heldMinute = record else listener.onMinute(record)
        }
        minuteSumA = 0.0; minuteSamples = 0; minuteValidTicks = 0; minuteMaxY = 0.0; minuteLafCount = 0
        minuteEvents = 0; minuteClockCorrections = 0
        minuteBucketCounts.fill(0); minuteFrames = 0

        checkClock()
        // Align the next minute to the wall clock (also after a clock correction or a gap).
        val nowMinute = Math.floorDiv(epochMsAt(totalSamples), 60_000L) * 60_000L
        minuteStartMs = maxOf(nowMinute, boundaryMs)
        nextMinuteBoundaryMs = minuteStartMs + 60_000L
    }

    /** Once per minute: re-anchor the sample clock if it drifted from the wall clock. */
    private fun checkClock() {
        val drift = minDriftMs
        minDriftMs = Long.MAX_VALUE
        if (drift == Long.MAX_VALUE || abs(drift) <= config.maxClockDriftMs) return
        anchorMs += drift
        minuteClockCorrections++
        listener.onClockCorrection(drift)
    }

    // ---- Classifier interface -------------------------------------------------------------

    /** True when a new classifier window should be taken now. */
    fun classifierDue(): Boolean {
        if (!classifierActive || !recording) return false
        if (classifierRing.available < ClassifierPreprocessor.YAMNET_INPUT_SAMPLES) return false
        if (overlapsInvalid(totalSamples - classifierWindowSamples48k, totalSamples)) return false
        return forceClassification || totalSamples - lastClassifierSample >= classifierIntervalSamples
    }

    /**
     * Copies the latest 0.975 s of 16 kHz audio (unweighted, anti-aliased) into [dest].
     * Returns the window end as an input sample index, or −1 if not enough data.
     */
    fun copyClassifierWindow(dest: FloatArray): Long {
        if (!classifierRing.copyLatest(dest, ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)) return -1
        lastClassifierSample = totalSamples
        forceClassification = false
        return totalSamples
    }

    val classifierWindowLength48k: Long get() = classifierWindowSamples48k

    /** Delivers a classifier result (from the inference thread, via the capture thread). */
    fun onClassifierFrame(frame: ClassifierFrame) {
        val m = mapper ?: return
        val windowStart = frame.endSample - frame.windowSamples48k
        // Windows touching silenced/invalid audio say nothing about the real sound: ignore them.
        if (overlapsInvalid(windowStart, frame.endSample)) return
        val decision = m.decide(frame.scores)
        listener.onClassification(epochMsAt(frame.endSample), decision, m.topLabels(frame.scores, 3))
        if (recording) {
            val b = bucketIds.indexOf(decision.dominant)
            if (b >= 0) minuteBucketCounts[b]++
            minuteFrames++
        }
        val accs = ArrayList<EventAcc>(pending.size + 1)
        accs.addAll(pending)
        activeAcc?.let { accs.add(it) }
        for (acc in accs) {
            val evEnd = acc.detected?.endSample ?: Long.MAX_VALUE
            if (windowStart < evEnd && frame.endSample > acc.startSample) {
                for (i in 0 until labelCount) acc.sum[i] += frame.scores[i]
                acc.frames++
            }
        }
        val it = pending.iterator()
        while (it.hasNext()) {
            val acc = it.next()
            if (acc.frames > 0) {
                it.remove()
                emitEvent(acc)
            }
        }
    }

    private fun finalizePending(force: Boolean) {
        if (pending.isEmpty()) return
        val timeout = (config.eventClassificationTimeoutSeconds * fs).toLong()
        val it = pending.iterator()
        while (it.hasNext()) {
            val acc = it.next()
            if (force || !classifierActive || totalSamples - acc.detected!!.endSample > timeout) {
                it.remove()
                emitEvent(acc)
            }
        }
        if (pending.isEmpty()) forceClassification = false
    }

    private fun emitEvent(acc: EventAcc) {
        val ev = acc.detected!!
        var dominant: String? = null
        var score = 0f
        var top: List<LabelScore> = emptyList()
        val m = mapper
        if (classifierActive && m != null) {
            if (acc.frames > 0) {
                val mean = FloatArray(labelCount) { acc.sum[it] / acc.frames }
                val d = m.decide(mean)
                dominant = d.dominant; score = d.dominantScore
                top = m.topLabels(mean, 3)
            } else {
                dominant = CategoryMapper.UNCLASSIFIED
            }
        }
        val startMs = epochMsAt(ev.startSample)
        listener.onEvent(
            NoiseEvent(
                startEpochMs = startMs,
                startIso = Iso.format(startMs, config.zone, millis = true),
                durationSeconds = ev.durationSeconds,
                lafMaxDb = ev.lafMaxDb,
                selDb = ev.selDb,
                backgroundDb = ev.backgroundDb,
                thresholdDb = ev.thresholdDb,
                dominantCategory = dominant,
                dominantScore = score,
                topLabels = top,
                classifierFrames = acc.frames,
                calibrationId = config.calibrationId,
                audioSource = config.audioSource,
                calibrated = config.calibrated,
                minLevelDb = ev.minLevelDb,
            )
        )
    }

    /** Ends the measurement: closes a running event and emits the partial minute (if ≥ 1 s). */
    fun stop() {
        detector.flush(totalSamples)
        finalizePending(force = true)
        releaseHeldMinute()
        if (recording && minuteSamples >= fs) {
            recording = false // no held minute at stop
            endMinute(nextMinuteBoundaryMs)
        }
        releaseHeldMinute()
        recording = false
        classifierRing.clear()
        decimator.reset()
    }
}
