package ch.stadtlaerm.app.upload

import ch.stadtlaerm.app.data.EventEntity
import ch.stadtlaerm.app.data.MinuteEntity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Read access to the local records the upload sends (Android: Room; tests: in memory). */
interface UploadSource {
    /** Minutes with `startEpochMs > afterMs`, oldest first, at most [limit]. */
    suspend fun minutesAfter(afterMs: Long, limit: Int): List<MinuteEntity>

    /** Events with `startEpochMs > afterMs`, oldest first, at most [limit]. */
    suspend fun eventsAfter(afterMs: Long, limit: Int): List<EventEntity>

    /** Phone model, audio source and calibration for the site call. */
    suspend fun siteExtras(): SiteExtras
}

/**
 * The upload of server/DESIGN.md §4.6, independent of Android so that it runs in JVM tests:
 * register once, send the site when it changed, then every minute and event newer than the last
 * acknowledged `start`, in batches of ≤ 1440 / ≤ 2000. The local database stays the source of
 * truth; the markers only move forward after the server acknowledged a batch, so an interrupted
 * upload resumes where it stopped and a repeated batch is harmless (the server is idempotent on
 * `(device_id, start)`).
 *
 * Records the server lists in `rejected` are kept in [UploadSnapshot.refused] and never sent again.
 */
class Uploader(
    private val client: UploadClient,
    private val store: UploadStore,
    private val source: UploadSource,
    private val appVersion: String,
    private val appBuild: Int,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Requests per run; the server allows 60 per device and hour (DESIGN.md §8). */
    private val maxRequestsPerRun: Int = 24,
    private val minuteBatch: Int = UploadClient.MAX_MINUTES,
    private val eventBatch: Int = UploadClient.MAX_EVENTS,
) {
    /** Serialises uploads, deletes and «Neue Kennung» within the process. */
    private val lock = Mutex()

    /** Set by a delete waiting for the lock: a running upload stops before its next request. */
    private val abort = AtomicBoolean(false)

    private fun stopRequested(): Boolean = abort.get() || !store.state.value.enabled

    sealed interface Outcome {
        /** Sharing is off (or was switched off during the run): nothing sent. */
        data object Disabled : Outcome

        /** No hectare chosen yet. */
        data object NotReady : Outcome

        /** The server does not accept the token; stops until «Neue Kennung». */
        data object AuthFailed : Outcome

        /** Everything newer than the markers was sent (or the request budget of this run is used up: [more]). */
        data class Done(val minutes: Int, val events: Int, val refused: Int, val more: Boolean) : Outcome

        /** Temporary problem (network, server, rate limit): try again later. */
        data class Retry(val message: String) : Outcome

        /** The server refused a whole request (4xx): retrying the same request will not help. */
        data class Failed(val message: String) : Outcome
    }

    sealed interface DeleteOutcome {
        /** The server confirmed (204) that every row of this device is gone, or there never was a device. */
        data object Deleted : DeleteOutcome

        /** The server did not know this device/token (401): nothing it holds can be reached with it. */
        data object UnknownToServer : DeleteOutcome

        /** No answer / an error: nothing was changed on the phone. */
        data class Failed(val message: String) : DeleteOutcome
    }

    /** Switch «Messwerte teilen» on. Needs the explanation and a hectare. */
    fun enable(): Boolean {
        val s = store.state.value
        if (!s.canEnable) return false
        val now = clock()
        store.update {
            // The very first time, the last 7 days are included (the explanation says so); after
            // that, nothing measured while sharing was off is ever sent.
            val floor = if (it.sendFloorMs == 0L && !it.registered) now - MAX_AGE_MS else now
            it.copy(enabled = true, sendFloorMs = maxOf(it.sendFloorMs, floor), lastError = null)
        }
        return true
    }

    /** Switch it off: no further request is started (a running one finishes its current call). */
    fun disable() {
        store.update { it.copy(enabled = false) }
    }

    /**
     * After «Alte Messungen neu bewerten» the stored levels changed: send the last 7 days again
     * (the server replaces records with the same start, DESIGN.md §4.3).
     */
    fun rewindAfterReevaluation() {
        store.update { it.copy(minutesSentUpToMs = 0, eventsSentUpToMs = 0) }
    }

    /** Oldest start that is still sent: the opt-in floor, and nothing the server would refuse as too old. */
    fun effectiveFloorMs(s: UploadSnapshot = store.state.value): Long =
        maxOf(s.sendFloorMs, clock() - MAX_AGE_MS + AGE_MARGIN_MS)

    /** `afterMs` for counting the minutes still to send (settings screen). */
    fun minutesPendingAfterMs(s: UploadSnapshot = store.state.value): Long =
        maxOf(s.minutesSentUpToMs, effectiveFloorMs(s) - 1)

    suspend fun run(): Outcome = lock.withLock { runLocked() }

    private suspend fun runLocked(): Outcome {
        var s = store.state.value
        if (!s.enabled) return Outcome.Disabled
        val site = s.site ?: return Outcome.NotReady
        if (s.authFailed) return Outcome.AuthFailed
        var requests = 0
        var sentMinutes = 0
        var sentEvents = 0
        var refused = 0
        try {
            if (!s.registered) {
                val reg = client.register(appVersion, appBuild)
                requests++
                s = store.update { it.copy(deviceId = reg.deviceId, token = reg.token, lastSiteJson = null, authFailed = false) }
            }
            val id = s.deviceId!!
            val token = s.token!!

            val siteJson = Payloads.site(site, source.siteExtras())
            val siteText = siteJson.toString()
            if (siteText != s.lastSiteJson) {
                if (stopRequested()) return Outcome.Disabled
                client.putSite(id, token, siteJson)
                requests++
                s = store.update { it.copy(lastSiteJson = siteText) }
            }

            var more = false
            val minutes = sendAll(
                kind = RefusedRecord.MINUTE, batchSize = minuteBatch, budget = { maxRequestsPerRun - requests },
                marker = { it.minutesSentUpToMs },
                fetch = { after, n -> source.minutesAfter(after, n) }, start = { it.startEpochMs },
                post = { batch -> client.postMinutes(id, token, JsonArray(batch.map(Payloads::minute))) },
                advance = { snap, t -> snap.copy(minutesSentUpToMs = maxOf(snap.minutesSentUpToMs, t)) },
            ) ?: return Outcome.Disabled
            requests += minutes.requests; sentMinutes = minutes.sent; refused += minutes.refused; more = minutes.more
            if (!more) {
                val events = sendAll(
                    kind = RefusedRecord.EVENT, batchSize = eventBatch, budget = { maxRequestsPerRun - requests },
                    marker = { it.eventsSentUpToMs },
                    fetch = { after, n -> source.eventsAfter(after, n) }, start = { it.startEpochMs },
                    // Wind-flagged events (detector v2) are not shared, as they are left out of the app's
                // own counts; the marker still moves past them (server/DESIGN.md §4.4).
                include = Payloads::isShared,
                post = { batch -> client.postEvents(id, token, JsonArray(batch.map(Payloads::event))) },
                    advance = { snap, t -> snap.copy(eventsSentUpToMs = maxOf(snap.eventsSentUpToMs, t)) },
                ) ?: return Outcome.Disabled
                requests += events.requests; sentEvents = events.sent; refused += events.refused; more = events.more
            }
            // «Zuletzt gesendet» only moves when something was actually sent.
            store.update { if (requests > 0) it.copy(lastSuccessAtMs = clock(), lastError = null) else it.copy(lastError = null) }
            return Outcome.Done(sentMinutes, sentEvents, refused, more)
        } catch (e: UploadClient.Failure.Unauthorized) {
            store.update { it.copy(authFailed = true, lastError = "Der Server kennt diese Kennung nicht (mehr). «Neue Kennung» wählen.") }
            return Outcome.AuthFailed
        } catch (e: UploadClient.Failure.RateLimited) {
            store.update { it.copy(lastError = "Server ausgelastet, später erneut.") }
            return Outcome.Retry(e.message ?: "429")
        } catch (e: UploadClient.Failure.Rejected) {
            store.update { it.copy(lastError = "Vom Server abgelehnt (${e.status}).") }
            return Outcome.Failed(e.message ?: e.status.toString())
        } catch (e: UploadClient.Failure.Server) {
            store.update { it.copy(lastError = "Serverfehler (${e.status}), später erneut.") }
            return Outcome.Retry(e.message ?: e.status.toString())
        } catch (e: IOException) {
            store.update { it.copy(lastError = "Keine Verbindung zum Server, später erneut.") }
            return Outcome.Retry(e.javaClass.simpleName)
        }
    }

    private class Sent(val requests: Int, val sent: Int, val refused: Int, val more: Boolean)

    /**
     * Sends every record newer than the marker, batch by batch, moving the marker after each
     * acknowledged batch. Null if sharing was switched off in between.
     *
     * A full batch might end in the middle of several records with the same start (two minute rows
     * can share a start after a restart within one minute). The marker means "start > marker", so
     * such a tail is cut off and goes with the next batch. (If a whole batch had one start it is
     * sent as is; with 1440 rows per batch that cannot happen in practice.)
     */
    private suspend fun <T> sendAll(
        kind: String,
        batchSize: Int,
        budget: () -> Int,
        marker: (UploadSnapshot) -> Long,
        fetch: suspend (Long, Int) -> List<T>,
        start: (T) -> Long,
        include: (T) -> Boolean = { true },
        post: (List<T>) -> UploadClient.Result,
        advance: (UploadSnapshot, Long) -> UploadSnapshot,
    ): Sent? {
        var requests = 0
        var sent = 0
        var refusedCount = 0
        while (true) {
            if (stopRequested()) return null
            val s = store.state.value
            if (requests >= budget()) return Sent(requests, sent, refusedCount, more = true)
            // One row more than a batch, to see whether the batch would split records with one start.
            val raw = fetch(maxOf(marker(s), effectiveFloorMs(s) - 1), batchSize + 1)
            if (raw.isEmpty()) return Sent(requests, sent, refusedCount, more = false)
            val full = raw.size > batchSize
            val rows = if (!full) raw else {
                val head = raw.take(batchSize)
                val nextStart = start(raw[batchSize])
                if (start(head.last()) != nextStart) head else head.dropLastWhile { start(it) == nextStart }.ifEmpty { head }
            }
            val skip = s.refused.filter { it.kind == kind }.map { it.startEpochMs }.toHashSet()
            val batch = rows.filter { start(it) !in skip && include(it) }
            if (batch.isNotEmpty()) {
                val res = post(batch)
                requests++
                sent += res.accepted + res.duplicates
                refusedCount += res.rejected.size
                recordRefused(kind, batch.map(start), res.rejected)
            }
            val last = start(rows.last())
            store.update { advance(it, last) }
            if (!full) return Sent(requests, sent, refusedCount, more = false)
        }
    }

    private fun recordRefused(kind: String, starts: List<Long>, rejected: List<UploadClient.Rejected>) {
        if (rejected.isEmpty()) return
        val add = rejected.mapNotNull { r -> starts.getOrNull(r.index)?.let { RefusedRecord(kind, it, r.reasons) } }
        store.update {
            it.copy(
                refused = (it.refused + add).takeLast(UploadSnapshot.REFUSED_KEEP),
                refusedTotal = it.refusedTotal + add.size,
            )
        }
    }

    /**
     * «Meine Daten auf dem Server löschen»: DELETE and wait for the server's 204, then forget the
     * id and token and switch sharing off. On any error nothing changes on the phone.
     */
    suspend fun deleteServerData(): DeleteOutcome {
        abort.set(true)
        return lock.withLock {
            abort.set(false)
            deleteAndForget(disable = true)
        }
    }

    /** «Neue Kennung»: delete the server data like above, then start over with a fresh id (on the next upload). */
    suspend fun newIdentity(): DeleteOutcome {
        abort.set(true)
        return lock.withLock {
            abort.set(false)
            deleteAndForget(disable = false)
        }
    }

    private fun deleteAndForget(disable: Boolean): DeleteOutcome {
        val r = deleteLocked()
        if (r !is DeleteOutcome.Failed) {
            store.update { forget(it).let { f -> if (disable) f.copy(enabled = false) else f } }
        }
        return r
    }

    private fun deleteLocked(): DeleteOutcome {
        val s = store.state.value
        if (!s.registered) return DeleteOutcome.Deleted
        return try {
            client.deleteDevice(s.deviceId!!, s.token!!)
            DeleteOutcome.Deleted
        } catch (e: UploadClient.Failure.Unauthorized) {
            DeleteOutcome.UnknownToServer
        } catch (e: IOException) {
            DeleteOutcome.Failed(
                when (e) {
                    is UploadClient.Failure -> e.message ?: "Fehler"
                    else -> "Keine Verbindung zum Server."
                }
            )
        }
    }

    /** Forget the device: nothing sent before now is sent again under a new id. */
    private fun forget(s: UploadSnapshot) = s.copy(
        deviceId = null, token = null, lastSiteJson = null, authFailed = false,
        minutesSentUpToMs = 0, eventsSentUpToMs = 0, sendFloorMs = clock(),
        lastSuccessAtMs = null, lastError = null, refused = emptyList(),
    )

    companion object {
        /** The server refuses records older than 7 days (DESIGN.md §8, `MAX_RECORD_AGE_DAYS`). */
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000
        /** Records this close to the limit are left out: they could be refused by the time they arrive. */
        const val AGE_MARGIN_MS = 3600L * 1000
    }
}
