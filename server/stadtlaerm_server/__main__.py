"""`python -m stadtlaerm_server [publish|retention|stats|serve]`.

serve (default)  run the API with the background publish/retention loop
publish          rebuild the map files (and stats.json) once and exit
retention        run the retention job once and exit
stats            print the usage counts of stats.json (computed now, nothing written)
"""

from __future__ import annotations

import json
import logging
import os
import sys

from . import db, publish, retention
from .config import Settings


def main(argv: list[str]) -> int:
    cmd = argv[0] if argv else "serve"
    s = Settings.from_env()
    if cmd == "serve":
        import uvicorn

        uvicorn.run(
            "stadtlaerm_server.app:app_from_env",
            factory=True,
            host=os.environ.get("HOST", "0.0.0.0"),
            port=int(os.environ.get("PORT", "8000")),
            proxy_headers=False,
            access_log=False,  # no per-request IP log; rate limiting keeps IPs in memory only
        )
        return 0
    with db.open_db(s.db_path) as conn:
        db.migrate(conn)
        if cmd == "publish":
            cells = publish.publish(conn, s.map_dir, min_devices=s.min_devices_per_cell)
            print(f"published {len(cells['cells'])} cells for night {cells['night']} to {s.map_dir}")
        elif cmd == "stats":
            stats = publish.current_stats(conn, min_devices=s.min_devices_per_cell)
            print(json.dumps(stats, ensure_ascii=False, indent=2))
        elif cmd == "retention":
            print(json.dumps(retention.run_retention(conn, retention_days=s.retention_days)))
        else:
            print(__doc__, file=sys.stderr)
            return 2
    return 0


if __name__ == "__main__":
    logging.basicConfig(level=logging.INFO)
    raise SystemExit(main(sys.argv[1:]))
