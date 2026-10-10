package ch.stadtlaerm.app.upload

import ch.stadtlaerm.app.data.EventEntity
import ch.stadtlaerm.app.data.MinuteEntity
import ch.stadtlaerm.dsp.Iso
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The opt-in upload against a fake server on 127.0.0.1 (server/DESIGN.md §4, server/README.md). */
class UploadTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private val server = FakeServer()
    private var now = ZonedDateTime.of(2026, 10, 8, 7, 5, 0, 0, zone).toInstant().toEpochMilli()

    @AfterTest
    fun stop() = server.close()

    // ---- fixtures ------------------------------------------------------------------------

    private val minuteRows = mutableListOf<MinuteEntity>()
    private val eventRows = mutableListOf<EventEntity>()

    private val source = object : UploadSource {
        override suspend fun minutesAfter(afterMs: Long, limit: Int) =
            minuteRows.filter { it.startEpochMs > afterMs }.sortedWith(compareBy({ it.startEpochMs }, { it.id })).take(limit)

        override suspend fun eventsAfter(afterMs: Long, limit: Int) =
            eventRows.filter { it.startEpochMs > afterMs }.sortedWith(compareBy({ it.startEpochMs }, { it.id })).take(limit)

        override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = true, calibrationOffsetDb = 121.9)
    }

    private fun minute(startMs: Long, laeq: Double? = 42.34, id: Long = minuteRows.size + 1L) = MinuteEntity(
        id = id, startEpochMs = startMs, startIso = Iso.format(startMs, zone), durationSeconds = 60.0,
        laeqDb = laeq, lafMaxDb = 61.04, lafMinDb = 33.1, l1Db = 55.0, l10Db = 47.2, l50Db = 41.0, l90Db = 36.4,
        eventCount = 2, dominantCategory = "road_traffic",
        categorySharesJson = """{"road_traffic":0.41,"loud_vehicle":0.05,"unclassified":0.54}""",
        classifierFrames = 60, calibrationId = 7, calibrationOffsetDb = 121.9, audioSource = "UNPROCESSED",
        calibrated = true, validSeconds = 59.5, coverage = 59.5 / 60, clockCorrections = 0,
        origLaeqDb = 30.0, recalibratedFromId = "default", recalibrationOffsetDb = 112.35,
    )

    private fun event(startMs: Long, category: String? = "loud_vehicle", id: Long = eventRows.size + 1L) = EventEntity(
        id = id, startEpochMs = startMs, startIso = Iso.format(startMs, zone, millis = true), durationSeconds = 2.75,
        lafMaxDb = 71.44, selDb = 74.0, backgroundDb = 38.1, thresholdDb = 10.0, dominantCategory = category,
        dominantScore = 0.31f, topLabelsJson = """[{"label":"Motorcycle","score":0.31}]""", classifierFrames = 3,
        calibrationId = 7, audioSource = "UNPROCESSED", calibrated = true, minLevelDb = 30.0,
    )

    /** [n] consecutive minutes ending one hour before now. */
    private fun addMinutes(n: Int, endMs: Long = now - 3_600_000) {
        val first = endMs - n * 60_000L
        repeat(n) { minuteRows += minute(first + it * 60_000L) }
    }

    private fun addEvents(n: Int, endMs: Long = now - 3_600_000) {
        val first = endMs - n * 7_000L
        repeat(n) { eventRows += event(first + it * 7_000L + 375) }
    }

    private val site = SiteSettings(cell = "h26824_12473", placement = "open_window", floor = 3, streetFacing = true, note = "2. OG")

    private fun setup(enabled: Boolean = true): Pair<Uploader, MemoryUploadStore> {
        val store = MemoryUploadStore(UploadSnapshot(explanationSeen = true, site = site))
        val up = Uploader(UploadClient(server.baseUrl), store, source, "0.4.0-dev", 9, clock = { now })
        if (enabled) assertTrue(up.enable())
        return up to store
    }

    // ---- tests ---------------------------------------------------------------------------

    @Test
    fun registersSendsSiteMinutesAndEventsAsGzipJsonWithBearerToken() = runBlocking {
        addMinutes(90); addEvents(12)
        val (up, store) = setup()
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 90, events = 12, refused = 0, more = false), o)

        val s = store.state.value
        assertTrue(s.registered)
        assertEquals(minuteRows.last().startEpochMs, s.minutesSentUpToMs)
        assertEquals(eventRows.last().startEpochMs, s.eventsSentUpToMs)
        assertEquals(now, s.lastSuccessAtMs)
        assertNull(s.lastError)

        val reqs = server.requests
        assertEquals(listOf("POST /v1/devices", "PUT /site", "POST /minutes", "POST /events"),
            reqs.map { r -> "${r.method} " + if (r.path == "/v1/devices") r.path else r.path.substringAfterLast("/").let { "/$it" } })
        assertNull(reqs[0].headers["authorization"], "registration carries no token")
        for (r in reqs.drop(1)) assertEquals("Bearer ${server.tokens[s.deviceId]}", r.headers["authorization"])
        for (r in reqs) {
            assertEquals("gzip", r.headers["content-encoding"])
            assertEquals("Stadtlaerm", r.headers["user-agent"], "no device details in the User-Agent")
        }
        assertEquals("""{"app_version":"0.4.0-dev","app_build":9}""", reqs[0].body.toString())
        assertEquals(90, server.minutes[s.deviceId]!!.size)
        assertEquals(12, server.events[s.deviceId]!!.size)
    }

    @Test
    fun siteBodyIsExactlyDesign42AndCarriesOnlyTheHectare() = runBlocking {
        val (up, store) = setup()
        up.run()
        val body = server.sites[store.state.value.deviceId]!!
        assertEquals(
            setOf("cell", "placement", "floor", "street_facing", "device_model", "audio_source", "calibrated", "calibration_offset_db", "note"),
            body.keys,
        )
        assertEquals("h26824_12473", body["cell"]!!.jsonPrimitive.content)
        assertEquals("open_window", body["placement"]!!.jsonPrimitive.content)
        assertEquals("3", body["floor"].toString())
        assertEquals("true", body["street_facing"].toString())
        assertEquals("Pixel 8", body["device_model"]!!.jsonPrimitive.content)
        assertEquals("121.9", body["calibration_offset_db"].toString())
        assertEquals("2. OG", body["note"]!!.jsonPrimitive.content)
    }

    @Test
    fun minuteAndEventBodiesFollowDesignAndLeaveOutLabelsAndOriginals() = runBlocking {
        minuteRows += minute(now - 7_200_000, laeq = Double.NaN)
        eventRows += event(now - 7_100_000, category = null)
        val (up, store) = setup()
        up.run()
        val id = store.state.value.deviceId!!
        val m = server.minutes[id]!!.values.single()
        assertEquals(
            setOf(
                "start", "duration_s", "valid_s", "coverage", "laeq_db", "lafmax_db", "lafmin_db", "l1_db", "l10_db", "l50_db",
                "l90_db", "event_count", "dominant_category", "category_shares", "calibration_id", "calibration_offset_db",
                "calibrated", "audio_source", "clock_corrections",
            ),
            m.keys,
        )
        assertEquals(JsonNull, m["laeq_db"], "NaN is sent as null, never as invalid JSON")
        assertEquals("61.0", m["lafmax_db"].toString(), "one decimal")
        assertEquals("0.992", m["coverage"].toString())
        assertEquals("\"7\"", m["calibration_id"].toString(), "calibration id as text (server: ShortText)")
        assertTrue(m["start"]!!.jsonPrimitive.content.endsWith("+02:00"), "ISO-8601 with offset")
        assertEquals(setOf("road_traffic", "loud_vehicle", "unclassified"), m["category_shares"]!!.jsonObject.keys)

        val e = server.events[id]!!.values.single()
        assertEquals(
            setOf("start", "duration_s", "lafmax_db", "sel_db", "background_db", "threshold_db", "min_level_db", "category",
                "category_score", "calibration_id", "calibrated"),
            e.keys,
        )
        assertEquals("unclassified", e["category"]!!.jsonPrimitive.content, "no category → unclassified")
        assertTrue(e["start"]!!.jsonPrimitive.content.contains("."), "event start with milliseconds")
        // Nothing of the raw classifier labels or the re-evaluation originals leaves the phone.
        for (r in server.requests) {
            val text = r.body.toString()
            assertFalse(text.contains("Motorcycle") || text.contains("label") || text.contains("orig_") || text.contains("recalibrat"), text)
        }
    }

    @Test
    fun windEventsAreNotSentAndMinuteEventCountExcludesWind() = runBlocking {
        // Detector v2: a minute with 1 counted event and 3 wind events; the minute's eventCount is
        // already the count without wind (what the app shows), windEventCount is not sent.
        minuteRows += minute(now - 7_200_000).copy(eventCount = 1, windEventCount = 3, localFloorDb = 35.2)
        eventRows += event(now - 7_190_000).copy(localFloorDb = 35.0, excessDb = 6.5, lfShare = 0.4, shape = "hump")
        eventRows += event(now - 7_180_000, category = "wind").copy(wind = true, lfShare = 0.97, lfFlutterDb = 5.0, shape = "impulse")
        eventRows += event(now - 7_170_000, category = "wind").copy(wind = true, lfShare = 0.95, shape = "jagged")
        val (up, store) = setup()
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 1, events = 1, refused = 0, more = false), o)
        val id = store.state.value.deviceId!!
        val m = server.minutes[id]!!.values.single()
        assertEquals("1", m["event_count"].toString(), "event_count without wind")
        val e = server.events[id]!!.values.single()
        assertEquals("loud_vehicle", e["category"]!!.jsonPrimitive.content)
        assertEquals(1, server.events[id]!!.size, "wind events are not sent")
        // The marker moves past the wind events, so they are not fetched again.
        assertEquals(eventRows.last().startEpochMs, store.state.value.eventsSentUpToMs)
        // No detector-v2 feature leaks into a body.
        for (r in server.requests.filter { it.path.endsWith("/minutes") || it.path.endsWith("/events") }) {
            val text = r.body.toString()
            for (k in listOf("\"wind", "local_floor", "excess", "lf_share", "lf_flutter", "shape", "rise_s", "decay_s", "jagged", "mid_band"))
                assertFalse(text.contains(k), "$k in $text")
        }
    }

    @Test
    fun aBatchOfOnlyWindEventsSendsNoEventRequest() = runBlocking {
        addMinutes(5)
        repeat(4) { eventRows += event(now - 7_000_000 + it * 5_000L, category = "wind").copy(wind = true, shape = "jagged") }
        val (up, store) = setup()
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 5, events = 0, refused = 0, more = false), o)
        assertEquals(0, server.count("POST", "/events"))
        assertEquals(eventRows.last().startEpochMs, store.state.value.eventsSentUpToMs)
        assertFalse(Payloads.isShared(eventRows.first()))
        assertTrue(Payloads.isShared(event(now - 1)))
    }

    @Test
    fun batchesAreAtMost1440MinutesAnd2000Events() = runBlocking {
        addMinutes(3000, endMs = now - 3_600_000)
        addEvents(2500)
        val (up, store) = setup()
        val o = up.run()
        assertIs<Uploader.Outcome.Done>(o)
        val minuteBatches = server.requests.filter { it.path.endsWith("/minutes") }.map { it.body!!.jsonArray.size }
        val eventBatches = server.requests.filter { it.path.endsWith("/events") }.map { it.body!!.jsonArray.size }
        assertTrue(minuteBatches.all { it <= 1440 }, "$minuteBatches")
        assertTrue(eventBatches.all { it <= 2000 }, "$eventBatches")
        // 3000 minutes = 50 h; only the last 7 days count, all of them are inside.
        assertEquals(3000, minuteBatches.sum())
        assertEquals(2500, eventBatches.sum())
        assertEquals(3000, server.minutes[store.state.value.deviceId]!!.size)
    }

    @Test
    fun repeatedUploadIsCountedAsDuplicatesAndStoresNothingTwice() = runBlocking {
        addMinutes(30); addEvents(5)
        val (up, store) = setup()
        up.run()
        up.rewindAfterReevaluation() // as after «Alte Messungen neu bewerten»
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 30, events = 5, refused = 0, more = false), o)
        assertEquals(30, server.minutes[store.state.value.deviceId]!!.size)
        assertEquals(2, server.count("POST", "/minutes"))
        assertEquals(1, server.count("POST", "/v1/devices"), "registered once")
        assertEquals(1, server.count("PUT", "/site"), "unchanged site is not sent again")
    }

    @Test
    fun resumesFromTheLastAcknowledgedStartAfterAPartialFailure() = runBlocking {
        addMinutes(3000)
        val (up, store) = setup()
        // First batch succeeds, second fails with a server error.
        var calls = 0
        val failing = object : UploadSource by source {
            override suspend fun minutesAfter(afterMs: Long, limit: Int): List<MinuteEntity> {
                calls++
                if (calls == 2) server.failNext += Triple("POST", "/minutes", 503)
                return source.minutesAfter(afterMs, limit)
            }
        }
        val up2 = Uploader(UploadClient(server.baseUrl), store, failing, "0.4.0-dev", 9, clock = { now })
        val first = up2.run()
        assertIs<Uploader.Outcome.Retry>(first)
        val marker = store.state.value.minutesSentUpToMs
        assertEquals(minuteRows[1439].startEpochMs, marker, "marker = last start of the acknowledged batch")
        assertTrue(store.state.value.lastError!!.contains("503"))

        val second = up.run()
        assertIs<Uploader.Outcome.Done>(second)
        val id = store.state.value.deviceId!!
        assertEquals(3000, server.minutes[id]!!.size)
        // Every start was sent successfully exactly once (the failed request stored nothing).
        val okStarts = server.requests.filter { it.path.endsWith("/minutes") }.drop(0)
            .flatMap { r -> r.body!!.jsonArray.map { it.jsonObject["start"]!!.jsonPrimitive.content } }
        assertEquals(3000 + 1440, okStarts.size, "the failed batch was resent once, nothing else")
        assertNull(store.state.value.lastError)
    }

    @Test
    fun rejectedRecordsAreMarkedAsRefusedAndNeverSentAgain() = runBlocking {
        addMinutes(10); addEvents(3)
        server.refuseStarts += minuteRows[3].startIso
        server.refuseStarts += eventRows[1].startIso
        val (up, store) = setup()
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 9, events = 2, refused = 2, more = false), o)
        val s = store.state.value
        assertEquals(2, s.refusedTotal)
        assertEquals(
            setOf(RefusedRecord.MINUTE to minuteRows[3].startEpochMs, RefusedRecord.EVENT to eventRows[1].startEpochMs),
            s.refused.map { it.kind to it.startEpochMs }.toSet(),
        )
        assertTrue(s.refused.first().reason.contains("out of range"))
        assertEquals(minuteRows.last().startEpochMs, s.minutesSentUpToMs, "the batch counts as done")

        up.rewindAfterReevaluation()
        up.run()
        val lastMinutes = server.requests.last { it.path.endsWith("/minutes") }.body!!.jsonArray
        assertEquals(9, lastMinutes.size)
        assertFalse(lastMinutes.any { it.jsonObject["start"]!!.jsonPrimitive.content == minuteRows[3].startIso })
    }

    @Test
    fun aRefusedTokenStopsUploadingUntilNewIdentity() = runBlocking {
        addMinutes(5)
        val (up, store) = setup()
        up.run()
        val oldId = store.state.value.deviceId!!
        server.tokens[oldId] = "something-else" // the server no longer accepts our token
        minuteRows += minute(now - 1_800_000)
        assertEquals(Uploader.Outcome.AuthFailed, up.run())
        assertTrue(store.state.value.authFailed)
        assertTrue(store.state.value.lastError!!.contains("Neue Kennung"))
        val before = server.requests.size
        assertEquals(Uploader.Outcome.AuthFailed, up.run())
        assertEquals(before, server.requests.size, "no requests while the token is refused")

        // «Neue Kennung»: the DELETE is refused (401) too, so the old id is simply forgotten.
        assertEquals(Uploader.DeleteOutcome.UnknownToServer, up.newIdentity())
        val s = store.state.value
        assertNull(s.deviceId); assertNull(s.token); assertFalse(s.authFailed)
        assertTrue(s.enabled, "«Neue Kennung» keeps sharing on")
        up.run()
        val newId = store.state.value.deviceId!!
        assertNotEquals(oldId, newId)
        assertEquals(0, server.minutes[newId]?.size ?: 0, "nothing from before the new id is sent under it")
    }

    @Test
    fun deleteRemovesEverythingOnTheServerSynchronouslyAndSwitchesOff() = runBlocking {
        addMinutes(20); addEvents(4)
        val (up, store) = setup()
        up.run()
        val id = store.state.value.deviceId!!
        assertEquals(Uploader.DeleteOutcome.Deleted, up.deleteServerData())
        assertEquals(1, server.count("DELETE", "/v1/devices/$id"))
        assertNull(server.tokens[id]); assertNull(server.sites[id]); assertNull(server.minutes[id]); assertNull(server.events[id])
        val s = store.state.value
        assertFalse(s.enabled); assertNull(s.deviceId); assertNull(s.token)
        assertEquals(0, s.minutesSentUpToMs)
        assertEquals(now, s.sendFloorMs, "switching on again sends nothing measured before the delete")
        // Off: nothing is sent.
        val n = server.requests.size
        assertEquals(Uploader.Outcome.Disabled, up.run())
        assertEquals(n, server.requests.size)
    }

    @Test
    fun aFailedDeleteChangesNothingOnThePhone() = runBlocking {
        addMinutes(3)
        val (up, store) = setup()
        up.run()
        val before = store.state.value
        server.failNext += Triple("DELETE", before.deviceId!!, 500)
        assertIs<Uploader.DeleteOutcome.Failed>(up.deleteServerData())
        assertEquals(before, store.state.value)
        assertEquals(Uploader.DeleteOutcome.Deleted, up.deleteServerData())
    }

    @Test
    fun deleteWithoutRegistrationSendsNothing() = runBlocking {
        val (up, _) = setup(enabled = false)
        assertEquals(Uploader.DeleteOutcome.Deleted, up.deleteServerData())
        assertTrue(server.requests.isEmpty())
    }

    @Test
    fun offByDefaultAndEnablingNeedsExplanationAndHectare() = runBlocking {
        addMinutes(5)
        assertFalse(UploadSnapshot().enabled)
        assertTrue(UploadSnapshot().wifiOnly)
        val bare = Uploader(UploadClient(server.baseUrl), MemoryUploadStore(), source, "x", 1, clock = { now })
        assertFalse(bare.enable(), "no explanation, no hectare")
        val noSite = MemoryUploadStore(UploadSnapshot(explanationSeen = true))
        assertFalse(Uploader(UploadClient(server.baseUrl), noSite, source, "x", 1, clock = { now }).enable())
        val (up, _) = setup(enabled = false)
        assertEquals(Uploader.Outcome.Disabled, up.run())
        assertTrue(server.requests.isEmpty(), "nothing at all is sent while sharing is off")
    }

    @Test
    fun onlyTheLastSevenDaysAndNothingFromWhileSharingWasOff() = runBlocking {
        val day = 86_400_000L
        minuteRows += minute(now - 8 * day)          // too old for the server
        minuteRows += minute(now - 6 * day)          // first opt-in includes the last 7 days
        val (up, store) = setup()
        up.run()
        val id = store.state.value.deviceId!!
        assertEquals(1, server.minutes[id]!!.size)

        up.disable()
        minuteRows += minute(now + 60_000)            // measured while sharing was off
        now += 3_600_000
        assertTrue(up.enable())
        minuteRows += minute(now + 60_000)            // measured after switching on again
        now += 600_000
        up.run()
        assertEquals(2, server.minutes[id]!!.size)
        assertEquals(Iso.format(now - 600_000 + 60_000, zone), server.minutes[id]!!.keys.max())
    }

    @Test
    fun aChangedSiteIsSentAgain() = runBlocking {
        val (up, store) = setup()
        up.run()
        store.update { it.copy(site = it.site!!.copy(cell = "h26833_12479", placement = "balcony")) }
        up.run()
        assertEquals(2, server.count("PUT", "/site"))
        assertEquals("h26833_12479", server.sites[store.state.value.deviceId]!!["cell"]!!.jsonPrimitive.content)
    }

    @Test
    fun rateLimitAndBadRequestsAreReportedForRetryOrNot() = runBlocking {
        addMinutes(3)
        val (up, store) = setup()
        server.failNext += Triple("POST", "/v1/devices", 429)
        assertIs<Uploader.Outcome.Retry>(up.run())
        assertFalse(store.state.value.registered)
        up.run()
        minuteRows += minute(now - 60_000)
        server.failNext += Triple("POST", "/minutes", 422)
        assertIs<Uploader.Outcome.Failed>(up.run())
        assertTrue(store.state.value.lastError!!.contains("422"))
    }

    @Test
    fun networkErrorIsRetried() = runBlocking {
        val store = MemoryUploadStore(UploadSnapshot(explanationSeen = true, site = site))
        server.close()
        val up = Uploader(UploadClient(server.baseUrl, connectTimeoutMs = 2000), store, source, "x", 1, clock = { now })
        up.enable()
        assertIs<Uploader.Outcome.Retry>(up.run())
        assertTrue(store.state.value.lastError!!.startsWith("Keine Verbindung"))
    }

    @Test
    fun aNightIsWellUnderATenthOfAMegabyteOnTheWire() = runBlocking {
        // 8 h of minutes and 150 events, backing the settings screen's «weniger als 0,1 MB pro Nacht».
        addMinutes(480); addEvents(150)
        val (up, _) = setup()
        up.run()
        val bytes = server.requests.sumOf { it.rawSize }
        assertTrue(bytes < 100_000, "$bytes bytes on the wire (gzip bodies)")
    }

    @Test
    fun theClientTalksOnlyHttpsExceptToLoopback() {
        UploadClient("https://api.stadtlaerm.ch")
        assertEquals("api.stadtlaerm.ch", UploadClient("https://api.stadtlaerm.ch/").host)
        UploadClient("http://127.0.0.1:8080")
        assertFailsWith<IllegalArgumentException> { UploadClient("http://api.stadtlaerm.ch") }
        assertFailsWith<IllegalArgumentException> { UploadClient("https://api.stadtlaerm.ch/v1") }
    }

    @Test
    fun hourlyJobRunsFiveMinutesAfterTheFullHour() {
        val t = ZonedDateTime.of(2026, 10, 8, 23, 41, 30, 0, zone)
        assertEquals((18 * 60 + 30 + 5 * 60) * 1000L, UploadModule.delayToNextHourMs(t))
    }

    @Test
    fun payloadNumbersAreRoundedAndNeverNaN() {
        assertEquals("42.3", Payloads.level(42.34).toString())
        assertEquals("42.4", Payloads.level(42.35000001).toString())
        assertEquals(JsonNull, Payloads.level(Double.NaN))
        assertEquals(JsonNull, Payloads.level(Double.POSITIVE_INFINITY))
        assertEquals(JsonNull, Payloads.level(null))
        val site = Payloads.site(SiteSettings("h26824_12473", note = "x".repeat(300)), SiteExtras("", "VOICE_RECOGNITION", false, 112.35))
        assertEquals(200, site["note"]!!.jsonPrimitive.content.length)
        assertEquals(JsonNull, site["device_model"])
        assertEquals(JsonNull, site["floor"])
        assertTrue((site as JsonObject).keys.none { it.contains("lat") || it.contains("lon") || it.contains("coord") })
    }

    @Test
    fun coordinateEntryBecomesAHectareOnThePhone() {
        fun ok(s: String) = (CellInput.parse(s) as CellInput.Parsed.Ok).cell
        assertEquals("h26833_12479", ok("47.3769, 8.5417"))
        assertEquals("h26833_12479", ok("47,3769 8,5417"))
        assertEquals("h26833_12479", ok("8.5417, 47.3769"))
        assertEquals("h26833_12479", ok("2'683'304, 1'247'925"))
        assertEquals("h26833_12479", ok("2683304.0 1247925.6"))
        assertEquals("h26833_12479", ok("683'304 / 247'925"))
        assertEquals("h26824_12473", ok("h26824_12473"))
        assertEquals("h26809_12512", ok("47.40717847, 8.51060962")) // ETH2 (DESIGN.md §3)
        assertIs<CellInput.Parsed.Error>(CellInput.parse(""))
        assertIs<CellInput.Parsed.Error>(CellInput.parse("48.8566, 2.3522"))   // Paris
        assertIs<CellInput.Parsed.Error>(CellInput.parse("47.37"))
        assertIs<CellInput.Parsed.Error>(CellInput.parse("h99999_99999"))
    }
}
