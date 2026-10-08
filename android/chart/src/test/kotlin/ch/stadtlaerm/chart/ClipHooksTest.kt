package ch.stadtlaerm.chart

import ch.stadtlaerm.dsp.Iso
import ch.stadtlaerm.dsp.MinuteRecord
import ch.stadtlaerm.dsp.NoiseEvent
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The optional clip hooks (filled only by the Labor build): dot accent and «Abspielen» button. */
class ClipHooksTest {
    private val zone = SyntheticData.zone
    private val night = Windows(zone).of(RangeMode.NIGHT, LocalDate.of(2026, 10, 9))
    private val after = night.endMs + 3 * 3_600_000L

    private fun at(h: Int, m: Int) = SyntheticData.ms(if (h >= 12) LocalDateTime.of(2026, 10, 9, h, m) else LocalDateTime.of(2026, 10, 10, h, m))

    private fun minute(t: Long, laeq: Double) = MinuteRecord(
        t, Iso.format(t, zone), 60.0, laeq, laeq + 10, laeq - 5, laeq + 8, laeq + 3, laeq, laeq - 3, 0, null, emptyMap(), 0, null, 112.35,
        "UNPROCESSED", true, validSeconds = 60.0,
    )

    private fun event(t: Long, max: Double, cat: String = "road_traffic") = NoiseEvent(
        t, Iso.format(t, zone, true), 2.0, max, max, 40.0, 10.0, cat, 0.5f, emptyList(), 1, null, "UNPROCESSED", true,
    )

    private val minutes = (0 until 60).map { minute(at(22, 0) + it * 60_000L, 40.0) }
    private val withClip = event(at(22, 10), 70.0)
    private val withClipHighlighted = event(at(22, 30), 75.0, "loud_vehicle")
    private val withoutClip = event(at(22, 50), 72.0)
    private val events = listOf(withClip, withClipHighlighted, withoutClip)
    private val refs = mapOf(withClip.startEpochMs to "clips/ev_1.wav", withClipHighlighted.startEpochMs to "clips/ev_2.wav")

    private fun geometry(clipRefs: Map<Long, String>): ChartGeometry {
        val model = ChartModel.build(ChartData(night, minutes, events, after, clipRefs = clipRefs), zone, "loud_vehicle", 45.0)
        return ChartGeometry(model, PlotRect(40f, 10f, 1040f, 600f))
    }

    private fun tap(g: ChartGeometry, e: NoiseEvent): Selection.Event {
        val s = g.hitTest(g.xOf(e.startEpochMs), g.yOf(e.lafMaxDb), 40f)
        return assertIs<Selection.Event>(s)
    }

    @Test
    fun dotAccentOnlyWithClipRef() {
        val g = geometry(refs)
        assertEquals(setOf(withClip, withClipHighlighted), g.clipAccentDots.map { it.event }.toSet())
        val plain = (g.otherDots + g.highlightDots).single { it.event == withoutClip }
        assertTrue(!plain.hasClipAccent)
        assertNull(plain.clipRef)
        assertEquals("clips/ev_2.wav", g.highlightDots.single().clipRef)
        assertTrue(g.highlightDots.single().hasClipAccent)

        // Public app: no clip references, no accent anywhere.
        val pub = geometry(emptyMap())
        assertTrue(pub.clipAccentDots.isEmpty())
        assertTrue((pub.otherDots + pub.highlightDots).all { it.clipRef == null && !it.hasClipAccent })
    }

    @Test
    fun tooltipShowsPlayButtonOnlyWithClipRef() {
        val g = geometry(refs)
        val clipped = tap(g, withClip)
        assertEquals("clips/ev_1.wav", clipped.clipRef)
        assertEquals("clips/ev_1.wav", tooltipPlayClip(clipped, canPlay = true))
        assertEquals("clips/ev_2.wav", tooltipPlayClip(tap(g, withClipHighlighted), canPlay = true))
        // No clip → no button.
        val plain = tap(g, withoutClip)
        assertNull(plain.clipRef)
        assertNull(tooltipPlayClip(plain, canPlay = true))
        // No callback (public app) → no button even with a clip reference.
        assertNull(tooltipPlayClip(clipped, canPlay = false))
        // Minutes and gaps never get one.
        val point = g.hitTest(g.xOf(at(22, 40)), 590f, 1f)
        assertIs<Selection.Point>(point)
        assertNull(tooltipPlayClip(point, canPlay = true))
        assertNull(tooltipPlayClip(null, canPlay = true))
        // The text lines are the same with or without a clip.
        val m = g.model
        assertEquals(tooltipLines(Selection.Event(withClip, false), m), tooltipLines(clipped, m))

        // Public app: hit-tested events carry no clip reference.
        assertNull(tooltipPlayClip(tap(geometry(emptyMap()), withClip), canPlay = true))
    }

    @Test
    fun clipRefsInTimeOrderFollowEventTimeAndFloor() {
        val quiet = event(at(22, 5), 44.0)
        val data = ChartData(
            night, minutes, listOf(withoutClip, withClipHighlighted, quiet, withClip), after,
            clipRefs = refs + (quiet.startEpochMs to "clips/ev_q.wav"),
        )
        assertEquals(listOf("clips/ev_1.wav", "clips/ev_2.wav"), data.clipRefsInTimeOrder(45.0))
        assertEquals(listOf("clips/ev_q.wav", "clips/ev_1.wav", "clips/ev_2.wav"), data.clipRefsInTimeOrder(40.0))
        assertTrue(ChartData(night, minutes, events, after).clipRefsInTimeOrder(45.0).isEmpty())
    }
}
