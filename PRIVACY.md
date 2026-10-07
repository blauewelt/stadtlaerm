# Privacy — what Stadtlärm does with sound

Stadtlärm measures noise levels. It never records audio. This is the core promise of the
project, and this page explains how the code keeps it, so anyone can check.

Everything on this page is about the **public app** (`ch.stadtlaerm.app`, the APK on
stadtlaerm.ch). Since v0.3.3 the repository can also build a separate diagnostics app that *can*
record audio; it is a different app, is never published, and the public app contains none of its
code. See [Labor-Build](#labor-build) below.

## Guarantees (app, since v0.1)

1. **Raw audio never touches disk.** No code path writes samples to a file, a database, the
   cache, shared preferences or the clipboard.
2. **Raw audio never leaves the app process.** The manifest has **no `INTERNET` permission**
   (it is explicitly removed with `tools:node="remove"`, so a library cannot add it back), and
   there is no network code. Data leaves the phone only when *you* export a CSV/JSON file and
   share it through the Android share menu. «Nach Update suchen» (since v0.3.1) only hands a
   link to the browser; see "The website" below.
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
| AudioRecord internal buffer (owned by Android) | ≈ 0.5 s | `android/app/src/main/kotlin/ch/stadtlaerm/app/audio/AudioCapture.kt` (`build`) |
| Capture block (reused, cleared on stop) | 125 ms (6000 samples) | `AudioCapture.kt` (`loop`) |
| A-weighting / Fast integrator state | a few numbers, not audio | `android/dsp/.../FrequencyWeighting.kt`, `TimeWeighting.kt` |
| Anti-aliasing filter history | 241 samples (5 ms) | `android/dsp/.../Resampler.kt` (`DecimatingResampler`) |
| Classifier ring buffer, 16 kHz | 1 s (16000 samples) | `android/dsp/.../Resampler.kt` (`FloatRingBuffer`), owned by `MeasurementEngine.kt` |
| Classifier window copy (cleared after each inference) | 0.975 s (15600 samples) | `android/app/.../service/MeasurementService.kt` (`onBlock`) |
| Model input tensor (zeroed after each inference) | 0.975 s | `android/app/.../classify/YamnetClassifier.kt` |
| Calibration tone analysis frame | 8192 samples (0.17 s) | `android/dsp/.../calibration/Calibration.kt` (`CalibrationMeasurement`) |

Everything downstream of these buffers is numbers: `LafTick`, `SecondResult`, `MinuteRecord`,
`NoiseEvent` in `android/dsp/src/main/kotlin/ch/stadtlaerm/dsp/Records.kt`, and the Room entities in
`android/app/src/main/kotlin/ch/stadtlaerm/app/data/Database.kt` (which have no column that could hold
audio).

## What the classifier output reveals

The classifier stores which *kind* of sound was present (e.g. "Speech", "Motorcycle") and how
confident it was. It does not store what was said. Still, be aware that a log saying "Speech,
23:41" reveals that someone was talking near the phone at that time. Treat exported files
accordingly before you share them.

## How to verify

- `aapt2 dump permissions stadtlaerm.apk` lists exactly: `RECORD_AUDIO`,
  `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`
  (plus AndroidX's internal `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`). No `INTERNET`.
- Search the source of the public app: `grep -rn "FileOutputStream\|openFileOutput\|Socket\|HttpURLConnection" android/app/src/main android/app/src/public android/dsp/src/main`
  returns nothing in the audio path (`android/app/src/labor/` is the separate Labor build, see below). The only file writes are the CSV/JSON exports in
  `android/app/.../data/Repositories.kt` and `android/app/.../ui/CalibrationViewModel.kt`, which contain aggregates only.

## Labor-Build

Since v0.3.3 the Android app has two product flavours (Gradle dimension `edition`):

| | `public` | `labor` |
|---|---|---|
| App id / name | `ch.stadtlaerm.app`, «Stadtlärm» | `ch.stadtlaerm.labor`, «Stadtlärm Labor» (red icon) |
| Published | yes: `docs/download/`, linked from stadtlaerm.ch | **never**: not in `docs/`, not linked from the website |
| Can record audio | no — the code does not exist in this app | yes, only after switching it on, see below |

The Labor build exists to debug the event detector and the sound-source classifier with real
recordings. It is a separate app with its own app id (installed side by side, its own data); it
is signed with the same key only so that it can be updated.

**How the public app is kept free of recording code.** Android Gradle compiles a variant only
from `app/src/main/` plus the source set of its flavour. All code that writes audio — the clip
assembler, the WAV and AAC writers, the manifest, the Labor UI — lives exclusively in
`app/src/labor/` (`ch.stadtlaerm.app.labor.*` and the Labor `ch.stadtlaerm.app.edition.*`), so it
is not compiled into the public APK at all. The shared code only contains:

- `app/src/main/kotlin/ch/stadtlaerm/app/audio/AudioTap.kt`: an interface through which a build
  *could* see the capture blocks and the event lifecycle, and `NoAudioTap`, which does nothing;
- `app/src/public/kotlin/ch/stadtlaerm/app/edition/Edition.kt`: the public `AudioTapProvider`,
  which always returns `NoAudioTap`, and empty UI hooks;
- in `MeasurementEngine`, listener hooks that report sample indices of event start/confirmation/
  end, the clock anchor and each classifier result (label names, scores, gain and level — numbers
  only; the default implementations do nothing).

The public manifest and permissions are unchanged (no `INTERNET`, no storage permission).

**How to verify** (any public build, e.g. `app/build/outputs/apk/public/release/app-public-release.apk`):

1. Source: `find android/app/src -path '*labor*'` lists the only recording code;
   `grep -rln "FileOutputStream\|MediaMuxer\|MediaCodec\|RIFF" android/app/src/main android/app/src/public android/dsp/src/main`
   finds nothing; the only file writes there are the CSV/JSON exports (`writeText` in
   `Repositories.kt` and `CalibrationViewModel.kt`), which contain aggregates only.
2. The built APK: list its classes and strings, e.g. with the Android SDK (cmdline-tools,
   build-tools 35)
   ```sh
   apkanalyzer dex packages app-public-release.apk | grep -c 'ch.stadtlaerm.app.labor'   # 0
   unzip -o app-public-release.apk 'classes*.dex' -d dex/
   for d in dex/classes*.dex; do dexdump "$d" | grep 'Class descriptor'; done \
     | grep -iE 'labor|recorder|wav|aac|muxer|clipassembler'                              # nothing
   for d in dex/classes*.dex; do strings "$d"; done \
     | grep -E 'Landroid/media/MediaMuxer;|Landroid/media/MediaCodec;|RIFF|WAVE|manifest\.jsonl|ch/stadtlaerm/app/labor/'   # nothing
   ```
   For v0.3.3 this was checked on the signed public release APK (the one on stadtlaerm.ch,
   SHA-256 `4fad06307d29f4ee088fdc4653acf48a39b29091a85214e7a0ee8d6b72fb0ac9`): `apkanalyzer` 0, `dexdump` lists 13401 classes and none of them
   matches, and no dex string references `MediaMuxer`, `MediaCodec`, `RIFF`/`WAVE`,
   `manifest.jsonl` or the `labor` package. As a control, the same commands on the Labor APK find
   828 `ch.stadtlaerm.app.labor` entries and 98 matching strings.
3. `aapt2 dump permissions` lists the same permissions as before (see «How to verify» above).

**What the Labor build records** (only after «Einstellungen → Labor → Audio während der Messung
aufzeichnen» is switched on and confirmed; off by default): event clips (WAV, 16 kHz, 5 s before to
5 s after each event, at most 60 s) and/or a continuous recording (AAC, 16 kHz, 64 kbit/s, one file
per hour), plus a `manifest.jsonl` with the event data and the classifier's per-second results for
each clip, and `classifier/*.jsonl` with every classifier run (label names and scores, no audio). Files stay on the phone in
`Android/data/ch.stadtlaerm.labor/files/audio/` until deleted in the app («Alle Aufnahmen löschen»)
or by uninstalling it; they leave the phone only if the user shares them («Als ZIP teilen») or
copies them over USB. A red banner on every screen says «LABOR-VERSION – kann Audio aufzeichnen»
(and «Aufnahme läuft» while recording). The Labor app has no `INTERNET` permission either.
Recordings can contain voices of people nearby; use the Labor build only where everyone recorded
knows about it.

## The website

[stadtlaerm.ch](https://stadtlaerm.ch) (source in `docs/`) is static HTML and CSS. It sets no
cookies, runs no analytics and loads nothing from third parties: no web fonts, no CDNs, no
external images. The only script is inline on `update.html` (allowed by its hash in the
Content-Security-Policy): the app opens `update.html#v=<version>&c=<versionCode>`, the browser
does not send the part after `#` to the server, and the script compares it with the published
version locally. It is hosted on GitHub Pages; GitHub may log visitors' IP addresses for
technical reasons, see the
[GitHub General Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
The German privacy page is [docs/datenschutz.html](docs/datenschutz.html).
