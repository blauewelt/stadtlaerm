package ch.stadtlaerm.app.labor

import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// LABOR BUILD ONLY (app/src/labor/).

/**
 * The storage cap: recording stops (measuring goes on) once the audio folder would exceed
 * [maxBytes]. Latching: once full, it stays full for the rest of the session. Thread-safe.
 */
class StorageBudget(val maxBytes: Long, initialUsedBytes: Long) {
    var usedBytes: Long = initialUsedBytes
        @Synchronized get
        private set
    @Volatile var full: Boolean = initialUsedBytes >= maxBytes
        private set

    /** Reserves [bytes] before writing them; false (and full from now on) if they do not fit. */
    @Synchronized
    fun tryReserve(bytes: Long): Boolean {
        if (full) return false
        if (usedBytes + bytes > maxBytes) { full = true; return false }
        usedBytes += bytes
        return true
    }

    /** Accounts for [bytes] already written (encoder output); false if that crossed the cap. */
    @Synchronized
    fun account(bytes: Long): Boolean {
        usedBytes += bytes
        if (usedBytes > maxBytes) full = true
        return !full
    }

    companion object {
        const val GB = 1_000_000_000L
        val CHOICES_GB = listOf(0.5, 1.0, 2.0, 5.0, 10.0)
        const val DEFAULT_GB = 2.0
    }
}

/** «Clip-Rate»: keep a clip for every [every]-th confirmed event (the 1st, then every n-th). */
class ClipRateSampler(val every: Int) {
    init { require(every >= 1) }
    var seen = 0L
        private set
    var skipped = 0L
        private set

    fun next(): Boolean {
        val keep = seen % every == 0L
        seen++
        if (!keep) skipped++
        return keep
    }

    companion object {
        val CHOICES = listOf(1, 2, 5)
    }
}

/**
 * Hourly file rotation for the continuous recording: a new file at every local wall-clock hour
 * (and at measurement start). Hours are keyed with their UTC offset, so the repeated hour at the
 * end of daylight saving time gets a file of its own.
 */
class HourRoller(private val zone: ZoneId) {
    private var currentKey: OffsetDateTime? = null

    fun hourKey(epochMs: Long): OffsetDateTime =
        Instant.ofEpochMilli(epochMs).atZone(zone).truncatedTo(ChronoUnit.HOURS).toOffsetDateTime()

    /** True if a block delivered at [epochMs] belongs into a new file (first call: always). */
    fun needsNewFile(epochMs: Long): Boolean {
        val k = hourKey(epochMs)
        if (k == currentKey) return false
        currentKey = k
        return true
    }

    /** Start of the next hour after [epochMs], epoch ms. */
    fun nextBoundaryMs(epochMs: Long): Long =
        Instant.ofEpochMilli(epochMs).atZone(zone).truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()

    fun baseName(epochMs: Long): String = Instant.ofEpochMilli(epochMs).atZone(zone).format(HOUR_NAME)

    companion object {
        val HOUR_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HH")
        val SECOND_NAME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /** `base.ext`, or `base_2.ext`, `base_3.ext` … if taken. */
        fun unique(base: String, ext: String, exists: (String) -> Boolean): String {
            var name = "$base.$ext"
            var i = 2
            while (exists(name)) { name = "${base}_$i.$ext"; i++ }
            return name
        }
    }
}
