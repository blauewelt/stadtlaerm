package ch.stadtlaerm.app.edition

import android.app.Application
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.stadtlaerm.app.AppContainer
import ch.stadtlaerm.app.audio.AudioTap
import ch.stadtlaerm.app.audio.NoAudioTap
import ch.stadtlaerm.app.data.EventEntity
import ch.stadtlaerm.app.ui.SectionCard
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// The OFFLINE edition («Stadtlärm offline», published). This source set (app/src/offline/) and
// app/src/main/ contain no code that writes audio (the Labor recorder exists only in
// app/src/labor/) and no code that uses the network (the upload exists only in app/src/karte/).
// The APK has no INTERNET permission. See PRIVACY.md.

/** What differs between the editions apart from audio and clips. Offline: no upload. */
object Edition {
    /** Whether this APK contains «Messwerte teilen». */
    const val uploadAvailable: Boolean = false

    /** Shown after the version in Einstellungen → App-Version; null keeps the plain line (Labor). */
    val versionLabel: String? = "offline"

    /** Called once from Application.onCreate. Offline: nothing to start. */
    fun start(@Suppress("UNUSED_PARAMETER") app: Application, @Suppress("UNUSED_PARAMETER") container: AppContainer) {}

    /** Einstellungen, where «Messwerte teilen» is in the other edition: one line pointing to it. */
    @Composable
    fun ShareSettingsEntry(@Suppress("UNUSED_PARAMETER") onOpen: () -> Unit) {
        SectionCard {
            Text(
                "Diese Ausgabe ist komplett offline. Mitmachen bei der Lärmkarte: Ausgabe «mit Lärmkarte» auf " +
                    "stadtlaerm.ch installieren (die Messwerte bleiben erhalten).",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    /** The «Messwerte teilen» screen; null: this edition has none. */
    val sharingPage: (@Composable (onBack: () -> Unit, modifier: Modifier) -> Unit)? = null

    /** The network line(s) in «Über Stadtlärm & Datenschutz», each ending with a newline. */
    const val networkPrivacyLines: String =
        "• Diese Ausgabe hat keine Internet-Berechtigung. Daten verlassen das Telefon nur, wenn du sie selbst exportierst und teilst.\n"
}

/** Offline: there is no audio recorder, the measurement service always gets [NoAudioTap]. */
object AudioTapProvider {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): AudioTap = NoAudioTap
}

/** Event clips for the chart. Offline: there are none (no clip references, no player). */
object EditionClips {
    val version: Flow<Long> = flowOf(0L)

    fun clipRefs(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") events: List<EventEntity>): Map<Long, String> =
        emptyMap()

    val player: ((clipRef: String, playlist: List<String>) -> Unit)? = null
}

/** Edition-specific UI. Offline: nothing. */
object EditionUi {
    /** Hosted once at the app root (Labor: the clip player). */
    @Composable
    fun Overlay() {}

    /** Shown above every screen (Labor: the red warning banner). */
    @Composable
    fun Banner() {}

    /** Extra section at the end of Einstellungen (Labor: recording settings). */
    @Composable
    fun SettingsSection() {}

    /** Marker after an event in «Letzte Ereignisse» (Labor: a red dot if a clip exists, tap plays it). */
    @Composable
    fun EventMarker(@Suppress("UNUSED_PARAMETER") eventId: Long) {}
}
