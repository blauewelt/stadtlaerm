package ch.stadtlaerm.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQL for schema migrations, kept free of Android types so it can be tested on the JVM against
 * SQLite (see app/src/test/.../AppJvmTest.kt).
 */
object MigrationSql {
    /** v2 `minutes` table, exactly as Room generates it (verified by a unit test). */
    const val CREATE_MINUTES_V2 =
        "CREATE TABLE IF NOT EXISTS `minutes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startEpochMs` INTEGER NOT NULL, " +
            "`startIso` TEXT NOT NULL, `durationSeconds` REAL NOT NULL, `laeqDb` REAL, `lafMaxDb` REAL, `lafMinDb` REAL, " +
            "`l1Db` REAL, `l10Db` REAL, `l50Db` REAL, `l90Db` REAL, `eventCount` INTEGER NOT NULL, `dominantCategory` TEXT, " +
            "`categorySharesJson` TEXT NOT NULL, `classifierFrames` INTEGER NOT NULL, `calibrationId` INTEGER, " +
            "`calibrationOffsetDb` REAL NOT NULL, `audioSource` TEXT NOT NULL, `calibrated` INTEGER NOT NULL, " +
            "`validSeconds` REAL NOT NULL, `coverage` REAL NOT NULL, `clockCorrections` INTEGER NOT NULL)"

    /** v3 `events` table, exactly as Room generates it (verified by a unit test). */
    const val CREATE_EVENTS_V3 =
        "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startEpochMs` INTEGER NOT NULL, " +
            "`startIso` TEXT NOT NULL, `durationSeconds` REAL NOT NULL, `lafMaxDb` REAL NOT NULL, `selDb` REAL NOT NULL, " +
            "`backgroundDb` REAL NOT NULL, `thresholdDb` REAL NOT NULL, `dominantCategory` TEXT, `dominantScore` REAL NOT NULL, " +
            "`topLabelsJson` TEXT NOT NULL, `classifierFrames` INTEGER NOT NULL, `calibrationId` INTEGER, " +
            "`audioSource` TEXT NOT NULL, `calibrated` INTEGER NOT NULL, `min_level_db` REAL)"

    private const val V1_COLUMNS = "`id`, `startEpochMs`, `startIso`, `durationSeconds`, `laeqDb`, `lafMaxDb`, `lafMinDb`, " +
        "`l1Db`, `l10Db`, `l50Db`, `l90Db`, `eventCount`, `dominantCategory`, `categorySharesJson`, `classifierFrames`, " +
        "`calibrationId`, `calibrationOffsetDb`, `audioSource`, `calibrated`"

    /**
     * v1 → v2: level columns become nullable (minutes without valid audio), and validSeconds /
     * coverage / clockCorrections are added. v1 had no silence detection: a v1 minute whose LAeq
     * is below 0 dB(A) can only come from a silenced microphone, so it gets coverage 0 (and is
     * left out of night summaries); all others are assumed fully valid.
     */
    val MIGRATE_1_2: List<String> = listOf(
        CREATE_MINUTES_V2.replace("`minutes`", "`minutes_v2`"),
        "INSERT INTO `minutes_v2` ($V1_COLUMNS, `validSeconds`, `coverage`, `clockCorrections`) " +
            "SELECT $V1_COLUMNS, CASE WHEN `laeqDb` < 0 THEN 0 ELSE `durationSeconds` END, " +
            "CASE WHEN `laeqDb` < 0 THEN 0 ELSE 1 END, 0 FROM `minutes`",
        "DROP TABLE `minutes`",
        "ALTER TABLE `minutes_v2` RENAME TO `minutes`",
        "CREATE INDEX IF NOT EXISTS `index_minutes_startEpochMs` ON `minutes` (`startEpochMs`)",
    )

    /**
     * v2 → v3: events store the absolute LAFmax floor that was in force (`min_level_db`). Events
     * from before have none (NULL); the app applies the current floor to them at read time.
     */
    val MIGRATE_2_3: List<String> = listOf(
        "ALTER TABLE `events` ADD COLUMN `min_level_db` REAL",
    )

    /** v4 `minutes` table, exactly as Room generates it for v0.3.2 – v0.3.4. */
    const val CREATE_MINUTES_V4_PREFIX =
        "CREATE TABLE IF NOT EXISTS `minutes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startEpochMs` INTEGER NOT NULL, " +
            "`startIso` TEXT NOT NULL, `durationSeconds` REAL NOT NULL, `laeqDb` REAL, `lafMaxDb` REAL, `lafMinDb` REAL, " +
            "`l1Db` REAL, `l10Db` REAL, `l50Db` REAL, `l90Db` REAL, `eventCount` INTEGER NOT NULL, `dominantCategory` TEXT, " +
            "`categorySharesJson` TEXT NOT NULL, `classifierFrames` INTEGER NOT NULL, `calibrationId` INTEGER, " +
            "`calibrationOffsetDb` REAL NOT NULL, `audioSource` TEXT NOT NULL, `calibrated` INTEGER NOT NULL, " +
            "`validSeconds` REAL NOT NULL, `coverage` REAL NOT NULL, `clockCorrections` INTEGER NOT NULL, " +
            "`orig_laeq_db` REAL, `orig_lafmax_db` REAL, `orig_lafmin_db` REAL, `orig_l1_db` REAL, `orig_l10_db` REAL, " +
            "`orig_l50_db` REAL, `orig_l90_db` REAL, `recalibrated_from_id` TEXT, `recalibration_offset_db` REAL"
    const val CREATE_MINUTES_V4 = "$CREATE_MINUTES_V4_PREFIX)"

    /** v4 `events` table, exactly as Room generates it for v0.3.2 – v0.3.4. */
    const val CREATE_EVENTS_V4_PREFIX =
        "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startEpochMs` INTEGER NOT NULL, " +
            "`startIso` TEXT NOT NULL, `durationSeconds` REAL NOT NULL, `lafMaxDb` REAL NOT NULL, `selDb` REAL NOT NULL, " +
            "`backgroundDb` REAL NOT NULL, `thresholdDb` REAL NOT NULL, `dominantCategory` TEXT, `dominantScore` REAL NOT NULL, " +
            "`topLabelsJson` TEXT NOT NULL, `classifierFrames` INTEGER NOT NULL, `calibrationId` INTEGER, " +
            "`audioSource` TEXT NOT NULL, `calibrated` INTEGER NOT NULL, `min_level_db` REAL, " +
            "`orig_lafmax_db` REAL, `orig_sel_db` REAL, `orig_background_db` REAL, `recalibrated_from_id` TEXT, " +
            "`recalibration_offset_db` REAL"
    const val CREATE_EVENTS_V4 = "$CREATE_EVENTS_V4_PREFIX)"

    /**
     * v3 → v4: re-evaluation of stored data with a later calibration. Minutes and events keep their
     * original level values (orig_*) and the calibration they were measured with
     * (recalibrated_from_id, recalibration_offset_db). All NULL until a record is re-evaluated.
     */
    val MIGRATE_3_4: List<String> =
        listOf("orig_laeq_db", "orig_lafmax_db", "orig_lafmin_db", "orig_l1_db", "orig_l10_db", "orig_l50_db", "orig_l90_db")
            .map { "ALTER TABLE `minutes` ADD COLUMN `$it` REAL" } +
            listOf(
                "ALTER TABLE `minutes` ADD COLUMN `recalibrated_from_id` TEXT",
                "ALTER TABLE `minutes` ADD COLUMN `recalibration_offset_db` REAL",
            ) +
            listOf("orig_lafmax_db", "orig_sel_db", "orig_background_db").map { "ALTER TABLE `events` ADD COLUMN `$it` REAL" } +
            listOf(
                "ALTER TABLE `events` ADD COLUMN `recalibrated_from_id` TEXT",
                "ALTER TABLE `events` ADD COLUMN `recalibration_offset_db` REAL",
            )

    /**
     * v4 → v5 (v0.4.0, detector v2): events gain the features (local floor, excess, rise/decay,
     * jaggedness, mid-band rise, LF share and flutter, the wind flag and the shape), minutes the
     * median local floor and the number of wind events. Existing rows: NULL features, wind 0,
     * wind_event_count 0 (no wind detection before v0.4.0).
     */
    val MIGRATE_4_5: List<String> =
        listOf("local_floor_db", "excess_db", "rise_s", "decay_s", "jaggedness", "mid_band_rise_db", "lf_share", "lf_flutter_db")
            .map { "ALTER TABLE `events` ADD COLUMN `$it` REAL" } +
            listOf(
                "ALTER TABLE `events` ADD COLUMN `wind` INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE `events` ADD COLUMN `shape` TEXT",
                "ALTER TABLE `minutes` ADD COLUMN `local_floor_db` REAL",
                "ALTER TABLE `minutes` ADD COLUMN `wind_event_count` INTEGER NOT NULL DEFAULT 0",
            )

    /** v5 `minutes` table, exactly as Room generates it (verified by a unit test). */
    const val CREATE_MINUTES_V5 = CREATE_MINUTES_V4_PREFIX +
        ", `local_floor_db` REAL, `wind_event_count` INTEGER NOT NULL DEFAULT 0)"

    /** v5 `events` table, exactly as Room generates it (verified by a unit test). */
    const val CREATE_EVENTS_V5 = CREATE_EVENTS_V4_PREFIX +
        ", `local_floor_db` REAL, `excess_db` REAL, `rise_s` REAL, `decay_s` REAL, `jaggedness` REAL, " +
        "`mid_band_rise_db` REAL, `lf_share` REAL, `lf_flutter_db` REAL, `wind` INTEGER NOT NULL DEFAULT 0, `shape` TEXT)"
}

/**
 * Records a re-evaluation with a calibration changes: same audio source, measured without or with
 * another calibration — the SQL form of `Recalibration.inScope` (checked by a unit test).
 */
object RecalibrationSql {
    const val SCOPE = "audioSource = :source AND (calibrationId IS NULL OR calibrationId != :calibrationId)"
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.MIGRATE_1_2.forEach { db.execSQL(it) }
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.MIGRATE_2_3.forEach { db.execSQL(it) }
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.MIGRATE_3_4.forEach { db.execSQL(it) }
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.MIGRATE_4_5.forEach { db.execSQL(it) }
    }
}
