import json
import os
import time
import uuid
from datetime import datetime, timedelta, timezone

os.environ["DATABASE_URL"] = "sqlite:///./test_footprint.db"
os.environ["ADMIN_TOKEN"] = "admin-test"
os.environ["READ_TOKEN"] = "read-test"
os.environ["FOOTPRINT_TZ"] = "Asia/Kolkata"
if os.path.exists("test_footprint.db"):
    os.remove("test_footprint.db")

from fastapi.testclient import TestClient  # noqa: E402

from app.config import settings  # noqa: E402
from app.main import app  # noqa: E402
from app.security import sign  # noqa: E402

ADMIN = {"Authorization": "Bearer admin-test"}
READ = {"Authorization": "Bearer read-test"}


def signed_post(client, device_id, secret, events, ts=None):
    body = json.dumps({"events": events}).encode()
    ts = ts or int(time.time())
    return client.post("/v1/events", content=body, headers={
        "Content-Type": "application/json", "X-Device-Id": device_id,
        "X-Timestamp": str(ts), "X-Signature": sign(secret, ts, body)})


def local(y, m, d, h, mi):
    return datetime(y, m, d, h, mi, tzinfo=settings.TZ)


def ev(type_, start, dur=0, **kw):
    return {"client_id": uuid.uuid4().hex, "type": type_, "started_at": start.isoformat(), "duration_s": dur, **kw}


def test_full_flow():
    with TestClient(app) as c:
        assert c.post("/v1/devices", json={"id": "laptop-main", "name": "Laptop", "kind": "laptop"}).status_code == 401
        lap = c.post("/v1/devices", json={"id": "laptop-main", "name": "Laptop", "kind": "laptop"}, headers=ADMIN).json()
        ph = c.post("/v1/devices", json={"id": "phone", "name": "Pixel", "kind": "phone"}, headers=ADMIN).json()

        day = local(2026, 9, 20, 0, 0)
        lap_events = [
            ev("app", local(2026, 9, 19, 23, 0), 1800, app="VS Code", category="Code"),     # yesterday, ends 23:30
            ev("app", local(2026, 9, 20, 10, 0), 3600, app="VS Code", category="Code"),
            ev("app", local(2026, 9, 20, 11, 0), 1200, app="Chrome", category="Browser", title="docs"),
            ev("app", local(2026, 9, 20, 23, 50), 1200, app="Chrome", category="Browser"),  # crosses midnight
        ]
        r = signed_post(c, "laptop-main", lap["secret"], lap_events)
        assert r.status_code == 200 and r.json() == {"received": 4, "stored": 4}

        # retry of the same batch is harmless
        assert signed_post(c, "laptop-main", lap["secret"], lap_events).json()["stored"] == 0

        ph_events = [
            ev("unlock", local(2026, 9, 20, 1, 10)),
            ev("app", local(2026, 9, 20, 1, 10), 300, app="Instagram", category="Social"),  # last use 01:15
            ev("unlock", local(2026, 9, 20, 7, 45)),
            ev("app", local(2026, 9, 20, 7, 45), 900, app="Instagram", category="Social"),
            ev("steps", local(2026, 9, 20, 12, 0), 3600, value=4200),
            ev("steps", local(2026, 9, 20, 18, 0), 3600, value=1800),
        ]
        assert signed_post(c, "phone", ph["secret"], ph_events).status_code == 200

        # bad signature / tampered / old timestamp / unknown device
        assert signed_post(c, "phone", "wrong-secret", ph_events).status_code == 401
        assert signed_post(c, "phone", ph["secret"], ph_events, ts=int(time.time()) - 3600).status_code == 401
        assert signed_post(c, "ghost", ph["secret"], ph_events).status_code == 401

        # reading needs a token
        assert c.get("/v1/summary/daily?date=2026-09-20").status_code == 401
        s = c.get("/v1/summary/daily?date=2026-09-20", headers=READ).json()
        apps = {(a["n"], a["d"]): a["m"] for a in s["apps"]}
        assert apps[("VS Code", "laptop")] == 60           # yesterday's session excluded
        assert apps[("Chrome", "laptop")] == 30            # 20 + only the 10 minutes before midnight
        assert apps[("Instagram", "phone")] == 20
        assert s["steps"] == 6000 and s["unlocks"] == 2
        assert s["bed"] == 1440 + 75 and s["wake"] == 7 * 60 + 45   # 01:15 -> 07:45
        assert s["sleep"] == 390
        assert s["laptop"][0] == [600.0, 680.0]            # 10:00-11:20 merged into one block

        week = c.get("/v1/summary/week?end=2026-09-20", headers=READ).json()
        assert len(week) == 7 and week[-1]["date"] == "2026-09-20"

        # revoked device is locked out
        c.post("/v1/devices/phone/revoke", headers=ADMIN)
        assert signed_post(c, "phone", ph["secret"], [ev("unlock", day)]).status_code == 401

        # dashboard is served
        assert c.get("/").status_code == 200


def test_android_payload_shape():
    """Mirror exactly what the Android collector sends (millisecond ISO times, icon in data)."""
    with TestClient(app) as c:
        ph = c.post("/v1/devices", json={"id": "phone-2", "name": "Phone", "kind": "phone"}, headers=ADMIN).json()
        events = [
            {"client_id": "a" * 32, "type": "app", "started_at": "2026-09-21T09:15:02.120+05:30", "duration_s": 312.5,
             "app": "Instagram", "category": "Social",
             "data": {"package": "com.instagram.android", "icon_png_b64": "iVBORw0KGgo="}},
            {"client_id": "b" * 32, "type": "unlock", "started_at": "2026-09-21T09:15:01.000+05:30", "duration_s": 0.0},
            {"client_id": "c" * 32, "type": "steps", "started_at": "2026-09-21T09:00:00.000+05:30", "duration_s": 900.0, "value": 412.0},
        ]
        r = signed_post(c, "phone-2", ph["secret"], events)
        assert r.status_code == 200, r.text
        s = c.get("/v1/summary/daily?date=2026-09-21", headers=READ).json()
        insta = next(a for a in s["apps"] if a["n"] == "Instagram")   # first test left a Chrome session crossing into this day
        assert insta["cat"] == "Social" and insta["d"] == "phone" and insta["m"] == 5
        assert s["steps"] == 412 and s["unlocks"] == 1


def signed_get(client, device_id, secret, path):
    ts = int(time.time())
    return client.get(path, headers={"X-Device-Id": device_id, "X-Timestamp": str(ts),
                                     "X-Signature": sign(secret, ts, f"GET {path}".encode())})


def test_icons_and_device_reads():
    from app.db import Event, SessionLocal
    from app.main import backfill_icons

    with TestClient(app) as c:
        ph = c.post("/v1/devices", json={"id": "phone-3", "name": "Phone", "kind": "phone"}, headers=ADMIN).json()
        icon = "iVBORw0KGgoAAAANSUhEUg=="
        r = signed_post(c, "phone-3", ph["secret"], [
            {"client_id": "d" * 32, "type": "app", "started_at": "2026-09-22T08:00:00.000+05:30", "duration_s": 60.0,
             "app": "WhatsApp", "category": "Chat", "data": {"package": "com.whatsapp", "icon_png_b64": icon}}])
        assert r.status_code == 200

        # icon moved out of the event into the icons table
        with SessionLocal() as db:
            ev = db.query(Event).filter(Event.client_id == "d" * 32).one()
            assert ev.data == {"package": "com.whatsapp"}

        # the phone reads with its own signature, no READ_TOKEN
        path = "/v1/icons"
        assert signed_get(c, "phone-3", ph["secret"], path).json()["WhatsApp"] == icon
        path = "/v1/summary/week?end=2026-09-22"
        assert len(signed_get(c, "phone-3", ph["secret"], path).json()) == 7
        # signature for one URL can't be reused for another
        ts = int(time.time())
        bad = c.get("/v1/summary/week?end=2026-09-01", headers={"X-Device-Id": "phone-3", "X-Timestamp": str(ts),
                    "X-Signature": sign(ph["secret"], ts, f"GET {path}".encode())})
        assert bad.status_code == 401
        assert signed_get(c, "phone-3", "wrong", "/v1/icons").status_code == 401

        # icons that were stored inside old events get moved by the backfill
        with SessionLocal() as db:
            db.add(Event(device_id="phone-3", client_id="e" * 32, type="app", app="Spotify",
                         started_at=datetime(2026, 9, 22, 3, 0), duration_s=30,
                         data={"package": "com.spotify.music", "icon_png_b64": icon}))
            db.commit()
            assert backfill_icons(db) == 1
        assert "Spotify" in c.get("/v1/icons", headers=READ).json()


def test_hello_mutual_proof():
    with TestClient(app) as c:
        ph = c.post("/v1/devices", json={"id": "phone-4", "name": "Phone", "kind": "phone"}, headers=ADMIN).json()
        nonce = "abcdef0123456789abcdef0123456789"
        path = f"/v1/hello?nonce={nonce}"
        ts = int(time.time())
        r = c.get(path, headers={"X-Device-Id": "phone-4", "X-Timestamp": str(ts),
                                 "X-Signature": sign(ph["secret"], ts, f"GET {path}".encode())})
        assert r.status_code == 200
        # the phone recomputes this with its own copy of the secret
        assert r.json()["proof"] == sign(ph["secret"], ts, f"SERVER {nonce}".encode())
        # no signature, or a bearer token instead, is not enough
        assert c.get(path).status_code == 401
        assert c.get(path, headers=READ).status_code == 401
        assert signed_get(c, "phone-4", "wrong", path).status_code == 401
