"""Expiry (DESIGN.md §2.9, §5): a device without uploads for INACTIVE_DELETE_DAYS is deleted completely."""

from __future__ import annotations

import json
import logging
from datetime import timedelta

import pytest
from test_api import SITE, minute, recent, register
from test_publish import LAST, NOW, add_night

from stadtlaerm_server import __main__ as cli
from stadtlaerm_server import db, publish, retention
from stadtlaerm_server.config import Settings

CELL = "h26824_12473"
TABLES = ("minutes", "events", "hourly", "sites")


def iso(days_ago: float) -> str:
    return (NOW - timedelta(days=days_ago)).isoformat(timespec="seconds")


def add(conn, dev, *, last_seen: str | None, created: str = "2026-01-01T00:00:00+00:00", hidden=False, cell=CELL):
    conn.execute(
        "INSERT INTO devices (id, token_hash, created_at, last_seen_at, hidden) VALUES (?, 'x', ?, ?, ?)",
        (dev, created, last_seen, int(hidden)),
    )
    conn.execute(
        "INSERT INTO sites (device_id, cell, placement, device_model, audio_source, calibrated, updated_at) "
        "VALUES (?, ?, 'open_window', 'Pixel 8', 'UNPROCESSED', 1, '')",
        (dev, cell),
    )
    add_night(conn, dev, LAST, 42.0, 4.0, sum(map(ord, dev)))
    conn.execute(
        "INSERT INTO hourly (device_id, hour_utc, valid_s, valid_minutes, events, calibrated) "
        "VALUES (?, 0, 3600, 60, '{}', 1)",
        (dev,),
    )
    conn.commit()
    return dev


def rows_of(conn, dev) -> dict[str, int]:
    out = {t: conn.execute(f"SELECT COUNT(*) FROM {t} WHERE device_id = ?", (dev,)).fetchone()[0] for t in TABLES}
    out["devices"] = conn.execute("SELECT COUNT(*) FROM devices WHERE id = ?", (dev,)).fetchone()[0]
    return out


def counter(conn, name) -> int:
    row = conn.execute("SELECT value FROM counters WHERE name = ?", (name,)).fetchone()
    return row[0] if row else 0


@pytest.fixture
def conn(tmp_path):
    c = db.connect(tmp_path / "db.sqlite")
    db.migrate(c)
    yield c
    c.close()


def test_61_days_deleted_with_every_row_59_kept(conn):
    old = add(conn, "dev-old", last_seen=iso(61))
    young = add(conn, "dev-young", last_seen=iso(59), cell="h26830_12490")
    assert all(n > 0 for n in rows_of(conn, old).values())
    assert retention.expire_inactive(conn, now=NOW, inactive_days=60) == 1
    assert rows_of(conn, old) == dict.fromkeys((*TABLES, "devices"), 0)
    assert all(n > 0 for n in rows_of(conn, young).values())
    assert counter(conn, "devices_expired") == 1
    assert counter(conn, "devices_deleted") == 0
    assert retention.expire_inactive(conn, now=NOW, inactive_days=60) == 0  # idempotent


def test_never_seen_device_uses_created_at(conn):
    add(conn, "dev-a", last_seen=None, created=iso(61))
    add(conn, "dev-b", last_seen=None, created=iso(59), cell="h26830_12490")
    # last_seen_at wins over created_at when present.
    add(conn, "dev-c", last_seen=iso(1), created=iso(400), cell="h26840_12500")
    assert retention.expire_inactive(conn, now=NOW, inactive_days=60) == 1
    left = {r[0] for r in conn.execute("SELECT id FROM devices")}
    assert left == {"dev-b", "dev-c"}


def test_zero_disables(conn):
    add(conn, "dev-old", last_seen=iso(1000))
    assert retention.expire_inactive(conn, now=NOW, inactive_days=0) == 0
    assert rows_of(conn, "dev-old")["devices"] == 1
    assert counter(conn, "devices_expired") == 0


def test_unparseable_time_is_kept(conn):
    add(conn, "dev-x", last_seen=None, created="")
    assert retention.expire_inactive(conn, now=NOW, inactive_days=60) == 0
    assert rows_of(conn, "dev-x")["devices"] == 1


def test_hidden_device_is_expired(conn):
    add(conn, "dev-hidden", last_seen=iso(61), hidden=True)
    assert retention.expire_inactive(conn, now=NOW, inactive_days=60) == 1
    assert rows_of(conn, "dev-hidden")["devices"] == 0


def test_counters_separate_and_in_stats(conn):
    add(conn, "dev-old", last_seen=iso(61))
    add(conn, "dev-mine", last_seen=iso(1), cell="h26830_12490")
    db.delete_device(conn, "dev-mine")
    retention.expire_inactive(conn, now=NOW, inactive_days=60)
    s = publish.current_stats(conn, NOW)
    assert s["devices_deleted_total"] == 1
    assert s["devices_expired_total"] == 1
    assert s["devices_registered"] == 0


def test_expired_device_leaves_the_map(conn, tmp_path):
    add(conn, "dev-old", last_seen=iso(61))
    out = tmp_path / "map"
    cells = publish.publish(conn, out, now=NOW)
    assert [c["cell"] for c in cells["cells"]] == [CELL]
    assert (out / "cells" / f"{CELL}.json").exists()
    retention.expire_inactive(conn, now=NOW, inactive_days=60)
    cells = publish.publish(conn, out, now=NOW)
    assert cells["cells"] == []
    assert not (out / "cells" / f"{CELL}.json").exists()
    assert all(json.loads(p.read_text())["cells"] == [] for p in (out / "nights").glob("*.json"))
    assert json.loads((out / "stats.json").read_text())["devices_expired_total"] == 1


def test_log_line_has_the_count_only(conn, caplog):
    add(conn, "dev-secret-id", last_seen=iso(61))
    with caplog.at_level(logging.INFO, logger="stadtlaerm"):
        retention.expire_inactive(conn, now=NOW, inactive_days=60)
    lines = [r.getMessage() for r in caplog.records if r.getMessage().startswith("expiry")]
    assert lines == ["expiry: 1 devices deleted after 60 days without uploads"]
    assert "dev-secret-id" not in caplog.text


def test_every_authenticated_request_keeps_the_device(make_client, settings):
    """last_seen_at is set by the API; an upload resets the clock."""
    client = make_client()
    dev, auth = register(client)
    client.put(f"/v1/devices/{dev}/site", json=SITE, headers=auth)
    with db.open_db(settings.db_path) as c:
        c.execute("UPDATE devices SET last_seen_at = ?, created_at = ? WHERE id = ?", (iso(100), iso(100), dev))
        c.commit()
    assert client.post(f"/v1/devices/{dev}/minutes", json=[minute(recent(10))], headers=auth).status_code == 200
    with db.open_db(settings.db_path) as c:
        assert retention.expire_inactive(c, inactive_days=60) == 0
        c.execute("UPDATE devices SET last_seen_at = ? WHERE id = ?", ("2020-01-01T00:00:00+00:00", dev))
        c.commit()
    # The daily job: expires, and the app answers 401 to the old token afterwards.
    assert client.app.state.retention_once() == 1
    r = client.post(f"/v1/devices/{dev}/minutes", json=[minute(recent(5))], headers=auth)
    assert r.status_code == 401


def test_setting_from_env():
    assert Settings.from_env({}).inactive_delete_days == 60
    assert Settings.from_env({"INACTIVE_DELETE_DAYS": "0"}).inactive_delete_days == 0
    assert Settings.from_env({"INACTIVE_DELETE_DAYS": "90"}).inactive_delete_days == 90


def test_cli_retention_expires_and_republishes(conn, tmp_path, monkeypatch, capsys):
    add(conn, "dev-old", last_seen="2020-01-01T00:00:00+00:00")
    out = tmp_path / "map"
    publish.publish(conn, out)
    monkeypatch.setenv("DB_PATH", str(tmp_path / "db.sqlite"))
    monkeypatch.setenv("MAP_DIR", str(out))
    assert cli.main(["retention"]) == 0
    result = json.loads(capsys.readouterr().out)
    assert result["devices_expired"] == 1
    # stats.json was written with 0 before; 1 now shows the command republished.
    assert json.loads((out / "stats.json").read_text())["devices_expired_total"] == 1
