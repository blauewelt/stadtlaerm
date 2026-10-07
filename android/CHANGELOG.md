# Changelog

## 0.3.1 — 2026-10-07

Update check that keeps the app without any internet permission.

- **Design.** The app still has no internet permission: «Nach Update suchen» only opens
  `stadtlaerm.ch/update.html#v=…&c=…` in the browser. The page compares the installed version
  from the URL fragment, which is never sent to the server, with the published one in the
  browser.
- **Einstellungen → App-Version:** «Stadtlärm 0.3.1 (Build vom 7.10.2026)» and the button «Nach
  Update suchen» («Kein Browser gefunden» if no browser is installed).
- **Offline age reminder:** from 30 days after the build date, Einstellungen and the Messen
  screen show «Diese App-Version ist n Tage alt. Nach Update suchen?»; «Später» hides it for 14
  days. New `BuildConfig.BUILD_DATE` (date of the Gradle build, `SOURCE_DATE_EPOCH` overrides).
- Website: new page `update.html` (one inline script, allowed by its SHA-256 in the
  Content-Security-Policy), linked in the footer; the privacy page explains the comparison.
  Release checklist in the README.

## 0.3.0 — 2026-10-07

Test version: a chart of the measured noise, an absolute floor for events, and a classification
rule that no longer drops quiet traffic into «unklassifiziert».

- **Chart («Nächte»).** A chart of the measured levels over time, drawn by the app itself (no
  chart library): the background band L90–L10, the LAeq line and every event as a dot at its
  LAFmax. Range buttons **Nacht · Tag · Woche** and ‹ › navigation (or swipe sideways on the
  chart); the screen opens on the running night, else on the most recent night with data. The
  line and band break wherever a minute is missing or has < 50 % valid audio; gaps of 10 min
  or more are marked «keine Messung». The week view shows hourly values (energy mean; an hour
  needs 30 valid minutes) and only events of the highlighted category ≥ 60 dB(A), at most the
  loudest 300. One category is highlighted in orange (default: Töff & Poser; a scrolling row of
  chips below the chart, remembered); the others are grey. Tap for a tooltip with the minute's
  levels or the event's details (with weekday and date in the day and week views); a summary row
  shows LAeq, event count and the loudest event of the window, plus the measured share of the
  time, **events per hour** of valid measurement, the **dynamics** (median over the valid minutes
  of L10 − L90) and any interruptions. The night list shows events/h and dynamics too. Events of
  other categories are drawn as smaller grey dots (6 dp) so a night with hundreds of quiet
  pass-bys does not bury the LAeq line. Tapping a night in the list opens it in the chart.
- **Event floor.** New setting «Mindestpegel für Ereignisse (LAFmax)», 20–70 dB(A), default 30.
  An event is only kept if its LAFmax reaches the floor: this filters noise at the phone itself
  (typing, breathing ≈ 20–30 dB) while quiet pass-bys stay (on a real windowsill night, highway
  passes peaked at a median of 37 dB(A), 13 dB above a 20 dB background). The floor in force is stored with each event (`min_level_db`,
  also in the events CSV). Events recorded before 0.3.0 are filtered with the current floor when
  the night list and the chart are shown, so both agree; their CSV export is unchanged (empty
  `min_level_db`).
- **Category rule.** If the top-1 AudioSet label belongs to a category and scores ≥ 0.10
  (`top1_threshold`), that category wins; otherwise the best category if its score ≥ 0.20
  (`category_threshold`, formerly `threshold`, now inclusive); otherwise «unklassifiziert». Before,
  only the 0.2 rule applied, and on a real night 328 of 440 events whose best label was «Vehicle»
  (scores 0.1–0.3) ended unclassified. Tie-break unchanged (Töff & Poser first). Applies to new
  measurements; stored events keep their category.
- Database v3 (migration adds `events.min_level_db`).
- New Gradle module `chart/` (drawing model, Compose chart, app theme) with JVM tests and
  Paparazzi renders.


## 0.2.0 — 2026-10-06

First signed public release. No functional changes to measurement, classification or storage.

- **Signed with the project's release key** (`CN=Christian Frank, O=Stadtlaerm, L=Zuerich, C=CH`,
  certificate SHA-256 `a3d5d10e…3142be`). Earlier builds were debug builds signed with a
  development key; Android cannot update across signing keys, so **uninstall any earlier
  Stadtlärm build first** (export your data before, uninstalling deletes it).
- Release APK contains only the phone ABIs (`arm64-v8a`, `armeabi-v7a`); the `x86_64` emulator
  ABI is now in debug builds only. Code shrinking stays off.
- Repository restructured as a monorepo (`android/`, `firmware/`, `docs/` for the website
  [stadtlaerm.ch](https://stadtlaerm.ch)); the APK is published at
  `https://stadtlaerm.ch/download/stadtlaerm.apk`.
- Optional release signing in Gradle (see README, "Signed release build"); without a key the
  release build is unsigned, so the public repository builds for everyone.

## 0.1.1 — 2026-10-06

Review fixes; not yet tested on a device.

- **Silenced microphone.** On Android 10+ a call or voice assistant makes the app receive zeros.
  This is now detected (`AudioRecordingConfiguration.isClientSilenced` on API 29+, plus a
  digital-silence check on all versions: ≥ 10 ms of exact zeros or a level below −130 dBFS per
  125 ms block). That time, plus 0.5 s of filter recovery, is excluded from LAeq, LAF statistics,
  percentiles, event detection and the background L90; classifier windows touching it are
  ignored. Minutes store `validSeconds`/`coverage`; nights leave out minutes with < 50 %
  coverage and show coverage; CSV has `valid_s`, `coverage` and `clock_corrections` columns.
- **Timestamps.** Sample time is anchored to the wall clock at the first delivered audio block
  (not when the engine is created) and re-anchored once per minute if drift exceeds 500 ms
  (latency-robust estimate: minimum over the minute). Corrections are counted per minute.
  Minute boundaries stay aligned to wall-clock minutes.
- **Calibration integrity.** A calibration measurement is aborted, with a message, and cannot be
  saved if the app goes to the background (ON_STOP) or the microphone is silenced. Starting a
  new run discards the previous unsaved result of that method.
- **Event classification.** The default classifier interval is (and stays) 1 s. When an event
  ends, the classifier results overlapping it are used immediately; if none overlapped, one
  extra classification is run right at event end (previously the app waited up to 3 s, or for the
  next periodic window).
- **Foreground service.** If the microphone permission is missing when the service starts, it is
  promoted as a `shortService` (API 34+) and stopped cleanly instead of crashing.
- **Smaller fixes.** Night length is computed from actual local 22:00–06:00 (7 h / 9 h on DST
  nights). Events are counted in the minute in which they start (a minute is held until a pending
  candidate is confirmed or discarded). Database insert failures are caught and logged by
  exception type only. The "Pegelanpassung" settings text now describes exactly what it does,
  and the setting is read at measurement start like all others.
- **Database v2** with a migration from v1 (level columns nullable for minutes without valid
  audio; new columns `validSeconds`, `coverage`, `clockCorrections`).
- Also included from the independent review (commit 7ffa218): LiteRT interpreter closed on its
  own thread, stop-during-start race, capture cleanup on failed start, calibration clipping check,
  battery settings fallback.

## 0.1.0 — 2026-10-06

First version: A-weighted LAF/LAeq/Ln measurement, event detection, YAMNet source categories,
calibration (reference meter, acoustic calibrator, manual), night summaries, CSV export.
