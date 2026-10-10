package ch.stadtlaerm.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * «Stadtlärm offline»: the upload code is not on the classpath at all (it lives in src/karte/).
 * The APK itself is checked with aapt2/unzip at release time (README.md, PRIVACY.md).
 */
class OfflineEditionTest {
    @Test
    fun noUploadClassesInTheOfflineEdition() {
        for (name in listOf(
            "ch.stadtlaerm.app.upload.UploadClient",
            "ch.stadtlaerm.app.upload.Uploader",
            "ch.stadtlaerm.app.upload.UploadWorker",
            "ch.stadtlaerm.app.ui.ShareScreenKt",
        )) {
            assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) }
        }
    }

    @Test
    fun buildConfigHasNoServerHost() {
        assertEquals("offline", BuildConfig.EDITION)
        assertFalse(BuildConfig.UPLOAD_AVAILABLE)
        val fields = BuildConfig::class.java.declaredFields.map { it.name }
        assertFalse("STADTLAERM_API" in fields, fields.toString())
    }
}
