# Privacy — what Stadtlärm does with sound

Stadtlärm measures noise levels. It never records audio. This is the core promise of the
project, and this page explains how the code keeps it, so anyone can check.

## Guarantees (v0.1)

1. **Raw audio never touches disk.** No code path writes samples to a file, a database, the
   cache, shared preferences or the clipboard.
2. **Raw audio never leaves the app process.** The manifest has **no `INTERNET` permission**
   (it is explicitly removed with `tools:node="remove"`, so a library cannot add it back), and
   there is no network code. Data leaves the phone only when *you* export a CSV/JSON file and
   share it through the Android share menu.
3. **Raw audio is never logged.** The only log line in the audio path reports an exception
   class name when the microphone cannot be opened.
4. **Raw audio is held only briefly, in memory, in small fixed-size buffers.** The largest is
   one second of 16 kHz audio for the sound-source classifier (see the table below). Every
   buffer is overwritten continuously and cleared when measurement stops.
5. **Only aggregates are stored:** levels (LAeq, LAFmax, LAFmin, L1/L10/L50/L90), event
   statistics (start, duration, LAFmax, SEL, background level), classifier category shares,
   the top-3 AudioSet label *names and scores* per event, and calibration records. None of these
   can be turned back into audio.
6. **No analytics, no crash reporting, no cloud backup.** `android:allowBackup="false"`, and
   the data-extraction rules exclude everything from cloud backup and device transfer.

## Where audio is handled in the code

| Buffer | Size | Code |
|---|---|---|
| AudioRecord internal buffer (owned by Android) | ≈ 0.5 s | `app/src/main/kotlin/ch/stadtlaerm/app/audio/AudioCapture.kt` (`build`) |
| Capture block (reused, cleared on stop) | 125 ms (6000 samples) | `AudioCapture.kt` (`loop`) |
| A-weighting / Fast integrator state | a few numbers, not audio | `dsp/.../FrequencyWeighting.kt`, `TimeWeighting.kt` |
| Anti-aliasing filter history | 241 samples (5 ms) | `dsp/.../Resampler.kt` (`DecimatingResampler`) |
| Classifier ring buffer, 16 kHz | 1 s (16000 samples) | `dsp/.../Resampler.kt` (`FloatRingBuffer`), owned by `MeasurementEngine.kt` |
| Classifier window copy (cleared after each inference) | 0.975 s (15600 samples) | `app/.../service/MeasurementService.kt` (`onBlock`) |
| Model input tensor (zeroed after each inference) | 0.975 s | `app/.../classify/YamnetClassifier.kt` |
| Calibration tone analysis frame | 8192 samples (0.17 s) | `dsp/.../calibration/Calibration.kt` (`CalibrationMeasurement`) |

Everything downstream of these buffers is numbers: `LafTick`, `SecondResult`, `MinuteRecord`,
`NoiseEvent` in `dsp/src/main/kotlin/ch/stadtlaerm/dsp/Records.kt`, and the Room entities in
`app/src/main/kotlin/ch/stadtlaerm/app/data/Database.kt` (which have no column that could hold
audio).

## What the classifier output reveals

The classifier stores which *kind* of sound was present (e.g. "Speech", "Motorcycle") and how
confident it was. It does not store what was said. Still, be aware that a log saying "Speech,
23:41" reveals that someone was talking near the phone at that time. Treat exported files
accordingly before you share them.

## How to verify

- `aapt2 dump permissions stadtlaerm-v0.1.1-debug.apk` lists exactly: `RECORD_AUDIO`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`
  (plus AndroidX's internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`). No `INTERNET`.
- Search the source: `grep -rn "FileOutputStream\|openFileOutput\|Socket\|HttpURLConnection" app dsp`
  returns nothing in the audio path. The only file writes are the CSV/JSON exports in
  `data/Repositories.kt` and `ui/CalibrationViewModel.kt`, which contain aggregates only.
