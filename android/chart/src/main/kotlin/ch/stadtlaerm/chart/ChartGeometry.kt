package ch.stadtlaerm.chart

import ch.stadtlaerm.dsp.NoiseEvent
import kotlin.math.hypot

/** Plot area in pixels. */
data class PlotRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

/**
 * An event dot in pixels. [clipRef] is set only if the event has an audio clip (Labor build); the
 * dot then gets a small ring accent, its fill colour keeps the category meaning.
 */
data class Dot(val x: Float, val y: Float, val event: NoiseEvent, val highlighted: Boolean, val clipRef: String? = null) {
    val hasClipAccent: Boolean get() = clipRef != null
}

/** What a tap selected (shown as crosshair + tooltip). */
sealed interface Selection {
    data class Point(val point: SeriesPoint) : Selection
    data class Event(val event: NoiseEvent, val highlighted: Boolean, val clipRef: String? = null) : Selection
    /** A time without valid data. */
    data class NoData(val timeMs: Long) : Selection
}

/**
 * Pixel-space drawing model of a [ChartModel] for one plot size. Pure Kotlin (no Android types):
 * the composable turns the float arrays into paths. Built with remember(model, size), not per frame.
 */
class ChartGeometry(val model: ChartModel, val plot: PlotRect) {
    private val w = model.window
    private val y = model.yRange

    fun xOf(t: Long): Float = plot.left + ((t - w.startMs).toDouble() / w.durationMs).toFloat() * plot.width
    fun timeAt(x: Float): Long = w.startMs + (((x - plot.left) / plot.width).toDouble() * w.durationMs).toLong()
    fun yOf(db: Double): Float = plot.bottom - (((db - y.lo) / y.span).coerceIn(0.0, 1.0)).toFloat() * plot.height

    /** LAeq polyline per contiguous run: x0, y0, x1, y1, … A single sample spans its own duration. */
    val lines: List<FloatArray> = model.segments.map { seg ->
        if (seg.size == 1) {
            val p = seg[0]
            floatArrayOf(xOf(p.startMs), yOf(p.laeq), xOf(p.endMs), yOf(p.laeq))
        } else {
            FloatArray(seg.size * 2).also { a -> seg.forEachIndexed { i, p -> a[2 * i] = xOf(p.midMs); a[2 * i + 1] = yOf(p.laeq) } }
        }
    }

    /** L90–L10 band per run as a closed polygon: L10 left→right, then L90 right→left. */
    val bands: List<FloatArray> = model.segments.map { seg ->
        val xs: List<Float>
        val pts: List<SeriesPoint>
        if (seg.size == 1) {
            xs = listOf(xOf(seg[0].startMs), xOf(seg[0].endMs)); pts = listOf(seg[0], seg[0])
        } else {
            xs = seg.map { xOf(it.midMs) }; pts = seg
        }
        val n = pts.size
        FloatArray(n * 4).also { a ->
            for (i in 0 until n) { a[2 * i] = xs[i]; a[2 * i + 1] = yOf(pts[i].l10) }
            for (i in 0 until n) { val j = n - 1 - i; a[2 * n + 2 * i] = xs[j]; a[2 * n + 2 * i + 1] = yOf(pts[j].l90) }
        }
    }

    /** Gap rectangles as (left, right) pixel pairs. */
    val gaps: List<Pair<Float, Float>> = model.gaps.map { xOf(it.span.startMs) to xOf(it.span.endMs) }
    val nights: List<Pair<Float, Float>> = model.nightSpans.map { xOf(it.startMs) to xOf(it.endMs) }
    val gridX: List<Float> = model.gridTicks.map { xOf(it) }
    val gridY: List<Pair<Double, Float>> = model.yRange.gridlines().map { it to yOf(it) }
    val labelsX: List<Pair<String, Float>> = model.axisLabels.map { it.text to xOf(it.ms) }

    val otherDots: List<Dot> = model.otherEvents.map { Dot(xOf(it.startEpochMs), yOf(it.lafMaxDb), it, false, model.clipRefOf(it)) }
    val highlightDots: List<Dot> = model.highlightedEvents.map { Dot(xOf(it.startEpochMs), yOf(it.lafMaxDb), it, true, model.clipRefOf(it)) }
    /** Dots that get the clip accent (events with an audio clip; none in the public app). */
    val clipAccentDots: List<Dot> get() = (otherDots + highlightDots).filter { it.hasClipAccent }

    /** Crosshair x of a selection. */
    fun selectionX(s: Selection): Float = when (s) {
        is Selection.Point -> xOf(s.point.midMs)
        is Selection.Event -> xOf(s.event.startEpochMs)
        is Selection.NoData -> xOf(s.timeMs)
    }

    /**
     * Tap at (x, y): an event dot within [eventRadiusPx] wins (the nearest one; on a tie the
     * highlighted one); otherwise the nearest minute (hour in the week view) with data; otherwise
     * "no data" at that time. Null outside the plot (unless an event dot near its edge is hit).
     */
    fun hitTest(x: Float, y: Float, eventRadiusPx: Float): Selection? {
        var best: Dot? = null
        var bestD = Float.MAX_VALUE
        for (d in highlightDots + otherDots) {
            val dist = hypot(d.x - x, d.y - y)
            if (dist <= eventRadiusPx && dist < bestD) { best = d; bestD = dist }
        }
        best?.let { return Selection.Event(it.event, it.highlighted, it.clipRef) }
        if (!plot.contains(x, y)) return null
        val t = timeAt(x)
        if (t > model.nowMs) return null
        return model.nearestValidPoint(t)?.let { Selection.Point(it) } ?: Selection.NoData(t)
    }
}

/** Which "keine Messung" label a gap gets: one line if it fits, else two lines, else none. */
object GapLabel {
    const val NONE = 0
    const val ONE_LINE = 1
    const val TWO_LINES = 2
    fun choose(gapWidthPx: Float, oneLineWidthPx: Float, twoLineWidthPx: Float, marginPx: Float): Int = when {
        gapWidthPx >= oneLineWidthPx + marginPx -> ONE_LINE
        gapWidthPx >= twoLineWidthPx + marginPx -> TWO_LINES
        else -> NONE
    }
}
