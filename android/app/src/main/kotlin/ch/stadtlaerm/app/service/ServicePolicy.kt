package ch.stadtlaerm.app.service

import java.util.Locale

/**
 * What the measurement service does when the app's task is swiped away (Service.onTaskRemoved).
 * Pure logic, unit-tested on the JVM.
 */
object TaskRemovedPolicy {
    enum class Action {
        /** Default: the measurement continues as a foreground service; the notification shows it. */
        KEEP_RUNNING,
        /** «Messung beenden, wenn die App geschlossen wird»: stop exactly like «Stopp». */
        STOP,
    }

    /**
     * @param stopOnTaskRemoved the setting «Messung beenden, wenn die App geschlossen wird»
     * @param running a measurement is running
     * @param starting a measurement is starting (model loading); stopping cancels the start
     */
    fun onTaskRemoved(stopOnTaskRemoved: Boolean, running: Boolean, starting: Boolean): Action =
        if (stopOnTaskRemoved && (running || starting)) Action.STOP else Action.KEEP_RUNNING
}

/** Texts of the foreground notification shown while measuring. */
object MeasurementNotification {
    const val TITLE = "Mikrofon aktiv – Messung läuft"
    const val STARTING = "Messung startet …"
    const val NO_PERMISSION = "Mikrofon-Berechtigung fehlt"
    const val SILENCED = "Mikrofon vom System stummgeschaltet – Zeit wird nicht gewertet"

    /** Content text: the running 1-min LAeq, as before 0.3.3; [note] is appended (Labor build). */
    fun text(laeqDb: Double, calibrated: Boolean, silenced: Boolean, note: String? = null): String {
        val base = if (silenced) SILENCED else String.format(
            Locale.GERMANY, "LAeq (1 min): %.1f dB(A)%s", laeqDb, if (calibrated) "" else " · unkalibriert",
        )
        return if (note.isNullOrEmpty()) base else "$base · $note"
    }
}
