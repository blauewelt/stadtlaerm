"""Builds the public map files of DESIGN.md §6 from the database and writes them atomically.

Layout in MAP_DIR (served under /v1/map/):

    cells.json              the last complete night, every shown cell (§6.1)
    cells/{cell}.json       one cell: 90 nights of `last_night` blocks + hours of the last night (§6.2)
    nights/{date}.json      cells.json for each of the last 90 nights (§6.3)

Only devices with a site that are not `hidden` contribute. A device's data belongs to the
cell of its *current* site. Files of cells or nights that are no longer shown are removed,
so a deleted device disappears from every file on the next run.
"""

from __future__ import annotations

import json
import os
import sqlite3
import tempfile
from collections import defaultdict
from collections.abc import Iterable, Iterator
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from pathlib import Path

from . import aggregate as agg
from . import db

HISTORY_NIGHTS = 90
WINDOW_LONG = 30
WINDOW_SHORT = 7
ACTIVE_DAYS = 7


@dataclass(frozen=True)
class DeviceSite:
    device_id: str
    cell: str
    device_model: str | None


@dataclass
class DeviceData:
    """Everything the publisher needs from one device, independent of the database."""

    site: DeviceSite
    minutes: list[agg.Minute]
    events: list[agg.Event]


def write_json_atomic(path: Path, obj: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=f".{path.name}.", suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as f:
            json.dump(obj, f, ensure_ascii=False, separators=(",", ":"))
            f.flush()
            os.fsync(f.fileno())
        os.chmod(tmp, 0o644)
        os.replace(tmp, path)
    except BaseException:
        Path(tmp).unlink(missing_ok=True)
        raise


def load_devices(conn: sqlite3.Connection, start_ms: int, end_ms: int) -> Iterator[DeviceData]:
    """One device at a time, so memory stays at one device's raw rows however large the network."""
    rows = conn.execute(
        "SELECT d.id, s.cell, s.device_model FROM devices d JOIN sites s ON s.device_id = d.id "
        "WHERE d.hidden = 0 ORDER BY d.id"
    ).fetchall()
    for device_id, cell, model in rows:
        yield DeviceData(
            DeviceSite(device_id, cell, model),
            db.load_minutes(conn, device_id, start_ms, end_ms),
            db.load_events(conn, device_id, start_ms, end_ms),
        )


def _split_by_night(data: DeviceData) -> tuple[dict[date, list[agg.Minute]], dict[date, list[agg.Event]]]:
    mins: dict[date, list[agg.Minute]] = defaultdict(list)
    evs: dict[date, list[agg.Event]] = defaultdict(list)
    for m in data.minutes:
        if (n := agg.night_of(m.start_ms)) is not None:
            mins[n].append(m)
    for e in data.events:
        if (n := agg.night_of(e.start_ms)) is not None:
            evs[n].append(e)
    return mins, evs


def _cell_entry(index: agg.NightIndex, cell: str, night: date) -> dict | None:
    """The cells.json entry of `cell` for the map of `night`; None if no data in the 30 nights."""
    long = index.window(cell, night, WINDOW_LONG)
    shown = [n for n in long if n is not None]
    if not shown:
        return None
    last = long[0]
    return {
        "cell": cell,
        # Without data that night: the newest night's device count and calibration state.
        "devices": (last or shown[0]).devices,
        "calibrated": last.calibrated if last else all(n.calibrated for n in shown),
        "last_night": last.block() if last else None,
        "last_7_nights": agg.window_block(long[:WINDOW_SHORT]),
        "last_30_nights": agg.window_block(long),
    }


def build(
    devices: Iterable[DeviceData], now: datetime, min_devices: int = 1
) -> tuple[dict, dict[str, dict], dict[str, dict]]:
    """Pure: device data → (cells.json, {cell: cells/{cell}.json}, {date: nights/{date}.json})."""
    last_night = agg.last_complete_night(now)
    first_night = last_night - timedelta(days=HISTORY_NIGHTS + WINDOW_LONG - 2)
    generated_at = now.astimezone(agg.ZONE).isoformat(timespec="seconds")
    active_since = int((now - timedelta(days=ACTIVE_DAYS)).timestamp() * 1000)
    now_ms = int(now.timestamp() * 1000)

    # Pass over the devices: keep only their night summaries and last night's hours.
    per_cell_night: dict[str, dict[date, list[agg.DeviceNight]]] = defaultdict(lambda: defaultdict(list))
    last_hours: dict[str, list[list[agg.DeviceHour]]] = defaultdict(list)
    models: dict[str, int] = defaultdict(int)
    active = 0
    for d in devices:
        if any(active_since <= m.start_ms <= now_ms and m.valid for m in d.minutes):
            active += 1
            models[d.site.device_model or "unbekannt"] += 1
        mins, evs = _split_by_night(d)
        for night in mins:
            if not (first_night <= night <= last_night):
                continue
            s = agg.summarize_device_night(night, mins[night], evs.get(night, []))
            if s is None:
                continue
            per_cell_night[d.site.cell][night].append(s)
            if night == last_night:
                last_hours[d.site.cell].append(agg.summarize_night_hours(night, mins[night], evs.get(night, [])))
    network = {"devices_active_7d": active, "device_models": dict(sorted(models.items()))}

    index = agg.NightIndex(min_devices=min_devices)
    for cell, nights in per_cell_night.items():
        for night, device_nights in nights.items():
            index.add(cell, night, device_nights)

    nights_files = {}
    for i in range(HISTORY_NIGHTS):
        night = last_night - timedelta(days=i)
        nights_files[night.isoformat()] = {
            "generated_at": generated_at,
            "night": night.isoformat(),
            "min_devices_per_cell": min_devices,
            "cells": [e for c in sorted(index.cells) if (e := _cell_entry(index, c, night)) is not None],
            "network": network,
        }
    cells_json = nights_files[last_night.isoformat()]

    # One history file per cell that appears in any of the 90 night maps.
    cell_files = {}
    for cell in sorted({e["cell"] for f in nights_files.values() for e in f["cells"]}):
        history = []
        for i in range(HISTORY_NIGHTS):
            night = last_night - timedelta(days=i)
            if (c := index.get(cell, night)) is not None:
                history.append(
                    {"night": night.isoformat(), "devices": c.devices, "calibrated": c.calibrated, **c.block()}
                )
        has_last = index.get(cell, last_night) is not None
        newest = index.get(cell, date.fromisoformat(history[0]["night"])) if history else None
        cell_files[cell] = {
            "generated_at": generated_at,
            "cell": cell,
            "devices": newest.devices if newest else 0,
            "calibrated": newest.calibrated if newest else False,
            "nights": history,
            "hours_night": last_night.isoformat(),
            "hours": agg.combine_cell_hours(last_hours[cell]) if has_last else [],
        }
    return cells_json, cell_files, nights_files


def write(map_dir: Path, cells_json: dict, cell_files: dict[str, dict], nights_files: dict[str, dict]) -> None:
    map_dir.mkdir(parents=True, exist_ok=True)
    for name, obj in nights_files.items():
        write_json_atomic(map_dir / "nights" / f"{name}.json", obj)
    for cell, obj in cell_files.items():
        write_json_atomic(map_dir / "cells" / f"{cell}.json", obj)
    # cells.json last, so a reader that sees it also finds the files it points to
    write_json_atomic(map_dir / "cells.json", cells_json)
    for sub, keep in (("nights", set(nights_files)), ("cells", set(cell_files))):
        for p in (map_dir / sub).glob("*.json"):
            if p.stem not in keep:
                p.unlink(missing_ok=True)


def publish(conn: sqlite3.Connection, map_dir: Path, now: datetime | None = None, min_devices: int = 1) -> dict:
    """Reads the database, writes every map file; returns cells.json."""
    now = now or datetime.now(UTC)
    last_night = agg.last_complete_night(now)
    first_night = last_night - timedelta(days=HISTORY_NIGHTS + WINDOW_LONG)
    start_ms = agg.night_bounds(first_night)[0]
    end_ms = max(agg.night_bounds(last_night)[1], int(now.timestamp() * 1000) + 1)
    devices = load_devices(conn, start_ms, end_ms)
    cells_json, cell_files, nights_files = build(devices, now, min_devices)
    write(map_dir, cells_json, cell_files, nights_files)
    return cells_json
