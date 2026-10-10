package ch.stadtlaerm.app.edition

import android.app.Application
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import ch.stadtlaerm.app.AppContainer
import ch.stadtlaerm.app.audio.AudioTap
import ch.stadtlaerm.app.audio.NoAudioTap
import ch.stadtlaerm.app.data.EventEntity
import ch.stadtlaerm.app.ui.ShareScreen
import ch.stadtlaerm.app.ui.ShareSettingsCard
import ch.stadtlaerm.app.upload.uploadModule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

// The «MIT LÄRMKARTE» edition (flavour karte, published). Like «Stadtlärm offline» plus the opt-in
// upload «Messwerte teilen» for the shared map: app/src/karte/ holds upload/, ui/ShareScreen.kt and
// the manifest with INTERNET + ACCESS_NETWORK_STATE. This source set and app/src/main/ contain no
// code that writes audio; the Labor recorder exists only in app/src/labor/. See PRIVACY.md.

/** What differs between the editions apart from audio and clips. «mit Lärmkarte»: the upload. */
object Edition {
    /** Whether this APK contains «Messwerte teilen». */
    const val uploadAvailable: Boolean = true

    /** Shown after the version in Einstellungen → App-Version; null keeps the plain line (Labor). */
    val versionLabel: String? = "mit Lärmkarte"

    /**
     * Called once from Application.onCreate: keeps the upload schedule in line with the setting
     * and uploads after a measurement stops. Does nothing (no network) while sharing is off.
     * Started off the main thread (the token is read through the Android Keystore).
     */
    fun start(app: Application, container: AppContainer) {
        container.appScope.launch { app.uploadModule.start(container.appScope, container.live, container.recalibrator) }
    }

    /** Einstellungen → the «Messwerte teilen» card. */
    @Composable
    fun ShareSettingsEntry(onOpen: () -> Unit) = ShareSettingsCard(onOpen)

    /** The «Messwerte teilen» screen. */
    val sharingPage: (@Composable (onBack: () -> Unit, modifier: Modifier) -> Unit)? = { onBack, modifier -> ShareScreen(onBack, modifier) }

    /** The network line(s) in «Über Stadtlärm & Datenschutz», each ending with a newline. */
    const val networkPrivacyLines: String =
        "• Internet nutzt die App nur für «Messwerte teilen» (aus, bis du es einschaltest): Kennwerte und die Hektare an api.stadtlaerm.ch, sonst an keinen Server.\n" +
            "• Sonst verlassen Daten das Telefon nur, wenn du sie selbst exportierst und teilst.\n"
}

/** «mit Lärmkarte»: there is no audio recorder, the measurement service always gets [NoAudioTap]. */
object AudioTapProvider {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): AudioTap = NoAudioTap
}

/** Event clips for the chart. «mit Lärmkarte»: there are none (no clip references, no player). */
object EditionClips {
    val version: Flow<Long> = flowOf(0L)

    fun clipRefs(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") events: List<EventEntity>): Map<Long, String> =
        emptyMap()

    val player: ((clipRef: String, playlist: List<String>) -> Unit)? = null
}

/** Edition-specific UI. «mit Lärmkarte»: nothing. */
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
