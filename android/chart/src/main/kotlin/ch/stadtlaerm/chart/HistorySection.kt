package ch.stadtlaerm.chart

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.ZoneId

/** Callbacks of the history section. */
interface HistoryActions {
    fun onMode(mode: RangeMode)
    fun onShift(delta: Int)
    fun onHighlight(category: String)
    fun onSelect(selection: Selection?)
}

/**
 * The top of the «Nächte» screen: range buttons, window navigation, the chart card with category
 * chips and legend, and the summary row.
 *
 * [data] may belong to the previous window while the new one loads; it is then shown dimmed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HistorySection(
    window: TimeWindow,
    data: ChartData?,
    zone: ZoneId,
    highlight: String,
    eventFloorDb: Double,
    canGoNext: Boolean,
    selection: Selection?,
    actions: HistoryActions,
    modifier: Modifier = Modifier,
) {
    val windows = remember(zone) { Windows(zone) }
    val model = remember(data, highlight, eventFloorDb, zone) {
        data?.let { ChartModel.build(it, zone, highlight, eventFloorDb) }
    }
    val loading = data == null || data.window != window
    val scheme = MaterialTheme.colorScheme
    val palette = ChartPalette.current()
    val highlightName = ChartCategories.name(highlight)

    val margin = 16.dp
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // 1. Range and navigation.
        val modes = listOf(RangeMode.NIGHT to "Nacht", RangeMode.DAY to "Tag", RangeMode.WEEK to "Woche")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = margin)) {
            modes.forEachIndexed { i, (m, label) ->
                SegmentedButton(
                    selected = window.mode == m,
                    onClick = { actions.onMode(m) },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(label) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = margin), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { actions.onShift(-1) }, modifier = Modifier.semantics { contentDescription = "früher" }) {
                Text("‹", style = MaterialTheme.typography.headlineMedium)
            }
            Text(
                windows.label(window), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            IconButton(
                onClick = { actions.onShift(1) }, enabled = canGoNext,
                modifier = Modifier.semantics { contentDescription = "später" },
            ) { Text("›", style = MaterialTheme.typography.headlineMedium) }
        }

        // 2. Chart card.
        val cardColor = scheme.surface
        Card(
            colors = CardDefaults.cardColors(containerColor = cardColor),
            border = BorderStroke(1.dp, scheme.outlineVariant),
            modifier = Modifier.fillMaxWidth().padding(horizontal = margin),
        ) {
            Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 8.dp)) {
                Row(Modifier.fillMaxWidth().height(32.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (model?.isEmpty == false) "dB(A)" else "", style = MaterialTheme.typography.labelSmall,
                        color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                    if (model?.anyUncalibrated == true) UncalibratedBadge()
                }
                val chartHeight = 248.dp
                if (model == null || model.isEmpty) {
                    Box(Modifier.fillMaxWidth().height(chartHeight), contentAlignment = Alignment.Center) {
                        Text(
                            if (model == null) "Lade …" else "Noch keine Messung in diesem Zeitraum.",
                            style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                        )
                    }
                } else {
                    NoiseChart(
                        model = model, selection = selection, onSelect = actions::onSelect, onNavigate = actions::onShift,
                        canGoNext = canGoNext, surfaceColor = cardColor, height = chartHeight,
                        modifier = if (loading) Modifier.alpha(0.5f) else Modifier,
                    )
                }
                if (model != null && model.eventsCapped) {
                    Text(
                        "Wochenansicht: Stundenwerte; gezeichnet sind nur die ${ChartModel.WEEK_EVENT_CAP} lautesten Ereignisse der hervorgehobenen Kategorie ab ${ChartFmt.dbCompact(maxOf(eventFloorDb, ChartModel.WEEK_EVENT_MIN_DB))} dB(A).",
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    )
                } else if (window.mode == RangeMode.WEEK) {
                    Text(
                        "Wochenansicht: Stundenwerte; gezeichnet sind nur Ereignisse der hervorgehobenen Kategorie ab " +
                            "${ChartFmt.dbCompact(maxOf(eventFloorDb, ChartModel.WEEK_EVENT_MIN_DB))} dB(A).",
                        style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant,
                    )
                }
                // Legend (text colours; small swatches carry the series colours).
                FlowRow(
                    Modifier.fillMaxWidth().padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    LegendItem(Swatch.Dot(palette.series2), "$highlightName (hervorgehoben)")
                    if (window.mode != RangeMode.WEEK) {
                        LegendItem(Swatch.Dot(scheme.onSurfaceVariant.copy(alpha = 0.55f).compositeOver(cardColor), small = true), "andere Ereignisse")
                    }
                    LegendItem(Swatch.Line(palette.series1), if (window.mode == RangeMode.WEEK) "LAeq pro Stunde" else "LAeq pro Minute")
                    LegendItem(Swatch.Band(palette.series1.copy(alpha = palette.bandAlpha).compositeOver(cardColor)), "Hintergrund L90–L10")
                }
            }
        }
        // Category chips (one scrolling row): which category is emphasised. The selected chip
        // carries an orange dot like the highlighted events.
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = margin),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(ChartCategories.all, key = { it.first }) { (id, name) ->
                val selected = highlight == id
                FilterChip(
                    selected = selected, onClick = { actions.onHighlight(id) }, label = { Text(name) },
                    leadingIcon = if (selected) {
                        { Canvas(Modifier.size(10.dp)) { drawCircle(palette.series2) } }
                    } else null,
                )
            }
        }

        // 3. Summary.
        val s = model?.summary
        Row(Modifier.fillMaxWidth().padding(horizontal = margin), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatTile("LAeq", s?.let { "${ChartFmt.db(it.laeqDb)} dB(A)" } ?: "–", if (s?.anyUncalibrated == true) "unkalibriert" else null, Modifier.weight(1f))
            StatTile("Ereignisse", s?.eventCount?.toString() ?: "–", s?.let { "${it.highlightCount} $highlightName" }, Modifier.weight(1f))
            val loud = s?.loudest
            StatTile(
                "Lautestes", loud?.let { "${ChartFmt.db(it.lafMaxDb)} dB(A)" } ?: "–",
                loud?.let { TimeFmt(zone, window.mode == RangeMode.WEEK).hm(it.startEpochMs) }, Modifier.weight(1f),
            )
        }
        if (s != null && model?.isEmpty == false) {
            Text(
                s.line(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = margin),
            )
            // Own line: appended to the summary line it could wrap with a leading «·».
            if (s.anyRecalibrated) {
                Text(
                    "Pegel $RECALIBRATED (Kalibrierung nach der Messung)", style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = margin),
                )
            }
        }
    }
}

@Composable
fun UncalibratedBadge() {
    AssistChip(
        onClick = {}, label = { Text("unkalibriert") },
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            labelColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
        border = null,
    )
}

private sealed interface Swatch {
    data class Dot(val color: Color, val small: Boolean = false) : Swatch
    data class Line(val color: Color) : Swatch
    data class Band(val color: Color) : Swatch
}

@Composable
private fun LegendItem(swatch: Swatch, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(Modifier.size(width = 14.dp, height = 10.dp)) {
            when (swatch) {
                is Swatch.Dot -> drawCircle(swatch.color, if (swatch.small) size.height / 3 else size.height / 2, Offset(size.width / 2, size.height / 2))
                is Swatch.Line -> drawLine(swatch.color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2.dp.toPx(), StrokeCap.Round)
                is Swatch.Band -> drawRect(swatch.color)
            }
        }
        Text(text, style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatTile(label: String, value: String, sub: String?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                sub ?: " ", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
