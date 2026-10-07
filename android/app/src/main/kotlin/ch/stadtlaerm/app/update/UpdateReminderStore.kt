package ch.stadtlaerm.app.update

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate

/** Remembers when «Später» was last tapped on the update reminder (local only). */
class UpdateReminderStore(context: Context) {
    private val prefs = context.getSharedPreferences("update_check", Context.MODE_PRIVATE)
    private val _dismissedOn = MutableStateFlow(read())
    val dismissedOn: StateFlow<LocalDate?> = _dismissedOn

    private fun read(): LocalDate? =
        if (prefs.contains(KEY)) UpdateCheck.dateOfEpochDay(prefs.getLong(KEY, 0L)) else null

    fun dismiss(today: LocalDate = LocalDate.now()) {
        prefs.edit().putLong(KEY, today.toEpochDay()).apply()
        _dismissedOn.value = today
    }

    private companion object {
        const val KEY = "reminder_dismissed_epoch_day"
    }
}
