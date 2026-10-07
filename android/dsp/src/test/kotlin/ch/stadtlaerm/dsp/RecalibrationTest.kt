package ch.stadtlaerm.dsp

import ch.stadtlaerm.dsp.calibration.EventFloor
import ch.stadtlaerm.dsp.calibration.Recalibration
import ch.stadtlaerm.dsp.classify.LabelScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecalibrationTest {
    private val default = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB

    private fun minute(laeq: Double, calibrationId: Long? = null, offset: Double = default, source: String = "UNPROCESSED") = MinuteRecord(
        0L, "2026-10-02T23:10:00+02:00", 60.0, laeq, laeq + 5, laeq - 5, laeq + 4, laeq + 2, laeq, laeq - 2, 3,
        "road_traffic", mapOf("road_traffic" to 1.0), 60, calibrationId, offset, source, calibrationId != null,
    )

    private fun event(max: Double, calibrationId: Long? = null) = NoiseEvent(
        0L, "2026-10-02T23:10:00.000+02:00", 2.0, max, max + 1, max - 20, 10.0, "road_traffic", 0.8f,
        listOf(LabelScore("Car", 0.8f)), 2, calibrationId, "UNPROCESSED", calibrationId != null, minLevelDb = 30.0,
    )

    private val a = Recalibration.Target(1L, 118.0, "UNPROCESSED")
    private val b = Recalibration.Target(2L, 121.95, "UNPROCESSED")
    private val c = Recalibration.Target(3L, 115.5, "UNPROCESSED")

    @Test
    fun firstRecalibrationShiftsAllLevelsAndKeepsOriginals() {
        val m = minute(40.0)
        val r = Recalibration.recalibrate(m, b)
        val delta = 121.95 - default
        assertEquals(40.0 + delta, r.laeqDb, 1e-9)
        assertEquals(45.0 + delta, r.lafMaxDb, 1e-9)
        assertEquals(35.0 + delta, r.lafMinDb, 1e-9)
        assertEquals(44.0 + delta, r.l1Db, 1e-9)
        assertEquals(42.0 + delta, r.l10Db, 1e-9)
        assertEquals(40.0 + delta, r.l50Db, 1e-9)
        assertEquals(38.0 + delta, r.l90Db, 1e-9)
        assertEquals(m.levels, r.original)
        assertEquals("default", r.recalibratedFromId)
        assertEquals(default, r.recalibrationOffsetDb)
        assertEquals(2L, r.calibrationId)
        assertEquals(121.95, r.calibrationOffsetDb)
        assertTrue(r.calibrated)
        assertTrue(r.recalibrated)
        // The event count stays as recorded.
        assertEquals(3, r.eventCount)
    }

    @Test
    fun negativeShiftAndCalibratedOrigin() {
        val m = minute(50.0, calibrationId = 1L, offset = 118.0)
        val r = Recalibration.recalibrate(m, c)
        assertEquals(50.0 - 2.5, r.laeqDb, 1e-9)
        assertEquals("1", r.recalibratedFromId)
        assertEquals(118.0, r.recalibrationOffsetDb)
    }

    @Test
    fun missingLevelsStayMissing() {
        val m = minute(Double.NaN)
        val r = Recalibration.recalibrate(m, b)
        assertTrue(r.laeqDb.isNaN())
        assertTrue(r.original!!.laeqDb.isNaN())
    }

    @Test
    fun recalibratingTwiceWithTheSameCalibrationChangesNothing() {
        val once = Recalibration.recalibrate(minute(40.0), b)
        assertEquals(once, Recalibration.recalibrate(once, b))
        // Another calibration with the same offset: same values, only the id changes.
        val same = Recalibration.recalibrate(once, b.copy(calibrationId = 9L))
        assertEquals(once.copy(calibrationId = 9L), same)
    }

    @Test
    fun aToBToCEqualsAToC() {
        val m = minute(41.3, calibrationId = 1L, offset = 118.0)
        val viaB = Recalibration.recalibrate(Recalibration.recalibrate(m, b), c)
        val direct = Recalibration.recalibrate(m, c)
        assertEquals(direct, viaB) // exactly, not just within rounding: always computed from the originals
        assertEquals("1", viaB.recalibratedFromId)
        assertEquals(118.0, viaB.recalibrationOffsetDb)
        assertEquals(m.levels, viaB.original)
        // Many hops never compound.
        var x = m
        repeat(50) { i -> x = Recalibration.recalibrate(x, if (i % 2 == 0) b else c) }
        assertEquals(Recalibration.recalibrate(m, c), x)
    }

    @Test
    fun backToTheOriginalCalibrationRestoresTheRecord() {
        val m = minute(41.3, calibrationId = 1L, offset = 118.0)
        val back = Recalibration.recalibrate(Recalibration.recalibrate(m, b), a)
        assertEquals(m, back)
        assertNull(back.original)
        assertFalse(back.recalibrated)
    }

    @Test
    fun eventsUseTheirCalibrationsOffsetOnlyTheFirstTime() {
        val e = event(60.0)
        val r = Recalibration.recalibrate(e, default, b)
        val delta = 121.95 - default
        assertEquals(60.0 + delta, r.lafMaxDb, 1e-9)
        assertEquals(61.0 + delta, r.selDb, 1e-9)
        assertEquals(40.0 + delta, r.backgroundDb, 1e-9)
        assertEquals(10.0, r.thresholdDb) // relative to the background: unchanged
        assertEquals(30.0, r.minLevelDb) // the floor in force at the time, as recorded
        assertEquals(EventLevels(60.0, 61.0, 40.0), r.original)
        assertEquals("default", r.recalibratedFromId)
        assertEquals(default, r.recalibrationOffsetDb)
        assertEquals(2L, r.calibrationId)
        assertTrue(r.calibrated)
        // Second hop: the passed offset (now that of b) is ignored, the originals are used.
        val viaB = Recalibration.recalibrate(r, 121.95, c)
        assertEquals(Recalibration.recalibrate(e, default, c), viaB)
        assertEquals(r, Recalibration.recalibrate(r, 121.95, b))
    }

    @Test
    fun scopeIsSameSourceAndAnotherCalibration() {
        assertTrue(Recalibration.inScope("UNPROCESSED", null, b))
        assertTrue(Recalibration.inScope("UNPROCESSED", 1L, b))
        assertFalse(Recalibration.inScope("UNPROCESSED", 2L, b))
        assertFalse(Recalibration.inScope("VOICE_RECOGNITION", null, b))
        assertFalse(Recalibration.inScope("VOICE_RECOGNITION", 1L, b))
        assertTrue(Recalibration.inScope("VOICE_RECOGNITION", 1L, b.copy(audioSource = "VOICE_RECOGNITION")))
        assertEquals("default", Recalibration.idText(null))
        assertEquals("12", Recalibration.idText(12L))
    }

    @Test
    fun nightSummaryFlagsRecalibratedNights() {
        val zone = java.time.ZoneId.of("Europe/Zurich")
        val t = java.time.LocalDateTime.of(2026, 10, 2, 23, 0).atZone(zone).toInstant().toEpochMilli()
        val m = minute(40.0).copy(startEpochMs = t)
        val plain = NightSummarizer.summarize(listOf(m), emptyList(), zone).single()
        assertFalse(plain.anyRecalibrated); assertFalse(plain.allCalibrated)
        val recal = NightSummarizer.summarize(listOf(Recalibration.recalibrate(m, b)), emptyList(), zone).single()
        assertTrue(recal.anyRecalibrated); assertTrue(recal.allCalibrated)
    }

    // ---- Event floor follows the calibration ---------------------------------------------------

    private fun adjust(floor: Double, prev: Double, new: Double) = EventFloor.adjust(floor, prev, new, 20.0, 70.0)

    @Test
    fun firstCalibrationFromTheDefault() {
        val a = adjust(30.0, default, default + 9.6)
        assertEquals(39.5, a.newFloorDb) // 39.6 rounded to 0.5 dB
        assertEquals(9.6, a.deltaDb, 1e-9)
        assertTrue(a.changed); assertFalse(a.clamped)
    }

    @Test
    fun positiveAndNegativeDelta() {
        assertEquals(42.0, adjust(30.0, 118.0, 130.0).newFloorDb)
        val down = adjust(39.5, 121.95, default)
        assertEquals(30.0, down.newFloorDb) // 29.9 → 30.0
        assertEquals(default - 121.95, down.deltaDb, 1e-9)
        assertEquals(25.0, adjust(30.0, 118.0, 113.0).newFloorDb)
    }

    @Test
    fun roundingToHalfDecibels() {
        assertEquals(30.0, adjust(30.0, 100.0, 100.2).newFloorDb)
        assertEquals(30.5, adjust(30.0, 100.0, 100.25).newFloorDb) // ties go up
        assertEquals(30.5, adjust(30.0, 100.0, 100.7).newFloorDb)
        assertEquals(31.0, adjust(30.0, 100.0, 100.8).newFloorDb)
        assertEquals(29.5, adjust(30.0, 100.0, 99.6).newFloorDb)
        val none = adjust(30.0, 100.0, 100.1)
        assertFalse(none.changed)
        assertEquals(37.5, EventFloor.roundToHalf(37.3))
        assertEquals(37.0, EventFloor.roundToHalf(37.2))
    }

    @Test
    fun clampingToTheSettingsRange() {
        val hi = adjust(65.0, default, default + 9.6)
        assertEquals(74.5, hi.unclampedFloorDb)
        assertEquals(70.0, hi.newFloorDb)
        assertTrue(hi.clamped)
        val lo = adjust(25.0, 120.0, 110.0)
        assertEquals(15.0, lo.unclampedFloorDb)
        assertEquals(20.0, lo.newFloorDb)
        assertTrue(lo.clamped)
        assertFalse(adjust(60.0, 100.0, 110.0).clamped) // exactly 70 is inside the range
    }

    @Test
    fun signedDelta() {
        assertEquals("+9.6", EventFloor.signed(9.6))
        assertEquals("−3.2", EventFloor.signed(-3.2))
        assertEquals("±0.0", EventFloor.signed(0.0))
        assertEquals("±0.0", EventFloor.signed(-0.04))
    }
}
