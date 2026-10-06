package ch.stadtlaerm.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.stadtlaerm.app.audio.AudioSourceSelector
import ch.stadtlaerm.app.data.CalibrationEntity
import ch.stadtlaerm.dsp.Acoustics
import ch.stadtlaerm.dsp.calibration.CalibrationMath
import ch.stadtlaerm.dsp.calibration.CalibrationWarning
import kotlinx.coroutines.launch

private fun parseDb(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()

fun warningText(w: CalibrationWarning): String = when (w) {
    CalibrationWarning.UNSTEADY -> "Pegel schwankte zu stark (Standardabweichung der 1-s-Pegel > 2 dB). Gleichmässigeres Geräusch verwenden."
    CalibrationWarning.TOO_QUIET -> "Referenzpegel unter 50 dB(A): zu nah am Eigenrauschen des Telefons. Lauteres Geräusch verwenden."
    CalibrationWarning.FREQUENCY_OFF -> "Dominante Frequenz ist nicht 1 kHz ± 5 %. Sitzt der Kalibrator richtig?"
    CalibrationWarning.NOT_TONAL -> "Signal ist kein sauberer Ton (Ankopplung prüfen, Umgebung leiser)."
    CalibrationWarning.IMPLAUSIBLE_OFFSET -> "Offset weicht > 20 dB vom Standard ab – vermutlich ein Fehler."
    CalibrationWarning.CLIPPING -> "Signal übersteuert (digitaler Vollausschlag): gemessener Pegel zu tief, Offset unbrauchbar. Leiseres Signal verwenden (z. B. 94 statt 114 dB)."
}

fun methodName(m: String): String = when (m) {
    CalibrationEntity.METHOD_REFERENCE -> "Referenz-Messgerät"
    CalibrationEntity.METHOD_CALIBRATOR -> "Akustischer Kalibrator"
    CalibrationEntity.METHOD_MANUAL -> "Manuell"
    CalibrationEntity.METHOD_RESET -> "Standard (zurückgesetzt)"
    else -> m
}

@Composable
fun CalibrateScreen(modifier: Modifier = Modifier, vm: CalibrationViewModel = viewModel()) {
    val context = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val active by vm.active.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var pendingMode by remember { mutableStateOf<CalMode?>(null) }
    val requestPermission = rememberMicPermissionLauncher { granted -> if (granted) pendingMode?.let { vm.start(it) } }
    val startMode: (CalMode) -> Unit = { mode ->
        if (hasMicPermission(context)) vm.start(mode) else { pendingMode = mode; requestPermission() }
    }
    val busy = state.running != null
    // Calibration must happen in the foreground: abort (unsaveable) when the app is stopped.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) vm.onAppStopped() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LazyColumn(modifier.fillMaxWidth()) {
        item {
            Text(
                "Kalibrieren", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
            )
        }
        item {
            SectionCard("Aktuell") {
                StatRow("Gerät", vm.deviceModel)
                StatRow("Audioquelle", AudioSourceSelector.labelDe(vm.source))
                val a = active
                if (a == null || !a.isCalibrated) {
                    StatRow("Offset", "${Fmt.db(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, 2)} dB (CDD-Standard)")
                    Text("Unkalibriert – alle Daten werden als „unkalibriert“ markiert.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                } else {
                    StatRow("Offset", "${Fmt.db(a.offsetDb, 2)} dB (#${a.id})")
                    StatRow("Methode", methodName(a.method))
                    StatRow("Datum", Fmt.dateTime(a.createdEpochMs))
                }
                Text(
                    "Kalibrierungen gelten pro Gerät und Audioquelle. Jede Minute speichert die verwendete Kalibrierungs-ID.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        state.message?.let { msg ->
            item {
                SectionCard {
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { vm.clearMessage() }) { Text("OK") }
                }
            }
        }
        if (busy) {
            item {
                SectionCard("Messung läuft …") {
                    Text("App im Vordergrund lassen – beim Wechsel in den Hintergrund oder bei einem Anruf wird abgebrochen.", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    StatRow("Verbleibend", "${state.remainingSeconds} s")
                    StatRow("Letzte Sekunde (aktueller Offset)", "${Fmt.db(state.currentDb)} dB(A)")
                    OutlinedButton(onClick = { vm.cancel() }) { Text("Abbrechen") }
                }
            }
        }

        // 1. Reference meter ------------------------------------------------------------------
        item {
            var reading by remember { mutableStateOf("") }
            var notes by remember { mutableStateOf("") }
            SectionCard("1 · Vergleich mit Schallpegelmesser (empfohlen)") {
                Text(
                    "1. Telefon-Mikrofon direkt neben das Mikrofon eines Klasse-1/2-Schallpegelmessers halten (wenige cm).\n" +
                        "2. Beide einem gleichmässigen Geräusch aussetzen, z. B. Rosa Rauschen aus einem Lautsprecher (≥ 60 dB(A)) oder gleichmässiger Verkehr.\n" +
                        "3. Gleichzeitig hier und am Messgerät eine LAeq-Messung über dasselbe Zeitfenster starten.\n" +
                        "4. Den LAeq des Messgeräts unten eintragen.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60).forEach { s ->
                        FilterChip(selected = state.referenceWindowSeconds == s, onClick = { vm.setReferenceWindow(s) }, label = { Text("$s s") }, enabled = !busy)
                    }
                }
                Button(onClick = { startMode(CalMode.REFERENCE) }, enabled = !busy) { Text("Messung starten (${state.referenceWindowSeconds} s)") }
                state.referenceResult?.let { r ->
                    HorizontalDivider()
                    StatRow("Gemessen (roh, Offset 0)", "${Fmt.db(r.rawLaeqDb, 2)} dB")
                    StatRow("Mit aktuellem Offset", "${Fmt.db(r.rawLaeqDb + (active?.offsetDb ?: Acoustics.DEFAULT_CALIBRATION_OFFSET_DB))} dB(A)")
                    StatRow("Standardabweichung 1-s-Pegel", "${Fmt.db(r.stdDevDb, 2)} dB")
                    OutlinedTextField(
                        value = reading, onValueChange = { reading = it },
                        label = { Text("LAeq des Messgeräts [dB(A)]") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notizen (Messgerät, Ort, Geräusch)") }, modifier = Modifier.fillMaxWidth())
                    val ref = parseDb(reading)
                    if (ref != null) {
                        StatRow("Neuer Offset", "${Fmt.db(CalibrationMath.referenceOffset(ref, r.rawLaeqDb), 2)} dB")
                    }
                    vm.referenceWarnings(ref).forEach { Text("⚠ " + warningText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    Button(onClick = { ref?.let { vm.saveReference(it, notes); reading = ""; notes = "" } }, enabled = ref != null) { Text("Kalibrierung übernehmen") }
                }
            }
        }

        // 2. Acoustic calibrator -------------------------------------------------------------
        item {
            var notes by remember { mutableStateOf("") }
            SectionCard("2 · Akustischer Kalibrator (1 kHz)") {
                Text(
                    "Kalibrator (94 dB oder 114 dB bei 1 kHz) dicht auf das Telefon-Mikrofon setzen. Telefon-Mikrofone brauchen dafür " +
                        "einen passenden Adapter (z. B. Gummi-Kuppler); ohne dichte Ankopplung ist das Ergebnis falsch. Messdauer 10 s.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(94.0, 114.0).forEach { v ->
                        FilterChip(selected = state.calibratorNominalDb == v, onClick = { vm.setCalibratorNominal(v) }, label = { Text("${v.toInt()} dB") }, enabled = !busy)
                    }
                }
                Button(onClick = { startMode(CalMode.CALIBRATOR) }, enabled = !busy) { Text("Kalibrator messen (10 s)") }
                state.calibratorResult?.let { r ->
                    HorizontalDivider()
                    StatRow("Frequenz", "${Fmt.db(r.toneFrequencyHz, 0)} Hz")
                    StatRow("Tonalität", r.tonality?.let { Fmt.percent(it) } ?: "–")
                    StatRow("Gemessen (roh)", "${Fmt.db(r.rawLaeqDb, 2)} dB")
                    StatRow("Neuer Offset", "${Fmt.db(CalibrationMath.calibratorOffset(state.calibratorNominalDb, r.rawLaeqDb), 2)} dB")
                    val warnings = vm.calibratorWarnings()
                    warnings.forEach { Text("⚠ " + warningText(it), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    val blocking = CalibrationWarning.FREQUENCY_OFF in warnings || CalibrationWarning.NOT_TONAL in warnings
                    OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notizen (Kalibrator, Adapter)") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.saveCalibrator(notes); notes = "" }, enabled = !blocking) { Text("Kalibrierung übernehmen") }
                }
            }
        }

        // 3. Manual -----------------------------------------------------------------------------
        item {
            var offset by remember { mutableStateOf("") }
            var notes by remember { mutableStateOf("") }
            SectionCard("3 · Offset manuell eingeben") {
                Text("Z. B. aus einer Kalibrierung mit einem anderen Gerät derselben Bauart oder aus einer früheren Export-Datei.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = offset, onValueChange = { offset = it }, label = { Text("Offset [dB]") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notizen / Herkunft") }, modifier = Modifier.fillMaxWidth())
                val v = parseDb(offset)
                if (v != null && CalibrationMath.implausible(v)) {
                    Text("⚠ " + warningText(CalibrationWarning.IMPLAUSIBLE_OFFSET), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = { v?.let { vm.saveManual(it, notes); offset = ""; notes = "" } }, enabled = v != null && !busy) { Text("Speichern") }
            }
        }

        // Noise floor ------------------------------------------------------------------------
        item {
            SectionCard("Eigenrauschen prüfen") {
                Text(
                    "20 s am ruhigsten verfügbaren Ort messen. Das Ergebnis ist die untere Grenze dessen, was dieses Telefon messen kann; " +
                        "Pegel in dieser Nähe sind nicht aussagekräftig.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { startMode(CalMode.NOISE_FLOOR) }, enabled = !busy) { Text("Eigenrauschen messen (20 s)") }
                state.noiseFloorDb?.let { StatRow("Eigenrauschen LAeq", "${Fmt.db(it)} dB(A)") }
            }
        }

        // History --------------------------------------------------------------------------------
        item {
            SectionCard("Verlauf") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch { shareFiles(context, listOf(vm.exportHistory()), "application/json", "Stadtlärm Kalibrierungen") }
                    }) { Text("Als JSON exportieren") }
                    TextButton(onClick = { vm.resetToDefault() }, enabled = !busy) { Text("Auf Standard") }
                }
                if (history.isEmpty()) Text("Noch keine Kalibrierungen.", style = MaterialTheme.typography.bodySmall)
            }
        }
        items(history, key = { it.id }) { h ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text(
                    "#${h.id} · ${Fmt.dateTime(h.createdEpochMs)} · ${methodName(h.method)} · ${Fmt.db(h.offsetDb, 2)} dB · ${h.audioSource}" +
                        (if (h.notes.isNotBlank()) " · ${h.notes}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}
