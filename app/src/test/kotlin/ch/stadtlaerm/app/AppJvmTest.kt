package ch.stadtlaerm.app

import ch.stadtlaerm.app.data.AppSettings
import ch.stadtlaerm.app.data.MigrationSql
import ch.stadtlaerm.app.ui.CalMode
import ch.stadtlaerm.app.ui.CalibrationRun
import java.io.File
import java.sql.DriverManager
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AppJvmTest {

    @Test
    fun classifierRunsEverySecondByDefault() {
        assertEquals(1, AppSettings().classifierIntervalSeconds)
    }

    // ---- Calibration integrity (review item 3) ---------------------------------------------

    private fun noise(seconds: Double, rms: Double = 0.01): FloatArray {
        val r = Random(1)
        val a = rms * Math.sqrt(3.0)
        return FloatArray((seconds * 48_000).toInt()) { ((r.nextDouble() * 2 - 1) * a).toFloat() }
    }

    private fun feed(run: CalibrationRun, x: FloatArray) {
        var p = 0
        while (p < x.size && !run.shouldStop) {
            val n = minOf(6000, x.size - p)
            run.onBlock(x.copyOfRange(p, p + n), n)
            p += n
        }
    }

    @Test
    fun completedCalibrationYieldsResult() {
        val run = CalibrationRun(CalMode.REFERENCE, 30)
        feed(run, noise(31.0))
        assertIs<CalibrationRun.Outcome.Done>(run.outcome())
    }

    @Test
    fun calibrationAbortsWhenAppGoesToBackground() {
        val run = CalibrationRun(CalMode.REFERENCE, 30)
        feed(run, noise(10.0))
        run.onAppStopped()
        assertTrue(run.shouldStop)
        feed(run, noise(25.0)) // further audio must not revive it
        val o = run.outcome()
        assertIs<CalibrationRun.Outcome.Aborted>(o)
        assertEquals(CalibrationRun.REASON_BACKGROUND, o.reason)
    }

    @Test
    fun calibrationAbortsWhenMicIsSilenced() {
        val byCallback = CalibrationRun(CalMode.CALIBRATOR, 10)
        feed(byCallback, noise(3.0))
        byCallback.onMicSilenced(true)
        assertEquals(CalibrationRun.REASON_SILENCED, (byCallback.outcome() as CalibrationRun.Outcome.Aborted).reason)

        val byZeros = CalibrationRun(CalMode.REFERENCE, 30)
        feed(byZeros, noise(5.0))
        feed(byZeros, FloatArray(48_000)) // system delivers zeros
        feed(byZeros, noise(30.0))
        assertEquals(CalibrationRun.REASON_SILENCED, (byZeros.outcome() as CalibrationRun.Outcome.Aborted).reason)
    }

    // ---- Database migration v1 → v2 ------------------------------------------------------------

    private val v1Minutes =
        "CREATE TABLE IF NOT EXISTS `minutes` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startEpochMs` INTEGER NOT NULL, " +
            "`startIso` TEXT NOT NULL, `durationSeconds` REAL NOT NULL, `laeqDb` REAL NOT NULL, `lafMaxDb` REAL NOT NULL, " +
            "`lafMinDb` REAL NOT NULL, `l1Db` REAL NOT NULL, `l10Db` REAL NOT NULL, `l50Db` REAL NOT NULL, `l90Db` REAL NOT NULL, " +
            "`eventCount` INTEGER NOT NULL, `dominantCategory` TEXT, `categorySharesJson` TEXT NOT NULL, " +
            "`classifierFrames` INTEGER NOT NULL, `calibrationId` INTEGER, `calibrationOffsetDb` REAL NOT NULL, " +
            "`audioSource` TEXT NOT NULL, `calibrated` INTEGER NOT NULL)"

    @Test
    fun v2CreateStatementMatchesRoomGeneratedSchema() {
        val dir = File(System.getProperty("stadtlaerm.generatedDb"))
        val impl = File(dir, "AppDatabase_Impl.kt").readText()
        assertTrue(impl.contains(MigrationSql.CREATE_MINUTES_V2), "Room schema changed: update MigrationSql")
        assertTrue(impl.contains("CREATE INDEX IF NOT EXISTS `index_minutes_startEpochMs` ON `minutes` (`startEpochMs`)"))
    }

    @Test
    fun migrationKeepsDataAndFlagsSilencedV1Minutes() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute(v1Minutes)
                st.execute("CREATE INDEX IF NOT EXISTS `index_minutes_startEpochMs` ON `minutes` (`startEpochMs`)")
                st.execute(
                    "INSERT INTO minutes (startEpochMs, startIso, durationSeconds, laeqDb, lafMaxDb, lafMinDb, l1Db, l10Db, l50Db, " +
                        "l90Db, eventCount, dominantCategory, categorySharesJson, classifierFrames, calibrationId, " +
                        "calibrationOffsetDb, audioSource, calibrated) VALUES " +
                        "(1, 'a', 60, 52.5, 70, 40, 65, 58, 50, 45, 2, 'road_traffic', '{}', 60, NULL, 112.35, 'UNPROCESSED', 0), " +
                        "(2, 'b', 60, -88.0, -80, -95, -82, -85, -88, -90, 0, NULL, '{}', 0, NULL, 112.35, 'UNPROCESSED', 0)"
                )
                MigrationSql.MIGRATE_1_2.forEach { st.execute(it) }
                st.executeQuery("SELECT laeqDb, validSeconds, coverage, clockCorrections, eventCount FROM minutes ORDER BY startEpochMs").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(52.5, rs.getDouble(1)); assertEquals(60.0, rs.getDouble(2)); assertEquals(1.0, rs.getDouble(3))
                    assertEquals(0, rs.getInt(4)); assertEquals(2, rs.getInt(5))
                    assertTrue(rs.next())
                    assertEquals(0.0, rs.getDouble(2)); assertEquals(0.0, rs.getDouble(3))
                }
                // Level columns are nullable now (minute without valid audio).
                st.execute(
                    "INSERT INTO minutes (startEpochMs, startIso, durationSeconds, laeqDb, eventCount, categorySharesJson, " +
                        "classifierFrames, calibrationOffsetDb, audioSource, calibrated, validSeconds, coverage, clockCorrections) " +
                        "VALUES (3, 'c', 60, NULL, 0, '{}', 0, 112.35, 'UNPROCESSED', 0, 0, 0, 0)"
                )
                // Same column layout as a freshly created v2 table.
                fun columns(table: String): List<String> {
                    val out = ArrayList<String>()
                    st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                        while (rs.next()) out += "${rs.getString("name")}|${rs.getString("type")}|${rs.getInt("notnull")}|${rs.getInt("pk")}"
                    }
                    return out
                }
                st.execute(MigrationSql.CREATE_MINUTES_V2.replace("`minutes`", "`fresh`"))
                assertEquals(columns("fresh"), columns("minutes"))
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='minutes'").use { rs ->
                    assertTrue(rs.next()); assertEquals("index_minutes_startEpochMs", rs.getString(1))
                }
            }
        }
    }
}
