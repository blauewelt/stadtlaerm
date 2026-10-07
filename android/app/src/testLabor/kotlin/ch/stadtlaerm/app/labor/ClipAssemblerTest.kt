package ch.stadtlaerm.app.labor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ring buffer + clip assembly. Runs at a 1 kHz "input rate" with the real proportions
 * (6 s ring, ±5 s, 60 s cap) to stay fast. Every sample's value is its own index, so a clip's
 * content shows exactly which input samples it holds.
 */
class ClipAssemblerTest {
    private val fs = 1000
    private val block = fs / 8 // 125 ms, like the capture blocks

    private class Feed(val a: ClipAssembler, val block: Int) {
        var pos = 0L
        /** Feeds samples up to (excluding) [until], calling [atSample] hooks before the block containing them. */
        fun to(until: Long, hooks: Map<Long, () -> Unit> = emptyMap()) {
            while (pos < until) {
                val b = FloatArray(block) { (pos + it).toFloat() }
                hooks.filterKeys { it in pos until pos + block }.values.forEach { it() }
                a.onBlock(b, block)
                pos += block
            }
        }
    }

    private fun assembler(out: MutableList<AssembledClip>, accept: (Long) -> Boolean = { true }, maxActive: Int = 4) =
        ClipAssembler(sampleRate = fs, accept = accept, maxActive = maxActive, chunkSamples = 700, sink = { out += it })

    private fun assertContiguous(c: AssembledClip) {
        val x = c.toArray()
        assertEquals(c.length, x.size)
        for (i in x.indices) assertEquals((c.clipStartSample + i).toFloat(), x[i], "sample $i")
    }

    @Test
    fun preAndPostRollBoundaries() {
        val out = ArrayList<AssembledClip>()
        val a = assembler(out)
        val f = Feed(a, block)
        val start = 10_000L
        val end = 12_500L
        // The engine reports the candidate while processing the block that contains its start.
        f.to(20_000, mapOf(start to { a.onCandidate(start) }, start + 500 to { a.onConfirmed(start) }, end to { a.onEnded(start, end) }))
        assertEquals(1, out.size)
        val c = out[0]
        assertEquals(start - 5 * fs, c.clipStartSample)
        assertEquals(5L * fs, c.preRollSamples)
        assertEquals((end + 5 * fs - (start - 5 * fs)).toInt(), c.length) // 5 s + event + 5 s
        assertEquals(start, c.eventStartSample); assertEquals(end, c.eventEndSample)
        assertFalse(c.truncated); assertFalse(c.endedEarly)
        assertContiguous(c)
        assertEquals(0, a.activeCount)
    }

    @Test
    fun clipIsCompletedExactlyWhenThePostRollIsIn() {
        val out = ArrayList<AssembledClip>()
        val a = assembler(out)
        val f = Feed(a, block)
        f.to(11_875, mapOf(6_000L to { a.onCandidate(6_000) }, 6_500L to { a.onConfirmed(6_000) }, 7_000L to { a.onEnded(6_000, 7_000) }))
        assertTrue(out.isEmpty()) // post-roll runs until 12 000 (exclusive)
        f.to(12_000)
        val c = out.single()
        assertEquals(1_000L, c.clipStartSample)
        assertEquals(12_000L, c.clipStartSample + c.length)
        assertContiguous(c)
    }

    @Test
    fun earlyEventHasShorterPreRoll() {
        val out = ArrayList<AssembledClip>()
        val a = assembler(out)
        val f = Feed(a, block)
        f.to(10_000, mapOf(2_000L to { a.onCandidate(2_000) }, 2_500L to { a.onConfirmed(2_000) }, 3_000L to { a.onEnded(2_000, 3_000) }))
        val c = out.single()
        assertEquals(0L, c.clipStartSample)
        assertEquals(2_000L, c.preRollSamples)
        assertEquals(8_000, c.length)
        assertContiguous(c)
    }

    @Test
    fun longEventIsTruncatedAt60Seconds() {
        val out = ArrayList<AssembledClip>()
        val a = assembler(out)
        val f = Feed(a, block)
        val start = 10_000L
        val end = start + 70 * fs
        f.to(end + 6 * fs, mapOf(start to { a.onCandidate(start) }, start + 500 to { a.onConfirmed(start) }, end to { a.onEnded(start, end) }))
        val c = out.single()
        assertEquals(60 * fs, c.length)
        assertEquals(start - 5 * fs, c.clipStartSample)
        assertTrue(c.truncated)
        assertContiguous(c)
    }

    @Test
    fun exactly60SecondsIsNotTruncated() {
        val out = ArrayList<AssembledClip>()
        val a = assembler(out)
        val f = Feed(a, block)
        val start = 10_000L
        val end = start + 50 * fs // 5 + 50 + 5 = 60 s
        f.to(end + 6 * fs, mapOf(start to { a.onCandidate(start) }, start + 500 to { a.onConfirmed(start) }, end to { a.onEnded(start, end) }))
        val c = out.single()
        assertEquals(60 * fs, c.length)
        assertFalse(c.truncated)
    }

    @Test
    fun discardedAndRejectedCandidatesProduceNothing() {
        val out = ArrayList<AssembledClip>()
        val a = assembler(out, accept = { it != 20_000L })
        val f = Feed(a, block)
        f.to(40_000, mapOf(
            10_000L to { a.onCandidate(10_000) }, 10_250L to { a.onDiscarded(10_000) },
            20_000L to { a.onCandidate(20_000) }, 20_500L to { a.onConfirmed(20_000) }, 21_000L to { a.onEnded(20_000, 21_000) },
        ))
        assertTrue(out.isEmpty())
        assertEquals(0, a.activeCount)
    }

    @Test
    fun overlappingClipsAreBothComplete() {
        val out = ArrayList<AssembledClip>()
        val a = assembler(out)
        val f = Feed(a, block)
        // B starts 2 s after A ends, inside A's post-roll; B's pre-roll covers A.
        f.to(30_000, mapOf(
            10_000L to { a.onCandidate(10_000) }, 10_500L to { a.onConfirmed(10_000) }, 11_000L to { a.onEnded(10_000, 11_000) },
            13_000L to { a.onCandidate(13_000) }, 13_500L to { a.onConfirmed(13_000) }, 14_000L to { a.onEnded(13_000, 14_000) },
        ))
        assertEquals(listOf(10_000L, 13_000L), out.map { it.eventStartSample })
        assertEquals(listOf(5_000L, 8_000L), out.map { it.clipStartSample })
        out.forEach { assertEquals(11_000, it.length); assertContiguous(it) }
    }

    @Test
    fun tooManyOpenClipsAreCounted() {
        val out = ArrayList<AssembledClip>()
        val a = assembler(out, maxActive = 1)
        val f = Feed(a, block)
        f.to(30_000, mapOf(
            10_000L to { a.onCandidate(10_000) }, 10_500L to { a.onConfirmed(10_000) }, 11_000L to { a.onEnded(10_000, 11_000) },
            13_000L to { a.onCandidate(13_000) }, 13_500L to { a.onConfirmed(13_000) }, 14_000L to { a.onEnded(13_000, 14_000) },
        ))
        assertEquals(listOf(10_000L), out.map { it.eventStartSample })
        assertEquals(1, a.droppedBusy)
    }

    @Test
    fun flushAtStopKeepsConfirmedClipsWithShorterPostRoll() {
        val out = ArrayList<AssembledClip>()
        val a = assembler(out)
        val f = Feed(a, block)
        f.to(13_000, mapOf(
            10_000L to { a.onCandidate(10_000) }, 10_500L to { a.onConfirmed(10_000) }, 12_000L to { a.onEnded(10_000, 12_000) },
        ))
        // A second, unconfirmed candidate at the very end.
        a.onCandidate(13_000)
        f.to(13_125)
        a.flush()
        val c = out.single()
        assertTrue(c.endedEarly)
        assertEquals(5_000L, c.clipStartSample)
        assertEquals(13_125 - 5_000, c.length)
        assertContiguous(c)
        assertEquals(0, a.activeCount)
    }
}
