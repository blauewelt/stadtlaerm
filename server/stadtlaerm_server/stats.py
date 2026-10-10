"""Project-wide usage figures (DESIGN.md §6.4): `stats.json` next to the map files.

Only counts over the whole network: no device ids, no per-device rows, no cells, no places,
no IP addresses (the server stores none). Computed from the data the server already keeps
for the map; nothing new is collected for it — except the single integer `devices_deleted`
in the `counters` table, incremented when a device deletes itself (db.delete_device).

Definitions (all "now" = the publish time):

- `devices_registered`: rows in `devices`, hidden ones included (deleted devices are gone).
- `devices_with_site`: of those, the ones that have sent a site (a hectare).
- `devices_ever_shared`: devices with at least one minute record, counting those whose old
  minutes the retention job already reduced to `hourly` rows.
- `devices_active_7d` / `_30d`: devices with a valid minute (the map's rule, aggregate.Minute.valid:
  coverage ≥ 50 %, a level, valid time > 0) that started in the last 7 / 30 days. Same rule as
  `network.devices_active_7d` in cells.json, but over every device (hidden and site-less ones
  included), so it can be larger than the map's figure.
- `devices_deleted_total`: how many devices have deleted their data on the server since the
  counter exists (one integer, no ids, no dates).
- `cells_with_data_30d`: hectares shown on the current map (cells.json), i.e. with data in the
  30 nights up to the last complete night, after MIN_DEVICES_PER_CELL.
- `nights_shared_total`: device-nights (22–06 local) with at least one valid minute, including
  nights already reduced to hourly rows (an hour with valid minutes).
- `app_versions`: devices active in the last 30 days per `app_version`, the version the app
  reported when it registered (the server is not told about later updates). Strings that do not
  look like a version number are counted as "andere", a missing one as "unbekannt".
- `registrations_by_week`: devices created per ISO week (Europe/Zurich), the last 12 weeks
  including the current one, oldest first, zeros included. Only devices that still exist.
"""

from __future__ import annotations

import re
import sqlite3
from collections import Counter
from datetime import UTC, date, datetime, timedelta

from . import aggregate as agg

ACTIVE_SHORT_DAYS = 7
ACTIVE_LONG_DAYS = 30
WEEKS = 12
VERSION_RE = re.compile(r"^[0-9]{1,3}\.[0-9]{1,3}(\.[0-9]{1,3})?(-[a-z]{1,12})?$")

# SQL form of aggregate.Minute.valid (kept in step by tests/test_stats.py).
VALID_MINUTE_SQL = f"coverage >= {agg.MIN_MINUTE_COVERAGE} AND laeq_db IS NOT NULL AND valid_s > 0"


def _count(conn: sqlite3.Connection, sql: str, *args: object) -> int:
    return int(conn.execute(sql, args).fetchone()[0])


def _active_ids(conn: sqlite3.Connection, since_ms: int, now_ms: int) -> set[str]:
    rows = conn.execute(
        f"SELECT DISTINCT device_id FROM minutes WHERE start_utc >= ? AND start_utc <= ? AND {VALID_MINUTE_SQL}",
        (since_ms, now_ms),
    )
    return {r[0] for r in rows}


def _version_key(v: str | None) -> str:
    if v is None or v == "":
        return "unbekannt"
    return v if VERSION_RE.match(v) else "andere"


def _iso_week(d: date) -> str:
    y, w, _ = d.isocalendar()
    return f"{y}-W{w:02d}"


def _created_local_date(created_at: str | None) -> date | None:
    if not created_at:
        return None
    try:
        t = datetime.fromisoformat(created_at)
    except ValueError:
        return None
    if t.tzinfo is None:
        t = t.replace(tzinfo=UTC)
    return t.astimezone(agg.ZONE).date()


def compute(conn: sqlite3.Connection, now: datetime, cells_with_data_30d: int) -> dict:
    """The stats.json object. `cells_with_data_30d` comes from the cells.json of the same run."""
    now_ms = int(now.timestamp() * 1000)
    since_7 = int((now - timedelta(days=ACTIVE_SHORT_DAYS)).timestamp() * 1000)
    since_30 = int((now - timedelta(days=ACTIVE_LONG_DAYS)).timestamp() * 1000)

    active_30 = _active_ids(conn, since_30, now_ms)
    versions: Counter[str] = Counter()
    if active_30:
        for device_id, version in conn.execute("SELECT id, app_version FROM devices"):
            if device_id in active_30:
                versions[_version_key(version)] += 1

    # Device-nights with a valid minute: distinct (device, hour) first, then hour → night. Night
    # bounds are whole local hours, so every minute of an hour belongs to the same night.
    device_hours = conn.execute(
        f"SELECT DISTINCT device_id, start_utc / {agg.HOUR_MS} FROM minutes WHERE {VALID_MINUTE_SQL} "
        f"UNION SELECT device_id, hour_utc / {agg.HOUR_MS} FROM hourly WHERE valid_minutes > 0"
    )
    nights = {(dev, n) for dev, h in device_hours if (n := agg.night_of(int(h) * agg.HOUR_MS)) is not None}

    today = now.astimezone(agg.ZONE).date()
    this_monday = today - timedelta(days=today.weekday())
    weeks = [_iso_week(this_monday - timedelta(weeks=i)) for i in range(WEEKS - 1, -1, -1)]
    first_monday = this_monday - timedelta(weeks=WEEKS - 1)
    per_week: Counter[str] = Counter()
    for (created_at,) in conn.execute("SELECT created_at FROM devices"):
        d = _created_local_date(created_at)
        if d is not None and first_monday <= d <= today:
            per_week[_iso_week(d)] += 1

    deleted = conn.execute("SELECT value FROM counters WHERE name = 'devices_deleted'").fetchone()

    return {
        "generated_at": now.astimezone(agg.ZONE).isoformat(timespec="seconds"),
        "devices_registered": _count(conn, "SELECT COUNT(*) FROM devices"),
        "devices_with_site": _count(conn, "SELECT COUNT(*) FROM devices d JOIN sites s ON s.device_id = d.id"),
        "devices_ever_shared": _count(
            conn,
            "SELECT COUNT(*) FROM devices d WHERE EXISTS (SELECT 1 FROM minutes m WHERE m.device_id = d.id) "
            "OR EXISTS (SELECT 1 FROM hourly h WHERE h.device_id = d.id)",
        ),
        "devices_active_7d": len(_active_ids(conn, since_7, now_ms)),
        "devices_active_30d": len(active_30),
        "devices_deleted_total": int(deleted[0]) if deleted else 0,
        "cells_with_data_30d": cells_with_data_30d,
        "nights_shared_total": len(nights),
        "app_versions": dict(sorted(versions.items())),
        "registrations_by_week": [{"week": w, "devices": per_week.get(w, 0)} for w in weeks],
    }
