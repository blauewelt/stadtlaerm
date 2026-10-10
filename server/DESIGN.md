# Stadtlärm – shared noise map: data model, contribution API, map

Status: design, 2026-10-08. This document is the contract between the three parts that
make the shared map: the **app** (sends), the **server** (stores and aggregates) and the
**website** (shows). Each part is built against this file; when one of them needs a
change, this file changes first.

## 1. What the map is for

A city-wide, resident-run picture of night-time road noise in Zürich: where it is loud,
where it is *bursty* (quiet street, loud single motorbikes), how many loud single events
per hour, and how that changes over weeks. It is meant to be shown to the city's
Team Strassenlärm and to neighbours — so every number on it must be reproducible from
the stored data, and the stored data must be explainable to the person who contributed it.

## 2. Privacy rules (not optional)

These rules are the design. They go into PRIVACY.md and the website when the feature ships.

1. **No audio, ever.** The app already never stores audio; the upload sends only the
   minute records and event records the app keeps today (levels, percentiles, category
   shares, event start/duration/peak/category). The top-3 raw AudioSet labels per event are
   **not** sent — the category is enough for the map and the labels add fingerprinting
   surface for no gain.
2. **No precise location.** The contributor places the sensor on a map **once**, in the
   app. The app snaps that point to a **Swiss hectare cell** (the 100 m × 100 m grid of the
   national LV95 coordinate system, the same grid the Federal Statistical Office publishes
   population on) *before* anything is sent. The server never sees coordinates, only cell
   ids. GPS is never read; the app does not request a location permission.
3. **Off by default, one explicit opt-in.** Upload is a setting («Messwerte teilen»),
   off until the contributor switches it on in a screen that lists exactly what leaves the
   phone, what never does, and where it goes. Switching it off stops uploads at once.
4. **Delete means delete.** «Meine Daten auf dem Server löschen» removes every row for
   the device, synchronously, and the server answers only after the rows are gone. The
   published aggregates are rebuilt on the next cycle without them.
5. **No account, no identity.** A device gets a random id and a secret token at opt-in.
   The id is never shown on the public map. «Neue Kennung» in settings deletes the server
   data and starts a fresh id.
6. **Only aggregates are public.** The website never fetches per-device rows. The server
   publishes per-cell aggregates (section 6). The minimum number of devices per shown cell
   is a server parameter `MIN_DEVICES_PER_CELL`, **default 1**: a lone contributor who
   opted in, snapped to a hectare with dozens of households, is showing their own street's
   noise, which is the point of the project. Raise it later if the network grows and the
   map becomes dense enough that it would not go blank.
7. **Swiss hosting.** The server runs in Switzerland (see section 8). The website's main
   page still loads nothing from third parties; the map page is the one exception and says
   so (swisstopo tiles and our own API host only; see section 7).
8. **Minimisation on publish.** Event times are published at hour resolution (counts and
   peak per hour), never as a per-second list. Device models are published only as counts
   per model over the whole network («Beteiligte Geräte»), never per cell. The usage figures
   of `stats.json` (§6.4) are likewise whole-network counts only.

## 3. Cell ids

A cell id is the hectare in LV95: `h` + E/100 + `_` + N/100, both integers,
e.g. `h26824_12473` for the hectare whose south-west corner is E 2 682 400, N 1 247 300.

WGS84 ↔ LV95 uses swisstopo's published approximate formulas (accuracy ≈ 1 m, far below the
100 m cell). The same pure function exists in Kotlin (`dsp` module, `geo/Lv95.kt`) and in
JavaScript (`docs/map/lv95.js`), each unit-tested against the reference points below and
against each other on a fixture of Zürich points. No agent needs to look anything up:

| Point | LV95 E | LV95 N | φ (ETRF93) | λ (ETRF93) | tolerance |
|---|---|---|---|---|---|
| swisstopo worked example | 2 700 000.00 | 1 100 000.00 | 46° 02′ 38.87″ | 8° 43′ 49.79″ | 1 m |
| AGNES ZIMM (Zimmerwald) | 2 602 030.740 | 1 191 775.030 | 46° 52′ 37.540569″ | 7° 27′ 54.983511″ | 1 m |
| AGNES ETH2 (ETH Zürich) | 2 680 910.112 | 1 251 259.201 | 47° 24′ 25.842486″ | 8° 30′ 38.194637″ | 1 m |
| AGNES LOMO (Locarno Monti) | 2 704 160.863 | 1 114 349.376 | 46° 10′ 21.225556″ | 8° 47′ 14.732003″ | 1 m |
| AGNES GENE (Genève) | 2 498 930.196 | 1 122 714.152 | 46° 14′ 53.692140″ | 6° 07′ 41.065513″ | 3 m |

(AGNES is swisstopo's permanent GNSS station network; the published station coordinates are
the reference. The approximate formulas were checked against all five on 2026-10-08: worst
case 2.2 m at Genève, the rest under 0.5 m.)

Cell → polygon for display: the four corners (E, N), (E+100, N), (E+100, N+100), (E, N+100)
converted to WGS84; drawn as a quadrilateral (it is not quite a square in Web Mercator and
that is fine).

## 4. What the app sends

All timestamps are ISO-8601 with zone offset, as in the CSV export. Levels in dB(A), one
decimal. JSON, gzip-compressed request bodies, idempotent on `(device_id, start)`.

### 4.1 Device (`POST /v1/devices`)

Request: `{ "app_version": "0.4.0", "app_build": 40 }` (no device info yet).
Response: `{ "device_id": "<uuid4>", "token": "<32 random bytes, base64url>" }`.
The token is sent as `Authorization: Bearer <token>` on every later call for that device.

### 4.2 Site (`PUT /v1/devices/{id}/site`)

The sensor's placement. Sent at opt-in and whenever the contributor changes it.

```json
{
  "cell": "h26824_12473",
  "placement": "open_window | balcony | behind_glass | other",
  "floor": 3,                      // 0 = ground floor; null = not given
  "street_facing": true,           // mic faces the street (vs courtyard)
  "device_model": "Pixel 8",       // android.os.Build.MODEL
  "audio_source": "UNPROCESSED | VOICE_RECOGNITION",
  "calibrated": true,
  "calibration_offset_db": 121.9,
  "note": "optional free text ≤ 200 chars, e.g. '2. OG, Seestrasse-Seite'"
}
```

`note` is for the contributor's own memory and the project's eyes; it is never published.

### 4.3 Minutes (`POST /v1/devices/{id}/minutes`)

Array of up to 1 440 minute records, each the app's `MinuteEntity` minus the `orig_*` and
`recalibrated_*` columns (the server receives the current, i.e. re-evaluated, levels and the
calibration id they were measured with — that is enough to reason about calibration):

```json
{
  "start": "2026-10-07T23:14:00+02:00",
  "duration_s": 60.0,
  "valid_s": 59.5,
  "coverage": 0.992,
  "laeq_db": 42.3, "lafmax_db": 61.0, "lafmin_db": 33.1,
  "l1_db": 55.0, "l10_db": 47.2, "l50_db": 41.0, "l90_db": 36.4,
  "event_count": 2,
  "dominant_category": "road_traffic",
  "category_shares": { "road_traffic": 0.41, "loud_vehicle": 0.05, "unclassified": 0.54 },
  "calibration_id": "a1b2…", "calibration_offset_db": 121.9, "calibrated": true,
  "audio_source": "UNPROCESSED",
  "clock_corrections": 0
}
```

Response: `{ "accepted": n, "duplicates": m }`. A record whose `start` already exists for
the device replaces the stored one (re-evaluation after a calibration sends the night again).

### 4.4 Events (`POST /v1/devices/{id}/events`)

Array of up to 2 000 events:

```json
{
  "start": "2026-10-07T23:14:12.375+02:00",
  "duration_s": 2.75,
  "lafmax_db": 71.4,
  "sel_db": 74.0,
  "background_db": 38.1,
  "threshold_db": 6.5,
  "min_level_db": 30.0,
  "category": "loud_vehicle",
  "category_score": 0.31,
  "calibration_id": "a1b2…", "calibrated": true
}
```

`threshold_db` is the start threshold the app's detector had in force: since app v0.4.0
(detector v2) the excess over the local floor (L90 of the last 30 s; default 6.5 dB), before that
the threshold over the 5-min background (default 10 dB). Events the app flags as wind on the
microphone (detector v2) are not sent, just as the app leaves them out of its own counts; the
minutes' `event_count` (§4.3) likewise counts events without wind.

Idempotent on `(device_id, start)`; same replace rule as minutes.

### 4.5 Delete (`DELETE /v1/devices/{id}`)

Deletes the device, its site, minutes and events in one transaction. Response `204`.

### 4.6 Upload cadence in the app

While «Messwerte teilen» is on: at the end of every full hour and when a measurement stops,
the app sends all minutes and events newer than the last acknowledged `start`, in batches,
over Wi-Fi or mobile data (setting «nur über WLAN», default on). Failures are retried with
backoff; nothing is lost because the local database is the source of truth. The settings
screen shows «Zuletzt gesendet: …» and the number of minutes still to send.

## 5. Server storage (SQLite)

```
devices(id TEXT PK, token_hash TEXT, app_version TEXT, created_at, last_seen_at, hidden INT DEFAULT 0)
sites(device_id PK→devices, cell TEXT, placement TEXT, floor INT, street_facing INT,
      device_model TEXT, audio_source TEXT, calibrated INT, calibration_offset_db REAL,
      note TEXT, updated_at)
minutes(device_id, start_utc INT, start_iso TEXT, … all fields of 4.3 …, received_at,
        PRIMARY KEY(device_id, start_utc))
events(device_id, start_utc INT, start_iso TEXT, … all fields of 4.4 …, received_at,
       PRIMARY KEY(device_id, start_utc))
counters(name TEXT PK, value INT)          -- schema v2; only 'devices_deleted' (§6.4)
```

`token_hash` is SHA-256 of the token; the token itself is never stored. `hidden` lets the
operator keep a misbehaving device out of the aggregates without deleting the data (it is
still deletable by its owner). Indexes on `minutes(start_utc)`, `events(start_utc)`,
`sites(cell)`.

Retention: raw minutes and events are kept for 2 years, then reduced to the hourly
aggregates of section 6 (a nightly job). Backups: nightly SQLite `.backup` to encrypted
object storage in Switzerland, 30 days.

## 6. Published aggregates (what the website reads)

Rebuilt every 10 minutes and written as static JSON files served with
`Cache-Control: max-age=300` under `/v1/map/`. The website only ever reads these.

### 6.1 `cells.json` — the map

```json
{
  "generated_at": "2026-10-08T06:40:00+02:00",
  "night": "2026-10-07",                 // the night 22:00 of this date → 06:00 next day
  "min_devices_per_cell": 1,
  "cells": [
    {
      "cell": "h26824_12473",
      "devices": 1,
      "calibrated": true,                // all contributing devices calibrated
      "last_night": {
        "laeq_db": 46.8, "l90_db": 37.2, "l10_db": 49.9,
        "dynamics_db": 11.5,             // median over valid minutes of (L10 − L90)
        "events_per_h": 7.3,
        "events_per_h_by_category": { "loud_vehicle": 1.1, "road_traffic": 4.9, "…": 0 },
        "loudest_event_db": 84.1,
        "measured_share": 0.97           // valid time / night length
      },
      "last_7_nights": { "laeq_db": 47.1, "dynamics_db": 10.9, "events_per_h": 6.8,
                         "nights_with_data": 6 },
      "last_30_nights": { "…same…": 0 }
    }
  ],
  "network": { "devices_active_7d": 14, "device_models": { "Pixel 8": 3, "…": 1 } }
}
```

Night rules are the app's: a night is 22:00–06:00 local (Europe/Zurich, DST-aware),
LAeq is the energy average over valid time of minutes with coverage ≥ 50 %, events/h is per
hour of valid measurement. With several devices in a cell, the cell value is the energy
mean of the device night-LAeqs (levels), the mean of the per-device rates (events/h), and
the median of the per-device medians (dynamics). The arithmetic is one pure module
(`server/stadtlaerm_server/aggregate.py`) with tests that reuse the fixture nights from
the app's chart tests.

### 6.2 `cells/{cell}.json` — one cell's history

Per night, the `last_night` block above, for the last 90 nights, plus per hour of the last
night: `{ "hour": "23", "laeq_db", "l90_db", "l10_db", "events": {…by category…},
"loudest_db", "measured_share" }`. This feeds the night chart in the hover card.

### 6.3 `nights/{date}.json`

`cells.json` for a past night (last 90 nights kept), so the map has a date control.

### 6.4 `stats.json` — usage figures of the whole project

Written in the same publish run, served the same way (`/v1/map/stats.json`, same cache and
CORS headers), and printed by `python -m stadtlaerm_server stats`. Counts only:

```json
{
  "generated_at": "2026-10-25T06:40:00+01:00",
  "devices_registered": 6, "devices_with_site": 4, "devices_ever_shared": 5,
  "devices_active_7d": 3, "devices_active_30d": 4, "devices_deleted_total": 0,
  "cells_with_data_30d": 2, "nights_shared_total": 14,
  "app_versions": { "0.5.0": 2, "andere": 1, "unbekannt": 1 },
  "registrations_by_week": [ { "week": "2026-W32", "devices": 1 }, "… 12 weeks, oldest first …" ]
}
```

The exact definitions are in `server/stadtlaerm_server/stats.py`. In short: active = a valid
minute in the window (the rule of `network.devices_active_7d`, but over every device, hidden
and site-less ones included); `devices_ever_shared` counts minutes already reduced to hourly
rows by retention; `app_versions` is the version reported at registration (the server is not
told about updates), per device active in the last 30 days, anything that does not look like a
version number as `"andere"`; `registrations_by_week` counts only devices that still exist.
`devices_deleted_total` is the only number kept for this purpose alone: one integer in
`counters`, incremented in the same transaction as a device's deletion.

**Privacy.** The file adds no new data collection and no new kind of data: every figure is a
count over the whole network, computed from the rows the map already uses, with no place
(no cell, no breakdown by area), no device id, no time finer than an ISO week (and only for
registrations), and no IP address — the server has none to give (no access log in Caddy or
uvicorn; rate limits keep IPs in memory only). A deletion still removes every row of the
device (§2.4); what remains is +1 on an anonymous counter, which cannot be traced back to a
device. So §2 is not weakened. No breakdown finer than the fields above may be added without
changing this section first. The app's Offline-Version has no internet permission and
contributes nothing to this file; only devices that switched on «Messwerte teilen» in the
Karten-Version are counted.

## 7. The map page (`docs/karte.html`)

- Own CSP: `default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:
  https://wmts.geo.admin.ch; connect-src https://api.stadtlaerm.ch`. The page says, in one
  sentence above the map, that it loads map tiles from swisstopo and the measurements from
  api.stadtlaerm.ch and nothing else. `datenschutz.html` gets the same sentence.
- **Leaflet** (vendored into `docs/vendor/`, no CDN), raster tiles
  `https://wmts.geo.admin.ch/1.0.0/ch.swisstopo.pixelkarte-grau/default/current/3857/{z}/{x}/{y}.jpeg`
  with credit «© swisstopo» — the same tile source and credit pattern as the `swisstopo`
  layers in blauewelt/earth (`src/app.js`, the `wmts.geo.admin.ch` entries); swisstopo
  answers 400 outside Switzerland, which earth handles by bounding the layer — here the map
  is bounded to the Zürich region anyway.
- Cells drawn as filled quadrilaterals (section 3), coloured by the chosen metric:
  **night LAeq** (default), **dynamics**, **events per hour**, **Töff & Poser per hour**.
  The LAeq scale is anchored on the night values of the Swiss Noise Abatement Ordinance
  (Lärmschutz-Verordnung, LSV, Anhang 3, road traffic; Lr in dB(A), night), so the colours
  mean something. The table, so that nobody has to look it up:

  | Empfindlichkeitsstufe | Planungswert | Immissionsgrenzwert | Alarmwert |
  |---|---|---|---|
  | ES I (recreation zones) | 40 | 45 | 60 |
  | ES II (residential) | 45 | 50 | 65 |
  | ES III (mixed residential/commercial) | 50 | 55 | 65 |
  | ES IV (industrial) | 55 | 65 | 70 |

  Legend ticks at 45 · 50 · 55 · 65 dB(A) with the plain-German meaning of each (Planungswert
  ES II, Immissionsgrenzwert ES II, Immissionsgrenzwert ES III, Alarmwert ES II/III). The
  legend must say, in one line, that the ordinance's values are *Beurteilungspegel Lr*
  (a rated level with corrections, assessed over the whole year) while the map shows the
  measured LAeq of single nights — the ticks are for orientation, not a legal finding.
- Tap/hover card in the style of earth's pixel inspector: cell, devices, last night's
  numbers each with *when* it was measured, a small night chart (L90–L10 band, LAeq line,
  event dots with the chosen category in amber — the app's chart, redrawn in SVG), and the
  7- and 30-night means. «Unkalibriert» badge when any device in the cell is uncalibrated.
- Date control: last night by default, ‹ › through the last 90 nights.
- Everything is static files plus two JSON fetches; works at 360 px; dark mode via the
  site's existing CSS variables. Hash-stamped asset URLs as in earth's
  `scripts/stamp_assets.py` so a phone never runs yesterday's script against today's JSON.
- Playwright tests at 360 px against a fixture `cells.json`: renders, legend ticks, card
  opens, date control, CSP has exactly the two external hosts.

## 8. Hosting

Code is hosting-agnostic: one container (Python 3.12, FastAPI, SQLite on a volume, the
aggregation job as a loop in the same process), reverse-proxied by Caddy for TLS.
Target: **Infomaniak** (Swiss company, the project's registrar already), a small VPS /
Public Cloud instance in Switzerland, `api.stadtlaerm.ch` pointed at it. Alternative if a
managed service is preferred: Google Cloud Run in `europe-west6` (Zürich) with the SQLite
file on a mounted volume — the container does not change. Deployment is `docker compose
up`; `server/README.md` documents it.

Abuse limits: 10 device registrations per IP per day, 60 requests per device per hour,
request body ≤ 2 MB, strict schema validation (values outside 0–140 dB, coverage outside
0–1, starts more than 7 days old or in the future are rejected with the reason).

## 9. App changes (public flavour)

(Since 0.5.0 the public app is published in two editions with the same app id: the
**Karten-Version**, Gradle flavour `public`, which has everything below, and the
**Offline-Version**, flavour `offline`, which has no `INTERNET` permission and no
«Messwerte teilen» at all. See PRIVACY.md.)

- Manifest gains `INTERNET` (the `tools:node="remove"` goes); `PRIVACY.md`, `README.md`
  and the website change in the same release to say: internet is used only for the opt-in
  upload described here, nothing else, and here is how to check (one network class,
  `upload/UploadClient.kt`, one host, no third-party SDK).
- New screen **Einstellungen → Messwerte teilen**: explanation (what is sent / what is
  never sent / where it goes / how to delete), the map placement step (a small map, same
  swisstopo tiles, pin → hectare shown as a square, «Dieses Hektar wird gezeigt»),
  placement/floor/street-facing fields, the switch, «nur über WLAN», «Zuletzt gesendet»,
  «Meine Daten auf dem Server löschen», «Neue Kennung».
- Upload worker: WorkManager periodic + on measurement stop; batches as in 4.6; tested on
  the JVM with a fake server.
- Nothing in the measurement path changes.

## 10. Out of scope for the first version

Per-device public pages, user accounts, comparing cells, exporting from the website,
non-Zürich bounding, the windowsill sensor's own upload (it will speak the same API).
