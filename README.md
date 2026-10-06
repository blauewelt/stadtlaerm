# Stadtlärm

> **Deutsch:** Stadtlärm ist ein offenes Messnetz für Strassenlärm in Zürich, betrieben von
> Anwohnerinnen und Anwohnern ([stadtlaerm.ch](https://stadtlaerm.ch)). Eine Android-App misst
> nachts A-bewertete Schallpegel, erkennt einzelne Lärmereignisse und ordnet sie einer Quelle zu
> (Töff, Verkehr, Tram, Stimmen …). Es wird nie Audio gespeichert oder versendet; gespeichert
> werden nur Pegel und Statistiken. Ein fest installierter Fenstersensor (ESP32) ist geplant.
> Stand: App v0.1.1, noch nicht auf echten Geräten getestet.

Stadtlärm is an open, resident-run road-noise measurement network for Zürich
([stadtlaerm.ch](https://stadtlaerm.ch)). Residents measure night-time noise outside their own
windows with calibrated, comparable methods and keep the data themselves.

## Privacy

Stadtlärm measures sound levels and never records audio: no audio is written to disk or sent
anywhere, and the app has no internet permission. [PRIVACY.md](PRIVACY.md) explains how the code
keeps this promise and how to check it.

## Repository layout

| Path | Contents |
|---|---|
| [`android/`](android/) | Android app (Kotlin): measurement service, on-device sound classifier, calibration, UI, plus the platform-independent `dsp` module |
| [`firmware/`](firmware/) | Planned ESP32-S3 windowsill sensor (placeholder, no code yet) |
| [`PRIVACY.md`](PRIVACY.md) | What the project does with sound, with references into the code |
| [`LICENSE`](LICENSE) | Apache License 2.0 |

Install, overnight measurement, calibration and build instructions are in
[android/README.md](android/README.md).

## Status

- **Phone app v0.1.1:** builds and passes its JVM unit tests; not yet tested on a real device or
  emulator. See [android/CHANGELOG.md](android/CHANGELOG.md).
- **Sensor hardware:** planned, see [firmware/README.md](firmware/README.md).

## License

Apache-2.0, see [LICENSE](LICENSE).
