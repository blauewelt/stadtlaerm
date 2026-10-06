package ch.stadtlaerm.app.ui

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import ch.stadtlaerm.app.container
import ch.stadtlaerm.dsp.classify.CategoryMapper
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

val SwissLocale: Locale = Locale("de", "CH")

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F3A4D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E4F0),
    onPrimaryContainer = Color(0xFF0B1E2A),
    secondary = Color(0xFF8A5A00),
    secondaryContainer = Color(0xFFFFE3A8),
    onSecondaryContainer = Color(0xFF2B1B00),
    tertiary = Color(0xFF3E6B48),
    background = Color(0xFFF7F7F4),
    surface = Color(0xFFF7F7F4),
    surfaceVariant = Color(0xFFE4E4DE),
    error = Color(0xFFB3261E),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9CC6E4),
    onPrimary = Color(0xFF0B1E2A),
    primaryContainer = Color(0xFF2A4B61),
    onPrimaryContainer = Color(0xFFD3E4F0),
    secondary = Color(0xFFF2C14E),
    secondaryContainer = Color(0xFF5A3F00),
    onSecondaryContainer = Color(0xFFFFE3A8),
    tertiary = Color(0xFF9FD3A8),
    background = Color(0xFF111416),
    surface = Color(0xFF111416),
    surfaceVariant = Color(0xFF2A2E31),
    error = Color(0xFFF2B8B5),
    errorContainer = Color(0xFF601410),
    onErrorContainer = Color(0xFFF9DEDC),
)

@Composable
fun StadtlaermTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors) {
        Surface(color = MaterialTheme.colorScheme.background, content = content)
    }
}

object Fmt {
    fun db(v: Double?, decimals: Int = 1): String =
        if (v == null || v.isNaN() || v.isInfinite()) "–" else String.format(SwissLocale, "%.${decimals}f", v)

    private val time = DateTimeFormatter.ofPattern("HH:mm:ss", SwissLocale)
    private val hm = DateTimeFormatter.ofPattern("HH:mm", SwissLocale)
    private val dateTime = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", SwissLocale)

    fun time(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(time)
    fun hm(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(hm)
    fun dateTime(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(dateTime)

    fun duration(seconds: Double): String = when {
        seconds < 60 -> String.format(SwissLocale, "%.1f s", seconds)
        seconds < 3600 -> String.format(SwissLocale, "%d min %02d s", (seconds / 60).toInt(), (seconds % 60).toInt())
        else -> String.format(SwissLocale, "%d h %02d min", (seconds / 3600).toInt(), ((seconds % 3600) / 60).toInt())
    }

    fun percent(v: Double): String = String.format(SwissLocale, "%.0f %%", v * 100)
}

/** German display name of a category id. */
fun categoryName(context: Context, id: String?): String = when (id) {
    null -> "–"
    "n/a" -> "nicht klassifiziert (aus)"
    else -> try { context.container.categoryMapper.nameDe(id) } catch (_: Exception) { id }
}

fun categoryColor(id: String?): Color = when (id) {
    "loud_vehicle" -> Color(0xFFD1495B)
    "road_traffic" -> Color(0xFFEDAE49)
    "rail_tram" -> Color(0xFF00798C)
    "aircraft" -> Color(0xFF6C5B7B)
    "construction" -> Color(0xFF8D6A3F)
    "voices" -> Color(0xFF3E8E41)
    "music" -> Color(0xFF30638E)
    CategoryMapper.UNCLASSIFIED -> Color(0xFF9E9E9E)
    else -> Color(0xFFBDBDBD)
}

@Composable
fun SectionCard(title: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

fun hasMicPermission(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Returns a launcher that requests microphone (+ notification) permission and calls [onResult]. */
@Composable
fun rememberMicPermissionLauncher(onResult: (Boolean) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        onResult(res[Manifest.permission.RECORD_AUDIO] == true)
    }
    return {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
        launcher.launch(perms.toTypedArray())
    }
}

/** Shares exported files through the system share sheet (FileProvider, read-only grant). */
fun shareFiles(context: Context, files: List<File>, mime: String, subject: String) {
    val authority = "${context.packageName}.fileprovider"
    val uris = files.map { FileProvider.getUriForFile(context, authority, it) }
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = mime
    intent.putExtra(Intent.EXTRA_SUBJECT, subject)
    intent.clipData = ClipData.newRawUri(null, uris[0]).also { cd -> uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(intent, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
