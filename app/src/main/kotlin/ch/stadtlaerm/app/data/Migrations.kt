package ch.stadtlaerm.app.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * SQL for schema migrations, kept free of Android types so it can be tested on the JVM against
 * SQLite (see app/src/test/.../MigrationSqlTest.kt).
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
}

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MigrationSql.MIGRATE_1_2.forEach { db.execSQL(it) }
    }
}
