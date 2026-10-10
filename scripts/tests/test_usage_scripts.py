"""Tests for scripts/download_stats.py and scripts/usage_report.py, with saved fixtures only.

    python3 -m unittest discover -s scripts/tests -v

fixtures/releases.json is a hand-written answer in the shape of GitHub's
`GET /repos/{owner}/{repo}/releases` (no network access was used to make it);
fixtures/stats.json is the server's stats.json for the synthetic network of
server/tests/test_stats.py. No test touches the network.
"""

from __future__ import annotations

import contextlib
import io
import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest import mock

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import download_stats  # noqa: E402
import usage_report  # noqa: E402

FIX = HERE / "fixtures"
RELEASES = json.loads((FIX / "releases.json").read_text(encoding="utf-8"))
STATS = json.loads((FIX / "stats.json").read_text(encoding="utf-8"))


def run(main, argv) -> tuple[int, str]:
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        code = main(argv)
    return code, out.getvalue()


class DownloadStats(unittest.TestCase):
    def test_editions_from_fixed_asset_names(self):
        self.assertEqual(download_stats.edition_of("stadtlaerm.apk"), "offline")
        self.assertEqual(download_stats.edition_of("stadtlaerm-karte.apk"), "karte")
        self.assertEqual(download_stats.edition_of("stadtlaerm-labor.apk"), "andere")

    def test_summary_per_release_and_totals(self):
        s = download_stats.summarize(RELEASES)
        self.assertEqual([r["tag"] for r in s["releases"]], ["v0.5.0", "v0.5.1"])  # oldest first, no draft
        self.assertEqual(
            s["releases"][0]["assets"],
            [
                {"name": "notes.txt", "edition": "andere", "downloads": 2},
                {"name": "stadtlaerm-karte.apk", "edition": "karte", "downloads": 12},
                {"name": "stadtlaerm.apk", "edition": "offline", "downloads": 37},
            ],
        )
        self.assertEqual(s["totals"], {"offline": 46, "karte": 16, "andere": 2, "alle": 64})

    def test_table_and_json_output(self):
        code, text = run(download_stats.main, ["--file", str(FIX / "releases.json")])
        self.assertEqual(code, 0)
        self.assertIn("v0.5.0  2026-10-11", text)
        self.assertRegex(text, r"stadtlaerm\.apk\s+offline\s+37")
        self.assertRegex(text, r"offline\s+46\n")
        self.assertRegex(text, r"alle\s+64")
        code, text = run(download_stats.main, ["--json", "--file", str(FIX / "releases.json")])
        self.assertEqual(json.loads(text)["totals"]["karte"], 16)

    def test_pagination(self):
        page1 = [{"tag_name": f"v0.0.{i}", "published_at": f"2026-01-01T00:00:{i:02d}Z", "assets": []} for i in range(100)]
        page2 = RELEASES[:1]
        calls = []

        def get(path):
            calls.append(path)
            return page1 if path.endswith("page=1") else page2

        self.assertEqual(len(download_stats.fetch_releases(get=get)), 101)
        self.assertEqual(
            calls,
            [
                "repos/blauewelt/stadtlaerm/releases?per_page=100&page=1",
                "repos/blauewelt/stadtlaerm/releases?per_page=100&page=2",
            ],
        )

    def test_gh_first_then_urllib(self):
        """A failing `gh` (not logged in) falls back to plain HTTPS; neither touches the network here."""
        failing_gh = mock.Mock(side_effect=subprocess.CalledProcessError(4, ["gh"]))
        with (
            mock.patch.object(download_stats.shutil, "which", return_value="/usr/bin/gh"),
            mock.patch.object(download_stats, "_get_gh", failing_gh),
            mock.patch.object(download_stats, "_get_urllib", return_value=RELEASES) as plain,
        ):
            self.assertEqual(download_stats.fetch_releases(), RELEASES)
        failing_gh.assert_called_once()
        plain.assert_called_once()

    def test_without_gh_uses_urllib_with_optional_token(self):
        seen = []

        class Answer(io.BytesIO):
            def __enter__(self):
                return self

            def __exit__(self, *exc):
                return False

        def urlopen(req, timeout):
            seen.append(req)
            return Answer(json.dumps(RELEASES).encode())

        with (
            mock.patch.object(download_stats.shutil, "which", return_value=None),
            mock.patch.object(download_stats.urllib.request, "urlopen", urlopen),
            mock.patch.dict(download_stats.os.environ, {"GITHUB_TOKEN": "t0k"}),
        ):
            self.assertEqual(download_stats.fetch_releases(), RELEASES)
        self.assertEqual(seen[0].full_url, "https://api.github.com/repos/blauewelt/stadtlaerm/releases?per_page=100&page=1")
        self.assertEqual(seen[0].get_header("Authorization"), "Bearer t0k")
        with (
            mock.patch.object(download_stats.shutil, "which", return_value=None),
            mock.patch.object(download_stats.urllib.request, "urlopen", urlopen),
            mock.patch.dict(download_stats.os.environ, {}, clear=True),
        ):
            download_stats.fetch_releases()
        self.assertIsNone(seen[1].get_header("Authorization"))  # works unauthenticated

    def test_error_is_reported(self):
        with (
            mock.patch.object(download_stats.shutil, "which", return_value=None),
            mock.patch.object(download_stats, "_get_urllib", side_effect=OSError("offline")),
        ):
            err = io.StringIO()
            with contextlib.redirect_stderr(err):
                code, _ = run(download_stats.main, [])
        self.assertEqual(code, 1)
        self.assertIn("offline", err.getvalue())


class UsageReport(unittest.TestCase):
    def test_report_from_fixtures(self):
        code, text = run(
            usage_report.main,
            ["--releases-file", str(FIX / "releases.json"), "--stats-file", str(FIX / "stats.json")],
        )
        self.assertEqual(code, 0)
        self.assertRegex(text, r"Offline-Version\s+46")
        self.assertRegex(text, r"Karten-Version\s+16")
        self.assertIn("newest release v0.5.1: karte 4, offline 9", text)
        self.assertRegex(text, r"ever sent measurements\s+5")
        self.assertIn("active, last 7 / 30 days      3 / 4", text)
        self.assertIn("0.5.0 2, andere 1, unbekannt 1", text)
        self.assertIn("(2026-W32 … 2026-W43)", text)
        self.assertIn("Offline-Version beyond its downloads", text)

    def test_stats_url(self):
        self.assertEqual(usage_report.STATS_URL, "https://api.stadtlaerm.ch/v1/map/stats.json")


if __name__ == "__main__":
    unittest.main()
