package ch.stadtlaerm.app.labor

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.io.FileInputStream
import java.time.ZoneId

// LABOR BUILD ONLY (app/src/labor/). Playback of the recorded event clips (v0.3.4). Playback only:
// nothing here opens the microphone, so it cannot conflict with a running measurement.

/** The clip index of the audio folder, re-read only when the manifest or the clips folder changed. */
object ClipLibrary {
    private var key: List<Long>? = null
    private var cached: ClipIndex = ClipIndex.EMPTY

    /** Blocking (file I/O): call off the main thread. */
    @Synchronized
    fun index(context: Context): ClipIndex {
        val d = Labor.dirs(context.applicationContext)
        val k = listOf(
            d.manifest.length(), d.manifest.lastModified(), d.clips.lastModified(),
            (d.clips.list()?.size ?: 0).toLong(), Labor.filesVersion.value,
        )
        if (k == key) return cached
        cached = try {
            ClipIndexReader.read(d.manifest, d.clips, ZoneId.systemDefault())
        } catch (e: Exception) {
            Log.w("ClipLibrary", "index: ${e.javaClass.simpleName}")
            ClipIndex.EMPTY
        }
        key = k
        return cached
    }

    /** The WAV file of [ref]; always inside the clips folder (the reference's folder part is ignored). */
    fun file(context: Context, ref: String): File = File(Labor.dirs(context.applicationContext).clips, ref.substringAfterLast('/'))
}

/** What the player shows: a clip and the clips that «‹» / «›» step through. */
data class PlayRequest(val ref: String, val playlist: List<String>, val id: Long)

/** Opens/closes the clip player (one per app; the sheet is hosted by EditionUi.Overlay). */
object LaborPlayer {
    private val _request = MutableStateFlow<PlayRequest?>(null)
    val request: StateFlow<PlayRequest?> = _request
    private var nextId = 0L

    @Synchronized
    fun open(ref: String, playlist: List<String>) {
        _request.value = PlayRequest(ref, if (ref in playlist) playlist else playlist + ref, ++nextId)
    }

    fun close() { _request.value = null }
}

/**
 * One MediaPlayer for WAV clips, played on the media stream (USAGE_MEDIA) with transient audio
 * focus. Main thread only.
 */
class ClipPlayback(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    /** Set when the player reported an error (corrupt file): the UI shows «Clip nicht gefunden». */
    var failed: Boolean = false
        private set

    /** Prepares [file]; false if it cannot be played. Releases the previous clip. */
    fun load(file: File): Boolean {
        stopAndRelease()
        failed = false
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(attrs)
            // Through a file descriptor: the media server may not be allowed to open app folders by path.
            FileInputStream(file).use { p.setDataSource(it.fd) }
            p.setOnErrorListener { _, what, extra ->
                Log.w("ClipPlayback", "error $what/$extra")
                failed = true
                abandonFocus()
                true
            }
            p.setOnCompletionListener { abandonFocus() }
            p.prepare()
            player = p
            true
        } catch (e: Exception) {
            Log.w("ClipPlayback", "load: ${e.javaClass.simpleName}")
            p.release()
            false
        }
    }

    val isLoaded: Boolean get() = player != null
    val isPlaying: Boolean get() = try { player?.isPlaying == true } catch (_: IllegalStateException) { false }
    val positionMs: Long get() = try { player?.currentPosition?.toLong() ?: 0L } catch (_: IllegalStateException) { 0L }
    val durationMs: Long get() = try { player?.duration?.toLong()?.coerceAtLeast(0) ?: 0L } catch (_: IllegalStateException) { 0L }

    fun play() {
        val p = player ?: return
        try {
            if (durationMs > 0 && positionMs >= durationMs - 30) p.seekTo(0)
            requestFocus()
            p.start()
        } catch (e: IllegalStateException) {
            failed = true
        }
    }

    fun pause() {
        try { player?.takeIf { it.isPlaying }?.pause() } catch (_: IllegalStateException) {}
        abandonFocus()
    }

    fun seekTo(ms: Long) {
        try { player?.seekTo(ms.coerceAtLeast(0), MediaPlayer.SEEK_CLOSEST) } catch (_: IllegalStateException) {}
    }

    /** Media volume is 0: nothing would be heard. */
    val mediaMuted: Boolean get() = try { audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0 } catch (_: Exception) { false }
    /** Ringer on silent/vibrate (media still plays, but people often expect silence). */
    val ringerSilent: Boolean get() = try { audio.ringerMode != AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) { false }

    fun release() = stopAndRelease()

    private fun stopAndRelease() {
        player?.let { p ->
            try { p.stop() } catch (_: IllegalStateException) {}
            p.release()
        }
        player = null
        abandonFocus()
    }

    private fun requestFocus() {
        if (focus != null) return
        val r = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener { change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    try { player?.takeIf { it.isPlaying }?.pause() } catch (_: IllegalStateException) {}
                }
            }
            .build()
        try { audio.requestAudioFocus(r); focus = r } catch (_: Exception) {}
    }

    private fun abandonFocus() {
        focus?.let { try { audio.abandonAudioFocusRequest(it) } catch (_: Exception) {} }
        focus = null
    }
}
