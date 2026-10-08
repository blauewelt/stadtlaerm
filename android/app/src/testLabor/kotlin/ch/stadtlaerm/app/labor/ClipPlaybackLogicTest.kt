package ch.stadtlaerm.app.labor

import ch.stadtlaerm.dsp.NoiseEvent
import ch.stadtlaerm.dsp.classify.CategoryDecision
import ch.stadtlaerm.dsp.classify.ClassifierResult
import ch.stadtlaerm.dsp.classify.LabelScore
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** v0.3.4 clip playback: manifest index, navigation, trace lookup, progress bar, WAV check. */
class ClipPlaybackLogicTest {
    private val zurich = ZoneId.of("Europe/Zurich")
    private fun ms(s: String) = ZonedDateTime.parse(s).toInstant().toEpochMilli()
    private val t0 = ms("2026-10-07T23:42:10.250+02:00[Europe/Zurich]")

    private fun event(start: Long, dur: Double = 2.375, cat: String? = "loud_vehicle") = NoiseEvent(
        startEpochMs = start, startIso = "", durationSeconds = dur, lafMaxDb = 71.04, selDb = 74.5, backgroundDb = 38.2,
        thresholdDb = 10.0, dominantCategory = cat, dominantScore = 0.5f,
        topLabels = listOf(LabelScore("Motorcycle", 0.75f), LabelScore("Vehicle", 0.5f), LabelScore("Car", 0.25f), LabelScore("X", 0.1f)),
        classifierFrames = 3, calibrationId = 4, audioSource = "UNPROCESSED", calibrated = true,
    )

    private fun result(endSample: Long, endMs: Long, gain: Double, ignored: Boolean = false) = ClassifierResult(
        endSample = endSample, windowSamples48k = 46_800, endEpochMs = endMs,
        top = listOf(LabelScore("Motorcycle", 0.6f), LabelScore("Vehicle", 0.3f), LabelScore("Car", 0.2f), LabelScore("Wind", 0.01f)),
        decision = if (ignored) null else CategoryDecision("loud_vehicle", 0.6f, mapOf("loud_vehicle" to 0.6f)),
        inputGainDb = gain, lafDb = 52.26, lafMaxDb = 55.08, ignoredInvalid = ignored,
    )

    /** A real manifest `clip` line (written by the recorder's code): event 2.375 s, 5 s pre-roll. */
    private fun clipLine(id: Long?, file: String, start: Long, eventStartSample: Long = 480_000, withTrace: Boolean = true): String {
        val clip = AssembledClip(
            eventStartSample = eventStartSample, eventEndSample = eventStartSample + 114_000, clipStartSample = eventStartSample - 240_000,
            chunks = emptyList(), length = 594_000, truncated = false, endedEarly = false,
        )
        val trace = if (!withTrace) emptyList() else listOf(
            result(clip.clipStartSample + 48_000, start - 4000, gain = 12.0),
            result(clip.clipStartSample + 288_000, start + 1000, gain = 31.24),
            result(clip.clipStartSample + 336_000, start + 2000, gain = 0.0, ignored = true),
        )
        return ManifestLines.clip("S", start, zurich, id, file, event(start), clip, 198_000, trace)
    }

    // ---- Manifest clip index ---------------------------------------------------------------------

    @Test
    fun manifestClipIndexByEventIdWithPathAndTrace() {
        val t1 = t0 + 60_000
        val lines = sequenceOf(
            """{"type":"session_start","session":"S","time":"x"}""",
            clipLine(17, "clips/ev_17_20261007_234210.wav", t0),
            """{"type":"clock","session":"S","time":"x","anchorSample":0}""",
            "not json at all {",
            clipLine(18, "clips/ev_18_20261007_234310.wav", t1, withTrace = false),
            clipLine(19, "clips/ev_19_20261007_234410.wav", t1 + 60_000), // file deleted
        )
        val files = mapOf(
            "ev_17_20261007_234210.wav" to 396_044L,
            "ev_18_20261007_234310.wav" to 300_000L,
            "ev_25_20261008_010203.wav" to 100_000L, // no manifest line
            "notes.txt" to 3L,
        )
        val index = ClipIndexReader.read(lines, files, zurich)
        assertEquals(3, index.size)
        assertEquals(796_044L, index.totalBytes)

        val e17 = assertNotNull(index.byEventId(17))
        assertEquals("clips/ev_17_20261007_234210.wav", e17.ref)
        assertEquals("ev_17_20261007_234210.wav", e17.fileName)
        assertEquals(t0, e17.eventStartMs)
        assertEquals(2.375, e17.durationS)
        assertEquals(71.04, e17.lafMaxDb)
        assertEquals("loud_vehicle", e17.category)
        assertEquals(5000L, e17.preRollMs)
        assertEquals(12_375L, e17.clipDurationMs)
        assertEquals(2375L, e17.eventSpanMs)
        assertEquals(listOf("Motorcycle", "Vehicle", "Car"), e17.top3.map { it.label })
        assertEquals(396_044L, e17.bytes)
        assertTrue(e17.hasTrace && e17.fromManifest)
        // Trace: window ends at 1 s, 6 s and 7 s in the clip; top labels, decision, gain.
        assertEquals(listOf(1000L, 6000L, 7000L), e17.trace.map { it.atMs })
        val second = e17.trace[1]
        assertEquals(listOf("Motorcycle", "Vehicle", "Car"), second.top3.map { it.label })
        assertEquals(0.6, second.top3[0].score)
        assertEquals("loud_vehicle", second.category)
        assertEquals(31.2, second.gainDb)
        assertNull(e17.trace[2].category)
        assertTrue(e17.trace[2].ignored)

        assertFalse(assertNotNull(index.byEventId(18)).hasTrace)
        assertNull(index.byEventId(19)) // its file is gone
        val orphan = assertNotNull(index.byEventId(25))
        assertFalse(orphan.fromManifest)
        assertEquals("clips/ev_25_20261008_010203.wav", orphan.ref)
        assertEquals(ms("2026-10-08T01:02:03+02:00[Europe/Zurich]"), orphan.eventStartMs)

        // Chart lookup: event id + start time must agree (another database's ids do not match).
        assertEquals("clips/ev_17_20261007_234210.wav", index.clipRefFor(17, t0 + 400))
        assertNull(index.clipRefFor(17, t0 + 3_600_000))
        assertNull(index.clipRefFor(99, t0))
        assertEquals(e17, index.byRef("clips/ev_17_20261007_234210.wav"))
        // Oldest first.
        assertEquals(listOf(17L, 18L, 25L), index.entries.map { it.eventId })
    }

    @Test
    fun clipWithoutEventIdAndTraceWithoutAtMs() {
        // A clip whose event never reached the database, and a trace entry from endSample only.
        val o = MiniJson.obj(clipLine(null, "clips/ev_na_20261007_234210.wav", t0))!!
        assertNull(ClipIndexReader.entry(o)!!.eventId)
        val line = """{"type":"clip","file":"clips/x.wav","clipStartSample":96000,"classifierTrace":[{"endSample":144000,"top5":[]}]}"""
        val e = ClipIndexReader.read(sequenceOf(line), null, zurich).entries.single()
        assertEquals(listOf(1000L), e.trace.map { it.atMs })
        assertNull(e.eventStartMs)
        assertNull(e.preRollMs)
        // A later line for the same file wins.
        val twice = ClipIndexReader.read(sequenceOf(clipLine(1, "clips/a.wav", t0), clipLine(2, "clips/a.wav", t0)), null, zurich)
        assertEquals(listOf(2L), twice.entries.map { it.eventId })
    }

    @Test
    fun miniJsonParsesWhatTheWriterWrites() {
        val written = Json().put("s", "a\"b\\c\nd\u0001ü").put("n", Double.NaN).put("x", 1.5).put("i", -3)
            .put("b", true).putStrings("e", listOf("x", "")).putObjects("o", listOf(Json().put("k", 2))).build()
        val o = MiniJson.obj(written)!!
        assertEquals("a\"b\\c\nd\u0001ü", o["s"])
        assertNull(o["n"])
        assertEquals(1.5, o["x"])
        assertEquals(-3.0, o["i"])
        assertEquals(true, o["b"])
        assertEquals(listOf("x", ""), o["e"])
        assertEquals(listOf(mapOf("k" to 2.0)), o["o"])
        assertNull(MiniJson.obj("[1,2]"))
        assertNull(MiniJson.obj("""{"a":1"""))
        assertNull(MiniJson.obj("""{"a":1} x"""))
        assertEquals(emptyMap<String, Any?>(), MiniJson.obj(" { } "))
    }

    // ---- Neighbour navigation --------------------------------------------------------------------

    @Test
    fun neighbourNavigationFollowsEventTime() {
        val a = clipLine(1, "clips/a.wav", t0)
        val b = clipLine(2, "clips/b.wav", t0 + 10_000)
        val c = clipLine(3, "clips/c.wav", t0 + 20_000)
        val index = ClipIndexReader.read(sequenceOf(c, a, b), null, zurich)
        // Given out of order (and with a duplicate): stepped through by event time.
        val p = ClipPlaylist(listOf("clips/c.wav", "clips/a.wav", "clips/b.wav", "clips/a.wav"), index)
        assertEquals(listOf("clips/a.wav", "clips/b.wav", "clips/c.wav"), p.refs)
        assertNull(p.previous("clips/a.wav"))
        assertEquals("clips/b.wav", p.next("clips/a.wav"))
        assertEquals("clips/a.wav", p.previous("clips/b.wav"))
        assertEquals("clips/c.wav", p.next("clips/b.wav"))
        assertNull(p.next("clips/c.wav"))
        // Only clips of the window are in the list: the chart's window may hold just two of them.
        val window = ClipPlaylist(listOf("clips/c.wav", "clips/a.wav"), index)
        assertEquals("clips/c.wav", window.next("clips/a.wav"))
        // Unknown reference: no neighbours. Clips without a time go last.
        assertNull(p.next("clips/zzz.wav"))
        assertNull(p.previous("clips/zzz.wav"))
        val withUnknown = ClipPlaylist(listOf("clips/zzz.wav", "clips/b.wav"), index)
        assertEquals(listOf("clips/b.wav", "clips/zzz.wav"), withUnknown.refs)
        // A single clip: no neighbours.
        val one = ClipPlaylist(listOf("clips/b.wav"), index)
        assertNull(one.next("clips/b.wav")); assertNull(one.previous("clips/b.wav"))
    }

    // ---- Trace lookup ----------------------------------------------------------------------------

    private fun t(at: Long) = TraceEntry(at, emptyList(), "road_traffic", 0.5, 0.0, 50.0, 52.0, false)

    @Test
    fun traceLookupForPlaybackPosition() {
        val trace = listOf(t(1000), t(2000), t(3000), t(6000))
        // The run whose window (≈ 1 s) ends at or after the position: the second being heard.
        assertEquals(1000L, TraceLookup.at(trace, 0)?.atMs)
        assertEquals(1000L, TraceLookup.at(trace, 999)?.atMs)
        assertEquals(1000L, TraceLookup.at(trace, 1000)?.atMs)
        assertEquals(2000L, TraceLookup.at(trace, 1001)?.atMs)
        assertEquals(3000L, TraceLookup.at(trace, 2500)?.atMs)
        // A gap (no runs 3–5 s, e.g. ignored windows not logged): nothing for 3.5 s … 5 s.
        assertEquals(3000L, TraceLookup.at(trace, 3999)?.atMs) // just after the last run: still shown
        assertNull(TraceLookup.at(trace, 4500))
        assertEquals(6000L, TraceLookup.at(trace, 5000)?.atMs)
        // After the last run: shown for up to 1 s, then nothing.
        assertEquals(6000L, TraceLookup.at(trace, 6800)?.atMs)
        assertNull(TraceLookup.at(trace, 7200))
        assertNull(TraceLookup.at(emptyList(), 0))
        // Before the first window ends (clip starts mid-window): the first run.
        assertEquals(500L, TraceLookup.at(listOf(t(500)), 0)?.atMs)
    }

    // ---- Progress bar geometry -------------------------------------------------------------------

    @Test
    fun progressBarGeometry() {
        // 12.375 s clip: 5 s pre-roll, 2.375 s event, 5 s post-roll.
        val b = ClipBar.of(12_375, 5_000, 2_375)
        assertEquals(5_000f / 12_375, b.eventStart, 1e-6f)
        assertEquals(7_375f / 12_375, b.eventEnd, 1e-6f)
        assertEquals(b.eventStart, b.preRoll)
        assertEquals(5_000f / 12_375, b.postRoll, 1e-5f)
        assertFalse(b.truncated)
        // Short pre-roll (event right after the start of the measurement).
        val early = ClipBar.of(8_000, 1_000, 2_000)
        assertEquals(0.125f, early.eventStart, 1e-6f)
        assertEquals(0.375f, early.eventEnd, 1e-6f)
        // Truncated at 60 s: the event runs past the end, no post-roll.
        val cut = ClipBar.of(60_000, 5_000, 120_000, flaggedTruncated = true)
        assertEquals(5_000f / 60_000, cut.eventStart, 1e-6f)
        assertEquals(1f, cut.eventEnd)
        assertEquals(0f, cut.postRoll)
        assertTrue(cut.truncated)
        // Event longer than the clip even without the flag (e.g. post-roll cut at stop) → truncated.
        assertTrue(ClipBar.of(6_000, 5_000, 2_000).truncated)
        assertEquals(1f, ClipBar.of(6_000, 5_000, 2_000).eventEnd)
        // Unknown timing (no manifest line): the whole clip.
        assertEquals(ClipBar.UNKNOWN, ClipBar.of(10_000, null, null))
        assertEquals(ClipBar.UNKNOWN, ClipBar.of(0, 5_000, 1_000))
        // Unknown event length: from the event start to the end.
        assertEquals(ClipBar(0.5f, 1f, false), ClipBar.of(10_000, 5_000, null))
    }

    @Test
    fun progressBarFromARealManifestLine() {
        val e = ClipIndexReader.read(sequenceOf(clipLine(17, "clips/a.wav", t0)), null, zurich).entries.single()
        val b = ClipBar.of(e.clipDurationMs!!, e.preRollMs, e.eventSpanMs, e.truncated)
        assertEquals(5_000f / 12_375, b.eventStart, 1e-6f)
        assertEquals(7_375f / 12_375, b.eventEnd, 1e-6f)
    }

    // ---- WAV check («Clip nicht gefunden») ---------------------------------------------------

    @Test
    fun wavCheckAcceptsClipsAndRejectsBrokenFiles() {
        val dir = Files.createTempDirectory("clips").toFile()
        try {
            val ok = File(dir, "ok.wav").also { Wav.write(it, FloatArray(16_000) { 0.1f }, 16_000, 16_000) }
            val info = assertNotNull(WavInfo.read(ok))
            assertEquals(16_000, info.sampleRate)
            assertEquals(1, info.channels)
            assertEquals(16, info.bitsPerSample)
            assertEquals(1000L, info.durationMs)
            // Cut short: plays what is there.
            val cut = File(dir, "cut.wav").also { it.writeBytes(ok.readBytes().copyOf(44 + 16_000)) }
            assertEquals(500L, WavInfo.read(cut)?.durationMs)
            assertNull(WavInfo.read(File(dir, "missing.wav")))
            assertNull(WavInfo.read(File(dir, "junk.wav").also { it.writeBytes(ByteArray(100) { 7 }) }))
            assertNull(WavInfo.read(File(dir, "header.wav").also { it.writeBytes(ok.readBytes().copyOf(44)) })) // no audio
            assertNull(WavInfo.read(File(dir, "empty.wav").also { it.writeBytes(ByteArray(0)) }))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun readsClipsFolderAndManifestFromDisk() {
        val root = Files.createTempDirectory("audio").toFile()
        try {
            val d = LaborDirs(root).also { it.ensure() }
            assertEquals(0, ClipIndexReader.read(d.manifest, d.clips, zurich).size) // nothing yet
            Wav.write(File(d.clips, "ev_17_20261007_234210.wav"), FloatArray(1600), 1600, 16_000)
            ManifestFile.append(d.manifest, clipLine(17, "clips/ev_17_20261007_234210.wav", t0))
            val index = ClipIndexReader.read(d.manifest, d.clips, zurich)
            assertEquals(Wav.fileBytes(1600), index.byEventId(17)?.bytes)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun currentNightForTheClipList() {
        fun at(s: String) = ClipNights.currentNightOf(ms("$s[Europe/Zurich]"), zurich)
        assertEquals(LocalDate.of(2026, 10, 8), at("2026-10-08T23:00:00+02:00")) // tonight
        assertEquals(LocalDate.of(2026, 10, 8), at("2026-10-08T22:00:00+02:00"))
        assertEquals(LocalDate.of(2026, 10, 7), at("2026-10-08T03:00:00+02:00")) // still last night
        assertEquals(LocalDate.of(2026, 10, 7), at("2026-10-08T15:00:00+02:00")) // the night that ended last
        assertEquals(LocalDate.of(2026, 10, 7), at("2026-10-08T21:59:00+02:00"))
    }
}
