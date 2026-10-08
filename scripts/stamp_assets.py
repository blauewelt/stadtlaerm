#!/usr/bin/env python3
"""Stamp the local script and style URLs of docs/karte.html with the hash of their file.

Adapted from blauewelt/earth (scripts/stamp_assets.py). GitHub Pages tells browsers a file
stays fresh for a while, and there is no build step that renames files, so a phone could run
yesterday's karte.js against today's JSON. Each local asset URL therefore carries `?v=` plus
the first 8 hex of the file's own sha256: change a byte and the URL changes with it; leave the
file alone and caching works as it should. The hash is measured, never typed in.

    python3 scripts/stamp_assets.py           # rewrite the stamps in docs/karte.html
    python3 scripts/stamp_assets.py --check   # exit 1 if any stamp is stale (tests, CI)

Only `src="…"` and `href="…"` attributes in `<script>` and `<link rel="stylesheet">` tags that
point at a file under docs/ are stamped; external URLs and anchors are left alone. A local
asset that is referenced without a stamp gets one; a referenced file that does not exist is
an error.
"""

import hashlib
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DOCS = os.path.join(ROOT, "docs")
PAGES = ["karte.html"]

TAG = re.compile(r"<(script|link)\b[^>]*>", re.IGNORECASE)
ATTR = re.compile(r'\b(src|href)="([^"?#]+)(\?v=[0-9a-f]{8})?"')


def digest(path):
    with open(path, "rb") as fh:
        return hashlib.sha256(fh.read()).hexdigest()[:8]


def is_local(url):
    return not re.match(r"^[a-z][a-z0-9+.-]*:", url, re.IGNORECASE) and not url.startswith("//")


def stamp_page(page):
    """Returns (new text, list of (asset, hash)); raises on a missing asset."""
    path = os.path.join(DOCS, page)
    text = open(path, encoding="utf-8").read()
    seen = []
    base = os.path.dirname(path)

    def fix_tag(m):
        tag = m.group(0)
        if m.group(1).lower() == "link" and not re.search(r'\brel="stylesheet"', tag):
            return tag

        def fix_attr(a):
            url = a.group(2)
            if not is_local(url):
                return a.group(0)
            asset = os.path.normpath(os.path.join(base, url))
            if not asset.startswith(DOCS + os.sep) or not os.path.isfile(asset):
                raise SystemExit(f"{page}: {url} is not a file under docs/")
            h = digest(asset)
            seen.append((url, h))
            return f'{a.group(1)}="{url}?v={h}"'

        return ATTR.sub(fix_attr, tag)

    return TAG.sub(fix_tag, text), seen


def main():
    check = "--check" in sys.argv[1:]
    stale = []
    for page in PAGES:
        path = os.path.join(DOCS, page)
        original = open(path, encoding="utf-8").read()
        new, seen = stamp_page(page)
        if new != original:
            stale.append(page)
            if not check:
                open(path, "w", encoding="utf-8").write(new)
        if not check:
            for url, h in seen:
                print(f"  {page}: {url:32s} {h}")
    if check:
        if stale:
            print("stale asset stamps in: " + ", ".join(stale) + " (run python3 scripts/stamp_assets.py)")
            return 1
        print("asset stamps up to date")
        return 0
    print("stamped: " + (", ".join(stale) if stale else "nothing to do"))
    return 0


if __name__ == "__main__":
    sys.exit(main())
