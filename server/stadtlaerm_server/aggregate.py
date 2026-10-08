"""Night and hour arithmetic for the shared map (DESIGN.md §6). Pure functions, no I/O.

The rules are the app's (android/dsp/.../Nights.kt, NightSummarizer and Dynamics):

* A night is 22:00–06:00 local time in Europe/Zurich, so 8 h, or 7 h / 9 h on DST-change
  nights. A record belongs to the night its *start* falls in.
* A minute is *valid* if its coverage is ≥ 0.5 and it has an LAeq.
* Night LAeq = energy mean of the valid minutes' LAeq, weighted by their valid seconds.
* Events per hour = events of the night / hours of valid measurement (valid seconds of the
  valid minutes). Every event is counted, wherever it starts in the night.
* Dynamics = median over the valid minutes of (L10 − L90); an even count takes the mean of
  the middle two.
* Night L10 / L90 (published, not in the app's night list) = median over the valid minutes.

Several devices in one cell (§6.1): levels are the energy mean of the device values, rates
the arithmetic mean of the device rates, dynamics the median of the device medians, the
loudest event the maximum, and the measured share the mean of the device shares.
"""

from __future__ import annotations

import math
from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import date, datetime, time, timedelta
from zoneinfo import ZoneInfo

ZONE = ZoneInfo("Europe/Zurich")
NIGHT_START_HOUR = 22
NIGHT_END_HOUR = 6
MIN_MINUTE_COVERAGE = 0.5
# An hour of the published night chart needs this many valid minutes for its levels
# (the app's week-view rule); events and the measured share are always given.
MIN_VALID_MINUTES_PER_HOUR = 30
HOUR_MS = 3_600_000

# The app's source categories (android/README.md, «Source categories»). Published blocks
# always carry all of them, so the website can rely on the keys; unknown ids are added.
CATEGORIES: tuple[str, ...] = (
    "loud_vehicle",
    "road_traffic",
    "rail_tram",
    "aircraft",
    "construction",
    "voices",
    "music",
    "unclassified",
)


# ---- input records ------------------------------------------------------------------------


@dataclass(frozen=True)
class Minute:
    start_ms: int  # UTC epoch milliseconds
    valid_s: float
    coverage: float
    laeq_db: float | None
    l10_db: float | None
    l90_db: float | None
    calibrated: bool

    @property
    def valid(self) -> bool:
        return self.coverage >= MIN_MINUTE_COVERAGE and self.laeq_db is not None and self.valid_s > 0


@dataclass(frozen=True)
class Event:
    start_ms: int  # UTC epoch milliseconds
    lafmax_db: float
    category: str


# ---- small maths --------------------------------------------------------------------------


def energy_mean(levels: Sequence[float], weights: Sequence[float] | None = None) -> float | None:
    """10·log10 of the (weighted) mean of 10^(L/10); None for no input or zero weight."""
    if not levels:
        return None
    w = weights if weights is not None else [1.0] * len(levels)
    total = sum(w)
    if total <= 0:
        return None
    return 10 * math.log10(sum(10 ** (lv / 10) * wi for lv, wi in zip(levels, w, strict=True)) / total)


def median(values: Iterable[float]) -> float | None:
    v = sorted(values)
    n = len(v)
    if n == 0:
        return None
    return v[n // 2] if n % 2 else (v[n // 2 - 1] + v[n // 2]) / 2


def mean(values: Iterable[float]) -> float | None:
    v = list(values)
    return sum(v) / len(v) if v else None


def r1(x: float | None) -> float | None:
    return None if x is None else round(x, 1)


def r2(x: float | None) -> float | None:
    return None if x is None else round(x, 2)


# ---- night windows ------------------------------------------------------------------------


def night_of(start_ms: int, zone: ZoneInfo = ZONE) -> date | None:
    """Evening date of the night containing the instant, or None outside 22:00–06:00."""
    t = datetime.fromtimestamp(start_ms / 1000, zone)
    if t.hour >= NIGHT_START_HOUR:
        return t.date()
    if t.hour < NIGHT_END_HOUR:
        return t.date() - timedelta(days=1)
    return None


def night_bounds(night: date, zone: ZoneInfo = ZONE) -> tuple[int, int]:
    """[start, end) of the night in UTC epoch ms; DST-aware via the zone."""
    a = datetime.combine(night, time(NIGHT_START_HOUR), zone)
    b = datetime.combine(night + timedelta(days=1), time(NIGHT_END_HOUR), zone)
    return int(a.timestamp() * 1000), int(b.timestamp() * 1000)


def night_length_s(night: date, zone: ZoneInfo = ZONE) -> float:
    a, b = night_bounds(night, zone)
    return (b - a) / 1000


def last_complete_night(now: datetime, zone: ZoneInfo = ZONE) -> date:
    """The most recent night that has ended by `now` (06:40 on 8 Oct → night of 7 Oct)."""
    local = now.astimezone(zone).replace(tzinfo=None)
    return (local - timedelta(hours=NIGHT_END_HOUR)).date() - timedelta(days=1)


def night_hours(night: date, zone: ZoneInfo = ZONE) -> list[int]:
    """UTC epoch ms of each hour start in the night: 8 entries, 7 or 9 on DST nights."""
    a, b = night_bounds(night, zone)
    return list(range(a, b, HOUR_MS))


# ---- one device -----------------------------------------------------------------------------


def _by_category(events: Sequence[Event], hours: float | None) -> dict[str, float]:
    counts = {c: 0 for c in CATEGORIES}
    for e in events:
        counts[e.category] = counts.get(e.category, 0) + 1
    if hours is None:
        return {k: float(v) for k, v in counts.items()}
    return {k: v / hours for k, v in counts.items()}


@dataclass(frozen=True)
class DeviceNight:
    """One device's figures for one night. Only built when there is ≥ 1 valid minute."""

    laeq_db: float
    l90_db: float | None
    l10_db: float | None
    dynamics_db: float | None
    events_per_h: float
    events_per_h_by_category: dict[str, float]
    loudest_event_db: float | None
    measured_share: float
    valid_s: float
    calibrated: bool
    event_count: int

    def to_dict(self) -> dict:
        return self.__dict__.copy()

    @classmethod
    def from_dict(cls, d: dict) -> DeviceNight:
        return cls(**d)


def summarize_device_night(
    night: date, minutes: Iterable[Minute], events: Iterable[Event], zone: ZoneInfo = ZONE
) -> DeviceNight | None:
    """The app's night summary for one device; records outside the night are ignored."""
    a, b = night_bounds(night, zone)
    ms = [m for m in minutes if a <= m.start_ms < b and m.valid]
    evs = [e for e in events if a <= e.start_ms < b]
    if not ms:
        return None
    valid_s = sum(m.valid_s for m in ms)
    hours = valid_s / 3600
    laeq = energy_mean([m.laeq_db for m in ms], [m.valid_s for m in ms])  # type: ignore[misc]
    assert laeq is not None
    spreads = [m.l10_db - m.l90_db for m in ms if m.l10_db is not None and m.l90_db is not None]
    return DeviceNight(
        laeq_db=laeq,
        l90_db=median(m.l90_db for m in ms if m.l90_db is not None),
        l10_db=median(m.l10_db for m in ms if m.l10_db is not None),
        dynamics_db=median(spreads),
        events_per_h=len(evs) / hours,
        events_per_h_by_category=_by_category(evs, hours),
        loudest_event_db=max((e.lafmax_db for e in evs), default=None),
        measured_share=min(1.0, valid_s / night_length_s(night, zone)),
        valid_s=valid_s,
        calibrated=all(m.calibrated for m in ms),
        event_count=len(evs),
    )


@dataclass(frozen=True)
class DeviceHour:
    """One device's figures for one clock hour (UTC-aligned, which is local-aligned in CH)."""

    hour_ms: int
    valid_s: float
    valid_minutes: int
    laeq_db: float | None
    l90_db: float | None
    l10_db: float | None
    dynamics_db: float | None
    events: dict[str, int]
    loudest_db: float | None
    calibrated: bool


def summarize_hour(hour_ms: int, minutes: Iterable[Minute], events: Iterable[Event]) -> DeviceHour:
    ms = [m for m in minutes if hour_ms <= m.start_ms < hour_ms + HOUR_MS and m.valid]
    evs = [e for e in events if hour_ms <= e.start_ms < hour_ms + HOUR_MS]
    counts = {c: 0 for c in CATEGORIES}
    for e in evs:
        counts[e.category] = counts.get(e.category, 0) + 1
    return DeviceHour(
        hour_ms=hour_ms,
        valid_s=sum(m.valid_s for m in ms),
        valid_minutes=len(ms),
        laeq_db=energy_mean([m.laeq_db for m in ms], [m.valid_s for m in ms]),  # type: ignore[misc]
        l90_db=median(m.l90_db for m in ms if m.l90_db is not None),
        l10_db=median(m.l10_db for m in ms if m.l10_db is not None),
        dynamics_db=median(m.l10_db - m.l90_db for m in ms if m.l10_db is not None and m.l90_db is not None),
        events=counts,
        loudest_db=max((e.lafmax_db for e in evs), default=None),
        calibrated=all(m.calibrated for m in ms),
    )


def summarize_night_hours(
    night: date, minutes: Sequence[Minute], events: Sequence[Event], zone: ZoneInfo = ZONE
) -> list[DeviceHour]:
    return [summarize_hour(h, minutes, events) for h in night_hours(night, zone)]


# ---- several devices in one cell ----------------------------------------------------------


@dataclass(frozen=True)
class CellNight:
    devices: int
    calibrated: bool
    laeq_db: float
    l90_db: float | None
    l10_db: float | None
    dynamics_db: float | None
    events_per_h: float
    events_per_h_by_category: dict[str, float]
    loudest_event_db: float | None
    measured_share: float

    def block(self) -> dict:
        """The published `last_night` block (§6.1), rounded."""
        return {
            "laeq_db": r1(self.laeq_db),
            "l90_db": r1(self.l90_db),
            "l10_db": r1(self.l10_db),
            "dynamics_db": r1(self.dynamics_db),
            "events_per_h": r1(self.events_per_h),
            "events_per_h_by_category": {k: r1(v) for k, v in self.events_per_h_by_category.items()},
            "loudest_event_db": r1(self.loudest_event_db),
            "measured_share": r2(self.measured_share),
        }


def combine_cell_night(nights: Sequence[DeviceNight]) -> CellNight | None:
    if not nights:
        return None
    cats: list[str] = list(CATEGORIES)
    for n in nights:
        cats += [k for k in n.events_per_h_by_category if k not in cats]
    laeq = energy_mean([n.laeq_db for n in nights])
    assert laeq is not None
    l90s = [n.l90_db for n in nights if n.l90_db is not None]
    l10s = [n.l10_db for n in nights if n.l10_db is not None]
    return CellNight(
        devices=len(nights),
        calibrated=all(n.calibrated for n in nights),
        laeq_db=laeq,
        l90_db=energy_mean(l90s),
        l10_db=energy_mean(l10s),
        dynamics_db=median(n.dynamics_db for n in nights if n.dynamics_db is not None),
        events_per_h=sum(n.events_per_h for n in nights) / len(nights),
        events_per_h_by_category={
            c: sum(n.events_per_h_by_category.get(c, 0.0) for n in nights) / len(nights) for c in cats
        },
        loudest_event_db=max((n.loudest_event_db for n in nights if n.loudest_event_db is not None), default=None),
        measured_share=sum(n.measured_share for n in nights) / len(nights),
    )


def window_block(nights: Sequence[CellNight | None]) -> dict:
    """`last_7_nights` / `last_30_nights`: each night with data counts once (§6.1).

    LAeq is the energy mean of the nightly cell LAeqs, dynamics the median of the nightly
    dynamics, events/h the mean of the nightly rates.
    """
    have = [n for n in nights if n is not None]
    return {
        "laeq_db": r1(energy_mean([n.laeq_db for n in have])),
        "dynamics_db": r1(median(n.dynamics_db for n in have if n.dynamics_db is not None)),
        "events_per_h": r1(mean(n.events_per_h for n in have)),
        "nights_with_data": len(have),
    }


def combine_cell_hours(per_device: Sequence[Sequence[DeviceHour]], zone: ZoneInfo = ZONE) -> list[dict]:
    """Hour rows of §6.2 for one cell; `per_device` holds each device's hours of the same night.

    The hour label is the local clock hour ("23"); on the autumn DST night "02" occurs twice,
    so each row also carries `start` (ISO-8601 with offset) to tell them apart.
    """
    if not per_device:
        return []
    rows = []
    for i, hour_ms in enumerate(h.hour_ms for h in per_device[0]):
        hs = [dev[i] for dev in per_device]
        with_levels = [h for h in hs if h.valid_minutes >= MIN_VALID_MINUTES_PER_HOUR and h.laeq_db is not None]
        cats: list[str] = list(CATEGORIES)
        for h in hs:
            cats += [k for k in h.events if k not in cats]
        start = datetime.fromtimestamp(hour_ms / 1000, zone)
        rows.append(
            {
                "hour": start.strftime("%H"),
                "start": start.isoformat(timespec="seconds"),
                "laeq_db": r1(energy_mean([h.laeq_db for h in with_levels])),  # type: ignore[misc]
                "l90_db": r1(energy_mean([h.l90_db for h in with_levels if h.l90_db is not None])),
                "l10_db": r1(energy_mean([h.l10_db for h in with_levels if h.l10_db is not None])),
                "events": {c: r1(sum(h.events.get(c, 0) for h in hs) / len(hs)) for c in cats},
                "loudest_db": r1(max((h.loudest_db for h in hs if h.loudest_db is not None), default=None)),
                "measured_share": r2(sum(min(1.0, h.valid_s / 3600) for h in hs) / len(hs)),
            }
        )
    return rows


@dataclass
class NightIndex:
    """Helper for publish: cell → night → CellNight, honouring the minimum device count."""

    min_devices: int = 1
    cells: dict[str, dict[date, CellNight]] = field(default_factory=dict)

    def add(self, cell: str, night: date, device_nights: Sequence[DeviceNight]) -> None:
        if len(device_nights) < self.min_devices:
            return
        c = combine_cell_night(device_nights)
        if c is not None:
            self.cells.setdefault(cell, {})[night] = c

    def get(self, cell: str, night: date) -> CellNight | None:
        return self.cells.get(cell, {}).get(night)

    def window(self, cell: str, last: date, days: int) -> list[CellNight | None]:
        return [self.get(cell, last - timedelta(days=i)) for i in range(days)]
