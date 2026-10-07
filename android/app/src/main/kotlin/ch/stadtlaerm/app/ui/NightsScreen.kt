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
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.stadtlaerm.app.container
import ch.stadtlaerm.app.data.Mappers.toEvent
import ch.stadtlaerm.app.data.Mappers.toRecord
import ch.stadtlaerm.dsp.NightSummarizer
import ch.stadtlaerm.dsp.NightSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun NightsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repo = context.container.measurements
    val nights by produceState<List<NightSummary>?>(null) {
        val settings = context.container.settings.state
        combine(repo.minutesFlow(), repo.eventsFlow(), settings.map { it.eventMinLevelDb }) { m, e, floor -> Triple(m, e, floor) }
            .map { (m, e, floor) -> NightSummarizer.summarize(m.map { it.toRecord() }, e.map { it.toEvent() }, ZoneId.systemDefault(), eventMinLevelDb = floor) }
            .flowOn(Dispatchers.Default)
            .collect { value = it }
    }

    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Text(
                "Nächte", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
            Text(
                "Nachtzeit 22:00–06:00 (Ortszeit; bei Zeitumstellung 7 bzw. 9 h). Pegel energetisch gemittelt über die " +
                    "gültige Messzeit; Minuten mit weniger als 50 % gültigem Signal (z. B. Mikrofon durch Anruf stummgeschaltet) werden nicht gewertet.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        val list = nights
        when {
            list == null -> item { Text("Lade …", Modifier.padding(16.dp)) }
            list.isEmpty() -> item { Text("Noch keine Nachtmessungen.", Modifier.padding(16.dp)) }
            else -> items(list, key = { it.nightOf.toString() }) { NightCard(it) }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

private val dayFmt = DateTimeFormatter.ofPattern("EEEE, dd.MM.", SwissLocale)
private val shortDay = DateTimeFormatter.ofPattern("EE dd.MM.", SwissLocale)

@Composable
private fun NightCard(n: NightSummary) {
    val context = LocalContext.current
    val weekend = n.nightOf.dayOfWeek == DayOfWeek.FRIDAY || n.nightOf.dayOfWeek == DayOfWeek.SATURDAY
    SectionCard {
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
