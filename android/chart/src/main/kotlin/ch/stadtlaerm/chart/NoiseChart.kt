package ch.stadtlaerm.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Pixel sizes of the chart, resolved from dp once per density. */
private class ChartDims(d: androidx.compose.ui.unit.Density) {
    val dotRadius = with(d) { 4.5.dp.toPx() } // 9 dp dot
    val ring = with(d) { 2.dp.toPx() }
    val touchRadius = with(d) { 16.dp.toPx() }
    val line = with(d) { 2.dp.toPx() }
    val grid = with(d) { 0.75.dp.toPx() }
    val gap4 = with(d) { 4.dp.toPx() }
    val gap6 = with(d) { 6.dp.toPx() }
    val gap8 = with(d) { 8.dp.toPx() }
    val tooltipPad = with(d) { 8.dp.toPx() }
    val corner = with(d) { 8.dp.toPx() }
    val flingVelocity = with(d) { 800.dp.toPx() }
}

/** Colours of one render (theme text colours + the chart palette). */
private class ChartColors(
    val series1: Color,
    val series2: Color,
    val other: Color,
    val grid: Color,
    val label: Color,
    val text: Color,
    val surface: Color,
    val night: Color,
    val gapFill: Color,
    val band: Color,
    val tooltipBg: Color,
    val tooltipBorder: Color,
    val crosshair: Color,
)

/**
 * The noise chart: background band (L90–L10), LAeq line, event dots, gaps, night shading, axes,
 * crosshair and tooltip. [selection] is hoisted so that it can be driven from outside (tests).
 *
 * Gestures: a tap (or a drag shorter than 15 % of the width) selects the nearest event (within
 * 16 dp) or minute and shows the tooltip, which stays until the tooltip itself or the area outside
 * the plot is tapped; while it is open, horizontal dragging moves the crosshair. With no tooltip
 * open, a horizontal drag of ≥ 15 % of the width or a fling moves one window earlier/later.
 * Vertical drags are left to the scrolling list.
 */
@Composable
fun NoiseChart(
    model: ChartModel,
    selection: Selection?,
    onSelect: (Selection?) -> Unit,
    onNavigate: (Int) -> Unit,
    canGoNext: Boolean,
    surfaceColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 248.dp,
) {
    val density = LocalDensity.current
    val dims = remember(density) { ChartDims(density) }
    val scheme = MaterialTheme.colorScheme
    val palette = ChartPalette.current()
    val colors = remember(scheme, palette, surfaceColor) {
        ChartColors(
            series1 = palette.series1,
            series2 = palette.series2,
            other = scheme.onSurfaceVariant.copy(alpha = 0.55f),
            grid = scheme.onSurface.copy(alpha = 0.11f),
            label = scheme.onSurfaceVariant,
            text = scheme.onSurface,
            surface = surfaceColor,
            night = scheme.surfaceVariant.copy(alpha = 0.55f),
            gapFill = scheme.onSurfaceVariant.copy(alpha = 0.10f),
            band = palette.series1.copy(alpha = palette.bandAlpha),
            tooltipBg = scheme.surface,
            tooltipBorder = scheme.outline.copy(alpha = 0.5f),
            crosshair = scheme.onSurface.copy(alpha = 0.45f),
        )
    }
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.label)
    val tipStyle = MaterialTheme.typography.bodySmall.copy(color = colors.text)

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = with(density) { height.toPx() }

        // Everything that depends only on data, mode and size: computed once, not per frame.
        val layout = remember(model, widthPx, heightPx, labelStyle) {
            ChartLayout.build(model, widthPx, heightPx, dims, measurer, labelStyle)
        }
        val tooltip = remember(selection, model, tipStyle) {
            selection?.let { tooltipLines(it, model) }?.let { lines ->
                lines.mapIndexed { i, s ->
                    measurer.measure(s, if (i == 0) tipStyle.copy(fontWeight = FontWeight.SemiBold) else tipStyle)
                }
            }
        }

        val currentLayout by rememberUpdatedState(layout)
        val currentSelection by rememberUpdatedState(selection)
        val currentTooltipRect by rememberUpdatedState(tooltip?.let { tooltipRect(layout, selection!!, it, dims) })
        val currentCanGoNext by rememberUpdatedState(canGoNext)
        val select by rememberUpdatedState(onSelect)
        val navigate by rememberUpdatedState(onNavigate)

        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .semantics { contentDescription = "Lärmverlauf" }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val geo = currentLayout.geometry
                        val tooltipOpen = currentSelection != null
                        val start = down.position
                        val tracker = VelocityTracker()
                        tracker.addPosition(down.uptimeMillis, down.position)
                        var last = down.position
                        var horizontal = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            last = change.position
                            if (!change.pressed) break
                            val dx = change.position.x - start.x
                            val dy = change.position.y - start.y
                            if (!horizontal) {
                                if (abs(dx) > viewConfiguration.touchSlop && abs(dx) > abs(dy)) horizontal = true
                                else if (abs(dy) > viewConfiguration.touchSlop) return@awaitEachGesture // list scrolls
                            }
                            if (horizontal) {
                                change.consume()
                                if (tooltipOpen) geo.hitTest(change.position.x, geo.plot.top + 1f, 0f)?.let(select)
                            }
                        }
                        val dx = last.x - start.x
                        if (horizontal && tooltipOpen) return@awaitEachGesture // scrubbing ended
                        if (horizontal) {
                            val vx = tracker.calculateVelocity().x
                            val swipe = abs(dx) >= 0.15f * size.width || (abs(vx) >= dims.flingVelocity && abs(dx) > viewConfiguration.touchSlop)
                            if (swipe) {
                                val dir = if ((if (abs(dx) >= 0.15f * size.width) dx else vx) < 0) 1 else -1
                                if (dir < 0 || currentCanGoNext) navigate(dir)
                                return@awaitEachGesture
                            }
                        }
                        // Tap (or short drag): tooltip tap closes it, plot tap selects, outside clears.
                        val tipRect = currentTooltipRect
                        if (tipRect != null && last.x in tipRect.left..tipRect.right && last.y in tipRect.top..tipRect.bottom) {
                            select(null)
                        } else {
                            select(geo.hitTest(last.x, last.y, dims.touchRadius))
                        }
                    }
                },
        ) {
            drawChart(layout, colors, dims)
            if (selection != null && tooltip != null) drawSelection(layout, selection, tooltip, colors, dims)
        }
    }
}

/** Data- and size-dependent drawing model including measured axis labels. */
private class ChartLayout(
    val geometry: ChartGeometry,
    val yLabels: List<Pair<TextLayoutResult, Float>>,
    val xLabels: List<Pair<TextLayoutResult, Float>>,
    val gapLabel: TextLayoutResult,
    val gapLabel2: TextLayoutResult,
    val nightLabel: TextLayoutResult?,
    val bandPaths: List<Path>,
    val linePaths: List<Path>,
    val width: Float,
    val height: Float,
) {
    companion object {
        fun build(
            model: ChartModel, width: Float, height: Float, dims: ChartDims,
            measurer: androidx.compose.ui.text.TextMeasurer, style: TextStyle,
        ): ChartLayout {
            val yTexts = model.yRange.gridlines().map { measurer.measure(ChartFmt.db(it, 0), style) }
            val yLabelW = (yTexts.maxOfOrNull { it.size.width } ?: 0).toFloat()
            val xLabelH = measurer.measure("0", style).size.height.toFloat()
            val plot = PlotRect(
                left = yLabelW + dims.gap6,
                top = dims.gap8,
                right = width - dims.gap4,
                bottom = height - xLabelH - dims.gap6,
            )
            val g = ChartGeometry(model, plot)
            val yLabels = g.gridY.mapIndexed { i, (_, y) -> yTexts[i] to y }
            val xLabels = g.labelsX.map { (text, x) -> measurer.measure(text, style) to x }
            val bandPaths = g.bands.map { a ->
                Path().apply {
                    moveTo(a[0], a[1])
                    var i = 2
                    while (i < a.size) { lineTo(a[i], a[i + 1]); i += 2 }
                    close()
                }
            }
            val linePaths = g.lines.map { a ->
                Path().apply {
                    moveTo(a[0], a[1])
                    var i = 2
                    while (i < a.size) { lineTo(a[i], a[i + 1]); i += 2 }
                }
            }
            return ChartLayout(
                g, yLabels, xLabels,
                gapLabel = measurer.measure("keine Messung", style),
                gapLabel2 = measurer.measure("keine\nMessung", style.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center)),
                nightLabel = if (model.window.mode == RangeMode.DAY) measurer.measure("Nacht", style) else null,
                bandPaths = bandPaths, linePaths = linePaths, width = width, height = height,
            )
        }
    }
}

private fun DrawScope.drawChart(l: ChartLayout, c: ChartColors, d: ChartDims) {
    val g = l.geometry
    val p = g.plot
    // Night shading (22–06), then gaps.
    for ((x0, x1) in g.nights) drawRect(c.night, Offset(x0, p.top), Size(x1 - x0, p.height))
    l.nightLabel?.let { lab ->
        for ((x0, x1) in g.nights) {
            if (x1 - x0 >= lab.size.width + d.gap8) drawText(lab, topLeft = Offset((x0 + x1 - lab.size.width) / 2, p.top + d.gap4))
        }
    }
    for ((x0, x1) in g.gaps) {
        drawRect(c.gapFill, Offset(x0, p.top), Size(x1 - x0, p.height))
        val choice = GapLabel.choose(x1 - x0, l.gapLabel.size.width.toFloat(), l.gapLabel2.size.width.toFloat(), d.gap8)
        if (choice != GapLabel.NONE) {
            val lab = if (choice == GapLabel.ONE_LINE) l.gapLabel else l.gapLabel2
            drawText(lab, topLeft = Offset((x0 + x1 - lab.size.width) / 2, p.top + (p.height - lab.size.height) / 2))
        }
    }
    // Grid.
    for ((_, y) in g.gridY) drawLine(c.grid, Offset(p.left, y), Offset(p.right, y), d.grid)
    for (x in g.gridX) drawLine(c.grid, Offset(x, p.top), Offset(x, p.bottom), d.grid)
    // Background band and LAeq line, per contiguous run.
    for (path in l.bandPaths) drawPath(path, c.band)
    for (path in l.linePaths) {
        drawPath(path, c.series1, style = Stroke(width = d.line, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
    // Events: others below, highlighted on top; each with a ring in the card surface colour.
    for (dot in g.otherDots) {
        drawCircle(c.surface, d.dotRadius + d.ring, Offset(dot.x, dot.y))
        drawCircle(c.other, d.dotRadius, Offset(dot.x, dot.y))
    }
    for (dot in g.highlightDots) {
        drawCircle(c.surface, d.dotRadius + d.ring, Offset(dot.x, dot.y))
        drawCircle(c.series2, d.dotRadius, Offset(dot.x, dot.y))
    }
    // Axis labels (text colours only).
    for ((lab, y) in l.yLabels) {
        val top = (y - lab.size.height / 2f).coerceIn(0f, l.height - lab.size.height)
        drawText(lab, topLeft = Offset(p.left - d.gap6 - lab.size.width, top))
    }
    var lastRight = Float.NEGATIVE_INFINITY
    for ((lab, x) in l.xLabels) {
        val left = (x - lab.size.width / 2f).coerceIn(0f, l.width - lab.size.width)
        if (left < lastRight + d.gap4) continue // never overlap labels
        drawText(lab, topLeft = Offset(left, p.bottom + d.gap4))
        lastRight = left + lab.size.width
    }
}

/** Tooltip box position: right of the crosshair, flipped left near the right edge, inside the plot. */
private fun tooltipRect(l: ChartLayout, s: Selection, lines: List<TextLayoutResult>, d: ChartDims): androidx.compose.ui.geometry.Rect {
    val p = l.geometry.plot
    val w = lines.maxOf { it.size.width } + 2 * d.tooltipPad
    val h = lines.sumOf { it.size.height } + 2 * d.tooltipPad
    val x = l.geometry.selectionX(s)
    var left = x + d.gap8
    if (left + w > p.right) left = x - d.gap8 - w
    left = left.coerceIn(0f, maxOf(0f, l.width - w))
    // Keep the tooltip away from the selected dot vertically when possible.
    var top = p.top + d.gap4
    if (s is Selection.Event) {
        val ey = l.geometry.yOf(s.event.lafMaxDb)
        if (ey < top + h + d.gap8 && x in left - d.touchRadius..left + w + d.touchRadius) top = minOf(p.bottom - h, ey + d.touchRadius)
    }
    return androidx.compose.ui.geometry.Rect(left, top, left + w, top + h)
}

private fun DrawScope.drawSelection(l: ChartLayout, s: Selection, lines: List<TextLayoutResult>, c: ChartColors, d: ChartDims) {
    val g = l.geometry
    val p = g.plot
    val x = g.selectionX(s)
    drawLine(c.crosshair, Offset(x, p.top), Offset(x, p.bottom), d.grid * 1.5f)
    when (s) {
        is Selection.Point -> {
            val y = g.yOf(s.point.laeq)
            drawCircle(c.surface, d.dotRadius * 0.8f + d.ring, Offset(x, y))
            drawCircle(c.series1, d.dotRadius * 0.8f, Offset(x, y))
        }
        is Selection.Event -> {
            val y = g.yOf(s.event.lafMaxDb)
            drawCircle(c.text, d.dotRadius + d.ring + d.grid * 2, Offset(x, y), style = Stroke(d.grid * 2))
            drawCircle(c.surface, d.dotRadius + d.ring, Offset(x, y))
            drawCircle(if (s.highlighted) c.series2 else c.other, d.dotRadius, Offset(x, y))
        }
        is Selection.NoData -> Unit
    }
    val r = tooltipRect(l, s, lines, d)
    drawRoundRect(c.tooltipBg, r.topLeft, r.size, CornerRadius(d.corner))
    drawRoundRect(c.tooltipBorder, r.topLeft, r.size, CornerRadius(d.corner), style = Stroke(d.grid))
    var y = r.top + d.tooltipPad
    for (line in lines) {
        drawText(line, topLeft = Offset(r.left + d.tooltipPad, y))
        y += line.size.height
    }
}

/** Tooltip text for a selection. */
fun tooltipLines(s: Selection, model: ChartModel): List<String> {
    val fmt = TimeFmt(model.zone, model.window.mode == RangeMode.WEEK)
    return when (s) {
        is Selection.Point -> {
            val p = s.point
            buildList {
                add(if (model.hourly) "${fmt.hm(p.startMs)}–${TimeFmt(model.zone, false).hm(p.endMs)}" else fmt.hm(p.startMs))
                add("LAeq ${ChartFmt.db(p.laeq)} dB(A)")
                add("LAFmax ${ChartFmt.db(p.lafMax)} dB(A)")
                add("L10 / L90 ${ChartFmt.db(p.l10)} / ${ChartFmt.db(p.l90)} dB(A)")
                if (!p.calibrated) add("unkalibriert")
            }
        }
        is Selection.Event -> {
            val e = s.event
            buildList {
                add("${fmt.hms(e.startEpochMs)} · ${ChartCategories.name(e.dominantCategory)}")
                add("LAFmax ${ChartFmt.db(e.lafMaxDb)} dB(A)")
                add("Dauer ${ChartFmt.duration(e.durationSeconds)}")
                add("Hintergrund ${ChartFmt.db(e.backgroundDb)} dB(A)")
                e.topLabels.firstOrNull()?.let { add("Erkannt: ${it.label}") }
                if (!e.calibrated) add("unkalibriert")
            }
        }
        is Selection.NoData -> listOf(fmt.hm(s.timeMs), "keine Messung")
    }
}
