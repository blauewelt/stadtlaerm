# Stadtlärm

**Deutsch:** Stadtlärm ist ein offenes Messnetz für Strassenlärm, von Anwohnerinnen und
Anwohnern für Anwohnerinnen und Anwohner, beginnend in Zürich. Eine Android-App misst den
Schallpegel durchgehend, auch nachts, hält laute Einzelereignisse (z. B. Töffs, Poser) mit Zeit,
Spitzenpegel und wahrscheinlicher Quelle fest, fasst jede Nacht von 22 bis 6 Uhr zusammen, zeigt
den Verlauf als Grafik und exportiert die Daten als CSV. Es wird nie Audio gespeichert oder übertragen.
Die App gibt es seit v0.4.0 in zwei Ausgaben: **Stadtlärm offline** hat gar keine
Internet-Berechtigung (wie alle Versionen bis v0.3.3). **Stadtlärm mit Lärmkarte** sendet nur,
wenn man «Messwerte teilen» selbst einschaltet: dann Pegel und Ereignisse, aber nie Audio und nie
den genauen Standort, an den Server des Projekts in der Schweiz, für eine gemeinsame Lärmkarte pro
Hektare. Beide Ausgaben installieren sich übereinander und behalten die Messwerte. Ein fest
montierter Fenstersensor ist in Entwicklung. Download und Anleitung: [stadtlaerm.ch](https://stadtlaerm.ch).

Stadtlärm is an open road-noise measurement network run by residents for residents, starting in
Zürich ([stadtlaerm.ch](https://stadtlaerm.ch)). An Android app measures A-weighted sound levels
continuously, including at night, records loud single events (motorbikes, revving cars, …) with
time, peak level and likely source, summarises each night (22:00–06:00) and exports CSV. The
data stays on the phone of the person who measured it, unless they switch on the opt-in
«Messwerte teilen» (from v0.4.0) to contribute their levels and events to a shared per-hectare
noise map. The app is published in two editions: **Stadtlärm offline**
(`stadtlaerm-offline.apk`, no internet permission) and **Stadtlärm mit Lärmkarte**
(`stadtlaerm-karte.apk`, with «Messwerte teilen»).

## Privacy

No audio is ever stored or transmitted; sound source classification runs on the phone and only
levels and statistics are kept. Since v0.4.0 there are two published editions, built from the
same source with two Gradle flavours and installable over each other in both directions:

- **Stadtlärm offline** (flavour `offline`) has no internet permission at all, exactly like every
  version up to v0.3.x, and contains no network code. Anyone can check this on the downloaded file
  (`aapt2 dump badging stadtlaerm-offline.apk | grep uses-permission`).
- **Stadtlärm mit Lärmkarte** (flavour `karte`) uses the internet for exactly one thing, the
  opt-in upload «Messwerte teilen» described in [server/DESIGN.md](server/DESIGN.md) §2 and §4:
  off until switched on, minute and event numbers plus a 100 m hectare (never coordinates, never
  the raw classifier labels), to one host (`api.stadtlaerm.ch`, in Switzerland), through one
  network class (`android/app/src/karte/kotlin/ch/stadtlaerm/app/upload/UploadClient.kt`), with no
  third-party SDK; deletable on the server from the app at any time. Switching to the offline
  edition? Delete the server data in the app first; the offline edition cannot.
[PRIVACY.md](PRIVACY.md) explains how the code keeps these promises and how to check them.

## Repository layout

| Path | Contents |
|---|---|
| [`android/`](android/) | Android app (Kotlin): measurement service, on-device sound classifier, calibration, UI, plus the platform-independent `dsp` module. See [android/README.md](android/README.md) for install, calibration and build instructions |
| [`firmware/`](firmware/) | Planned ESP32-S3 windowsill sensor (placeholder, no code yet) |
| [`docs/`](docs/) | The website [stadtlaerm.ch](https://stadtlaerm.ch) (static HTML/CSS, one hash-pinned inline script on `update.html`, served by GitHub Pages), the noise map `karte.html` (vendored Leaflet, swisstopo tiles and the project's API as its only external hosts; see [docs/README.md](docs/README.md)) and the two signed APKs in `docs/download/` (`stadtlaerm-offline.apk`, `stadtlaerm-karte.apk`) |
| [`PRIVACY.md`](PRIVACY.md) | What the project does with sound, with references into the code |
| [`server/`](server/) | The contribution server of the shared noise map (Python, FastAPI, SQLite); [server/DESIGN.md](server/DESIGN.md) is the contract between app, server and map |
| [`LICENSE`](LICENSE) | Apache License 2.0 |

## Status

- **Phone app v0.4.0:** public test version with a night/day/week chart of the measurements, an
  event floor that follows the calibration, re-evaluation of old measurements with a new
  calibration, and an update check that runs in the browser. New in v0.4.0: two editions.
  «Stadtlärm offline» has no internet permission. «Stadtlärm mit Lärmkarte» adds the opt-in
  «Messwerte teilen», which sends levels and events with a 100 m hectare (never audio, never
  coordinates) to the shared noise map; it is off until switched on, and that is the only use of
  its internet permission.
  It passes its JVM unit tests and JVM renders but is not yet validated on many devices. Download at
  [stadtlaerm.ch](https://stadtlaerm.ch); changes in [android/CHANGELOG.md](android/CHANGELOG.md).
- **Windowsill sensor:** planned, see [firmware/README.md](firmware/README.md).

Bug reports and measurements from different phone models are welcome as
[GitHub issues](https://github.com/blauewelt/stadtlaerm/issues).

## License

Apache-2.0, see [LICENSE](LICENSE).
