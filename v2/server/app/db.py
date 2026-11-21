from datetime import datetime, timezone

from sqlalchemy import (JSON, Boolean, DateTime, Float, ForeignKey, Index, String, Text,
                        UniqueConstraint, create_engine)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column, sessionmaker

from .config import settings

connect_args = {"check_same_thread": False} if settings.DATABASE_URL.startswith("sqlite") else {}
engine = create_engine(settings.DATABASE_URL, connect_args=connect_args, pool_pre_ping=True)
SessionLocal = sessionmaker(bind=engine, autoflush=False, expire_on_commit=False)


def utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


class Base(DeclarativeBase):
    pass


class Device(Base):
    __tablename__ = "devices"
    id: Mapped[str] = mapped_column(String(64), primary_key=True)      # e.g. "laptop-main"
    name: Mapped[str] = mapped_column(String(120))
    kind: Mapped[str] = mapped_column(String(16))                       # "laptop" | "phone"
    secret: Mapped[str] = mapped_column(String(128))                    # HMAC key (see README threat model)
    revoked: Mapped[bool] = mapped_column(Boolean, default=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)
    last_seen: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)


class Event(Base):
    """One thing that happened. All times are stored as naive UTC."""
    __tablename__ = "events"
    id: Mapped[int] = mapped_column(primary_key=True, autoincrement=True)
    device_id: Mapped[str] = mapped_column(ForeignKey("devices.id"), index=True)
    client_id: Mapped[str] = mapped_column(String(64))                  # generated on the device; makes retries safe
    type: Mapped[str] = mapped_column(String(16))                       # app | steps | unlock | screen_on | screen_off
    app: Mapped[str | None] = mapped_column(String(200), nullable=True)
    title: Mapped[str | None] = mapped_column(String(500), nullable=True)
    category: Mapped[str | None] = mapped_column(String(40), nullable=True)
    started_at: Mapped[datetime] = mapped_column(DateTime)
    duration_s: Mapped[float] = mapped_column(Float, default=0)
    value: Mapped[float | None] = mapped_column(Float, nullable=True)  # e.g. step count
    data: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    received_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)

    __table_args__ = (
        UniqueConstraint("device_id", "client_id", name="uq_device_client"),
        Index("ix_events_started", "started_at"),
        Index("ix_events_type_started", "type", "started_at"),
    )


class AppIcon(Base):
    """Latest icon for each app, keyed by the app's display name (what the dashboard shows)."""
    __tablename__ = "app_icons"
    app: Mapped[str] = mapped_column(String(200), primary_key=True)
    package: Mapped[str | None] = mapped_column(String(200), nullable=True)
    png_b64: Mapped[str] = mapped_column(Text)
    updated_at: Mapped[datetime] = mapped_column(DateTime, default=utcnow)


def init_db() -> None:
    Base.metadata.create_all(engine)


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()
