package ch.stadtlaerm.app.upload

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.concurrent.thread

/**
 * A small stand-in for the contribution server (server/README.md «The API in one screen»): a
 * minimal HTTP/1.1 server on a plain `ServerSocket` bound to 127.0.0.1 (Android unit tests compile
 * against android.jar, which has no `com.sun.net.httpserver`; no extra test dependency needed).
 * It keeps devices, sites, minutes and events in memory with the server's semantics (idempotent
 * on start, `rejected` per record, 401 for an unknown device or wrong token, 204 on delete) and
 * lets a test inject failures.
 */
class FakeServer : AutoCloseable {
    data class Request(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: JsonElement?,
        val rawSize: Int,
    )

    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${socket.localPort}"

    val requests: MutableList<Request> = java.util.Collections.synchronizedList(mutableListOf())
    val tokens = java.util.concurrent.ConcurrentHashMap<String, String>()
    val sites = java.util.concurrent.ConcurrentHashMap<String, JsonObject>()
    val minutes = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, JsonObject>>()
    val events = java.util.concurrent.ConcurrentHashMap<String, MutableMap<String, JsonObject>>()

    /** Return this status for the next request matching (method, path suffix) once, e.g. "POST" to "/minutes". */
    val failNext: MutableList<Triple<String, String, Int>> = java.util.Collections.synchronizedList(mutableListOf())

    /** Records (by start) the server refuses for good. */
    val refuseStarts: MutableSet<String> = java.util.Collections.synchronizedSet(mutableSetOf())

    init {
        thread(isDaemon = true, name = "fake-server") {
            while (true) {
                val client = try { socket.accept() } catch (_: SocketException) { break }
                thread(isDaemon = true) { client.use { serve(it) } }
            }
        }
    }

    override fun close() = socket.close()

    /** One exchange per connection (every reply says `Connection: close`). */
    private class Exchange(val method: String, val path: String, val headers: Map<String, String>, val raw: ByteArray, val socket: Socket)

    private fun readLine(input: InputStream): String {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) break
            if (c != '\r'.code) b.write(c)
        }
        return String(b.toByteArray(), Charsets.ISO_8859_1)
    }

    private fun serve(sock: Socket) {
        val input = BufferedInputStream(sock.getInputStream())
        val requestLine = readLine(input)
        if (requestLine.isEmpty()) return
        val parts = requestLine.split(" ")
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        val length = headers["content-length"]?.toInt() ?: 0
        val raw = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(raw, read, length - read)
            if (n < 0) break
            read += n
        }
        handle(Exchange(parts[0], parts[1].substringBefore('?'), headers, raw, sock))
    }

    private fun handle(ex: Exchange) {
        try {
            val raw = ex.raw
            val gz = ex.headers["content-encoding"] == "gzip"
            val text = if (raw.isEmpty()) "" else String(if (gz) GZIPInputStream(raw.inputStream()).readBytes() else raw, Charsets.UTF_8)
            val body = if (text.isEmpty()) null else Json.parseToJsonElement(text)
            val path = ex.path
            val headers = ex.headers
            requests += Request(ex.method, path, headers, body, raw.size)

            val inject = synchronized(failNext) {
                failNext.firstOrNull { ex.method == it.first && path.endsWith(it.second) }?.also { failNext.remove(it) }
            }
            if (inject != null) return reply(ex, inject.third, """{"detail":"injected"}""")

            if (ex.method == "POST" && path == "/v1/devices") {
                val id = UUID.randomUUID().toString()
                val token = UUID.randomUUID().toString().replace("-", "")
                tokens[id] = token
                return reply(ex, 201, buildJsonObject { put("device_id", id); put("token", token) }.toString())
            }
            val m = Regex("^/v1/devices/([^/]+)(/site|/minutes|/events)?$").matchEntire(path)
                ?: return reply(ex, 404, """{"detail":"not found"}""")
            val id = m.groupValues[1]
            val auth = headers["authorization"] ?: ""
            if (tokens[id] == null || auth != "Bearer ${tokens[id]}") return reply(ex, 401, """{"detail":"invalid device or token"}""")
            when (ex.method to m.groupValues[2]) {
                "PUT" to "/site" -> { sites[id] = body!!.jsonObject; reply(ex, 204, null) }
                "POST" to "/minutes" -> reply(ex, 200, upsert(minutes.getOrPut(id) { mutableMapOf() }, body!!.jsonArray, 1440))
                "POST" to "/events" -> reply(ex, 200, upsert(events.getOrPut(id) { mutableMapOf() }, body!!.jsonArray, 2000))
                "DELETE" to "" -> {
                    tokens.remove(id); sites.remove(id); minutes.remove(id); events.remove(id)
                    reply(ex, 204, null)
                }
                else -> reply(ex, 405, """{"detail":"method"}""")
            }
        } catch (e: Exception) {
            reply(ex, 500, """{"detail":"${e.javaClass.simpleName}"}""")
        }
    }

    private fun upsert(into: MutableMap<String, JsonObject>, arr: JsonArray, limit: Int): String {
        if (arr.size > limit) return """{"detail":"at most $limit records per request"}"""
        var accepted = 0
        var duplicates = 0
        val rejected = buildJsonArray {
            arr.forEachIndexed { i, el ->
                val o = el.jsonObject
                val start = o["start"]!!.jsonPrimitive.content
                if (start in refuseStarts) {
                    add(buildJsonObject {
                        put("index", i); put("start", start)
                        put("reasons", buildJsonArray { add(buildJsonObject { put("field", "laeq_db"); put("reason", "out of range") }) })
                    })
                } else {
                    if (into.put(start, o) == null) accepted++ else duplicates++
                }
            }
        }
        return buildJsonObject { put("accepted", accepted); put("duplicates", duplicates); put("rejected", rejected) }.toString()
    }

    private fun reply(ex: Exchange, status: Int, body: String?) {
        val bytes = body?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val head = buildString {
            append("HTTP/1.1 $status X\r\n")
            append("Connection: close\r\n")
            if (status == 429) append("Retry-After: 120\r\n")
            if (status != 204) {
                append("Content-Type: application/json\r\n")
                append("Content-Length: ${bytes.size}\r\n")
            }
            append("\r\n")
        }
        val out = ex.socket.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        if (status != 204) out.write(bytes)
        out.flush()
    }

    fun count(method: String, suffix: String) = synchronized(requests) { requests.count { it.method == method && it.path.endsWith(suffix) } }
}
