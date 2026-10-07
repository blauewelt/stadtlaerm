# Stadtlärm — citizen noise measurement for Zürich (Android, v0.3.0)

Stadtlärm turns an Android phone into a night-time noise logger. It measures A-weighted
sound levels the way a sound level meter does (IEC 61672-1 A-weighting, Fast time weighting),
detects individual noise events (a motorbike, a shouting group, a tram), tags each with its
likely source using an on-device sound classifier, and summarises every night
(22:00–06:00, the Swiss night period).

**Privacy by construction:** no audio is ever stored or sent, and the app has no internet
permission at all. See [PRIVACY.md](../PRIVACY.md).

The UI is German (Swiss spelling); the code and docs are English. License: [Apache-2.0](../LICENSE).

This is the `android/` part of the [Stadtlärm repository](../README.md); paths and commands
below are relative to this directory.

---

## What it measures

| Quantity | Definition in this app |
|---|---|
| LAF | A-weighted level with Fast time weighting (exponential, τ = 125 ms), sampled every 125 ms |
| LAeq,1s / LAeq,1min | Energy-equivalent A-weighted level over 1 s / 1 min (from the un-time-weighted signal) |
| LAFmax / LAFmin | Max/min of LAF in the period (max from the continuous Fast signal, min from the 125 ms samples) |
| L1, L10, L50, L90 | Level exceeded 1/10/50/90 % of the time, from the 480 LAF samples of the minute (so L10 > L90) |
| Event | LAF > background + threshold (default 10 dB) for ≥ 0.5 s; ends below background + threshold − 3 dB. Background = L90 of LAF over the trailing 5 min, frozen at event start. Kept only if its LAFmax also reaches the absolute **event floor** (setting «Mindestpegel», default 45 dB(A), 30–70) |
| SEL (LAE) | Sound exposure level of the event, re 1 s: `10·log10(Σ 10^(Leq_tick/10)·0.125 s)` |
| Valid time / coverage | Seconds of a minute with a real microphone signal. Time while Android silences the mic (phone call, voice assistant — the app then receives zeros) or while the input is digital silence, plus 0.5 s of filter recovery, is excluded from every level, percentile, event and the background |
| Night LAeq | Energy average over the valid time of all minutes that start between 22:00 and 06:00 local time; minutes with < 50 % coverage are left out. The night is 8 h, or 7 h / 9 h on DST-change nights |

Level = `10·log10(mean square) + calibration offset`. Without a calibration the app uses the
Android CDD sensitivity guideline (90 dB SPL at 1 kHz → RMS 2500/32768, i.e. −22.35 dBFS),
which gives an offset of **112.35 dB**. All data measured this way is flagged
`calibrated = false`.

**Event floor.** Relative to a very quiet background (20 dB at night indoors) even keystrokes are
10 dB louder, so events must also reach an absolute LAFmax. A candidate that never reaches the
floor is discarded and not counted in the minute's `event_count`. The floor in force is stored
with every event (`min_level_db`). Events recorded before v0.3.0 have none; the night list and the
chart apply the *current* floor to all stored events, so they always agree. With an uncalibrated
phone the floor is only approximate, like every level.

Every minute record stores: start (ISO-8601 with zone offset), duration, LAeq, LAFmax,
LAFmin, L1, L10, L50, L90, event count, dominant source category, time share per category,
calibration id and offset, audio source and the calibrated flag. Every event stores start,
duration, LAFmax, SEL, background level, threshold, dominant category and score, and the top-3
raw AudioSet labels with scores, and the event floor in force.

## Chart

The **Nächte** tab starts with a chart of the measured levels, drawn by the app (Compose
`Canvas`, no chart library):

- **Range:** Nacht (22:00–06:00 local time, DST-aware, the same nights as the summaries), Tag
  (00:00–24:00) or Woche (Monday–Sunday). ‹ › or a sideways swipe on the chart (≥ 15 % of its
  width, or a fling) move one window; › stops at the window that contains now. The screen opens
  on the running night, otherwise on the most recent night with data. Tapping a night in the list
  below opens it in the chart.
- **Marks:** a band from L90 to L10 (the background), the LAeq line per minute, and every event
  as a dot at (start, LAFmax). One category is highlighted in orange and drawn on top (default
  Töff & Poser; chosen with the scrolling row of chips under the chart and remembered; the
  selected chip carries the same orange dot), all others grey. Night
  periods are shaded. The y axis runs from the lowest L90 − 5 dB to the loudest shown event or
  LAeq + 5 dB (rounded to 5 dB, at least 30 dB).
- **Gaps:** the line and band are never drawn across a missing minute or one with < 50 % valid
  audio; spans of 10 min or more get a light «keine Messung» area. Time after now is not a gap.
- **Week view:** hourly values (energy mean over the valid minutes, weighted by their valid
  seconds; L10 = maximum and L90 = minimum of the minute values; an hour needs 30 valid
  minutes). Only events of the highlighted category ≥ max(floor, 60 dB(A)) are drawn, at most
  the loudest 300; the event count in the summary still includes all events.
- **Touch:** a tap shows a crosshair and a tooltip for the nearest minute (hour), or for an
  event dot within 16 dp (in the day and week views with weekday and date); while the tooltip is open, dragging sideways moves the crosshair.
  Tapping the tooltip (or outside the plot) closes it.
- **Summary:** LAeq of the window (same rule as the night summaries), number of events (and of
  the highlighted category), the loudest event, the measured share of the time and any
  interruptions.

The chart code is in `chart/`: `Windows.kt` (window arithmetic), `ChartModel.kt` (data → model:
series, runs, gaps, y range, events, summary), `ChartGeometry.kt` (pixels, hit testing),
`NoiseChart.kt` and `HistorySection.kt` (Compose). It is tested on the JVM, and rendered without a
device with [Paparazzi](https://github.com/cashapp/paparazzi):

```sh
STADTLAERM_SCREENSHOT_DIR=/tmp/shots ./gradlew :chart:testDebugUnitTest --rerun
# optional: also render real data from the app's CSV export (minuten.csv, ereignisse.csv)
STADTLAERM_REAL_DATA=/path/to/export ./gradlew :chart:testDebugUnitTest --rerun
```

The renders use synthetic data (`chart/src/test/.../SyntheticData.kt`); real exports are only
read from the directory given in `STADTLAERM_REAL_DATA` and never belong in the repository.

## Source categories

The on-device classifier is [YAMNet](https://www.kaggle.com/models/google/yamnet) (521 AudioSet
classes, MediaPipe float32 build, Apache-2.0), run on the latest 0.975 s every second. Its
scores are grouped into project categories by `app/src/main/assets/categories.json`
(category score = max of its members, dominant = highest category score > 0.2, otherwise
"unclassified"; ties are broken by list order, so **Töff & Poser beats Strassenverkehr**):

| id | UI name | AudioSet classes |
|---|---|---|
| `loud_vehicle` | Töff & Poser | Motorcycle; Accelerating, revving, vroom; Race car, auto racing; Engine starting |
| `road_traffic` | Strassenverkehr | Vehicle; Car; Car passing by; Motor vehicle (road); Traffic noise, roadway noise; Truck; Bus; Engine; Idling; Heavy/Medium/Light engine; Tire squeal; Skidding; Vehicle horn, car horn, honking; Air brake |
| `rail_tram` | Tram & Bahn | Rail transport; Train; Railroad car, train wagon; Train wheels squealing; Subway, metro, underground; Train horn; Train whistle |
| `aircraft` | Flugzeug | Aircraft; Aircraft engine; Jet engine; Fixed-wing aircraft, airplane; Propeller, airscrew; Helicopter |
| `construction` | Baustelle | Jackhammer; Drill; Power tool; Sawing; Hammer; Sanding; Chainsaw; Filing (rasp) |
| `voices` | Stimmen | Speech; Conversation; Shout; Yell; Children shouting; Children playing; Crowd; Chatter; Laughter; Hubbub, speech noise, speech babble; Cheering |
| `music` | Musik | Music; Pop/Rock/Hip hop/Electronic/Electronic dance/Dance/House music; Techno; Drum and bass; Disco; Reggae; Singing |
| `unclassified` | Sonstiges / unklassifiziert | everything else (birds, wind, silence, …) |

A unit test fails the build if any name in the JSON does not exist exactly in the shipped label
file (`yamnet_labels.txt`, extracted from the model's own metadata).

The classifier sits behind the `SoundClassifier` interface (`dsp/.../classify/SoundClassifier.kt`),
so a better model can be swapped in without touching the measurement code.

## Install (sideload)

The signed release APK is published at
[stadtlaerm.ch/download/stadtlaerm.apk](https://stadtlaerm.ch/download/stadtlaerm.apk)
(the filename stays the same across versions; [stadtlaerm.ch](https://stadtlaerm.ch) shows the
current version and its SHA-256). A German step-by-step guide is on the website.

> **Upgrading from an earlier test build (v0.1.x)?** Those were debug builds signed with a
> different key, and Android refuses to update an app across signing keys. Export your data
> (Daten → CSV exportieren), **uninstall the old Stadtlärm**, then install the current version.
> From v0.2.0 on, updates install over the previous version and keep the data.

1. Download the APK on the phone. If the browser's download dialog refuses to open it, open it
   from the **Files** app (Dateien → Downloads).
2. Android asks to allow installing unknown apps from that source (Files, Chrome, …): allow it.
3. If Play Protect warns about an unknown developer: *More details → Install anyway*.
4. Or with a computer: `adb install stadtlaerm.apk`.
5. Start Stadtlärm and allow **microphone** and **notifications** when asked.

To check the download: `sha256sum stadtlaerm.apk` must match the value on the website, and
`apksigner verify --print-certs stadtlaerm.apk` must show the signer
`CN=Christian Frank, O=Stadtlaerm, L=Zuerich, C=CH` with certificate SHA-256
`a3d5d10e69b383846ead4d1aac9ea5df9bc32845237506dcd233f097743142be`.

Requirements: Android 8.0 (API 26) or newer, ARM phone (arm64-v8a or armeabi-v7a). Not yet
validated on many devices — see "Status" below.

## Overnight measurement

1. Put the phone where you want to measure — ideally at an open window or on the balcony,
   microphone facing the street, out of wind and rain. Behind closed glass you measure the
   indoor level (typically 25–35 dB lower).
2. Optionally connect the charger. Measurement does not depend on charging; the charger only
   saves battery (see below).
3. **Messen → Messung starten.** A persistent notification shows the current LAeq; you can turn
   the screen off.
4. In the morning: **Messung stoppen**, then look at **Nächte**.

### Battery

Measurement runs in a foreground service of type *microphone* so Android keeps it alive with the
screen off. By default the app additionally holds a partial wake lock while measuring (setting
"CPU wach halten"). The audio system already keeps the CPU partly awake while recording, so the
extra cost should be small; not yet measured on a device — my estimate is a few % of battery
per hour, plus one classifier inference per second (expected tens of milliseconds on a mid-range
phone). The app keeps measuring on battery; nothing in it depends on the charger. Over a full
night that estimate adds up to a noticeable share of the battery, so the charger is convenient,
not required. For measuring on battery, exempt Stadtlärm from battery optimisation
(Einstellungen → Akku-Optimierung öffnen; on a Pixel: Settings → Apps → Stadtlärm → App battery
usage → Unrestricted). Phones with aggressive power management (Xiaomi, Huawei, Samsung "deep
sleep") may need an extra exemption in the maker's own battery settings.

## Calibration (step by step)

Phones differ by several dB, so for data you want to show to anyone, calibrate. Calibrations are
stored per **device model and audio source** with a full history; every minute record carries the
id of the calibration that was active.

### Method 1 — compare with a sound level meter (recommended)

You need a Class 1 or Class 2 sound level meter that can show LAeq (borrow one: many
environmental offices and universities lend them).

1. Stop any running measurement. Open **Kalibrieren**.
2. Put the phone's microphone (usually at the bottom edge) right next to the meter's microphone,
   a few centimetres apart, both pointing at the source.
3. Create a **steady** noise of at least 60 dB(A): pink noise from a loudspeaker 1–2 m away is
   ideal; steady, dense traffic works too. Avoid speech or music.
4. Choose 30 s or 60 s, tap **Messung starten**, and at the same moment start an LAeq measurement
   of the same length on the meter.
5. When the countdown ends, type the meter's LAeq into the field. The app shows the new offset
   (`reference − measured`).
6. Check the warnings: standard deviation of the 1 s levels > 2 dB means the noise was not steady
   enough; a reference below 50 dB(A) is too close to the phone's own noise floor. Repeat if
   warned.
7. Tap **Kalibrierung übernehmen**. Repeat once or twice; the results should agree within ~0.5 dB.

### Method 2 — acoustic calibrator

1. Select 94 dB or 114 dB to match your calibrator (1 kHz).
2. Couple the calibrator tightly to the phone's microphone port. Phone microphones need an
   adapter (a rubber coupler or a 3D-printed sleeve); without an airtight seal the result is wrong.
3. Switch the calibrator on and tap **Kalibrator messen (10 s)**.
4. The app checks that the dominant frequency is 1 kHz ± 5 % and that the signal is a clean tone;
   otherwise saving is blocked. Offset = nominal − measured (A-weighting is 0 dB at 1 kHz).

### Method 3 — manual offset

Enter an offset determined elsewhere (e.g. another phone of the same model calibrated with
method 1). Use **Auf Standard** to return to the uncalibrated CDD default.

### Noise floor check

**Eigenrauschen messen** records 20 s in the quietest place you have and shows the resulting
LAeq. Levels within ~5 dB of this value are dominated by the phone's own noise and are not
meaningful. Many phones bottom out somewhere around 30–40 dB(A) (to be confirmed per model).

The calibration history can be exported as JSON (Kalibrieren → Als JSON exportieren).

## Data export

**Daten → CSV exportieren & teilen** creates two UTF-8 CSV files (minutes, events) with a
`.` decimal separator and ISO-8601 timestamps with zone offset, and hands them to the Android
share sheet. Column names are in the first row (`laeq_db`, `lafmax_db`, `share_loud_vehicle`, …).

## Known limitations

- **Phone microphones are not measurement microphones.** Frequency response, self-noise and
  directivity vary by model. Even calibrated, expect a few dB of uncertainty, more at low and
  high frequencies. Uncalibrated data is a rough indication only (±5 dB or worse).
- **Audio source.** The app uses Android's `UNPROCESSED` source when the phone declares support
  for it, otherwise `VOICE_RECOGNITION`, and switches off AGC/noise suppression/echo
  cancellation where Android allows it. Some phones still apply processing; calibrate per source.
- **Wind** causes large false low-frequency levels. Shield the phone; don't measure in strong
  wind.
- **Behind glass vs open window**: these are different measurements; note which one you made.
  Reflections from the façade raise levels right at a wall by up to ~3 dB.
- **No tram class.** AudioSet has no "tram" class. Zürich's trams (a major noise source,
  including wheel squeal in curves) are classified as "Rail transport", "Train", "Train wheels
  squealing" or sometimes as road traffic. Treat the Tram & Bahn share as indicative.
- **YAMNet was trained on YouTube audio**, which is louder and closer-miked than a street at
  night. Measured with the real model, speech at −60 dBFS is already partly reported as
  "Silence". The app therefore amplifies quiet windows for classification only (up to +40 dB,
  setting "Pegelanpassung"); levels are never affected. Classifications of distant sources stay
  uncertain; motorcycles and cars are often confused, and the parent class "Vehicle" tends to
  dominate.
- **Duration of events** is measured on the Fast-weighted level, so it includes the decay tail
  (≈ 35 dB/s); a 2 s pass-by 25 dB above background is reported as ≈ 2.7 s.
- **Timing**: sample time is anchored to the wall clock when the first audio block arrives and
  re-anchored once per minute if the audio clock has drifted by more than 0.5 s (the number of
  corrections is stored per minute as `clock_corrections`). Timestamps are therefore accurate to
  roughly the audio input latency (tens of ms) plus up to 0.5 s of drift. Minutes are aligned to
  wall-clock minutes and close on the first 125 ms tick after the boundary.
- **Silenced microphone**: on Android 10+ a phone call or voice assistant silences the app's
  microphone. The app detects this (system callback plus a digital-silence check) and does not
  evaluate that time; the Measure screen shows a notice, minutes store `valid_s`/`coverage`, and
  calibrations in progress are aborted. Data recorded with v0.1 has no such check; on upgrade,
  v0.1 minutes with an LAeq below 0 dB(A) are marked as invalid.
- High frequencies: the digital A-filter matches IEC 61672-1 within ±0.23 dB from 20 Hz to
  10 kHz, but rolls off faster above (−1.3 dB at 12.5 kHz, −4.6 dB at 16 kHz vs nominal; still
  inside Class 1 tolerances).
- Settings changes (including the classifier level adjustment) apply from the next start of a measurement.
- Calibration must be done with the app in the foreground; leaving the app aborts the measurement.

## Status of v0.3.0

v0.3.0 is a public **test version**: v0.2.0 plus the chart and the event floor (see
[CHANGELOG](CHANGELOG.md)).

- The chart is verified with JVM unit tests and JVM renders (Paparazzi) in light and dark mode,
  with synthetic data and with a real night; touch gestures and performance have not yet been
  tried on a phone.

- Built and unit-tested on the JVM: all DSP (A-weighting, Fast weighting, levels, percentiles,
  events, resampler, category mapping, calibration math, night summaries, CSV).
- Model input pipeline verified against the real `yamnet.tflite` with Python/LiteRT
  (`tools/verify_yamnet.py`).
- **Not yet validated on many devices.** Audio capture, the foreground service, the LiteRT
  runtime on Android, battery use and the UI need testing on a range of real phones. Reports
  are welcome as GitHub issues (please include the phone model and Android version).

## Build

```sh
export ANDROID_HOME=/path/to/android-sdk   # platform 35, build-tools 35.0.0
./gradlew test assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

`./gradlew assembleRelease` without a key produces an **unsigned** release APK
(`app-release-unsigned.apk`), which Android will not install until it is signed.

### Signed release build

Release signing is optional and configured from a properties file that lives **outside** the
repository (never commit it or the keystore; `.gitignore` excludes `keystore.properties`,
`*.jks` and `*.keystore` as a safety net):

```properties
# e.g. ~/stadtlaerm-keys/keystore.properties  (chmod 600)
storeFile=/home/you/stadtlaerm-keys/stadtlaerm-release.jks
storePassword=…
keyAlias=stadtlaerm
keyPassword=…
```

Pass its path as a Gradle property or an environment variable:

```sh
./gradlew test assembleRelease -Pstadtlaerm.keystoreProperties=$HOME/stadtlaerm-keys/keystore.properties
# or
STADTLAERM_KEYSTORE_PROPERTIES=$HOME/stadtlaerm-keys/keystore.properties ./gradlew assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

Check the result (build-tools 35):

```sh
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk   # v2 + v3, signer DN
aapt2 dump permissions app/build/outputs/apk/release/app-release.apk                   # no INTERNET
sha256sum app/build/outputs/apk/release/app-release.apk
```

To publish, copy it to `../docs/download/stadtlaerm.apk` and update version, size and SHA-256 on
`../docs/index.html`. The release key cannot be replaced without forcing every user to
uninstall and reinstall, so keep the keystore and its password backed up.

Release builds contain only `arm64-v8a` and `armeabi-v7a` native code; debug builds also
include `x86_64` for the emulator. Minification is off (LiteRT has not been tested with R8).

Python cross-check of the classifier pipeline (needs `pip install ai-edge-litert scipy numpy`):

```sh
python3 tools/verify_yamnet.py
```

### Project layout

```
dsp/   pure Kotlin/JVM, no Android dependencies — shared with the planned ESP32 sensor (../firmware/)
  FrequencyWeighting.kt   A- and Z-weighting (bilinear biquads, 0 dB at 1 kHz)
  TimeWeighting.kt        Fast/Slow exponential averaging
  MeasurementEngine.kt    the pipeline: ticks, seconds, minutes, events, classifier hook
  EventDetector.kt        background (L90/5 min) + hysteresis event detector, SEL
  Resampler.kt            48 → 16 kHz anti-aliasing FIR (241 taps, ≥ 70 dB) + ring buffer
  classify/               SoundClassifier interface, category mapping, input normalisation
  calibration/            calibration measurement, tone check (FFT), offset math
  Nights.kt, Csv.kt       night summaries, CSV export
chart/ Android library: the history chart (drawing model, Compose Canvas) and the app theme;
       JVM tests and Paparazzi renders
app/   Android: AudioRecord capture, foreground service, LiteRT YAMNet, Room, Compose UI
tools/verify_yamnet.py    model I/O + Kotlin-vs-Python preprocessing check
```

## Roadmap

- **Better classifier:** swap YAMNet for an EfficientAT MobileNet (higher AudioSet mAP, similar
  cost), then fine-tune a small head on labelled Zürich recordings (tram, tram squeal, motorbike,
  pass-by vs idling) collected *with explicit consent* by volunteers.
- **ESP32 sensor:** a fixed outdoor sensor (ESP32-S3 + MEMS microphone) running the same DSP
  (the `dsp` module is written to port 1:1 to C/C++ or Kotlin/Native) and the same calibration
  procedure, so phone and sensor data are comparable. See [firmware/](../firmware/README.md).
- Opt-in, aggregated data sharing for a city-wide map (only after a separate privacy review).
- Slow time weighting, Lnight/Lden reporting per ISO 1996; zoom and export of the chart.

## Third-party components

- YAMNet model (`app/src/main/assets/yamnet.tflite`, MediaPipe float32 build) and its AudioSet
  label list — Google, Apache-2.0.
- LiteRT (TensorFlow Lite runtime), AndroidX, Jetpack Compose, Room, kotlinx — Apache-2.0.
