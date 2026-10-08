"""Tests for stadtlaerm_weather.py (standard library unittest, no network).

Run from the repository root:
    python3 -I -m unittest discover -s tools/weather/tests -v
"""
from __future__ import annotations

import contextlib
import csv
import datetime as dt
import http.server
import io
import math
import shutil
import sys
import tempfile
import threading
import unittest
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
FIX = HERE / "fixtures"
sys.path.insert(0, str(HERE.parent))
import stadtlaerm_weather as sw  # noqa: E402

UTC = dt.timezone.utc
TZ = sw.get_tz("Europe/Zurich")
NO_PROXY = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def read_csv(path: Path) -> list[dict]:
    with open(path, encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def run_main(argv: list[str]) -> tuple[int, str, str]:
    out, err = io.StringIO(), io.StringIO()
    sw._warned.clear()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = sw.main(argv)
    return code, out.getvalue(), err.getvalue()


class TimeParsing(unittest.TestCase):
    def test_meteoswiss_timestamp_is_utc(self):
        t = sw.parse_meteoswiss_ts("07.10.2026 20:10")
        self.assertEqual(t, dt.datetime(2026, 10, 7, 20, 10, tzinfo=UTC))
        self.assertIsNone(sw.parse_meteoswiss_ts("2026-10-07 20:10"))
        self.assertIsNone(sw.parse_meteoswiss_ts("31.02.2026 20:10"))

    def test_iso_variants(self):
        a = sw.parse_iso("2026-10-06T18:54:38.403+02:00")
        self.assertEqual(a.astimezone(UTC), dt.datetime(2026, 10, 6, 16, 54, 38, 403000, tzinfo=UTC))
        self.assertEqual(sw.parse_iso("2026-10-08T03:00:40Z"), dt.datetime(2026, 10, 8, 3, 0, 40, tzinfo=UTC))
        self.assertEqual(sw.parse_iso("2026-10-08T03:00+0100").utcoffset(), dt.timedelta(hours=1))
        self.assertIsNone(sw.parse_iso("yesterday"))
        self.assertIsNone(sw.parse_iso("2026-10-08T03:00:00"))  # no zone, no default
        sw._warned.clear()
        with contextlib.redirect_stderr(io.StringIO()):
            naive = sw.parse_iso("2026-10-08T03:00:00", TZ)
        self.assertEqual(naive.utcoffset(), dt.timedelta(hours=2))

    def test_local_time_is_dst_aware(self):
        summer = dt.datetime(2026, 10, 7, 20, 10, tzinfo=UTC)
        winter = dt.datetime(2026, 11, 7, 20, 10, tzinfo=UTC)
        self.assertEqual(sw.iso_local(summer, TZ), "2026-10-07T22:10:00+02:00")
        self.assertEqual(sw.iso_local(winter, TZ), "2026-11-07T21:10:00+01:00")
        # 25 Oct 2026: 02:10 local happens twice
        self.assertEqual(sw.iso_local(dt.datetime(2026, 10, 25, 0, 10, tzinfo=UTC), TZ), "2026-10-25T02:10:00+02:00")
        self.assertEqual(sw.iso_local(dt.datetime(2026, 10, 25, 1, 10, tzinfo=UTC), TZ), "2026-10-25T02:10:00+01:00")
        self.assertNotEqual(sw.hour_key(dt.datetime(2026, 10, 25, 0, 10, tzinfo=UTC), TZ),
                            sw.hour_key(dt.datetime(2026, 10, 25, 1, 10, tzinfo=UTC), TZ))

    def test_local_day_range(self):
        s, e = sw.local_day_range_utc(dt.date(2026, 10, 6), dt.date(2026, 10, 9), TZ)
        self.assertEqual(s, dt.datetime(2026, 10, 5, 22, 0, tzinfo=UTC))
        self.assertEqual(e, dt.datetime(2026, 10, 9, 22, 0, tzinfo=UTC))

    def test_night_key(self):
        k = lambda s: sw.night_key(sw.parse_iso(s), TZ)
        self.assertEqual(k("2026-10-07T22:00:00+02:00"), "2026-10-07")
        self.assertEqual(k("2026-10-08T05:59:59+02:00"), "2026-10-07")
        self.assertIsNone(k("2026-10-08T06:00:00+02:00"))
        self.assertIsNone(k("2026-10-07T21:59:00+02:00"))


class Maths(unittest.TestCase):
    def test_tailwind_convention(self):
        # road to the west (bearing 270): a west wind (from 270) blows road -> microphone
        self.assertAlmostEqual(sw.tailwind(2.0, 270, 270), 2.0)
        self.assertAlmostEqual(sw.tailwind(2.0, 90, 270), -2.0)
        self.assertAlmostEqual(sw.tailwind(2.0, 0, 270), 0.0, places=9)
        self.assertAlmostEqual(sw.tailwind(2.0, 300, 270), 2.0 * math.cos(math.radians(30)))
        self.assertAlmostEqual(sw.tailwind(1.0, 5, 355), math.cos(math.radians(10)))  # across north
        self.assertIsNone(sw.tailwind(None, 270, 270))
        self.assertIsNone(sw.tailwind(1.0, None, 270))

    def test_energy_mean(self):
        self.assertAlmostEqual(sw.energy_mean_db([40.0, 50.0]), 10 * math.log10((1e4 + 1e5) / 2))
        self.assertAlmostEqual(sw.energy_mean_db([40.0, 50.0], [3, 0]), 40.0)
        self.assertIsNone(sw.energy_mean_db([]))

    def test_circular_median(self):
        self.assertEqual(sw.circular_median_deg([350, 355, 10]), 355)
        self.assertEqual(sw.circular_median_deg([85, 355, 5]), 5)
        self.assertEqual(sw.circular_median_deg([100, 120, 140]), 120)
        self.assertIsNone(sw.circular_median_deg([]))

    def test_numbers(self):
        self.assertIsNone(sw.to_float(""))
        self.assertIsNone(sw.to_float("nan"))
        self.assertIsNone(sw.to_float("inf"))
        self.assertIsNone(sw.to_float("abc"))
        self.assertEqual(sw.to_float(" 1.5 "), 1.5)
        self.assertEqual(sw.fmt(-0.001, 2), "0.00")


class FileSelection(unittest.TestCase):
    NOW = dt.datetime(2026, 10, 8, 12, 0, tzinfo=UTC)

    def kinds(self, d_from, d_to):
        s, e = sw.local_day_range_utc(d_from, d_to, TZ)
        return [n for _k, n in sw.needed_files("SMA", s, e, self.NOW)]

    def test_this_week(self):
        self.assertEqual(self.kinds(dt.date(2026, 10, 6), dt.date(2026, 10, 9)),
                         ["ogd-smn_sma_t_recent.csv", "ogd-smn_sma_t_now.csv"])

    def test_today_only(self):
        self.assertIn("ogd-smn_sma_t_now.csv", self.kinds(dt.date(2026, 10, 8), dt.date(2026, 10, 8)))

    def test_older_this_year(self):
        self.assertEqual(self.kinds(dt.date(2026, 3, 1), dt.date(2026, 3, 2)), ["ogd-smn_sma_t_recent.csv"])

    def test_across_new_year(self):
        self.assertEqual(self.kinds(dt.date(2025, 12, 30), dt.date(2026, 1, 2)),
                         ["ogd-smn_sma_t_historical_2020-2029.csv", "ogd-smn_sma_t_recent.csv"])

    def test_across_decade(self):
        self.assertEqual(self.kinds(dt.date(2019, 12, 31), dt.date(2020, 1, 1)),
                         ["ogd-smn_sma_t_historical_2010-2019.csv", "ogd-smn_sma_t_historical_2020-2029.csv"])

    def test_station_validation(self):
        self.assertEqual(sw.station_abbr("sma"), "SMA")
        with self.assertRaises(sw.ToolError):
            sw.station_abbr("../x")


class MeteoSwissCsv(unittest.TestCase):
    def test_read_and_map_columns(self):
        s = dt.datetime(2026, 10, 7, 19, 55, tzinfo=UTC)
        e = dt.datetime(2026, 10, 7, 21, 0, tzinfo=UTC)
        rows = sw.read_meteoswiss_csv(FIX / "ogd-smn_tst_t_recent.csv", s, e, "recent")
        # interval ending 20:00 overlaps [19:55, ...); 21:00 interval starts at 20:50 < 21:00
        self.assertEqual(len(rows), 7)
        r = rows[dt.datetime(2026, 10, 7, 20, 20, tzinfo=UTC)]
        self.assertEqual((r["wind_ms"], r["gust_ms"], r["wind_dir_deg"], r["rain_mm"]), (3.0, 7.0, 355.0, 0.2))
        self.assertEqual((r["temp_c"], r["pressure_hpa"], r["pressure_qff_hpa"]), (14.8, 940.1, 1010.1))
        gap = rows[dt.datetime(2026, 10, 7, 20, 40, tzinfo=UTC)]
        self.assertIsNone(gap["wind_ms"])
        self.assertEqual(gap["pressure_hpa"], 940.2)

    def test_missing_column_warns_not_crashes(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        p = tmp / "x.csv"
        p.write_text("station_abbr;reference_timestamp;tre200s0\r\nTST;07.10.2026 20:10;12.5\r\n", encoding="cp1252")
        err = io.StringIO()
        sw._warned.clear()
        with contextlib.redirect_stderr(err):
            rows = sw.read_meteoswiss_csv(p, dt.datetime(2026, 10, 7, tzinfo=UTC),
                                          dt.datetime(2026, 10, 8, tzinfo=UTC))
        self.assertIn("fkl010z0", err.getvalue())
        (r,) = rows.values()
        self.assertEqual(r["temp_c"], 12.5)
        self.assertIsNone(r["wind_ms"])

    def test_not_a_data_file(self):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp)
        p = tmp / "x.csv"
        p.write_text("<html>oops</html>\n", encoding="utf-8")
        with self.assertRaises(sw.ToolError):
            sw.read_meteoswiss_csv(p, dt.datetime(2026, 10, 7, tzinfo=UTC), dt.datetime(2026, 10, 8, tzinfo=UTC))

    def test_merge_prefers_more_final_source(self):
        t = dt.datetime(2026, 10, 7, 20, 10, tzinfo=UTC)
        now = {t: {"_rank": sw.SOURCE_RANK["now"], "temp_c": 1.0}}
        recent = {t: {"_rank": sw.SOURCE_RANK["recent"], "temp_c": 2.0}}
        self.assertEqual(sw.merge_sources([now, recent])[t]["temp_c"], 2.0)
        self.assertEqual(sw.merge_sources([recent, now])[t]["temp_c"], 2.0)


class _Handler(http.server.BaseHTTPRequestHandler):
    body = b""
    etag = '"v1"'
    hits: list[str] = []

    def do_GET(self):  # noqa: N802
        type(self).hits.append(self.path)
        if self.path.endswith("missing.csv"):
            self.send_response(404)
            self.end_headers()
            return
        if self.headers.get("If-None-Match") == self.etag:
            self.send_response(304)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("ETag", self.etag)
        self.send_header("Content-Length", str(len(self.body)))
        self.end_headers()
        self.wfile.write(self.body)

    def log_message(self, *a):
        pass


class Downloads(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _Handler.body = (FIX / "ogd-smn_tst_t_recent.csv").read_bytes()
        cls.server = http.server.HTTPServer(("127.0.0.1", 0), _Handler)
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.server.server_address[1]}"

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        _Handler.hits = []
        sw._warned.clear()

    def test_cache_and_conditional_get(self):
        url = f"{self.base}/tst/ogd-smn_tst_t_recent.csv"
        p = sw.fetch_cached(url, self.tmp, "f.csv", max_age_s=3600, opener=NO_PROXY)
        self.assertEqual(p.read_bytes(), _Handler.body)
        sw.fetch_cached(url, self.tmp, "f.csv", max_age_s=3600, opener=NO_PROXY)
        self.assertEqual(len(_Handler.hits), 1)  # fresh cache: no request
        sw.fetch_cached(url, self.tmp, "f.csv", max_age_s=0, opener=NO_PROXY)
        self.assertEqual(len(_Handler.hits), 2)  # conditional request -> 304
        self.assertEqual(p.read_bytes(), _Handler.body)

    def test_offline(self):
        with self.assertRaises(sw.ToolError):
            sw.fetch_cached(f"{self.base}/x.csv", self.tmp, "x.csv", 0, offline=True, opener=NO_PROXY)
        self.assertEqual(_Handler.hits, [])

    def test_404_is_a_clear_error(self):
        with self.assertRaises(sw.ToolError) as cm:
            sw.fetch_cached(f"{self.base}/tst/missing.csv", self.tmp, "ogd-smn_tst_t_missing.csv", 0, opener=NO_PROXY)
        self.assertIn("not found", str(cm.exception))

    def test_network_failure(self):
        dead = "http://127.0.0.1:9/nothing.csv"  # discard port: connection refused
        with self.assertRaises(sw.ToolError) as cm:
            sw.fetch_cached(dead, self.tmp, "n.csv", 0, timeout=5, opener=NO_PROXY)
        self.assertIn("network error", str(cm.exception))
        (self.tmp / "n.csv").write_text("cached", encoding="utf-8")
        err = io.StringIO()
        with contextlib.redirect_stderr(err):
            p = sw.fetch_cached(dead, self.tmp, "n.csv", 0, timeout=5, opener=NO_PROXY)
        self.assertEqual(p.read_text(encoding="utf-8"), "cached")
        self.assertIn("cached copy", err.getvalue())

    def test_fetch_command_end_to_end(self):
        old = (sw.BASE_URL, sw._opener, sw._utcnow)
        sw.BASE_URL, sw._opener = self.base, NO_PROXY
        sw._utcnow = lambda: dt.datetime(2026, 12, 1, tzinfo=UTC)
        try:
            code, _o, err = run_main(["fetch", "--station", "tst", "--from", "2026-10-07", "--to", "2026-10-07",
                                      "--cache", str(self.tmp / "cache"), "--out-dir", str(self.tmp)])
        finally:
            sw.BASE_URL, sw._opener, sw._utcnow = old
        self.assertEqual(code, 0, err)
        rows = read_csv(self.tmp / "weather_TST_2026-10-07_2026-10-07.csv")
        self.assertEqual(len(rows), 7)
        self.assertEqual(rows[1]["time_local"], "2026-10-07T22:10:00+02:00")
        self.assertEqual(rows[1]["interval_start_local"], "2026-10-07T22:00:00+02:00")
        self.assertEqual(rows[1]["time_utc"], "2026-10-07T20:10:00Z")
        self.assertEqual(rows[1]["wind_dir_deg"], "85")
        self.assertEqual(rows[4]["wind_ms"], "")
        self.assertIn("missing", err)  # most of the day is not in the tiny fixture


class JoinAndSummaries(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        rows = sw.read_meteoswiss_csv(FIX / "ogd-smn_tst_t_recent.csv",
                                      dt.datetime(2026, 10, 7, tzinfo=UTC), dt.datetime(2026, 10, 9, tzinfo=UTC))
        self.weather = self.tmp / "weather.csv"
        sw.write_weather_csv(self.weather, rows, "TST", TZ)

    def join(self, *extra):
        out = self.tmp / "joined.csv"
        code, o, err = run_main(["join", "--weather", str(self.weather), "--minutes", str(FIX / "minuten_sample.csv"),
                                 "--out", str(out), *extra])
        self.assertEqual(code, 0, err)
        return out, err

    def test_join_nearest_and_tailwind(self):
        out, err = self.join("--events", str(FIX / "ereignisse_sample.csv"), "--highway-bearing", "265")
        rows = read_csv(out)
        self.assertEqual(len(rows), 5)
        self.assertEqual(rows[0]["wx_time_utc"], "2026-10-07T20:10:00Z")  # 22:01 local -> 22:00-22:10 interval
        self.assertEqual(rows[0]["wind_ms"], "2.0")
        self.assertEqual(rows[0]["tailwind_ms"], "-2.00")  # wind from 85 deg, road at 265 deg: headwind
        self.assertEqual(rows[2]["wx_time_utc"], "2026-10-07T20:20:00Z")
        self.assertEqual(rows[2]["tailwind_ms"], "0.00")  # wind from 355: crosswind
        self.assertEqual(rows[3]["wx_time_utc"], "2026-10-07T20:30:00Z")
        self.assertEqual(rows[4]["wx_time_utc"], "")  # 03:00 local: no weather row within 10 min
        self.assertEqual(rows[4]["temp_c"], "")
        self.assertEqual(rows[0]["laeq_db"], "40.0")  # app columns kept as they were
        self.assertIn("1 minutes have no weather", err)

        ev = read_csv(self.tmp / "joined_events.csv")
        self.assertEqual([r["wx_time_utc"] for r in ev], ["2026-10-07T20:10:00Z", "2026-10-07T20:10:00Z", ""])

        hourly = read_csv(self.tmp / "joined_hourly.csv")
        h = hourly[0]
        self.assertEqual(h["hour_local"], "2026-10-07T22:00+02:00")
        self.assertEqual(h["n_minutes"], "3")  # the coverage-0 minute is left out
        self.assertEqual(h["laeq_db"], "45.7")  # energy mean of 40, 50, 30
        self.assertEqual(h["l90_median_db"], "32.0")
        self.assertEqual(h["l10_l90_median_db"], "12.0")
        self.assertEqual(h["events"], "6")
        self.assertEqual(h["events_per_h"], "120.0")
        self.assertEqual(h["events_listed"], "2")
        self.assertEqual(h["n_weather_rows"], "3")  # each 10-minute row counted once
        self.assertEqual(h["rain_sum_mm"], "0.5")
        self.assertEqual(h["wind_mean_ms"], "1.83")
        self.assertEqual(h["gust_max_ms"], "7.0")
        self.assertEqual(h["dir_median_deg"], "5")
        self.assertEqual(h["tailwind_mean_ms"], "-0.70")
        self.assertEqual(h["temp_mean_c"], "14.8")

        (n,) = read_csv(self.tmp / "joined_nights.csv")
        self.assertEqual(n["night_of"], "2026-10-07")
        self.assertEqual(n["n_minutes"], "4")
        self.assertEqual(n["laeq_db"], "44.5")
        self.assertEqual(n["events_listed"], "3")
        self.assertEqual(n["coverage_pct"], "1")

    def test_no_bearing_no_tailwind(self):
        out, _ = self.join()
        self.assertNotIn("tailwind_ms", read_csv(out)[0])
        self.assertNotIn("tailwind_mean_ms", read_csv(self.tmp / "joined_hourly.csv")[0])

    def test_minutes_without_level_columns(self):
        p = self.tmp / "thin.csv"
        p.write_text("start,duration_s\n2026-10-07T22:01:00+02:00,60\n", encoding="utf-8")
        code, _o, err = run_main(["join", "--weather", str(self.weather), "--minutes", str(p),
                                  "--out", str(self.tmp / "thin_joined.csv")])
        self.assertEqual(code, 0, err)
        self.assertIn("laeq_db missing", err)
        self.assertEqual(read_csv(self.tmp / "thin_joined.csv")[0]["wind_ms"], "2.0")

    def test_bad_inputs_are_errors(self):
        p = self.tmp / "bad.csv"
        p.write_text("a,b\n1,2\n", encoding="utf-8")
        code, _o, err = run_main(["join", "--weather", str(self.weather), "--minutes", str(p), "--out", str(self.tmp / "o.csv")])
        self.assertEqual(code, 2)
        self.assertIn("no 'start' column", err)
        code, _o, err = run_main(["join", "--weather", str(p), "--minutes", str(p), "--out", str(self.tmp / "o.csv")])
        self.assertEqual(code, 2)
        code, _o, err = run_main(["join", "--weather", str(self.tmp / "nope.csv"), "--minutes", str(p),
                                  "--out", str(self.tmp / "o.csv")])
        self.assertEqual(code, 2)

    def test_nights_command_prints_table(self):
        out, _ = self.join("--highway-bearing", "265")
        code, text, err = run_main(["nights", "--joined", str(out), "--hourly"])
        self.assertEqual(code, 0, err)
        self.assertIn("2026-10-07", text)
        self.assertIn("44.5", text)
        self.assertIn("22:00", text)
        self.assertIn("tail", text)
        self.assertIn("Source: MeteoSwiss", text)


if __name__ == "__main__":
    unittest.main()
