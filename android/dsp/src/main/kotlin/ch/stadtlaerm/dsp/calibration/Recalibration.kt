package ch.stadtlaerm.dsp.calibration

import ch.stadtlaerm.dsp.MinuteRecord
import ch.stadtlaerm.dsp.NoiseEvent
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Re-evaluating stored measurements with a newer calibration («nachträglich kalibriert»).
 *
 * Levels are stored with the calibration offset already applied (`level = raw + offset`). For the
 * same phone and the same audio source, a record measured with offset O1 corresponds under a new
 * offset O2 to `level − O1 + O2`.
 *
 * A record is always corrected relative to its ORIGINAL values: the first re-evaluation keeps the
 * stored levels as [MinuteRecord.original] together with the id and offset they were measured with;
 * every later re-evaluation computes from those, never from an already corrected value. So
 * re-evaluating A→B→C gives exactly the same record as A→C, and repeating a re-evaluation with the
 * same calibration changes nothing.
 */
object Recalibration {
    /** [MinuteRecord.recalibratedFromId] of records measured without a calibration (CDD default). */
    const val FROM_DEFAULT = "default"

    /** The calibration to re-evaluate with. Always a real calibration (data becomes «calibrated»). */
    data class Target(val calibrationId: Long, val offsetDb: Double, val audioSource: String)

    fun idText(calibrationId: Long?): String = calibrationId?.toString() ?: FROM_DEFAULT

    /**
     * Records that a re-evaluation with [target] changes: same audio source (UNPROCESSED and
     * VOICE_RECOGNITION are calibrated separately), measured without or with another calibration.
     */
    fun inScope(audioSource: String, calibrationId: Long?, target: Target): Boolean =
        audioSource == target.audioSource && calibrationId != target.calibrationId

    /** The minute re-evaluated with [target] (see the class comment). */
    fun recalibrate(m: MinuteRecord, target: Target): MinuteRecord {
        val first = m.original == null || m.recalibratedFromId == null || m.recalibrationOffsetDb == null
        val original = if (first) m.levels else m.original!!
        val fromId = if (first) idText(m.calibrationId) else m.recalibratedFromId!!
        val fromOffset = if (first) m.calibrationOffsetDb else m.recalibrationOffsetDb!!
        val restore = fromId == target.calibrationId.toString()
        val l = if (restore) original else original.shifted(target.offsetDb - fromOffset)
        return m.copy(
            laeqDb = l.laeqDb, lafMaxDb = l.lafMaxDb, lafMinDb = l.lafMinDb, l1Db = l.l1Db,
            l10Db = l.l10Db, l50Db = l.l50Db, l90Db = l.l90Db,
            calibrationId = target.calibrationId, calibrationOffsetDb = target.offsetDb, calibrated = true,
            // Back on the calibration it was measured with: the record is original again.
            original = if (restore) null else original,
            recalibratedFromId = if (restore) null else fromId,
            recalibrationOffsetDb = if (restore) null else fromOffset,
        )
    }

    /**
     * The event re-evaluated with [target]. Events store no offset of their own:
     * [currentOffsetDb] is the offset of the event's current calibration (the default if it has
     * none); it is only used the first time.
     */
    fun recalibrate(e: NoiseEvent, currentOffsetDb: Double, target: Target): NoiseEvent {
        val first = e.original == null || e.recalibratedFromId == null || e.recalibrationOffsetDb == null
        val original = if (first) e.levels else e.original!!
        val fromId = if (first) idText(e.calibrationId) else e.recalibratedFromId!!
        val fromOffset = if (first) currentOffsetDb else e.recalibrationOffsetDb!!
        val restore = fromId == target.calibrationId.toString()
        val l = if (restore) original else original.shifted(target.offsetDb - fromOffset)
        return e.copy(
            lafMaxDb = l.lafMaxDb, selDb = l.selDb, backgroundDb = l.backgroundDb,
            calibrationId = target.calibrationId, calibrated = true,
            original = if (restore) null else original,
            recalibratedFromId = if (restore) null else fromId,
            recalibrationOffsetDb = if (restore) null else fromOffset,
        )
    }
}

/**
 * The event floor («Mindestpegel», an absolute LAFmax) follows the calibration: it was chosen on
 * levels measured with the previous offset, so when a calibration with a different offset becomes
 * active, the same physical threshold is `floor + (new − previous)`.
 */
object EventFloor {
    data class Adjustment(
        val previousOffsetDb: Double,
        val newOffsetDb: Double,
        val oldFloorDb: Double,
        /** Old floor + delta, rounded to 0.5 dB, before clamping to the setting's range. */
        val unclampedFloorDb: Double,
        val newFloorDb: Double,
    ) {
        val deltaDb: Double get() = newOffsetDb - previousOffsetDb
        val clamped: Boolean get() = newFloorDb != unclampedFloorDb
        val changed: Boolean get() = newFloorDb != oldFloorDb
    }

    /** Rounds to the nearest 0.5 dB (ties upwards). */
    fun roundToHalf(v: Double): Double = (v * 2).roundToLong() / 2.0

    fun adjust(floorDb: Double, previousOffsetDb: Double, newOffsetDb: Double, minDb: Double, maxDb: Double): Adjustment {
        val unclamped = roundToHalf(floorDb + (newOffsetDb - previousOffsetDb))
        return Adjustment(previousOffsetDb, newOffsetDb, floorDb, unclamped, unclamped.coerceIn(minDb, maxDb))
    }

    /** "+9.6", "−3.2" (minus sign), "±0.0". */
    fun signed(deltaDb: Double, decimals: Int = 1): String {
        val text = String.format(java.util.Locale.ROOT, "%.${decimals}f", abs(deltaDb))
        return when {
            text.trimStart('0', '.').isEmpty() -> "±$text"
            deltaDb > 0 -> "+$text"
            else -> "−$text"
        }
    }
}
