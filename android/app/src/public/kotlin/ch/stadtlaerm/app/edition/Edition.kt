package ch.stadtlaerm.app.edition

import android.content.Context
import androidx.compose.runtime.Composable
import ch.stadtlaerm.app.audio.AudioTap
import ch.stadtlaerm.app.audio.NoAudioTap

// The PUBLIC edition. This source set (app/src/public/) and app/src/main/ contain no code that
// writes audio; the Labor edition's recorder exists only in app/src/labor/. See PRIVACY.md.

/** Public: there is no audio recorder, the measurement service always gets [NoAudioTap]. */
object AudioTapProvider {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): AudioTap = NoAudioTap
}

/** Edition-specific UI. Public: nothing. */
object EditionUi {
    /** Shown above every screen (Labor: the red warning banner). */
    @Composable
    fun Banner() {}

    /** Extra section at the end of Einstellungen (Labor: recording settings). */
    @Composable
    fun SettingsSection() {}

    /** Marker after an event in «Letzte Ereignisse» (Labor: a microphone if a clip exists). */
    @Composable
    fun EventMarker(@Suppress("UNUSED_PARAMETER") eventId: Long) {}
}
