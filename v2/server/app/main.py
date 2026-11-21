import asyncio
import secrets
from contextlib import asynccontextmanager
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Query
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from sqlalchemy import select, update
from sqlalchemy.orm import Session

from .config import settings
from .db import AppIcon, Device, Event, SessionLocal, engine, get_db, init_db, utcnow
from .schemas import DeviceCreate, DeviceCreated, DeviceOut, EventBatch, IngestResult
from .security import admin_auth, device_auth, device_get_auth, reader_auth, sign
from .summary import daily_summary, top_apps


async def _retention_loop():
    """Every 6 hours, wipe window titles older than TITLE_RETENTION_DAYS (app + duration are kept)."""
    while True:
        cutoff = utcnow() - timedelta(days=settings.TITLE_RETENTION_DAYS)
        with SessionLocal() as db:
            db.execute(update(Event).where(Event.started_at < cutoff, Event.title.is_not(None)).values(title=None))
            db.commit()
        await asyncio.sleep(6 * 3600)


def _save_icon(db: Session, app_name: str | None, data: dict | None) -> dict | None:
    """Move an icon out of event data into the icons table. Returns the data without the icon."""
    if not isinstance(data, dict) or "icon_png_b64" not in data:
        return data
    data = dict(data)
    icon = data.pop("icon_png_b64")
    if app_name and isinstance(icon, str) and 0 < len(icon) <= 200_000:
        db.merge(AppIcon(app=app_name, package=data.get("package"), png_b64=icon, updated_at=utcnow()))
    return data


def backfill_icons(db: Session) -> int:
    """Icons that arrived before the icons table existed are sitting inside event data; move them."""
    moved = 0
    for ev in db.scalars(select(Event).where(Event.type == "app")).yield_per(500):
        if isinstance(ev.data, dict) and "icon_png_b64" in ev.data:
            ev.data = _save_icon(db, ev.app, ev.data)
            moved += 1
    db.commit()
    return moved


@asynccontextmanager
async def lifespan(_app: FastAPI):
    init_db()
    with SessionLocal() as db:
        backfill_icons(db)
    task = asyncio.create_task(_retention_loop())
    yield
    task.cancel()


app = FastAPI(title="Footprint API", version="0.1.0", lifespan=lifespan)
if settings.CORS_ORIGINS:
    app.add_middleware(CORSMiddleware, allow_origins=settings.CORS_ORIGINS,
                       allow_methods=["GET"], allow_headers=["Authorization"])


def _today() -> date:
    return datetime.now(settings.TZ).date()


# ---------- health ----------
@app.get("/health")
def health():
    return {"ok": True}


@app.get("/v1/hello")
def hello(nonce: str = Query(..., min_length=16, max_length=64, pattern=r"^[A-Za-z0-9]+$"),
          auth: tuple[Device, int] = Depends(device_get_auth)):
    """
    Mutual check used by the phone when it looks for the laptop on the hotspot.
    The phone proves who it is with its signature; the server proves it's the real server
    by signing the phone's random nonce with the same device secret.
    """
    device, ts = auth
    return {"server": "footprint", "device": device.id,
            "proof": sign(device.secret, ts, f"SERVER {nonce}".encode())}


# ---------- devices (admin) ----------
@app.post("/v1/devices", response_model=DeviceCreated, dependencies=[Depends(admin_auth)], status_code=201)
def create_device(body: DeviceCreate, db: Session = Depends(get_db)):
    if db.get(Device, body.id):
        raise HTTPException(409, f"Device '{body.id}' already exists. Pick another id or revoke the old one.")
    secret = secrets.token_urlsafe(32)
    db.add(Device(id=body.id, name=body.name, kind=body.kind, secret=secret))
    db.commit()
    return DeviceCreated(id=body.id, secret=secret)


@app.get("/v1/devices", response_model=list[DeviceOut], dependencies=[Depends(admin_auth)])
def list_devices(db: Session = Depends(get_db)):
    return db.scalars(select(Device).order_by(Device.created_at)).all()


@app.post("/v1/devices/{device_id}/revoke", dependencies=[Depends(admin_auth)])
def revoke_device(device_id: str, db: Session = Depends(get_db)):
    device = db.get(Device, device_id)
    if not device:
        raise HTTPException(404, "No device with that id")
    device.revoked = True
    db.commit()
    return {"revoked": device_id}


# ---------- ingest (signed by device) ----------
def _insert_ignore_duplicates(db: Session, rows: list[dict]) -> int:
    if not rows:
        return 0
    dialect = engine.dialect.name
    if dialect == "sqlite":
        from sqlalchemy.dialects.sqlite import insert
    elif dialect == "postgresql":
        from sqlalchemy.dialects.postgresql import insert
    else:
        raise RuntimeError(f"Unsupported database: {dialect}")
    stmt = insert(Event).values(rows).on_conflict_do_nothing(index_elements=["device_id", "client_id"])
    result = db.execute(stmt)
    db.commit()
    return result.rowcount or 0


@app.post("/v1/events", response_model=IngestResult)
def ingest(batch: EventBatch, device: Device = Depends(device_auth), db: Session = Depends(get_db)):
    rows = []
    for e in batch.events:
        data = _save_icon(db, e.app, e.data)
        rows.append({
            "device_id": device.id,
            "client_id": e.client_id,
            "type": e.type,
            "app": e.app,
            "title": e.title,
            "category": e.category,
            "started_at": e.started_at.astimezone(timezone.utc).replace(tzinfo=None),
            "duration_s": e.duration_s,
            "value": e.value,
            "data": data,
            "received_at": utcnow(),
        })
    stored = _insert_ignore_duplicates(db, rows)
    return IngestResult(received=len(rows), stored=stored)


# ---------- read (dashboard) ----------
@app.get("/v1/summary/daily", dependencies=[Depends(reader_auth)])
def summary_daily(day: date | None = Query(None, alias="date"), db: Session = Depends(get_db)):
    return daily_summary(db, day or _today())


@app.get("/v1/summary/week", dependencies=[Depends(reader_auth)])
def summary_week(end: date | None = None, db: Session = Depends(get_db)):
    end = end or _today()
    return [daily_summary(db, end - timedelta(days=i)) for i in range(6, -1, -1)]


@app.get("/v1/apps/top", dependencies=[Depends(reader_auth)])
def apps_top(days: int = Query(7, ge=1, le=90), db: Session = Depends(get_db)):
    return top_apps(db, _today(), days)


@app.get("/v1/icons", dependencies=[Depends(reader_auth)])
def icons(db: Session = Depends(get_db)):
    """{app display name: base64 PNG} for every app that has sent its icon."""
    return {i.app: i.png_b64 for i in db.scalars(select(AppIcon))}


# ---------- dashboard (static) ----------
_dash = Path(__file__).resolve().parent.parent / "dashboard"
if _dash.is_dir():
    app.mount("/", StaticFiles(directory=_dash, html=True), name="dashboard")
