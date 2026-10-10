package ch.stadtlaerm.app.upload

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Where the sensor is (server/DESIGN.md §4.2). Only the hectare is kept, never coordinates. */
data class SiteSettings(
    /** LV95 hectare id, e.g. "h26824_12473" (dsp `Lv95.cellId`). */
    val cell: String,
    /** open_window | balcony | behind_glass | other */
    val placement: String = PLACEMENT_OPEN_WINDOW,
    /** 0 = ground floor; null = not given. */
    val floor: Int? = null,
    /** Microphone faces the street (true) or the courtyard (false); null = not given. */
    val streetFacing: Boolean? = null,
    /** Free text ≤ 200 characters, never published. */
    val note: String = "",
) {
    companion object {
        const val PLACEMENT_OPEN_WINDOW = "open_window"
        const val PLACEMENT_BALCONY = "balcony"
        const val PLACEMENT_BEHIND_GLASS = "behind_glass"
        const val PLACEMENT_OTHER = "other"
        val PLACEMENTS = listOf(PLACEMENT_OPEN_WINDOW, PLACEMENT_BALCONY, PLACEMENT_BEHIND_GLASS, PLACEMENT_OTHER)
        const val NOTE_MAX = 200
    }
}

/** A record the server refused for good (it would never be accepted); it is not sent again. */
data class RefusedRecord(val kind: String, val startEpochMs: Long, val reason: String) {
    companion object {
        const val MINUTE = "minute"
        const val EVENT = "event"
    }
}

/** Everything the upload remembers. Immutable; changed through [UploadStore.update]. */
data class UploadSnapshot(
    /** «Messwerte teilen» (off by default, DESIGN.md §2.3). */
    val enabled: Boolean = false,
    /** «nur über WLAN» (default on, DESIGN.md §4.6). */
    val wifiOnly: Boolean = true,
    /** The explanation screen was read and confirmed once. */
    val explanationSeen: Boolean = false,
    val site: SiteSettings? = null,
    val deviceId: String? = null,
    /** Bearer token; kept encrypted at rest (Android Keystore), plain only in memory. */
    val token: String? = null,
    /** Nothing that started before this is ever sent (set at opt-in and with «Neue Kennung»). */
    val sendFloorMs: Long = 0,
    /** Start (epoch ms) of the last minute the server acknowledged; the next upload sends newer ones. */
    val minutesSentUpToMs: Long = 0,
    /** Same for events. */
    val eventsSentUpToMs: Long = 0,
    /** The site JSON last accepted by the server; a different one is sent again. */
    val lastSiteJson: String? = null,
    /** Wall-clock time of the last upload that completed without error. */
    val lastSuccessAtMs: Long? = null,
    /** Short German description of the last problem, for the settings screen; null when fine. */
    val lastError: String? = null,
    /** The server did not accept the token (401): uploading stops until «Neue Kennung». */
    val authFailed: Boolean = false,
    /** Records the server refused for good (bounded list, newest last). */
    val refused: List<RefusedRecord> = emptyList(),
    /** How many records were refused in total (the list above is bounded). */
    val refusedTotal: Int = 0,
) {
    val registered: Boolean get() = deviceId != null && token != null
    val canEnable: Boolean get() = explanationSeen && site != null

    companion object {
        const val REFUSED_KEEP = 2000
    }
}

/** Storage of [UploadSnapshot]. Android: [UploadState]; tests: an in-memory one. */
interface UploadStore {
    val state: StateFlow<UploadSnapshot>
    fun update(transform: (UploadSnapshot) -> UploadSnapshot): UploadSnapshot
}

/** In-memory store (JVM tests, previews). */
class MemoryUploadStore(initial: UploadSnapshot = UploadSnapshot()) : UploadStore {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<UploadSnapshot> = flow

    @Synchronized
    override fun update(transform: (UploadSnapshot) -> UploadSnapshot): UploadSnapshot =
        transform(flow.value).also { flow.value = it }
}

/**
 * The upload's state on the phone: a private SharedPreferences file ("upload"), excluded from cloud
 * backup and device transfer like everything else (`data_extraction_rules.xml`,
 * `allowBackup=false`). The token is encrypted with an AES-GCM key that lives in the Android
 * Keystore and never leaves it; device id, markers and placement are not secret.
 */
class UploadState(context: Context) : UploadStore {
    private val prefs: SharedPreferences = context.getSharedPreferences("upload", Context.MODE_PRIVATE)
    private val flow = MutableStateFlow(read())
    override val state: StateFlow<UploadSnapshot> = flow

    @Synchronized
    override fun update(transform: (UploadSnapshot) -> UploadSnapshot): UploadSnapshot {
        val n = transform(flow.value)
        write(n)
        flow.value = n
        return n
    }

    private fun read(): UploadSnapshot {
        val site = prefs.getString("site_cell", null)?.let { cell ->
            SiteSettings(
                cell = cell,
                placement = prefs.getString("site_placement", null) ?: SiteSettings.PLACEMENT_OPEN_WINDOW,
                floor = if (prefs.contains("site_floor")) prefs.getInt("site_floor", 0) else null,
                streetFacing = if (prefs.contains("site_street")) prefs.getBoolean("site_street", true) else null,
                note = prefs.getString("site_note", null) ?: "",
            )
        }
        val token = prefs.getString("token_enc", null)?.let { TokenCipher.decrypt(it) }
        return UploadSnapshot(
            enabled = prefs.getBoolean("enabled", false),
            wifiOnly = prefs.getBoolean("wifi_only", true),
            explanationSeen = prefs.getBoolean("explanation_seen", false),
            site = site,
            deviceId = prefs.getString("device_id", null),
            // A token that cannot be decrypted (Keystore reset) is as good as none: «Neue Kennung».
            token = token,
            sendFloorMs = prefs.getLong("send_floor_ms", 0),
            minutesSentUpToMs = prefs.getLong("minutes_sent_ms", 0),
            eventsSentUpToMs = prefs.getLong("events_sent_ms", 0),
            lastSiteJson = prefs.getString("last_site_json", null),
            lastSuccessAtMs = if (prefs.contains("last_success_ms")) prefs.getLong("last_success_ms", 0) else null,
            lastError = prefs.getString("last_error", null),
            authFailed = prefs.getBoolean("auth_failed", false) ||
                (prefs.contains("token_enc") && token == null),
            refused = parseRefused(prefs.getString("refused", null)),
            refusedTotal = prefs.getInt("refused_total", 0),
        )
    }

    private fun write(s: UploadSnapshot) {
        val e = prefs.edit()
            .putBoolean("enabled", s.enabled)
            .putBoolean("wifi_only", s.wifiOnly)
            .putBoolean("explanation_seen", s.explanationSeen)
            .putLong("send_floor_ms", s.sendFloorMs)
            .putLong("minutes_sent_ms", s.minutesSentUpToMs)
            .putLong("events_sent_ms", s.eventsSentUpToMs)
            .putBoolean("auth_failed", s.authFailed)
            .putInt("refused_total", s.refusedTotal)
            .putString("refused", formatRefused(s.refused))
        s.site?.let { site ->
            e.putString("site_cell", site.cell).putString("site_placement", site.placement).putString("site_note", site.note)
            if (site.floor != null) e.putInt("site_floor", site.floor) else e.remove("site_floor")
            if (site.streetFacing != null) e.putBoolean("site_street", site.streetFacing) else e.remove("site_street")
        } ?: e.remove("site_cell").remove("site_placement").remove("site_note").remove("site_floor").remove("site_street")
        if (s.deviceId != null) e.putString("device_id", s.deviceId) else e.remove("device_id")
        val current = flow.value
        if (s.token == null) {
            e.remove("token_enc")
        } else if (s.token != current.token || !prefs.contains("token_enc")) {
            e.putString("token_enc", TokenCipher.encrypt(s.token))
        }
        if (s.lastSiteJson != null) e.putString("last_site_json", s.lastSiteJson) else e.remove("last_site_json")
        if (s.lastSuccessAtMs != null) e.putLong("last_success_ms", s.lastSuccessAtMs) else e.remove("last_success_ms")
        if (s.lastError != null) e.putString("last_error", s.lastError) else e.remove("last_error")
        // commit(): the markers must be on disk before the worker reports success.
        e.commit()
    }

    private fun formatRefused(list: List<RefusedRecord>): String = buildJsonArray {
        list.forEach { r -> add(buildJsonObject { put("k", r.kind); put("t", r.startEpochMs); put("r", r.reason) }) }
    }.toString()

    private fun parseRefused(s: String?): List<RefusedRecord> = try {
        if (s == null) emptyList() else Json.parseToJsonElement(s).jsonArray.map {
            val o = it.jsonObject
            RefusedRecord(o["k"]!!.jsonPrimitive.content, o["t"]!!.jsonPrimitive.long, o["r"]?.jsonPrimitive?.content ?: "")
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/** AES-256-GCM with a non-exportable key in the Android Keystore, for the one secret (the token). */
private object TokenCipher {
    private const val ALIAS = "stadtlaerm_upload_token"
    private const val KEYSTORE = "AndroidKeyStore"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val iv = c.iv
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(ct, Base64.NO_WRAP)
    }

    fun decrypt(stored: String): String? = try {
        val (ivB64, ctB64) = stored.split(":", limit = 2)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
        String(c.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
    } catch (_: Exception) {
        null
    }
}
