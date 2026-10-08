#!/usr/bin/env python3
"""Join MeteoSwiss open station weather with Stadtlärm CSV exports.

Subcommands
-----------
fetch   download 10-minute station data (MeteoSwiss Open Government Data,
        "SwissMetNet" automatic stations) for a date range into a local cache
        and write weather_<station>_<from>_<to>.csv with local and UTC times.
join    attach the nearest 10-minute weather row to every minute of an app
        export (minuten.csv, optionally ereignisse.csv), add the tailwind
        component and write per-hour and per-night summaries.
nights  print the night summaries (22:00-06:00) of a joined file as a table.

Standard library only, Python 3.10+. See README.md next to this file.
Weather data: Source: MeteoSwiss (CC BY 4.0).
"""
from __future__ import annotations

import argparse
import bisect
import csv
import datetime as dt
import json
import math
import os
import re
import statistics
import sys
import tempfile
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Iterable

try:
    from zoneinfo import ZoneInfo, ZoneInfoNotFoundError
except ImportError:  # pragma: no cover - Python < 3.9
    sys.exit("error: Python 3.10+ with zoneinfo is required")

__version__ = "1.0"

BASE_URL = "https://data.geo.admin.ch/ch.meteoschweiz.ogd-smn"
STAC_ITEM_URL = "https://data.geo.admin.ch/api/stac/v1/collections/ch.meteoschweiz.ogd-smn/items/{abbr}"
USER_AGENT = f"stadtlaerm-weather/{__version__} (+https://stadtlaerm.ch)"
UTC = dt.timezone.utc
TEN_MIN = dt.timedelta(minutes=10)
MAX_DOWNLOAD_BYTES = 1 << 30  # refuse anything larger than 1 GiB

# (clean column, MeteoSwiss parameter, unit, meaning) - checked against
# ogd-smn_meta_parameters.csv on 2026-10-08.
PARAMS: list[tuple[str, str, str, str]] = [
    ("temp_c", "tre200s0", "°C", "air temperature 2 m above ground, instantaneous"),
    ("rh_pct", "ure200s0", "%", "relative humidity 2 m above ground, instantaneous"),
    ("dewpoint_c", "tde200s0", "°C", "dew point 2 m above ground, instantaneous"),
    ("wind_ms", "fkl010z0", "m/s", "wind speed (scalar), 10-minute mean"),
    ("wind_vec_ms", "fve010z0", "m/s", "wind speed (vector), 10-minute mean"),
    ("gust_ms", "fkl010z1", "m/s", "gust peak (1 s), maximum of the 10 minutes"),
    ("wind_dir_deg", "dkl010z0", "°", "wind direction (where the wind comes FROM), 10-minute mean"),
    ("rain_mm", "rre150z0", "mm", "precipitation, 10-minute sum"),
    ("pressure_hpa", "prestas0", "hPa", "air pressure at station level (QFE), instantaneous"),
    ("pressure_qff_hpa", "pp0qffs0", "hPa", "air pressure reduced to sea level (QFF), instantaneous"),
    ("sunshine_min", "sre000z0", "min", "sunshine duration, 10-minute sum"),
    ("global_rad_wm2", "gre000z0", "W/m²", "global radiation, 10-minute mean"),
]
WEATHER_COLS = [p[0] for p in PARAMS]
WEATHER_TIME_COLS = ["station", "interval_start_local", "time_local", "time_utc"]

# How long a cached file is trusted before a conditional re-download is tried.
CACHE_MAX_AGE_S = {"now": 600, "recent": 3600, "historical": 7 * 86400}
# Prefer the more quality-controlled file when two files hold the same timestamp.
SOURCE_RANK = {"historical": 3, "recent": 2, "now": 1}


class ToolError(Exception):
    """A user-facing error: printed as 'error: …', exit status 2."""


_warned: set[str] = set()


def warn(msg: str) -> None:
    if msg not in _warned:
        _warned.add(msg)
        print(f"warning: {msg}", file=sys.stderr)


def info(msg: str) -> None:
    print(msg, file=sys.stderr)


# --------------------------------------------------------------------------
# time helpers
# --------------------------------------------------------------------------

def get_tz(name: str) -> dt.tzinfo:
    try:
        return ZoneInfo(name)
    except (ZoneInfoNotFoundError, ValueError) as e:
        raise ToolError(f"unknown time zone {name!r} (install the 'tzdata' package on Windows): {e}")


_MS_TS = re.compile(r"^\s*(\d{2})\.(\d{2})\.(\d{4}) (\d{2}):(\d{2})\s*$")


def parse_meteoswiss_ts(s: str) -> dt.datetime | None:
    """'dd.mm.yyyy HH:MM' (UTC, end of the 10-minute interval) -> aware datetime."""
    m = _MS_TS.match(s)
    if not m:
        return None
    d, mo, y, h, mi = (int(x) for x in m.groups())
    try:
        return dt.datetime(y, mo, d, h, mi, tzinfo=UTC)
    except ValueError:
        return None


_ISO = re.compile(
    r"^\s*(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2})(?::(\d{2})(?:[.,](\d{1,9}))?)?"
    r"\s*(Z|z|[+-]\d{2}(?::?\d{2})?)?\s*$"
)


def parse_iso(s: str, default_tz: dt.tzinfo | None = None) -> dt.datetime | None:
    """Parse ISO-8601 date-time with optional fraction and offset (3.10-safe).

    Without an offset the time is taken as local time in default_tz (a warning
    is printed once); returns None if unparseable.
    """
    m = _ISO.match(s or "")
    if not m:
        return None
    y, mo, d, h, mi, sec, frac, off = m.groups()
    us = int((frac or "0")[:6].ljust(6, "0"))
    if off is None:
        tz = default_tz
        if tz is None:
            return None
        warn("timestamps without UTC offset are read as local time")
    elif off in ("Z", "z"):
        tz = UTC
    else:
        sign = -1 if off[0] == "-" else 1
        digits = off[1:].replace(":", "")
        oh, om = int(digits[:2]), int(digits[2:4] or 0)
        tz = dt.timezone(sign * dt.timedelta(hours=oh, minutes=om))
    try:
        return dt.datetime(int(y), int(mo), int(d), int(h), int(mi), int(sec or 0), us, tzinfo=tz)
    except ValueError:
        return None


def iso_local(t: dt.datetime, tz: dt.tzinfo) -> str:
    return t.astimezone(tz).isoformat(timespec="seconds")


def iso_utc(t: dt.datetime) -> str:
    return t.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


def parse_date(s: str) -> dt.date:
    try:
        return dt.date.fromisoformat(s)
    except ValueError:
        raise ToolError(f"bad date {s!r}, expected YYYY-MM-DD")


def local_day_range_utc(d_from: dt.date, d_to: dt.date, tz: dt.tzinfo) -> tuple[dt.datetime, dt.datetime]:
    """[d_from 00:00 local, d_to+1 00:00 local) as UTC datetimes (d_to inclusive)."""
    start = dt.datetime.combine(d_from, dt.time(0), tzinfo=tz).astimezone(UTC)
    end = dt.datetime.combine(d_to + dt.timedelta(days=1), dt.time(0), tzinfo=tz).astimezone(UTC)
    return start, end


# --------------------------------------------------------------------------
# numbers
# --------------------------------------------------------------------------

def to_float(s: str | None) -> float | None:
    if s is None:
        return None
    s = s.strip()
    if not s:
        return None
    try:
        v = float(s)
    except ValueError:
        return None
    return v if math.isfinite(v) else None


def fmt(v: float | None, nd: int = 1) -> str:
    if v is None:
        return ""
    r = round(v, nd)
    if r == 0:
        r = 0.0  # no "-0.0"
    return f"{r:.{nd}f}"


def energy_mean_db(levels: list[float], weights: list[float] | None = None) -> float | None:
    if not levels:
        return None
    if weights is None:
        weights = [1.0] * len(levels)
    wsum = sum(weights)
    if wsum <= 0:
        return None
    e = sum(w * 10 ** (lv / 10) for lv, w in zip(levels, weights)) / wsum
    return 10 * math.log10(e) if e > 0 else None


def median(xs: list[float]) -> float | None:
    return statistics.median(xs) if xs else None


def mean(xs: list[float]) -> float | None:
    return sum(xs) / len(xs) if xs else None


def circular_median_deg(dirs: list[float]) -> float | None:
    """Sample direction minimising the summed angular distance to all others."""
    if not dirs:
        return None
    def dist(a: float, b: float) -> float:
        x = abs(a - b) % 360
        return min(x, 360 - x)
    best = min(dirs, key=lambda c: (sum(dist(c, d) for d in dirs), c))
    return best % 360


def tailwind(wind_ms: float | None, wind_dir_deg: float | None, bearing_deg: float) -> float | None:
    """Wind component blowing from the road towards the microphone.

    wind_dir_deg is meteorological (direction the wind comes FROM); bearing_deg
    is the compass bearing from the microphone to the road. Wind coming from
    the road's bearing is a full tailwind: wind * cos(dir - bearing).
    """
    if wind_ms is None or wind_dir_deg is None:
        return None
    return wind_ms * math.cos(math.radians(wind_dir_deg - bearing_deg))


# --------------------------------------------------------------------------
# download + cache
# --------------------------------------------------------------------------

_opener: urllib.request.OpenerDirector = urllib.request.build_opener()


def station_abbr(s: str) -> str:
    s = s.strip()
    if not re.fullmatch(r"[A-Za-z0-9]{2,6}", s):
        raise ToolError(f"bad station abbreviation {s!r} (e.g. SMA, REH, KLO)")
    return s.upper()


def needed_files(abbr: str, start_utc: dt.datetime, end_utc: dt.datetime,
                 now_utc: dt.datetime) -> list[tuple[str, str]]:
    """Which station files cover [start_utc, end_utc]? -> [(kind, file name)].

    historical_<decade>: start of measurement .. 31 Dec of last year (yearly update)
    recent:              1 Jan of this year .. yesterday (updated ~01 and ~10 UTC)
    now:                 yesterday 12 UTC .. now (every 10 minutes)
    """
    a = abbr.lower()
    today0 = now_utc.astimezone(UTC).replace(hour=0, minute=0, second=0, microsecond=0)
    jan1 = today0.replace(month=1, day=1)
    out: list[tuple[str, str]] = []
    if start_utc <= jan1:
        last_year = min(end_utc.year, jan1.year - 1)
        decades = sorted({y // 10 * 10 for y in range(start_utc.year, last_year + 1)})
        for d in decades:
            out.append(("historical", f"ogd-smn_{a}_t_historical_{d}-{d + 9}.csv"))
    if end_utc > jan1 and start_utc < today0 + TEN_MIN:
        out.append(("recent", f"ogd-smn_{a}_t_recent.csv"))
    # 'now' starts at yesterday 12 UTC; ask for it generously so the early
    # hours before 'recent' is refreshed are not lost.
    if end_utc > today0 - dt.timedelta(hours=36):
        out.append(("now", f"ogd-smn_{a}_t_now.csv"))
    return out


def file_url(abbr: str, name: str, base_url: str | None = None) -> str:
    return f"{base_url or BASE_URL}/{abbr.lower()}/{name}"


def fetch_cached(url: str, cache_dir: Path, name: str, max_age_s: float,
                 offline: bool = False, timeout: float = 60.0,
                 opener: urllib.request.OpenerDirector | None = None) -> Path:
    """Download url to cache_dir/name with a conditional GET (ETag/Last-Modified).

    A cached copy younger than max_age_s is used without contacting the
    server. If the server cannot be reached, a stale cached copy is used with a
    warning; without one, ToolError is raised.
    """
    opener = opener or _opener
    cache_dir.mkdir(parents=True, exist_ok=True)
    path = cache_dir / name
    meta_path = cache_dir / (name + ".meta.json")
    meta: dict = {}
    if meta_path.exists():
        try:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            if not isinstance(meta, dict):
                meta = {}
        except (OSError, ValueError):
            meta = {}
    have = path.exists() and path.stat().st_size > 0
    if have and offline:
        return path
    if offline:
        raise ToolError(f"--offline: {name} is not in the cache {cache_dir}")
    if have and time.time() - float(meta.get("checked", 0)) < max_age_s:
        return path

    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    if have:
        if isinstance(meta.get("etag"), str):
            req.add_header("If-None-Match", meta["etag"])
        if isinstance(meta.get("last_modified"), str):
            req.add_header("If-Modified-Since", meta["last_modified"])
    try:
        resp = opener.open(req, timeout=timeout)
    except urllib.error.HTTPError as e:
        if e.code == 304 and have:
            meta["checked"] = time.time()
            _write_json(meta_path, meta)
            return path
        if have:
            warn(f"{url}: HTTP {e.code}; using cached copy from "
                 f"{time.strftime('%Y-%m-%d %H:%M', time.localtime(float(meta.get('checked', 0))))}")
            return path
        if e.code in (403, 404):  # the S3 bucket answers 403 for files that do not exist
            raise ToolError(f"not found: {url} (HTTP {e.code}; station or file name wrong? the list of files is at "
                            f"{STAC_ITEM_URL.format(abbr=name.split('_')[1])})")
        raise ToolError(f"download failed: {url}: HTTP {e.code} {e.reason}")
    except (urllib.error.URLError, OSError) as e:
        reason = getattr(e, "reason", e)
        if have:
            warn(f"cannot reach {url} ({reason}); using cached copy")
            return path
        raise ToolError(f"network error while downloading {url}: {reason}")

    with resp:
        length = resp.headers.get("Content-Length")
        if length and length.isdigit() and int(length) > MAX_DOWNLOAD_BYTES:
            raise ToolError(f"{url}: refusing {int(length)} bytes")
        fd, tmp = tempfile.mkstemp(prefix=".part-", dir=cache_dir)
        try:
            total = 0
            with os.fdopen(fd, "wb") as f:
                while True:
                    chunk = resp.read(1 << 16)
                    if not chunk:
                        break
                    total += len(chunk)
                    if total > MAX_DOWNLOAD_BYTES:
                        raise ToolError(f"{url}: download larger than {MAX_DOWNLOAD_BYTES} bytes")
                    f.write(chunk)
            os.replace(tmp, path)
        except (OSError, urllib.error.URLError) as e:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            if have:
                warn(f"download of {url} broke off ({e}); using cached copy")
                return path
            raise ToolError(f"download of {url} broke off: {e}")
        except BaseException:
            try:
                os.unlink(tmp)
            except OSError:
                pass
            raise
        meta = {"url": url, "checked": time.time(),
                "etag": resp.headers.get("ETag"),
                "last_modified": resp.headers.get("Last-Modified"),
                "bytes": total}
        _write_json(meta_path, meta)
    return path


def _write_json(path: Path, obj: dict) -> None:
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(obj, indent=1), encoding="utf-8")
    os.replace(tmp, path)


# --------------------------------------------------------------------------
# MeteoSwiss CSV
# --------------------------------------------------------------------------

def read_meteoswiss_csv(path: Path, start_utc: dt.datetime, end_utc: dt.datetime,
                        source: str = "") -> dict[dt.datetime, dict]:
    """Rows whose 10-minute interval overlaps [start_utc, end_utc).

    Returns {interval_end_utc: {"station": .., "_rank": .., clean_col: float|None}}.
    """
    rows: dict[dt.datetime, dict] = {}
    years = {str(y) for y in range(start_utc.year, end_utc.year + 1)}
    rank = SOURCE_RANK.get(source, 0)
    # The files are Windows-1252; errors="replace" keeps a broken byte from
    # aborting the run (only numbers and timestamps are used).
    with open(path, encoding="cp1252", errors="replace", newline="") as f:
        reader = csv.reader(f, delimiter=";")
        try:
            header = [h.strip() for h in next(reader)]
        except StopIteration:
            warn(f"{path.name}: empty file")
            return rows
        if "reference_timestamp" not in header:
            raise ToolError(f"{path.name}: not a MeteoSwiss data file (no reference_timestamp column)")
        i_ts = header.index("reference_timestamp")
        i_st = header.index("station_abbr") if "station_abbr" in header else None
        idx: dict[str, int | None] = {}
        for clean, param, _u, _d in PARAMS:
            if param in header:
                idx[clean] = header.index(param)
            else:
                idx[clean] = None
                warn(f"{path.name}: column {param} ({clean}) missing, left empty")
        for rec in reader:
            if len(rec) <= i_ts:
                continue
            ts_s = rec[i_ts]
            if ts_s[6:10] not in years:  # cheap pre-filter on the year
                continue
            t = parse_meteoswiss_ts(ts_s)
            if t is None or not (t > start_utc and t - TEN_MIN < end_utc):
                continue
            row = {"station": rec[i_st].strip() if i_st is not None and i_st < len(rec) else "",
                   "_rank": rank}
            for clean, i in idx.items():
                row[clean] = to_float(rec[i]) if i is not None and i < len(rec) else None
            rows[t] = row
    return rows


def merge_sources(parts: Iterable[dict[dt.datetime, dict]]) -> dict[dt.datetime, dict]:
    merged: dict[dt.datetime, dict] = {}
    for part in parts:
        for t, row in part.items():
            old = merged.get(t)
            if old is None or row["_rank"] > old["_rank"]:
                merged[t] = row
    return merged


def write_weather_csv(path: Path, rows: dict[dt.datetime, dict], abbr: str, tz: dt.tzinfo) -> int:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(WEATHER_TIME_COLS + WEATHER_COLS)
        for t in sorted(rows):
            r = rows[t]
            w.writerow([r.get("station") or abbr, iso_local(t - TEN_MIN, tz), iso_local(t, tz), iso_utc(t)]
                       + [_fmt_param(c, r.get(c)) for c in WEATHER_COLS])
    return len(rows)


def _fmt_param(col: str, v: float | None) -> str:
    if v is None:
        return ""
    if col in ("wind_dir_deg", "sunshine_min", "global_rad_wm2"):
        return fmt(v, 0)
    return fmt(v, 1)


# --------------------------------------------------------------------------
# fetch command
# --------------------------------------------------------------------------

def _utcnow() -> dt.datetime:  # patched in tests
    return dt.datetime.now(UTC)


def cmd_fetch(args: argparse.Namespace) -> int:
    tz = get_tz(args.tz)
    abbr = station_abbr(args.station)
    d_from, d_to = parse_date(args.date_from), parse_date(args.date_to)
    if d_to < d_from:
        raise ToolError("--to is before --from")
    start, end = local_day_range_utc(d_from, d_to, tz)
    now = _utcnow()
    if start > now:
        raise ToolError("the requested range lies in the future")
    cache = Path(args.cache)
    files = needed_files(abbr, start, end, now)
    parts = []
    for kind, name in files:
        url = file_url(abbr, name)
        info(f"{kind:10s} {url}")
        p = fetch_cached(url, cache, name, CACHE_MAX_AGE_S[kind], offline=args.offline,
                         timeout=args.timeout)
        parts.append(read_meteoswiss_csv(p, start, end, kind))
    rows = merge_sources(parts)

    expected = int((min(end, now) - start) / TEN_MIN)
    got = len(rows)
    if got == 0:
        raise ToolError(f"no weather rows for {abbr} between {d_from} and {d_to}")
    if got < expected:
        warn(f"{expected - got} of {expected} ten-minute rows are missing in the range "
             f"(gaps in the data, or the range reaches into the future)")
    for c in WEATHER_COLS:
        n_missing = sum(1 for r in rows.values() if r.get(c) is None)
        if 0 < n_missing:
            info(f"  {c}: {n_missing} of {got} values empty")

    out = Path(args.out) if args.out else Path(args.out_dir) / f"weather_{abbr}_{d_from}_{d_to}.csv"
    write_weather_csv(out, rows, abbr, tz)
    info(f"wrote {got} rows to {out}  (Source: MeteoSwiss, CC BY 4.0)")
    return 0


# --------------------------------------------------------------------------
# join command
# --------------------------------------------------------------------------

class WeatherIndex:
    """Nearest-interval lookup over a weather CSV written by `fetch`."""

    def __init__(self, path: Path, tz: dt.tzinfo):
        self.rows: list[dict] = []
        self.centres: list[float] = []
        self.cols: list[str] = []
        try:
            f = open(path, encoding="utf-8-sig", newline="")
        except OSError as e:
            raise ToolError(f"cannot read {path}: {e}")
        with f:
            reader = csv.DictReader(f)
            header = reader.fieldnames or []
            if "time_utc" not in header:
                raise ToolError(f"{path}: no time_utc column - is this a file written by 'fetch'?")
            self.cols = [c for c in WEATHER_COLS if c in header]
            for c in WEATHER_COLS:
                if c not in header:
                    warn(f"{path.name}: weather column {c} missing, left empty")
            items = []
            for r in reader:
                t = parse_iso(r.get("time_utc") or "", UTC)
                if t is None:
                    continue
                row = {c: to_float(r.get(c)) for c in WEATHER_COLS}
                row["wx_time_utc"] = t
                items.append((t, row))
        items.sort(key=lambda x: x[0])
        for t, row in items:
            row["wx_time_local"] = iso_local(t, tz)
            self.rows.append(row)
            self.centres.append((t - TEN_MIN / 2).timestamp())
        if not self.rows:
            raise ToolError(f"{path}: no weather rows")

    def nearest(self, t: dt.datetime, max_gap_s: float) -> dict | None:
        x = t.timestamp()
        i = bisect.bisect_left(self.centres, x)
        best = None
        for j in (i - 1, i):
            if 0 <= j < len(self.centres):
                d = abs(self.centres[j] - x)
                if d <= max_gap_s and (best is None or d < best[0]):
                    best = (d, j)
        return self.rows[best[1]] if best else None


def out_paths(out: Path) -> dict[str, Path]:
    stem = out.with_suffix("") if out.suffix.lower() == ".csv" else out
    return {
        "joined": out,
        "hourly": stem.with_name(stem.name + "_hourly.csv"),
        "nights": stem.with_name(stem.name + "_nights.csv"),
        "events": stem.with_name(stem.name + "_events.csv"),
    }


def _read_app_csv(path: Path) -> tuple[list[str], list[dict]]:
    try:
        f = open(path, encoding="utf-8-sig", newline="")
    except OSError as e:
        raise ToolError(f"cannot read {path}: {e}")
    with f:
        reader = csv.DictReader(f)
        header = list(reader.fieldnames or [])
        rows = list(reader)
    if "start" not in header:
        raise ToolError(f"{path}: no 'start' column - is this an app export (minuten.csv / ereignisse.csv)?")
    return header, rows


def _weather_values(wx: dict | None, bearing: float | None) -> dict[str, str]:
    out: dict[str, str] = {}
    out["wx_time_utc"] = iso_utc(wx["wx_time_utc"]) if wx else ""
    out["wx_time_local"] = wx["wx_time_local"] if wx else ""
    for c in WEATHER_COLS:
        out[c] = _fmt_param(c, wx.get(c)) if wx else ""
    if bearing is not None:
        tw = tailwind(wx.get("wind_ms"), wx.get("wind_dir_deg"), bearing) if wx else None
        out["tailwind_ms"] = fmt(tw, 2)
    return out


def _added_cols(bearing: float | None) -> list[str]:
    return ["wx_time_utc", "wx_time_local"] + WEATHER_COLS + (["tailwind_ms"] if bearing is not None else [])


def join_file(header: list[str], rows: list[dict], index: WeatherIndex, tz: dt.tzinfo,
              bearing: float | None, max_gap_s: float, mid_from_duration: bool) -> tuple[list[str], list[dict], int]:
    added = _added_cols(bearing)
    clash = [c for c in added if c in header]
    if clash:
        warn(f"input already has columns {', '.join(clash)}; they are replaced by the weather values")
    out_header = [c for c in header if c not in added] + added
    out_rows = []
    matched = 0
    for r in rows:
        t = parse_iso(r.get("start") or "", tz)
        wx = None
        if t is not None:
            if mid_from_duration:
                dur = to_float(r.get("duration_s"))
                t = t + dt.timedelta(seconds=(dur if dur is not None and 0 <= dur <= 3600 else 60) / 2)
            wx = index.nearest(t, max_gap_s)
        else:
            warn("rows with unreadable 'start' timestamps are kept without weather")
        if wx is not None:
            matched += 1
        nr = {k: v for k, v in r.items() if k is not None}
        nr.update(_weather_values(wx, bearing))
        out_rows.append(nr)
    return out_header, out_rows, matched


def write_rows(path: Path, header: list[str], rows: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=header, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)


# --------------------------------------------------------------------------
# summaries
# --------------------------------------------------------------------------

HOURLY_COLS = ["hour_local", "n_minutes", "measured_min", "laeq_db", "l90_median_db",
               "l10_l90_median_db", "events", "events_per_h", "events_listed",
               "wind_mean_ms", "gust_max_ms", "dir_median_deg", "tailwind_mean_ms",
               "rain_sum_mm", "temp_mean_c", "n_weather_rows"]
NIGHT_COLS = ["night_of", "n_minutes", "measured_h", "coverage_pct", "laeq_db", "l90_median_db",
              "l10_l90_median_db", "events", "events_per_h", "events_listed",
              "wind_mean_ms", "gust_max_ms", "dir_median_deg", "tailwind_mean_ms",
              "tailwind_max_ms", "rain_sum_mm", "temp_mean_c", "rh_mean_pct", "n_weather_rows"]


class Minute:
    __slots__ = ("t", "valid", "laeq", "dur", "valid_s", "l10", "l90", "events", "wx_key", "wx")

    def __init__(self, r: dict, tz: dt.tzinfo, min_coverage: float):
        self.t = parse_iso(r.get("start") or "", tz)
        self.laeq = to_float(r.get("laeq_db"))
        self.dur = to_float(r.get("duration_s"))
        self.valid_s = to_float(r.get("valid_s"))
        cov = to_float(r.get("coverage"))
        if self.valid_s is None:
            self.valid_s = self.dur
        self.valid = (self.t is not None and self.laeq is not None
                      and (self.valid_s is None or self.valid_s > 0)
                      and (cov is None or cov >= min_coverage))
        self.l10 = to_float(r.get("l10_db"))
        self.l90 = to_float(r.get("l90_db"))
        self.events = to_float(r.get("event_count"))
        self.wx_key = (r.get("wx_time_utc") or "").strip()
        self.wx = {c: to_float(r.get(c)) for c in WEATHER_COLS + ["tailwind_ms"]}


def _summarise(minutes: list[Minute], n_listed: int | None, has_tailwind: bool) -> dict:
    valid = [m for m in minutes if m.valid]
    weights = [m.valid_s if m.valid_s is not None else 60.0 for m in valid]
    measured_s = sum(weights)
    ev_minutes = [m for m in valid if m.events is not None]
    ev_sum = sum(m.events for m in ev_minutes) if ev_minutes else None
    ev_time = sum((m.dur if m.dur is not None else 60.0) for m in ev_minutes)
    # weather: every 10-minute row once, however many minutes point to it
    wx_rows: dict[str, dict] = {}
    for m in minutes:
        if m.wx_key and m.wx_key not in wx_rows:
            wx_rows[m.wx_key] = m.wx
    def col(c: str) -> list[float]:
        return [r[c] for r in wx_rows.values() if r.get(c) is not None]
    gusts = col("gust_ms")
    tws = col("tailwind_ms")
    rain = col("rain_mm")
    return {
        "n_minutes": str(len(valid)),
        "measured_min": fmt(measured_s / 60, 1),
        "measured_h": fmt(measured_s / 3600, 2),
        "laeq_db": fmt(energy_mean_db([m.laeq for m in valid], weights), 1),
        "l90_median_db": fmt(median([m.l90 for m in valid if m.l90 is not None]), 1),
        "l10_l90_median_db": fmt(median([m.l10 - m.l90 for m in valid
                                         if m.l10 is not None and m.l90 is not None]), 1),
        "events": "" if ev_sum is None else str(int(ev_sum)),
        "events_per_h": fmt(ev_sum * 3600 / ev_time, 1) if ev_sum is not None and ev_time > 0 else "",
        "events_listed": "" if n_listed is None else str(n_listed),
        "wind_mean_ms": fmt(mean(col("wind_ms")), 2),
        "gust_max_ms": fmt(max(gusts), 1) if gusts else "",
        "dir_median_deg": fmt(circular_median_deg(col("wind_dir_deg")), 0),
        "tailwind_mean_ms": fmt(mean(tws), 2) if has_tailwind else "",
        "tailwind_max_ms": fmt(max(tws), 2) if has_tailwind and tws else "",
        "rain_sum_mm": fmt(sum(rain), 1) if rain else "",
        "temp_mean_c": fmt(mean(col("temp_c")), 1),
        "rh_mean_pct": fmt(mean(col("rh_pct")), 0),
        "n_weather_rows": str(len(wx_rows)),
    }


def hour_key(t: dt.datetime, tz: dt.tzinfo) -> str:
    # Swiss offsets are whole hours, so the local hour is the UTC hour; going
    # through UTC keeps the two 02:00 hours of the autumn DST change apart.
    h = t.astimezone(UTC).replace(minute=0, second=0, microsecond=0)
    return h.astimezone(tz).isoformat(timespec="minutes")


def night_key(t: dt.datetime, tz: dt.tzinfo, start_h: int = 22, end_h: int = 6) -> str | None:
    lt = t.astimezone(tz)
    if lt.hour >= start_h:
        return lt.date().isoformat()
    if lt.hour < end_h:
        return (lt.date() - dt.timedelta(days=1)).isoformat()
    return None


def summaries(joined_rows: list[dict], tz: dt.tzinfo, min_coverage: float, has_tailwind: bool,
              event_times: list[dt.datetime] | None) -> tuple[list[dict], list[dict]]:
    minutes = [Minute(r, tz, min_coverage) for r in joined_rows]
    minutes = [m for m in minutes if m.t is not None]
    by_hour: dict[str, list[Minute]] = {}
    by_night: dict[str, list[Minute]] = {}
    hour_order: dict[str, float] = {}
    for m in minutes:
        hk = hour_key(m.t, tz)
        by_hour.setdefault(hk, []).append(m)
        hour_order.setdefault(hk, m.t.timestamp())
        nk = night_key(m.t, tz)
        if nk:
            by_night.setdefault(nk, []).append(m)
    ev_hour: dict[str, int] = {}
    ev_night: dict[str, int] = {}
    if event_times is not None:
        for t in event_times:
            ev_hour[hour_key(t, tz)] = ev_hour.get(hour_key(t, tz), 0) + 1
            nk = night_key(t, tz)
            if nk:
                ev_night[nk] = ev_night.get(nk, 0) + 1
    hourly = []
    for hk in sorted(by_hour, key=lambda k: hour_order[k]):
        s = _summarise(by_hour[hk], ev_hour.get(hk, 0) if event_times is not None else None, has_tailwind)
        s["hour_local"] = hk
        hourly.append(s)
    nights = []
    for nk in sorted(by_night):
        s = _summarise(by_night[nk], ev_night.get(nk, 0) if event_times is not None else None, has_tailwind)
        s["night_of"] = nk
        d = dt.date.fromisoformat(nk)
        n_start = dt.datetime.combine(d, dt.time(22), tzinfo=tz).astimezone(UTC)
        n_end = dt.datetime.combine(d + dt.timedelta(days=1), dt.time(6), tzinfo=tz).astimezone(UTC)
        span_h = (n_end - n_start).total_seconds() / 3600  # 8 h, 7 or 9 on DST nights
        mh = to_float(s["measured_h"]) or 0.0
        s["coverage_pct"] = fmt(100 * mh / span_h, 0)
        nights.append(s)
    return hourly, nights


# --------------------------------------------------------------------------
# join / nights commands
# --------------------------------------------------------------------------

def cmd_join(args: argparse.Namespace) -> int:
    tz = get_tz(args.tz)
    index = WeatherIndex(Path(args.weather), tz)
    bearing = args.highway_bearing
    if bearing is not None and not (-360 <= bearing <= 720):
        raise ToolError("--highway-bearing must be a compass bearing in degrees (0-360)")
    max_gap_s = args.max_gap_min * 60
    paths = out_paths(Path(args.out))

    header, rows = _read_app_csv(Path(args.minutes))
    for c in ("laeq_db", "l10_db", "l90_db", "event_count", "duration_s"):
        if c not in header:
            warn(f"{Path(args.minutes).name}: column {c} missing; the matching summary columns stay empty")
    jh, jrows, matched = join_file(header, rows, index, tz, bearing, max_gap_s, mid_from_duration=True)
    write_rows(paths["joined"], jh, jrows)
    info(f"wrote {paths['joined']}: {len(jrows)} minutes, {matched} with weather")
    if matched < len(jrows):
        warn(f"{len(jrows) - matched} minutes have no weather row within {args.max_gap_min:g} min "
             f"(fetch a wider date range?)")

    event_times = None
    if args.events:
        eh, erows = _read_app_csv(Path(args.events))
        ejh, ejrows, ematched = join_file(eh, erows, index, tz, bearing, max_gap_s, mid_from_duration=False)
        write_rows(paths["events"], ejh, ejrows)
        info(f"wrote {paths['events']}: {len(ejrows)} events, {ematched} with weather")
        event_times = [t for t in (parse_iso(r.get("start") or "", tz) for r in erows) if t is not None]

    hourly, nights = summaries(jrows, tz, args.min_coverage, bearing is not None, event_times)
    hcols = [c for c in HOURLY_COLS if bearing is not None or c != "tailwind_mean_ms"]
    ncols = [c for c in NIGHT_COLS if bearing is not None or not c.startswith("tailwind")]
    if event_times is None:
        hcols.remove("events_listed")
        ncols.remove("events_listed")
    write_rows(paths["hourly"], hcols, hourly)
    write_rows(paths["nights"], ncols, nights)
    info(f"wrote {paths['hourly']} ({len(hourly)} hours) and {paths['nights']} ({len(nights)} nights)")
    return 0


PRINT_NIGHT = [("night_of", "night", 10), ("n_minutes", "min", 4), ("coverage_pct", "cov%", 4),
               ("laeq_db", "LAeq", 5), ("l90_median_db", "L90", 5), ("l10_l90_median_db", "L10-90", 6),
               ("events_per_h", "ev/h", 5), ("wind_mean_ms", "wind", 5), ("gust_max_ms", "gust", 5),
               ("dir_median_deg", "dir", 4), ("tailwind_mean_ms", "tail", 5), ("rain_sum_mm", "rain", 5),
               ("temp_mean_c", "temp", 5)]
PRINT_HOUR = [("hour_local", "hour", 5)] + PRINT_NIGHT[1:2] + PRINT_NIGHT[3:]


def format_table(rows: list[dict], spec: list[tuple[str, str, int]], hour_short: bool = False) -> str:
    spec = [s for s in spec if any(r.get(s[0]) not in (None, "") for r in rows) or s[0] in ("night_of", "hour_local")]
    lines = ["  ".join(f"{h:>{w}}" for _k, h, w in spec)]
    for r in rows:
        cells = []
        for k, _h, w in spec:
            v = r.get(k, "") or "-"
            if k == "hour_local" and hour_short and len(v) >= 16:
                v = v[11:16]
            cells.append(f"{v:>{w}}")
        lines.append("  ".join(cells))
    return "\n".join(lines)


def cmd_nights(args: argparse.Namespace) -> int:
    tz = get_tz(args.tz)
    header, rows = _read_app_csv(Path(args.joined))
    if "wx_time_utc" not in header:
        warn("the file has no weather columns - run 'join' first; showing levels only")
    has_tw = "tailwind_ms" in header
    event_times = None
    if args.events:
        _eh, erows = _read_app_csv(Path(args.events))
        event_times = [t for t in (parse_iso(r.get("start") or "", tz) for r in erows) if t is not None]
    hourly, nights = summaries(rows, tz, args.min_coverage, has_tw, event_times)
    if not nights:
        print("no minutes between 22:00 and 06:00 in this file")
        return 0
    print(format_table(nights, PRINT_NIGHT))
    if args.hourly:
        for n in nights:
            night_hours = [h for h in hourly
                           if night_key(parse_iso(h["hour_local"], tz), tz) == n["night_of"]]
            print(f"\nnight of {n['night_of']}")
            print(format_table(night_hours, PRINT_HOUR, hour_short=True))
    print("\nlevels in dB(A) as exported by the app; wind/gust/tail in m/s, dir in degrees "
          "(from), rain in mm. Weather: Source: MeteoSwiss")
    return 0


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------

def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="stadtlaerm_weather.py",
        description="Join MeteoSwiss station weather (Source: MeteoSwiss, CC BY 4.0) with Stadtlärm CSV exports.")
    p.add_argument("--version", action="version", version=__version__)
    sub = p.add_subparsers(dest="cmd", required=True)

    def common(sp: argparse.ArgumentParser) -> None:
        sp.add_argument("--tz", default="Europe/Zurich", help="local time zone (default Europe/Zurich)")

    f = sub.add_parser("fetch", help="download station weather for a date range")
    f.add_argument("--station", default="SMA", help="station abbreviation (default SMA = Zürich/Fluntern)")
    f.add_argument("--from", dest="date_from", required=True, help="first local date, YYYY-MM-DD")
    f.add_argument("--to", dest="date_to", required=True, help="last local date (inclusive), YYYY-MM-DD")
    f.add_argument("--cache", default="meteoswiss-cache", help="cache directory for the downloaded files")
    f.add_argument("--out-dir", default=".", help="where weather_<station>_<from>_<to>.csv goes")
    f.add_argument("--out", help="explicit output file (overrides --out-dir)")
    f.add_argument("--offline", action="store_true", help="use only the cache, never the network")
    f.add_argument("--timeout", type=float, default=60.0, help="network timeout in seconds")
    common(f)
    f.set_defaults(func=cmd_fetch)

    j = sub.add_parser("join", help="attach weather to every minute of an app export")
    j.add_argument("--weather", required=True, help="CSV written by 'fetch'")
    j.add_argument("--minutes", required=True, help="the app's minute export (minuten.csv)")
    j.add_argument("--events", help="the app's event export (ereignisse.csv), optional")
    j.add_argument("--out", required=True, help="joined minute file; summaries go next to it")
    j.add_argument("--highway-bearing", type=float, default=None,
                   help="compass bearing (deg) from the microphone to the road; adds tailwind_ms")
    j.add_argument("--max-gap-min", type=float, default=10.0,
                   help="largest distance to the centre of a weather interval (default 10 min)")
    j.add_argument("--min-coverage", type=float, default=0.5,
                   help="minutes with a lower 'coverage' are left out of the level statistics")
    common(j)
    j.set_defaults(func=cmd_join)

    n = sub.add_parser("nights", help="print night summaries (22:00-06:00) of a joined file")
    n.add_argument("--joined", required=True, help="file written by 'join'")
    n.add_argument("--events", help="optional ereignisse.csv to count listed events")
    n.add_argument("--hourly", action="store_true", help="also print the hours of each night")
    n.add_argument("--min-coverage", type=float, default=0.5)
    common(n)
    n.set_defaults(func=cmd_nights)
    return p


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return args.func(args)
    except ToolError as e:
        print(f"error: {e}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
