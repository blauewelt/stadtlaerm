package ch.stadtlaerm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.stadtlaerm.chart.ChartFmt
import ch.stadtlaerm.chart.HistoryActions
import ch.stadtlaerm.chart.HistorySection
import ch.stadtlaerm.chart.RangeMode
import ch.stadtlaerm.chart.Selection
import ch.stadtlaerm.dsp.NightSummary
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.format.DateTimeFormatter

@Composable
fun NightsScreen(modifier: Modifier = Modifier, vm: HistoryViewModel = viewModel()) {
    val nights by vm.nights.collectAsStateWithLifecycle()
    val window by vm.window.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The tooltip belongs to one window: navigating closes it.
    val selectionState = remember(window) { mutableStateOf<Selection?>(null) }
    val actions = remember(vm, selectionState) {
        object : HistoryActions {
            override fun onMode(mode: RangeMode) = vm.setMode(mode)
            override fun onShift(delta: Int) = vm.shift(delta)
            override fun onHighlight(category: String) = vm.setHighlight(category)
            override fun onSelect(selection: Selection?) { selectionState.value = selection }
        }
    }

    LazyColumn(modifier.fillMaxWidth(), state = listState) {
        item {
            Text(
                "Nächte", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }
        item {
            val w = window
            if (w == null) {
                Text("Lade …", Modifier.padding(16.dp))
            } else {
                HistorySection(
                    window = w, data = data, zone = vm.zone, highlight = settings.chartHighlightCategory,
                    eventFloorDb = settings.eventMinLevelDb,
                    canGoNext = vm.windows.canGoNext(w, System.currentTimeMillis()),
                    selection = selectionState.value, actions = actions,
                )
            }
        }
        item {
            Text(
                "Alle Nächte", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp),
            )
            Text(
                "Nachtzeit 22:00–06:00 (Ortszeit; bei Zeitumstellung 7 bzw. 9 h). Pegel energetisch gemittelt über die " +
                    "gültige Messzeit; Minuten mit weniger als 50 % gültigem Signal (z. B. Mikrofon durch Anruf stummgeschaltet) werden nicht gewertet. " +
                    "Ereignisse zählen ab ${settings.eventMinLevelDb.toInt()} dB(A) (Einstellungen). Tippen zeigt die Nacht in der Grafik.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val list = nights
        when {
            list == null -> item { Text("Lade …", Modifier.padding(16.dp)) }
            list.isEmpty() -> item { Text("Noch keine Nachtmessungen.", Modifier.padding(16.dp)) }
            else -> items(list, key = { it.nightOf.toString() }) { n ->
                NightCard(n) {
                    vm.showNight(n.nightOf)
                    scope.launch { listState.animateScrollToItem(0) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, dd.MM.", SwissLocale)
private val shortDay = DateTimeFormatter.ofPattern("EE dd.MM.", SwissLocale)

@Composable
private fun NightCard(n: NightSummary, onClick: () -> Unit) {
    val context = LocalContext.current
    val weekend = n.nightOf.dayOfWeek == DayOfWeek.FRIDAY || n.nightOf.dayOfWeek == DayOfWeek.SATURDAY
    SectionCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${n.nightOf.format(dayFmt)} → ${n.nightOf.plusDays(1).format(shortDay)}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (weekend) {
                Text(
                    "Wochenende",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }
        StatRow("Nacht-LAeq", "${Fmt.db(n.laeqDb)} dB(A)" + if (n.allCalibrated) "" else " (unkalibriert)")
        StatRow("Gültig gemessen", "${Fmt.duration(n.measuredSeconds)} von ${Fmt.duration(n.nominalSeconds)} (${Fmt.percent(n.coverage)})")
        if (n.excludedMinutes > 0) {
            StatRow("Nicht gewertete Minuten (< 50 % gültig)", n.excludedMinutes.toString())
        }
        StatRow("Ereignisse", n.eventCount.toString())
        if (!n.eventsPerHour.isNaN()) {
            StatRow(
                "Ereignisse/h · Dynamik L10−L90",
                "${ChartFmt.comma(n.eventsPerHour, if (n.eventsPerHour < 10) 1 else 0)} · ${ChartFmt.comma(n.dynamicsDb, 1)} dB",
            )
        }
        StatRow("davon ≥ Hintergrund + 15 dB", n.strongEventCount.toString())
        n.loudestEvent?.let { e ->
            StatRow(
                "Lautestes Ereignis",
                "${Fmt.hm(e.startEpochMs)} · ${Fmt.db(e.lafMaxDb)} dB(A) · ${categoryName(context, e.dominantCategory ?: "n/a")}",
            )
        }
        if (n.categoryShares.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text("Zeitanteile nach Quelle", style = MaterialTheme.typography.labelLarge)
            n.categoryShares.entries.filter { it.value > 0.0 }.sortedByDescending { it.value }.forEach { (id, share) ->
                ShareBar(categoryName(context, id), share, n.eventsByCategory[id] ?: 0, id)
            }
        } else if (n.eventsByCategory.isNotEmpty()) {
            Text("Ereignisse nach Quelle", style = MaterialTheme.typography.labelLarge)
            n.eventsByCategory.forEach { (id, count) -> StatRow(categoryName(context, id), count.toString()) }
        }
    }
}

@Composable
private fun ShareBar(name: String, share: Double, events: Int, id: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(id)))
        Text(name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(132.dp), maxLines = 1)
        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surface)) {
            Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(0f, 1f)).height(8.dp).background(categoryColor(id)))
        }
        Text("${Fmt.percent(share)} · $events Ereig.", style = MaterialTheme.typography.bodySmall)
    }
}
