# Stadtlärm — citizen noise measurement for Zürich (Android, v0.1)

Stadtlärm turns an Android phone into a night-time noise logger. It measures A-weighted
sound levels the way a sound level meter does (IEC 61672-1 A-weighting, Fast time weighting),
detects individual noise events (a motorbike, a shouting group, a tram), tags each with its
likely source using an on-device sound classifier, and summarises every night
(22:00–06:00, the Swiss night period).

**Privacy by construction:** no audio is ever stored or sent, and the app has no internet
permission at all. See [PRIVACY.md](PRIVACY.md).

The UI is German (Swiss spelling); the code and docs are English. License: Apache-2.0.

---

## What it measures

| Quantity | Definition in this app |
|---|---|
| LAF | A-weighted level with Fast time weighting (exponential, τ = 125 ms), sampled every 125 ms |
| LAeq,1s / LAeq,1min | Energy-equivalent A-weighted level over 1 s / 1 min (from the un-time-weighted signal) |
| LAFmax / LAFmin | Max/min of LAF in the period (max from the continuous Fast signal, min from the 125 ms samples) |
| L1, L10, L50, L90 | Level exceeded 1/10/50/90 % of the time, from the 480 LAF samples of the minute (so L10 > L90) |
| Event | LAF > background + threshold (default 10 dB) for ≥ 0.5 s; ends below background + threshold − 3 dB. Background = L90 of LAF over the trailing 5 min, frozen at event start |
| SEL (LAE) | Sound exposure level of the event, re 1 s: `10·log10(Σ 10^(Leq_tick/10)·0.125 s)` |
| Night LAeq | Energy average of all minutes that start between 22:00 and 06:00 |

Level = `10·log10(mean square) + calibration offset`. Without a calibration the app uses the
Android CDD sensitivity guideline (90 dB SPL at 1 kHz → RMS 2500/32768, i.e. −22.35 dBFS),
which gives an offset of **112.35 dB**. All data measured this way is flagged
`calibrated = false`.

Every minute record stores: start (ISO-8601 with zone offset), duration, LAeq, LAFmax,
LAFmin, L1, L10, L50, L90, event count, dominant source category, time share per category,
calibration id and offset, audio source and the calibrated flag. Every event stores start,
duration, LAFmax, SEL, background level, threshold, dominant category and score, and the top-3
raw AudioSet labels with scores.

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

1. Copy `dist/stadtlaerm-v0.1-debug.apk` to the phone (USB, cloud drive, e-mail to yourself).
2. Open it on the phone. Android asks to allow installing from that source (Files, Chrome, …):
   allow it once.
3. Or with a computer: `adb install dist/stadtlaerm-v0.1-debug.apk`.
4. Start Stadtlärm and allow **microphone** and **notifications** when asked.

Requirements: Android 8.0 (API 26) or newer. Tested only by build and unit tests so far — see
"Status" below.

## Overnight measurement

1. Put the phone where you want to measure — ideally at an open window or on the balcony,
   microphone facing the street, out of wind and rain. Behind closed glass you measure the
   indoor level (typically 25–35 dB lower).
2. Connect the charger.
3. **Messen → Messung starten.** A persistent notification shows the current LAeq; you can turn
   the screen off.
4. In the morning: **Messung stoppen**, then look at **Nächte**.

### Battery

Measurement runs in a foreground service of type *microphone* so Android keeps it alive with the
screen off. By default the app additionally holds a partial wake lock while measuring (setting
"CPU wach halten"). The audio system already keeps the CPU partly awake while recording, so the
extra cost should be small; not yet measured on a device — my estimate is a few % of battery
per hour, plus one classifier inference per second (expected tens of milliseconds on a mid-range
phone). **Keep the phone on the charger overnight.** On phones with
aggressive power management (Xiaomi, Huawei, Samsung "deep sleep") also exempt Stadtlärm from
battery optimisation (Einstellungen → Akku-Optimierung öffnen).

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
- **Timing**: timestamps are derived from the start time and the audio sample count; over a
  night the audio clock may drift by a few seconds against wall time. Minutes close on the first
  125 ms tick after the wall-clock minute boundary.
- High frequencies: the digital A-filter matches IEC 61672-1 within ±0.23 dB from 20 Hz to
  10 kHz, but rolls off faster above (−1.3 dB at 12.5 kHz, −4.6 dB at 16 kHz vs nominal; still
  inside Class 1 tolerances).
- Settings changes apply from the next start of a measurement.

## Status of v0.1

- Built and unit-tested on the JVM: all DSP (A-weighting, Fast weighting, levels, percentiles,
  events, resampler, category mapping, calibration math, night summaries, CSV).
- Model input pipeline verified against the real `yamnet.tflite` with Python/LiteRT
  (`tools/verify_yamnet.py`).
- **Not yet run on a real device or emulator.** Audio capture, the foreground service, the
  LiteRT runtime on Android and all UI screens are untested in practice.

## Build

```sh
export ANDROID_HOME=/path/to/android-sdk   # platform 35, build-tools 35.0.0
./gradlew test assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Python cross-check of the classifier pipeline (needs `pip install ai-edge-litert scipy numpy`):

```sh
python3 tools/verify_yamnet.py
```

### Project layout

```
dsp/   pure Kotlin/JVM, no Android dependencies — shared with a future ESP32 port
  FrequencyWeighting.kt   A- and Z-weighting (bilinear biquads, 0 dB at 1 kHz)
  TimeWeighting.kt        Fast/Slow exponential averaging
  MeasurementEngine.kt    the pipeline: ticks, seconds, minutes, events, classifier hook
  EventDetector.kt        background (L90/5 min) + hysteresis event detector, SEL
  Resampler.kt            48 → 16 kHz anti-aliasing FIR (241 taps, ≥ 70 dB) + ring buffer
  classify/               SoundClassifier interface, category mapping, input normalisation
  calibration/            calibration measurement, tone check (FFT), offset math
  Nights.kt, Csv.kt       night summaries, CSV export
app/   Android: AudioRecord capture, foreground service, LiteRT YAMNet, Room, Compose UI
tools/verify_yamnet.py    model I/O + Kotlin-vs-Python preprocessing check
```

## Roadmap

- **Better classifier:** swap YAMNet for an EfficientAT MobileNet (higher AudioSet mAP, similar
  cost), then fine-tune a small head on labelled Zürich recordings (tram, tram squeal, motorbike,
  pass-by vs idling) collected *with explicit consent* by volunteers.
- **ESP32 sensor:** a fixed outdoor sensor (ESP32-S3 + MEMS microphone) running the same DSP
  (the `dsp` module is written to port 1:1 to C/C++ or Kotlin/Native) and the same calibration
  procedure, so phone and sensor data are comparable.
- Opt-in, aggregated data sharing for a city-wide map (only after a separate privacy review).
- Per-night charts, Slow time weighting, Lnight/Lden reporting per ISO 1996.

## Third-party components

- YAMNet model (`app/src/main/assets/yamnet.tflite`, MediaPipe float32 build) and its AudioSet
  label list — Google, Apache-2.0.
- LiteRT (TensorFlow Lite runtime), AndroidX, Jetpack Compose, Room, kotlinx — Apache-2.0.
