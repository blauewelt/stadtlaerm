#!/usr/bin/env python3
"""CSP hashes for the inline scripts of a website page.

The pages in docs/ allow scripts only by hash (Content-Security-Policy `script-src 'sha256-…'`),
so any change to an inline <script> must be followed by a new hash. Usage (from the repo root):

    python3 android/tools/csp_hash.py docs/update.html            # print and check
    python3 android/tools/csp_hash.py --write docs/update.html    # also update the CSP meta tag

The check exits with status 1 if the CSP does not list exactly the hashes of the page's scripts.
"""
import base64
import hashlib
import re
import sys

SCRIPT = re.compile(r"<script>(.*?)</script>", re.S)
SCRIPT_SRC = re.compile(r"script-src[^;\"]*")


def hashes(html: str) -> list[str]:
    return ["'sha256-%s'" % base64.b64encode(hashlib.sha256(s.encode("utf-8")).digest()).decode()
            for s in SCRIPT.findall(html)]


def main(argv: list[str]) -> int:
    write = "--write" in argv
    paths = [a for a in argv if a != "--write"]
    if not paths:
        print(__doc__)
        return 2
    status = 0
    for path in paths:
        with open(path, encoding="utf-8") as f:
            html = f.read()
        want = "script-src " + " ".join(hashes(html))
        m = SCRIPT_SRC.search(html)
        have = m.group(0).strip() if m else None
        print(f"{path}: {want}")
        if have == want:
            continue
        if write and m:
            html = html[:m.start()] + want + html[m.end():]
            with open(path, "w", encoding="utf-8") as f:
                f.write(html)
            print(f"{path}: CSP updated (was: {have})")
        else:
            print(f"{path}: CSP MISMATCH (has: {have})")
            status = 1
    return status


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
