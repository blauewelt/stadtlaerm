package ch.stadtlaerm.app

import ch.stadtlaerm.app.audio.NoAudioTap
import ch.stadtlaerm.app.data.AppSettings
import ch.stadtlaerm.app.service.MeasurementNotification
import ch.stadtlaerm.app.service.TaskRemovedPolicy
import ch.stadtlaerm.app.service.TaskRemovedPolicy.Action
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Both editions: behaviour when the app is swiped away, and the notification texts. */
class ServicePolicyTest {

    @Test
    fun measurementKeepsRunningByDefaultWhenTheAppIsSwipedAway() {
        assertFalse(AppSettings().stopOnTaskRemoved)
        val default = AppSettings().stopOnTaskRemoved
        assertEquals(Action.KEEP_RUNNING, TaskRemovedPolicy.onTaskRemoved(default, running = true, starting = false))
        assertEquals(Action.KEEP_RUNNING, TaskRemovedPolicy.onTaskRemoved(default, running = false, starting = true))
    }

    @Test
    fun settingOnStopsARunningOrStartingMeasurement() {
        assertEquals(Action.STOP, TaskRemovedPolicy.onTaskRemoved(true, running = true, starting = false))
        assertEquals(Action.STOP, TaskRemovedPolicy.onTaskRemoved(true, running = false, starting = true))
        // Nothing to stop (e.g. already stopped from the notification).
        assertEquals(Action.KEEP_RUNNING, TaskRemovedPolicy.onTaskRemoved(true, running = false, starting = false))
    }

    @Test
    fun notificationTexts() {
        assertEquals("Mikrofon aktiv – Messung läuft", MeasurementNotification.TITLE)
        assertEquals("LAeq (1 min): 42,3 dB(A)", MeasurementNotification.text(42.26, calibrated = true, silenced = false))
        assertEquals("LAeq (1 min): 42,3 dB(A) · unkalibriert", MeasurementNotification.text(42.26, calibrated = false, silenced = false))
        assertEquals(MeasurementNotification.SILENCED, MeasurementNotification.text(42.0, calibrated = true, silenced = true))
        assertEquals(
            "LAeq (1 min): 42,3 dB(A) · Aufnahme läuft",
            MeasurementNotification.text(42.26, calibrated = true, silenced = false, note = "Aufnahme läuft"),
        )
    }

    @Test
    fun noAudioTapAddsNothingToTheNotification() {
        assertNull(NoAudioTap.notificationNote)
    }
}
