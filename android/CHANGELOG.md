# Changelog

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
