package ch.stadtlaerm.app.upload

import ch.stadtlaerm.app.data.EventEntity
import ch.stadtlaerm.app.data.MinuteEntity
import ch.stadtlaerm.dsp.Iso
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Optional round trip against the real contribution server (`server/`, run locally):
 *
 *     cd server && DB_PATH=/tmp/s.sqlite MAP_DIR=/tmp/map BACKGROUND_JOBS=0 HOST=127.0.0.1 PORT=18765 \
 *         .venv/bin/python -m stadtlaerm_server serve
 *     STADTLAERM_TEST_SERVER=http://127.0.0.1:18765 ./gradlew :app:testKarteDebugUnitTest --tests '*RealServerTest*'
 *
 * Skipped unless STADTLAERM_TEST_SERVER is set (only a loopback URL is accepted by UploadClient
 * besides https). Checks that the server's schema validation accepts exactly what the app sends.
 */
class RealServerTest {
    private val base: String? = System.getenv("STADTLAERM_TEST_SERVER")
    private val zone = ZoneId.of("Europe/Zurich")

    @Test
    fun theRealServerAcceptsEverythingTheAppSendsAndDeletesIt() = runBlocking {
        assumeTrue("STADTLAERM_TEST_SERVER not set", base != null)
        val now = System.currentTimeMillis()
        val firstMinute = (now - 3 * 3_600_000) / 60_000 * 60_000
        val minutes = (0 until 120).map { i ->
            val t = firstMinute + i * 60_000L
            MinuteEntity(
                id = i + 1L, startEpochMs = t, startIso = Iso.format(t, zone), durationSeconds = 60.0,
                laeqDb = if (i == 5) null else 42.34 + i % 7, lafMaxDb = if (i == 5) null else 61.04, lafMinDb = 33.1,
                l1Db = 55.0, l10Db = 47.2, l50Db = 41.0, l90Db = 36.4, eventCount = i % 3,
                dominantCategory = if (i % 2 == 0) "road_traffic" else null,
                categorySharesJson = """{"road_traffic":0.41,"loud_vehicle":0.05,"unclassified":0.54}""",
                classifierFrames = 60, calibrationId = if (i < 60) null else 7, calibrationOffsetDb = 112.35,
                audioSource = "UNPROCESSED", calibrated = i >= 60, validSeconds = if (i == 5) 0.0 else 59.5,
                coverage = if (i == 5) 0.0 else 59.5 / 60, clockCorrections = 0,
            )
        }
        val events = (0 until 40).map { i ->
            val t = firstMinute + i * 97_000L + 375
            EventEntity(
                id = i + 1L, startEpochMs = t, startIso = Iso.format(t, zone, millis = true), durationSeconds = 2.75,
                lafMaxDb = 71.44, selDb = 74.0, backgroundDb = 38.1, thresholdDb = 10.0,
                dominantCategory = if (i % 4 == 0) null else "loud_vehicle", dominantScore = 0.31f,
                topLabelsJson = "[]", classifierFrames = 3, calibrationId = 7, audioSource = "UNPROCESSED",
                calibrated = true, minLevelDb = if (i % 5 == 0) Double.NaN else 30.0,
            )
        }
        val tooOld = minutes.first().copy(id = 999, startEpochMs = now - 8L * 86_400_000, startIso = Iso.format(now - 8L * 86_400_000, zone))
        val source = object : UploadSource {
            val all = listOf(tooOld) + minutes
            override suspend fun minutesAfter(afterMs: Long, limit: Int) = all.filter { it.startEpochMs > afterMs }.take(limit)
            override suspend fun eventsAfter(afterMs: Long, limit: Int) = events.filter { it.startEpochMs > afterMs }.take(limit)
            override suspend fun siteExtras() = SiteExtras("Pixel 8", "UNPROCESSED", calibrated = false, calibrationOffsetDb = 112.35)
        }
        val store = MemoryUploadStore(
            UploadSnapshot(explanationSeen = true, site = SiteSettings("h26833_12479", "balcony", floor = 0, streetFacing = false, note = "Test"))
        )
        val up = Uploader(UploadClient(base!!), store, source, "0.4.0-dev", 9, minuteBatch = 50, eventBatch = 15)
        up.enable()
        val o = up.run()
        assertEquals(Uploader.Outcome.Done(minutes = 120, events = 40, refused = 0, more = false), o, "the 8-day-old minute is not even sent")
        // Again after a re-evaluation: the server reports duplicates, stores nothing twice.
        up.rewindAfterReevaluation()
        val again = up.run()
        assertIs<Uploader.Outcome.Done>(again)
        assertEquals(120, again.minutes)
        assertNull(store.state.value.lastError)
        assertTrue(store.state.value.refused.isEmpty())
        assertEquals(Uploader.DeleteOutcome.Deleted, up.deleteServerData())
    }
}
