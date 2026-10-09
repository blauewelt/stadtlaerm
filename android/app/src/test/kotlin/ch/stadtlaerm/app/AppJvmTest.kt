package ch.stadtlaerm.app

import ch.stadtlaerm.app.data.AppSettings
import ch.stadtlaerm.app.data.Mappers
import ch.stadtlaerm.app.data.SettingsMigration
import ch.stadtlaerm.dsp.EventFeatures
import ch.stadtlaerm.dsp.EventShape
import ch.stadtlaerm.dsp.WindRule
import ch.stadtlaerm.app.data.EventEntity
import ch.stadtlaerm.app.data.MinuteEntity
import ch.stadtlaerm.app.data.MigrationSql
import ch.stadtlaerm.app.data.RecalibrationMapping
import ch.stadtlaerm.app.data.RecalibrationSql
import ch.stadtlaerm.app.ui.CalibrationTexts
import ch.stadtlaerm.dsp.Acoustics
import ch.stadtlaerm.dsp.calibration.EventFloor
import ch.stadtlaerm.dsp.calibration.Recalibration
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
    fun v2CreateStatementIsThePrefixOfTheCurrentSchema() {
        // v3 left `minutes` unchanged; v4 only appends columns.
        assertTrue(MigrationSql.CREATE_MINUTES_V4.startsWith(MigrationSql.CREATE_MINUTES_V2.removeSuffix(")") + ", "))
        assertTrue(MigrationSql.CREATE_EVENTS_V4.startsWith(MigrationSql.CREATE_EVENTS_V3.removeSuffix(")") + ", "))
        // v5 only appends columns too.
        assertTrue(MigrationSql.CREATE_MINUTES_V5.startsWith(MigrationSql.CREATE_MINUTES_V4.removeSuffix(")") + ", "))
        assertTrue(MigrationSql.CREATE_EVENTS_V5.startsWith(MigrationSql.CREATE_EVENTS_V4.removeSuffix(")") + ", "))
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

    // ---- Database migration v2 → v3 (event floor) ----------------------------------------------

    /** v2 `events` table as Room generated it for v0.2.0. */
    private val v2Events =
        "CREATE TABLE IF NOT EXISTS `events` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `startEpochMs` INTEGER NOT NULL, " +
            "`startIso` TEXT NOT NULL, `durationSeconds` REAL NOT NULL, `lafMaxDb` REAL NOT NULL, `selDb` REAL NOT NULL, " +
            "`backgroundDb` REAL NOT NULL, `thresholdDb` REAL NOT NULL, `dominantCategory` TEXT, `dominantScore` REAL NOT NULL, " +
            "`topLabelsJson` TEXT NOT NULL, `classifierFrames` INTEGER NOT NULL, `calibrationId` INTEGER, " +
            "`audioSource` TEXT NOT NULL, `calibrated` INTEGER NOT NULL)"

    @Test
    fun v5CreateStatementsMatchRoomGeneratedSchema() {
        val dir = File(System.getProperty("stadtlaerm.generatedDb"))
        val impl = File(dir, "AppDatabase_Impl.kt").readText()
        assertTrue(impl.contains(MigrationSql.CREATE_MINUTES_V5), "Room schema changed: update MigrationSql")
        assertTrue(impl.contains(MigrationSql.CREATE_EVENTS_V5), "Room schema changed: update MigrationSql")
        assertTrue(impl.contains("RoomOpenHelper.Delegate(5)"), "Room schema version is not 5")
        assertTrue(impl.contains("CREATE INDEX IF NOT EXISTS `index_minutes_startEpochMs` ON `minutes` (`startEpochMs`)"))
        assertTrue(impl.contains("CREATE INDEX IF NOT EXISTS `index_events_startEpochMs` ON `events` (`startEpochMs`)"))
    }

    @Test
    fun migration2to3AddsNullableFloorAndKeepsEvents() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute(v2Events)
                st.execute("CREATE INDEX IF NOT EXISTS `index_events_startEpochMs` ON `events` (`startEpochMs`)")
                st.execute(
                    "INSERT INTO events (startEpochMs, startIso, durationSeconds, lafMaxDb, selDb, backgroundDb, thresholdDb, " +
                        "dominantCategory, dominantScore, topLabelsJson, classifierFrames, calibrationId, audioSource, calibrated) VALUES " +
                        "(1, 'a', 2.25, 45.1, 45.4, 21.8, 10.0, 'unclassified', 0.063, '[]', 2, NULL, 'UNPROCESSED', 0), " +
                        "(2, 'b', 0.5, 37.3, 30.6, 22.0, 10.0, 'voices', 0.4, '[]', 1, NULL, 'UNPROCESSED', 0)"
                )
                MigrationSql.MIGRATE_2_3.forEach { st.execute(it) }
                st.executeQuery("SELECT lafMaxDb, min_level_db FROM events ORDER BY startEpochMs").use { rs ->
                    assertTrue(rs.next()); assertEquals(45.1, rs.getDouble(1)); rs.getDouble(2); assertTrue(rs.wasNull())
                    assertTrue(rs.next()); assertEquals(37.3, rs.getDouble(1)); rs.getDouble(2); assertTrue(rs.wasNull())
                }
                st.execute(
                    "INSERT INTO events (startEpochMs, startIso, durationSeconds, lafMaxDb, selDb, backgroundDb, thresholdDb, " +
                        "dominantScore, topLabelsJson, classifierFrames, audioSource, calibrated, min_level_db) VALUES " +
                        "(3, 'c', 1.0, 60.0, 61.0, 30.0, 10.0, 0.0, '[]', 0, 'UNPROCESSED', 0, 45.0)"
                )
                fun columns(table: String): List<String> {
                    val out = ArrayList<String>()
                    st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
                        while (rs.next()) {
                            out += "${rs.getString("name")}|${rs.getString("type")}|${rs.getInt("notnull")}|${rs.getInt("pk")}|${rs.getString("dflt_value")}"
                        }
                    }
                    return out
                }
                st.execute(MigrationSql.CREATE_EVENTS_V3.replace("`events`", "`fresh`"))
                assertEquals(columns("fresh"), columns("events"))
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='events'").use { rs ->
                    assertTrue(rs.next()); assertEquals("index_events_startEpochMs", rs.getString(1))
                }
            }
        }
    }

    @Test
    fun eventFloorDefaultsTo30dB() {
        assertEquals(30.0, AppSettings().eventMinLevelDb)
        assertEquals(20.0, AppSettings.EVENT_MIN_LEVEL_MIN)
        assertEquals(70.0, AppSettings.EVENT_MIN_LEVEL_MAX)
        assertEquals("loud_vehicle", AppSettings().chartHighlightCategory)
    }

    // ---- Database migration v3 → v4 (re-evaluation with a later calibration) ------------------

    private fun columns(st: java.sql.Statement, table: String): List<String> {
        val out = ArrayList<String>()
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            while (rs.next()) {
                out += "${rs.getString("name")}|${rs.getString("type")}|${rs.getInt("notnull")}|${rs.getInt("pk")}|${rs.getString("dflt_value")}"
            }
        }
        return out
    }

    @Test
    fun migration3to4AddsNullableRecalibrationColumnsAndKeepsData() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute(MigrationSql.CREATE_MINUTES_V2)
                st.execute("CREATE INDEX IF NOT EXISTS `index_minutes_startEpochMs` ON `minutes` (`startEpochMs`)")
                st.execute(MigrationSql.CREATE_EVENTS_V3)
                st.execute("CREATE INDEX IF NOT EXISTS `index_events_startEpochMs` ON `events` (`startEpochMs`)")
                st.execute(
                    "INSERT INTO minutes (startEpochMs, startIso, durationSeconds, laeqDb, lafMaxDb, lafMinDb, l1Db, l10Db, l50Db, " +
                        "l90Db, eventCount, dominantCategory, categorySharesJson, classifierFrames, calibrationId, " +
                        "calibrationOffsetDb, audioSource, calibrated, validSeconds, coverage, clockCorrections) VALUES " +
                        "(1, 'a', 60, 52.5, 70, 40, 65, 58, 50, 45, 2, 'road_traffic', '{}', 60, NULL, 112.35, 'UNPROCESSED', 0, 60, 1, 0)"
                )
                st.execute(
                    "INSERT INTO events (startEpochMs, startIso, durationSeconds, lafMaxDb, selDb, backgroundDb, thresholdDb, " +
                        "dominantCategory, dominantScore, topLabelsJson, classifierFrames, calibrationId, audioSource, calibrated, min_level_db) " +
                        "VALUES (1, 'a', 2.25, 45.1, 45.4, 21.8, 10.0, 'unclassified', 0.063, '[]', 2, NULL, 'UNPROCESSED', 0, 30.0)"
                )
                MigrationSql.MIGRATE_3_4.forEach { st.execute(it) }
                st.executeQuery(
                    "SELECT laeqDb, eventCount, orig_laeq_db, orig_l90_db, recalibrated_from_id, recalibration_offset_db FROM minutes"
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(52.5, rs.getDouble(1)); assertEquals(2, rs.getInt(2))
                    for (i in 3..6) { rs.getObject(i); assertTrue(rs.wasNull(), "column $i") }
                }
                st.executeQuery(
                    "SELECT lafMaxDb, min_level_db, orig_lafmax_db, orig_sel_db, orig_background_db, recalibrated_from_id, " +
                        "recalibration_offset_db FROM events"
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(45.1, rs.getDouble(1)); assertEquals(30.0, rs.getDouble(2))
                    for (i in 3..7) { rs.getObject(i); assertTrue(rs.wasNull(), "column $i") }
                }
                // Same column layout as freshly created v4 tables.
                st.execute(MigrationSql.CREATE_MINUTES_V4.replace("`minutes`", "`fresh_minutes`"))
                st.execute(MigrationSql.CREATE_EVENTS_V4.replace("`events`", "`fresh_events`"))
                assertEquals(columns(st, "fresh_minutes"), columns(st, "minutes"))
                assertEquals(columns(st, "fresh_events"), columns(st, "events"))
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name IN ('minutes','events') ORDER BY name").use { rs ->
                    assertTrue(rs.next()); assertEquals("index_events_startEpochMs", rs.getString(1))
                    assertTrue(rs.next()); assertEquals("index_minutes_startEpochMs", rs.getString(1))
                }
            }
        }
    }

    // ---- Database migration v4 → v5 (detector v2) -----------------------------------------------

    @Test
    fun migration4to5AddsFeatureColumnsWithDefaultsAndKeepsData() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute(MigrationSql.CREATE_MINUTES_V4)
                st.execute("CREATE INDEX IF NOT EXISTS `index_minutes_startEpochMs` ON `minutes` (`startEpochMs`)")
                st.execute(MigrationSql.CREATE_EVENTS_V4)
                st.execute("CREATE INDEX IF NOT EXISTS `index_events_startEpochMs` ON `events` (`startEpochMs`)")
                st.execute(
                    "INSERT INTO minutes (startEpochMs, startIso, durationSeconds, laeqDb, lafMaxDb, lafMinDb, l1Db, l10Db, l50Db, " +
                        "l90Db, eventCount, dominantCategory, categorySharesJson, classifierFrames, calibrationId, " +
                        "calibrationOffsetDb, audioSource, calibrated, validSeconds, coverage, clockCorrections, orig_laeq_db) VALUES " +
                        "(1, 'a', 60, 52.5, 70, 40, 65, 58, 50, 45, 2, 'road_traffic', '{}', 60, NULL, 112.35, 'UNPROCESSED', 0, 60, 1, 0, 50.0)"
                )
                st.execute(
                    "INSERT INTO events (startEpochMs, startIso, durationSeconds, lafMaxDb, selDb, backgroundDb, thresholdDb, " +
                        "dominantCategory, dominantScore, topLabelsJson, classifierFrames, calibrationId, audioSource, calibrated, min_level_db) " +
                        "VALUES (1, 'a', 2.25, 45.1, 45.4, 21.8, 10.0, 'unclassified', 0.063, '[]', 2, NULL, 'UNPROCESSED', 0, 30.0)"
                )
                MigrationSql.MIGRATE_4_5.forEach { st.execute(it) }
                st.executeQuery("SELECT laeqDb, eventCount, orig_laeq_db, local_floor_db, wind_event_count FROM minutes").use { rs ->
                    assertTrue(rs.next())
                    assertEquals(52.5, rs.getDouble(1)); assertEquals(2, rs.getInt(2)); assertEquals(50.0, rs.getDouble(3))
                    rs.getObject(4); assertTrue(rs.wasNull())
                    assertEquals(0, rs.getInt(5)); assertTrue(!rs.wasNull())
                }
                st.executeQuery(
                    "SELECT lafMaxDb, thresholdDb, local_floor_db, excess_db, rise_s, decay_s, jaggedness, mid_band_rise_db, lf_share, " +
                        "lf_flutter_db, shape, wind FROM events"
                ).use { rs ->
                    assertTrue(rs.next())
                    assertEquals(45.1, rs.getDouble(1)); assertEquals(10.0, rs.getDouble(2))
                    for (i in 3..11) { rs.getObject(i); assertTrue(rs.wasNull(), "column $i") }
                    assertEquals(0, rs.getInt(12)); assertTrue(!rs.wasNull())
                }
                st.execute(MigrationSql.CREATE_MINUTES_V5.replace("`minutes`", "`fresh_minutes`"))
                st.execute(MigrationSql.CREATE_EVENTS_V5.replace("`events`", "`fresh_events`"))
                assertEquals(columns(st, "fresh_minutes"), columns(st, "minutes"))
                assertEquals(columns(st, "fresh_events"), columns(st, "events"))
                st.executeQuery("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name IN ('minutes','events') ORDER BY name").use { rs ->
                    assertTrue(rs.next()); assertEquals("index_events_startEpochMs", rs.getString(1))
                    assertTrue(rs.next()); assertEquals("index_minutes_startEpochMs", rs.getString(1))
                }
            }
        }
    }

    @Test
    fun featuresRoundTripThroughTheEntities() {
        val f = EventFeatures(41.8, 12.7, 1.748, Double.NaN, 0.014, 12.5, 0.008, 0.39, false, EventShape.HUMP)
        val e = Mappers.run { eventEntity(1, "UNPROCESSED", null).toEvent() }.copy(features = f, thresholdDb = 6.5)
        val entity = Mappers.run { e.toEntity() }
        assertEquals(12.7, entity.excessDb); assertEquals(null, entity.decayS); assertEquals("hump", entity.shape); assertEquals(false, entity.wind)
        assertEquals(e, Mappers.run { entity.toEvent() })
        val wind = e.copy(features = f.copy(wind = true, lfShare = 0.98), dominantCategory = WindRule.CATEGORY)
        assertTrue(Mappers.run { wind.toEntity() }.wind)
        assertEquals(wind, Mappers.run { wind.toEntity().toEvent() })
        // Events from before v0.4.0: no features.
        assertEquals(null, Mappers.run { eventEntity(2, "UNPROCESSED", null).toEvent() }.features)
        val m = Mappers.run { minuteEntity(1, "UNPROCESSED", null, 112.35).toRecord() }.copy(localFloorDb = 38.2, windEventCount = 3)
        val me = Mappers.run { m.toEntity() }
        assertEquals(38.2, me.localFloorDb); assertEquals(3, me.windEventCount)
        assertEquals(m, Mappers.run { me.toRecord() })
        assertEquals(null, Mappers.run { m.copy(localFloorDb = Double.NaN).toEntity() }.localFloorDb)
    }

    // ---- Settings v0.3 → v0.4: threshold over the 5-min background → excess over the local floor --

    @Test
    fun legacyThresholdIsMigratedToTheExcess() {
        assertEquals(SettingsMigration.Result(6.5, null), SettingsMigration.migrate(10.0)) // the old default: silently
        val custom = SettingsMigration.migrate(7.0)
        assertEquals(3.5, custom.excessDb)
        assertEquals(
            "Neue Ereigniserkennung (Version 0.4): Ereignisse werden jetzt über dem lokalen Hintergrund (L90 der letzten 30 s) " +
                "erkannt statt über dem 5-Minuten-Hintergrund. Ihre Schwelle von 7 dB wurde auf 3.5 dB über dem lokalen " +
                "Hintergrund umgerechnet (der lokale Hintergrund liegt rund 3.5 dB höher). Einstellbar unter Einstellungen → Ereigniserkennung.",
            custom.notice,
        )
        assertEquals(3.0, SettingsMigration.migrate(5.0).excessDb)
        assertEquals(SettingsMigration.Result(6.5, null), SettingsMigration.migrate(null)) // fresh install
        assertEquals(6.5, AppSettings().eventExcessDb)
        assertEquals(30, AppSettings().localFloorWindowSeconds)
        assertEquals(0.93, AppSettings().windLfShareMin)
        assertEquals(4.5, AppSettings().windFlutterMinDb)
        assertTrue(AppSettings().showWindEvents)
    }

    // ---- Re-evaluation: scope, entity mapping --------------------------------------------------

    private fun minuteEntity(id: Long, source: String, calibrationId: Long?, offset: Double, laeq: Double? = 40.0) = MinuteEntity(
        id = id, startEpochMs = id * 60_000, startIso = "2026-10-02T23:0$id:00+02:00", durationSeconds = 60.0,
        laeqDb = laeq, lafMaxDb = laeq?.plus(5), lafMinDb = laeq?.minus(5), l1Db = laeq?.plus(4), l10Db = laeq?.plus(2),
        l50Db = laeq, l90Db = laeq?.minus(2), eventCount = 3, dominantCategory = "road_traffic",
        categorySharesJson = "{\"road_traffic\":0.75,\"unclassified\":0.25}", classifierFrames = 60, calibrationId = calibrationId,
        calibrationOffsetDb = offset, audioSource = source, calibrated = calibrationId != null, validSeconds = 58.5,
        coverage = 58.5 / 60, clockCorrections = 1,
    )

    private fun eventEntity(id: Long, source: String, calibrationId: Long?) = EventEntity(
        id = id, startEpochMs = id * 60_000, startIso = "2026-10-02T23:0$id:00.000+02:00", durationSeconds = 2.0,
        lafMaxDb = 60.0, selDb = 61.0, backgroundDb = 40.0, thresholdDb = 10.0, dominantCategory = "road_traffic",
        dominantScore = 0.8f, topLabelsJson = "[{\"label\":\"Car\",\"score\":0.8}]", classifierFrames = 2,
        calibrationId = calibrationId, audioSource = source, calibrated = calibrationId != null, minLevelDb = 30.0,
    )

    @Test
    fun scopeSqlSelectsSameSourceAndOtherCalibration() {
        val target = Recalibration.Target(5L, 121.95, "UNPROCESSED")
        val rows = listOf(
            minuteEntity(1, "UNPROCESSED", null, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB),
            minuteEntity(2, "UNPROCESSED", 3L, 118.0),
            minuteEntity(3, "UNPROCESSED", 5L, 121.95),
            minuteEntity(4, "VOICE_RECOGNITION", null, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB),
            minuteEntity(5, "VOICE_RECOGNITION", 3L, 118.0),
            minuteEntity(6, "VOICE_RECOGNITION", 5L, 121.95),
        )
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute(MigrationSql.CREATE_MINUTES_V4)
                for (r in rows) {
                    st.execute(
                        "INSERT INTO minutes (id, startEpochMs, startIso, durationSeconds, eventCount, categorySharesJson, classifierFrames, " +
                            "calibrationId, calibrationOffsetDb, audioSource, calibrated, validSeconds, coverage, clockCorrections) VALUES " +
                            "(${r.id}, 0, '', 60, 0, '{}', 0, ${r.calibrationId ?: "NULL"}, ${r.calibrationOffsetDb}, '${r.audioSource}', 0, 60, 1, 0)"
                    )
                }
            }
            val sql = "SELECT id FROM minutes WHERE " + RecalibrationSql.SCOPE.replace(":source", "?").replace(":calibrationId", "?") + " ORDER BY id"
            val selected = ArrayList<Long>()
            c.prepareStatement(sql).use { ps ->
                ps.setString(1, target.audioSource); ps.setLong(2, target.calibrationId)
                ps.executeQuery().use { rs -> while (rs.next()) selected += rs.getLong(1) }
            }
            assertEquals(listOf(1L, 2L), selected)
            assertEquals(rows.filter { Recalibration.inScope(it.audioSource, it.calibrationId, target) }.map { it.id }, selected)
        }
    }

    @Test
    fun entityRecalibrationKeepsEverythingElseAndRoundTrips() {
        val target = Recalibration.Target(5L, 121.95, "UNPROCESSED")
        val e = minuteEntity(2, "UNPROCESSED", null, Acoustics.DEFAULT_CALIBRATION_OFFSET_DB)
        val r = RecalibrationMapping.minute(e, target)
        val d = 121.95 - Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
        assertEquals(2L, r.id)
        assertEquals(40.0 + d, r.laeqDb!!, 1e-9)
        assertEquals(38.0 + d, r.l90Db!!, 1e-9)
        assertEquals(40.0, r.origLaeqDb); assertEquals(45.0, r.origLafMaxDb); assertEquals(35.0, r.origLafMinDb)
        assertEquals(44.0, r.origL1Db); assertEquals(42.0, r.origL10Db); assertEquals(40.0, r.origL50Db); assertEquals(38.0, r.origL90Db)
        assertEquals("default", r.recalibratedFromId)
        assertEquals(Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, r.recalibrationOffsetDb)
        assertEquals(5L, r.calibrationId); assertEquals(121.95, r.calibrationOffsetDb); assertTrue(r.calibrated)
        // Everything that is not a level stays as recorded (event_count included).
        val unchanged = e.copy(
            laeqDb = r.laeqDb, lafMaxDb = r.lafMaxDb, lafMinDb = r.lafMinDb, l1Db = r.l1Db, l10Db = r.l10Db, l50Db = r.l50Db,
            l90Db = r.l90Db, calibrationId = 5L, calibrationOffsetDb = 121.95, calibrated = true,
            origLaeqDb = 40.0, origLafMaxDb = 45.0, origLafMinDb = 35.0, origL1Db = 44.0, origL10Db = 42.0, origL50Db = 40.0,
            origL90Db = 38.0, recalibratedFromId = "default", recalibrationOffsetDb = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB,
        )
        assertEquals(unchanged, r)
        // A second, later calibration computes from orig_* (via the entity round trip).
        val c2 = Recalibration.Target(6L, 119.0, "UNPROCESSED")
        assertEquals(RecalibrationMapping.minute(e, c2), RecalibrationMapping.minute(r, c2))
        assertEquals(r, RecalibrationMapping.minute(r, target))
        // A minute without valid audio keeps NULL levels.
        val empty = RecalibrationMapping.minute(minuteEntity(3, "UNPROCESSED", null, 112.35, laeq = null), target)
        assertEquals(null, empty.laeqDb); assertEquals(null, empty.origLaeqDb); assertEquals("default", empty.recalibratedFromId)
    }

    @Test
    fun eventRecalibrationUsesItsCalibrationsOffset() {
        val target = Recalibration.Target(5L, 121.95, "UNPROCESSED")
        val offsets = mapOf(3L to 118.0, 5L to 121.95)
        val calibrated = RecalibrationMapping.event(eventEntity(1, "UNPROCESSED", 3L), offsets, target)
        assertEquals(60.0 + 3.95, calibrated.lafMaxDb, 1e-9)
        assertEquals(61.0 + 3.95, calibrated.selDb, 1e-9)
        assertEquals(40.0 + 3.95, calibrated.backgroundDb, 1e-9)
        assertEquals(60.0, calibrated.origLafMaxDb); assertEquals(61.0, calibrated.origSelDb); assertEquals(40.0, calibrated.origBackgroundDb)
        assertEquals("3", calibrated.recalibratedFromId); assertEquals(118.0, calibrated.recalibrationOffsetDb)
        assertEquals(5L, calibrated.calibrationId); assertTrue(calibrated.calibrated)
        assertEquals(30.0, calibrated.minLevelDb); assertEquals(10.0, calibrated.thresholdDb)
        assertEquals("[{\"label\":\"Car\",\"score\":0.8}]", calibrated.topLabelsJson)
        val fromDefault = RecalibrationMapping.event(eventEntity(2, "UNPROCESSED", null), offsets, target)
        assertEquals(60.0 + 121.95 - Acoustics.DEFAULT_CALIBRATION_OFFSET_DB, fromDefault.lafMaxDb, 1e-9)
        assertEquals("default", fromDefault.recalibratedFromId)
    }

    // ---- Event floor follows the calibration: confirmation text ---------------------------------

    @Test
    fun floorConfirmationText() {
        val d = Acoustics.DEFAULT_CALIBRATION_OFFSET_DB
        assertEquals(
            "Kalibrierung gespeichert. Offset +9.6 dB gegenüber vorher; der Mindestpegel für Ereignisse wurde von 30.0 auf 39.5 dB(A) angepasst.",
            CalibrationTexts.floorFollowed("Kalibrierung gespeichert.", EventFloor.adjust(30.0, d, d + 9.6, 20.0, 70.0)),
        )
        assertEquals(
            "Kalibrierung gespeichert. Offset +9.6 dB gegenüber vorher; der Mindestpegel für Ereignisse wurde von 65.0 auf 70.0 dB(A) " +
                "angepasst (begrenzt auf den Einstellbereich 20–70 dB(A); rechnerisch 74.5 dB(A)).",
            CalibrationTexts.floorFollowed("Kalibrierung gespeichert.", EventFloor.adjust(65.0, d, d + 9.6, 20.0, 70.0)),
        )
        assertEquals(
            "Kalibrierung gespeichert. Offset ±0.0 dB gegenüber vorher; der Mindestpegel für Ereignisse bleibt bei 30.0 dB(A).",
            CalibrationTexts.floorFollowed("Kalibrierung gespeichert.", EventFloor.adjust(30.0, 118.0, 118.0, 20.0, 70.0)),
        )
        assertEquals(
            "Auf Standard-Offset zurückgesetzt (unkalibriert). Offset −9.6 dB gegenüber vorher; der Mindestpegel für Ereignisse " +
                "wurde von 39.5 auf 30.0 dB(A) angepasst.",
            CalibrationTexts.floorFollowed("Auf Standard-Offset zurückgesetzt (unkalibriert).", EventFloor.adjust(39.5, d + 9.6, d, 20.0, 70.0)),
        )
        assertEquals(
            "Betrifft 1 Minute und 12 Ereignisse mit derselben Audioquelle, aufgenommen ohne oder mit einer anderen Kalibrierung. " +
                "Die Originalwerte bleiben gespeichert.",
            CalibrationTexts.recalScope(1, 12),
        )
        assertEquals("480 Minuten und 1 Ereignis", "${CalibrationTexts.minutes(480)} und ${CalibrationTexts.events(1)}")
    }
}
