package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.classify.CategoryDecision
import ch.stadtlaerm.dsp.classify.CategoryMapper
import ch.stadtlaerm.dsp.classify.ClassifierFrame
import ch.stadtlaerm.dsp.classify.ClassifierPreprocessor
import ch.stadtlaerm.dsp.classify.LabelScore
import java.time.ZoneId

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
    val backgroundWindowSeconds: Double = 300.0,
    val backgroundMinHistorySeconds: Double = 30.0,
    val classifierEnabled: Boolean = true,
    val classifierIntervalSeconds: Double = 1.0,
    /** Max time to wait for a classifier frame after an event ends before storing it anyway. */
    val eventClassificationTimeoutSeconds: Double = 3.0,
    val zone: ZoneId = ZoneId.systemDefault(),
) {
    init {
        require(sampleRate % 8 == 0) { "sample rate must be divisible by 8 (125 ms ticks)" }
        require(sampleRate % 3 == 0) { "sample rate must be divisible by 3 (classifier decimation)" }
    }
}

/**
 * The measurement pipeline (pure Kotlin, no Android dependencies).
 *
 * Input: mono float blocks at [EngineConfig.sampleRate] (48 kHz) in any block size.
 * Per sample: A-weighting → square → Fast (125 ms) exponential averaging; Z (unweighted)
 * mean square for diagnostics; the unweighted signal also goes through the anti-aliasing
 * decimator into a 1 s, 16 kHz ring buffer for the classifier.
 * Every 125 ms ("tick"): LAF sample, event detection. Every second: LAeq,1s, LAFmax,1s, background.
 * At each wall-clock minute boundary: a [MinuteRecord].
 *
 * Privacy: the only audio held is the decimator history (241 samples) and the 1 s classifier
 * ring buffer. Nothing here writes audio anywhere.
 *
 * Not thread-safe: call all methods from the capture thread.
 */
class MeasurementEngine(
    val config: EngineConfig,
    private val startEpochMs: Long,
    private val mapper: CategoryMapper?,
    private val listener: Listener,
) {
    interface Listener {
        fun onTick(tick: LafTick) {}
        fun onSecond(second: SecondResult) {}
        fun onMinute(minute: MinuteRecord) {}
        fun onEvent(event: NoiseEvent) {}
        fun onClassification(endEpochMs: Long, decision: CategoryDecision, top: List<LabelScore>) {}
    }

    private val fs = config.sampleRate
    private val offset = config.calibrationOffsetDb
    private val tickLen = fs / 8
    private val warmupTicks = 4 // 0.5 s: lets the A-filter and the Fast integrator settle

    private val aWeighting = AWeighting(fs)
    private val fast = ExponentialTimeWeighting(ExponentialTimeWeighting.FAST_TAU, fs)
    private val decimator = DecimatingResampler(fs, 3)
    private val classifierRing = FloatRingBuffer(fs / 3) // 1 s at 16 kHz
    private val classifierWindowSamples48k = ClassifierPreprocessor.YAMNET_INPUT_SAMPLES.toLong() * 3

    private val classifierActive = config.classifierEnabled && mapper != null
    private val classifierIntervalSamples = (config.classifierIntervalSeconds * fs).toLong()
    private var lastClassifierSample = Long.MIN_VALUE / 2

    var totalSamples = 0L
        private set
    private var tickCount = 0L

    // Tick accumulators
    private var tickPos = 0
    private var tickSumA = 0.0
    private var tickSumZ = 0.0
    private var tickMaxY = 0.0

    // Second accumulators
    private var secTicks = 0
    private var secSumA = 0.0
    private var secSumZ = 0.0
    private var secSamples = 0L
    private var secMaxY = 0.0
    private val last60s = DoubleRing(60)

    // Minute accumulators
    private var minuteStartMs = 0L
    private var nextMinuteBoundaryMs = 0L
    private var minuteSumA = 0.0
    private var minuteSamples = 0L
    private var minuteMaxY = 0.0
    private var minuteLafs = DoubleArray(1024)
    private var minuteLafCount = 0
    private var minuteEvents = 0
    private val bucketIds: List<String> = mapper?.bucketIds ?: emptyList()
    private val minuteBucketCounts = IntArray(bucketIds.size)
    private var minuteFrames = 0
    private var recording = false

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
        tickSamples = tickLen,
        sampleRate = fs,
        listener = object : EventDetector.Listener {
            override fun onCandidateStart(startSample: Long) {
                activeAcc = EventAcc(startSample, labelCount)
            }

            override fun onConfirmed(startSample: Long) {
                if (recording) minuteEvents++
            }

            override fun onDiscarded(startSample: Long) {
                activeAcc = null
            }

            override fun onClosed(event: DetectedEvent) {
                val acc = activeAcc ?: EventAcc(event.startSample, labelCount)
                activeAcc = null
                acc.detected = event
                pending.add(acc)
                if (!classifierActive) finalizePending(force = true)
            }
        },
    )

    fun epochMsAt(sample: Long): Long = startEpochMs + sample * 1000L / fs

    /** Feeds [count] samples of [block]. */
    fun process(block: FloatArray, count: Int = block.size) {
        for (i in 0 until count) {
            val x = block[i].toDouble()
            val a = aWeighting.process(x)
            val a2 = a * a
            tickSumA += a2
            tickSumZ += x * x
            val y = fast.process(a2)
            if (y > tickMaxY) tickMaxY = y
            tickPos++
            totalSamples++
            if (tickPos == tickLen) endTick()
        }
        if (classifierActive) decimator.process(block, count, classifierRing)
    }

    private fun resetTick() {
        tickPos = 0; tickSumA = 0.0; tickSumZ = 0.0; tickMaxY = 0.0
    }

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

        secSumA += tickSumA; secSumZ += tickSumZ; secSamples += tickLen
        if (tickMaxY > secMaxY) secMaxY = tickMaxY
        minuteSumA += tickSumA; minuteSamples += tickLen
        if (tickMaxY > minuteMaxY) minuteMaxY = tickMaxY
        if (minuteLafCount == minuteLafs.size) minuteLafs = minuteLafs.copyOf(minuteLafs.size * 2)
        minuteLafs[minuteLafCount++] = laf

        background.add(laf)
        detector.onTick(endSample, laf, lafMaxTick, leqTick)
        listener.onTick(LafTick(endSample, nowMs, laf, lafMaxTick, leqTick))
        resetTick()

        secTicks++
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
        val msA = secSumA / secSamples
        last60s.add(msA)
        val running = last60s.toArray().average()
        val bg = background.l90()
        detector.backgroundDb = bg
        listener.onSecond(
            SecondResult(
                endSample = endSample,
                epochMs = nowMs,
                laeqDb = Acoustics.db(msA, offset),
                lafMaxDb = Acoustics.db(secMaxY, offset),
                lzeqDb = Acoustics.db(secSumZ / secSamples, offset),
                laeqRunning60sDb = Acoustics.db(running, offset),
                backgroundDb = bg,
            )
        )
        secTicks = 0; secSumA = 0.0; secSumZ = 0.0; secSamples = 0; secMaxY = 0.0
    }

    private fun endMinute(boundaryMs: Long) {
        if (minuteSamples > 0) {
            val stats = Percentiles.stats(minuteLafs.copyOf(minuteLafCount))
            val shares = LinkedHashMap<String, Double>()
            var dominant: String? = null
            if (classifierActive && minuteFrames > 0) {
                var best = -1
                for (b in bucketIds.indices) {
                    shares[bucketIds[b]] = minuteBucketCounts[b].toDouble() / minuteFrames
                    if (best < 0 || minuteBucketCounts[b] > minuteBucketCounts[best]) best = b
                }
                dominant = bucketIds[best]
            } else if (classifierActive) {
                dominant = null
            }
            listener.onMinute(
                MinuteRecord(
                    startEpochMs = minuteStartMs,
                    startIso = Iso.format(minuteStartMs, config.zone),
                    durationSeconds = minuteSamples.toDouble() / fs,
                    laeqDb = Acoustics.db(minuteSumA / minuteSamples, offset),
                    lafMaxDb = Acoustics.db(minuteMaxY, offset),
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
                )
            )
        }
        minuteStartMs = boundaryMs
        nextMinuteBoundaryMs = boundaryMs + 60_000L
        minuteSumA = 0.0; minuteSamples = 0; minuteMaxY = 0.0; minuteLafCount = 0; minuteEvents = 0
        minuteBucketCounts.fill(0); minuteFrames = 0
    }

    // ---- Classifier interface -------------------------------------------------------------

    /** True when a new classifier window should be taken now. */
    fun classifierDue(): Boolean =
        classifierActive && recording &&
            classifierRing.available >= ClassifierPreprocessor.YAMNET_INPUT_SAMPLES &&
            totalSamples - lastClassifierSample >= classifierIntervalSamples

    /**
     * Copies the latest 0.975 s of 16 kHz audio (unweighted, anti-aliased) into [dest].
     * Returns the window end as an input sample index, or −1 if not enough data.
     */
    fun copyClassifierWindow(dest: FloatArray): Long {
        if (!classifierRing.copyLatest(dest, ClassifierPreprocessor.YAMNET_INPUT_SAMPLES)) return -1
        lastClassifierSample = totalSamples
        return totalSamples
    }

    val classifierWindowLength48k: Long get() = classifierWindowSamples48k

    /** Delivers a classifier result (from the inference thread, via the capture thread). */
    fun onClassifierFrame(frame: ClassifierFrame) {
        val m = mapper ?: return
        val decision = m.decide(frame.scores)
        listener.onClassification(epochMsAt(frame.endSample), decision, m.topLabels(frame.scores, 3))
        if (recording) {
            val b = bucketIds.indexOf(decision.dominant)
            if (b >= 0) minuteBucketCounts[b]++
            minuteFrames++
        }
        val windowStart = frame.endSample - frame.windowSamples48k
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
            if (frame.endSample >= acc.detected!!.endSample) {
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
            )
        )
    }

    /** Ends the measurement: closes a running event and emits the partial minute (if ≥ 1 s). */
    fun stop() {
        detector.flush(totalSamples)
        finalizePending(force = true)
        if (recording && minuteSamples >= fs) endMinute(nextMinuteBoundaryMs)
        recording = false
        classifierRing.clear()
        decimator.reset()
    }
}
