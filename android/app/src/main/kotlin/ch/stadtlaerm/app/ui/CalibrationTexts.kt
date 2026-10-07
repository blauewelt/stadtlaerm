package ch.stadtlaerm.app.ui

import ch.stadtlaerm.app.data.AppSettings
import ch.stadtlaerm.dsp.calibration.EventFloor
import java.util.Locale

/** German UI texts around calibration changes (pure Kotlin, unit-tested). */
object CalibrationTexts {
    private fun one(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
    private fun whole(v: Double): String = String.format(Locale.ROOT, "%.0f", v)

    /**
     * «Kalibrierung gespeichert. Offset +9.6 dB gegenüber vorher; der Mindestpegel für Ereignisse
     * wurde von 30.0 auf 39.5 dB(A) angepasst.» [prefix] is the first sentence.
     */
    fun floorFollowed(prefix: String, a: EventFloor.Adjustment): String {
        val offset = "Offset ${EventFloor.signed(a.deltaDb)} dB gegenüber vorher"
        val floor = when {
            !a.changed && a.clamped ->
                "der Mindestpegel für Ereignisse bleibt bei ${one(a.newFloorDb)} dB(A) (Grenze des Einstellbereichs " +
                    "${whole(AppSettings.EVENT_MIN_LEVEL_MIN)}–${whole(AppSettings.EVENT_MIN_LEVEL_MAX)} dB(A); rechnerisch ${one(a.unclampedFloorDb)} dB(A))"
            !a.changed -> "der Mindestpegel für Ereignisse bleibt bei ${one(a.newFloorDb)} dB(A)"
            a.clamped ->
                "der Mindestpegel für Ereignisse wurde von ${one(a.oldFloorDb)} auf ${one(a.newFloorDb)} dB(A) angepasst " +
                    "(begrenzt auf den Einstellbereich ${whole(AppSettings.EVENT_MIN_LEVEL_MIN)}–${whole(AppSettings.EVENT_MIN_LEVEL_MAX)} dB(A); " +
                    "rechnerisch ${one(a.unclampedFloorDb)} dB(A))"
            else -> "der Mindestpegel für Ereignisse wurde von ${one(a.oldFloorDb)} auf ${one(a.newFloorDb)} dB(A) angepasst"
        }
        return "$prefix $offset; $floor."
    }

    fun minutes(n: Int): String = if (n == 1) "1 Minute" else "$n Minuten"
    fun events(n: Int): String = if (n == 1) "1 Ereignis" else "$n Ereignisse"

    const val RECAL_QUESTION = "Frühere Messungen mit dieser Kalibrierung neu bewerten?"

    /** «Betrifft n Minuten und m Ereignisse mit derselben Audioquelle, …» */
    fun recalScope(minutes: Int, events: Int): String =
        "Betrifft ${minutes(minutes)} und ${events(events)} mit derselben Audioquelle, aufgenommen ohne oder mit einer " +
            "anderen Kalibrierung. Die Originalwerte bleiben gespeichert."

    fun recalDone(minutes: Int, events: Int, calibrationId: Long, offsetDb: Double): String =
        "${minutes(minutes)} und ${events(events)} mit Kalibrierung #$calibrationId (Offset ${String.format(Locale.ROOT, "%.2f", offsetDb)} dB) neu bewertet."
}
