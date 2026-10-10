package ch.stadtlaerm.app

import ch.stadtlaerm.app.edition.Edition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Runs in every edition (offline, karte, labor): the build config and the edition object agree. */
class EditionTest {
    @Test
    fun buildConfigAndEditionAgree() {
        assertTrue(BuildConfig.EDITION in setOf("offline", "karte", "labor"), BuildConfig.EDITION)
        assertEquals(BuildConfig.EDITION == "karte", BuildConfig.UPLOAD_AVAILABLE)
        assertEquals(BuildConfig.UPLOAD_AVAILABLE, Edition.uploadAvailable)
        assertEquals(Edition.uploadAvailable, Edition.sharingPage != null)
        when (BuildConfig.EDITION) {
            "offline" -> assertEquals("offline", Edition.versionLabel)
            "karte" -> assertEquals("mit Lärmkarte", Edition.versionLabel)
            "labor" -> assertEquals(null, Edition.versionLabel)
        }
    }

    @Test
    fun onlyTheKarteEditionMentionsTheServer() {
        assertEquals(BuildConfig.EDITION == "karte", "api.stadtlaerm.ch" in Edition.networkPrivacyLines)
    }
}
