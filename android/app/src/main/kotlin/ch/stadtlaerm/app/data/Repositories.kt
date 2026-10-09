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

import ch.stadtlaerm.dsp.EngineConfig
import ch.stadtlaerm.dsp.ThresholdMigration
import ch.stadtlaerm.dsp.WindRule

private val DEFAULT_INTERVAL_S: Int = ch.stadtlaerm.dsp.EngineConfig.DEFAULT_CLASSIFIER_INTERVAL_SECONDS.toInt()

data class AppSettings(
    /**
     * «Ereignis-Schwelle über lokalem Hintergrund»: an event starts when LAF reaches the local floor
     * + this (dB, 3–20, 0.5 steps). Replaces the v0.3 `eventThresholdDb` (over the 5-min background).
     */
    val eventExcessDb: Double = EngineConfig.DEFAULT_EVENT_EXCESS_DB,
    /** «Lokaler Hintergrund: Fenster»: the local floor is the L90 over this trailing window (s, 10–60). */
    val localFloorWindowSeconds: Int = EngineConfig.DEFAULT_LOCAL_FLOOR_WINDOW_SECONDS.toInt(),
    /** Experten: wind if the 20–200 Hz energy share is at least this … */
    val windLfShareMin: Double = WindRule.DEFAULT_LF_SHARE,
    /** … or the low-frequency flutter at least this (dB). */
    val windFlutterMinDb: Double = WindRule.DEFAULT_FLUTTER_DB,
    /** Experten: draw wind events in the chart (hollow grey dots); they are never counted. */
    val showWindEvents: Boolean = true,
    val classifierEnabled: Boolean = true,
    /** Once per second by default (EngineConfig.DEFAULT_CLASSIFIER_INTERVAL_SECONDS). */
    val classifierIntervalSeconds: Int = DEFAULT_INTERVAL_S,
    val classifierNormalize: Boolean = true,
    val wakeLock: Boolean = true,
    /** Events count only if their LAFmax reaches this level (dB(A)); also applied to stored events. */
    val eventMinLevelDb: Double = ch.stadtlaerm.dsp.EngineConfig.DEFAULT_EVENT_MIN_LEVEL_DB,
    /** Event category emphasised in the chart. */
    val chartHighlightCategory: String = "loud_vehicle",
    /**
     * «Messung beenden, wenn die App geschlossen wird»: stop the measurement when the app's task
     * is swiped away. Off by default: the measurement keeps running as a foreground service.
     */
    val stopOnTaskRemoved: Boolean = false,
) {
    companion object {
        const val EVENT_MIN_LEVEL_MIN = 20.0
        const val EVENT_MIN_LEVEL_MAX = 70.0
        const val EVENT_EXCESS_MIN = ThresholdMigration.EXCESS_MIN
        const val EVENT_EXCESS_MAX = ThresholdMigration.EXCESS_MAX
        const val LOCAL_WINDOW_MIN = 10
        const val LOCAL_WINDOW_MAX = 60
        const val WIND_LF_SHARE_MIN = 0.80
        const val WIND_LF_SHARE_MAX = 1.00
        const val WIND_FLUTTER_MIN = 2.0
        const val WIND_FLUTTER_MAX = 10.0
    }
}

/**
 * v0.3 → v0.4 settings migration (pure, unit-tested): the stored `event_threshold_db` (over the
 * 5-min background, default 10) becomes `event_excess_db` (over the local floor) =
 * [ThresholdMigration.excessFromLegacyThreshold]. A user who had changed the threshold is told
 * once ([Result.notice]); the default maps silently to the new default 6.5.
 */
object SettingsMigration {
    data class Result(val excessDb: Double, val notice: String?)

    /** [oldThresholdDb]: the stored v0.3 value, null if none was ever stored. */
    fun migrate(oldThresholdDb: Double?): Result {
        if (oldThresholdDb == null) return Result(EngineConfig.DEFAULT_EVENT_EXCESS_DB, null)
        val excess = ThresholdMigration.excessFromLegacyThreshold(oldThresholdDb)
        val custom = Math.abs(oldThresholdDb - ThresholdMigration.LEGACY_DEFAULT_THRESHOLD_DB) > 1e-6
        return Result(excess, if (custom) notice(oldThresholdDb, excess) else null)
    }

    fun notice(oldThresholdDb: Double, excessDb: Double): String =
        "Neue Ereigniserkennung (Version 0.4): Ereignisse werden jetzt über dem lokalen Hintergrund (L90 der letzten 30 s) " +
            "erkannt statt über dem 5-Minuten-Hintergrund. Ihre Schwelle von ${fmt(oldThresholdDb)} dB wurde auf " +
            "${fmt(excessDb)} dB über dem lokalen Hintergrund umgerechnet (der lokale Hintergrund liegt rund 3.5 dB höher). " +
            "Einstellbar unter Einstellungen → Ereigniserkennung."

    private fun fmt(v: Double): String = if (v % 1.0 == 0.0) String.format(java.util.Locale.ROOT, "%.0f", v)
        else String.format(java.util.Locale.ROOT, "%.1f", v)
}

/** Small settings store on SharedPreferences, exposed as a StateFlow. */
class SettingsStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    init {
        // v0.3 → v0.4: event_threshold_db (over the 5-min background) → event_excess_db (over the
        // local floor), once. The old key is left alone (a downgrade would still find it).
        if (!prefs.contains(KEY_EXCESS)) {
            val old = if (prefs.contains(KEY_LEGACY_THRESHOLD)) prefs.getFloat(KEY_LEGACY_THRESHOLD, 10f).toDouble() else null
            val m = SettingsMigration.migrate(old)
            val edit = prefs.edit().putFloat(KEY_EXCESS, m.excessDb.toFloat())
            if (m.notice != null) edit.putString(KEY_NOTICE, m.notice)
            edit.apply()
        }
    }

    private val _state = MutableStateFlow(read())
    val state: StateFlow<AppSettings> = _state

    private val _notice = MutableStateFlow(prefs.getString(KEY_NOTICE, null))
    /** The one-time notice about the migrated threshold, until dismissed. */
    val migrationNotice: StateFlow<String?> = _notice

    fun dismissMigrationNotice() {
        prefs.edit().remove(KEY_NOTICE).apply()
        _notice.value = null
    }

    private fun read() = AppSettings(
        eventExcessDb = prefs.getFloat(KEY_EXCESS, EngineConfig.DEFAULT_EVENT_EXCESS_DB.toFloat()).toDouble()
            .coerceIn(AppSettings.EVENT_EXCESS_MIN, AppSettings.EVENT_EXCESS_MAX),
        localFloorWindowSeconds = prefs.getInt("local_floor_window_s", EngineConfig.DEFAULT_LOCAL_FLOOR_WINDOW_SECONDS.toInt())
            .coerceIn(AppSettings.LOCAL_WINDOW_MIN, AppSettings.LOCAL_WINDOW_MAX),
        windLfShareMin = prefs.getFloat("wind_lf_share_min", WindRule.DEFAULT_LF_SHARE.toFloat()).toDouble()
            .coerceIn(AppSettings.WIND_LF_SHARE_MIN, AppSettings.WIND_LF_SHARE_MAX),
        windFlutterMinDb = prefs.getFloat("wind_flutter_min_db", WindRule.DEFAULT_FLUTTER_DB.toFloat()).toDouble()
            .coerceIn(AppSettings.WIND_FLUTTER_MIN, AppSettings.WIND_FLUTTER_MAX),
        showWindEvents = prefs.getBoolean("show_wind_events", true),
        classifierEnabled = prefs.getBoolean("classifier_enabled", true),
        classifierIntervalSeconds = prefs.getInt("classifier_interval_s", DEFAULT_INTERVAL_S),
        classifierNormalize = prefs.getBoolean("classifier_normalize", true),
        wakeLock = prefs.getBoolean("wake_lock", true),
        eventMinLevelDb = prefs.getFloat("event_min_level_db", ch.stadtlaerm.dsp.EngineConfig.DEFAULT_EVENT_MIN_LEVEL_DB.toFloat())
            .toDouble().coerceIn(AppSettings.EVENT_MIN_LEVEL_MIN, AppSettings.EVENT_MIN_LEVEL_MAX),
        chartHighlightCategory = prefs.getString("chart_highlight", null) ?: "loud_vehicle",
        stopOnTaskRemoved = prefs.getBoolean("stop_on_task_removed", false),
    )

    fun update(transform: (AppSettings) -> AppSettings) {
        val n = transform(_state.value)
        prefs.edit()
            .putFloat(KEY_EXCESS, n.eventExcessDb.toFloat())
            .putInt("local_floor_window_s", n.localFloorWindowSeconds)
            .putFloat("wind_lf_share_min", n.windLfShareMin.toFloat())
            .putFloat("wind_flutter_min_db", n.windFlutterMinDb.toFloat())
            .putBoolean("show_wind_events", n.showWindEvents)
            .putBoolean("classifier_enabled", n.classifierEnabled)
            .putInt("classifier_interval_s", n.classifierIntervalSeconds)
            .putBoolean("classifier_normalize", n.classifierNormalize)
            .putBoolean("wake_lock", n.wakeLock)
            .putFloat("event_min_level_db", n.eventMinLevelDb.toFloat())
            .putString("chart_highlight", n.chartHighlightCategory)
            .putBoolean("stop_on_task_removed", n.stopOnTaskRemoved)
            .apply()
        _state.value = n
    }

    companion object {
        const val KEY_EXCESS = "event_excess_db"
        const val KEY_LEGACY_THRESHOLD = "event_threshold_db"
        const val KEY_NOTICE = "excess_migration_notice"
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
    fun minutesBetween(fromMs: Long, toMs: Long) = dao.minutesBetween(fromMs, toMs)
    fun eventsBetween(fromMs: Long, toMs: Long) = dao.eventsBetween(fromMs, toMs)
    fun lastValidEndBefore(t: Long) = dao.lastValidEndBefore(t)
    fun firstValidStartAfter(t: Long) = dao.firstValidStartAfter(t)
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
