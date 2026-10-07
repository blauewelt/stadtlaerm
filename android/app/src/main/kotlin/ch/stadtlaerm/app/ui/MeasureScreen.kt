package ch.stadtlaerm.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.stadtlaerm.app.audio.AudioSourceSelector
import ch.stadtlaerm.app.container
import ch.stadtlaerm.app.data.EventEntity
import ch.stadtlaerm.app.service.MeasurementService

@Composable
fun MeasureScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = context.container
    val live by c.live.collectAsStateWithLifecycle()
    val events by remember { c.measurements.recentEvents(30) }.collectAsStateWithLifecycle(emptyList())
    val calibrating by c.calibrationActive.collectAsStateWithLifecycle()
    val requestPermission = rememberMicPermissionLauncher { granted ->
        if (granted) MeasurementService.start(context)
    }

    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Messen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (live.running && !live.calibrated) {
                    AssistChip(
                        onClick = {}, label = { Text("unkalibriert") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    )
                } else if (live.running) {
                    AssistChip(onClick = {}, label = { Text("kalibriert") })
                }
            }
        }

        if (live.running && (live.micSilenced || live.invalidAudio)) {
            item {
                SectionCard {
                    Text(
                        if (live.micSilenced) "Mikrofon vom System stummgeschaltet (Anruf oder Sprachassistent)."
                        else "Kein gültiges Mikrofonsignal (digitale Stille).",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Diese Zeit wird nicht gewertet: keine Pegel, keine Ereignisse.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            SectionCard {
                Text("Momentanpegel LAF", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(Fmt.db(live.lafDb), fontSize = 64.sp, fontWeight = FontWeight.Bold, lineHeight = 64.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("dB(A)", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
                }
                LevelBar(live.lafDb)
                Spacer(Modifier.height(4.dp))
                StatRow("LAeq letzte Minute (gleitend)", "${Fmt.db(live.laeq60sDb)} dB(A)")
                StatRow("Hintergrund L90 (5 min)", "${Fmt.db(live.backgroundDb)} dB(A)")
            }
        }

        item {
            SectionCard("Geräuschquelle") {
                if (!live.running) {
                    Text("–", style = MaterialTheme.typography.bodyMedium)
                } else if (!live.classifierEnabled) {
                    Text("Klassifikation ist ausgeschaltet (Einstellungen).", style = MaterialTheme.typography.bodyMedium)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).clip(CircleShape).background(categoryColor(live.dominantCategory)))
                        Spacer(Modifier.width(8.dp))
                        Text(categoryName(context, live.dominantCategory), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        Text(Fmt.percent(live.dominantScore.toDouble()), style = MaterialTheme.typography.titleMedium)
                    }
                    if (live.topLabels.isNotEmpty()) {
                        Text(
                            "AudioSet: " + live.topLabels.joinToString(" · ") { "${it.label} ${Fmt.percent(it.score.toDouble())}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                        )
                    }
                }
            }
        }

        item {
            val m = live.lastMinute
            SectionCard("Letzte volle Minute" + (m?.let { " (${Fmt.hm(it.startEpochMs)})" } ?: "")) {
                StatRow("LAeq", "${Fmt.db(m?.laeqDb)} dB(A)")
                StatRow("LAFmax", "${Fmt.db(m?.lafMaxDb)} dB(A)")
                StatRow("L10", "${Fmt.db(m?.l10Db)} dB(A)")
                StatRow("L50", "${Fmt.db(m?.l50Db)} dB(A)")
                StatRow("L90", "${Fmt.db(m?.l90Db)} dB(A)")
                if (m != null) {
                    StatRow("Ereignisse", m.eventCount.toString())
                    StatRow("Gültige Messzeit", "${Fmt.db(m.validSeconds, 0)} s (${Fmt.percent(m.coverage)})")
                }
            }
        }

        item {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (live.running) {
                    Button(
                        onClick = { MeasurementService.stop(context) },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    ) { Text("Messung stoppen", fontSize = 18.sp) }
                } else {
                    Button(
                        onClick = {
                            if (hasMicPermission(context)) MeasurementService.start(context) else requestPermission()
                        },
                        enabled = !live.starting && !calibrating,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text(if (live.starting) "Startet …" else "Messung starten", fontSize = 18.sp) }
                }
                if (calibrating) Text("Kalibrierung läuft – Messung erst danach möglich.", style = MaterialTheme.typography.bodySmall)
                if (!live.running) UpdateReminderLine()
                live.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (live.running) {
                    Text(
                        "Quelle: ${AudioSourceSelector.labelDe(live.audioSource ?: "")} · ${live.encoding} · " +
                            "Offset ${Fmt.db(live.calibrationOffsetDb, 2)} dB" +
                            (live.calibrationId?.let { " (Kalibrierung #$it)" } ?: " (Standard)"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (live.effects.isNotEmpty()) {
                        Text("Effekte: " + live.effects.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Läuft seit ${Fmt.time(live.startedAtMs)} – auch bei ausgeschaltetem Bildschirm.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        item {
            Text(
                "Letzte Ereignisse",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (events.isEmpty()) {
            item { Text("Noch keine Ereignisse.", modifier = Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodyMedium) }
        }
        items(events, key = { it.id }) { e -> EventRow(e) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun LevelBar(db: Double?) {
    val lo = 20.0
    val hi = 100.0
    val p = if (db == null || db.isNaN()) 0f else ((db - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat()
    val color = when {
        db == null -> MaterialTheme.colorScheme.primary
        db >= 75 -> MaterialTheme.colorScheme.error
        db >= 55 -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.tertiary
    }
    Column {
        LinearProgressIndicator(
            progress = { p },
            modifier = Modifier.fillMaxWidth().height(14.dp).clip(RoundedCornerShape(7.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            drawStopIndicator = {},
        )
        Row(Modifier.fillMaxWidth()) {
            Text("20", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("60", style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
            Text("100 dB", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
fun EventRow(e: EventEntity) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(e.dominantCategory)))
            Spacer(Modifier.width(8.dp))
            Text(Fmt.time(e.startEpochMs), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(76.dp))
            Text(
                "${Fmt.db(e.lafMaxDb)} dB(A)", style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, modifier = Modifier.width(92.dp),
            )
            Text(Fmt.duration(e.durationSeconds), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(64.dp))
            Text(
                categoryName(context, e.dominantCategory ?: "n/a"), style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f), maxLines = 1,
            )
        }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = MaterialTheme.colorScheme.surfaceVariant)
    }
}
