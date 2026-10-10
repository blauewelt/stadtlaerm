# Privacy — what Stadtlärm does with sound

Stadtlärm measures noise levels. It never records audio. This is the core promise of the
project, and this page explains how the code keeps it, so anyone can check.

Everything on this page is about the **public app** (`ch.stadtlaerm.app`, the APKs linked from
stadtlaerm.ch). Since v0.5.0 it is published in two editions, the **Offline-Version** (no
internet permission at all) and the **Karten-Version** (internet only for the opt-in «Messwerte
teilen»), see [Editions](#editions-from-v050). Since v0.3.3 the repository can also build a
separate diagnostics app that *can* record audio; it is a different app, is never published, and
neither published edition contains any of its code. See [Labor-Build](#labor-build) below.

## Editions (from v0.5.0)

| | Offline-Version | Karten-Version |
|---|---|---|
| File (GitHub Release `vX.Y.Z`) | `stadtlaerm.apk` — the default download | `stadtlaerm-karte.apk` |
| Gradle flavour | `offline` | `public` |
| App id, signing key | `ch.stadtlaerm.app`, release key | the same |
| `INTERNET`, `ACCESS_NETWORK_STATE` | **no** (removed in `app/src/offline/AndroidManifest.xml`) | yes, only for «Messwerte teilen» |
| «Messwerte teilen» | does not exist (`BuildConfig.UPLOAD_AVAILABLE = false`) | off until you switch it on |
| Data leaves the phone | only when *you* export it (CSV; calibrations as JSON) and share it | the same, plus the opt-in upload |
| Can report anything (usage, crashes, versions) | **no** — it has no way to | only what «Messwerte teilen» sends, below |

Both editions are built from the same Kotlin sources (`app/src/main` and `app/src/public/kotlin`)
and contain no recording code. Because they share the app id and the key, installing one over the
other switches the edition and keeps the measurements. Every version up to v0.4.0 is equivalent to
the Offline-Version (no `INTERNET`), so for those users the Offline-Version is an ordinary update
without a new permission. The Offline-Version still contains the compiled upload classes (shared
code), but they can never run: every entry point returns when `UPLOAD_AVAILABLE` is false, the
settings card does not exist, and without `INTERNET` Android would refuse the socket anyway. When
the Offline-Version is installed over a Karten-Version that had sharing on, it switches sharing
off and cancels the scheduled upload jobs at its first start (`UploadModule.stopForOfflineEdition`);
it keeps the device id and token so that the Karten-Version can still delete the data on the
server later. Delete your server data in the Karten-Version *before* switching if you want it gone.

## Guarantees (app, since v0.1)

1. **Raw audio never touches disk.** No code path writes samples to a file, a database, the
   cache, shared preferences or the clipboard.
2. **Raw audio never leaves the app process.** There is no code that sends audio anywhere. Up to
   v0.4.0, and in the **Offline-Version** from v0.5.0, the manifest has no `INTERNET` permission
   at all. **From v0.5.0** the **Karten-Version** has
   `INTERNET` for exactly one purpose: the opt-in upload «Messwerte teilen» (see
   [«Messwerte teilen»](#messwerte-teilen-opt-in-upload-from-v050) below), which is **off until you
   switch it on**, sends only the minute and event *numbers* listed there, to one host
   (`api.stadtlaerm.ch`), through one class (`upload/UploadClient.kt`). Otherwise data leaves the
   phone only when *you* export a CSV/JSON file and share it through the Android share menu.
   «Nach Update suchen» (since v0.3.1) only hands a link to the browser; see "The website" below.
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
   the data-extraction rules exclude everything from cloud backup and device transfer. No
   third-party SDK talks to the network: the upload uses Android's own `HttpURLConnection`.

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

- `aapt2 dump permissions <apk>` (Android SDK build-tools) lists exactly:
  - **`stadtlaerm.apk`** (Offline-Version, and every version up to v0.4.0): `RECORD_AUDIO`,
    `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MICROPHONE`, `POST_NOTIFICATIONS`, `WAKE_LOCK`
    (plus AndroidX's internal `ch.stadtlaerm.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`).
    No `INTERNET`, no `ACCESS_NETWORK_STATE`.
  - **`stadtlaerm-karte.apk`** (Karten-Version, from v0.5.0): the same plus `INTERNET` and
    `ACCESS_NETWORK_STATE`, both only for «Messwerte teilen» (below).
  - Neither has a location, Wi-Fi-state, boot or storage permission. `aapt2 dump badging` shows
    `package: name='ch.stadtlaerm.app'` for both.
- Search the source of the public app: `grep -rn "FileOutputStream\|openFileOutput\|Socket\|HttpURLConnection\|openConnection\|URL(" android/app/src/main android/app/src/public android/dsp/src/main`
  finds network code in exactly one file, `android/app/src/main/kotlin/ch/stadtlaerm/app/upload/UploadClient.kt`
  (the upload), and nothing in the audio path (`android/app/src/labor/` is the separate Labor build, see below). The only file writes are the CSV/JSON exports in
  `android/app/.../data/Repositories.kt` and `android/app/.../ui/CalibrationViewModel.kt`, which contain aggregates only.

## «Messwerte teilen» (opt-in upload, from v0.5.0)

The shared noise map ([server/DESIGN.md](server/DESIGN.md), its §2 is the privacy contract) is
fed by phones whose owners switched on **Einstellungen → Messwerte teilen**. The rules, and where
the code keeps them:

| Rule | How |
|---|---|
| **Off by default**, one explicit opt-in | `UploadSnapshot.enabled = false`; the switch is enabled only after the explanation screen was read and a hectare was chosen (`Uploader.enable()`, `ui/ShareScreen.kt`). Switching off cancels every scheduled job at once (`UploadModule.applySchedule()`); a running upload stops before its next request. |
| **What is sent** | The minute records (levels, percentiles, valid time, event count, category shares, calibration id/offset, audio source, clock corrections) and event records (start, duration, LAFmax, SEL, background, threshold, floor, category and its score, calibration) of `upload/Payloads.kt`, plus the placement of §4.2 (hectare, placement, floor, street side, phone model, audio source, calibration, optional note). Exactly the bodies of DESIGN.md §4. Events flagged as wind on the microphone are not sent (the app leaves them out of its own counts too), and a minute's event count is the count without wind. |
| **What is never sent** | Audio (none exists). The top-3 raw AudioSet labels of events (`topLabelsJson` is not read by `Payloads`). The `orig_*`/re-evaluation columns. The detector-v2 features of events and minutes (local floor, excess, rise/decay, LF share and flutter, shape, wind flag, wind event count). Coordinates: the placement step turns what you type into an LV95 hectare id on the phone (`upload/CellInput.kt`, `dsp/.../geo/Lv95.kt`); the typed text is neither stored nor sent. GPS is never read; there is no location permission. No name, account, phone number, advertising id or Android id. |
| **Where it goes** | One host, `BuildConfig.STADTLAERM_API` = `https://api.stadtlaerm.ch` (the project's server in Switzerland, DESIGN.md §8). HTTPS only (`UploadClient` refuses any other base URL except a loopback test server), redirects are not followed, no cookies, and the User-Agent is just `Stadtlaerm` (no phone model or Android build). |
| **When** | After every full hour and shortly after a measurement stops (WorkManager), only over Wi-Fi/unmetered networks unless «nur über WLAN» is switched off. At the first opt-in the last 7 days are included (the screen says so); afterwards nothing measured while sharing was off is ever sent. |
| **Identity** | At the first upload the server issues a random device id and a secret token (DESIGN.md §4.1). The token is stored encrypted with a key held in the Android Keystore; the id is never shown on the public map. «Neue Kennung» deletes the server data and starts a fresh id. |
| **Delete means delete** | «Meine Daten auf dem Server löschen» sends `DELETE /v1/devices/{id}` and waits for the server's `204` (sent only after every row is gone, DESIGN.md §2.4); only then are id and token forgotten on the phone and sharing switched off. On an error nothing changes and the screen says so. |

`ACCESS_NETWORK_STATE` is required by Android for background jobs that wait for a network (and
for «nur über WLAN»); it tells the app whether a connection is up and metered, not which network.
`ACCESS_WIFI_STATE` and `RECEIVE_BOOT_COMPLETED` (which WorkManager would add) are removed in the
manifest.

**How to verify:** the grep above (one network file); `android/app/src/test/.../upload/UploadTest.kt`
checks the exact request bodies against a local fake server (no labels, no originals, no
coordinates, gzip, bearer token, the fixed User-Agent, nothing sent while off); and on a phone,
Android's per-app data usage (on a Pixel: *Settings → Apps → Stadtlärm → Mobile data & Wi-Fi*)
shows the app's traffic, which stays at zero while sharing is off.

## Labor-Build

Since v0.3.3 the Android app has product flavours in the Gradle dimension `edition`; since
v0.5.0 three of them (`offline`, `public`, `labor`):

| | `offline` and `public` (the two published editions) | `labor` |
|---|---|---|
| App id / name | `ch.stadtlaerm.app`, «Stadtlärm» | `ch.stadtlaerm.labor`, «Stadtlärm Labor» (red icon) |
| Published | yes: GitHub Releases (`stadtlaerm.apk`, `stadtlaerm-karte.apk`), linked from stadtlaerm.ch; the offline APK also in `docs/download/` for old links | **never**: not in `docs/`, not in a release, not linked from the website |
| Can record audio | no — the code does not exist in these apps | yes, only after switching it on, see below |

The Labor build exists to debug the event detector and the sound-source classifier with real
recordings. It is a separate app with its own app id (installed side by side, its own data); it
is signed with the same key only so that it can be updated.

**How the public app is kept free of recording code.** Android Gradle compiles a variant only
from `app/src/main/` plus the source set of its flavour. All code that writes audio — the clip
assembler, the WAV and AAC writers, the manifest, the Labor UI — lives exclusively in
`app/src/labor/` (`ch.stadtlaerm.app.labor.*` and the Labor `ch.stadtlaerm.app.edition.*`), so it
is not compiled into either published APK at all (the `offline` flavour adds only
`app/src/public/kotlin` to its sources, not `app/src/labor/`). The shared code only contains:

- `app/src/main/kotlin/ch/stadtlaerm/app/audio/AudioTap.kt`: an interface through which a build
  *could* see the capture blocks and the event lifecycle, and `NoAudioTap`, which does nothing;
- `app/src/public/kotlin/ch/stadtlaerm/app/edition/Edition.kt`: the public `AudioTapProvider`,
  which always returns `NoAudioTap`, and empty UI hooks;
- in `MeasurementEngine`, listener hooks that report sample indices of event start/confirmation/
  end, the clock anchor and each classifier result (label names, scores, gain and level — numbers
  only; the default implementations do nothing).
- in the chart (since v0.3.4), an optional clip reference per event and an optional «Abspielen»
  callback. The public `EditionClips` provides no references and no callback, so the public chart
  never shows a play button; the clip index and the player (MediaPlayer) live in `app/src/labor/`.

The Labor build has no network access: its manifest (`app/src/labor/AndroidManifest.xml`) removes
`INTERNET` and `ACCESS_NETWORK_STATE`, and «Messwerte teilen» does not exist in it
(`BuildConfig.UPLOAD_AVAILABLE = false`). No storage permission in either build.

**How to verify** (on **both** published APKs: `app/build/outputs/apk/offline/release/app-offline-release.apk`
= `stadtlaerm.apk` and `app/build/outputs/apk/public/release/app-public-release.apk` = `stadtlaerm-karte.apk`;
the commands below use the second name, run them on each):

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
3. `aapt2 dump permissions` lists the permissions of «How to verify» above for each published APK;
   for the Labor APK the Offline-Version's set (no `INTERNET`, no `ACCESS_NETWORK_STATE`).

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
cookies, runs no analytics and loads nothing from third parties (except the map page, below): no
web fonts, no CDNs, no external images. The only inline script is on `update.html` (allowed by its hash in the
Content-Security-Policy): the app opens `update.html#v=<version>&c=<versionCode>&e=<edition>`
(`e` since v0.5.0: `offline` or `karte`), the browser does not send the part after `#` to the
server, and the script compares it with the published version locally and offers the download of
the same edition. It is hosted on GitHub Pages; GitHub may log visitors' IP addresses for
technical reasons, see the
[GitHub General Privacy Statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
The German privacy page is [docs/datenschutz.html](docs/datenschutz.html).

**Downloads.** From v0.5.0 the APKs are attached to a GitHub Release per version
(`github.com/blauewelt/stadtlaerm/releases/download/vX.Y.Z/stadtlaerm.apk` and
`…/stadtlaerm-karte.apk`), and the website's download links point there. GitHub serves the file
and, as for the website, sees the requesting IP address; the project sees only GitHub's public
per-file `download_count`. `docs/download/stadtlaerm.apk` (the Offline-Version) stays on
stadtlaerm.ch for old links; downloads of it are not counted by anyone.

**The map page is the one exception.** `docs/karte.html` (the shared noise map, see
[server/DESIGN.md](server/DESIGN.md) §7) loads map tiles from swisstopo
(`wmts.geo.admin.ch`) and the published per-hectare aggregates from the project's own API host
(`api.stadtlaerm.ch`), and nothing else; its Content-Security-Policy allows exactly these two
hosts (`img-src` and `connect-src`) and scripts only from the site itself (vendored Leaflet,
no inline script). It says so in one sentence above the map, and `datenschutz.html` repeats it.
Both hosts necessarily see the visitor's IP address; the page sends no referrer and sets no
cookies, and the API server keeps no access log. All other pages still load nothing from third
parties.

## Usage numbers

The project wants to know whether the app is used, without learning anything about anyone. It
counts in exactly two places, both public, both totals over the whole project:

1. **Downloads**, counted by GitHub: `download_count` per release asset
   (`scripts/download_stats.py` reads them from GitHub's release API). Downloads, not people; no
   IP addresses, times or places reach the project.
2. **Sharing**, from the server's own data: `https://api.stadtlaerm.ch/v1/map/stats.json`
   ([server/DESIGN.md](server/DESIGN.md) §6.4), rebuilt with the map — how many devices are
   registered, have set a hectare, ever shared, were active in the last 7/30 days, deleted their
   data (one counter), how many hectares are on the map, how many device-nights were shared, how
   many active devices reported which app version at registration, and registrations per ISO week.
   Counts only: no device ids, no cells or places, no IP addresses (the server stores none, and
   neither Caddy nor the app server keeps an access log). Nothing new is collected for it; the only
   number kept just for it is the deletion counter, a single integer.

**Not counted:** anything about the Offline-Version beyond its downloads — it has no internet
permission and cannot report anything, by design; the Karten-Version with sharing switched off;
app starts, measurements, crashes or versions of apps that do not share; downloads of
`docs/download/stadtlaerm.apk`. `scripts/usage_report.py` combines both sources into one short
text report.
