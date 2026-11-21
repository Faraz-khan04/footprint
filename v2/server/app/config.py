"""Settings, read from environment variables (or a .env file you source yourself)."""
import os
from zoneinfo import ZoneInfo


class Settings:
    # SQLite by default; set e.g. postgresql+psycopg://user:pass@localhost/footprint for Postgres
    DATABASE_URL: str = os.getenv("DATABASE_URL", "sqlite:///./footprint.db")

    # Used to register/revoke devices. Long random string. Never ships to a device.
    ADMIN_TOKEN: str = os.getenv("ADMIN_TOKEN", "")
    # Used by the dashboard to read summaries. Separate from ADMIN_TOKEN so a leaked
    # dashboard token can read data but can't add devices or write events.
    READ_TOKEN: str = os.getenv("READ_TOKEN", "")

    # Your local timezone decides where "a day" starts and ends.
    TZ: ZoneInfo = ZoneInfo(os.getenv("FOOTPRINT_TZ", "Asia/Kolkata"))

    # Signed requests older/newer than this (seconds) are rejected (replay window).
    MAX_CLOCK_SKEW: int = int(os.getenv("MAX_CLOCK_SKEW", "300"))

    # Window titles leak email subjects, file names, chat names. Wipe them after N days,
    # keep only app + duration forever.
    TITLE_RETENTION_DAYS: int = int(os.getenv("TITLE_RETENTION_DAYS", "30"))

    # Comma-separated origins allowed to call the API from a browser (dashboard served elsewhere).
    CORS_ORIGINS: list[str] = [o for o in os.getenv("CORS_ORIGINS", "").split(",") if o]


settings = Settings()
