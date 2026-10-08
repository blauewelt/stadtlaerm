"""Deterministic synthetic measurements for tests and the example fixture (never real data).

A Python port of the app's chart test data (android/chart/src/test/.../SyntheticData.kt):
the same minute generator (background L90, L10 = L90 + 3…5.5 dB, event energy added to the
minute it starts in) and the same Friday night 9 → 10 October 2026. The random numbers differ
from Kotlin's, so tests check the arithmetic, not the Kotlin values. Records come out in the
upload format of DESIGN.md §4.3 / §4.4.
"""

from __future__ import annotations

import math
import random
from collections.abc import Callable, Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from .aggregate import ZONE, Event, Minute

TOP_CATEGORY_DURATION = {"aircraft": 25.0, "rail_tram": 9.0}


@dataclass(frozen=True)
class Ev:
    at: datetime  # naive local time (Europe/Zurich)
    lafmax: float
    category: str
    duration_s: float = 3.0


def iso(local: datetime, millis: bool = False) -> str:
    """Naive local time → ISO-8601 with the Zürich offset, as the app writes it."""
    t = local.replace(tzinfo=ZONE)
    return t.isoformat(timespec="milliseconds" if millis else "seconds")


def iso_utc_ms(epoch_ms: int, millis: bool = False) -> str:
    t = datetime.fromtimestamp(epoch_ms / 1000, ZONE)
    return t.isoformat(timespec="milliseconds" if millis else "seconds")


def epoch_ms(local: datetime) -> int:
    return int(local.replace(tzinfo=ZONE).timestamp() * 1000)


def _local_minutes(start: datetime, end: datetime) -> list[int]:
    """UTC epoch ms of every real minute in [start, end) local; DST-aware (a 9 h night has 540)."""
    a, b = epoch_ms(start), epoch_ms(end)
    return list(range(a, b, 60_000))


def minutes(
    start: datetime,
    end: datetime,
    l90: Callable[[datetime], float],
    events: Sequence[Ev] = (),
    gaps: Sequence[tuple[datetime, datetime]] = (),
    seed: int = 1,
    calibrated: bool = False,
    offset_db: float = 112.35,
    spread: tuple[float, float] = (0.0, 2.5),
    low_coverage: Sequence[tuple[datetime, datetime]] = (),
) -> list[dict]:
    """Minute records from `start` to `end` (naive local), skipping `gaps`.

    `spread` is the random part of L10 − L90 on top of 3 dB (the app's 0…2.5 dB); minutes in
    `low_coverage` get 20 s of valid audio (coverage 0.33, left out of every level).
    """
    r = random.Random(seed)
    ev_ms = [(epoch_ms(e.at), e) for e in events]
    gap_ms = [(epoch_ms(a), epoch_ms(b)) for a, b in gaps]
    low_ms = [(epoch_ms(a), epoch_ms(b)) for a, b in low_coverage]
    out = []
    for t in _local_minutes(start, end):
        if any(a <= t < b for a, b in gap_ms):
            continue
        local = datetime.fromtimestamp(t / 1000, ZONE).replace(tzinfo=None)
        bg = l90(local) + r.uniform(-0.8, 0.8)
        l10 = bg + 3.0 + r.uniform(*spread)
        l50 = bg + 1.5
        energy = 10 ** ((bg + 2.2 + r.uniform(0.0, 1.2)) / 10)
        lafmax = l10 + 4 + r.uniform(0.0, 4.0)
        count = 0
        cats = {"road_traffic": 0.0}
        for et, e in ev_ms:
            if t <= et < t + 60_000:
                energy += 10 ** ((e.lafmax + 10 * math.log10(e.duration_s) - 3) / 10) / 60
                lafmax = max(lafmax, e.lafmax)
                count += 1
                cats[e.category] = cats.get(e.category, 0.0) + min(e.duration_s, 60) / 60
        rest = max(0.0, 1.0 - sum(cats.values()))
        cats["road_traffic"] += rest * 0.6
        cats["unclassified"] = cats.get("unclassified", 0.0) + rest * 0.4
        valid = 20.0 if any(a <= t < b for a, b in low_ms) else 60.0
        out.append(
            {
                "start": iso_utc_ms(t),
                "duration_s": 60.0,
                "valid_s": valid,
                "coverage": round(valid / 60, 3),
                "laeq_db": round(10 * math.log10(energy), 1),
                "lafmax_db": round(lafmax, 1),
                "lafmin_db": round(bg - 2, 1),
                "l1_db": round(lafmax - 2, 1),
                "l10_db": round(l10, 1),
                "l50_db": round(l50, 1),
                "l90_db": round(bg, 1),
                "event_count": count,
                "dominant_category": max(cats, key=lambda k: cats[k]),
                "category_shares": {k: round(v, 3) for k, v in cats.items()},
                "calibration_id": "cal-synthetic" if calibrated else None,
                "calibration_offset_db": offset_db,
                "calibrated": calibrated,
                "audio_source": "UNPROCESSED",
                "clock_corrections": 0,
            }
        )
    return out


def event_records(events: Sequence[Ev], l90: Callable[[datetime], float], calibrated: bool = False) -> list[dict]:
    return [
        {
            "start": iso(e.at, millis=True),
            "duration_s": e.duration_s,
            "lafmax_db": round(e.lafmax, 1),
            "sel_db": round(e.lafmax + 10 * math.log10(e.duration_s) - 3, 1),
            "background_db": round(l90(e.at), 1),
            "threshold_db": 10.0,
            "min_level_db": 30.0,
            "category": e.category,
            "category_score": 0.6,
            "calibration_id": "cal-synthetic" if calibrated else None,
            "calibrated": calibrated,
        }
        for e in events
    ]


def to_agg(minute_records: Sequence[dict], event_records_: Sequence[dict] = ()) -> tuple[list[Minute], list[Event]]:
    """Upload-format dicts → the aggregation module's records (what the database returns)."""

    def ms(s: str) -> int:
        return math.floor(datetime.fromisoformat(s).timestamp() * 1000 + 0.5)

    mins = [
        Minute(ms(m["start"]), m["valid_s"], m["coverage"], m["laeq_db"], m["l10_db"], m["l90_db"], m["calibrated"])
        for m in minute_records
    ]
    evs = [Event(ms(e["start"]), e["lafmax_db"], e["category"]) for e in event_records_]
    return mins, evs


# ---- Friday night 9 → 10 October 2026 (the app's fixture) ----------------------------------

FRIDAY = date(2026, 10, 9)


def fri(h: int, m: int, s: int = 0) -> datetime:
    d = FRIDAY if h >= 12 else FRIDAY + timedelta(days=1)
    return datetime(d.year, d.month, d.day, h, m, s)


FRIDAY_EVENTS: list[Ev] = [
    Ev(fri(22, 12), 61.0, "road_traffic"),
    Ev(fri(22, 31), 58.5, "voices", 4.0),
    Ev(fri(22, 47), 64.0, "road_traffic"),
    Ev(fri(23, 5), 78.0, "loud_vehicle", 5.0),
    Ev(fri(23, 18), 59.0, "voices", 6.0),
    Ev(fri(23, 40, 30), 85.0, "loud_vehicle", 7.5),
    Ev(fri(23, 58), 66.0, "road_traffic"),
    Ev(fri(0, 20), 72.0, "loud_vehicle", 4.0),
    Ev(fri(0, 33), 62.5, "voices", 8.0),
    Ev(fri(0, 51), 57.0, "unclassified", 1.5),
    Ev(fri(1, 10), 81.0, "loud_vehicle", 6.0),
    Ev(fri(1, 26), 63.0, "voices", 5.0),
    Ev(fri(1, 50), 76.0, "loud_vehicle", 4.5),
    Ev(fri(2, 40), 60.0, "road_traffic"),
    Ev(fri(4, 15), 62.0, "road_traffic"),
    Ev(fri(4, 52), 65.0, "road_traffic"),
    Ev(fri(5, 20), 63.5, "rail_tram", 9.0),
    # The night window is [22:00, 06:00): this one starts just before 06:00 and counts.
    Ev(fri(5, 59, 20), 67.0, "aircraft", 25.0),
]
FRIDAY_GAP = (fri(3, 10), fri(3, 35))


def friday_l90(t: datetime) -> float:
    """L90 38–42 until ~02:30, then rising to 48 by 05:30."""
    h = (t - datetime(FRIDAY.year, FRIDAY.month, FRIDAY.day, 22)).total_seconds() / 3600
    base = 40.0 + 2.0 * math.sin(h * 1.3)
    if h < 4.5:
        return base
    if h < 7.5:
        return base + (48.0 - base) * (h - 4.5) / 3.0
    return 48.0


def friday_night() -> tuple[list[dict], list[dict]]:
    """Minutes 21:30 → 06:30 (so some fall outside the night) with a 25 min gap at 03:10."""
    mins = minutes(fri(21, 30), fri(6, 30), friday_l90, FRIDAY_EVENTS, gaps=[FRIDAY_GAP], seed=9)
    return mins, event_records(FRIDAY_EVENTS, friday_l90)


# ---- a generic night, for the fixture and multi-night tests -------------------------------


def night_profile(base: float, morning_rise: float = 6.0) -> Callable[[datetime], float]:
    """Background that dips after midnight and rises from 04:30 (morning traffic)."""

    def l90(t: datetime) -> float:
        h = t.hour + t.minute / 60
        x = h - 24 if h >= 12 else h  # hours relative to midnight, −2 … +6
        dip = -2.5 * math.exp(-((x - 2.5) ** 2) / 3)
        rise = morning_rise * max(0.0, (x - 4.5) / 1.5) if x > 4.5 else 0.0
        return base + dip + rise

    return l90


def random_events(
    night: date, seed: int, per_hour: dict[str, float], level: dict[str, tuple[float, float]]
) -> list[Ev]:
    """Poisson-ish events per category through the night 22:00–06:00."""
    r = random.Random(seed)
    out = []
    start = datetime(night.year, night.month, night.day, 22)
    for cat, rate in per_hour.items():
        n = int(rate * 8 + r.random())
        lo, hi = level.get(cat, (55.0, 70.0))
        for _ in range(n):
            at = start + timedelta(seconds=r.randrange(0, 8 * 3600))
            dur = TOP_CATEGORY_DURATION.get(cat, r.uniform(1.5, 7.0))
            out.append(Ev(at, r.uniform(lo, hi), cat, round(dur, 2)))
    return sorted(out, key=lambda e: e.at)
