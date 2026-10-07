package ch.stadtlaerm.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.stadtlaerm.app.BuildConfig
import ch.stadtlaerm.app.audio.AudioSourceSelector
import ch.stadtlaerm.app.container
import ch.stadtlaerm.app.data.AppSettings
import ch.stadtlaerm.app.data.CalibrationRepository
import ch.stadtlaerm.dsp.Acoustics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun DataScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = context.container
    val minutes by remember { c.measurements.minuteCount() }.collectAsStateWithLifecycle(0)
    val events by remember { c.measurements.eventCount() }.collectAsStateWithLifecycle(0)
    val live by c.live.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var info by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            "Daten", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        SectionCard("Gespeichert (nur auf diesem Telefon)") {
            StatRow("Minutenwerte", minutes.toString())
            StatRow("Ereignisse", events.toString())
            Text(
                "Gespeichert werden nur Kennwerte (Pegel, Statistik, Kategorien, Ereignisse) – nie Audio.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionCard("Export als CSV") {
            Text(
                "Zwei Dateien (Minuten, Ereignisse), Zeitstempel ISO-8601 mit Zeitzone, Dezimalpunkt. " +
                    "Teilen über das Android-Teilen-Menü (z. B. E-Mail, Dateien). Die App selbst hat keinen Netzwerkzugriff.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = {
                scope.launch {
                    val files = withContext(Dispatchers.IO) {
                        c.measurements.exportCsv(File(context.cacheDir, "exports"), c.categoryMapper.bucketIds)
                    }
                    shareFiles(context, files, "text/csv", "Stadtlärm Messdaten")
                }
            }) { Text("CSV exportieren & teilen") }
        }
        SectionCard("Daten löschen") {
            Text("Löscht alle Minutenwerte und Ereignisse. Kalibrierungen bleiben erhalten.", style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { confirmClear = true },
                enabled = !live.running,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Alle Messdaten löschen") }
            if (live.running) Text("Zuerst die Messung stoppen.", style = MaterialTheme.typography.bodySmall)
            info?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Alle Messdaten löschen?") },
            text = { Text("$minutes Minutenwerte und $events Ereignisse werden endgültig gelöscht.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        c.measurements.clear()
                        File(context.cacheDir, "exports").listFiles()?.forEach { it.delete() }
                        info = "Gelöscht."
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val c = context.container
    val s by c.settings.state.collectAsStateWithLifecycle()
    val live by c.live.collectAsStateWithLifecycle()
    val source = remember { AudioSourceSelector.select(context) }
    val active by remember { c.calibrations.activeFlow(source) }.collectAsStateWithLifecycle(null)

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Text(
            "Einstellungen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        if (live.running) {
            Text(
                "Änderungen gelten ab dem nächsten Start der Messung.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        SectionCard("Ereigniserkennung") {
            StatRow("Schwelle über Hintergrund (L90, 5 min)", "${s.eventThresholdDb.toInt()} dB")
            Slider(
                value = s.eventThresholdDb.toFloat(), valueRange = 5f..20f, steps = 14,
                onValueChange = { v -> c.settings.update { it.copy(eventThresholdDb = Math.round(v).toDouble()) } },
            )
            Text(
                "Ein Ereignis beginnt, wenn LAF den Hintergrund um die Schwelle übersteigt, dauert mind. 0.5 s und endet 3 dB unter der Schwelle.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            StatRow("Mindestpegel für Ereignisse (LAFmax)", "${s.eventMinLevelDb.toInt()} dB(A)")
            Slider(
                value = s.eventMinLevelDb.toFloat(),
                valueRange = AppSettings.EVENT_MIN_LEVEL_MIN.toFloat()..AppSettings.EVENT_MIN_LEVEL_MAX.toFloat(),
                steps = (AppSettings.EVENT_MIN_LEVEL_MAX - AppSettings.EVENT_MIN_LEVEL_MIN).toInt() - 1,
                onValueChange = { v -> c.settings.update { it.copy(eventMinLevelDb = Math.round(v).toDouble()) } },
            )
            Text(
                "Ereignisse zählen nur, wenn sie lauter sind als dieser Pegel. Bei unkalibriertem Telefon sind die Pegel ungefähr.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionCard("Geräuschquellen-Erkennung") {
            SwitchRow("Klassifikation (YAMNet, auf dem Gerät)", s.classifierEnabled) { v -> c.settings.update { it.copy(classifierEnabled = v) } }
            Text("Intervall", style = MaterialTheme.typography.bodyMedium)
            val options = listOf(1, 2, 5)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                options.forEachIndexed { i, sec ->
                    SegmentedButton(
                        selected = s.classifierIntervalSeconds == sec,
                        onClick = { c.settings.update { it.copy(classifierIntervalSeconds = sec) } },
                        shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        enabled = s.classifierEnabled,
                    ) { Text("$sec s") }
                }
            }
            SwitchRow("Pegelanpassung für Klassifikator", s.classifierNormalize) { v -> c.settings.update { it.copy(classifierNormalize = v) } }
            Text(
                "Ein: Jedes Erkennungsfenster (0.975 s) wird vor der Klassifikation auf einen RMS-Pegel von −30 dBFS angehoben – " +
                    "höchstens um +40 dB, nie abgeschwächt, Spitzen auf ±1 begrenzt. Das hilft bei leisen Nachtgeräuschen, weil das Modell " +
                    "auf lauten YouTube-Aufnahmen trainiert wurde, kann aber auch leises Rauschen als Geräusch erkennen lassen. " +
                    "Aus: Das Modell sieht das unveränderte Signal. Pegel, Statistik und Ereignisse sind in beiden Fällen gleich. " +
                    "Wirkt ab dem nächsten Start der Messung.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        SectionCard("Nachtmessung & Akku") {
            SwitchRow("CPU während der Messung wach halten (Wake-Lock)", s.wakeLock) { v -> c.settings.update { it.copy(wakeLock = v) } }
            Text(
                "Empfohlen für Nachtmessungen. Kostet etwas Akku; das Telefon sollte nachts am Ladegerät hängen. " +
                    "Zusätzlich hilft es, die Akku-Optimierung für Stadtlärm auszuschalten.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = {
                // Not every vendor ROM implements this screen; fall back to the app's details page.
                try {
                    context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                } catch (_: ActivityNotFoundException) {
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    } catch (_: ActivityNotFoundException) {}
                }
            }) { Text("Akku-Optimierung öffnen") }
        }
        SectionCard("Messkette") {
            StatRow("Gerät", CalibrationRepository.deviceModel)
            StatRow("Audioquelle", AudioSourceSelector.labelDe(source))
            val a = active
            StatRow(
                "Kalibrierung",
                if (a == null || !a.isCalibrated) "keine (Standard ${Fmt.db(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, 2)} dB)"
                else "#${a.id}: ${Fmt.db(a.offsetDb, 2)} dB (${methodName(a.method)})",
            )
            if (live.running && live.effects.isNotEmpty()) StatRow("Effekte", live.effects.joinToString(", "))
        }
        SectionCard("Über Stadtlärm & Datenschutz") {
            Text("Stadtlärm ${BuildConfig.VERSION_NAME} – offene Lärmmessung für Zürich. Lizenz: Apache-2.0.", style = MaterialTheme.typography.bodyMedium)
            Text(
                "• Audio verlässt nie den Arbeitsspeicher: höchstens ca. 1 s wird für die Erkennung gepuffert, nichts wird gespeichert, protokolliert oder gesendet.\n" +
                    "• Die App hat keine Internet-Berechtigung.\n" +
                    "• Gespeichert werden nur Kennwerte: Pegel, Statistik, Kategorie-Anteile, Ereignisse.\n" +
                    "• Daten verlassen das Telefon nur, wenn du sie selbst exportierst und teilst.\n" +
                    "• Pegel sind ohne Kalibrierung nur Richtwerte (Toleranz typ. ±5 dB oder mehr).\n" +
                    "• Erkennung: YAMNet (Google, Apache-2.0), AudioSet-Klassen. Es gibt keine eigene Tram-Klasse.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
