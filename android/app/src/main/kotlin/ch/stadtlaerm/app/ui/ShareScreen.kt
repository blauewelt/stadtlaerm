package ch.stadtlaerm.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.stadtlaerm.app.container
import ch.stadtlaerm.app.upload.CellInput
import ch.stadtlaerm.app.upload.SiteSettings
import ch.stadtlaerm.app.upload.UploadSnapshot
import ch.stadtlaerm.app.upload.Uploader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// «Einstellungen → Messwerte teilen» (server/DESIGN.md §2, §9). Off by default; switching on needs
// the explanation (once) and a hectare. Everything network goes through upload/UploadClient.kt.

/** Short German labels of the placement values (server/DESIGN.md §4.2). */
fun placementLabel(p: String): String = when (p) {
    SiteSettings.PLACEMENT_OPEN_WINDOW -> "Offenes Fenster"
    SiteSettings.PLACEMENT_BALCONY -> "Balkon"
    SiteSettings.PLACEMENT_BEHIND_GLASS -> "Hinter Glas"
    else -> "Anderes"
}

/** One line for the settings overview. */
fun shareSummary(s: UploadSnapshot): String = when {
    s.enabled && s.authFailed -> "Ein – Kennung vom Server abgelehnt, «Neue Kennung» wählen"
    s.enabled -> "Ein" + (s.site?.let { " · Hektare ${it.cell}" } ?: "") +
        (s.lastSuccessAtMs?.let { " · zuletzt gesendet ${Fmt.dateTime(it)}" } ?: "")
    else -> "Aus – es wird nichts gesendet"
}

/** The card in Einstellungen that opens the screen. */
@Composable
fun ShareSettingsCard(onOpen: () -> Unit) {
    val c = LocalContext.current.container
    val s by c.upload.store.state.collectAsStateWithLifecycle()
    SectionCard("Messwerte teilen") {
        Text(
            "Freiwillig: deine Messwerte (nie Audio) für die gemeinsame Lärmkarte an den Server des Projekts in der Schweiz senden.",
            style = MaterialTheme.typography.bodySmall,
        )
        StatRow("Status", if (s.enabled) "Ein" else "Aus")
        Text(shareSummary(s), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = onOpen) { Text(if (s.enabled) "Einstellungen zum Teilen" else "Mehr erfahren & einrichten") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShareScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    BackHandler(onBack = onBack)
    val c = LocalContext.current.container
    val module = c.upload
    val s by module.store.state.collectAsStateWithLifecycle()
    val pending by remember { module.pendingMinutes }.collectAsStateWithLifecycle(0)
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmNewId by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Einstellungen") }
        }
        Text(
            "Messwerte teilen", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )

        // 1. What is sent, what never, where, how to delete.
        SectionCard("Was gesendet wird") {
            Bullets(
                "Deine Minutenwerte: Pegel (LAeq, LAFmax, LAFmin, L1, L10, L50, L90), gültige Messzeit, Anzahl Ereignisse und die Anteile der Geräuschkategorien.",
                "Deine Ereignisse: Beginn, Dauer, Spitzenpegel, SEL, Hintergrundpegel und die Kategorie (z. B. «Töff & Poser»).",
                "Die Hektare (100 × 100 m), in der das Telefon steht, und deine Angaben zur Aufstellung (Fenster, Balkon, Stockwerk, Strassenseite, Notiz).",
                "Telefonmodell, Audioquelle und Kalibrierung, damit die Werte vergleichbar sind.",
                "Eine zufällige Kennung dieses Telefons. Sie hat nichts mit dir oder dem Telefon zu tun und wird nie öffentlich gezeigt.",
            )
        }
        SectionCard("Was nie gesendet wird") {
            Bullets(
                "Audio. Die App speichert gar keines.",
                "Dein genauer Standort. Die App liest kein GPS und hat keine Standort-Berechtigung. Aus deiner Eingabe berechnet das Telefon selbst die Hektare; nur diese wird gesendet.",
                "Name, Telefonnummer, E-Mail oder ein Konto – es gibt keine Anmeldung.",
                "Die einzelnen Klassen des Klassifikators (etwa «Speech» oder «Motorcycle»), nur die Kategorie.",
            )
        }
        SectionCard("Wohin") {
            Bullets(
                "An den Server des Projekts in der Schweiz: ${module.client.host}. Die App verbindet sich mit keinem anderen Server.",
                "Öffentlich sind nur Werte pro Hektare auf der Lärmkarte (stadtlaerm.ch/karte.html) – nie einzelne Messungen, die Kennung oder deine Notiz. Das Telefonmodell erscheint nur in einer Zählung über alle beteiligten Geräte.",
                "Beim ersten Einschalten werden die Messungen der letzten 7 Tage mitgesendet, danach nach jeder vollen Stunde und nach jeder Messung das Neue. Was du misst, während das Teilen aus ist, wird nie gesendet.",
            )
        }
        SectionCard("Löschen") {
            Bullets(
                "Ausschalten stoppt das Senden sofort.",
                "«Meine Daten auf dem Server löschen» (unten) entfernt alles von diesem Telefon sofort vom Server; die Karte wird beim nächsten Durchlauf (alle 10 Minuten) ohne deine Werte neu berechnet.",
                "Deine Daten auf dem Telefon bleiben in jedem Fall, bis du sie unter «Daten» löschst.",
            )
            if (!s.explanationSeen) {
                Button(onClick = { module.store.update { it.copy(explanationSeen = true) } }) { Text("Gelesen, weiter") }
            }
        }

        if (s.explanationSeen) {
            PlacementCard(s.site) { site ->
                module.store.update { it.copy(site = site) }
                module.uploadSoon()
            }

            // 3. The switches and the status.
            SectionCard("Teilen") {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Messwerte teilen", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(
                        checked = s.enabled,
                        enabled = s.enabled || s.canEnable,
                        onCheckedChange = { on ->
                            if (on) {
                                if (module.uploader.enable()) { module.applySchedule(); module.uploadSoon(5) }
                            } else {
                                module.uploader.disable(); module.applySchedule()
                            }
                        },
                    )
                }
                if (!s.enabled && s.site == null) {
                    Text("Zuerst oben die Hektare festlegen.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Nur über WLAN", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = s.wifiOnly, onCheckedChange = { v ->
                        module.store.update { it.copy(wifiOnly = v) }
                        module.applySchedule()
                    })
                }
                Text(
                    if (s.wifiOnly) "Gesendet wird nur über WLAN (genauer: über Verbindungen ohne Datenvolumen-Begrenzung)."
                    else "Gesendet wird auch über mobile Daten (weniger als 0,1 MB pro Nacht).",
                    style = MaterialTheme.typography.bodySmall,
                )
                StatRow("Zuletzt gesendet", s.lastSuccessAtMs?.let { Fmt.dateTime(it) } ?: "noch nie")
                StatRow("Noch zu senden", "$pending Minuten")
                if (s.refusedTotal > 0) {
                    Text(
                        "${s.refusedTotal} Werte hat der Server als ungültig abgelehnt (z. B. älter als 7 Tage); sie werden nicht erneut gesendet.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                s.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }

            // 4. Server data.
            SectionCard("Deine Daten auf dem Server") {
                Text(
                    if (s.registered) "Dieses Telefon ist auf dem Server unter einer zufälligen Kennung eingetragen."
                    else "Von diesem Telefon liegen keine Daten auf dem Server.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(
                    onClick = { confirmDelete = true },
                    enabled = s.registered && busy == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text("Meine Daten auf dem Server löschen") }
                OutlinedButton(onClick = { confirmNewId = true }, enabled = (s.registered || s.authFailed) && busy == null) {
                    Text("Neue Kennung")
                }
                Text(
                    "«Neue Kennung» löscht deine Daten auf dem Server und sendet künftig unter einer neuen Kennung; Messungen von vorher werden nicht erneut gesendet.",
                    style = MaterialTheme.typography.bodySmall,
                )
                busy?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    fun runDelete(newIdentity: Boolean) {
        busy = if (newIdentity) "Neue Kennung: Daten auf dem Server werden gelöscht …" else "Daten auf dem Server werden gelöscht …"
        result = null
        scope.launch {
            val r = withContext(Dispatchers.IO) {
                if (newIdentity) module.uploader.newIdentity() else module.uploader.deleteServerData()
            }
            module.applySchedule()
            busy = null
            result = when (r) {
                Uploader.DeleteOutcome.Deleted ->
                    if (newIdentity) "Gelöscht. Ab dem nächsten Senden wird eine neue Kennung verwendet."
                    else "Gelöscht: Der Server hat bestätigt, dass alle Daten dieses Telefons entfernt sind. Das Teilen ist ausgeschaltet."
                Uploader.DeleteOutcome.UnknownToServer ->
                    "Der Server kennt diese Kennung nicht (mehr); dort liegt nichts mehr, das zu ihr gehört. Die Kennung wurde auf dem Telefon vergessen."
                is Uploader.DeleteOutcome.Failed -> "Nicht gelöscht (${r.message}). Bitte mit Internetverbindung erneut versuchen."
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Daten auf dem Server löschen?") },
            text = {
                Text(
                    "Alle Minutenwerte, Ereignisse und die Aufstellung, die dieses Telefon gesendet hat, werden sofort vom Server gelöscht. " +
                        "Das Teilen wird ausgeschaltet. Deine Daten auf dem Telefon bleiben.",
                )
            },
            confirmButton = { TextButton(onClick = { confirmDelete = false; runDelete(newIdentity = false) }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmNewId) {
        AlertDialog(
            onDismissRequest = { confirmNewId = false },
            title = { Text("Neue Kennung?") },
            text = { Text("Deine Daten auf dem Server werden gelöscht, und künftig wird unter einer neuen, zufälligen Kennung gesendet.") },
            confirmButton = { TextButton(onClick = { confirmNewId = false; runDelete(newIdentity = true) }) { Text("Neue Kennung") } },
            dismissButton = { TextButton(onClick = { confirmNewId = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Bullets(vararg lines: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        lines.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}

/**
 * The placement step. There is no map yet (see WORKLOG.md: map picker is an open question):
 * the contributor pastes coordinates from a map app or map.geo.admin.ch, the phone turns them into
 * the hectare at once and shows only that; the typed text is not stored.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacementCard(current: SiteSettings?, onSave: (SiteSettings) -> Unit) {
    var coords by rememberSaveable { mutableStateOf("") }
    var placement by rememberSaveable(current) { mutableStateOf(current?.placement ?: SiteSettings.PLACEMENT_OPEN_WINDOW) }
    var floorText by rememberSaveable(current) { mutableStateOf(current?.floor?.toString() ?: "") }
    // 0 = Strassenseite, 1 = Hofseite, 2 = keine Angabe
    var facing by rememberSaveable(current) {
        mutableStateOf(when (current?.streetFacing) { true -> 0; false -> 1; null -> 2 })
    }
    var note by rememberSaveable(current) { mutableStateOf(current?.note ?: "") }
    var saved by remember { mutableStateOf(false) }

    val parsed = if (coords.isBlank()) null else CellInput.parse(coords)
    val cell = (parsed as? CellInput.Parsed.Ok)?.cell ?: current?.cell
    val floor = floorText.trim().toIntOrNull()
    val floorOk = floorText.isBlank() || (floor != null && floor in -3..100)

    SectionCard("Aufstellung") {
        Text(
            "Wo steht das Telefon? Füge die Koordinaten ein, z. B. aus einer Karten-App (Punkt lange drücken, Koordinaten kopieren) " +
                "oder von map.geo.admin.ch. Die App rechnet daraus die Hektare (100 × 100 m); nur diese wird gespeichert und gesendet.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = coords, onValueChange = { coords = it; saved = false },
            label = { Text("Koordinaten (z. B. 47.3769, 8.5417 oder 2'683'304, 1'247'925)") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        when {
            parsed is CellInput.Parsed.Error -> Text(parsed.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            cell != null -> Text("Hektare: $cell – dieses Hektar wird gezeigt", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            else -> Text("Hektare: noch keine", style = MaterialTheme.typography.bodySmall)
        }

        Text("Aufstellung des Telefons", style = MaterialTheme.typography.bodyMedium)
        val places = SiteSettings.PLACEMENTS
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            places.forEachIndexed { i, p ->
                SegmentedButton(
                    selected = placement == p, onClick = { placement = p; saved = false },
                    shape = SegmentedButtonDefaults.itemShape(i, places.size),
                ) { Text(placementLabel(p), maxLines = 2, style = MaterialTheme.typography.labelSmall) }
            }
        }
        OutlinedTextField(
            value = floorText, onValueChange = { floorText = it.filter { ch -> ch.isDigit() || ch == '-' }.take(3); saved = false },
            label = { Text("Stockwerk (0 = Erdgeschoss, leer = keine Angabe)") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !floorOk, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Text("Mikrofon zeigt zur", style = MaterialTheme.typography.bodyMedium)
        val sides = listOf("Strasse", "Hofseite", "keine Angabe")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            sides.forEachIndexed { i, label ->
                SegmentedButton(
                    selected = facing == i, onClick = { facing = i; saved = false },
                    shape = SegmentedButtonDefaults.itemShape(i, sides.size),
                ) { Text(label, style = MaterialTheme.typography.labelSmall) }
            }
        }
        OutlinedTextField(
            value = note, onValueChange = { note = it.take(SiteSettings.NOTE_MAX); saved = false },
            label = { Text("Notiz (freiwillig, wird nie veröffentlicht)") },
            supportingText = { Text("${note.length}/${SiteSettings.NOTE_MAX}") },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            enabled = cell != null && floorOk,
            onClick = {
                onSave(
                    SiteSettings(
                        cell = cell!!, placement = placement, floor = floor,
                        streetFacing = when (facing) { 0 -> true; 1 -> false; else -> null },
                        note = note.trim(),
                    )
                )
                coords = ""
                saved = true
            },
        ) { Text("Aufstellung speichern") }
        if (saved) Text("Gespeichert.", style = MaterialTheme.typography.bodySmall)
    }
}
