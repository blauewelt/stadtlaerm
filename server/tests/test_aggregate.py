"""The night arithmetic of DESIGN.md §6.1 on synthetic nights (mirrors the app's chart fixtures)."""

from __future__ import annotations

import math
from datetime import UTC, date, datetime, timedelta

import pytest

from stadtlaerm_server import aggregate as agg
from stadtlaerm_server import synthetic as syn
from stadtlaerm_server.aggregate import Event, Minute


def energy(levels, weights=None):
    w = weights or [1.0] * len(levels)
    return 10 * math.log10(sum(10 ** (lv / 10) * x for lv, x in zip(levels, w, strict=True)) / sum(w))


# ---- night windows ----------------------------------------------------------------------


def test_night_lengths_follow_dst():
    assert agg.night_length_s(date(2026, 10, 7)) == 8 * 3600
    # Autumn change: 03:00 CEST → 02:00 CET on Sunday 25 October 2026, so the night that starts
    # on the evening of Saturday 24 October lasts 9 hours.
    assert agg.night_length_s(date(2026, 10, 24)) == 9 * 3600
    assert len(agg.night_hours(date(2026, 10, 24))) == 9
    # Spring change: 29 March 2026, the night of 28 March has 7 hours.
    assert agg.night_length_s(date(2026, 3, 28)) == 7 * 3600


def test_night_of_assigns_by_local_start():
    def ms(s):
        return int(datetime.fromisoformat(s).timestamp() * 1000)

    assert agg.night_of(ms("2026-10-07T21:59:59+02:00")) is None
    assert agg.night_of(ms("2026-10-07T22:00:00+02:00")) == date(2026, 10, 7)
    assert agg.night_of(ms("2026-10-08T05:59:59+02:00")) == date(2026, 10, 7)
    assert agg.night_of(ms("2026-10-08T06:00:00+02:00")) is None
    # Both 02:30s of the autumn night belong to the night of 24 October.
    assert agg.night_of(ms("2026-10-25T02:30:00+02:00")) == date(2026, 10, 24)
    assert agg.night_of(ms("2026-10-25T02:30:00+01:00")) == date(2026, 10, 24)


def test_last_complete_night():
    assert agg.last_complete_night(datetime.fromisoformat("2026-10-08T06:40:00+02:00")) == date(2026, 10, 7)
    assert agg.last_complete_night(datetime.fromisoformat("2026-10-08T05:59:00+02:00")) == date(2026, 10, 6)
    assert agg.last_complete_night(datetime(2026, 10, 8, 4, 0, tzinfo=UTC)) == date(2026, 10, 7)  # 06:00 local


def test_median_even_and_odd():
    assert agg.median([3.0, 1.0, 2.0]) == 2.0
    assert agg.median([4.0, 1.0, 3.0, 2.0]) == 2.5
    assert agg.median([]) is None


# ---- one device, the app's Friday night ---------------------------------------------------


def test_friday_night_matches_the_app_rules():
    mins_rec, evs_rec = syn.friday_night()
    mins, evs = syn.to_agg(mins_rec, evs_rec)
    n = agg.summarize_device_night(syn.FRIDAY, mins, evs)
    assert n is not None

    a, b = agg.night_bounds(syn.FRIDAY)
    inside = [m for m in mins_rec if a <= syn.to_agg([m])[0][0].start_ms < b]
    assert len(inside) == 480 - 25  # 8 h minus the 03:10–03:35 gap
    assert n.valid_s == pytest.approx(455 * 60)
    assert n.measured_share == pytest.approx(455 / 480)
    assert n.laeq_db == pytest.approx(energy([m["laeq_db"] for m in inside]))
    spreads = sorted(m["l10_db"] - m["l90_db"] for m in inside)
    assert n.dynamics_db == pytest.approx((spreads[226] + spreads[227]) / 2 if len(spreads) % 2 == 0 else spreads[227])
    # All 18 events start inside [22:00, 06:00), including the aircraft at 05:59:20.
    assert n.event_count == 18
    assert n.events_per_h == pytest.approx(18 / (455 / 60))
    assert n.events_per_h_by_category["loud_vehicle"] == pytest.approx(5 / (455 / 60))
    assert n.events_per_h_by_category["music"] == 0
    assert n.loudest_event_db == 85.0
    assert n.calibrated is False
    # L10 is above L90 and the dynamics are the app's 3–5.5 dB.
    assert n.l10_db > n.l90_db
    assert 3.0 <= n.dynamics_db <= 5.5


def test_low_coverage_minutes_are_left_out():
    t0 = int(datetime.fromisoformat("2026-10-07T23:00:00+02:00").timestamp() * 1000)
    mins = [
        Minute(t0, 60.0, 1.0, 40.0, 43.0, 38.0, True),
        Minute(t0 + 60_000, 30.0, 0.5, 50.0, 60.0, 40.0, True),  # exactly 0.5: kept
        Minute(t0 + 120_000, 29.0, 0.49, 90.0, 95.0, 30.0, True),  # below: dropped
        Minute(t0 + 180_000, 60.0, 1.0, None, None, None, True),  # no level: dropped
    ]
    evs = [Event(t0 + 5000, 70.0, "loud_vehicle"), Event(t0 + 125_000, 80.0, "road_traffic")]
    n = agg.summarize_device_night(date(2026, 10, 7), mins, evs)
    assert n.valid_s == 90.0
    assert n.laeq_db == pytest.approx(energy([40.0, 50.0], [60.0, 30.0]))
    assert n.dynamics_db == pytest.approx((5.0 + 20.0) / 2)
    # Events are per hour of *valid* time, and every event of the night counts.
    assert n.events_per_h == pytest.approx(2 / (90 / 3600))
    assert n.loudest_event_db == 80.0


def test_no_valid_minutes_gives_no_night():
    t0 = int(datetime.fromisoformat("2026-10-07T23:00:00+02:00").timestamp() * 1000)
    assert agg.summarize_device_night(date(2026, 10, 7), [Minute(t0, 10.0, 0.17, 40.0, 41.0, 39.0, True)], []) is None
    assert agg.summarize_device_night(date(2026, 10, 7), [], [Event(t0, 70.0, "voices")]) is None


def test_uncalibrated_minute_makes_the_night_uncalibrated():
    t0 = int(datetime.fromisoformat("2026-10-07T23:00:00+02:00").timestamp() * 1000)
    mins = [Minute(t0, 60, 1, 40, 42, 38, True), Minute(t0 + 60_000, 60, 1, 40, 42, 38, False)]
    assert agg.summarize_device_night(date(2026, 10, 7), mins, []).calibrated is False


# ---- the DST night ------------------------------------------------------------------------


def test_autumn_dst_night_has_nine_hours():
    night = date(2026, 10, 24)
    l90 = syn.night_profile(42.0)
    evs_in = syn.random_events(night, 5, {"road_traffic": 3.0, "loud_vehicle": 0.5}, {})
    mins_rec = syn.minutes(datetime(2026, 10, 24, 21, 0), datetime(2026, 10, 25, 7, 0), l90, evs_in, seed=4)
    mins, evs = syn.to_agg(mins_rec, syn.event_records(evs_in, l90))
    a, b = agg.night_bounds(night)
    inside = [m for m in mins if a <= m.start_ms < b]
    assert len(inside) == 540

    n = agg.summarize_device_night(night, mins, evs)
    assert n.valid_s == 540 * 60
    assert n.measured_share == 1.0  # a 9 h night fully measured is 100 %, not 112 %
    assert n.events_per_h == pytest.approx(len([e for e in evs if a <= e.start_ms < b]) / 9)

    hours = agg.combine_cell_hours([agg.summarize_night_hours(night, mins, evs)])
    assert [h["hour"] for h in hours] == ["22", "23", "00", "01", "02", "02", "03", "04", "05"]
    assert hours[4]["start"].endswith("+02:00") and hours[5]["start"].endswith("+01:00")
    assert all(h["measured_share"] == 1.0 for h in hours)


def test_spring_dst_night_has_seven_hours():
    night = date(2026, 3, 28)
    mins_rec = syn.minutes(datetime(2026, 3, 28, 22, 0), datetime(2026, 3, 29, 6, 0), syn.night_profile(40.0), seed=2)
    mins, _ = syn.to_agg(mins_rec)
    assert len(mins) == 420
    n = agg.summarize_device_night(night, mins, [])
    assert n.measured_share == 1.0
    assert [h["hour"] for h in agg.combine_cell_hours([agg.summarize_night_hours(night, mins, [])])] == [
        "22", "23", "00", "01", "03", "04", "05",
    ]  # fmt: skip


# ---- several devices in a cell --------------------------------------------------------------


def _device(night, base, rate, seed, calibrated=True, spread=(0.0, 2.5), gaps=()):
    l90 = syn.night_profile(base)
    evs_in = syn.random_events(night, seed, {"road_traffic": rate, "loud_vehicle": rate / 4}, {})
    start = datetime(night.year, night.month, night.day, 22)
    end = start + timedelta(hours=8)
    mins_rec = syn.minutes(start, end, l90, evs_in, gaps=gaps, seed=seed, calibrated=calibrated, spread=spread)
    mins, evs = syn.to_agg(mins_rec, syn.event_records(evs_in, l90, calibrated))
    return agg.summarize_device_night(night, mins, evs)


def test_two_device_cell_combination_rules():
    night = date(2026, 10, 7)
    a = _device(night, 44.0, 6.0, 11)
    b = _device(night, 36.0, 2.0, 12, calibrated=False, spread=(4.0, 8.0),
                gaps=[(datetime(2026, 10, 8, 1), datetime(2026, 10, 8, 3))])  # fmt: skip
    c = agg.combine_cell_night([a, b])
    assert c.devices == 2
    assert c.calibrated is False
    assert c.laeq_db == pytest.approx(energy([a.laeq_db, b.laeq_db]))  # energy mean of levels
    assert c.laeq_db > (a.laeq_db + b.laeq_db) / 2  # louder than the arithmetic mean
    assert c.events_per_h == pytest.approx((a.events_per_h + b.events_per_h) / 2)  # mean of rates
    assert c.dynamics_db == pytest.approx((a.dynamics_db + b.dynamics_db) / 2)  # median of two medians
    assert c.loudest_event_db == max(a.loudest_event_db, b.loudest_event_db)
    assert c.measured_share == pytest.approx((1.0 + 6 / 8) / 2)
    for k in agg.CATEGORIES:
        assert c.events_per_h_by_category[k] == pytest.approx(
            (a.events_per_h_by_category[k] + b.events_per_h_by_category[k]) / 2
        )


def test_three_device_dynamics_is_median_not_mean():
    base = agg.DeviceNight(45, 40, 48, 0.0, 1, {}, None, 1, 3600, True, 1)
    nights = [base.__class__(**{**base.to_dict(), "dynamics_db": d}) for d in (2.0, 3.0, 20.0)]
    assert agg.combine_cell_night(nights).dynamics_db == 3.0


def test_window_block_counts_nights_with_data():
    c = agg.combine_cell_night([agg.DeviceNight(50, 40, 52, 6.0, 4.0, {}, 70, 1, 3600, True, 4)])
    d = agg.combine_cell_night([agg.DeviceNight(40, 35, 44, 8.0, 2.0, {}, 65, 1, 3600, True, 2)])
    w = agg.window_block([c, None, d, None, None, None, None])
    assert w["nights_with_data"] == 2
    assert w["laeq_db"] == pytest.approx(round(energy([50, 40]), 1))
    assert w["events_per_h"] == 3.0
    assert w["dynamics_db"] == 7.0
    assert agg.window_block([None] * 7) == {
        "laeq_db": None,
        "dynamics_db": None,
        "events_per_h": None,
        "nights_with_data": 0,
    }
