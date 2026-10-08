package ch.stadtlaerm.app.labor

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ch.stadtlaerm.app.container
import ch.stadtlaerm.app.ui.StatRow
import ch.stadtlaerm.app.ui.SwissLocale
import ch.stadtlaerm.app.ui.categoryColor
import ch.stadtlaerm.app.ui.categoryName
import ch.stadtlaerm.app.ui.shareFiles
import ch.stadtlaerm.chart.RangeMode
import ch.stadtlaerm.chart.Windows
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// LABOR BUILD ONLY (app/src/labor/). The clip player sheet and the clip list (v0.3.4).

const val CLIP_NOT_FOUND = "Clip nicht gefunden"

fun toastClipNotFound(context: Context) {
    Toast.makeText(context.applicationContext, CLIP_NOT_FOUND, Toast.LENGTH_SHORT).show()
}

private val clipDateTime = DateTimeFormatter.ofPattern("EE dd.MM. HH:mm:ss", SwissLocale)

private fun clipTime(ms: Long?): String =
    ms?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(clipDateTime) } ?: "Zeit unbekannt"

/** «0:03.2» */
private fun mmss(ms: Long): String {
    val t = ms.coerceAtLeast(0)
    return String.format(SwissLocale, "%d:%02d.%d", t / 60_000, (t / 1000) % 60, (t % 1000) / 100)
}

private fun db(v: Double?): String = if (v == null || v.isNaN()) "–" else String.format(SwissLocale, "%.1f", v)
private fun pct(v: Double): String = String.format(SwissLocale, "%.0f %%", v * 100)

private fun eventDuration(e: ClipEntry?): String = e?.durationS?.let {
    if (it < 60) String.format(SwissLocale, "%.1f s", it) else String.format(SwissLocale, "%d min %02d s", (it / 60).toInt(), (it % 60).toInt())
} ?: "–"

/**
 * The «Clip» bottom sheet: event data, a progress bar with pre-roll / event / post-roll, play/pause,
 * scrub bar, previous/next clip, «Teilen», and the classifier result of the second being heard.
 * The MediaPlayer is released when the sheet goes away.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipPlayerSheet(request: PlayRequest, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val playback = remember { ClipPlayback(context) }
    DisposableEffect(playback) { onDispose { playback.release() } }
    // Pause when the app goes to the background.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, playback) {
        val obs = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) playback.pause() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val live by context.container.live.collectAsStateWithLifecycle()
    val filesVersion by Labor.filesVersion.collectAsStateWithLifecycle()

    var ref by remember(request.id) { mutableStateOf(request.ref) }
    val index by produceState<ClipIndex?>(null, filesVersion) { value = withContext(Dispatchers.IO) { ClipLibrary.index(context) } }
    val playlist = remember(request.id, index) { index?.let { ClipPlaylist(request.playlist, it) } }

    var loadedRef by remember { mutableStateOf<String?>(null) }
    var wav by remember { mutableStateOf<WavInfo?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var muted by remember { mutableStateOf(false) }
    var silent by remember { mutableStateOf(false) }

    // Load (and start) the clip whenever the reference changes.
    LaunchedEffect(ref) {
        if (ref == loadedRef) return@LaunchedEffect // back on the clip that is loaded (see below)
        val f = ClipLibrary.file(context, ref)
        val info = withContext(Dispatchers.IO) { WavInfo.read(f) }
        if (info == null) {
            // Missing or corrupt: say so and stay on the clip that is loaded (if any).
            toastClipNotFound(context)
            val prev = loadedRef
            if (prev == null) onDismiss() else ref = prev
            return@LaunchedEffect
        }
        if (!playback.load(f)) { // the previous clip is released by now
            toastClipNotFound(context)
            onDismiss()
            return@LaunchedEffect
        }
        wav = info
        loadedRef = ref
        position = 0
        playback.play()
    }
    // Poll position and state (50 ms) while the sheet is open.
    LaunchedEffect(playback) {
        var n = 0
        while (true) {
            if (scrub == null) position = playback.positionMs
            playing = playback.isPlaying
            if (playback.failed) {
                playback.release()
                toastClipNotFound(context)
                onDismiss()
                break
            }
            if (n++ % 10 == 0) { muted = playback.mediaMuted; silent = playback.ringerSilent }
            delay(50)
        }
    }

    val entry = index?.byRef(loadedRef ?: ref)
    val clipMs = wav?.durationMs?.takeIf { it > 0 } ?: playback.durationMs
    val bar = ClipBar.of(clipMs, entry?.preRollMs, entry?.eventSpanMs, entry?.truncated == true)
    val shownPos = scrub?.let { (it * clipMs).toLong() } ?: position

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Clip", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val pl = playlist
                if (pl != null && pl.refs.size > 1) {
                    Text("${pl.indexOf(loadedRef ?: ref) + 1} / ${pl.refs.size}", style = MaterialTheme.typography.labelLarge)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(categoryColor(entry?.category)))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (entry == null) "…" else "${clipTime(entry.eventStartMs)} · " +
                        if (entry.fromManifest) categoryName(context, entry.category ?: "n/a") else "ohne Ereignisdaten",
                    style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            StatRow("LAFmax", "${db(entry?.lafMaxDb)} dB(A)")
            StatRow("Dauer des Ereignisses", eventDuration(entry))
            StatRow("Clip", "${mmss(clipMs)} · Vorlauf ${entry?.preRollMs?.let { mmss(it) } ?: "–"}")
            if (entry?.truncated == true) Text("Auf 60 s gekürzt: das Ereignis läuft über das Clip-Ende hinaus.", style = MaterialTheme.typography.bodySmall)
            if (entry?.postRollCut == true) Text("Nachlauf gekürzt (Messung gestoppt).", style = MaterialTheme.typography.bodySmall)
            if (entry != null && !entry.fromManifest) Text("Keine Manifest-Zeile: Zeit aus dem Dateinamen, keine Ereignisdaten.", style = MaterialTheme.typography.bodySmall)

            ClipProgressBar(
                bar = bar, fraction = if (clipMs > 0) (shownPos.toFloat() / clipMs).coerceIn(0f, 1f) else 0f,
                traceMarks = entry?.trace?.map { it.atMs.toFloat() / clipMs.coerceAtLeast(1) } ?: emptyList(),
                onSeek = { f -> playback.seekTo((f * clipMs).toLong()); position = (f * clipMs).toLong() },
            )
            Slider(
                value = if (clipMs > 0) (shownPos.toFloat() / clipMs).coerceIn(0f, 1f) else 0f,
                onValueChange = { scrub = it },
                onValueChangeFinished = {
                    scrub?.let { f -> playback.seekTo((f * clipMs).toLong()); position = (f * clipMs).toLong() }
                    scrub = null
                },
                modifier = Modifier.semantics { contentDescription = "Position im Clip" },
            )
            Row(Modifier.fillMaxWidth()) {
                Text(mmss(shownPos), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text("Vorlauf · Ereignis · Nachlauf", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(mmss(clipMs), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }

            val prev = playlist?.previous(loadedRef ?: ref)
            val next = playlist?.next(loadedRef ?: ref)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { prev?.let { ref = it } }, enabled = prev != null) { Text("‹ Vorheriger") }
                Button(
                    onClick = { if (playing) playback.pause() else playback.play() },
                    enabled = loadedRef != null, modifier = Modifier.weight(1f),
                ) {
                    if (playing) Text("❚❚ Pause")
                    else { Icon(Icons.Filled.PlayArrow, contentDescription = null); Spacer(Modifier.width(4.dp)); Text("Abspielen") }
                }
                OutlinedButton(onClick = { next?.let { ref = it } }, enabled = next != null) { Text("Nächster ›") }
            }
            OutlinedButton(
                enabled = loadedRef != null,
                onClick = {
                    val r = loadedRef ?: return@OutlinedButton
                    val f = ClipLibrary.file(context, r)
                    if (!f.isFile) { toastClipNotFound(context); return@OutlinedButton }
                    try {
                        shareFiles(context, listOf(f), "audio/wav", "Stadtlärm Labor – Clip ${f.name}")
                    } catch (e: Exception) {
                        Toast.makeText(context, "Teilen nicht möglich: ${e.javaClass.simpleName}", Toast.LENGTH_SHORT).show()
                    }
                },
            ) { Text("Teilen") }

            if (muted) {
                Hint("Lautstärke: Die Medien-Lautstärke ist auf 0. Bitte mit den Lautstärketasten erhöhen.")
            } else if (silent) {
                Hint("Lautstärke: Das Telefon ist lautlos; Clips spielen trotzdem über die Medien-Lautstärke.")
            }
            if (live.running) {
                Hint("Die Messung läuft: Was der Lautsprecher abspielt, wird mitgemessen. Besser mit Kopfhörer anhören.")
            }

            HorizontalDivider(Modifier.padding(vertical = 6.dp))
            TraceBlock(context, entry, shownPos)
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

/** The classifier run of the second being heard, read from the clip's `classifierTrace`. */
@Composable
private fun TraceBlock(context: Context, entry: ClipEntry?, positionMs: Long) {
    Text("Klassifikator", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    if (entry == null || !entry.hasTrace) {
        Text(
            "Kein Klassifikator-Protokoll für diesen Clip (Klassifikation aus oder keine Manifest-Zeile).",
            style = MaterialTheme.typography.bodySmall,
        )
    } else {
        val t = TraceLookup.at(entry.trace, positionMs)
        if (t == null) {
            Text("Für diese Stelle gibt es kein Ergebnis.", style = MaterialTheme.typography.bodySmall)
        } else {
            val from = (t.atMs - TraceLookup.WINDOW_MS).coerceAtLeast(0)
            Text("Fenster ${mmss(from)}–${mmss(t.atMs)}", style = MaterialTheme.typography.labelMedium)
            t.top3.forEach { l -> StatRow(l.label, pct(l.score)) }
            val decision = when {
                t.ignored -> "ignoriert (stummgeschaltet/ungültig)"
                t.category == null -> "keine"
                else -> "${categoryName(context, t.category)}" + (t.categoryScore?.let { " (${pct(it)})" } ?: "")
            }
            StatRow("Entscheidung", decision)
            StatRow("Gain", t.gainDb?.let { String.format(SwissLocale, "%+.1f dB", it) } ?: "–")
            StatRow("LAF / LAFmax im Fenster", "${db(t.lafDb)} / ${db(t.lafMaxDb)} dB(A)")
        }
    }
    if (entry != null && entry.top3.isNotEmpty()) {
        Text(
            "Ereignis gesamt: " + entry.top3.joinToString(" · ") { "${it.label} ${pct(it.score)}" } +
                (entry.category?.let { " → ${categoryName(context, it)}" } ?: ""),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The clip as a bar: pre-roll and post-roll shaded, the event span highlighted, the played part
 * darker, a playhead, and small ticks where classifier windows end. Tap or drag to seek.
 */
@Composable
private fun ClipProgressBar(bar: ClipBar, fraction: Float, traceMarks: List<Float>, onSeek: (Float) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shaded = scheme.onSurface.copy(alpha = 0.10f)
    val event = scheme.primary.copy(alpha = 0.40f)
    val played = scheme.onSurface.copy(alpha = 0.22f)
    val head = scheme.onSurface
    val tick = scheme.onSurfaceVariant
    Canvas(
        Modifier.fillMaxWidth().height(36.dp)
            .semantics { contentDescription = "Clip: Vorlauf, Ereignis, Nachlauf" }
            .pointerInput(Unit) { detectTapGestures { o -> onSeek((o.x / size.width).coerceIn(0f, 1f)) } }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onSeek((change.position.x / size.width).coerceIn(0f, 1f)) }
            },
    ) {
        val w = size.width
        val top = 4.dp.toPx()
        val h = size.height - 2 * top - 6.dp.toPx()
        val r = CornerRadius(4.dp.toPx())
        drawRoundRect(shaded, Offset(0f, top), Size(w, h), r)
        drawRect(event, Offset(bar.eventStart * w, top), Size((bar.eventEnd - bar.eventStart) * w, h))
        drawRect(played, Offset(0f, top), Size(fraction * w, h))
        val tickTop = top + h + 2.dp.toPx()
        for (m in traceMarks) if (m in 0f..1f) drawLine(tick, Offset(m * w, tickTop), Offset(m * w, tickTop + 4.dp.toPx()), 1.dp.toPx())
        drawLine(head, Offset(fraction * w, 0f), Offset(fraction * w, top + h + 2.dp.toPx()), 2.dp.toPx())
    }
}

/**
 * Einstellungen → Labor → «Clips»: all clips newest first (or only the current night's), with
 * category filter chips, count and total size. Tapping a clip opens the player; «‹» / «›» step
 * through the filtered list.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClipsListDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val version by Labor.filesVersion.collectAsStateWithLifecycle()
    val index by produceState<ClipIndex?>(null, version) { value = withContext(Dispatchers.IO) { ClipLibrary.index(context) } }
    val zone = remember { ZoneId.systemDefault() }
    val night = remember { Windows(zone).of(RangeMode.NIGHT, ClipNights.currentNightOf(System.currentTimeMillis(), zone)) }
    var onlyNight by remember { mutableStateOf<Boolean?>(null) }
    var category by remember { mutableStateOf<String?>(null) }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Clips", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Schliessen") }
                }
                val all = index
                if (all == null) {
                    Text("Lade …", Modifier.padding(16.dp))
                    return@Column
                }
                val inNight = all.entries.filter { e -> e.eventStartMs?.let { it in night } == true }
                val nightOnly = onlyNight ?: inNight.isNotEmpty()
                val scope = if (nightOnly) inNight else all.entries
                val cats = scope.map { it.category }.distinct()
                val shown = scope.filter { category == null || it.category == category }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    SegmentedButton(selected = nightOnly, onClick = { onlyNight = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                        Text("Diese Nacht (${inNight.size})", maxLines = 1)
                    }
                    SegmentedButton(selected = !nightOnly, onClick = { onlyNight = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text("Alle (${all.size})", maxLines = 1)
                    }
                }
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(horizontal = 16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item { FilterChip(selected = category == null, onClick = { category = null }, label = { Text("Alle Kategorien") }) }
                    items(cats, key = { it ?: "" }) { id ->
                        FilterChip(
                            selected = category == id && id != null, onClick = { category = id },
                            label = { Text(if (id == null) "ohne Kategorie" else categoryName(context, id)) },
                            leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(id))) },
                        )
                    }
                }
                Text(
                    "${shown.size} Clips · ${mb(shown.sumOf { it.bytes })}",
                    style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                val playlist = shown.map { it.ref } // oldest first: «›» = later event
                LazyColumn(Modifier.fillMaxSize()) {
                    if (shown.isEmpty()) item { Text("Keine Clips.", Modifier.padding(16.dp)) }
                    items(shown.asReversed(), key = { it.ref }) { e ->
                        ClipRow(context, e) { LaborPlayer.open(e.ref, playlist) }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
            }
        }
    }
}

@Composable
private fun ClipRow(context: Context, e: ClipEntry, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(e.category)))
            Spacer(Modifier.width(8.dp))
            Text(clipTime(e.eventStartMs), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(if (e.hasTrace) "Trace ✓" else "ohne Trace", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.padding(start = 18.dp)) {
            Text(
                if (e.fromManifest) categoryName(context, e.category ?: "n/a") else "ohne Manifest-Zeile",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text("${db(e.lafMaxDb)} dB(A) · ${eventDuration(e)}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
        }
        HorizontalDivider(Modifier.padding(top = 8.dp), color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

private fun mb(bytes: Long): String = String.format(SwissLocale, "%.1f MB", bytes / 1e6)
