"""HTTP API (DESIGN.md §4), the static map (§6) and the background jobs, in one process.

No `from __future__ import annotations` here: FastAPI must resolve the dependency
annotations defined inside create_app at runtime.
"""

import asyncio
import base64
import contextlib
import hashlib
import hmac
import json
import logging
import secrets
import sqlite3
import uuid
import zlib
from collections.abc import AsyncIterator, Iterator
from datetime import UTC, datetime, timedelta
from typing import Annotated, Any

from fastapi import Depends, FastAPI, Header, HTTPException, Request, Response
from fastapi.responses import FileResponse, JSONResponse
from pydantic import BaseModel, ValidationError

from . import db, publish, retention
from .config import Settings
from .ratelimit import TokenBuckets
from .schemas import MAX_EVENTS, MAX_MINUTES, DeviceIn, DeviceOut, EventIn, MinuteIn, SiteIn, UploadResult

log = logging.getLogger("stadtlaerm")


class ApiError(HTTPException):
    pass


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


def _reasons(err: ValidationError) -> list[dict[str, str]]:
    return [
        {"field": ".".join(str(p) for p in e["loc"]) or "body", "reason": e["msg"].removeprefix("Value error, ")}
        for e in err.errors(include_url=False)
    ]


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    registrations = TokenBuckets(settings.registrations_per_ip_per_day, 86_400)
    device_requests = TokenBuckets(settings.requests_per_device_per_hour, 3_600)

    with db.open_db(settings.db_path) as conn:
        db.migrate(conn)

    def publish_once() -> None:
        with db.open_db(settings.db_path) as conn:
            publish.publish(conn, settings.map_dir, min_devices=settings.min_devices_per_cell)

    def retention_once() -> int:
        """Retention and expiry (DESIGN.md §5); returns the number of expired devices."""
        with db.open_db(settings.db_path) as conn:
            log.info("retention: %s", retention.run_retention(conn, retention_days=settings.retention_days))
            return retention.expire_inactive(conn, inactive_days=settings.inactive_delete_days)

    async def background() -> None:
        last_retention: datetime | None = None
        while True:
            try:
                await asyncio.to_thread(publish_once)
                now = datetime.now(UTC)
                if last_retention is None or now - last_retention >= timedelta(days=1):
                    if await asyncio.to_thread(retention_once):
                        # Expired devices leave the map now, not only at the next regular run.
                        await asyncio.to_thread(publish_once)
                    last_retention = now
                registrations.prune()
                device_requests.prune()
            except Exception:  # keep the loop alive; the next run tries again
                log.exception("background job failed")
            await asyncio.sleep(settings.publish_interval_s)

    @contextlib.asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        task = asyncio.create_task(background()) if settings.background_jobs else None
        yield
        if task:
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task

    app = FastAPI(title="Stadtlärm contribution API", version="1", lifespan=lifespan, docs_url=None, redoc_url=None)
    app.state.settings = settings
    app.state.publish_once = publish_once
    app.state.retention_once = retention_once
    app.state.registrations = registrations
    app.state.device_requests = device_requests

    # ---- helpers ------------------------------------------------------------------------

    def get_conn() -> Iterator[sqlite3.Connection]:
        conn = db.connect(settings.db_path)
        try:
            yield conn
        finally:
            conn.close()

    Conn = Annotated[sqlite3.Connection, Depends(get_conn)]

    def client_ip(request: Request) -> str:
        if settings.trust_proxy:
            fwd = request.headers.get("x-forwarded-for")
            if fwd:
                # The proxy (Caddy) appends the address it saw; the rightmost entry is the one we trust.
                return fwd.split(",")[-1].strip()
        return request.client.host if request.client else "unknown"

    def limited(buckets: TokenBuckets, key: str, what: str) -> None:
        wait = buckets.take(key)
        if wait > 0:
            raise ApiError(429, f"rate limit: {what}", headers={"Retry-After": str(int(wait) + 1)})

    async def read_body(request: Request) -> Any:
        declared = request.headers.get("content-length")
        if declared and declared.isdigit() and int(declared) > settings.max_body_bytes:
            raise ApiError(413, f"request body larger than {settings.max_body_bytes} bytes")
        raw = bytearray()
        async for chunk in request.stream():
            raw += chunk
            if len(raw) > settings.max_body_bytes:
                raise ApiError(413, f"request body larger than {settings.max_body_bytes} bytes")
        encoding = request.headers.get("content-encoding", "identity").strip().lower()
        if encoding == "gzip":
            d = zlib.decompressobj(16 + zlib.MAX_WBITS)
            try:
                data = d.decompress(bytes(raw), settings.max_decompressed_bytes)
            except zlib.error as e:
                raise ApiError(400, "body is not valid gzip") from e
            if d.unconsumed_tail or not d.eof:
                raise ApiError(413 if d.unconsumed_tail else 400, "gzip body too large or truncated")
        elif encoding in ("identity", ""):
            data = bytes(raw)
        else:
            raise ApiError(415, f"unsupported Content-Encoding: {encoding}")
        try:
            return json.loads(data)
        except (UnicodeDecodeError, json.JSONDecodeError) as e:
            raise ApiError(400, "body is not valid JSON") from e

    def validate[M: BaseModel](model: type[M], body: Any) -> M:
        try:
            return model.model_validate(body)
        except ValidationError as e:
            raise ApiError(422, _reasons(e)) from e

    def authenticate(
        device_id: str, conn: sqlite3.Connection, authorization: str | None, *, rate_limit: bool = True
    ) -> None:
        scheme, _, token = (authorization or "").partition(" ")
        if scheme.lower() != "bearer" or not token.strip():
            raise ApiError(401, "missing bearer token", headers={"WWW-Authenticate": "Bearer"})
        row = conn.execute("SELECT token_hash FROM devices WHERE id = ?", (device_id,)).fetchone()
        # Unknown device and wrong token look the same from outside.
        if row is None or not hmac.compare_digest(row[0], hash_token(token.strip())):
            raise ApiError(401, "invalid device or token", headers={"WWW-Authenticate": "Bearer"})
        if rate_limit:
            limited(device_requests, device_id, f"{settings.requests_per_device_per_hour} requests per device per hour")
        with conn:
            conn.execute("UPDATE devices SET last_seen_at = ? WHERE id = ?", (db.utc_now_iso(), device_id))

    Auth = Annotated[str | None, Header()]

    def check_time(start: datetime, now: datetime) -> str | None:
        if start > now + timedelta(seconds=settings.clock_skew_s):
            return "start is in the future"
        if start < now - timedelta(days=settings.max_record_age_days):
            return f"start is more than {settings.max_record_age_days} days old"
        return None

    def upload(body: Any, model: type[MinuteIn] | type[EventIn], limit: int, to_row) -> tuple[list[dict], list[dict]]:
        if not isinstance(body, list):
            raise ApiError(422, "body must be a JSON array of records")
        if len(body) > limit:
            raise ApiError(413, f"at most {limit} records per request")
        now = datetime.now(UTC)
        rows, rejected = [], []
        for i, item in enumerate(body):
            start = item.get("start") if isinstance(item, dict) else None
            try:
                rec = model.model_validate(item)
            except ValidationError as e:
                rejected.append({"index": i, "start": start, "reasons": _reasons(e)})
                continue
            if (why := check_time(rec.start_dt, now)) is not None:
                rejected.append({"index": i, "start": start, "reasons": [{"field": "start", "reason": why}]})
                continue
            rows.append(to_row(rec))
        return rows, rejected

    # ---- endpoints ----------------------------------------------------------------------

    @app.exception_handler(ApiError)
    async def _api_error(_: Request, exc: ApiError) -> JSONResponse:
        return JSONResponse({"detail": exc.detail}, status_code=exc.status_code, headers=exc.headers)

    @app.get("/healthz")
    def healthz(conn: Conn) -> dict:
        conn.execute("SELECT 1").fetchone()
        return {"ok": True}

    @app.post("/v1/devices", status_code=201, response_model=DeviceOut)
    async def register(request: Request, conn: Conn) -> DeviceOut:
        limited(
            registrations, client_ip(request), f"{settings.registrations_per_ip_per_day} registrations per IP per day"
        )
        body = validate(DeviceIn, await read_body(request))
        device_id = str(uuid.uuid4())
        token = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
        with conn:
            conn.execute(
                "INSERT INTO devices (id, token_hash, app_version, app_build, created_at, last_seen_at) "
                "VALUES (?, ?, ?, ?, ?, ?)",
                (device_id, hash_token(token), body.app_version, body.app_build, db.utc_now_iso(), db.utc_now_iso()),
            )
        return DeviceOut(device_id=device_id, token=token)

    @app.put("/v1/devices/{device_id}/site", status_code=204)
    async def put_site(device_id: str, request: Request, conn: Conn, authorization: Auth = None) -> Response:
        authenticate(device_id, conn, authorization)
        s = validate(SiteIn, await read_body(request))
        with conn:
            conn.execute(
                "INSERT INTO sites (device_id, cell, placement, floor, street_facing, device_model, audio_source, "
                "calibrated, calibration_offset_db, note, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                "ON CONFLICT (device_id) DO UPDATE SET cell = excluded.cell, placement = excluded.placement, "
                "floor = excluded.floor, street_facing = excluded.street_facing, device_model = excluded.device_model, "
                "audio_source = excluded.audio_source, calibrated = excluded.calibrated, "
                "calibration_offset_db = excluded.calibration_offset_db, note = excluded.note, "
                "updated_at = excluded.updated_at",
                (
                    device_id, s.cell, s.placement, s.floor,
                    None if s.street_facing is None else int(s.street_facing),
                    s.device_model, s.audio_source, int(s.calibrated), s.calibration_offset_db, s.note,
                    db.utc_now_iso(),
                ),
            )  # fmt: skip
        return Response(status_code=204)

    @app.post("/v1/devices/{device_id}/minutes", response_model=UploadResult)
    async def post_minutes(device_id: str, request: Request, conn: Conn, authorization: Auth = None) -> UploadResult:
        authenticate(device_id, conn, authorization)
        received = db.utc_now_iso()
        rows, rejected = upload(
            await read_body(request), MinuteIn, MAX_MINUTES, lambda m: db.minute_row(device_id, m, received)
        )
        with conn:
            accepted, duplicates = db.upsert_rows(conn, "minutes", db.MINUTE_COLUMNS, rows)
        return UploadResult(accepted=accepted, duplicates=duplicates, rejected=rejected)

    @app.post("/v1/devices/{device_id}/events", response_model=UploadResult)
    async def post_events(device_id: str, request: Request, conn: Conn, authorization: Auth = None) -> UploadResult:
        authenticate(device_id, conn, authorization)
        received = db.utc_now_iso()
        rows, rejected = upload(
            await read_body(request), EventIn, MAX_EVENTS, lambda e: db.event_row(device_id, e, received)
        )
        with conn:
            accepted, duplicates = db.upsert_rows(conn, "events", db.EVENT_COLUMNS, rows)
        return UploadResult(accepted=accepted, duplicates=duplicates, rejected=rejected)

    @app.delete("/v1/devices/{device_id}", status_code=204)
    def delete(device_id: str, conn: Conn, authorization: Auth = None) -> Response:
        # Never rate limited: deleting one's data must always work (DESIGN.md §2.4).
        authenticate(device_id, conn, authorization, rate_limit=False)
        db.delete_device(conn, device_id)
        return Response(status_code=204)

    # ---- the public map files -------------------------------------------------------------

    map_headers = {
        "Cache-Control": "max-age=300",
        "Access-Control-Allow-Origin": settings.cors_origin,
        "Vary": "Origin",
    }

    @app.get("/v1/map/{path:path}")
    def map_file(path: str) -> Response:
        root = settings.map_dir.resolve()
        target = (root / path).resolve()
        if root not in target.parents or target.suffix != ".json" or not target.is_file():
            return JSONResponse({"detail": "not found"}, status_code=404, headers=map_headers)
        return FileResponse(target, media_type="application/json", headers=map_headers)

    return app


def app_from_env() -> FastAPI:
    """Factory for uvicorn: `uvicorn --factory stadtlaerm_server.app:app_from_env`."""
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    return create_app()
