"""stats.json (DESIGN.md §6.4): project-wide usage counts, from synthetic data."""

from __future__ import annotations

import json
from datetime import date, datetime, timedelta
from pathlib import Path

import pytest
from test_api import SITE, minute, recent, register
from test_publish import LAST, NOW, add_night

from stadtlaerm_server import __main__ as cli
from stadtlaerm_server import aggregate as agg
from stadtlaerm_server import db, publish, retention, stats
from stadtlaerm_server.schemas import MinuteIn

KEYS = {
    "generated_at", "devices_registered", "devices_with_site", "devices_ever_shared",
    "devices_active_7d", "devices_active_30d", "devices_deleted_total", "cells_with_data_30d",
    "nights_shared_total", "app_versions", "registrations_by_week",
}  # fmt: skip


def add(conn, dev, created_at="", version=None, cell=None, hidden=False):
    conn.execute(
        "INSERT INTO devices (id, token_hash, app_version, created_at, hidden) VALUES (?, 'x', ?, ?, ?)",
        (dev, version, created_at, int(hidden)),
    )
    if cell:
        conn.execute(
            "INSERT INTO sites (device_id, cell, placement, device_model, audio_source, calibrated, updated_at) "
            "VALUES (?, ?, 'open_window', 'Pixel 8', 'UNPROCESSED', 1, '')",
            (dev, cell),
        )
    return dev


@pytest.fixture
def conn(tmp_path: Path):
    c = db.connect(tmp_path / "db.sqlite")
    db.migrate(c)
    yield c
    c.close()


@pytest.fixture
def network(conn):
    """Six devices; NOW = 2026-10-25 06:40 (Sunday of ISO week 2026-W43)."""
    a = add(conn, "dev-a", "2026-10-19T10:00:00+00:00", "0.5.0", "h26824_12473")
    # 23:30 UTC on Sunday 18 Oct is 01:30 on Monday 19 Oct in Zürich → week 43, not 42.
    b = add(conn, "dev-b", "2026-10-18T23:30:00+00:00", "0.5.0", "h26830_12490")
    hidden = add(conn, "dev-hidden", "2026-08-03T12:00:00+00:00", "x; drop", "h26840_12500", hidden=True)
    nosite = add(conn, "dev-nosite", "", None)
    add(conn, "dev-idle", "2026-10-01T08:00:00+00:00", "0.5.0")  # registered, never sent anything
    old = add(conn, "dev-old", "2024-08-01T08:00:00+00:00", "0.4.0", "h26850_12510")
    for i in range(10):
        add_night(conn, a, LAST - timedelta(days=i), 44.0, 6.0, 100 + i)
    add_night(conn, b, LAST, 40.0, 3.0, 300)
    add_night(conn, hidden, LAST, 70.0, 30.0, 400)
    add_night(conn, nosite, LAST - timedelta(days=20), 40.0, 3.0, 500, start_h=23, hours=2)
    add_night(conn, old, date(2024, 9, 1), 42.0, 6.0, 600)
    conn.commit()
    return conn


EXPECTED = {
    "generated_at": "2026-10-25T06:40:00+01:00",
    "devices_registered": 6,
    "devices_with_site": 4,  # a, b, hidden, old
    "devices_ever_shared": 5,  # all but dev-idle
    "devices_active_7d": 3,  # a, b, hidden (hidden counts here, not on the map)
    "devices_active_30d": 4,  # + nosite (20 nights ago)
    "devices_deleted_total": 0,
    "cells_with_data_30d": 2,  # a's and b's hectares; hidden not shown, old too old
    "nights_shared_total": 14,  # a 10, b 1, hidden 1, nosite 1, old 1
    "app_versions": {"0.5.0": 2, "andere": 1, "unbekannt": 1},
    "registrations_by_week": [
        {"week": f"2026-W{w}", "devices": {32: 1, 40: 1, 43: 2}.get(w, 0)} for w in range(32, 44)
    ],
}


def test_every_field(network):
    s = publish.current_stats(network, NOW)
    assert set(s) == KEYS
    assert s == EXPECTED


def test_written_by_publish_and_contains_no_ids(network, tmp_path):
    out = tmp_path / "map"
    cells = publish.publish(network, out, now=NOW)
    written = json.loads((out / "stats.json").read_text())
    assert written == EXPECTED
    assert len(cells["cells"]) == written["cells_with_data_30d"]
    text = (out / "stats.json").read_text()
    for forbidden in ("dev-", "h268", "Pixel", "drop"):
        assert forbidden not in text
    # Republishing leaves stats.json in place (only stale cells/nights files are removed).
    publish.publish(network, out, now=NOW)
    assert (out / "stats.json").exists()


def test_after_delete(network):
    db.delete_device(network, "dev-b")
    db.delete_device(network, "dev-b")  # already gone: not counted twice
    s = publish.current_stats(network, NOW)
    assert s["devices_deleted_total"] == 1
    assert s["devices_registered"] == 5
    assert s["devices_with_site"] == 3
    assert s["devices_ever_shared"] == 4
    assert s["devices_active_7d"] == 2 and s["devices_active_30d"] == 3
    assert s["cells_with_data_30d"] == 1
    assert s["nights_shared_total"] == 13
    assert s["app_versions"] == {"0.5.0": 1, "andere": 1, "unbekannt": 1}
    assert s["registrations_by_week"][-1] == {"week": "2026-W43", "devices": 1}


def test_after_retention(network):
    before = publish.current_stats(network, NOW)
    result = retention.run_retention(network, now=NOW, retention_days=730)
    assert result["minutes_deleted"] == 480  # dev-old's night of 2024-09-01 is now hourly only
    assert network.execute("SELECT COUNT(*) FROM minutes WHERE device_id = 'dev-old'").fetchone()[0] == 0
    after = publish.current_stats(network, NOW)
    assert after == before
    assert after["devices_ever_shared"] == 5 and after["nights_shared_total"] == 14


def test_valid_minute_rule_matches_the_map(conn):
    """VALID_MINUTE_SQL = aggregate.Minute.valid; active_7d = the map's figure for shown devices."""
    good = add(conn, "dev-good", "", "0.5.0", "h26824_12473")
    low = add(conn, "dev-low", "", "0.5.0", "h26830_12490")
    t = datetime(2026, 10, 24, 23, 0, tzinfo=agg.ZONE)  # in the night of 24 October
    rec = minute(t)
    received = "2026-10-25T05:00:00+00:00"
    db.upsert_rows(conn, "minutes", db.MINUTE_COLUMNS, [db.minute_row(good, MinuteIn(**rec), received)])
    for i, (cov, laeq) in enumerate(((0.4, 42.0), (0.9, None))):
        bad = MinuteIn(**{**rec, "start": (t + timedelta(minutes=i + 1)).isoformat(), "coverage": cov, "laeq_db": laeq})
        db.upsert_rows(conn, "minutes", db.MINUTE_COLUMNS, [db.minute_row(low, bad, received)])
    conn.commit()
    s = publish.current_stats(conn, NOW)
    cells = publish.build_from_db(conn, NOW)[0]
    assert s["devices_active_7d"] == cells["network"]["devices_active_7d"] == 1
    assert s["devices_ever_shared"] == 2  # an invalid minute is still a shared minute
    assert s["nights_shared_total"] == 1  # only the valid minute makes a shared night
    for m in db.load_minutes(conn, low, 0, 2**62):
        assert not m.valid


def test_version_key():
    assert stats._version_key("0.5.0") == "0.5.0"
    assert stats._version_key("0.5.0-labor") == "0.5.0-labor"
    assert stats._version_key("0.4") == "0.4"
    assert stats._version_key("hello world") == "andere"
    assert stats._version_key("0.5.0-" + "x" * 20) == "andere"
    assert stats._version_key(None) == "unbekannt"


def test_empty_database(conn):
    s = publish.current_stats(conn, NOW)
    assert s["devices_registered"] == 0 and s["app_versions"] == {} and s["nights_shared_total"] == 0
    assert len(s["registrations_by_week"]) == 12 and all(w["devices"] == 0 for w in s["registrations_by_week"])


def test_api_delete_counts_and_stats_is_served(make_client, settings):
    client = make_client()
    dev, auth = register(client)
    other, other_auth = register(client)
    for d, a in ((dev, auth), (other, other_auth)):
        client.put(f"/v1/devices/{d}/site", json=SITE, headers=a)
        client.post(f"/v1/devices/{d}/minutes", json=[minute(recent(10))], headers=a)
    assert client.delete(f"/v1/devices/{dev}", headers=auth).status_code == 204
    assert client.delete(f"/v1/devices/{dev}", headers=auth).status_code == 401  # gone; not counted again
    client.app.state.publish_once()
    r = client.get("/v1/map/stats.json")
    assert r.status_code == 200
    assert r.headers["cache-control"] == "max-age=300"
    assert r.headers["access-control-allow-origin"] == settings.cors_origin
    s = r.json()
    assert set(s) == KEYS
    assert s["devices_registered"] == 1 and s["devices_deleted_total"] == 1
    assert s["devices_ever_shared"] == 1 and s["devices_active_7d"] == 1
    assert s["app_versions"] == {"0.4.0": 1}
    assert s["registrations_by_week"][-1]["devices"] == 1


def test_cli_on_the_network(network, tmp_path, monkeypatch, capsys):
    monkeypatch.setenv("DB_PATH", str(tmp_path / "db.sqlite"))  # the `network` database
    monkeypatch.setenv("MAP_DIR", str(tmp_path / "map"))
    monkeypatch.setattr(publish, "datetime", _FrozenDatetime)
    assert cli.main(["stats"]) == 0
    assert json.loads(capsys.readouterr().out) == EXPECTED


class _FrozenDatetime(datetime):
    @classmethod
    def now(cls, tz=None):
        return NOW if tz is None else NOW.astimezone(tz)


def test_script_fixture_is_what_the_server_writes(network):
    """scripts/tests/fixtures/stats.json (used by scripts/usage_report.py's tests) = this network."""
    fixture = Path(__file__).resolve().parents[2] / "scripts" / "tests" / "fixtures" / "stats.json"
    assert json.loads(fixture.read_text(encoding="utf-8")) == publish.current_stats(network, NOW)
