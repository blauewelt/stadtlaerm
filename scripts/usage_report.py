#!/usr/bin/env python3
"""One short usage report: APK downloads (GitHub) and sharing (the server's stats.json).

    python3 scripts/usage_report.py
    python3 scripts/usage_report.py --releases-file r.json --stats-file stats.json   # offline

Downloads come from scripts/download_stats.py (GitHub's download_count per release asset);
sharing figures from https://api.stadtlaerm.ch/v1/map/stats.json (server/DESIGN.md §6.4),
both public, both counts only. The Offline-Version cannot report anything: beyond its
downloads, nothing about it is known, by design.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import download_stats  # noqa: E402

STATS_URL = "https://api.stadtlaerm.ch/v1/map/stats.json"


def fetch_stats(url: str = STATS_URL) -> dict:
    req = urllib.request.Request(url, headers={"User-Agent": "stadtlaerm-usage-report"})
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def render(downloads: dict, stats: dict) -> str:
    t = downloads["totals"]
    lines = [
        "Stadtlärm usage report",
        "",
        "Downloads (GitHub release assets; downloads, not people):",
        f"  Offline-Version  {t.get('offline', 0):>7}",
        f"  Karten-Version   {t.get('karte', 0):>7}",
        f"  all files        {t.get('alle', 0):>7}",
    ]
    if downloads["releases"]:
        newest = downloads["releases"][-1]
        per = ", ".join(f"{a['edition']} {a['downloads']}" for a in newest["assets"]) or "no assets"
        lines.append(f"  newest release {newest['tag']}: {per}")
    weeks = stats.get("registrations_by_week", [])
    versions = ", ".join(f"{v} {n}" for v, n in stats.get("app_versions", {}).items()) or "–"
    lines += [
        "",
        f"Sharing («Messwerte teilen», Karten-Version only; server stats of {stats.get('generated_at', '?')}):",
        f"  devices registered          {stats.get('devices_registered', 0):>7}",
        f"  … with a hectare set        {stats.get('devices_with_site', 0):>7}",
        f"  … ever sent measurements    {stats.get('devices_ever_shared', 0):>7}",
        f"  active, last 7 / 30 days    {stats.get('devices_active_7d', 0):>3} / {stats.get('devices_active_30d', 0)}",
        f"  deleted their data          {stats.get('devices_deleted_total', 0):>7}",
        f"  deleted after 60 d inactive {stats.get('devices_expired_total', 0):>7}",
        f"  hectares on the map (30 n.) {stats.get('cells_with_data_30d', 0):>7}",
        f"  device-nights shared        {stats.get('nights_shared_total', 0):>7}",
        f"  app versions (active 30 d)  {versions}",
        "  registrations per ISO week  " + (" ".join(str(w["devices"]) for w in weeks) or "–")
        + (f"  ({weeks[0]['week']} … {weeks[-1]['week']})" if weeks else ""),
        "",
        "Not counted: anything about the Offline-Version beyond its downloads; people (only",
        "downloads and devices); downloads from stadtlaerm.ch/download/ (fallback for old links).",
    ]
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--releases-file", help="saved answer of GitHub's release API instead of the network")
    p.add_argument("--stats-file", help="saved stats.json instead of the network")
    args = p.parse_args(argv)
    try:
        if args.releases_file:
            releases = json.loads(Path(args.releases_file).read_text(encoding="utf-8"))
        else:
            releases = download_stats.fetch_releases()
        if args.stats_file:
            stats = json.loads(Path(args.stats_file).read_text(encoding="utf-8"))
        else:
            stats = fetch_stats()
    except (OSError, RuntimeError, ValueError) as e:
        print(f"usage report failed: {e}", file=sys.stderr)
        return 1
    print(render(download_stats.summarize(releases), stats))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
