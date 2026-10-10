"""Settings, read once from environment variables (see README.md for each one)."""

from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


def _bool(value: str) -> bool:
    return value.strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Settings:
    db_path: Path = Path("data/stadtlaerm.sqlite")
    map_dir: Path = Path("data/map")
    min_devices_per_cell: int = 1
    publish_interval_s: float = 600.0
    background_jobs: bool = True
    retention_days: int = 730
    inactive_delete_days: int = 60
    cors_origin: str = "https://stadtlaerm.ch"
    trust_proxy: bool = False
    registrations_per_ip_per_day: int = 10
    requests_per_device_per_hour: int = 60
    max_body_bytes: int = 2_000_000
    max_record_age_days: int = 7
    clock_skew_s: float = 600.0

    @property
    def max_decompressed_bytes(self) -> int:
        """Gzip bodies may expand to this size; larger ones are refused (gzip-bomb guard)."""
        return 8 * self.max_body_bytes

    @classmethod
    def from_env(cls, env: dict[str, str] | None = None) -> Settings:
        e = os.environ if env is None else env
        d = cls()
        return cls(
            db_path=Path(e.get("DB_PATH", str(d.db_path))),
            map_dir=Path(e.get("MAP_DIR", str(d.map_dir))),
            min_devices_per_cell=int(e.get("MIN_DEVICES_PER_CELL", d.min_devices_per_cell)),
            publish_interval_s=float(e.get("PUBLISH_INTERVAL_S", d.publish_interval_s)),
            background_jobs=_bool(e.get("BACKGROUND_JOBS", "1")),
            retention_days=int(e.get("RETENTION_DAYS", d.retention_days)),
            inactive_delete_days=int(e.get("INACTIVE_DELETE_DAYS", d.inactive_delete_days)),
            cors_origin=e.get("CORS_ORIGIN", d.cors_origin),
            trust_proxy=_bool(e.get("TRUST_PROXY", "0")),
            registrations_per_ip_per_day=int(e.get("REGISTRATIONS_PER_IP_PER_DAY", d.registrations_per_ip_per_day)),
            requests_per_device_per_hour=int(e.get("REQUESTS_PER_DEVICE_PER_HOUR", d.requests_per_device_per_hour)),
            max_body_bytes=int(e.get("MAX_BODY_BYTES", d.max_body_bytes)),
            max_record_age_days=int(e.get("MAX_RECORD_AGE_DAYS", d.max_record_age_days)),
            clock_skew_s=float(e.get("CLOCK_SKEW_S", d.clock_skew_s)),
        )
