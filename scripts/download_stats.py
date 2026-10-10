#!/usr/bin/env python3
"""How often each published APK was downloaded, from GitHub's release API.

The APKs are attached to a GitHub Release per version (tag vX.Y.Z) under fixed names:

    stadtlaerm.apk        Offline-Version (no internet permission)  → edition "offline"
    stadtlaerm-karte.apk  Karten-Version («Messwerte teilen»)        → edition "karte"

GitHub counts every download of a release asset (`download_count`). These are downloads, not
people: one person downloading twice counts twice, and an update downloaded again counts again.
The project never sees who downloaded (GitHub serves the file; we only read this one number
per file). Files on stadtlaerm.ch itself (docs/download/, the fallback for old links) are not
counted anywhere.

    python3 scripts/download_stats.py                  # table per release and asset, totals
    python3 scripts/download_stats.py --json           # the same as JSON
    python3 scripts/download_stats.py --file r.json    # read a saved API answer instead

Source: `gh api repos/blauewelt/stadtlaerm/releases` if the `gh` command is installed and
logged in; otherwise plain HTTPS (urllib) to api.github.com, with `Authorization: Bearer
$GITHUB_TOKEN` if that variable is set. The repository is public, so no token is needed
(unauthenticated: 60 API requests per hour per IP address).
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import urllib.request
from collections.abc import Callable

REPO = "blauewelt/stadtlaerm"
API = "https://api.github.com"
PER_PAGE = 100
EDITIONS = {"stadtlaerm.apk": "offline", "stadtlaerm-karte.apk": "karte"}
EDITION_ORDER = ("offline", "karte", "andere")


def edition_of(asset_name: str) -> str:
    return EDITIONS.get(asset_name, "andere")


def _get_gh(path: str) -> list | dict:
    out = subprocess.run(["gh", "api", path], check=True, capture_output=True, text=True).stdout
    return json.loads(out)


def _get_urllib(path: str) -> list | dict:
    req = urllib.request.Request(
        f"{API}/{path}",
        headers={
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
            "User-Agent": "stadtlaerm-download-stats",
        },
    )
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        req.add_header("Authorization", f"Bearer {token}")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def fetch_releases(repo: str = REPO, get: Callable[[str], list | dict] | None = None) -> list[dict]:
    """Every release of `repo` (all pages). `get` is for tests; by default gh, then urllib."""
    getters = [get] if get else ([_get_gh] if shutil.which("gh") else []) + [_get_urllib]
    last_error: Exception | None = None
    for getter in getters:
        try:
            releases: list[dict] = []
            page = 1
            while True:
                batch = getter(f"repos/{repo}/releases?per_page={PER_PAGE}&page={page}")
                if not isinstance(batch, list):
                    raise ValueError(f"unexpected answer from the release API: {str(batch)[:200]}")
                releases.extend(batch)
                if len(batch) < PER_PAGE:
                    return releases
                page += 1
        except (OSError, subprocess.CalledProcessError, ValueError) as e:  # e.g. gh not logged in
            last_error = e
    raise RuntimeError(f"could not read the releases of {repo}: {last_error}")


def summarize(releases: list[dict], repo: str = REPO) -> dict:
    """{"releases": [{tag, published_at, assets: [{name, edition, downloads}]}], "totals": {...}}."""
    out = []
    totals: dict[str, int] = {}
    for rel in sorted(releases, key=lambda r: r.get("published_at") or r.get("created_at") or ""):
        if rel.get("draft"):
            continue
        assets = []
        for a in sorted(rel.get("assets", []), key=lambda a: a["name"]):
            ed = edition_of(a["name"])
            n = int(a.get("download_count", 0))
            assets.append({"name": a["name"], "edition": ed, "downloads": n})
            totals[ed] = totals.get(ed, 0) + n
        out.append({"tag": rel.get("tag_name"), "published_at": rel.get("published_at"), "assets": assets})
    ordered = {ed: totals[ed] for ed in EDITION_ORDER if ed in totals}
    ordered["alle"] = sum(totals.values())
    return {"repo": repo, "releases": out, "totals": ordered}


def render(summary: dict) -> str:
    lines = [f"Downloads of release assets, github.com/{summary['repo']} (downloads, not people)", ""]
    if not summary["releases"]:
        lines.append("(no published releases)")
    for rel in summary["releases"]:
        date = (rel["published_at"] or "")[:10]
        lines.append(f"{rel['tag']}  {date}")
        if not rel["assets"]:
            lines.append("    (no assets)")
        for a in rel["assets"]:
            lines.append(f"    {a['name']:<24} {a['edition']:<8} {a['downloads']:>8}")
    lines += ["", "Total per edition, all releases:"]
    for ed, n in summary["totals"].items():
        lines.append(f"    {ed:<33} {n:>8}")
    return "\n".join(lines)


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    p.add_argument("--json", action="store_true", help="print JSON instead of a table")
    p.add_argument("--file", help="read a saved answer of the release API (a JSON array) instead of the network")
    p.add_argument("--repo", default=REPO, help=argparse.SUPPRESS)
    args = p.parse_args(argv)
    if args.file:
        with open(args.file, encoding="utf-8") as f:
            releases = json.load(f)
    else:
        try:
            releases = fetch_releases(args.repo)
        except RuntimeError as e:
            print(e, file=sys.stderr)
            return 1
    s = summarize(releases, args.repo)
    print(json.dumps(s, ensure_ascii=False, indent=2) if args.json else render(s))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
