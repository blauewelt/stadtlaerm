package ch.stadtlaerm.dsp

/**
 * Percentile levels. `Ln` is the level exceeded n % of the time, i.e. the (100 − n)th percentile
 * of the level distribution — so L10 ≥ L50 ≥ L90.
 */
object Percentiles {

    /** Linear-interpolated percentile (0…100) of an already sorted array. */
    fun percentileSorted(sorted: DoubleArray, p: Double): Double {
        require(p in 0.0..100.0)
        if (sorted.isEmpty()) return Double.NaN
        if (sorted.size == 1) return sorted[0]
        val pos = p / 100.0 * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        val frac = pos - lo
        return sorted[lo] + (sorted[hi] - sorted[lo]) * frac
    }

    /** Level exceeded [n] percent of the time. */
    fun exceedanceLevelSorted(sorted: DoubleArray, n: Double): Double = percentileSorted(sorted, 100.0 - n)

    fun exceedanceLevel(values: DoubleArray, n: Double): Double =
        exceedanceLevelSorted(values.copyOf().also { it.sort() }, n)

    data class Stats(val min: Double, val max: Double, val l1: Double, val l10: Double, val l50: Double, val l90: Double)

    fun stats(values: DoubleArray): Stats {
        if (values.isEmpty()) return Stats(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        val s = values.copyOf().also { it.sort() }
        return Stats(
            min = s.first(), max = s.last(),
            l1 = exceedanceLevelSorted(s, 1.0),
            l10 = exceedanceLevelSorted(s, 10.0),
            l50 = exceedanceLevelSorted(s, 50.0),
            l90 = exceedanceLevelSorted(s, 90.0),
        )
    }
}

/** Fixed-capacity ring of doubles (used for the trailing background window). */
class DoubleRing(val capacity: Int) {
    private val data = DoubleArray(capacity)
    private var next = 0
    var size = 0
        private set

    fun add(v: Double) {
        data[next] = v
        next = (next + 1) % capacity
        if (size < capacity) size++
    }

    fun toArray(): DoubleArray {
        val out = DoubleArray(size)
        val start = (next - size + capacity) % capacity
        for (i in 0 until size) out[i] = data[(start + i) % capacity]
        return out
    }

    fun clear() {
        next = 0; size = 0
    }
}
