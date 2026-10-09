package ch.stadtlaerm.app.labor

// LABOR BUILD ONLY (app/src/labor/).

/** An event clip, still at the input rate (48 kHz), as handed to the clip writer. */
class AssembledClip(
    val eventStartSample: Long,
    val eventEndSample: Long,
    /** Input sample index of the clip's first sample. */
    val clipStartSample: Long,
    val chunks: List<FloatArray>,
    val length: Int,
    /** The clip was cut at the maximum length (event + pre/post-roll longer than allowed). */
    val truncated: Boolean,
    /** The post-roll was cut short because the measurement stopped. */
    val endedEarly: Boolean,
) {
    val preRollSamples: Long get() = eventStartSample - clipStartSample

    fun toArray(): FloatArray {
        val a = FloatArray(length)
        var p = 0
        for (c in chunks) {
            val k = minOf(c.size, length - p)
            if (k <= 0) break
            c.copyInto(a, p, 0, k); p += k
        }
        return a
    }
}

/**
 * Builds event clips from the capture stream: a ring buffer holds the last [ringSamples] of input
 * audio; when an event candidate starts, a clip begins [preRollSamples] before it (as far as the
 * ring reaches back) and then follows the live audio until [postRollSamples] after the event's end,
 * at most [maxClipSamples] in total (longer clips are truncated and flagged). Candidates that are
 * discarded, or that [accept] rejects at confirmation (clip rate), are dropped.
 *
 * Sample indices count input samples since the start of the measurement, exactly like the engine.
 * [onEnded] may come after the post-roll has already been copied (the engine reports the end after
 * the event's tail); the clip is then cut back to end + post-roll.
 * The lifecycle calls ([onCandidate] …) may come before the block containing that sample has been
 * passed to [onBlock] (the engine reports them while processing the block); clips are filled only in
 * [onBlock], after the block has been appended to the ring.
 *
 * Not thread-safe: use from the capture thread only (and [flush] after capture has stopped).
 */
class ClipAssembler(
    val sampleRate: Int = 48_000,
    val preRollSamples: Int = 5 * sampleRate,
    val postRollSamples: Int = 5 * sampleRate,
    val maxClipSamples: Int = 60 * sampleRate,
    val ringSamples: Int = 6 * sampleRate,
    /** Clips being assembled at once (overlapping pre/post-rolls); more are dropped and counted. */
    val maxActive: Int = 4,
    private val chunkSamples: Int = sampleRate,
    private val accept: (eventStartSample: Long) -> Boolean = { true },
    private val sink: (AssembledClip) -> Unit,
) {
    init {
        require(ringSamples >= preRollSamples) { "ring must hold the pre-roll" }
    }

    private val ring = FloatArray(ringSamples)
    /** Absolute index of the next sample to be appended (= samples seen so far). */
    var position = 0L
        private set

    private class Active(val eventStart: Long, var clipStart: Long) {
        var confirmed = false
        var eventEnd = -1L
        var filledUntil = clipStart
        val chunks = ArrayList<FloatArray>()
        var length = 0
    }

    private val active = ArrayList<Active>()

    /** Candidates that could not be followed because [maxActive] clips were already open. */
    var droppedBusy = 0L
        private set

    val activeCount: Int get() = active.size

    fun onCandidate(eventStartSample: Long) {
        if (active.size >= maxActive) { droppedBusy++; return }
        active += Active(eventStartSample, maxOf(0L, eventStartSample - preRollSamples))
    }

    fun onConfirmed(eventStartSample: Long) {
        val a = find(eventStartSample) ?: return
        if (accept(eventStartSample)) a.confirmed = true else active.remove(a)
    }

    fun onDiscarded(eventStartSample: Long) {
        find(eventStartSample)?.let { active.remove(it) }
    }

    fun onEnded(eventStartSample: Long, eventEndSample: Long) {
        val a = find(eventStartSample) ?: return
        a.eventEnd = eventEndSample
        pump()
    }

    private fun find(start: Long): Active? = active.firstOrNull { it.eventStart == start }

    /** Appends a capture block, then extends the open clips and completes the finished ones. */
    fun onBlock(samples: FloatArray, count: Int) {
        var src = 0
        while (src < count) {
            val at = (position % ringSamples).toInt()
            val k = minOf(count - src, ringSamples - at)
            samples.copyInto(ring, at, src, src + k)
            src += k
            position += k
        }
        pump()
    }

    private fun limit(a: Active): Long {
        val cap = a.clipStart + maxClipSamples
        return if (a.eventEnd >= 0) minOf(a.eventEnd + postRollSamples, cap) else cap
    }

    private fun pump() {
        if (active.isEmpty()) return
        val oldest = maxOf(0L, position - ringSamples)
        val it = active.iterator()
        while (it.hasNext()) {
            val a = it.next()
            if (a.length == 0 && a.clipStart < oldest) {
                // The ring no longer reaches back that far (late start): shorten the pre-roll.
                a.clipStart = minOf(oldest, a.eventStart); a.filledUntil = a.clipStart
            }
            val lim = limit(a)
            // The end can be reported late (since 0.4.0 after the event's decay tail, ≤ 5 s): audio
            // already copied beyond the post-roll is cut off again.
            if (a.filledUntil > lim && a.filledUntil > a.clipStart) {
                val cut = minOf(a.filledUntil - lim, a.length.toLong()).toInt()
                a.length -= cut
                a.filledUntil -= cut
            }
            val until = minOf(position, lim)
            if (until > a.filledUntil) copy(a, a.filledUntil, until)
            if (a.confirmed && a.eventEnd >= 0 && a.filledUntil >= limit(a)) {
                it.remove()
                emit(a, endedEarly = false)
            }
        }
    }

    private fun copy(a: Active, from: Long, until: Long) {
        var s = from
        while (s < until) {
            val off = a.length % chunkSamples
            if (off == 0) a.chunks += FloatArray(chunkSamples)
            val chunk = a.chunks.last()
            val ringAt = (s % ringSamples).toInt()
            val k = minOf(until - s, (chunkSamples - off).toLong(), (ringSamples - ringAt).toLong()).toInt()
            ring.copyInto(chunk, off, ringAt, ringAt + k)
            a.length += k
            s += k
        }
        a.filledUntil = until
    }

    private fun emit(a: Active, endedEarly: Boolean) {
        val cap = a.clipStart + maxClipSamples
        // Truncated = the clip would have needed more than the maximum length.
        val truncated = if (a.eventEnd >= 0) a.eventEnd + postRollSamples > cap else a.filledUntil >= cap
        sink(
            AssembledClip(
                eventStartSample = a.eventStart,
                eventEndSample = if (a.eventEnd >= 0) a.eventEnd else a.filledUntil,
                clipStartSample = a.clipStart,
                chunks = a.chunks,
                length = a.length,
                truncated = truncated,
                endedEarly = endedEarly,
            )
        )
    }

    /**
     * Measurement stopped: confirmed clips are completed with the audio there is (shorter
     * post-roll); unconfirmed candidates are dropped. Clears the ring.
     */
    fun flush() {
        pump()
        for (a in active) {
            if (a.confirmed && a.length > 0) emit(a, endedEarly = true)
        }
        active.clear()
        ring.fill(0f)
    }
}
