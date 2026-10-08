package ch.stadtlaerm.app.update

import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Update check without network access. The app never contacts a server for it: «Nach Update suchen»
 * opens stadtlaerm.ch/update.html in the browser with the installed version in the URL fragment
 * (which the browser does not send to the server), and the page compares it locally.
 *
 * The age reminder is fully offline too: it only compares the build date baked into the APK
 * with the phone's calendar date. Pure Kotlin, no Android dependencies, so it is unit-tested
 * on the JVM.
 */
object UpdateCheck {
    const val PAGE = "https://stadtlaerm.ch/update.html"

    /** Show the reminder once the installed build is at least this many days old. */
    const val REMINDER_AGE_DAYS = 30L

    /** «Später» hides the reminder for this many days. */
    const val SNOOZE_DAYS = 14L

    private val displayDate: DateTimeFormatter = DateTimeFormatter.ofPattern("d.M.yyyy")

    /** `https://stadtlaerm.ch/update.html#v=<versionName>&c=<versionCode>`. */
    fun updateUrl(versionName: String, versionCode: Int): String =
        "$PAGE#v=${URLEncoder.encode(versionName, "UTF-8")}&c=$versionCode"

    /** Parses the ISO date from BuildConfig.BUILD_DATE; null if it is malformed. */
    fun parseBuildDate(iso: String?): LocalDate? =
        iso?.let { runCatching { LocalDate.parse(it.trim()) }.getOrNull() }

    /** «7.10.2026», or the raw string if it cannot be parsed. */
    fun displayBuildDate(iso: String): String = parseBuildDate(iso)?.format(displayDate) ?: iso

    /** Whole days from [buildDate] to [today]; negative if the clock is behind the build date. */
    fun ageDays(buildDate: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(buildDate, today)

    /**
     * Whether to show «Diese App-Version ist n Tage alt». [dismissedOn] is the day «Später» was
     * last tapped (null if never).
     *
     * - Build less than [REMINDER_AGE_DAYS] old (or in the future, clock behind): hide.
     * - Dismissed fewer than [SNOOZE_DAYS] days ago: hide.
     * - Dismissal dated in the future (clock went backwards since): the snooze can no longer be
     *   trusted and would otherwise last arbitrarily long, so it is ignored and the reminder shows;
     *   «Später» then stores the current date again.
     */
    fun shouldRemind(buildDate: LocalDate?, today: LocalDate, dismissedOn: LocalDate?): Boolean {
        if (buildDate == null) return false
        if (ageDays(buildDate, today) < REMINDER_AGE_DAYS) return false
        if (dismissedOn != null) {
            val sinceDismissed = ChronoUnit.DAYS.between(dismissedOn, today)
            if (sinceDismissed in 0 until SNOOZE_DAYS) return false
        }
        return true
    }

    /** Converts a stored epoch day (SharedPreferences) back to a date; null for "never". */
    fun dateOfEpochDay(epochDay: Long?): LocalDate? =
        epochDay?.let { runCatching { LocalDate.ofEpochDay(it) }.getOrNull() }
}
