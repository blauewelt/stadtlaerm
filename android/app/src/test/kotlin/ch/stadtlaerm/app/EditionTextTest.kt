package ch.stadtlaerm.app

import ch.stadtlaerm.app.edition.EditionUi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The audio bullet of «Über Stadtlärm & Datenschutz» must tell the truth about each edition. */
class EditionTextTest {
    @Test
    fun aboutAudioMatchesTheEdition() {
        val text = EditionUi.ABOUT_AUDIO
        if (BuildConfig.FLAVOR == "labor") {
            // Labor can store clips and hour files locally; it has no internet permission.
            assertTrue(text.contains("Labor-Version"), text)
            assertTrue(text.contains("keine Internet-Berechtigung"), text)
            assertFalse(text.contains("verlässt nie den Arbeitsspeicher"), text)
        } else {
            assertEquals(
                "• Audio verlässt nie den Arbeitsspeicher: höchstens ca. 1 s wird für die Erkennung gepuffert, nichts wird gespeichert, protokolliert oder gesendet.",
                text,
            )
        }
    }
}
