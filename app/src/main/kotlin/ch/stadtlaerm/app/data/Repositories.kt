package ch.stadtlaerm.app.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import ch.stadtlaerm.app.data.Mappers.toEntity
import ch.stadtlaerm.app.data.Mappers.toEvent
import ch.stadtlaerm.app.data.Mappers.toRecord
import ch.stadtlaerm.dsp.Acoustics
import ch.stadtlaerm.dsp.Csv
import ch.stadtlaerm.dsp.Iso
import ch.stadtlaerm.dsp.MinuteRecord
import ch.stadtlaerm.dsp.NoiseEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.time.ZoneId

data class AppSettings(
    val eventThresholdDb: Double = 10.0,
    val classifierEnabled: Boolean = true,
    val classifierIntervalSeconds: Int = 1,
    val classifierNormalize: Boolean = true,
    val wakeLock: Boolean = true,
)

/** Small settings store on SharedPreferences, exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state

    private fun read() = AppSettings(
        eventThresholdDb = prefs.getFloat("event_threshold_db", 10f).toDouble(),
        classifierEnabled = prefs.getBoolean("classifier_enabled", true),
        classifierIntervalSeconds = prefs.getInt("classifier_interval_s", 1),
        classifierNormalize = prefs.getBoolean("classifier_normalize", true),
        wakeLock = prefs.getBoolean("wake_lock", true),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(_state.value)
        prefs.edit()
            .putFloat("event_threshold_db", n.eventThresholdDb.toFloat())
            .putBoolean("classifier_enabled", n.classifierEnabled)
            .putInt("classifier_interval_s", n.classifierIntervalSeconds)
            .putBoolean("classifier_normalize", n.classifierNormalize)
            .putBoolean("wake_lock", n.wakeLock)
            .apply()
        _state.value = n
    }
}

/** Calibration offsets per (device model, audio source), with full history. */
class CalibrationRepository(private val dao: CalibrationDao) {
    companion object {
        val deviceModel: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
    }

    data class Active(val entity: CalibrationEntity?) {
        val offsetDb: Double get() = entity?.offsetDb ?: Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
        val calibrated: Boolean get() = entity?.isCalibrated == true
        val id: Long? get() = entity?.id
    }

    suspend fun active(source: String): Active = Active(dao.latest(deviceModel, source))
    fun activeFlow(source: String): Flow<CalibrationEntity?> = dao.latestFlow(deviceModel, source)
    fun history(): Flow<List<CalibrationEntity>> = dao.historyFlow()

    suspend fun save(
        source: String, offsetDb: Double, method: String, notes: String,
        referenceDb: Double? = null, measuredRawDb: Double? = null, stdDevDb: Double? = null,
        durationSeconds: Double? = null, calibratorNominalDb: Double? = null,
        toneFrequencyHz: Double? = null, tonality: Double? = null, warnings: List<String> = emptyList(),
    ): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            CalibrationEntity(
                deviceModel = deviceModel, audioSource = source, offsetDb = offsetDb, method = method,
                createdEpochMs = now, createdIso = Iso.format(now, ZoneId.systemDefault()), notes = notes,
                referenceDb = referenceDb, measuredRawDb = measuredRawDb, stdDevDb = stdDevDb,
                durationSeconds = durationSeconds, calibratorNominalDb = calibratorNominalDb,
                toneFrequencyHz = toneFrequencyHz, tonality = tonality, warnings = warnings.joinToString(","),
            )
        )
    }

    suspend fun exportJson(): String {
        val all = dao.all()
        return buildJsonObject {
            put("app", "Stadtlärm")
            put("format", "calibration-history-v1")
            put("default_offset_db", Acoustics.DEFAULT_CALIBRATION_OFFSET_DB)
            put("calibrations", buildJsonArray {
                all.forEach { c ->
                    add(buildJsonObject {
                        put("id", c.id)
                        put("device_model", c.deviceModel)
                        put("audio_source", c.audioSource)
                        put("offset_db", c.offsetDb)
                        put("method", c.method)
                        put("created", c.createdIso)
                        put("notes", c.notes)
                        c.referenceDb?.let { put("reference_db", it) }
                        c.measuredRawDb?.let { put("measured_raw_db", it) }
                        c.stdDevDb?.let { put("std_dev_1s_db", it) }
                        c.durationSeconds?.let { put("duration_s", it) }
                        c.calibratorNominalDb?.let { put("calibrator_nominal_db", it) }
                        c.toneFrequencyHz?.let { put("tone_frequency_hz", it) }
                        c.tonality?.let { put("tonality", it) }
                        if (c.warnings.isNotEmpty()) put("warnings", c.warnings)
                    })
                }
            })
        }.toString()
    }
}

/** Stored aggregates (minutes, events) and their export. */
class MeasurementRepository(private val dao: MeasurementDao) {
    suspend fun insert(m: MinuteRecord) = dao.insertMinute(m.toEntity())
    suspend fun insert(e: NoiseEvent) = dao.insertEvent(e.toEntity())

    fun recentEvents(limit: Int = 30): Flow<List<EventEntity>> = dao.recentEvents(limit)
    fun minutesFlow() = dao.minutesFlow()
    fun eventsFlow() = dao.eventsFlow()
    fun minuteCount() = dao.minuteCount()
    fun eventCount() = dao.eventCount()

    suspend fun allMinutes(): List<MinuteRecord> = dao.allMinutes().map { it.toRecord() }
    suspend fun allEvents(): List<NoiseEvent> = dao.allEvents().map { it.toEvent() }

    suspend fun clear() {
        dao.clearMinutes(); dao.clearEvents()
    }

    /** Writes CSV exports into cache/exports (served via FileProvider). */
    suspend fun exportCsv(dir: File, bucketIds: List<String>): List<File> {
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.ROOT).format(java.util.Date())
        val minutes = File(dir, "stadtlaerm-minuten-$stamp.csv").apply { writeText(Csv.minutes(allMinutes(), bucketIds)) }
        val events = File(dir, "stadtlaerm-ereignisse-$stamp.csv").apply { writeText(Csv.events(allEvents())) }
        return listOf(minutes, events)
    }
}
