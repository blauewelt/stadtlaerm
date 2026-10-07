package ch.stadtlaerm.app.labor

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// LABOR BUILD ONLY (app/src/labor/). See PRIVACY.md → «Labor-Build».

data class LaborSettings(
    /** «Audio während der Messung aufzeichnen» (off by default). */
    val recordAudio: Boolean = false,
    /** «Ereignis-Clips (±5 s, WAV 16 kHz)». */
    val clips: Boolean = true,
    /** «Durchgehend (AAC 64 kbit/s, Stundendateien)». */
    val continuous: Boolean = true,
    /** «Clip-Rate»: every n-th event (1, 2, 5). */
    val clipEvery: Int = 1,
    /** «Maximaler Speicher» in GB. */
    val maxGb: Double = StorageBudget.DEFAULT_GB,
) {
    val maxBytes: Long get() = (maxGb * StorageBudget.GB).toLong()
}

/** What the recorder is doing right now (for the banner and the settings section). */
data class RecorderStatus(
    val recording: Boolean = false,
    val clips: Boolean = false,
    val continuous: Boolean = false,
    /** Recording stopped because «Maximaler Speicher» was reached (measuring goes on). */
    val storageFull: Boolean = false,
    val clipsThisSession: Long = 0,
    val droppedThisSession: Long = 0,
)

/** Labor singletons (settings, status, files). */
object Labor {
    private var prefs: SharedPreferences? = null
    private val _settings = MutableStateFlow(LaborSettings())
    val settings: StateFlow<LaborSettings> = _settings
    val status = MutableStateFlow(RecorderStatus())
    /** Session id of the newest recorder (older ones may still be finishing their files). */
    @Volatile var currentSession: String? = null
    /** Event ids that have a clip on disk (for the marker in «Letzte Ereignisse»). */
    val clipEventIds = MutableStateFlow<Set<Long>>(emptySet())
    /** Bumped whenever files were written or deleted (refreshes the storage figures). */
    val filesVersion = MutableStateFlow(0L)

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences("labor", Context.MODE_PRIVATE)
        prefs = p
        _settings.value = LaborSettings(
            recordAudio = p.getBoolean("record_audio", false),
            clips = p.getBoolean("clips", true),
            continuous = p.getBoolean("continuous", true),
            clipEvery = p.getInt("clip_every", 1).takeIf { it in ClipRateSampler.CHOICES } ?: 1,
            maxGb = p.getFloat("max_gb", StorageBudget.DEFAULT_GB.toFloat()).toDouble(),
        )
        Thread({ refreshClipIds(dirs(context)) }, "stadtlaerm-labor-scan").start()
    }

    fun update(context: Context, transform: (LaborSettings) -> LaborSettings) {
        init(context)
        val n = transform(_settings.value)
        prefs!!.edit()
            .putBoolean("record_audio", n.recordAudio)
            .putBoolean("clips", n.clips)
            .putBoolean("continuous", n.continuous)
            .putInt("clip_every", n.clipEvery)
            .putFloat("max_gb", n.maxGb.toFloat())
            .apply()
        _settings.value = n
    }

    fun dirs(context: Context): LaborDirs {
        val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "external-fallback")
        return LaborDirs(File(base, "audio"))
    }

    private val CLIP_ID = Regex("""^ev_(\d+)_\d{8}_\d{6}(_\d+)?\.wav$""")

    fun refreshClipIds(d: LaborDirs) {
        clipEventIds.value = d.clips.listFiles()?.mapNotNull { CLIP_ID.find(it.name)?.groupValues?.get(1)?.toLongOrNull() }?.toSet()
            ?: emptySet()
    }

    fun clipWritten(eventId: Long?) {
        if (eventId != null) clipEventIds.update { it + eventId }
        filesVersion.update { it + 1 }
    }
}

/**
 * Where the Labor build puts its recordings: getExternalFilesDir(null)/audio/
 * {clips/, continuous/, classifier/, manifest.jsonl}.
 */
class LaborDirs(val root: File) {
    val clips = File(root, "clips")
    val continuous = File(root, "continuous")
    val classifier = File(root, "classifier")
    val manifest = File(root, "manifest.jsonl")

    fun ensure() { clips.mkdirs(); continuous.mkdirs(); classifier.mkdirs() }

    data class Stats(
        val usedBytes: Long,
        val clipCount: Int,
        val clipBytes: Long,
        val hourFiles: Int,
        val continuousBytes: Long,
        val manifestBytes: Long,
        val classifierFiles: Int = 0,
        val classifierBytes: Long = 0,
    ) {
        /** What «Als ZIP teilen» packs besides the hour files. */
        val textAndClipBytes: Long get() = clipBytes + manifestBytes + classifierBytes
    }

    private fun files(dir: File, ext: String): List<File> = dir.listFiles()?.filter { it.isFile && it.name.endsWith(ext) } ?: emptyList()

    fun stats(): Stats {
        val c = files(clips, ".wav")
        val h = files(continuous, ".m4a")
        val k = files(classifier, ".jsonl")
        return Stats(
            usedBytes = usedBytes(),
            clipCount = c.size, clipBytes = c.sumOf { it.length() },
            hourFiles = h.size, continuousBytes = h.sumOf { it.length() },
            manifestBytes = if (manifest.isFile) manifest.length() else 0,
            classifierFiles = k.size, classifierBytes = k.sumOf { it.length() },
        )
    }

    fun usedBytes(): Long = if (root.exists()) root.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    /** Deletes every recording and the manifest. */
    fun deleteAll() {
        if (root.exists()) root.walkBottomUp().forEach { if (it != root) it.delete() }
    }

    /**
     * Zips the manifest, the clips and the classifier logs (and the hourly files if
     * [withContinuous]) into [zip]. Entries keep their folder (clips/…, classifier/…, continuous/…).
     */
    fun zipTo(zip: File, withContinuous: Boolean) {
        zip.parentFile?.mkdirs()
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            fun add(f: File, name: String) {
                z.putNextEntry(ZipEntry(name).apply { time = f.lastModified() })
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
            if (manifest.isFile) add(manifest, "manifest.jsonl")
            files(clips, ".wav").sortedBy { it.name }.forEach { add(it, "clips/${it.name}") }
            files(classifier, ".jsonl").sortedBy { it.name }.forEach { add(it, "classifier/${it.name}") }
            if (withContinuous) files(continuous, ".m4a").sortedBy { it.name }.forEach { add(it, "continuous/${it.name}") }
        }
    }

    companion object {
        /** Hourly files go into the ZIP only if they total less than this. */
        const val ZIP_CONTINUOUS_LIMIT = 500L * 1_000_000L
    }
}
