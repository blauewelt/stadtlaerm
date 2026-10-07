package ch.stadtlaerm.app.labor

import ch.stadtlaerm.dsp.NoiseEvent
import ch.stadtlaerm.dsp.classify.CategoryDecision
import ch.stadtlaerm.dsp.classify.ClassifierResult
import ch.stadtlaerm.dsp.classify.LabelScore
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PoliciesAndManifestTest {
    private val zurich = ZoneId.of("Europe/Zurich")
    private fun ms(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()

    // ---- Storage cap ----------------------------------------------------------------------

    @Test
    fun storageCapStopsAndLatches() {
        val b = StorageBudget(maxBytes = 1_000, initialUsedBytes = 400)
        assertFalse(b.full)
        assertTrue(b.tryReserve(600)) // exactly at the cap is allowed
        assertEquals(1_000, b.usedBytes)
        assertFalse(b.tryReserve(1)) // one more byte: full
        assertTrue(b.full)
        assertFalse(b.tryReserve(0)) // latched for the rest of the session
        assertEquals(1_000, b.usedBytes) // a refused reservation is not counted
    }

    @Test
    fun storageCapAccountingAfterTheFact() {
        val b = StorageBudget(maxBytes = 100, initialUsedBytes = 0)
        assertTrue(b.account(60))
        assertTrue(b.account(40))
        assertFalse(b.account(1))
        assertTrue(b.full)
        // Already over the cap from earlier sessions: full right away.
        assertTrue(StorageBudget(100, 100).full)
        assertEquals(2_000_000_000L, (StorageBudget.DEFAULT_GB * StorageBudget.GB).toLong())
        assertEquals(2_000_000_000L, LaborSettings().maxBytes)
    }

    // ---- Clip rate --------------------------------------------------------------------------

    @Test
    fun clipRateKeepsFirstThenEveryNth() {
        fun pattern(n: Int) = ClipRateSampler(n).let { s -> List(11) { s.next() } }
        assertEquals(List(11) { true }, pattern(1))
        assertEquals(List(11) { it % 2 == 0 }, pattern(2))
        assertEquals(List(11) { it % 5 == 0 }, pattern(5)) // events 1, 6, 11
        val s = ClipRateSampler(5)
        repeat(11) { s.next() }
        assertEquals(11, s.seen); assertEquals(8, s.skipped)
    }

    // ---- Hour rollover ------------------------------------------------------------------------

    @Test
    fun newFileAtStartAndAtEveryWallClockHour() {
        val r = HourRoller(zurich)
        assertTrue(r.needsNewFile(ms("2026-10-07T23:42:10.500+02:00[Europe/Zurich]"))) // measurement start
        assertEquals("20261007_23", r.baseName(ms("2026-10-07T23:42:10.500+02:00[Europe/Zurich]")))
        assertFalse(r.needsNewFile(ms("2026-10-07T23:59:59.999+02:00[Europe/Zurich]")))
        assertTrue(r.needsNewFile(ms("2026-10-08T00:00:00.000+02:00[Europe/Zurich]")))
        assertEquals("20261008_00", r.baseName(ms("2026-10-08T00:00:00.000+02:00[Europe/Zurich]")))
        assertFalse(r.needsNewFile(ms("2026-10-08T00:00:00.125+02:00[Europe/Zurich]")))
        assertEquals(ms("2026-10-08T01:00:00+02:00[Europe/Zurich]"), r.nextBoundaryMs(ms("2026-10-08T00:12:00+02:00[Europe/Zurich]")))
    }

    @Test
    fun daylightSavingTimeHours() {
        // End of DST, 25.10.2026: 02:00–02:59 happens twice (CEST, then CET).
        val r = HourRoller(zurich)
        val firstTwo = ms("2026-10-25T02:30:00+02:00")
        val secondTwo = ms("2026-10-25T02:30:00+01:00")
        assertTrue(r.needsNewFile(firstTwo))
        assertTrue(r.needsNewFile(ms("2026-10-25T02:00:00+01:00"))) // the repeated hour is a new file
        assertFalse(r.needsNewFile(secondTwo))
        assertEquals(r.baseName(firstTwo), r.baseName(secondTwo)) // same name → made unique on disk
        assertTrue(r.needsNewFile(ms("2026-10-25T03:00:00+01:00")))
        // Start of DST, 29.3.2026: 02:00 does not exist, 01:59:59 is followed by 03:00.
        val s = HourRoller(zurich)
        assertTrue(s.needsNewFile(ms("2026-03-29T01:59:59+01:00")))
        assertTrue(s.needsNewFile(ms("2026-03-29T03:00:00+02:00")))
        assertEquals("20260329_03", s.baseName(ms("2026-03-29T03:00:00+02:00")))
        assertEquals(ms("2026-03-29T03:00:00+02:00"), s.nextBoundaryMs(ms("2026-03-29T01:30:00+01:00")))
    }

    @Test
    fun uniqueNames() {
        val taken = setOf("20261025_02.m4a", "20261025_02_2.m4a")
        assertEquals("20261025_02_3.m4a", HourRoller.unique("20261025_02", "m4a") { it in taken })
        assertEquals("20261025_03.m4a", HourRoller.unique("20261025_03", "m4a") { it in taken })
    }

    // ---- Manifest ------------------------------------------------------------------------------

    private val t0 = ms("2026-10-07T23:42:10.250+02:00[Europe/Zurich]")

    @Test
    fun jsonEscapingAndNumbers() {
        val j = Json().put("s", "a\"b\\c\nd\u0001ü").put("n", Double.NaN).put("x", 1.5).put("y", 2.0).put("z", -0.0004)
            .put("i", 3).put("l", null as Long?).put("b", true).putStrings("e", listOf("AGC: aus", "x")).build()
        assertEquals("""{"s":"a\"b\\c\nd\u0001ü","n":null,"x":1.5,"y":2,"z":-0,"i":3,"l":null,"b":true,"e":["AGC: aus","x"]}""", j)
    }

    @Test
    fun sessionAndClockLines() {
        val info = ManifestLines.SessionInfo(
            deviceModel = "Google Pixel 7", androidRelease = "15", sdkInt = 35, appVersion = "0.3.3-labor", appVersionCode = 7,
            audioSource = "UNPROCESSED", encoding = "PCM_FLOAT", effects = listOf("AGC: nicht vorhanden"),
            calibrationId = 4, calibrationOffsetDb = 112.25, calibrated = true,
            eventThresholdDb = 10.0, eventMinLevelDb = 30.0, classifierEnabled = true, classifierNormalize = true,
            classifierIntervalSeconds = 1.0,
            clips = true, continuous = false, clipEvery = 2, maxBytes = 2_000_000_000,
        )
        assertEquals(
            """{"type":"session_start","session":"20261007_234210","time":"2026-10-07T23:42:10.250+02:00","zone":"Europe/Zurich",""" +
                """"device":"Google Pixel 7","android":"15","sdk":35,"app":"0.3.3-labor","appCode":7,"audioSource":"UNPROCESSED",""" +
                """"encoding":"PCM_FLOAT","effects":["AGC: nicht vorhanden"],"calibrationId":4,"calibrationOffsetDb":112.25,"calibrated":true,""" +
                """"eventThresholdDb":10,"eventFloorDb":30,"classifier":true,"classifierLevelAdjustment":true,"classifierIntervalS":1,""" +
                """"inputSampleRate":48000,"clips":true,"continuous":false,"clipEvery":2,"maxBytes":2000000000}""",
            ManifestLines.sessionStart("20261007_234210", t0, zurich, info),
        )
        assertEquals(
            """{"type":"clock","session":"S","time":"2026-10-07T23:42:10.250+02:00","anchorSample":6000,"anchorEpochMs":$t0,""" +
                """"anchorTime":"2026-10-07T23:42:10.250+02:00","correctionMs":-612}""",
            ManifestLines.clock("S", t0, zurich, 6000, t0, -612),
        )
    }

    @Test
    fun clipLine() {
        val ev = NoiseEvent(
            startEpochMs = t0, startIso = "", durationSeconds = 2.375, lafMaxDb = 71.04, selDb = 74.5, backgroundDb = 38.2,
            thresholdDb = 10.0, dominantCategory = "loud_vehicle", dominantScore = 0.5f,
            topLabels = listOf(LabelScore("Motorcycle", 0.75f), LabelScore("Vehicle", 0.5f), LabelScore("Car", 0.25f), LabelScore("X", 0.1f)),
            classifierFrames = 3, calibrationId = 4, audioSource = "UNPROCESSED", calibrated = true,
        )
        val clip = AssembledClip(
            eventStartSample = 480_000, eventEndSample = 594_000, clipStartSample = 240_000,
            chunks = emptyList(), length = 594_000 + 240_000 - 240_000, truncated = false, endedEarly = false,
        )
        // Classifier windows ending at 6 s and 7 s (inside the clip that starts at 5 s).
        val trace = listOf(result(288_000, t0 + 1000, gain = 31.24), result(336_000, t0 + 2000, gain = 0.0, ignored = true))
        val line = ManifestLines.clip("S", t0, zurich, 17, "clips/ev_17_20261007_234210.wav", ev, clip, 198_000, trace)
        assertEquals(
            """{"type":"clip","session":"S","time":"2026-10-07T23:42:10.250+02:00","eventId":17,"file":"clips/ev_17_20261007_234210.wav",""" +
                """"eventStart":"2026-10-07T23:42:10.250+02:00","eventEnd":"2026-10-07T23:42:12.625+02:00","durationS":2.375,""" +
                """"lafMaxDb":71.04,"selDb":74.5,"backgroundDb":38.2,"category":"loud_vehicle","categoryScore":0.5,""" +
                """"top3":[{"label":"Motorcycle","score":0.75},{"label":"Vehicle","score":0.5},{"label":"Car","score":0.25}],""" +
                """"offsetOfEventStartInClipMs":5000,"clipDurationMs":12375,"sampleRate":16000,"truncated":false,"postRollCut":false,""" +
                """"eventStartSample":480000,"eventEndSample":594000,"clipStartSample":240000,""" +
                """"classifierTrace":[""" +
                """{"time":"2026-10-07T23:42:11.250+02:00","atMsInClip":1000,"top5":[{"label":"Vehicle","score":0.4125},""" +
                """{"label":"Car","score":0.2},{"label":"Silence","score":0.0001},{"label":"Wind","score":0},{"label":"Rain","score":0}],""" +
                """"category":"loud_vehicle","categoryScore":0.4125,"gainDb":31.2,"lafDb":52.3,"lafMaxDb":55.1,"ignored":false,"endSample":288000},""" +
                """{"time":"2026-10-07T23:42:12.250+02:00","atMsInClip":2000,"top5":[{"label":"Vehicle","score":0.4125},""" +
                """{"label":"Car","score":0.2},{"label":"Silence","score":0.0001},{"label":"Wind","score":0},{"label":"Rain","score":0}],""" +
                """"category":null,"categoryScore":null,"gainDb":0,"lafDb":52.3,"lafMaxDb":55.1,"ignored":true,"endSample":336000}]}""",
            line,
        )
        assertFalse('\n' in line)
    }

    @Test
    fun continuousAndStopLines() {
        val open = ManifestLines.continuousOpen("S", t0, zurich, "continuous/20261007_23.m4a", 6000, t0)
        assertTrue(open.startsWith("""{"type":"continuous_open","session":"S","""))
        assertTrue(""""startInputSample":6000,"sampleRate":16000,"channels":1,"codec":"AAC-LC","bitRate":64000""" in open, open)
        val close = ManifestLines.continuousClose("S", t0, zurich, "continuous/20261007_23.m4a", 16_000 * 90, 720_000, "hour")
        assertTrue(""""samples":1440000,"durationS":90,"bytes":720000,"reason":"hour"}""" in close, close)
        val gap = ManifestLines.gap("S", t0, zurich, "continuous/x.m4a", 48_000, 54_000, true)
        assertTrue(""""fromInputSample":48000,"toInputSample":54000,"durationMs":125,"filledWithSilence":true}""" in gap, gap)
        val stop = ManifestLines.sessionStop("S", t0, zurich, ManifestLines.Counts(clipsWritten = 3, continuousBlocksDropped = 2), false)
        assertTrue(stop.endsWith(""""clipsWritten":3,"clipsSkippedRate":0,"clipsDroppedBusy":0,"clipsDroppedQueue":0,"clipsDroppedStorage":0,""" +
            """"clipsWithoutEventId":0,"clipErrors":0,"continuousFiles":0,"continuousBlocksDropped":2,"storageFull":false}"""), stop)
    }

    // ---- Classifier trace and log ------------------------------------------------------------

    private fun result(endSample: Long, endMs: Long, gain: Double, ignored: Boolean = false) = ClassifierResult(
        endSample = endSample, windowSamples48k = 46_800, endEpochMs = endMs,
        top = listOf(
            LabelScore("Vehicle", 0.41249f), LabelScore("Car", 0.2f), LabelScore("Silence", 0.00011f),
            LabelScore("Wind", 0.00002f), LabelScore("Rain", 0f), LabelScore("Sixth", 0f),
        ),
        decision = if (ignored) null else CategoryDecision("loud_vehicle", 0.41249f, mapOf("loud_vehicle" to 0.41249f)),
        inputGainDb = gain, lafDb = 52.26, lafMaxDb = 55.08, ignoredInvalid = ignored,
    )

    @Test
    fun classifierLogLine() {
        val line = ManifestLines.classifierLine("20261007_234210", result(1_234_567, t0, gain = 40.0), zurich)
        assertEquals(
            """{"time":"2026-10-07T23:42:10.250+02:00","top5":[{"label":"Vehicle","score":0.4125},{"label":"Car","score":0.2},""" +
                """{"label":"Silence","score":0.0001},{"label":"Wind","score":0},{"label":"Rain","score":0}],"category":"loud_vehicle",""" +
                """"categoryScore":0.4125,"gainDb":40,"lafDb":52.3,"lafMaxDb":55.1,"ignored":false,"endSample":1234567,""" +
                """"session":"20261007_234210"}""",
            line,
        )
        assertFalse('\n' in line)
        // Small text: one line per second is about 0.3 kB, ≈ 1.1 MB per hour.
        assertTrue(line.length in 200..400, "${line.length}")
    }

    @Test
    fun classifierLogRollsOverAtTheWallClockHour() {
        val dir = Files.createTempDirectory("cls").toFile()
        try {
            ClassifierLog(dir, zurich).use { log ->
                val a = ms("2026-10-07T23:59:59.000+02:00[Europe/Zurich]")
                val b = ms("2026-10-08T00:00:00.000+02:00[Europe/Zurich]")
                assertEquals("20261007_23.jsonl", log.fileName(a))
                assertEquals(8L, log.append(a, "{\"a\":1}"))
                log.append(a + 500, "{\"a\":2}")
                log.append(b, "{\"b\":1}")
            }
            // A later measurement in the same hour appends.
            ClassifierLog(dir, zurich).use { it.append(ms("2026-10-08T00:30:00+02:00[Europe/Zurich]"), "{\"b\":2}") }
            assertEquals(listOf("20261007_23.jsonl", "20261008_00.jsonl"), dir.list()!!.sorted())
            assertEquals("{\"a\":1}\n{\"a\":2}\n", File(dir, "20261007_23.jsonl").readText())
            assertEquals("{\"b\":1}\n{\"b\":2}\n", File(dir, "20261008_00.jsonl").readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun zipContainsManifestClipsAndClassifierLogsButHourFilesOnlyOnRequest() {
        val root = Files.createTempDirectory("labor").toFile()
        try {
            val d = LaborDirs(File(root, "audio"))
            d.ensure()
            d.manifest.writeText("{}\n")
            File(d.clips, "ev_3_20261007_234210.wav").writeBytes(ByteArray(100))
            File(d.classifier, "20261007_23.jsonl").writeText("{}\n")
            File(d.continuous, "20261007_23.m4a").writeBytes(ByteArray(1000))
            val st = d.stats()
            assertEquals(1, st.clipCount); assertEquals(1, st.hourFiles); assertEquals(1, st.classifierFiles)
            assertEquals(1106L, st.usedBytes)
            assertEquals(106L, st.textAndClipBytes)
            fun entries(f: File) = java.util.zip.ZipFile(f).use { z -> z.entries().toList().map { it.name } }
            val small = File(root, "a.zip").also { d.zipTo(it, withContinuous = false) }
            assertEquals(listOf("manifest.jsonl", "clips/ev_3_20261007_234210.wav", "classifier/20261007_23.jsonl"), entries(small))
            val all = File(root, "b.zip").also { d.zipTo(it, withContinuous = true) }
            assertEquals("continuous/20261007_23.m4a", entries(all).last())
            d.deleteAll()
            assertEquals(0L, d.usedBytes())
        } finally {
            root.deleteRecursively()
        }
    }
}
