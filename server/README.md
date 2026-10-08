# Stadtlärm contribution server

The server behind the shared noise map: phones that opted in to «Messwerte teilen» send their
minute and event records here, and every 10 minutes the server turns them into per-hectare
night figures that the map page reads as static JSON. [DESIGN.md](DESIGN.md) is the contract;
this file is about running it.

One process (Python 3.12, FastAPI, SQLite) does everything: the upload API, the static map
files under `/v1/map/`, and a background loop that republishes the map and runs the nightly
retention job. Caddy in front of it handles TLS.

## Layout

| Path | What it is |
|---|---|
| `stadtlaerm_server/app.py` | The HTTP API of DESIGN.md §4, auth, gzip bodies, limits, `/v1/map/*`, background loop |
| `stadtlaerm_server/schemas.py` | Request validation (§4 fields, §8 limits) |
| `stadtlaerm_server/db.py` | SQLite schema (§5), migrations by version, row helpers |
| `stadtlaerm_server/aggregate.py` | Night and hour arithmetic, pure functions (§6.1 rules) |
| `stadtlaerm_server/publish.py` | Builds and atomically writes `cells.json`, `cells/{cell}.json`, `nights/{date}.json` |
| `stadtlaerm_server/retention.py` | Reduces raw rows older than 2 years to hourly aggregates |
| `stadtlaerm_server/synthetic.py` | Invented test data (a port of the app's chart test fixtures) |
| `scripts/make_fixture.py` | Generates `example_cells.json` / `example_cell.json` through the real publisher |
| `scripts/backup.sh` | Nightly encrypted SQLite backup |
| `example_cells.json` | A `cells.json` for 25 Zürich hectares, invented data, exact server format |
| `example_cell.json` | The matching `cells/h26828_12455.json` (Seestrasse, two devices) |

## Run locally

```sh
cd server
python3.12 -m venv .venv
.venv/bin/pip install -e '.[dev]'
DB_PATH=data/stadtlaerm.sqlite MAP_DIR=data/map .venv/bin/python -m stadtlaerm_server serve
# → http://localhost:8000/v1/map/cells.json  (published at startup, empty until data arrives)
```

(`make venv` and `make run` do the same.) Try it:

```sh
curl -s -X POST localhost:8000/v1/devices -H 'Content-Type: application/json' \
     -d '{"app_version":"0.4.0","app_build":40}'
# {"device_id":"…","token":"…"}  — then send  Authorization: Bearer <token>  on every call
```

Other commands: `python -m stadtlaerm_server publish` rebuilds the map files once,
`python -m stadtlaerm_server retention` runs the retention job once.

To regenerate the example fixture after a format change: `python scripts/make_fixture.py`
(about 20 s). It writes synthetic nights for 25 hectares into a temporary database and runs the
real `publish()` on it, so the file is always what the server really emits.

## Run the tests

```sh
cd server
.venv/bin/python -m pytest        # or: make test
```

The tests cover the API round trip (register, site, minutes and events as plain and gzip JSON,
duplicates, delete, auth and validation failures, rate limits, body limits, map headers),
the night arithmetic on synthetic nights (the app's Friday fixture, a 9-hour DST night, a
7-hour one, low-coverage minutes, two- and three-device cells), publishing, and retention.

## The API in one screen

| Call | Auth | Answer |
|---|---|---|
| `POST /v1/devices` `{app_version, app_build}` | – | `201 {device_id, token}` |
| `PUT /v1/devices/{id}/site` (DESIGN §4.2) | Bearer | `204` |
| `POST /v1/devices/{id}/minutes` `[…≤ 1440]` | Bearer | `200 {accepted, duplicates, rejected}` |
| `POST /v1/devices/{id}/events` `[…≤ 2000]` | Bearer | `200 {accepted, duplicates, rejected}` |
| `DELETE /v1/devices/{id}` | Bearer | `204` after every row is gone |
| `GET /v1/map/cells.json`, `/v1/map/cells/{cell}.json`, `/v1/map/nights/{date}.json` | – | static JSON |
| `GET /healthz` | – | `{ok: true}` |

Bodies may be plain JSON or gzip (`Content-Encoding: gzip`), at most 2 MB on the wire.
Errors are `{"detail": …}`; validation errors list `{field, reason}` for each problem.

**Record-level rejects.** In a minutes or events upload, a record that fails validation (a
level outside 0–140 dB, coverage outside 0–1, a start more than 7 days old or in the future,
an unknown field…) is skipped and reported in `rejected` as `{index, start, reasons}`; the valid
records of the same batch are stored. The app should treat rejected records as permanently
refused (they will never be accepted) and move on, rather than retrying the batch forever.

**Status codes.** 401 bad or missing token (unknown device and wrong token look the same),
413 body or batch too large, 415 unknown `Content-Encoding`, 400 not JSON / not gzip,
422 schema errors in the whole body, 429 rate limit (with `Retry-After`).

## Deploy on a fresh Ubuntu VPS at Infomaniak

What you need: an Infomaniak VPS or Public Cloud instance in Switzerland (the smallest size
is plenty: the database grows by roughly 100 MB per device per year of nightly measuring), Ubuntu 24.04, SSH
access, and the `stadtlaerm.ch` DNS zone (it is at Infomaniak already).

1. **DNS.** In the Infomaniak manager, DNS zone of `stadtlaerm.ch`: add an `A` record
   `api` → the server's IPv4 address (and an `AAAA` record for its IPv6 address if it has one).
   Wait until `dig +short api.stadtlaerm.ch` shows the address.
2. **Firewall.** In the Infomaniak cloud firewall (and `ufw` if you use it) allow TCP 22, 80,
   443 and UDP 443. Caddy needs port 80 to obtain the certificate.
3. **Docker.**
   ```sh
   sudo apt update && sudo apt install -y ca-certificates curl git gnupg sqlite3
   curl -fsSL https://get.docker.com | sudo sh
   ```
4. **Code and config.**
   ```sh
   sudo git clone https://github.com/blauewelt/stadtlaerm /opt/stadtlaerm
   cd /opt/stadtlaerm/server
   sudo cp .env.example .env && sudo nano .env      # usually nothing to change
   ```
5. **Start.**
   ```sh
   sudo docker compose up -d --build
   sudo docker compose logs -f                      # Caddy logs "certificate obtained"
   curl https://api.stadtlaerm.ch/healthz           # {"ok":true}
   curl -I https://api.stadtlaerm.ch/v1/map/cells.json
   ```
   The SQLite file and the map files live in the Docker volume `server_data`
   (`/data` in the container); certificates in `server_caddy_data`.
6. **Backups** (DESIGN §5: nightly, encrypted, 30 days). Create a passphrase and keep a copy
   of it somewhere safe *outside* the server (without it the backups are useless):
   ```sh
   sudo sh -c 'openssl rand -base64 32 > /root/.stadtlaerm-backup-passphrase && chmod 600 /root/.stadtlaerm-backup-passphrase'
   ```
   Optional off-site copy: create an Infomaniak Swiss Backup / object storage bucket, configure
   it as an rclone remote (`sudo apt install rclone && sudo rclone config`, S3 provider
   "Other", the Infomaniak endpoint), and set `BACKUP_REMOTE=<remote>:<bucket>` in `.env`.
   Then add the cron job (`sudo crontab -e`):
   ```cron
   15 4 * * * /opt/stadtlaerm/server/scripts/backup.sh >> /var/log/stadtlaerm-backup.log 2>&1
   ```
   `backup.sh` runs `sqlite3 … ".backup …"` inside the container (a consistent copy while the
   app keeps writing), gzips it, encrypts it with GPG (AES-256, the passphrase file), writes it
   to `/var/backups/stadtlaerm/`, copies it to `BACKUP_REMOTE` if set, and deletes copies older
   than 30 days. Restore:
   ```sh
   gpg -d stadtlaerm-YYYYmmdd-HHMM.sqlite.gz.gpg | gunzip > stadtlaerm.sqlite
   sudo docker compose stop app
   # remove the old write-ahead log first, it belongs to the old file
   sudo docker compose run --rm --no-deps app rm -f /data/stadtlaerm.sqlite-wal /data/stadtlaerm.sqlite-shm
   sudo docker compose cp stadtlaerm.sqlite app:/data/stadtlaerm.sqlite
   sudo docker compose start app
   ```
7. **Updates.** `cd /opt/stadtlaerm && sudo git pull && cd server && sudo docker compose up -d --build`.
   Schema migrations run automatically at startup (table `schema_version`).

**Operator tasks.** Keep a misbehaving device out of the map without deleting its data:
`sudo docker compose exec app sqlite3 /data/stadtlaerm.sqlite "UPDATE devices SET hidden = 1 WHERE id = '…'"`
(it disappears on the next publish; its owner can still delete it).

**Cloud Run alternative** (DESIGN §8): the same image runs on Google Cloud Run in
`europe-west6` with a volume mounted at `/data`; set `TRUST_PROXY=1`, keep one instance
(`--max-instances 1`, SQLite and the in-process rate limits assume a single process) and
`--no-cpu-throttling` so the background loop keeps running between requests.

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `DB_PATH` | `data/stadtlaerm.sqlite` (container: `/data/stadtlaerm.sqlite`) | Where the SQLite database file lives. Created on first start. |
| `MAP_DIR` | `data/map` (container: `/data/map`) | Folder the published map files are written to and served from under `/v1/map/`. |
| `MIN_DEVICES_PER_CELL` | `1` | A hectare is shown on the map for a night only if at least this many devices measured there that night. Raise it if the network becomes dense. |
| `PUBLISH_INTERVAL_S` | `600` | How often (seconds) the map files are rebuilt. They are also rebuilt at startup. |
| `BACKGROUND_JOBS` | `1` | `1` runs the publish loop and the daily retention job inside the server process; `0` turns them off (tests, or if you run `publish`/`retention` from cron instead). |
| `RETENTION_DAYS` | `730` | Raw minutes and events older than this are reduced to hourly figures and deleted (2 years per DESIGN §5). |
| `CORS_ORIGIN` | `https://stadtlaerm.ch` | The website origin allowed to read the map files from the browser (`Access-Control-Allow-Origin`). Use `http://localhost:8080` or similar for local website work. |
| `TRUST_PROXY` | `0` (compose: `1`) | `1` takes the client IP for the registration limit from the `X-Forwarded-For` header set by Caddy. Only turn on behind a proxy you control, otherwise clients could fake their IP. |
| `REGISTRATIONS_PER_IP_PER_DAY` | `10` | How many new device ids one IP address may create per day. |
| `REQUESTS_PER_DEVICE_PER_HOUR` | `60` | How many authenticated requests one device may make per hour (deleting is never limited). |
| `MAX_BODY_BYTES` | `2000000` | Largest request body accepted, as sent (compressed). A gzip body may expand to 8× this. |
| `MAX_RECORD_AGE_DAYS` | `7` | Minute and event records that started longer ago than this are rejected. |
| `CLOCK_SKEW_S` | `600` | How far in the future a record's start may be (phone clock drift) before it is rejected as "in the future". |
| `HOST`, `PORT` | `0.0.0.0`, `8000` | Where the server listens inside the container. |
| `API_HOST` | `api.stadtlaerm.ch` | (compose/Caddy) The public host name Caddy gets a certificate for. |
| `BACKUP_PASSPHRASE_FILE` | `/root/.stadtlaerm-backup-passphrase` | (backup.sh) File holding the GPG passphrase for backups. |
| `BACKUP_DIR` | `/var/backups/stadtlaerm` | (backup.sh) Where encrypted backups are kept on the host. |
| `BACKUP_REMOTE` | empty | (backup.sh) rclone destination for an off-site copy, e.g. `swissbackup:stadtlaerm`; empty = local only. |
| `BACKUP_KEEP_DAYS` | `30` | (backup.sh) Days backups are kept. |

## Published files: details beyond DESIGN.md §6

These are choices the design left open; the website builds against them (see the fixtures).

- **Which night.** `cells.json` is the last *complete* night: before 06:00 local time it is
  still the night before last. `generated_at` is local time with offset.
- **Cells without data last night** stay on the map while they have any data in the last 30
  nights; their `last_night` is `null`, `devices` is the device count of the newest night with
  data, and `calibrated` is true only if every night shown in the window was calibrated.
- **Moving a sensor.** A device's data counts for the cell of its *current* site: if a
  contributor changes the placement to another hectare, its history moves with it.
- **Night L90 / L10** are the medians over the valid minutes of the minute L90 / L10 (the
  "typical" quiet and loud level of a minute). With several devices, the energy mean.
- **`events_per_h_by_category`** always lists all eight app categories (zeros included).
- **`measured_share`** of a cell is the mean of the device shares.
- **`last_7_nights` / `last_30_nights`**: energy mean of the nightly cell LAeqs, median of the
  nightly dynamics, mean of the nightly events/h, over the nights with data in the window
  (ending with the file's night).
- **`cells/{cell}.json`**: `{generated_at, cell, devices, calibrated, nights: [last_night block +
  "night", "devices", "calibrated"; newest first; only nights with data; ≤ 90], hours_night,
  hours: [...]}`. Each hour has `hour` ("23") and `start` (ISO with offset; on the autumn DST
  night "02" appears twice and only `start` tells them apart). `events` per hour are counts
  (averaged over devices when a cell has several, hence possibly fractional). An hour's levels
  need 30 valid minutes (the app's week-view rule), otherwise they are `null`.
- **`nights/{date}.json`** exist for each of the last 90 nights, also nights without any cell.
- **`network`** is the same in every file of one run (it describes now, not the past night);
  a device counts as active if it has a valid minute in the last 7 days. Models without a name
  are counted as `"unbekannt"`.
- All numbers are rounded to 0.1 dB / 0.1 events per hour; `measured_share` to 0.01.
