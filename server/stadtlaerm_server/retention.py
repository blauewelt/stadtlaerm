"""Retention (DESIGN.md §5): raw minutes and events older than RETENTION_DAYS (2 years)
are reduced to per-device hourly aggregates in `hourly`, then deleted.

The hourly rows use the same arithmetic as the published hour rows (aggregate.summarize_hour),
for every clock hour that has data, day or night. Run nightly; it is idempotent.

Expiry (DESIGN.md §2.9, §5): a device that has made no authenticated request for
INACTIVE_DELETE_DAYS (60) days is deleted completely, exactly like a deletion the device asked
for (db.delete_device_rows), and counted only in the anonymous counter `devices_expired`.
"""

from __future__ import annotations

import logging
import sqlite3
from datetime import UTC, datetime, timedelta

from . import aggregate as agg
from . import db

log = logging.getLogger("stadtlaerm")

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


def _parse_utc(value: str | None) -> datetime | None:
    try:
        t = datetime.fromisoformat(value) if value else None
    except ValueError:
        return None
    if t is not None and t.tzinfo is None:
        t = t.replace(tzinfo=UTC)  # the server writes UTC; a naive value can only be UTC
    return t


def expire_inactive(conn: sqlite3.Connection, now: datetime | None = None, inactive_days: int = 60) -> int:
    """Deletes every device whose last authenticated request (`last_seen_at`, or `created_at` if
    it never made one) is more than `inactive_days` ago; hidden devices too. Returns the count.

    One transaction per device, which re-reads the time under a write lock: a device that
    uploads while the job runs is kept. 0 (or less) disables the rule. Logs the count only.
    """
    if inactive_days <= 0:
        return 0
    now = now or datetime.now(UTC)
    cutoff = now - timedelta(days=inactive_days)
    candidates = []
    for device_id, last in conn.execute("SELECT id, COALESCE(last_seen_at, created_at) FROM devices"):
        t = _parse_utc(last)
        # A time the server did not write itself (unparseable) is never a reason to delete.
        if t is not None and t < cutoff:
            candidates.append((device_id, last))
    expired = 0
    for device_id, last in candidates:
        with conn:
            conn.execute("BEGIN IMMEDIATE")
            row = conn.execute(
                "SELECT COALESCE(last_seen_at, created_at) FROM devices WHERE id = ?", (device_id,)
            ).fetchone()
            if row is None or row[0] != last:
                continue  # deleted by its owner or seen again since the scan
            if db.delete_device_rows(conn, device_id):
                db.bump_counter(conn, "devices_expired")
                expired += 1
    log.info("expiry: %d devices deleted after %d days without uploads", expired, inactive_days)
    return expired
