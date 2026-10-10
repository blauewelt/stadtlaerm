"""SQLite storage (DESIGN.md §5): connection setup, migrations by version, row access.

Times: `start_utc` is UTC epoch **milliseconds** (events have millisecond starts);
`start_iso` is the timestamp exactly as the app sent it. Bookkeeping times
(`created_at`, `received_at`, …) are ISO-8601 UTC strings.
"""

from __future__ import annotations

import json
import sqlite3
from collections.abc import Iterator, Sequence
from contextlib import contextmanager
from datetime import UTC, datetime
from pathlib import Path

from . import aggregate as agg
from .schemas import EventIn, MinuteIn

MIGRATIONS: list[str] = [
    # 1: the tables of DESIGN.md §5, plus `hourly` for the retention job.
    """
    CREATE TABLE devices (
        id TEXT PRIMARY KEY,
        token_hash TEXT NOT NULL,
        app_version TEXT,
        app_build INTEGER,
        created_at TEXT NOT NULL,
        last_seen_at TEXT,
        hidden INTEGER NOT NULL DEFAULT 0
    );
    CREATE TABLE sites (
        device_id TEXT PRIMARY KEY REFERENCES devices(id) ON DELETE CASCADE,
        cell TEXT NOT NULL,
        placement TEXT NOT NULL,
        floor INTEGER,
        street_facing INTEGER,
        device_model TEXT,
        audio_source TEXT,
        calibrated INTEGER,
        calibration_offset_db REAL,
        note TEXT,
        updated_at TEXT NOT NULL
    );
    CREATE INDEX sites_cell ON sites(cell);
    CREATE TABLE minutes (
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        start_utc INTEGER NOT NULL,
        start_iso TEXT NOT NULL,
        duration_s REAL NOT NULL,
        valid_s REAL NOT NULL,
        coverage REAL NOT NULL,
        laeq_db REAL, lafmax_db REAL, lafmin_db REAL,
        l1_db REAL, l10_db REAL, l50_db REAL, l90_db REAL,
        event_count INTEGER NOT NULL,
        dominant_category TEXT,
        category_shares TEXT NOT NULL,
        calibration_id TEXT,
        calibration_offset_db REAL,
        calibrated INTEGER NOT NULL,
        audio_source TEXT,
        clock_corrections INTEGER NOT NULL,
        received_at TEXT NOT NULL,
        PRIMARY KEY (device_id, start_utc)
    ) WITHOUT ROWID;
    CREATE INDEX minutes_start ON minutes(start_utc);
    CREATE TABLE events (
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        start_utc INTEGER NOT NULL,
        start_iso TEXT NOT NULL,
        duration_s REAL NOT NULL,
        lafmax_db REAL NOT NULL,
        sel_db REAL,
        background_db REAL,
        threshold_db REAL,
        min_level_db REAL,
        category TEXT NOT NULL,
        category_score REAL,
        calibration_id TEXT,
        calibrated INTEGER NOT NULL,
        received_at TEXT NOT NULL,
        PRIMARY KEY (device_id, start_utc)
    ) WITHOUT ROWID;
    CREATE INDEX events_start ON events(start_utc);
    CREATE TABLE hourly (
        device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
        hour_utc INTEGER NOT NULL,
        valid_s REAL NOT NULL,
        valid_minutes INTEGER NOT NULL,
        laeq_db REAL, l90_db REAL, l10_db REAL, dynamics_db REAL,
        events TEXT NOT NULL,
        loudest_db REAL,
        calibrated INTEGER NOT NULL,
        PRIMARY KEY (device_id, hour_utc)
    ) WITHOUT ROWID;
    """,
]

MINUTE_COLUMNS = (
    "device_id", "start_utc", "start_iso", "duration_s", "valid_s", "coverage",
    "laeq_db", "lafmax_db", "lafmin_db", "l1_db", "l10_db", "l50_db", "l90_db",
    "event_count", "dominant_category", "category_shares", "calibration_id",
    "calibration_offset_db", "calibrated", "audio_source", "clock_corrections", "received_at",
)  # fmt: skip
EVENT_COLUMNS = (
    "device_id", "start_utc", "start_iso", "duration_s", "lafmax_db", "sel_db",
    "background_db", "threshold_db", "min_level_db", "category", "category_score",
    "calibration_id", "calibrated", "received_at",
)  # fmt: skip


def utc_now_iso() -> str:
    return datetime.now(UTC).isoformat(timespec="seconds")


def connect(path: Path | str) -> sqlite3.Connection:
    if str(path) != ":memory:":
        Path(path).parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(path, timeout=10, check_same_thread=False)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA synchronous=NORMAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.execute("PRAGMA busy_timeout=10000")
    return conn


def migrate(conn: sqlite3.Connection) -> int:
    """Applies every migration newer than the stored version; returns the new version."""
    conn.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
    row = conn.execute("SELECT version FROM schema_version").fetchone()
    if row is None:
        conn.execute("INSERT INTO schema_version (version) VALUES (0)")
        conn.commit()
        version = 0
    else:
        version = row[0]
    for i, script in enumerate(MIGRATIONS[version:], start=version + 1):
        # executescript commits first; wrap each step so a failure leaves the old version.
        conn.executescript(f"BEGIN;\n{script}\nUPDATE schema_version SET version = {i};\nCOMMIT;")
        version = i
    return version


@contextmanager
def open_db(path: Path | str) -> Iterator[sqlite3.Connection]:
    conn = connect(path)
    try:
        yield conn
    finally:
        conn.close()


# ---- writes -----------------------------------------------------------------------------


def upsert_rows(conn: sqlite3.Connection, table: str, columns: Sequence[str], rows: Sequence[dict]) -> tuple[int, int]:
    """Insert-or-replace on (device_id, start_utc). Returns (new rows, replaced rows).

    A record whose start already exists — stored earlier or earlier in the same batch — counts
    as a duplicate; the later one wins.
    """
    if not rows:
        return 0, 0
    device_id = rows[0]["device_id"]
    starts = [r["start_utc"] for r in rows]
    seen: set[int] = set()
    for i in range(0, len(starts), 900):
        chunk = starts[i : i + 900]
        q = f"SELECT start_utc FROM {table} WHERE device_id = ? AND start_utc IN ({','.join('?' * len(chunk))})"
        seen.update(r[0] for r in conn.execute(q, [device_id, *chunk]))
    accepted = duplicates = 0
    for s in starts:
        if s in seen:
            duplicates += 1
        else:
            accepted += 1
            seen.add(s)
    cols = ", ".join(columns)
    marks = ", ".join(f":{c}" for c in columns)
    updates = ", ".join(f"{c} = excluded.{c}" for c in columns if c not in ("device_id", "start_utc"))
    conn.executemany(
        f"INSERT INTO {table} ({cols}) VALUES ({marks}) ON CONFLICT (device_id, start_utc) DO UPDATE SET {updates}",
        rows,
    )
    return accepted, duplicates


def delete_device(conn: sqlite3.Connection, device_id: str) -> None:
    """Every row of the device, in one transaction (DESIGN.md §2.4, §4.5)."""
    with conn:
        for table in ("minutes", "events", "hourly", "sites"):
            conn.execute(f"DELETE FROM {table} WHERE device_id = ?", (device_id,))
        conn.execute("DELETE FROM devices WHERE id = ?", (device_id,))


# ---- reads for aggregation ---------------------------------------------------------------


def load_minutes(conn: sqlite3.Connection, device_id: str, start_ms: int, end_ms: int) -> list[agg.Minute]:
    rows = conn.execute(
        "SELECT start_utc, valid_s, coverage, laeq_db, l10_db, l90_db, calibrated FROM minutes "
        "WHERE device_id = ? AND start_utc >= ? AND start_utc < ? ORDER BY start_utc",
        (device_id, start_ms, end_ms),
    )
    return [agg.Minute(r[0], r[1], r[2], r[3], r[4], r[5], bool(r[6])) for r in rows]


def load_events(conn: sqlite3.Connection, device_id: str, start_ms: int, end_ms: int) -> list[agg.Event]:
    rows = conn.execute(
        "SELECT start_utc, lafmax_db, category FROM events "
        "WHERE device_id = ? AND start_utc >= ? AND start_utc < ? ORDER BY start_utc",
        (device_id, start_ms, end_ms),
    )
    return [agg.Event(r[0], r[1], r[2]) for r in rows]


def minute_row(device_id: str, m: MinuteIn, received_at: str) -> dict:
    d = m.model_dump()
    del d["start"]
    d.update(
        device_id=device_id, start_utc=m.start_ms, start_iso=m.start, received_at=received_at,
        category_shares=json.dumps(m.category_shares, separators=(",", ":")), calibrated=int(m.calibrated),
    )  # fmt: skip
    return d


def event_row(device_id: str, e: EventIn, received_at: str) -> dict:
    d = e.model_dump()
    del d["start"]
    d.update(
        device_id=device_id,
        start_utc=e.start_ms,
        start_iso=e.start,
        received_at=received_at,
        calibrated=int(e.calibrated),
    )
    return d


def hour_row(device_id: str, h: agg.DeviceHour) -> dict:
    return {
        "device_id": device_id,
        "hour_utc": h.hour_ms,
        "valid_s": h.valid_s,
        "valid_minutes": h.valid_minutes,
        "laeq_db": h.laeq_db,
        "l90_db": h.l90_db,
        "l10_db": h.l10_db,
        "dynamics_db": h.dynamics_db,
        "events": json.dumps(h.events, separators=(",", ":")),
        "loudest_db": h.loudest_db,
        "calibrated": int(h.calibrated),
    }
