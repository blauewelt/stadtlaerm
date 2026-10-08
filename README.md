# Stadtlärm

**Deutsch:** Stadtlärm ist ein offenes Messnetz für Strassenlärm, von Anwohnerinnen und
Anwohnern für Anwohnerinnen und Anwohner, beginnend in Zürich. Eine Android-App misst den
Schallpegel durchgehend, auch nachts, hält laute Einzelereignisse (z. B. Töffs, Poser) mit Zeit,
Spitzenpegel und wahrscheinlicher Quelle fest, fasst jede Nacht von 22 bis 6 Uhr zusammen, zeigt
den Verlauf als Grafik und exportiert die Daten als CSV. Es wird nie Audio gespeichert oder übertragen, und die App hat
keine Internet-Berechtigung. Die App ist eine Testversion (v0.3.2), ein fest montierter
Fenstersensor ist in Entwicklung. Download und Anleitung: [stadtlaerm.ch](https://stadtlaerm.ch).

Stadtlärm is an open road-noise measurement network run by residents for residents, starting in
Zürich ([stadtlaerm.ch](https://stadtlaerm.ch)). An Android app measures A-weighted sound levels
continuously, including at night, records loud single events (motorbikes, revving cars, …) with
time, peak level and likely source, summarises each night (22:00–06:00) and exports CSV. The
data stays on the phone of the person who measured it.

## Privacy

No audio is ever stored or transmitted, and the app has no internet permission at all; sound
source classification runs on the phone and only levels and statistics are kept.
[PRIVACY.md](PRIVACY.md) explains how the code keeps this promise and how to check it.

## Repository layout

| Path | Contents |
|---|---|
| [`android/`](android/) | Android app (Kotlin): measurement service, on-device sound classifier, calibration, UI, plus the platform-independent `dsp` module. See [android/README.md](android/README.md) for install, calibration and build instructions |
| [`firmware/`](firmware/) | Planned ESP32-S3 windowsill sensor (placeholder, no code yet) |
| [`tools/weather/`](tools/weather/) | Command-line tool that joins MeteoSwiss station weather (wind, gusts, rain, temperature) with the app's CSV export, per minute, hour and night |
| [`docs/`](docs/) | The website [stadtlaerm.ch](https://stadtlaerm.ch) (static HTML/CSS, one hash-pinned inline script on `update.html`, served by GitHub Pages) and the signed APK in `docs/download/` |
| [`PRIVACY.md`](PRIVACY.md) | What the project does with sound, with references into the code |
| [`LICENSE`](LICENSE) | Apache License 2.0 |

## Status

- **Phone app v0.3.2:** public test version with a night/day/week chart of the measurements, an
  event floor that follows the calibration, re-evaluation of old measurements with a new
  calibration, and an update check that runs in the browser (the app itself still has no internet
  permission).
  It passes its JVM unit tests and JVM renders but is not yet validated on many devices. Download at
  [stadtlaerm.ch](https://stadtlaerm.ch); changes in [android/CHANGELOG.md](android/CHANGELOG.md).
- **Windowsill sensor:** planned, see [firmware/README.md](firmware/README.md).

Bug reports and measurements from different phone models are welcome as
[GitHub issues](https://github.com/blauewelt/stadtlaerm/issues).

## License

Apache-2.0, see [LICENSE](LICENSE).
