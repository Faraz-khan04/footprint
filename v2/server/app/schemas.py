from datetime import datetime
from typing import Literal

from pydantic import AwareDatetime, BaseModel, Field

EventType = Literal["app", "steps", "unlock", "screen_on", "screen_off"]


class EventIn(BaseModel):
    client_id: str = Field(min_length=8, max_length=64)
    type: EventType
    app: str | None = Field(None, max_length=200)
    title: str | None = Field(None, max_length=500)
    category: str | None = Field(None, max_length=40)
    started_at: AwareDatetime                       # must include a timezone offset
    duration_s: float = Field(0, ge=0, le=86_400)
    value: float | None = Field(None, ge=0, le=1_000_000)
    data: dict | None = None


class EventBatch(BaseModel):
    events: list[EventIn] = Field(max_length=1000)


class IngestResult(BaseModel):
    received: int
    stored: int          # fewer than received means some were duplicates (already stored)


class DeviceCreate(BaseModel):
    id: str = Field(pattern=r"^[a-z0-9][a-z0-9-]{1,62}$")
    name: str = Field(max_length=120)
    kind: Literal["laptop", "phone"]


class DeviceCreated(BaseModel):
    id: str
    secret: str          # shown once; store it in the device's config
    note: str = "Save this secret now. It will not be shown again."


class DeviceOut(BaseModel):
    id: str
    name: str
    kind: str
    revoked: bool
    created_at: datetime
    last_seen: datetime | None
