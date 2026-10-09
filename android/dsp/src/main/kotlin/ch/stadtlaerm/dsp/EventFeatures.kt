package ch.stadtlaerm.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Second-order Butterworth sections (bilinear transform with pre-warping, Q = 1/√2), used for the
 * cheap band splits of the event features. Exactly −3 dB at the corner frequency.
 */
object Butterworth {
    private const val Q = 0.7071067811865476

    fun lowpass(cornerHz: Double, sampleRate: Int): Biquad {
        val w0 = 2 * PI * cornerHz / sampleRate
        val c = cos(w0)
        val alpha = sin(w0) / (2 * Q)
        val a0 = 1 + alpha
        return Biquad((1 - c) / 2 / a0, (1 - c) / a0, (1 - c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }

    fun highpass(cornerHz: Double, sampleRate: Int): Biquad {
        val w0 = 2 * PI * cornerHz / sampleRate
        val c = cos(w0)
        val alpha = sin(w0) / (2 * Q)
        val a0 = 1 + alpha
        return Biquad((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
    }
}

/**
 * The per-sample band splits behind the event features (all O(1) per sample, no allocation):
 *
 * - **mid band:** the A-weighted signal through a 250 Hz high-pass and a 4.5 kHz low-pass
 *   (2nd-order Butterworth each), i.e. the A-weighted level in 250 Hz–4.5 kHz;
 * - **total:** the unweighted signal through a 20 Hz high-pass (removes DC and infrasound) and an
 *   8 kHz low-pass, i.e. 20 Hz–8 kHz;
 * - **low frequencies:** that 20 Hz high-passed signal through a 200 Hz low-pass, i.e. 20–200 Hz.
 *
 * The caller squares and sums the outputs per 125 ms tick.
 */
class FeatureFilters(sampleRate: Int) {
    private val bandHp = Butterworth.highpass(250.0, sampleRate)
    private val bandLp = Butterworth.lowpass(4500.0, sampleRate)
    private val hp20 = Butterworth.highpass(20.0, sampleRate)
    private val lp200 = Butterworth.lowpass(200.0, sampleRate)
    private val lp8k = Butterworth.lowpass(8000.0, sampleRate)

    var band = 0.0; private set
    var low = 0.0; private set
    var total = 0.0; private set

    /** [x]: unweighted sample; [a]: the same sample A-weighted. Results in [band], [low], [total]. */
    fun process(x: Double, a: Double) {
        band = bandLp.process(bandHp.process(a))
        val h = hp20.process(x)
        low = lp200.process(h)
        total = lp8k.process(h)
    }

    fun reset() {
        bandHp.reset(); bandLp.reset(); hp20.reset(); lp200.reset(); lp8k.reset()
    }
}

/**
 * Local floor: L90 of the LAF samples over a trailing window (default 30 s), recomputed with every
 * sample (125 ms). Same percentile definition as [Percentiles.exceedanceLevel] (linear
 * interpolation), computed by quickselect in a preallocated scratch array: no sorting, no
 * allocation. NaN until [minHistorySeconds] of samples are available.
 */
class LocalFloorEstimator(
    val windowSeconds: Double = 30.0,
    val minHistorySeconds: Double = 5.0,
    val tickSeconds: Double = 0.125,
) {
    private val capacity = Math.round(windowSeconds / tickSeconds).toInt().coerceAtLeast(1)
    private val ring = DoubleArray(capacity)
    private val scratch = DoubleArray(capacity)
    private var next = 0
    private val minSamples = kotlin.math.ceil(minOf(minHistorySeconds, windowSeconds) / tickSeconds - 1e-9).toInt().coerceAtLeast(1)

    var size = 0
        private set

    /** The current local floor (dB), NaN until enough history. */
    var value: Double = Double.NaN
        private set

    fun add(lafDb: Double) {
        ring[next] = lafDb
        next = (next + 1) % capacity
        if (size < capacity) size++
        value = if (size < minSamples) Double.NaN else l90()
    }

    private fun l90(): Double {
        val n = size
        // The order of the ring does not matter for a percentile.
        System.arraycopy(ring, 0, scratch, 0, n)
        return QuickSelect.percentile(scratch, n, 10.0)
    }

    fun clear() {
        next = 0; size = 0; value = Double.NaN
    }
}

/** In-place selection on a prefix of a DoubleArray (no allocation). */
object QuickSelect {
    /**
     * The [p]th percentile (0…100) of `a[0 until n]` with linear interpolation between the
     * neighbouring order statistics, exactly like [Percentiles.percentileSorted]. Reorders `a`.
     */
    fun percentile(a: DoubleArray, n: Int, p: Double): Double {
        if (n <= 0) return Double.NaN
        if (n == 1) return a[0]
        val pos = p / 100.0 * (n - 1)
        val lo = pos.toInt()
        val frac = pos - lo
        val vLo = select(a, n, lo)
        if (frac == 0.0 || lo + 1 >= n) return vLo
        // After select(), everything right of lo is ≥ a[lo]: the next order statistic is their minimum.
        var vHi = a[lo + 1]
        for (i in lo + 2 until n) if (a[i] < vHi) vHi = a[i]
        return vLo + (vHi - vLo) * frac
    }

    /** Rearranges `a[0 until n]` so that a[k] is the k-th smallest; returns it. */
    fun select(a: DoubleArray, n: Int, k: Int): Double {
        var left = 0
        var right = n - 1
        while (right > left) {
            // Median of three as pivot, moved to the right end.
            val mid = (left + right) ushr 1
            if (a[mid] < a[left]) swap(a, mid, left)
            if (a[right] < a[left]) swap(a, right, left)
            if (a[mid] < a[right]) swap(a, mid, right)
            val pivot = a[right]
            var store = left
            for (i in left until right) {
                if (a[i] < pivot) { swap(a, i, store); store++ }
            }
            swap(a, store, right)
            when {
                store == k -> return a[k]
                k < store -> right = store - 1
                else -> left = store + 1
            }
        }
        return a[k]
    }

    private fun swap(a: DoubleArray, i: Int, j: Int) {
        val t = a[i]; a[i] = a[j]; a[j] = t
    }
}

/**
 * Shape and wind features of one event (detector v2, engine 0.4.0). Computed once when the event
 * is complete; see [EventFeatureTracker] for the exact definitions.
 */
data class EventFeatures(
    /** L90 of LAF over the trailing local-floor window, frozen at the event's start (dB(A)). */
    val localFloorDb: Double,
    /** LAFmax − [localFloorDb] (dB). */
    val excessDb: Double,
    /** Rise time 10 → 90 % of the excess on the 125 ms LAF curve (s); NaN if not measurable. */
    val riseS: Double,
    /** Decay time 90 → 10 % after the peak (s); NaN if the level did not fall to 10 % in time. */
    val decayS: Double,
    /** RMS second difference of the 125 ms LAF curve during the event / [excessDb]. */
    val jaggedness: Double,
    /** A-weighted 250 Hz–4.5 kHz level in the loudest 1 s minus the 5 s before the start (dB). */
    val midBandRiseDb: Double,
    /** Unweighted energy share 20–200 Hz of 20 Hz–8 kHz over the event (0…1). */
    val lfShare: Double,
    /** RMS of the detrended 125 ms level of 20–200 Hz over the event (dB). */
    val lfFlutterDb: Double,
    /** Wind on the microphone ([WindRule]); such events are excluded from the event counts. */
    val wind: Boolean,
    /** [EventShape] id: hump, jagged, impulse or long. */
    val shape: String,
)

/**
 * Wind on the microphone: `lfShare ≥ lfShareMin || lfFlutterDb ≥ flutterMinDb`.
 *
 * Defaults fitted on the whole recorded night 7./8.10.2026 (continuous recording replayed through
 * the engine, 340 detected events matched to the weakly labelled Labor clips, 81 of them wind):
 * 0.93 / 4.5 dB agree with the labels on 97.4 % (5 false, 4 missed), the first guess 0.95 / 3.8 dB
 * on 96.2 % (5 false, 8 missed); on the clips alone 96.9 % vs 95.1 %. This flutter estimator reads
 * ≈ 0.6× the offline analysis's 0.5–5 Hz band-pass flutter (r = 0.66), so its 3.8 dB did not carry over.
 */
data class WindRule(
    val lfShareMin: Double = DEFAULT_LF_SHARE,
    val flutterMinDb: Double = DEFAULT_FLUTTER_DB,
) {
    /** NaN never triggers. */
    fun isWind(lfShare: Double, lfFlutterDb: Double): Boolean = lfShare >= lfShareMin || lfFlutterDb >= flutterMinDb

    companion object {
        const val DEFAULT_LF_SHARE = 0.93
        const val DEFAULT_FLUTTER_DB = 4.5
        /** Category id of wind events (not part of the classifier mapping). */
        const val CATEGORY = "wind"
        const val NAME_DE = "Wind"
    }
}

/**
 * Pass shape (informational, not a filter). In this order:
 * 1. `long`    — duration ≥ [LONG_MIN_S];
 * 2. `impulse` — rise < [IMPULSE_MAX_RISE_S] and duration ≤ [IMPULSE_MAX_DURATION_S];
 * 3. `jagged`  — jaggedness ≥ [JAGGED_MIN];
 * 4. `hump`    — everything else (a smooth rise and fall, the typical vehicle pass).
 * The thresholds are provisional (chosen on synthetic signals and the reported medians, not fitted).
 */
object EventShape {
    const val HUMP = "hump"
    const val JAGGED = "jagged"
    const val IMPULSE = "impulse"
    const val LONG = "long"

    const val LONG_MIN_S = 30.0
    const val IMPULSE_MAX_RISE_S = 0.35
    const val IMPULSE_MAX_DURATION_S = 2.0
    const val JAGGED_MIN = 0.3

    fun classify(durationS: Double, riseS: Double, jaggedness: Double): String = when {
        durationS >= LONG_MIN_S -> LONG
        riseS < IMPULSE_MAX_RISE_S && durationS <= IMPULSE_MAX_DURATION_S -> IMPULSE
        jaggedness >= JAGGED_MIN -> JAGGED
        else -> HUMP
    }

    fun nameDe(id: String?): String = when (id) {
        HUMP -> "Buckel"
        JAGGED -> "zackig"
        IMPULSE -> "Impuls"
        LONG -> "lang"
        null -> "–"
        else -> id
    }
}

/**
 * Keeps the last [capacityTicks] 125 ms ticks (LAF and the mean squares of the A-weighted signal,
 * the A-weighted mid band, the 20–200 Hz band and the 20 Hz–8 kHz total; NaN for invalid ticks) and
 * computes [EventFeatures] for a span of them. Ticks are numbered from 0 in push order.
 *
 * Definitions (ticks i of the event: [start, end); F = local floor at the start; L(i) = LAF at the
 * end of tick i; Δ = 125 ms):
 * - **excessDb** = LAFmax − F (LAFmax from the continuous Fast signal).
 * - The curve's own peak Lp = max L(i) at tick p, its excess x = Lp − F, and the levels
 *   l10 = F + 0.1·x, l90 = F + 0.9·x.
 * - **riseS**: t90 = first upward crossing of l90 up to p; t10 = the last time before t90 that the
 *   curve was at or below l10, searched back through up to [preTicks] ticks before the start;
 *   rise = t90 − t10 (crossings linearly interpolated between ticks). NaN if not found.
 * - **decayS**: from the first fall below l90 after p to the first time at or below l10, within the
 *   event and its tail (up to [tailEnd]). NaN if it did not fall to l10 by then.
 * - **jaggedness** = sqrt(mean((L(i+1) − 2·L(i) + L(i−1))²)) over the inner ticks / excessDb.
 * - **midBandRiseDb** = 10·log10(mean band energy in the loudest 1 s of the event (8 consecutive
 *   ticks with the largest A-weighted energy; the whole event if shorter) / mean band energy over
 *   the 40 ticks (5 s) before the start). NaN without valid pre-event ticks.
 * - **lfShare** = Σ E(20–200 Hz) / Σ E(20 Hz–8 kHz) over the event ticks (unweighted).
 * - **lfFlutterDb**: Llf(i) = 10·log10 E(20–200 Hz) of tick i (un-time-weighted 125 ms level);
 *   detrended by a centred running **median** over ±h ticks, h = min([FLUTTER_HALF_WIDTH], ticks
 *   to the nearer edge of the event) (at most 17 ticks ≈ 2.1 s; symmetric, so it shrinks towards
 *   the edges). The median follows slow trends (< ≈ 0.5 Hz) and single steps (the on- and offset
 *   of any sound), so the residual is the fast, back-and-forth fluctuation (0.5 Hz up to the
 *   4 Hz the 8 Hz tick rate allows) that wind turbulence produces. lfFlutterDb = RMS of the
 *   residual over the event. NaN for < 4 ticks. (A moving average instead of the median would
 *   turn every abrupt on/offset into "flutter" — a 2 s tone burst would read ≈ 8 dB.)
 */
class EventFeatureTracker(val capacityTicks: Int, val tickSeconds: Double = 0.125, val preTicks: Int = 80) {
    companion object {
        /** Half width (ticks) of the moving average that detrends the LF level for the flutter. */
        const val FLUTTER_HALF_WIDTH = 8
        /** The pre-event span for [EventFeatures.midBandRiseDb] (5 s). */
        const val MID_BAND_PRE_TICKS = 40
        /** The "top 1 s" of the event for [EventFeatures.midBandRiseDb]. */
        const val MID_BAND_TOP_TICKS = 8
    }

    private val laf = DoubleArray(capacityTicks)
    private val eA = DoubleArray(capacityTicks)
    private val eBand = DoubleArray(capacityTicks)
    private val eLow = DoubleArray(capacityTicks)
    private val eTotal = DoubleArray(capacityTicks)
    /** Scratch for the flutter (levels, median window); sized for the longest event. */
    private val lfLevel = DoubleArray(capacityTicks)
    private val window = DoubleArray(2 * FLUTTER_HALF_WIDTH + 1)

    /** Number of ticks pushed so far = index of the next tick. */
    var count = 0L
        private set

    private fun slot(i: Long) = (i % capacityTicks).toInt()

    /** Oldest tick index still held. */
    val oldest: Long get() = maxOf(0L, count - capacityTicks)

    fun push(lafDb: Double, msA: Double, msBand: Double, msLow: Double, msTotal: Double): Long {
        val s = slot(count)
        laf[s] = lafDb; eA[s] = msA; eBand[s] = msBand; eLow[s] = msLow; eTotal[s] = msTotal
        return count++
    }

    fun pushInvalid(): Long = push(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)

    fun lafAt(i: Long): Double = if (i < oldest || i >= count) Double.NaN else laf[slot(i)]

    /** Crossing time (in ticks) of [level] between ticks a and a + 1, linearly interpolated. */
    private fun cross(a: Long, level: Double): Double {
        val la = lafAt(a)
        val lb = lafAt(a + 1)
        if (la.isNaN() || lb.isNaN() || lb == la) return (a + 1).toDouble()
        return a + ((level - la) / (lb - la)).coerceIn(0.0, 1.0)
    }

    /**
     * Features of the event of ticks [start, end) with the tail up to [tailEnd] (exclusive, ≥ end).
     * The ticks must still be held ([capacityTicks] ≥ preTicks + event + tail).
     */
    fun compute(
        start: Long, end: Long, tailEnd: Long, floorDb: Double, lafMaxDb: Double, durationS: Double, wind: WindRule,
    ): EventFeatures {
        val excess = lafMaxDb - floorDb
        val n = (end - start).toInt()
        var p = start
        var lp = Double.NEGATIVE_INFINITY
        for (i in start until end) {
            val v = lafAt(i)
            if (v > lp) { lp = v; p = i }
        }
        val x = lp - floorDb
        var rise = Double.NaN
        var decay = Double.NaN
        if (n > 0 && x > 0) {
            val hi = floorDb + 0.9 * x
            val lo = floorDb + 0.1 * x
            // Rise.
            var i90 = start
            while (i90 < p && !(lafAt(i90) >= hi)) i90++
            val t90 = if (lafAt(i90 - 1) < hi) cross(i90 - 1, hi) else i90.toDouble()
            var j = i90 - 1
            val minJ = maxOf(start - preTicks, oldest)
            var found = false
            while (j >= minJ) {
                val v = lafAt(j)
                if (v.isNaN()) break
                if (v <= lo) { found = true; break }
                j--
            }
            if (found) rise = (t90 - cross(j, lo)) * tickSeconds
            // Decay.
            val limit = minOf(tailEnd, count)
            var k = p + 1
            while (k < limit && lafAt(k) >= hi) k++
            if (k < limit && !lafAt(k).isNaN()) {
                val t90d = cross(k - 1, hi)
                var m = k
                while (m < limit && lafAt(m) > lo) m++
                if (m < limit && !lafAt(m).isNaN()) decay = (cross(m - 1, lo) - t90d) * tickSeconds
            }
        }

        // Jaggedness.
        var jag = Double.NaN
        if (n >= 3 && excess > 0) {
            var sum = 0.0
            for (i in start + 1 until end - 1) {
                val d2 = lafAt(i + 1) - 2 * lafAt(i) + lafAt(i - 1)
                sum += d2 * d2
            }
            jag = sqrt(sum / (n - 2)) / excess
        }

        // Mid band: loudest 1 s of the event vs the 5 s before it.
        var midRise = Double.NaN
        if (n > 0) {
            val w = minOf(MID_BAND_TOP_TICKS, n)
            var run = 0.0
            for (i in start until start + w) run += eA[slot(i)]
            var best = run
            var bestStart = start
            for (i in start + w until end) {
                run += eA[slot(i)] - eA[slot(i - w)]
                if (run > best) { best = run; bestStart = i - w + 1 }
            }
            var during = 0.0
            for (i in bestStart until bestStart + w) during += eBand[slot(i)]
            during /= w
            var pre = 0.0
            var preN = 0
            for (i in maxOf(start - MID_BAND_PRE_TICKS, oldest) until start) {
                val e = eBand[slot(i)]
                if (!e.isNaN()) { pre += e; preN++ }
            }
            if (preN > 0 && pre > 0 && during > 0) midRise = 10 * log10(during / (pre / preN))
        }

        // Low-frequency share and flutter.
        var low = 0.0
        var total = 0.0
        for (i in start until end) { low += eLow[slot(i)]; total += eTotal[slot(i)] }
        val lfShare = if (total > 0) low / total else Double.NaN
        var flutter = Double.NaN
        if (n >= 4) {
            for (k in 0 until n) lfLevel[k] = 10 * log10(maxOf(eLow[slot(start + k)], 1e-30))
            var sum = 0.0
            for (k in 0 until n) {
                val h = minOf(FLUTTER_HALF_WIDTH, k, n - 1 - k)
                val w = 2 * h + 1
                System.arraycopy(lfLevel, k - h, window, 0, w)
                val d = lfLevel[k] - QuickSelect.select(window, w, h)
                sum += d * d
            }
            flutter = sqrt(sum / n)
        }

        return EventFeatures(
            localFloorDb = floorDb,
            excessDb = excess,
            riseS = rise,
            decayS = decay,
            jaggedness = jag,
            midBandRiseDb = midRise,
            lfShare = lfShare,
            lfFlutterDb = flutter,
            wind = wind.isWind(lfShare, flutter),
            shape = EventShape.classify(durationS, rise, jag),
        )
    }
}

/**
 * Settings migration v0.3 → v0.4: the old start threshold over the 5-min background
 * (`eventThresholdDb`, default 10 dB) becomes an excess over the local floor. The 5-min L90 sits
 * about 3.5 dB below the local floor (median 3.1 dB in one recorded night), so the same physical
 * threshold is the old value − 3.5 dB, at least [EXCESS_MIN]: 10 → 6.5 (the new default), 7 → 3.5.
 */
object ThresholdMigration {
    const val LEGACY_DEFAULT_THRESHOLD_DB = 10.0
    const val EXCESS_MIN = 3.0
    const val EXCESS_MAX = 20.0
    const val OFFSET_DB = 3.5

    fun excessFromLegacyThreshold(oldThresholdDb: Double): Double =
        (oldThresholdDb - OFFSET_DB).coerceIn(EXCESS_MIN, EXCESS_MAX)
}
