# Stadtlärm

**Deutsch:** Stadtlärm ist ein offenes Messnetz für Strassenlärm, von Anwohnerinnen und
Anwohnern für Anwohnerinnen und Anwohner, beginnend in Zürich. Eine Android-App misst den
Schallpegel durchgehend, auch nachts, hält laute Einzelereignisse (z. B. Töffs, Poser) mit Zeit,
Spitzenpegel und wahrscheinlicher Quelle fest, fasst jede Nacht von 22 bis 6 Uhr zusammen, zeigt
den Verlauf als Grafik und exportiert die Daten als CSV. Es wird nie Audio gespeichert oder übertragen.
Ab v0.5.0 (noch nicht veröffentlicht) gibt es die App in zwei Ausführungen: Die Offline-Version
(Standard) hat, wie alle Versionen bis v0.4.0, gar keine Internet-Berechtigung. Die Karten-Version
sendet nur dann etwas ins Internet, wenn man «Messwerte teilen» selbst einschaltet:
dann Pegel und Ereignisse, aber nie Audio und nie den genauen Standort, an den
Server des Projekts in der Schweiz, für eine gemeinsame Lärmkarte pro Hektare. Ein fest
montierter Fenstersensor ist in Entwicklung. Download und Anleitung: [stadtlaerm.ch](https://stadtlaerm.ch).

Stadtlärm is an open road-noise measurement network run by residents for residents, starting in
Zürich ([stadtlaerm.ch](https://stadtlaerm.ch)). An Android app measures A-weighted sound levels
continuously, including at night, records loud single events (motorbikes, revving cars, …) with
time, peak level and likely source, summarises each night (22:00–06:00) and exports CSV. The
data stays on the phone of the person who measured it, unless they use the map edition
(«Karten-Version», from v0.5.0, not yet published) and switch on the opt-in «Messwerte teilen» to
contribute their levels and events to a shared per-hectare noise map. The default download, the
«Offline-Version», has no internet permission at all.

## Privacy

No audio is ever stored or transmitted; sound source classification runs on the phone and only
levels and statistics are kept. Up to v0.4.0 (the version published on stadtlaerm.ch) the app has no
internet permission at all, and from v0.5.0 (not yet published) neither has the default
**Offline-Version** (`stadtlaerm.apk`). The **Karten-Version** (`stadtlaerm-karte.apk`, same app id,
installs over the other and keeps the data) uses the internet for exactly one thing, the opt-in upload «Messwerte teilen» described
in [server/DESIGN.md](server/DESIGN.md) §2 and §4: off until switched on, minute and event
numbers plus a 100 m hectare (never coordinates, never the raw classifier labels), to one host
(`api.stadtlaerm.ch`, in Switzerland), through one network class
(`android/app/src/main/kotlin/ch/stadtlaerm/app/upload/UploadClient.kt`), with no third-party SDK;
deletable on the server from the app at any time.
[PRIVACY.md](PRIVACY.md) explains how the code keeps these promises and how to check them.

## Usage numbers

What the project counts, and who counts it — totals over the whole project, all public:

- **Downloads:** GitHub counts how often each release file (`stadtlaerm.apk`,
  `stadtlaerm-karte.apk`) was downloaded; `python3 scripts/download_stats.py` prints the counts per
  release and per edition (via `gh api` or plain HTTPS, no token needed). Downloads, not people. The
  project never sees who downloaded; downloads of the fallback copy on stadtlaerm.ch are not counted.
- **Sharing:** the server publishes [`stats.json`](https://api.stadtlaerm.ch/v1/map/stats.json)
  with the map files ([server/DESIGN.md](server/DESIGN.md) §6.4): devices registered, with a
  hectare, ever shared, active in 7/30 days, deleted; hectares on the map; shared device-nights;
  app versions of active devices; registrations per ISO week. Computed from the data the map already
  uses; no ids, no places, no IP addresses.
- `python3 scripts/usage_report.py` prints both in one short report.
- **Not counted:** anything about the Offline-Version beyond its downloads (it has no internet
  permission and cannot report anything), and anything about Karten-Version users who leave
  «Messwerte teilen» off. No analytics, no crash reports, no app-start pings in any edition.

## Repository layout

| Path | Contents |
|---|---|
| [`android/`](android/) | Android app (Kotlin): measurement service, on-device sound classifier, calibration, UI, plus the platform-independent `dsp` module. See [android/README.md](android/README.md) for install, calibration and build instructions |
| [`firmware/`](firmware/) | Planned ESP32-S3 windowsill sensor (placeholder, no code yet) |
| [`tools/weather/`](tools/weather/) | Command-line tool that joins MeteoSwiss station weather (wind, gusts, rain, temperature) with the app's CSV export, per minute, hour and night |
| [`docs/`](docs/) | The website [stadtlaerm.ch](https://stadtlaerm.ch) (static HTML/CSS, one hash-pinned inline script on `update.html`, served by GitHub Pages), the noise map `karte.html` (vendored Leaflet, swisstopo tiles and the project's API as its only external hosts; see [docs/README.md](docs/README.md)) and, in `docs/download/`, the Offline-Version APK as a fallback for old links (new downloads come from GitHub Releases) |
| [`scripts/`](scripts/) | `stamp_assets.py` (cache stamps of the map page), `download_stats.py` and `usage_report.py` (usage numbers, see above), tests in `scripts/tests/` |
| [`PRIVACY.md`](PRIVACY.md) | What the project does with sound, with references into the code |
| [`server/`](server/) | The contribution server of the shared noise map (Python, FastAPI, SQLite); [server/DESIGN.md](server/DESIGN.md) is the contract between app, server and map |
| [`LICENSE`](LICENSE) | Apache License 2.0 |

## Status

- **Phone app v0.4.0 (published on stadtlaerm.ch):** public test version with a night/day/week
  chart of the measurements, event detector v2 (events measured against the local background,
  wind on the microphone flagged and left out of the counts), an event floor that follows the
  calibration, re-evaluation of old measurements with a new calibration, and an update check that
  runs in the browser (the app itself has no internet permission).
- **Phone app v0.5.0 (test build, not yet published), in two editions:** the **Offline-Version**
  (default download, no internet permission, the normal update for 0.4.0) and the
  **Karten-Version**: v0.4.0 plus the opt-in «Messwerte teilen», which sends levels and events
  (without wind events) with a 100 m hectare (never audio, never coordinates) to the shared noise
  map; it is off until switched on, and that is the only use of the internet permission it adds.
  Both are downloaded from GitHub Releases.
- **Noise map and server:** the contribution server ([server/](server/)) runs at
  `api.stadtlaerm.ch` (in Switzerland); the map page `docs/karte.html` is part of v0.5.0 and not
  yet on the website.
- The app passes its JVM unit tests and JVM renders but is not yet validated on many devices. Download at
  [stadtlaerm.ch](https://stadtlaerm.ch); changes in [android/CHANGELOG.md](android/CHANGELOG.md).
- **Windowsill sensor:** planned, see [firmware/README.md](firmware/README.md).

Bug reports and measurements from different phone models are welcome as
[GitHub issues](https://github.com/blauewelt/stadtlaerm/issues).

## License

Apache-2.0, see [LICENSE](LICENSE).
