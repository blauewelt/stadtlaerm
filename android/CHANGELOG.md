# Changelog

## 0.4.0 — 2026-10-09 (two editions since 2026-10-10)

### Two editions of the published app (`ch.stadtlaerm.app`, version 0.4.0, code 10)

Since 2026-10-10 the app is published in two editions, same app id, version and signing key, so
each installs over the other in both directions and keeps the measurements:

- **Stadtlärm offline** (`download/stadtlaerm-offline.apk`, Gradle flavour `offline`): exactly
  what 0.3.3 promised: no `INTERNET`, no `ACCESS_NETWORK_STATE` (the v0.3.3 permission set), no
  upload code in the APK (`app/src/karte/` is not compiled into it), no server host string.
  Einstellungen shows «Stadtlärm 0.4.0 (offline)» and, where «Messwerte teilen» would be, one line
  pointing to the other edition.
- **Stadtlärm mit Lärmkarte** (`download/stadtlaerm-karte.apk`, flavour `karte`): everything
  below, i.e. the opt-in «Messwerte teilen». Einstellungen shows «Stadtlärm 0.4.0 (mit Lärmkarte)».

Why: «no `INTERNET` permission» is a promise anyone can verify with `aapt2 dump badging`; «upload
off by default» has to be trusted. The 2026-10-09 build (`download/stadtlaerm.apk`, upload
included) is replaced by these two files; it is the same app as «mit Lärmkarte».
«Nach Update suchen» now appends `&e=<edition>` to the update page's fragment, so the page offers
the matching APK first. Code: the upload (`upload/`, `ui/ShareScreen.kt`) moved from `src/main/`
to `src/karte/`; per-flavour `edition/Edition.kt` objects hook it into the shared code; WorkManager
and `BuildConfig.STADTLAERM_API` exist only in the karte variant; upload tests moved to
`src/testKarte/`.

### «mit Lärmkarte»: what 0.4.0 adds to 0.3.3

In short: an opt-in «Messwerte teilen» sends the measured levels to the shared noise map — off by
default; the `INTERNET` permission is added for exactly this. The location is placed on the phone
to a 100 m × 100 m hectare square; «Meine Daten auf dem Server löschen» deletes everything sent;
«Neue Kennung» starts over with a new device id. Nothing in the measurement changed.

- **«Messwerte teilen» (opt-in upload for the shared noise map).** New screen
  Einstellungen → Messwerte teilen (server/DESIGN.md §2, §4, §9): an explanation of what is sent,
  what is never sent (audio, the precise location, name, the raw classifier labels), where it goes
  (api.stadtlaerm.ch, Switzerland) and how to delete; the placement step (coordinates in, hectare
  out, on the phone; placement, floor, street side, note); the switch (off by default), «Nur über
  WLAN» (default on), «Zuletzt gesendet», the minutes still to send, «Meine Daten auf dem Server
  löschen» and «Neue Kennung». Uploads run 5 min after every full hour and 15 s after a measurement
  stops (WorkManager), in batches of ≤ 1440 minutes / ≤ 2000 events, resume from the last
  acknowledged start, and never resend records the server refused.
- **Permissions:** «mit Lärmkarte» has `INTERNET` and `ACCESS_NETWORK_STATE`, used only by
  `upload/UploadClient.kt` for one host (the offline edition has neither). `ACCESS_WIFI_STATE` and `RECEIVE_BOOT_COMPLETED` stay
  removed; no location permission. PRIVACY.md, the READMEs and the website say so.
- New dependency («mit Lärmkarte» only): `androidx.work:work-runtime-ktx` 2.9.1 (AndroidX, scheduling only). The network
  code uses the platform's `HttpURLConnection`; no third-party SDK.
- `dsp`: `geo/Lv95.kt`, WGS84 ↔ LV95 and hectare ids, the same formulas as the map's
  `docs/map/lv95.js`, tested against swisstopo's reference points.
- Nothing in the measurement path changed. The database schema is unchanged (three read-only
  queries added).

### «Stadtlärm Labor» (`ch.stadtlaerm.labor`, `0.4.0-labor`, never published)

- No functional change: no network permission (its manifest removes `INTERNET` and
  `ACCESS_NETWORK_STATE`), «Messwerte teilen» is not offered; since the two-editions build it
  contains no upload code and no WorkManager at all. «Nach Update suchen» sends `e=labor`.

## 0.3.4 — 2026-10-08

### Public app (`ch.stadtlaerm.app`, not published)

- **No functional change.** Version 0.3.4 (code 8) only so that both flavours share one version.
  The chart gained optional, flavour-agnostic hooks for event clips (a clip reference per event,
  an `onPlayClip` callback); the public app passes none, so it draws and behaves exactly as in
  0.3.3. This build is not published; stadtlaerm.ch stays on 0.3.3.

### «Stadtlärm Labor» (`ch.stadtlaerm.labor`, `0.3.4-labor`, never published): listen to clips

- **Chart:** events that have a clip get a thin ring around their dot (the dot colour keeps its
  meaning); their tooltip has an «Abspielen» button. The clip of an event is found through the
  manifest (event id, checked against the event's start time), indexed once and cached until the
  manifest or the clips folder changes.
- **Player («Clip» sheet):** event time, category, LAFmax, event duration, clip length; a bar of
  the clip with the pre-roll and post-roll shaded and the event span highlighted (ticks where
  classifier windows end), tap/drag or the scrub bar to seek; play/pause; «‹ Vorheriger» /
  «Nächster ›» step to the neighbouring event with a clip in the chart window (by time); «Teilen»
  shares the WAV (FileProvider). Below the bar: the classifier result of the second being heard,
  read from the clip's `classifierTrace` (top-3 labels with scores, the category decision, the
  input gain, LAF). Played on the media stream (`USAGE_MEDIA`, MediaPlayer, transient audio
  focus); a hint appears if the media volume is 0 or the phone is on silent, and while a
  measurement runs (the speaker is measured too). Playback only, the microphone is not touched.
- **Einstellungen → Labor → «Clips anhören»:** all clips newest first or only the current night's
  (time, category, LAFmax, duration, whether a trace exists), category filter chips, count and
  total size; tapping plays it, «‹»/«›» step through the filtered list.
- **Messen → «Letzte Ereignisse»:** the red record dot is now a button that plays the clip.
- Robustness: a missing or corrupt WAV shows «Clip nicht gefunden»; the player is released when
  the sheet closes, when a measurement starts or stops, and pauses when the app goes to the
  background. Clips without a manifest line (only the file) still play, with the time from the
  file name.
- New JVM tests (`app/src/testLabor/…/ClipPlaybackLogicTest.kt`): manifest clip index (event id →
  file, trace), neighbour order, trace lookup for a playback position, progress-bar geometry
  (also truncated clips), WAV check; chart: dot accent and «Abspielen» only with a clip reference.

## 0.3.3 — 2026-10-07

### Public app (`ch.stadtlaerm.app`, published)

- **Notification while measuring:** title «Mikrofon aktiv – Messung läuft», text with the current
  LAeq as before, «Stopp» button. The notification channel now has importance *default*, so the
  icon stays visible in the status bar (no sound, no vibration). Because Android cannot raise the
  importance of an existing channel, the channel has a new id (`measurement_active`); the old one
  is removed.
- **New setting «Messung beenden, wenn die App geschlossen wird»** (Einstellungen → Messung, off
  by default). Off: as before, the measurement keeps running as a background service when the app
  is swiped away. On: swiping the app away stops the measurement exactly like «Stopp»
  (`Service.onTaskRemoved`), including the current partial minute. A stop now stays in the
  foreground until the last minute and event are in the database (at most 3 s).
- **Two product flavours** (Gradle dimension `edition`): `public` (this app) and `labor` (below).
  The measurement service hands the audio blocks, the event lifecycle and the classifier results
  to an `AudioTap`, which in the public app is always the no-op `NoAudioTap`; the engine reports
  sample indices and classifier results through new no-op listener hooks. **No change to the
  measurement**, same manifest and permissions (no `INTERNET`). The public APK contains no
  recording code; how to check is described in PRIVACY.md → «Labor-Build». APK paths now include
  the flavour (`app/build/outputs/apk/public/release/app-public-release.apk`).

### New: «Stadtlärm Labor» (`ch.stadtlaerm.labor`, `0.3.3-labor`, never published)

A diagnostics build that can record audio to debug the event detector and the sound-source
classifier, only after «Einstellungen → Labor → Audio während der Messung aufzeichnen» is
switched on and confirmed. Red icon and a red banner on every screen («LABOR-VERSION – kann Audio
aufzeichnen», «Aufnahme läuft»).

- Event clips: WAV 16 kHz, 16-bit, 5 s before to 5 s after the event, at most 60 s (flagged
  `truncated`), from a 6 s ring buffer of the 48 kHz input; clip rate every / every 2nd / every
  5th event.
- Continuous recording: AAC-LC 16 kHz 64 kbit/s, one M4A per wall-clock hour.
- Classifier trace: every clip's manifest line carries the classifier's per-second results inside
  the clip (top-5 labels and scores, category decision and score, input gain, LAF); every
  classifier run of the measurement is also logged to `classifier/<yyyyMMdd_HH>.jsonl`.
- `manifest.jsonl`: session start/stop (device, versions, audio chain, calibration, event
  threshold and floor, settings, drop counts), sample clock and clock corrections, per-clip event
  data, per-file sample mapping.
- Storage cap (default 2 GB; recording stops, measuring continues), used space and file counts,
  «Ordner anzeigen», «Als ZIP teilen» (clips, manifest, classifier logs; hour files only under
  500 MB) and «Alle Aufnahmen löschen». Events with a clip get a red dot in «Letzte Ereignisse».
- All of it lives in `app/src/labor/`; JVM tests in `app/src/testLabor/`.

## 0.3.2 — 2026-10-07

The event floor follows the calibration, and old measurements can be re-evaluated with a new
calibration.

- **Event floor follows the calibration.** When a calibration is saved and becomes active for this
  device and audio source (also «Auf Standard»), the floor «Mindestpegel für Ereignisse» moves by
  the change of the offset (new − previous, the default if none was active), rounded to 0.5 dB and
  limited to 20–70 dB(A). The calibration screen confirms it: «Kalibrierung gespeichert. Offset
  +9.6 dB gegenüber vorher; der Mindestpegel für Ereignisse wurde von 30.0 auf 39.5 dB(A)
  angepasst.» The setting now moves in 0.5 dB steps and is shown with one decimal.
- **Re-evaluate old measurements («nachträglich kalibriert»).** After saving a calibration the app
  offers to re-evaluate earlier minutes and events with the same audio source that were measured
  without or with another calibration (dialog «Neu bewerten» / «Nicht jetzt»; also «Daten → Alte
  Messungen neu bewerten»). All level columns move by (new − original offset); the original values
  are kept (`orig_*`, `recalibrated_from_id`, `recalibration_offset_db`) and later re-evaluations
  always start from them, so corrections never compound. Runs in the background with a progress
  bar, in one transaction. `event_count` stays as recorded (the night list and the chart apply the
  current floor at read time).
- Re-evaluated data counts as calibrated; the night list and the chart summary note «nachträglich
  kalibriert», and so does the chart tooltip.
- CSV export: the new columns are appended to both files (earlier columns unchanged).
- Database v4 (migration adds the nullable re-evaluation columns to `minutes` and `events`).

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
