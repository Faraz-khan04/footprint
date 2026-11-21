"""
Device requests are signed:  X-Signature = hex(HMAC-SHA256(secret, f"{timestamp}." + raw_body))
Headers: X-Device-Id, X-Timestamp (unix seconds), X-Signature

Why HMAC instead of a plain API key in a header:
  * the secret itself never travels over the wire, only a signature of this exact body
  * a tampered body fails verification
  * the timestamp window limits replays to MAX_CLOCK_SKEW seconds, and replays inside
    that window are harmless because events are de-duplicated by (device_id, client_id)
"""
import hashlib
import hmac
import time

from fastapi import Depends, Header, HTTPException, Request, status
from sqlalchemy.orm import Session

from .config import settings
from .db import Device, get_db, utcnow


def sign(secret: str, timestamp: int, body: bytes) -> str:
    return hmac.new(secret.encode(), f"{timestamp}.".encode() + body, hashlib.sha256).hexdigest()


async def device_auth(
    request: Request,
    x_device_id: str = Header(...),
    x_timestamp: int = Header(...),
    x_signature: str = Header(...),
    db: Session = Depends(get_db),
) -> Device:
    fail = HTTPException(status.HTTP_401_UNAUTHORIZED, "Signature check failed")
    if abs(time.time() - x_timestamp) > settings.MAX_CLOCK_SKEW:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Timestamp outside allowed window; check the device clock")
    device = db.get(Device, x_device_id)
    if device is None or device.revoked:
        raise fail  # same message as a bad signature, so device IDs can't be probed
    body = await request.body()
    if not hmac.compare_digest(sign(device.secret, x_timestamp, body), x_signature):
        raise fail
    device.last_seen = utcnow()
    db.commit()
    return device


def _bearer(authorization: str | None, expected: str) -> None:
    if not expected:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, "Token not configured on the server")
    token = (authorization or "").removeprefix("Bearer ").strip()
    if not hmac.compare_digest(token, expected):
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Invalid token")


def admin_auth(authorization: str | None = Header(None)) -> None:
    _bearer(authorization, settings.ADMIN_TOKEN)


def read_auth(authorization: str | None = Header(None)) -> None:
    """Bearer token only (used where a device signature makes no sense)."""
    _read_bearer(authorization)


def _read_bearer(authorization: str | None) -> None:
    # the admin token can read too
    token = (authorization or "").removeprefix("Bearer ").strip()
    if settings.ADMIN_TOKEN and hmac.compare_digest(token, settings.ADMIN_TOKEN):
        return
    _bearer(authorization, settings.READ_TOKEN)


def _verify_device_get(request: Request, x_device_id, x_timestamp, x_signature, db: Session) -> Device:
    fail = HTTPException(status.HTTP_401_UNAUTHORIZED, "Signature check failed")
    if x_device_id is None or x_timestamp is None or x_signature is None:
        raise fail
    if abs(time.time() - x_timestamp) > settings.MAX_CLOCK_SKEW:
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, "Timestamp outside allowed window; check the device clock")
    device = db.get(Device, x_device_id)
    if device is None or device.revoked:
        raise fail
    target = request.url.path + (f"?{request.url.query}" if request.url.query else "")
    expected = sign(device.secret, x_timestamp, f"{request.method} {target}".encode())
    if not hmac.compare_digest(expected, x_signature):
        raise fail
    return device


async def reader_auth(
    request: Request,
    authorization: str | None = Header(None),
    x_device_id: str | None = Header(None),
    x_timestamp: int | None = Header(None),
    x_signature: str | None = Header(None),
    db: Session = Depends(get_db),
) -> None:
    """
    Read access for the dashboard. Either:
      * Authorization: Bearer <READ_TOKEN>            (browser dashboard), or
      * a device signature over "GET <path?query>"     (dashboard inside the phone app,
        so the phone never needs the READ_TOKEN and its secret never enters the web page)
    """
    if x_signature is None:
        _read_bearer(authorization)
        return
    _verify_device_get(request, x_device_id, x_timestamp, x_signature, db)


async def device_get_auth(
    request: Request,
    x_device_id: str | None = Header(None),
    x_timestamp: int | None = Header(None),
    x_signature: str | None = Header(None),
    db: Session = Depends(get_db),
) -> tuple[Device, int]:
    """Signed GET from a registered device. Returns the device and the request timestamp."""
    return _verify_device_get(request, x_device_id, x_timestamp, x_signature, db), x_timestamp
