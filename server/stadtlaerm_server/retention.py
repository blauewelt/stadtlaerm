"""Retention (DESIGN.md §5): raw minutes and events older than RETENTION_DAYS (2 years)
are reduced to per-device hourly aggregates in `hourly`, then deleted.

The hourly rows use the same arithmetic as the published hour rows (aggregate.summarize_hour),
for every clock hour that has data, day or night. Run nightly; it is idempotent.
"""

from __future__ import annotations

import sqlite3
from datetime import UTC, datetime, timedelta

from . import aggregate as agg
from . import db

HOUR_COLUMNS = (
    "device_id", "hour_utc", "valid_s", "valid_minutes", "laeq_db", "l90_db", "l10_db",
    "dynamics_db", "events", "loudest_db", "calibrated",
)  # fmt: skip


def run_retention(conn: sqlite3.Connection, now: datetime | None = None, retention_days: int = 730) -> dict:
    """Returns counts: {"hours": n, "minutes_deleted": n, "events_deleted": n}."""
    now = now or datetime.now(UTC)
    # Cut at a whole hour so an hour is never split between raw and reduced data.
    cutoff_ms = int((now - timedelta(days=retention_days)).timestamp() * 1000) // agg.HOUR_MS * agg.HOUR_MS
    hours_written = 0
    with conn:
        devices = [
            r[0]
            for r in conn.execute(
                "SELECT device_id FROM minutes WHERE start_utc < ? "
                "UNION SELECT device_id FROM events WHERE start_utc < ?",
                (cutoff_ms, cutoff_ms),
            )
        ]
        cols = ", ".join(HOUR_COLUMNS)
        marks = ", ".join(f":{c}" for c in HOUR_COLUMNS)
        for device_id in devices:
            minutes = db.load_minutes(conn, device_id, 0, cutoff_ms)
            events = db.load_events(conn, device_id, 0, cutoff_ms)
            hours = sorted(
                {m.start_ms // agg.HOUR_MS * agg.HOUR_MS for m in minutes}
                | {e.start_ms // agg.HOUR_MS * agg.HOUR_MS for e in events}
            )
            by_hour_m: dict[int, list[agg.Minute]] = {}
            by_hour_e: dict[int, list[agg.Event]] = {}
            for m in minutes:
                by_hour_m.setdefault(m.start_ms // agg.HOUR_MS * agg.HOUR_MS, []).append(m)
            for e in events:
                by_hour_e.setdefault(e.start_ms // agg.HOUR_MS * agg.HOUR_MS, []).append(e)
            rows = [
                db.hour_row(device_id, agg.summarize_hour(h, by_hour_m.get(h, []), by_hour_e.get(h, []))) for h in hours
            ]
            conn.executemany(f"INSERT OR REPLACE INTO hourly ({cols}) VALUES ({marks})", rows)
            hours_written += len(rows)
        m_del = conn.execute("DELETE FROM minutes WHERE start_utc < ?", (cutoff_ms,)).rowcount
        e_del = conn.execute("DELETE FROM events WHERE start_utc < ?", (cutoff_ms,)).rowcount
    return {"hours": hours_written, "minutes_deleted": m_del, "events_deleted": e_del}
