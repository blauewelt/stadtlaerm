package ch.stadtlaerm.app.edition

/**
 * Names and texts of the three editions (BuildConfig.EDITION). Pure Kotlin in app/src/main, so
 * every flavour compiles the same texts and the JVM tests check them. Which edition a build is
 * comes from the Gradle flavour (app/build.gradle.kts):
 *
 * - [OFFLINE] «Offline-Version» (flavour `offline`, ch.stadtlaerm.app): no internet permission;
 *   the default download, GitHub release asset stadtlaerm.apk.
 * - [KARTE] «Karten-Version» (flavour `public`, ch.stadtlaerm.app): internet only for the opt-in
 *   «Messwerte teilen»; GitHub release asset stadtlaerm-karte.apk.
 * - [LABOR] «Labor-Version» (flavour `labor`, ch.stadtlaerm.labor): never published.
 *
 * Offline and Karten-Version share the app id and the signing key: installing one over the other
 * switches the edition and keeps the measurements.
 */
object EditionInfo {
    const val OFFLINE = "offline"
    const val KARTE = "karte"
    const val LABOR = "labor"

    /** «Offline-Version», «Karten-Version», «Labor-Version». */
    fun label(edition: String): String = when (edition) {
        OFFLINE -> "Offline-Version"
        KARTE -> "Karten-Version"
        LABOR -> "Labor-Version"
        else -> edition
    }

    /** Line in Einstellungen → App-Version: which edition is installed. */
    fun summary(edition: String): String = when (edition) {
        OFFLINE -> "Offline-Version: ohne Internet-Berechtigung."
        KARTE -> "Karten-Version: mit «Messwerte teilen» für die Lärmkarte (aus, bis du es einschaltest)."
        LABOR -> "Labor-Version: kann Audio aufzeichnen, wird nicht veröffentlicht."
        else -> edition
    }

    /** How to switch to the other published edition; null for Labor (it is a separate app). */
    fun switchHint(edition: String): String? = when (edition) {
        OFFLINE ->
            "Wer Messwerte für die Lärmkarte teilen möchte, installiert die Karten-Version von stadtlaerm.ch über diese App. Die Messungen bleiben erhalten."
        KARTE ->
            "Zur Offline-Version wechselst du, indem du sie von stadtlaerm.ch über diese App installierst. Die Messungen bleiben erhalten, " +
                "«Messwerte teilen» wird ausgeschaltet. Willst du deine Daten auf dem Server löschen, tu das vorher unter «Messwerte teilen»: " +
                "In der Offline-Version geht das nicht."
        else -> null
    }
}
