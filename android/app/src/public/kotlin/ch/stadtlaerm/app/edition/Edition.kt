package ch.stadtlaerm.app.edition

import android.content.Context
import androidx.compose.runtime.Composable
import ch.stadtlaerm.app.audio.AudioTap
import ch.stadtlaerm.app.audio.NoAudioTap
import ch.stadtlaerm.app.data.EventEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

// The PUBLIC edition. This source set (app/src/public/) and app/src/main/ contain no code that
// writes audio; the Labor edition's recorder exists only in app/src/labor/. See PRIVACY.md.

/** Public: there is no audio recorder, the measurement service always gets [NoAudioTap]. */
object AudioTapProvider {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): AudioTap = NoAudioTap
}

/** Event clips for the chart. Public: there are none (no clip references, no player). */
object EditionClips {
    val version: Flow<Long> = flowOf(0L)

    fun clipRefs(@Suppress("UNUSED_PARAMETER") context: Context, @Suppress("UNUSED_PARAMETER") events: List<EventEntity>): Map<Long, String> =
        emptyMap()

    val player: ((clipRef: String, playlist: List<String>) -> Unit)? = null
}

/** Edition-specific UI. Public: nothing. */
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
