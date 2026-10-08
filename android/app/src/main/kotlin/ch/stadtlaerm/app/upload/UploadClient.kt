package ch.stadtlaerm.app.upload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.zip.GZIPOutputStream

/**
 * THE network code of the app. Nothing else in the public app opens a connection (PRIVACY.md →
 * «Messwerte teilen»; check with `grep -rn "HttpURLConnection\|Socket\|URL(" android/app/src/main`).
 *
 * One host ([baseUrl], from `BuildConfig.STADTLAERM_API`, default https://api.stadtlaerm.ch), the
 * five calls of server/DESIGN.md §4, JSON bodies always gzip-compressed, the device token as
 * `Authorization: Bearer …`. No redirects are followed (a redirect could point to another host),
 * no cookies, no cache, a fixed User-Agent without device details (Android's default one names
 * the phone model and build).
 *
 * Every method blocks; call it from a background thread (the upload worker, or a coroutine on
 * Dispatchers.IO).
 */
class UploadClient(
    baseUrl: String,
    private val userAgent: String = "Stadtlaerm",
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
) {
    private val base: String = baseUrl.trimEnd('/')

    init {
        val uri = URI(base)
        val loopback = uri.host == "127.0.0.1" || uri.host == "localhost" || uri.host == "[::1]"
        // HTTPS only; plain HTTP is accepted for a loopback test server (JVM tests) and nothing else.
        require(uri.scheme == "https" || (uri.scheme == "http" && loopback)) { "upload host must be https: $base" }
        require(uri.path.isNullOrEmpty() && uri.query == null) { "upload base URL must be scheme://host[:port]: $base" }
    }

    /** The host every request goes to (for the settings screen). */
    val host: String = URI(base).host

    data class Registration(val deviceId: String, val token: String)

    /** One upload's answer (server/README.md «The API in one screen»). */
    data class Result(val accepted: Int, val duplicates: Int, val rejected: List<Rejected>)

    /** A record the server refused for good: [index] into the batch that was sent, [reasons] as text. */
    data class Rejected(val index: Int, val start: String?, val reasons: String)

    /** Failures, split by what the caller should do about them. */
    sealed class Failure(message: String) : IOException(message) {
        /** 401: unknown device or wrong token (the server does not tell which). Do not retry. */
        class Unauthorized(message: String) : Failure(message)

        /** 429: retry later, at the earliest after [retryAfterS] seconds if the server said so. */
        class RateLimited(val retryAfterS: Long?, message: String) : Failure(message)

        /** Other 4xx (400, 413, 415, 422 …): the request itself is wrong; retrying will not help. */
        class Rejected(val status: Int, message: String) : Failure(message)

        /** 5xx, an unexpected status or an unreadable answer: retry later. */
        class Server(val status: Int, message: String) : Failure(message)
    }

    /** `POST /v1/devices` (§4.1). No token yet. */
    fun register(appVersion: String, appBuild: Int): Registration {
        val body = buildJsonObject { put("app_version", appVersion); put("app_build", appBuild) }
        val r = send("POST", "/v1/devices", token = null, body = body, expect = setOf(201, 200))
        val o = parseObject(r)
        val id = o["device_id"]?.jsonPrimitive?.content
        val token = o["token"]?.jsonPrimitive?.content
        if (id.isNullOrBlank() || token.isNullOrBlank()) throw Failure.Server(r.status, "registration answer without device_id/token")
        return Registration(id, token)
    }

    /** `PUT /v1/devices/{id}/site` (§4.2). */
    fun putSite(deviceId: String, token: String, site: JsonObject) {
        send("PUT", "/v1/devices/${seg(deviceId)}/site", token, site, expect = setOf(204, 200))
    }

    /** `POST /v1/devices/{id}/minutes` (§4.3), at most 1440 records. */
    fun postMinutes(deviceId: String, token: String, minutes: JsonArray): Result {
        require(minutes.size <= MAX_MINUTES) { "at most $MAX_MINUTES minutes per request" }
        return parseResult(send("POST", "/v1/devices/${seg(deviceId)}/minutes", token, minutes, expect = setOf(200)))
    }

    /** `POST /v1/devices/{id}/events` (§4.4), at most 2000 records. */
    fun postEvents(deviceId: String, token: String, events: JsonArray): Result {
        require(events.size <= MAX_EVENTS) { "at most $MAX_EVENTS events per request" }
        return parseResult(send("POST", "/v1/devices/${seg(deviceId)}/events", token, events, expect = setOf(200)))
    }

    /** `DELETE /v1/devices/{id}` (§4.5). Returns only after the server answered 204 (rows gone). */
    fun deleteDevice(deviceId: String, token: String) {
        send("DELETE", "/v1/devices/${seg(deviceId)}", token, body = null, expect = setOf(204, 200))
    }

    // ---- HTTP ------------------------------------------------------------------------------

    private class Response(val status: Int, val body: String, val retryAfter: String?)

    private fun seg(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun send(method: String, path: String, token: String?, body: JsonElement?, expect: Set<Int>): Response {
        val conn = URI(base + path).toURL().openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("User-Agent", userAgent)
            conn.setRequestProperty("Accept", "application/json")
            if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                val bytes = gzip(body.toString().toByteArray(Charsets.UTF_8))
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Content-Encoding", "gzip")
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val status = conn.responseCode
            val stream = if (status >= 400) conn.errorStream else conn.inputStream
            val text = stream?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
            val r = Response(status, text, conn.getHeaderField("Retry-After"))
            if (status in expect) return r
            val detail = detail(text)
            throw when {
                status == 401 -> Failure.Unauthorized("401 $detail")
                status == 429 -> Failure.RateLimited(r.retryAfter?.trim()?.toLongOrNull(), "429 $detail")
                status in 400..499 -> Failure.Rejected(status, "$status $detail")
                else -> Failure.Server(status, "$status $detail")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun gzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size / 4 + 64)
        GZIPOutputStream(out).use { it.write(data) }
        return out.toByteArray()
    }

    /** The server's `{"detail": …}`, shortened, for the settings screen. Never contains our data. */
    private fun detail(text: String): String {
        val d = try {
            Json.parseToJsonElement(text).jsonObject["detail"]?.toString()
        } catch (_: Exception) {
            null
        }
        return (d ?: text).take(200)
    }

    private fun parseObject(r: Response): JsonObject = try {
        Json.parseToJsonElement(r.body).jsonObject
    } catch (e: Exception) {
        throw Failure.Server(r.status, "answer is not a JSON object")
    }

    private fun parseResult(r: Response): Result {
        val o = parseObject(r)
        return try {
            val rejected = (o["rejected"] as? JsonArray ?: JsonArray(emptyList())).map { el ->
                val x = el.jsonObject
                Rejected(
                    index = x["index"]!!.jsonPrimitive.int,
                    start = x["start"]?.let { if (it is JsonNull) null else it.jsonPrimitive.content },
                    reasons = x["reasons"]?.toString()?.take(300) ?: "",
                )
            }
            Result(
                accepted = o["accepted"]?.jsonPrimitive?.intOrNull ?: 0,
                duplicates = o["duplicates"]?.jsonPrimitive?.intOrNull ?: 0,
                rejected = rejected,
            )
        } catch (e: Exception) {
            throw Failure.Server(r.status, "unexpected upload answer")
        }
    }

    companion object {
        const val MAX_MINUTES = 1440
        const val MAX_EVENTS = 2000
    }
}
