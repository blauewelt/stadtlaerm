"""Generate `example_cells.json` (and `example_cell.json`) from synthetic minute data.

The data is invented, but it goes through the real code path: synthetic minute/event
records in the upload format → schema validation → SQLite → publish.publish(). So the
fixture has exactly the format the server emits.

    python scripts/make_fixture.py            # writes server/example_cells.json, example_cell.json
    python scripts/make_fixture.py --map-dir /tmp/map   # also keep the full map tree there
"""

from __future__ import annotations

import argparse
import json
import math
import random
import sys
import tempfile
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from pathlib import Path

SERVER = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SERVER))

from stadtlaerm_server import db, publish  # noqa: E402
from stadtlaerm_server import synthetic as syn  # noqa: E402
from stadtlaerm_server.schemas import EventIn, MinuteIn  # noqa: E402

NOW = datetime.fromisoformat("2026-10-08T06:40:00+02:00")
LAST_NIGHT = date(2026, 10, 7)
NIGHTS = 30


def wgs84_to_cell(lat: float, lon: float) -> str:
    """swisstopo's approximate WGS84 → LV95 formulas (≈ 1 m), then the hectare id (DESIGN §3)."""
    phi = (lat * 3600 - 169028.66) / 10000
    lam = (lon * 3600 - 26782.5) / 10000
    e = 2600072.37 + 211455.93 * lam - 10938.51 * lam * phi - 0.36 * lam * phi**2 - 44.54 * lam**3
    n = (
        1200147.07 + 308807.95 * phi + 3745.25 * lam**2 + 76.63 * phi**2
        - 194.56 * lam**2 * phi + 119.79 * phi**3
    )  # fmt: skip
    return f"h{math.floor(e / 100)}_{math.floor(n / 100)}"


@dataclass(frozen=True)
class Profile:
    """Night character of a street: background, burstiness and event rates per hour."""

    base: float  # L90 around midnight, dB(A)
    spread: tuple[float, float]  # random part of L10 − L90 on top of 3 dB
    rates: dict[str, float]  # events per hour on a weekday night
    weekend: float = 1.0  # factor on rates (and +1.5 dB background) on Fri/Sat nights
    levels: dict[str, tuple[float, float]] | None = None


SEESTRASSE = Profile(45.0, (1.0, 4.0), {"road_traffic": 6.0, "loud_vehicle": 1.6, "voices": 0.5}, 1.8,
                     {"loud_vehicle": (72, 88), "road_traffic": (60, 72)})  # fmt: skip
BEDERSTRASSE = Profile(42.0, (1.0, 4.5), {"road_traffic": 4.5, "loud_vehicle": 0.9, "rail_tram": 0.8}, 1.4,
                       {"loud_vehicle": (70, 84), "rail_tram": (62, 70)})  # fmt: skip
LANGSTRASSE = Profile(47.0, (2.0, 6.0), {"voices": 5.0, "music": 1.2, "road_traffic": 3.5, "loud_vehicle": 1.2}, 2.2,
                      {"voices": (58, 74), "music": (58, 70), "loud_vehicle": (72, 86)})  # fmt: skip
KREIS7 = Profile(33.0, (3.0, 9.0), {"road_traffic": 0.8, "voices": 0.3, "unclassified": 0.3, "loud_vehicle": 0.1}, 1.1,
                 {"road_traffic": (48, 60), "loud_vehicle": (62, 74)})  # fmt: skip
HIGHWAY = Profile(55.0, (0.5, 2.0), {"road_traffic": 9.0, "loud_vehicle": 1.0, "aircraft": 0.1}, 1.2,
                  {"road_traffic": (64, 74), "loud_vehicle": (72, 82), "aircraft": (60, 66)})  # fmt: skip


@dataclass(frozen=True)
class Site:
    name: str
    lat: float
    lon: float
    profile: Profile
    devices: int = 1
    offset_db: float = 0.0  # this hectare is a bit louder/quieter than its street's profile
    models: tuple[str, ...] = ("Pixel 8",)
    uncalibrated: bool = False
    nights: int = NIGHTS  # how many of the last 30 nights it measured
    skip_last: bool = False  # no data last night (shows `last_night: null`)
    partial_last: bool = False  # only 23:00–03:00 last night


SITES = [
    # Kreis 2: Seestrasse (Poser route along the lake) and Bederstrasse
    Site("Seestrasse / Mutschellenstrasse", 47.3553, 8.5355, SEESTRASSE, 2, 1.0, ("Pixel 8", "Galaxy A54")),
    Site("Seestrasse / Kilchbergstrasse", 47.3497, 8.5381, SEESTRASSE, 1, 0.0, ("Pixel 7a",)),
    Site("Seestrasse / Bellariastrasse", 47.3595, 8.5345, SEESTRASSE, 1, 1.5, ("Pixel 8",)),
    Site("Seestrasse, Wollishofen", 47.3455, 8.5390, SEESTRASSE, 1, -1.0, ("Pixel 6a",), uncalibrated=True),
    Site("Bederstrasse / Brunaustrasse", 47.3580, 8.5300, BEDERSTRASSE, 1, 0.5, ("Pixel 8",)),
    Site("Bederstrasse / Breitingerstrasse", 47.3610, 8.5315, BEDERSTRASSE, 2, 0.0, ("Pixel 8 Pro", "Pixel 7a")),
    Site("Bederstrasse / Lessingstrasse", 47.3552, 8.5272, BEDERSTRASSE, 1, -0.5, ("Galaxy S23",), nights=12),
    Site("Hofstrasse, Kreis 2 (courtyard)", 47.3568, 8.5320, KREIS7, 1, 3.0, ("Pixel 8",)),
    # Kreis 4: Langstrasse
    Site("Langstrasse / Dienerstrasse", 47.3781, 8.5268, LANGSTRASSE, 2, 1.0, ("Pixel 8", "Fairphone 5")),
    Site("Langstrasse / Brauerstrasse", 47.3767, 8.5254, LANGSTRASSE, 1, 0.0, ("Pixel 7a",)),
    Site("Langstrasse / Militärstrasse", 47.3796, 8.5282, LANGSTRASSE, 1, 2.0, ("Galaxy A54",)),
    Site("Langstrasse / Hohlstrasse", 47.3808, 8.5265, LANGSTRASSE, 1, -0.5, ("Pixel 8",), partial_last=True),
    Site("Brauerstrasse, Kreis 4", 47.3760, 8.5225, LANGSTRASSE, 1, -4.0, ("Pixel 6a",), uncalibrated=True),
    Site("Helvetiaplatz", 47.3757, 8.5277, LANGSTRASSE, 1, -2.0, ("Pixel 8",), nights=20),
    Site("Kanzleistrasse, Kreis 4", 47.3742, 8.5262, LANGSTRASSE, 1, -5.0, ("Pixel 7a",), skip_last=True, nights=9),
    # Kreis 7: quiet residential streets
    Site("Kapfstrasse, Hirslanden", 47.3606, 8.5722, KREIS7, 1, 0.0, ("Pixel 8",)),
    Site("Klosbachstrasse, Hottingen", 47.3706, 8.5603, KREIS7, 1, 2.0, ("Pixel 7a",)),
    Site("Freiestrasse, Hottingen", 47.3683, 8.5572, KREIS7, 2, 1.0, ("Pixel 8", "Galaxy S23")),
    Site("Hofackerstrasse, Hirslanden", 47.3634, 8.5677, KREIS7, 1, -1.0, ("Pixel 8",)),
    Site("Witikonerstrasse / Eierbrecht", 47.3608, 8.5850, KREIS7, 1, 4.0, ("Pixel 6a",)),
    Site("Toblerstrasse, Fluntern", 47.3765, 8.5600, KREIS7, 1, -2.0, ("Pixel 8",), uncalibrated=True, nights=18),
    # A3 Sihlhochstrasse ending at Brunau
    Site("Sihlhochstrasse / Brunau", 47.3505, 8.5232, HIGHWAY, 2, 0.0, ("Pixel 8", "Pixel 7a")),
    Site("Allmend Brunau", 47.3478, 8.5212, HIGHWAY, 1, -3.0, ("Galaxy A54",)),
    Site("Sihlhochstrasse, Giesshübel", 47.3540, 8.5248, HIGHWAY, 1, 1.0, ("Pixel 8",)),
    Site("Brunaustrasse near A3", 47.3528, 8.5270, HIGHWAY, 1, -6.0, ("Pixel 8 Pro",)),
]


def night_records(site: Site, device: int, night: date, seed: int) -> tuple[list[dict], list[dict]]:
    p = site.profile
    weekend = night.weekday() in (4, 5)  # Fri, Sat evenings
    factor = p.weekend if weekend else 1.0
    base = p.base + site.offset_db + (1.5 if weekend and p.weekend > 1.3 else 0.0) + device * 0.8
    l90 = syn.night_profile(base, morning_rise=4.0 if p is not HIGHWAY else 2.0)
    rates = {k: v * factor for k, v in p.rates.items()}
    levels = {k: (lo + site.offset_db / 2, hi + site.offset_db / 2) for k, (lo, hi) in (p.levels or {}).items()}
    events = syn.random_events(night, seed, rates, levels)
    start = datetime(night.year, night.month, night.day, 22)
    end = start + timedelta(hours=8)
    r = random.Random(seed)
    gaps = []
    if r.random() < 0.25:  # a phone call or a battery break now and then
        g = start + timedelta(minutes=r.randrange(0, 420))
        gaps.append((g, g + timedelta(minutes=r.randrange(10, 60))))
    if site.partial_last and night == LAST_NIGHT:
        gaps = [(start, start + timedelta(hours=1)), (start + timedelta(hours=5), end)]
    events = [e for e in events if not any(a <= e.at < b for a, b in gaps)]
    calibrated = not site.uncalibrated
    mins = syn.minutes(start, end, l90, events, gaps=gaps, seed=seed, calibrated=calibrated,
                       offset_db=112.35 if site.uncalibrated else 118.0 + device, spread=p.spread)  # fmt: skip
    return mins, syn.event_records(events, l90, calibrated)


def build_db(path: Path) -> None:
    conn = db.connect(path)
    db.migrate(conn)
    received = "2026-10-08T04:00:00+00:00"
    for si, site in enumerate(SITES):
        cell = wgs84_to_cell(site.lat, site.lon)
        for d in range(site.devices):
            dev = f"fixture-{si:02d}-{d}"
            conn.execute("INSERT INTO devices (id, token_hash, created_at) VALUES (?, 'fixture', ?)", (dev, received))
            conn.execute(
                "INSERT INTO sites (device_id, cell, placement, floor, street_facing, device_model, audio_source, "
                "calibrated, updated_at) VALUES (?, ?, 'open_window', 2, 1, ?, 'UNPROCESSED', ?, ?)",
                (dev, cell, site.models[d % len(site.models)], int(not site.uncalibrated), received),
            )
            for i in range(site.nights):
                night = LAST_NIGHT - timedelta(days=i)
                if i == 0 and site.skip_last:
                    continue
                if i > 0 and random.Random(si * 1000 + d * 100 + i).random() < 0.12:
                    continue  # nights without measurement
                mins, evs = night_records(site, d, night, seed=si * 10_000 + d * 1000 + i)
                # MinuteIn's "7 days old" rule is an API check, not a schema rule: the
                # fixture bypasses the API but not the schema, so the data is still validated.
                db.upsert_rows(
                    conn, "minutes", db.MINUTE_COLUMNS, [db.minute_row(dev, MinuteIn(**m), received) for m in mins]
                )
                db.upsert_rows(
                    conn, "events", db.EVENT_COLUMNS, [db.event_row(dev, EventIn(**e), received) for e in evs]
                )
        conn.commit()
    conn.close()


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--map-dir", type=Path, help="also keep the full published tree here")
    args = ap.parse_args()
    cells = {wgs84_to_cell(s.lat, s.lon) for s in SITES}
    assert len(cells) == len(SITES), "two fixture sites fall into the same hectare"

    with tempfile.TemporaryDirectory() as tmp:
        dbp = Path(tmp) / "fixture.sqlite"
        build_db(dbp)
        out = args.map_dir or Path(tmp) / "map"
        with db.open_db(dbp) as conn:
            result = publish.publish(conn, out, now=NOW, min_devices=1)
        example = wgs84_to_cell(SITES[0].lat, SITES[0].lon)
        # Same content as the published files, indented so the fixture is readable in a diff.
        for src, dst in (
            (out / "cells.json", "example_cells.json"),
            (out / "cells" / f"{example}.json", "example_cell.json"),
        ):
            obj = json.loads(src.read_text(encoding="utf-8"))
            (SERVER / dst).write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"{len(result['cells'])} cells, night {result['night']} → example_cells.json; {example} → example_cell.json")


if __name__ == "__main__":
    main()
