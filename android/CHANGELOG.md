# Changelog

## 0.5.0 — unreleased (test build, versionCode 11, not published)

0.5.0 = 0.4.0 (detector v2, below) + the opt-in «Messwerte teilen» for the shared noise map. The
sharing feature was developed on the `map-design` branch as an unpublished "0.4.0" (code 10); it
never shipped under that number. The server runs at api.stadtlaerm.ch; the website still offers
0.4.0.

### Two published editions

- **Offline-Version** (flavour `offline`, `stadtlaerm.apk`, the default download): the public app
  **without** `INTERNET` and `ACCESS_NETWORK_STATE` and without «Messwerte teilen»
  (`UPLOAD_AVAILABLE = false`). Data leaves the phone only through the existing exports. 0.4.0
  users get it as their normal update, with no new permission.
- **Karten-Version** (flavour `public`, `stadtlaerm-karte.apk`): the public app as described below,
  with the opt-in «Messwerte teilen».
- Both have app id `ch.stadtlaerm.app`, version 0.5.0 / code 11 and the same key: installing one over
  the other switches the edition and keeps the data. `BuildConfig.EDITION` (`offline` | `karte` |
  `labor`) is shown in Einstellungen → App-Version with a sentence on how to switch, and «Nach Update
  suchen» adds `&e=<edition>` so update.html offers the same edition. The Offline-Version, started
  after a Karten-Version with sharing on, switches sharing off and cancels the upload jobs.
- **Downloads via GitHub Releases:** both APKs are attached to the release `v0.5.0` under fixed
  names; the website links there (GitHub counts downloads per file, `scripts/download_stats.py`).
  `docs/download/stadtlaerm.apk` remains as a fallback for old links (Offline-Version).
- **Server:** `stats.json` next to the map files (`/v1/map/stats.json`, `python -m
  stadtlaerm_server stats`): project-wide counts only (registered/active/sharing devices,
  deletions, hectares, shared nights, app versions, registrations per week; server/DESIGN.md §6.4).
  Schema v2 adds a single deletion counter. `scripts/usage_report.py` combines both sources.

### Added by the merge

- **Wind events are not shared.** Events flagged as wind by detector v2 are filtered out on the
  phone before upload (`upload/Payloads.kt`), the same way they are left out of the app's own
  counts. The minute field `event_count` sent to the server is detector v2's count without wind
  (the minute's `wind_event_count` is not sent). The payload fields are unchanged
  (server/DESIGN.md §4.3/§4.4); none of the new detector features (local floor, excess, rise,
  decay, LF share, …) are sent. `threshold_db` in the event payload is now detector v2's excess
  over the local floor.
- **Labor about text:** the card «Über Stadtlärm & Datenschutz» no longer claims in the Labor
  edition that audio never leaves the working memory; it says that Labor can store clips and hour
  files on the phone when switched on and never sends anything (no internet permission). The
  public text is unchanged.

### Public app, Karten-Version (`ch.stadtlaerm.app`, version 0.5.0, code 11; not yet published)

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
- **Permissions:** the Karten-Version now has `INTERNET` and `ACCESS_NETWORK_STATE` (the
  Offline-Version has neither), used only by
  `upload/UploadClient.kt` for one host. `ACCESS_WIFI_STATE` and `RECEIVE_BOOT_COMPLETED` stay
  removed; no location permission. PRIVACY.md, the READMEs and the website say so.
- New dependency: `androidx.work:work-runtime-ktx` 2.9.1 (AndroidX, scheduling only). The network
  code uses the platform's `HttpURLConnection`; no third-party SDK.
- `dsp`: `geo/Lv95.kt`, WGS84 ↔ LV95 and hectare ids, the same formulas as the map's
  `docs/map/lv95.js`, tested against swisstopo's reference points.
- Nothing in the measurement path changed by the sharing feature. The database schema stays at v5
  (detector v2); the sharing feature only adds read-only queries.

### «Stadtlärm Labor» (`ch.stadtlaerm.labor`, `0.5.0-labor`, never published)

- No functional change: no network permission (its manifest removes `INTERNET` and
  `ACCESS_NETWORK_STATE`), «Messwerte teilen» is not offered.

## 0.4.0 — 2026-10-09 (detector v2; both flavours, versionCode 9; public app published on stadtlaerm.ch)

Why: in one recorded night (528 event clips, uncalibrated) the 5-minute L90 sat a median 3.1 dB
below the level right around an event, so «background + 10 dB» fired on nothing in particular and a
third of the events barely exceeded the local hum; 14 % of the events were wind on the microphone;
and the constant highway hum was mixed into the event figures.

### Event detection

- **Local floor:** L90 of LAF over the trailing 30 s (setting «Lokaler Hintergrund: Fenster»,
  10–60 s), updated every 125 ms, frozen while an event runs. Events start at **local floor +
  excess** (setting «Ereignis-Schwelle über lokalem Hintergrund», default 6.5 dB, 3–20 dB in 0.5 dB
  steps), end 3 dB below, need ≥ 0.5 s and the event floor (unchanged, 30 dB(A)), at most 300 s.
  Detection starts after 5 s of history (was 30 s). The 5-min background is still computed, stored
  with every event and shown live. The old setting is migrated once: excess = max(3, threshold −
  3.5) (10 → 6.5, 7 → 3.5); a changed threshold is announced once (Messen and Einstellungen).
- **Features per event:** excess over the floor, rise and decay time (10↔90 %), jaggedness,
  A-weighted 250 Hz–4.5 kHz rise against the 5 s before, unweighted 20–200 Hz energy share and
  low-frequency flutter (definitions in README → Event detection). Computed with five 2nd-order
  Butterworth sections per sample and per-tick sums (no FFT, no allocation per sample). For the
  decay the level is followed up to 5 s past the end; events are reported after that.
- **Wind flag:** `lf_share ≥ 0.93` or `lf_flutter_db ≥ 4.5` (both under «Experten»; fitted on the
  recorded night, see Validation). Wind events are
  stored with category «Wind» (`wind`) and exported, but excluded from events/h, `event_count`, the
  category counts and highlights, the loudest event and the night's events. The chart draws them as
  small hollow grey dots («Wind (ausgeschlossen)», can be hidden).
- **Shape** (informational): hump, jagged, impulse or long.

### Data

- Database v5 (migration from v4, tested against SQLite): events gain `local_floor_db`, `excess_db`,
  `rise_s`, `decay_s`, `jaggedness`, `mid_band_rise_db`, `lf_share`, `lf_flutter_db`, `wind`, `shape`;
  minutes gain `local_floor_db` (median over the minute) and `wind_event_count`. Existing data keeps
  its values (no features, not wind).
- CSV exports: the same columns appended at the end. `threshold_db` is now the excess over the local
  floor (before: the threshold over the 5-min background). `event_count` counts bursts without wind.
- Re-evaluation with a later calibration also moves the local floor (no new `orig_` columns needed).

### Display

- Chart summary tiles: «Hintergrund (L90)» (the hum: median of the minutes' L90), «Ereignisse»
  (bursts per hour without wind, «n Wind ausgeschlossen» below), «Lautestes» (loudest burst). The
  line below has the LAeq, the number of events (and of the highlighted category), coverage,
  dynamics and gaps. The night list shows the hum and events/h for every night and the number of
  excluded wind events. The tooltip of an event shows the local floor and excess, and the shape
  (wind: LF share and flutter). Messen shows the live local floor.

### «Stadtlärm Labor» (`0.4.0-labor`)

- `AudioTap.onEventEnded` carries the features; the manifest gets an `event` line for every event
  (also without a clip), a `features` object in `clip` lines, and the detector settings in
  `session_start` (`eventExcessDb`, `localFloorWindowS`, wind thresholds instead of
  `eventThresholdDb`). Clips are cut back to end + 5 s when the end is reported late.

### Validation (one recorded night, Labor build)

- The night's continuous recording (8.7 h) replayed through the engine and matched to the 528
  weakly labelled clips: at excess 6.5 dB / floor 30 dB(A) 64 % of the vehicle passes are kept and
  84 % of the "background" events dropped (floor 20: 80 % / 72 %); 5 dB keeps the background too,
  8 dB loses half of the passes. Wind thresholds fitted to the labels: 0.93 / 4.5 dB (97.4 %
  agreement on 340 events; first guess 0.95 / 3.8 dB: 96.2 %). Details: README → Event detection.

### Development

- Offline replay harness (`dsp/src/test/.../Replay.kt`): WAV (16 kHz upsampled ×3 polyphase, or
  48 kHz) → engine → events/minutes CSV; three synthetic fixtures (smooth hump, LF wind thump,
  steady tone) with expected outputs; `./gradlew :dsp:replay --args="clip.wav"` for real clips,
  `:dsp:replayBatch` for all clips of a Labor manifest and `:dsp:replayNight` for a whole night's
  continuous recording (one CSV each; the audio stays outside the repository).

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
