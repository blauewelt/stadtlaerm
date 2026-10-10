package ch.stadtlaerm.app.edition

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.stadtlaerm.app.audio.AudioTap
import ch.stadtlaerm.app.audio.NoAudioTap
import ch.stadtlaerm.app.container
import ch.stadtlaerm.app.data.EventEntity
import ch.stadtlaerm.app.labor.ClipLibrary
import ch.stadtlaerm.app.labor.ClipPlayerSheet
import ch.stadtlaerm.app.labor.ClipRateSampler
import ch.stadtlaerm.app.labor.ClipsListDialog
import ch.stadtlaerm.app.labor.Labor
import ch.stadtlaerm.app.labor.LaborDirs
import ch.stadtlaerm.app.labor.LaborPlayer
import ch.stadtlaerm.app.labor.LaborRecorder
import ch.stadtlaerm.app.labor.RecorderStatus
import ch.stadtlaerm.app.labor.StorageBudget
import ch.stadtlaerm.app.labor.toastClipNotFound
import ch.stadtlaerm.app.ui.SectionCard
import ch.stadtlaerm.app.ui.StatRow
import ch.stadtlaerm.app.ui.SwissLocale
import ch.stadtlaerm.app.ui.shareFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// The LABOR edition (app/src/labor/): can record audio for debugging the event detector and the
// classifier. Never published on the website. See PRIVACY.md → «Labor-Build».

private val LaborRed = Color(0xFFB3261E)

/** Labor: the recorder if «Audio während der Messung aufzeichnen» is on, else nothing. */
object AudioTapProvider {
    fun create(context: Context): AudioTap {
        Labor.init(context)
        val s = Labor.settings.value
        Labor.status.value = RecorderStatus()
        // Recording on: the recorder (with neither clips nor continuous it writes only the
        // classifier log and the manifest, no audio).
        if (!s.recordAudio) return NoAudioTap
        return try {
            LaborRecorder(context, s)
        } catch (e: Exception) {
            Log.w("Labor", "recorder not started: ${e.javaClass.simpleName}")
            NoAudioTap
        }
    }
}

/**
 * Labor: the event clips for the chart (v0.3.4). [clipRefs] maps the start time of each event that
 * has a clip to its clip reference (the manifest is indexed once and cached until it changes).
 */
object EditionClips {
    /** Changes when clips are written or deleted (the chart data is then re-evaluated). */
    val version: Flow<Long> = Labor.filesVersion

    /** Event start (epoch ms) → clip reference, for the events of a chart window. Blocking (file I/O). */
    fun clipRefs(context: Context, events: List<EventEntity>): Map<Long, String> {
        if (events.isEmpty()) return emptyMap()
        val index = ClipLibrary.index(context)
        if (index.size == 0) return emptyMap()
        val out = HashMap<Long, String>()
        for (e in events) index.clipRefFor(e.id, e.startEpochMs)?.let { out[e.startEpochMs] = it }
        return out
    }

    /** Opens the clip player on a clip; «‹» / «›» step through [playlist]. */
    val player: ((clipRef: String, playlist: List<String>) -> Unit)? = { ref, playlist -> LaborPlayer.open(ref, playlist) }
}

object EditionUi {
    /**
     * First bullet of «Über Stadtlärm & Datenschutz»: what this edition does with audio. The public
     * text («Audio verlässt nie den Arbeitsspeicher …») would be false here.
     */
    const val ABOUT_AUDIO =
        "• Labor-Version: Audio kann – nur wenn du es einschaltest – als Clips und Stundendateien auf diesem Telefon " +
            "gespeichert werden, dazu ein Protokoll der Erkennung. Nichts davon wird gesendet: diese Version hat keine Internet-Berechtigung."

    /**
     * Hosted once at the app root: the clip player sheet. It is closed (and its MediaPlayer
     * released) whenever a measurement starts or stops.
     */
    @Composable
    fun Overlay() {
        val context = LocalContext.current
        val request by LaborPlayer.request.collectAsStateWithLifecycle()
        val live by context.container.live.collectAsStateWithLifecycle()
        val running = live.running
        var lastRunning by remember { mutableStateOf(running) }
        LaunchedEffect(running) {
            if (running != lastRunning) {
                lastRunning = running
                LaborPlayer.close()
            }
        }
        request?.let { r -> ClipPlayerSheet(r, onDismiss = { LaborPlayer.close() }) }
    }

    /** The permanent red banner above every screen. */
    @Composable
    fun Banner() {
        val context = LocalContext.current
        remember { Labor.init(context) }
        val st by Labor.status.collectAsStateWithLifecycle()
        val live by context.container.live.collectAsStateWithLifecycle()
        Column(
            Modifier.fillMaxWidth().background(LaborRed)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(
                "LABOR-VERSION – kann Audio aufzeichnen", color = Color.White,
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
            )
            if (st.recording && live.running) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(6.dp))
                    Text("Aufnahme läuft", color = Color.White, style = MaterialTheme.typography.labelMedium)
                }
            }
            if (st.storageFull) {
                Text(
                    "Speicher voll: Aufnahme gestoppt, die Messung läuft weiter.",
                    color = Color.White, style = MaterialTheme.typography.labelMedium,
                )
            }
        }
    }

    /** Einstellungen → «Labor». */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun SettingsSection() {
        val context = LocalContext.current
        remember { Labor.init(context) }
        val s by Labor.settings.collectAsStateWithLifecycle()
        val st by Labor.status.collectAsStateWithLifecycle()
        val live by context.container.live.collectAsStateWithLifecycle()
        val version by Labor.filesVersion.collectAsStateWithLifecycle()
        val dirs = remember { Labor.dirs(context) }
        val scope = rememberCoroutineScope()
        var confirmOn by remember { mutableStateOf(false) }
        var showFolder by remember { mutableStateOf(false) }
        var zipPlan by remember { mutableStateOf<LaborDirs.Stats?>(null) }
        var zipBusy by remember { mutableStateOf(false) }
        var confirmDelete by remember { mutableStateOf(false) }
        var showClips by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        val recordingNow = st.recording && live.running

        val stats by produceState<LaborDirs.Stats?>(null, version, recordingNow) {
            while (true) {
                value = withContext(Dispatchers.IO) { dirs.stats() }
                if (!recordingNow) break
                delay(10_000)
            }
        }

        SectionCard("Labor") {
            LaborSwitch("Audio während der Messung aufzeichnen", s.recordAudio) { v ->
                if (v) confirmOn = true else Labor.update(context) { it.copy(recordAudio = false) }
            }
            LaborSwitch("Ereignis-Clips (±5 s, WAV 16 kHz)", s.clips, enabled = s.recordAudio) { v ->
                Labor.update(context) { it.copy(clips = v) }
            }
            LaborSwitch("Durchgehend (AAC 64 kbit/s, Stundendateien)", s.continuous, enabled = s.recordAudio) { v ->
                Labor.update(context) { it.copy(continuous = v) }
            }
            Text("Clip-Rate", style = MaterialTheme.typography.bodyMedium)
            val rates = ClipRateSampler.CHOICES
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                rates.forEachIndexed { i, n ->
                    SegmentedButton(
                        selected = s.clipEvery == n,
                        onClick = { Labor.update(context) { it.copy(clipEvery = n) } },
                        shape = SegmentedButtonDefaults.itemShape(i, rates.size),
                        enabled = s.recordAudio && s.clips,
                    ) { Text(if (n == 1) "jedes" else "jedes $n.", maxLines = 1) }
                }
            }
            Text("Maximaler Speicher", style = MaterialTheme.typography.bodyMedium)
            val caps = StorageBudget.CHOICES_GB
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                caps.forEachIndexed { i, gb ->
                    SegmentedButton(
                        selected = s.maxGb == gb,
                        onClick = { Labor.update(context) { it.copy(maxGb = gb) } },
                        shape = SegmentedButtonDefaults.itemShape(i, caps.size),
                        enabled = s.recordAudio,
                    ) { Text(gbLabel(gb), maxLines = 1) }
                }
            }
            Text(
                "Pro Nacht (8 h): durchgehend etwa 230 MB (ca. 30 MB pro Stunde), dazu die Clips mit je etwa 0.4 MB " +
                    "(höchstens 2 MB) und das Klassifikator-Protokoll (jede Sekunde die erkannten Geräusche, ca. 1 MB pro Stunde). " +
                    "Ist der Speicher voll, stoppt nur die Aufnahme, die Messung läuft weiter. " +
                    "Änderungen gelten ab dem nächsten Start der Messung; Ausschalten stoppt eine laufende Aufnahme sofort.",
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val sts = stats
            StatRow("Belegt", if (sts == null) "…" else "${mb(sts.usedBytes)} von ${gbLabel(s.maxGb)}")
            StatRow("Clips", sts?.clipCount?.toString() ?: "…")
            StatRow("Stundendateien", sts?.hourFiles?.toString() ?: "…")
            StatRow("Klassifikator-Protokolle", sts?.classifierFiles?.toString() ?: "…")
            if (st.storageFull) {
                Text("Speicher voll: Die Aufnahme wurde gestoppt, die Messung läuft weiter.", color = LaborRed, style = MaterialTheme.typography.bodySmall)
            }
            if (recordingNow && st.droppedThisSession > 0) {
                Text("Verworfen (Schreiben zu langsam oder Speicher voll): ${st.droppedThisSession}", style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = { showClips = true }, enabled = (sts?.clipCount ?: 0) > 0) { Text("Clips anhören") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showFolder = true }) { Text("Ordner anzeigen") }
                OutlinedButton(
                    enabled = !zipBusy,
                    onClick = { scope.launch { zipPlan = withContext(Dispatchers.IO) { dirs.stats() } } },
                ) { Text(if (zipBusy) "ZIP …" else "Als ZIP teilen") }
            }
            OutlinedButton(enabled = !recordingNow, onClick = { confirmDelete = true }) { Text("Alle Aufnahmen löschen") }
            if (recordingNow) Text("Löschen ist während einer Aufnahme nicht möglich.", style = MaterialTheme.typography.bodySmall)
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }

        if (showClips) ClipsListDialog(onDismiss = { showClips = false })

        if (confirmOn) {
            AlertDialog(
                onDismissRequest = { confirmOn = false },
                text = {
                    Text(
                        "Die Labor-Version speichert dann Roh-Audio auf diesem Telefon, auch Stimmen in der Nähe. " +
                            "Die Dateien bleiben auf dem Telefon, bis du sie löschst."
                    )
                },
                confirmButton = {
                    TextButton(onClick = { confirmOn = false; Labor.update(context) { it.copy(recordAudio = true) } }) { Text("Aufzeichnen") }
                },
                dismissButton = { TextButton(onClick = { confirmOn = false }) { Text("Abbrechen") } },
            )
        }

        if (showFolder) {
            val rel = "Android/data/${context.packageName}/files/audio"
            AlertDialog(
                onDismissRequest = { showFolder = false },
                title = { Text("Aufnahme-Ordner") },
                text = {
                    Text(
                        "Interner Speicher › $rel\n\n${dirs.root.absolutePath}\n\n" +
                            "Am Computer (USB, Dateiübertragung) ist der Ordner sichtbar. Viele Dateimanager auf dem Telefon " +
                            "dürfen Android/data ab Android 11 nicht mehr öffnen; dann bitte «Als ZIP teilen» oder USB verwenden."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        showFolder = false
                        if (!openFolder(context, rel)) message = "Kein Dateimanager kann den Ordner öffnen. Pfad: ${dirs.root.absolutePath}"
                    }) { Text("Im Dateimanager öffnen") }
                },
                dismissButton = { TextButton(onClick = { showFolder = false }) { Text("Schliessen") } },
            )
        }

        zipPlan?.let { plan ->
            val withContinuous = plan.continuousBytes in 1 until LaborDirs.ZIP_CONTINUOUS_LIMIT
            val total = plan.textAndClipBytes + if (withContinuous) plan.continuousBytes else 0
            AlertDialog(
                onDismissRequest = { zipPlan = null },
                title = { Text("Als ZIP teilen") },
                text = {
                    Text(
                        buildString {
                            append("${plan.clipCount} Clips, das Manifest und ${plan.classifierFiles} Klassifikator-Protokolle")
                            if (withContinuous) append(" sowie ${plan.hourFiles} Stundendateien")
                            append(", etwa ${mb(total)}.")
                            if (plan.continuousBytes >= LaborDirs.ZIP_CONTINUOUS_LIMIT) {
                                append("\n\nDie Stundendateien (${mb(plan.continuousBytes)}) sind für ein ZIP zu gross; bitte per USB kopieren.")
                            }
                            if (total > 100_000_000L) append("\n\nDas ZIP ist gross; nicht jede App kann es versenden.")
                            if (recordingNow) append("\n\nDie laufende Stundendatei ist erst nach dem Stopp der Messung lesbar.")
                        }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        zipPlan = null
                        zipBusy = true
                        scope.launch {
                            val zip = withContext(Dispatchers.IO) {
                                try {
                                    val dir = File(context.externalCacheDir ?: context.cacheDir, "labor_share")
                                    dir.listFiles()?.forEach { it.delete() }
                                    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmm"))
                                    File(dir, "stadtlaerm-labor-$stamp.zip").also { dirs.zipTo(it, withContinuous) }
                                } catch (e: Exception) {
                                    null
                                }
                            }
                            zipBusy = false
                            if (zip == null) message = "ZIP konnte nicht erstellt werden (Speicher?)."
                            else try {
                                shareFiles(context, listOf(zip), "application/zip", "Stadtlärm Labor – Aufnahmen")
                            } catch (e: Exception) {
                                message = "Teilen nicht möglich: ${e.javaClass.simpleName}"
                            }
                        }
                    }) { Text("ZIP erstellen") }
                },
                dismissButton = { TextButton(onClick = { zipPlan = null }) { Text("Abbrechen") } },
            )
        }

        if (confirmDelete) {
            AlertDialog(
                onDismissRequest = { confirmDelete = false },
                title = { Text("Alle Aufnahmen löschen?") },
                text = { Text("Alle Clips, Stundendateien, Klassifikator-Protokolle und das Manifest werden gelöscht. Die Messwerte bleiben.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmDelete = false
                        LaborPlayer.close()
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                dirs.deleteAll()
                                File(context.externalCacheDir ?: context.cacheDir, "labor_share").listFiles()?.forEach { it.delete() }
                                Labor.refreshClipIds(dirs)
                            }
                            Labor.status.value = Labor.status.value.copy(storageFull = false)
                            Labor.filesVersion.value = Labor.filesVersion.value + 1
                            message = "Alle Aufnahmen gelöscht."
                        }
                    }) { Text("Löschen") }
                },
                dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
            )
        }

        LaunchedEffect(message) {
            if (message != null) { delay(8_000); message = null }
        }
    }

    /** A small red record dot after events that have a clip; tapping it plays the clip. */
    @Composable
    fun EventMarker(eventId: Long) {
        val ids by Labor.clipEventIds.collectAsStateWithLifecycle()
        if (eventId in ids) {
            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            Box(
                Modifier.padding(start = 2.dp).size(28.dp).clip(CircleShape)
                    .clickable(onClickLabel = "Clip abspielen") {
                        scope.launch {
                            val index = withContext(Dispatchers.IO) { ClipLibrary.index(context) }
                            val e = index.byEventId(eventId)
                            if (e == null) toastClipNotFound(context)
                            else LaborPlayer.open(e.ref, index.entries.map { it.ref })
                        }
                    }
                    .semantics { contentDescription = "Clip vorhanden, abspielen" },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(LaborRed))
            }
        }
    }
}

@Composable
private fun LaborSwitch(label: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun gbLabel(gb: Double): String =
    if (gb < 1) String.format(SwissLocale, "%.1f GB", gb) else String.format(SwissLocale, "%.0f GB", gb)

private fun mb(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(SwissLocale, "%.2f GB", bytes / 1e9)
    else -> String.format(SwissLocale, "%.1f MB", bytes / 1e6)
}

/** Tries to open the folder in DocumentsUI (blocked for Android/data on many Android 11+ devices). */
private fun openFolder(context: Context, relativePath: String): Boolean {
    val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:$relativePath")
    val intents = listOf(
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR),
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "resource/folder"),
    )
    for (i in intents) {
        try {
            context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
            return true
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
    return false
}
