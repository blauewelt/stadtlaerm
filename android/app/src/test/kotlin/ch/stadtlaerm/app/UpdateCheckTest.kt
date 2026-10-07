package ch.stadtlaerm.app

import ch.stadtlaerm.app.update.UpdateCheck
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckTest {
    private val build = LocalDate.of(2026, 10, 7)

    @Test
    fun remindsFromThirtyDaysOn() {
        assertFalse(UpdateCheck.shouldRemind(build, build, null))
        assertFalse(UpdateCheck.shouldRemind(build, build.plusDays(29), null))
        assertTrue(UpdateCheck.shouldRemind(build, build.plusDays(30), null))
        assertTrue(UpdateCheck.shouldRemind(build, build.plusDays(400), null))
        assertEquals(30, UpdateCheck.ageDays(build, build.plusDays(30)))
    }

    @Test
    fun laterHidesForFourteenDays() {
        val dismissed = build.plusDays(35)
        assertFalse(UpdateCheck.shouldRemind(build, dismissed, dismissed))
        assertFalse(UpdateCheck.shouldRemind(build, dismissed.plusDays(13), dismissed))
        assertTrue(UpdateCheck.shouldRemind(build, dismissed.plusDays(14), dismissed))
    }

    @Test
    fun clockGoingBackwardsDoesNotCrash() {
        // Phone date before the build date: negative age, no reminder.
        assertEquals(-10, UpdateCheck.ageDays(build, build.minusDays(10)))
        assertFalse(UpdateCheck.shouldRemind(build, build.minusDays(10), null))
        // Dismissed "in the future" (clock set back since): the snooze is ignored.
        val today = build.plusDays(40)
        assertTrue(UpdateCheck.shouldRemind(build, today, today.plusDays(3)))
        // Absurd stored values do not throw.
        assertNull(UpdateCheck.dateOfEpochDay(Long.MAX_VALUE))
        assertNull(UpdateCheck.dateOfEpochDay(null))
        assertEquals(build, UpdateCheck.dateOfEpochDay(build.toEpochDay()))
        assertTrue(UpdateCheck.shouldRemind(build, LocalDate.MAX, LocalDate.MIN))
        assertFalse(UpdateCheck.shouldRemind(build, LocalDate.MIN, LocalDate.MAX))
    }

    @Test
    fun malformedBuildDateNeverReminds() {
        assertNull(UpdateCheck.parseBuildDate("not a date"))
        assertNull(UpdateCheck.parseBuildDate(null))
        assertFalse(UpdateCheck.shouldRemind(null, build.plusDays(100), null))
        assertEquals("7.10.2026", UpdateCheck.displayBuildDate("2026-10-07"))
    }

    @Test
    fun buildConfigCarriesAnIsoBuildDate() {
        // The value depends on the build time; only its format is checked.
        assertNotNull(UpdateCheck.parseBuildDate(BuildConfig.BUILD_DATE))
    }

    @Test
    fun updateUrlCarriesVersionInFragment() {
        assertEquals("https://stadtlaerm.ch/update.html#v=0.3.1&c=5", UpdateCheck.updateUrl("0.3.1", 5))
        assertEquals("https://stadtlaerm.ch/update.html#v=0.3.1+x%26y&c=5", UpdateCheck.updateUrl("0.3.1 x&y", 5))
    }
}
