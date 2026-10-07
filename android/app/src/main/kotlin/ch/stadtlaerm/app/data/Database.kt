package ch.stadtlaerm.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import ch.stadtlaerm.dsp.EventLevels
import ch.stadtlaerm.dsp.MinuteLevels
import ch.stadtlaerm.dsp.MinuteRecord
import ch.stadtlaerm.dsp.NoiseEvent
import ch.stadtlaerm.dsp.classify.LabelScore
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

// Only aggregates are stored. There is deliberately no table or column that could hold audio.

@Entity(tableName = "minutes", indices = [Index("startEpochMs")])
data class MinuteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long,
    val startIso: String,
    val durationSeconds: Double,
    /** Level fields are null when the minute had no valid audio (microphone silenced). */
    val laeqDb: Double?,
    val lafMaxDb: Double?,
    val lafMinDb: Double?,
    val l1Db: Double?,
    val l10Db: Double?,
    val l50Db: Double?,
    val l90Db: Double?,
    val eventCount: Int,
    val dominantCategory: String?,
    /** JSON object bucket id → share. */
    val categorySharesJson: String,
    val classifierFrames: Int,
    val calibrationId: Long?,
    val calibrationOffsetDb: Double,
    val audioSource: String,
    val calibrated: Boolean,
    /** Seconds of valid (not silenced) audio; levels refer to these only. */
    val validSeconds: Double,
    /** validSeconds / durationSeconds. */
    val coverage: Double,
    val clockCorrections: Int,
    // v4 (v0.3.2): re-evaluation with a later calibration («nachträglich kalibriert»). The first
    // re-evaluation copies the level columns into orig_*; later ones always compute from orig_*.
    @ColumnInfo(name = "orig_laeq_db") val origLaeqDb: Double? = null,
    @ColumnInfo(name = "orig_lafmax_db") val origLafMaxDb: Double? = null,
    @ColumnInfo(name = "orig_lafmin_db") val origLafMinDb: Double? = null,
    @ColumnInfo(name = "orig_l1_db") val origL1Db: Double? = null,
    @ColumnInfo(name = "orig_l10_db") val origL10Db: Double? = null,
    @ColumnInfo(name = "orig_l50_db") val origL50Db: Double? = null,
    @ColumnInfo(name = "orig_l90_db") val origL90Db: Double? = null,
    /** Calibration id (or "default") the orig_* values were measured with; null if never re-evaluated. */
    @ColumnInfo(name = "recalibrated_from_id") val recalibratedFromId: String? = null,
    /** Offset the orig_* values were measured with. */
    @ColumnInfo(name = "recalibration_offset_db") val recalibrationOffsetDb: Double? = null,
)

@Entity(tableName = "events", indices = [Index("startEpochMs")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long,
    val startIso: String,
    val durationSeconds: Double,
    val lafMaxDb: Double,
    val selDb: Double,
    val backgroundDb: Double,
    val thresholdDb: Double,
    val dominantCategory: String?,
    val dominantScore: Float,
    /** JSON array of {label, score}. */
    val topLabelsJson: String,
    val classifierFrames: Int,
    val calibrationId: Long?,
    val audioSource: String,
    val calibrated: Boolean,
    /** LAFmax floor in force when the event was detected (v3); null for events from before v0.3.0. */
    @ColumnInfo(name = "min_level_db") val minLevelDb: Double? = null,
    // v4 (v0.3.2): see MinuteEntity. Events store no offset of their own; it is that of their
    // calibration (calibrationId), or the default without one.
    @ColumnInfo(name = "orig_lafmax_db") val origLafMaxDb: Double? = null,
    @ColumnInfo(name = "orig_sel_db") val origSelDb: Double? = null,
    @ColumnInfo(name = "orig_background_db") val origBackgroundDb: Double? = null,
    @ColumnInfo(name = "recalibrated_from_id") val recalibratedFromId: String? = null,
    @ColumnInfo(name = "recalibration_offset_db") val recalibrationOffsetDb: Double? = null,
)

@Entity(tableName = "calibrations", indices = [Index(value = ["deviceModel", "audioSource"])])
data class CalibrationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deviceModel: String,
    val audioSource: String,
    val offsetDb: Double,
    /** reference_meter | acoustic_calibrator | manual | reset_default */
    val method: String,
    val createdEpochMs: Long,
    val createdIso: String,
    val notes: String,
    val referenceDb: Double? = null,
    val measuredRawDb: Double? = null,
    val stdDevDb: Double? = null,
    val durationSeconds: Double? = null,
    val calibratorNominalDb: Double? = null,
    val toneFrequencyHz: Double? = null,
    val tonality: Double? = null,
    val warnings: String = "",
) {
    val isCalibrated: Boolean get() = method != METHOD_RESET

    companion object {
        const val METHOD_REFERENCE = "reference_meter"
        const val METHOD_CALIBRATOR = "acoustic_calibrator"
        const val METHOD_MANUAL = "manual"
        const val METHOD_RESET = "reset_default"
    }
}

@Dao
interface MeasurementDao {
    @Insert suspend fun insertMinute(m: MinuteEntity): Long
    @Insert suspend fun insertEvent(e: EventEntity): Long

    @Query("SELECT * FROM minutes ORDER BY startEpochMs ASC")
    suspend fun allMinutes(): List<MinuteEntity>

    @Query("SELECT * FROM events ORDER BY startEpochMs ASC")
    suspend fun allEvents(): List<EventEntity>

    @Query("SELECT * FROM minutes ORDER BY startEpochMs ASC")
    fun minutesFlow(): Flow<List<MinuteEntity>>

    @Query("SELECT * FROM events ORDER BY startEpochMs ASC")
    fun eventsFlow(): Flow<List<EventEntity>>

    /** Minutes starting in [fromMs, toMs). */
    @Query("SELECT * FROM minutes WHERE startEpochMs >= :fromMs AND startEpochMs < :toMs ORDER BY startEpochMs ASC")
    fun minutesBetween(fromMs: Long, toMs: Long): Flow<List<MinuteEntity>>

    /** Events starting in [fromMs, toMs). */
    @Query("SELECT * FROM events WHERE startEpochMs >= :fromMs AND startEpochMs < :toMs ORDER BY startEpochMs ASC")
    fun eventsBetween(fromMs: Long, toMs: Long): Flow<List<EventEntity>>

    /** End (ms) of the last minute with valid audio that starts before [t], or null. */
    @Query("SELECT MAX(startEpochMs + CAST(durationSeconds * 1000 AS INTEGER)) FROM minutes WHERE startEpochMs < :t AND coverage >= 0.5 AND laeqDb IS NOT NULL")
    fun lastValidEndBefore(t: Long): Flow<Long?>

    /** Start (ms) of the first minute with valid audio that starts at or after [t], or null. */
    @Query("SELECT MIN(startEpochMs) FROM minutes WHERE startEpochMs >= :t AND coverage >= 0.5 AND laeqDb IS NOT NULL")
    fun firstValidStartAfter(t: Long): Flow<Long?>

    @Query("SELECT * FROM events ORDER BY startEpochMs DESC LIMIT :limit")
    fun recentEvents(limit: Int): Flow<List<EventEntity>>

    @Query("SELECT COUNT(*) FROM minutes")
    fun minuteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM events")
    fun eventCount(): Flow<Int>

    @Query("DELETE FROM minutes") suspend fun clearMinutes()
    @Query("DELETE FROM events") suspend fun clearEvents()

    // ---- Re-evaluation with a later calibration (v0.3.2) -------------------------------------

    @Query("SELECT COUNT(*) FROM minutes WHERE " + RecalibrationSql.SCOPE)
    fun minutesToRecalibrate(source: String, calibrationId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM events WHERE " + RecalibrationSql.SCOPE)
    fun eventsToRecalibrate(source: String, calibrationId: Long): Flow<Int>

    @Query("SELECT * FROM minutes WHERE " + RecalibrationSql.SCOPE + " ORDER BY id")
    suspend fun minutesInScope(source: String, calibrationId: Long): List<MinuteEntity>

    @Query("SELECT * FROM events WHERE " + RecalibrationSql.SCOPE + " ORDER BY id")
    suspend fun eventsInScope(source: String, calibrationId: Long): List<EventEntity>

    @Update suspend fun updateMinutes(m: List<MinuteEntity>)
    @Update suspend fun updateEvents(e: List<EventEntity>)
}

@Dao
interface CalibrationDao {
    @Insert suspend fun insert(c: CalibrationEntity): Long

    @Query("SELECT * FROM calibrations WHERE deviceModel = :model AND audioSource = :source ORDER BY createdEpochMs DESC, id DESC LIMIT 1")
    suspend fun latest(model: String, source: String): CalibrationEntity?

    @Query("SELECT * FROM calibrations WHERE deviceModel = :model AND audioSource = :source ORDER BY createdEpochMs DESC, id DESC LIMIT 1")
    fun latestFlow(model: String, source: String): Flow<CalibrationEntity?>

    @Query("SELECT * FROM calibrations ORDER BY createdEpochMs DESC, id DESC")
    fun historyFlow(): Flow<List<CalibrationEntity>>

    @Query("SELECT * FROM calibrations ORDER BY createdEpochMs ASC, id ASC")
    suspend fun all(): List<CalibrationEntity>

    @Query("SELECT * FROM calibrations WHERE id = :id")
    suspend fun byId(id: Long): CalibrationEntity?
}

@Database(entities = [MinuteEntity::class, EventEntity::class, CalibrationEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun measurements(): MeasurementDao
    abstract fun calibrations(): CalibrationDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "stadtlaerm.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}

// ---- Mapping between DSP records and entities ----------------------------------------------

object Mappers {
    fun MinuteRecord.toEntity() = MinuteEntity(
        startEpochMs = startEpochMs, startIso = startIso, durationSeconds = durationSeconds,
        laeqDb = laeqDb.orNull(), lafMaxDb = lafMaxDb.orNull(), lafMinDb = lafMinDb.orNull(), l1Db = l1Db.orNull(),
        l10Db = l10Db.orNull(), l50Db = l50Db.orNull(), l90Db = l90Db.orNull(),
        eventCount = eventCount, dominantCategory = dominantCategory,
        categorySharesJson = buildJsonObject { categoryShares.forEach { (k, v) -> put(k, v) } }.toString(),
        classifierFrames = classifierFrames, calibrationId = calibrationId,
        calibrationOffsetDb = calibrationOffsetDb, audioSource = audioSource, calibrated = calibrated,
        validSeconds = validSeconds, coverage = coverage, clockCorrections = clockCorrections,
        origLaeqDb = original?.laeqDb?.orNull(), origLafMaxDb = original?.lafMaxDb?.orNull(),
        origLafMinDb = original?.lafMinDb?.orNull(), origL1Db = original?.l1Db?.orNull(),
        origL10Db = original?.l10Db?.orNull(), origL50Db = original?.l50Db?.orNull(), origL90Db = original?.l90Db?.orNull(),
        recalibratedFromId = recalibratedFromId, recalibrationOffsetDb = recalibrationOffsetDb,
    )

    private fun Double.orNull(): Double? = takeUnless { it.isNaN() || it.isInfinite() }
    private fun Double?.orNaN(): Double = this ?: Double.NaN

    fun MinuteEntity.toRecord() = MinuteRecord(
        startEpochMs, startIso, durationSeconds, laeqDb.orNaN(), lafMaxDb.orNaN(), lafMinDb.orNaN(),
        l1Db.orNaN(), l10Db.orNaN(), l50Db.orNaN(), l90Db.orNaN(),
        eventCount, dominantCategory,
        Json.parseToJsonElement(categorySharesJson).jsonObject.mapValues { it.value.jsonPrimitive.double },
        classifierFrames, calibrationId, calibrationOffsetDb, audioSource, calibrated,
        validSeconds = validSeconds, clockCorrections = clockCorrections,
        original = recalibratedFromId?.let {
            MinuteLevels(
                origLaeqDb.orNaN(), origLafMaxDb.orNaN(), origLafMinDb.orNaN(), origL1Db.orNaN(),
                origL10Db.orNaN(), origL50Db.orNaN(), origL90Db.orNaN(),
            )
        },
        recalibratedFromId = recalibratedFromId, recalibrationOffsetDb = recalibrationOffsetDb,
    )

    fun NoiseEvent.toEntity() = EventEntity(
        startEpochMs = startEpochMs, startIso = startIso, durationSeconds = durationSeconds,
        lafMaxDb = lafMaxDb, selDb = selDb, backgroundDb = backgroundDb, thresholdDb = thresholdDb,
        dominantCategory = dominantCategory, dominantScore = dominantScore,
        topLabelsJson = buildJsonArray {
            topLabels.forEach { add(buildJsonObject { put("label", it.label); put("score", it.score) }) }
        }.toString(),
        classifierFrames = classifierFrames, calibrationId = calibrationId, audioSource = audioSource,
        calibrated = calibrated, minLevelDb = minLevelDb.orNull(),
        origLafMaxDb = original?.lafMaxDb?.orNull(), origSelDb = original?.selDb?.orNull(),
        origBackgroundDb = original?.backgroundDb?.orNull(),
        recalibratedFromId = recalibratedFromId, recalibrationOffsetDb = recalibrationOffsetDb,
    )

    fun EventEntity.toEvent() = NoiseEvent(
        startEpochMs, startIso, durationSeconds, lafMaxDb, selDb, backgroundDb, thresholdDb,
        dominantCategory, dominantScore,
        Json.parseToJsonElement(topLabelsJson).jsonArray.map {
            val o = it.jsonObject
            LabelScore(o["label"]!!.jsonPrimitive.content, o["score"]!!.jsonPrimitive.float)
        },
        classifierFrames, calibrationId, audioSource, calibrated,
        minLevelDb = minLevelDb.orNaN(),
        original = recalibratedFromId?.let { EventLevels(origLafMaxDb.orNaN(), origSelDb.orNaN(), origBackgroundDb.orNaN()) },
        recalibratedFromId = recalibratedFromId, recalibrationOffsetDb = recalibrationOffsetDb,
    )
}
