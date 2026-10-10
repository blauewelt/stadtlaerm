"""Publishing the map files (§6) from the database, and the retention job (§5)."""

from __future__ import annotations

import json
from datetime import date, datetime, timedelta
from pathlib import Path

import pytest

from stadtlaerm_server import aggregate as agg
from stadtlaerm_server import db, publish, retention
from stadtlaerm_server import synthetic as syn
from stadtlaerm_server.schemas import EventIn, MinuteIn

# Publishing "now" = 06:40 on Sunday 25 October 2026, after the 9-hour DST night.
NOW = datetime.fromisoformat("2026-10-25T06:40:00+01:00")
LAST = date(2026, 10, 24)


def add_device(conn, cell, model="Pixel 8", hidden=False) -> str:
    dev = f"dev-{cell}-{model}-{hidden}".replace(" ", "")
    conn.execute("INSERT INTO devices (id, token_hash, created_at, hidden) VALUES (?, 'x', '', ?)", (dev, int(hidden)))
    conn.execute(
        "INSERT INTO sites (device_id, cell, placement, device_model, audio_source, calibrated, updated_at) "
        "VALUES (?, ?, 'open_window', ?, 'UNPROCESSED', 1, '')",
        (dev, cell, model),
    )
    return dev


def add_night(conn, dev, night: date, base: float, rate: float, seed: int, calibrated=True, start_h=22, hours=8):
    l90 = syn.night_profile(base)
    evs = syn.random_events(night, seed, {"road_traffic": rate, "loud_vehicle": rate / 5, "voices": rate / 4}, {})
    start = datetime(night.year, night.month, night.day, start_h)
    end = datetime(night.year, night.month, night.day, 22) + timedelta(hours=hours)
    mins = syn.minutes(start, end, l90, evs, seed=seed, calibrated=calibrated)
    received = "2026-10-25T05:00:00+00:00"
    db.upsert_rows(conn, "minutes", db.MINUTE_COLUMNS, [db.minute_row(dev, MinuteIn(**m), received) for m in mins])
    ev_rows = [db.event_row(dev, EventIn(**e), received) for e in syn.event_records(evs, l90, calibrated)]
    db.upsert_rows(conn, "events", db.EVENT_COLUMNS, ev_rows)


@pytest.fixture
def conn(tmp_path: Path):
    c = db.connect(tmp_path / "db.sqlite")
    db.migrate(c)
    yield c
    c.close()


def load(p: Path) -> dict:
    return json.loads(p.read_text())


def test_publish_files_and_format(conn, tmp_path):
    a = add_device(conn, "h26824_12473")
    b = add_device(
        conn,
        "h26824_12473",
        model="Galaxy A54",
    )
    c = add_device(conn, "h26830_12490", model="Pixel 7a")
    hidden = add_device(conn, "h26840_12500", hidden=True)
    for i in range(10):
        night = LAST - timedelta(days=i)
        add_night(conn, a, night, 44.0, 6.0, 100 + i)
        add_night(conn, c, night, 38.0, 2.0, 200 + i, calibrated=False)
    add_night(conn, b, LAST, 40.0, 3.0, 300)
    add_night(conn, hidden, LAST, 70.0, 30.0, 400)
    conn.commit()

    out = tmp_path / "map"
    cells = publish.publish(conn, out, now=NOW)
    assert load(out / "cells.json") == cells
    assert cells["night"] == "2026-10-24"
    assert cells["generated_at"] == "2026-10-25T06:40:00+01:00"
    assert cells["min_devices_per_cell"] == 1
    by_cell = {x["cell"]: x for x in cells["cells"]}
    assert set(by_cell) == {"h26824_12473", "h26830_12490"}  # hidden device not shown

    two = by_cell["h26824_12473"]
    assert two["devices"] == 2 and two["calibrated"] is True
    ln = two["last_night"]
    assert set(ln) == {"laeq_db", "l90_db", "l10_db", "dynamics_db", "events_per_h", "events_per_h_by_category",
                       "loudest_event_db", "measured_share"}  # fmt: skip
    assert set(agg.CATEGORIES) <= set(ln["events_per_h_by_category"])
    assert ln["measured_share"] == 1.0  # full 9 h DST night
    assert two["last_7_nights"]["nights_with_data"] == 7
    assert two["last_30_nights"]["nights_with_data"] == 10
    assert set(two["last_7_nights"]) == {"laeq_db", "dynamics_db", "events_per_h", "nights_with_data"}
    assert by_cell["h26830_12490"]["calibrated"] is False

    # The cell value equals the combination of the two device nights computed directly.
    nights = []
    for dev in (a, b):
        s, e = agg.night_bounds(LAST)
        nights.append(
            agg.summarize_device_night(LAST, db.load_minutes(conn, dev, s, e), db.load_events(conn, dev, s, e))
        )
    assert ln == agg.combine_cell_night(nights).block()

    assert cells["network"] == {"devices_active_7d": 3, "device_models": {"Galaxy A54": 1, "Pixel 7a": 1, "Pixel 8": 1}}

    # Per-cell history and hours (9 rows on the DST night).
    cf = load(out / "cells" / "h26824_12473.json")
    assert cf["cell"] == "h26824_12473" and cf["hours_night"] == "2026-10-24"
    assert len(cf["nights"]) == 10 and cf["nights"][0]["night"] == "2026-10-24"
    assert cf["nights"][0]["laeq_db"] == ln["laeq_db"]
    assert [h["hour"] for h in cf["hours"]] == ["22", "23", "00", "01", "02", "02", "03", "04", "05"]
    assert set(cf["hours"][0]) == {
        "hour",
        "start",
        "laeq_db",
        "l90_db",
        "l10_db",
        "events",
        "loudest_db",
        "measured_share",
    }
    assert not (out / "cells" / "h26840_12500.json").exists()

    # Past nights: 90 files; a night before any data shows no cells.
    nf = sorted(p.stem for p in (out / "nights").glob("*.json"))
    assert len(nf) == 90 and nf[-1] == "2026-10-24"
    assert load(out / "nights" / "2026-10-24.json") == cells
    past = load(out / "nights" / "2026-10-20.json")
    assert past["night"] == "2026-10-20"
    assert {x["cell"] for x in past["cells"]} == {"h26824_12473", "h26830_12490"}
    assert next(x for x in past["cells"] if x["cell"] == "h26824_12473")["devices"] == 1
    assert load(out / "nights" / "2026-08-01.json")["cells"] == []
    # No temp files left behind.
    assert not list(out.rglob("*.tmp"))


def test_min_devices_per_cell(conn, tmp_path):
    a = add_device(conn, "h26824_12473")
    b = add_device(conn, "h26824_12473", model="Galaxy A54")
    c = add_device(conn, "h26830_12490")
    for dev, seed in ((a, 1), (b, 2), (c, 3)):
        add_night(conn, dev, LAST, 42.0, 4.0, seed)
    conn.commit()
    cells = publish.publish(conn, tmp_path / "map", now=NOW, min_devices=2)
    assert [x["cell"] for x in cells["cells"]] == ["h26824_12473"]
    assert cells["min_devices_per_cell"] == 2


def test_cell_without_last_night_keeps_window_values(conn, tmp_path):
    a = add_device(conn, "h26824_12473")
    add_night(conn, a, LAST - timedelta(days=3), 42.0, 4.0, 1)
    conn.commit()
    cells = publish.publish(conn, tmp_path / "map", now=NOW)
    (entry,) = cells["cells"]
    assert entry["last_night"] is None
    assert entry["last_7_nights"]["nights_with_data"] == 1
    assert entry["devices"] == 1


def test_deleted_device_disappears_on_next_publish(conn, tmp_path):
    a = add_device(conn, "h26824_12473")
    add_night(conn, a, LAST, 42.0, 4.0, 1)
    conn.commit()
    out = tmp_path / "map"
    publish.publish(conn, out, now=NOW)
    assert (out / "cells" / "h26824_12473.json").exists()
    db.delete_device(conn, a)
    cells = publish.publish(conn, out, now=NOW)
    assert cells["cells"] == [] and cells["network"]["devices_active_7d"] == 0
    assert not (out / "cells" / "h26824_12473.json").exists()
    assert all(load(p)["cells"] == [] for p in (out / "nights").glob("*.json"))


def test_partial_night_measured_share(conn, tmp_path):
    a = add_device(conn, "h26824_12473")
    add_night(conn, a, date(2026, 10, 23), 42.0, 4.0, 1, start_h=23, hours=4)  # 23:00–02:00 of an 8 h night
    conn.commit()
    cells = publish.publish(conn, tmp_path / "map", now=datetime.fromisoformat("2026-10-24T07:00:00+02:00"))
    assert cells["cells"][0]["last_night"]["measured_share"] == pytest.approx(3 / 8, abs=0.01)


def test_retention_reduces_old_rows_to_hours(conn):
    a = add_device(conn, "h26824_12473")
    old_night = date(2024, 9, 1)
    add_night(conn, a, old_night, 42.0, 6.0, 1)
    add_night(conn, a, LAST, 42.0, 6.0, 2)
    conn.commit()
    s, e = agg.night_bounds(old_night)
    expected = agg.summarize_night_hours(old_night, db.load_minutes(conn, a, s, e), db.load_events(conn, a, s, e))
    n_old = conn.execute("SELECT COUNT(*) FROM minutes WHERE start_utc < ?", (e,)).fetchone()[0]
    n_new = conn.execute("SELECT COUNT(*) FROM minutes WHERE start_utc >= ?", (e,)).fetchone()[0]

    result = retention.run_retention(conn, now=NOW, retention_days=730)
    assert result["minutes_deleted"] == n_old == 480
    assert result["hours"] == 8
    assert conn.execute("SELECT COUNT(*) FROM minutes").fetchone()[0] == n_new
    assert conn.execute("SELECT COUNT(*) FROM events WHERE start_utc < ?", (e,)).fetchone()[0] == 0
    hours = conn.execute("SELECT hour_utc, laeq_db, valid_minutes, events FROM hourly ORDER BY hour_utc").fetchall()
    assert [h[0] for h in hours] == [x.hour_ms for x in expected]
    assert [round(h[1], 6) for h in hours] == [round(x.laeq_db, 6) for x in expected]
    assert all(h[2] == 60 for h in hours)
    assert sum(sum(json.loads(h[3]).values()) for h in hours) == sum(sum(x.events.values()) for x in expected)

    # Idempotent: a second run finds nothing old.
    assert retention.run_retention(conn, now=NOW, retention_days=730) == {
        "hours": 0,
        "minutes_deleted": 0,
        "events_deleted": 0,
    }
    assert conn.execute("SELECT COUNT(*) FROM hourly").fetchone()[0] == 8


def test_background_publish_on_startup(make_client, settings):
    """The app publishes once at startup when background jobs are on."""
    import time

    make_client(background_jobs=True, publish_interval_s=3600)
    for _ in range(50):
        if (settings.map_dir / "cells.json").exists():
            break
        time.sleep(0.05)
    cells = load(settings.map_dir / "cells.json")
    assert cells["cells"] == [] and cells["min_devices_per_cell"] == 1


def test_write_json_atomic_replaces(tmp_path):
    p = tmp_path / "x" / "a.json"
    publish.write_json_atomic(p, {"a": 1})
    publish.write_json_atomic(p, {"a": 2})
    assert load(p) == {"a": 2}
    assert [q.name for q in p.parent.iterdir()] == ["a.json"]


def _shape(obj):
    """Keys and value types, recursively; list lengths and device model names ignored."""
    if isinstance(obj, dict):
        return {k: ("{model: int}" if k == "device_models" else _shape(v)) for k, v in obj.items()}
    if isinstance(obj, list):
        return [_shape(obj[0])] if obj else []
    return type(obj).__name__ if obj is not None else None


def test_example_fixture_has_the_published_format(conn, tmp_path):
    """example_cells.json / example_cell.json must look exactly like what publish() writes.

    Regenerate with `python scripts/make_fixture.py` when this fails after a format change.
    """
    a = add_device(conn, "h26824_12473")
    b = add_device(conn, "h26824_12473", model="Galaxy A54")
    for i in range(3):
        add_night(conn, a, LAST - timedelta(days=i), 44.0, 6.0, 10 + i)
        add_night(conn, b, LAST - timedelta(days=i), 40.0, 3.0, 20 + i)
    conn.commit()
    cells = publish.publish(conn, tmp_path / "map", now=NOW)
    server = Path(__file__).resolve().parent.parent
    example = load(server / "example_cells.json")
    full = next(c for c in example["cells"] if c["last_night"] is not None)
    assert _shape({**example, "cells": [full]}) == _shape(cells)
    assert _shape(load(server / "example_cell.json")) == _shape(load(tmp_path / "map" / "cells" / "h26824_12473.json"))
    assert 20 <= len(example["cells"]) <= 30
