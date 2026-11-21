"""
Turns raw events into the day summary the dashboard renders.

Output shape (matches the dashboard's sample data, so swapping it in is one fetch()):
{
  "date": "2026-09-20",
  "apps":   [{"n": "VS Code", "d": "laptop", "cat": "Code", "m": 95}, ...],   # minutes, sorted desc
  "phone":  [[start_min, end_min], ...],   # minutes after local midnight
  "laptop": [[start_min, end_min], ...],
  "bed": 1454, "wake": 472, "sleep": 458,  # bed = minutes after *previous* midnight
  "steps": 6120, "unlocks": 87, "screen_min": 342
}
"""
from collections import defaultdict
from datetime import date, datetime, time, timedelta, timezone

from sqlalchemy import select
from sqlalchemy.orm import Session

from .config import settings
from .db import Device, Event

MERGE_GAP_MIN = 2          # sessions closer than this are drawn as one block
MIN_SLEEP_GAP_H = 3        # a quiet stretch shorter than this is not treated as sleep


def _local_midnight_utc(d: date) -> datetime:
    """Local midnight of d, as naive UTC (the way times are stored)."""
    return datetime.combine(d, time(0), tzinfo=settings.TZ).astimezone(timezone.utc).replace(tzinfo=None)


def _merge(intervals: list[list[float]], gap: float) -> list[list[float]]:
    out: list[list[float]] = []
    for s, e in sorted(intervals):
        if out and s - out[-1][1] <= gap:
            out[-1][1] = max(out[-1][1], e)
        else:
            out.append([s, e])
    return out


def _events(db: Session, start: datetime, end: datetime):
    # 1 day of look-back catches events that started before `start` but run into it
    q = (select(Event, Device.kind)
         .join(Device, Device.id == Event.device_id)
         .where(Event.started_at >= start - timedelta(days=1), Event.started_at < end))
    return db.execute(q).all()


def _infer_sleep(db: Session, d: date):
    """Longest quiet stretch (no app use on any device, no unlocks) between 18:00 yesterday and 14:00 today."""
    base = _local_midnight_utc(d - timedelta(days=1))        # yesterday 00:00 local
    w_start, w_end = base + timedelta(hours=18), base + timedelta(hours=38)
    active: list[list[float]] = []
    for ev, _kind in _events(db, w_start, w_end):
        s = (ev.started_at - base).total_seconds() / 60
        if ev.type in ("app", "screen_on"):
            active.append([s, s + max(ev.duration_s, 60) / 60])
        elif ev.type == "unlock":
            active.append([s, s + 1])
    if not active:
        return None
    lo, hi = 18 * 60, 38 * 60
    busy = _merge([[max(s, lo), min(e, hi)] for s, e in active if e > lo and s < hi], 0)
    if not busy:
        return None
    best = None
    for (_, prev_end), (next_start, _) in zip(busy, busy[1:]):
        gap = next_start - prev_end
        if gap >= MIN_SLEEP_GAP_H * 60 and (best is None or gap > best[1] - best[0]):
            best = (prev_end, next_start)
    if best is None:
        return None
    bed, wake_abs = best
    return {"bed": round(bed), "wake": round(wake_abs - 1440), "sleep": round(wake_abs - bed)}


def daily_summary(db: Session, d: date) -> dict:
    start = _local_midnight_utc(d)
    end = _local_midnight_utc(d + timedelta(days=1))
    day_min = (end - start).total_seconds() / 60           # 1380/1440/1500 around DST

    per_app: dict[tuple[str, str], dict] = defaultdict(lambda: {"m": 0.0, "cat": None})
    sessions: dict[str, list[list[float]]] = {"phone": [], "laptop": []}
    steps = 0.0
    unlocks = 0

    for ev, kind in _events(db, start, end):
        ev_start = (ev.started_at - start).total_seconds() / 60
        ev_end = ev_start + ev.duration_s / 60
        if ev.type == "app":
            s, e = max(ev_start, 0), min(ev_end, day_min)
            if e <= s:
                continue
            key = (ev.app or "Unknown", kind)
            per_app[key]["m"] += e - s
            per_app[key]["cat"] = per_app[key]["cat"] or ev.category
            sessions.setdefault(kind, []).append([s, e])
        elif 0 <= ev_start < day_min:
            if ev.type == "steps":
                steps += ev.value or 0
            elif ev.type == "unlock":
                unlocks += 1

    apps = sorted(
        ({"n": name, "d": kind, "cat": v["cat"] or "Other", "m": round(v["m"])}
         for (name, kind), v in per_app.items() if v["m"] >= 0.5),
        key=lambda a: a["m"], reverse=True)

    out = {
        "date": d.isoformat(),
        "apps": apps,
        "phone": [[round(s, 1), round(e, 1)] for s, e in _merge(sessions.get("phone", []), MERGE_GAP_MIN)],
        "laptop": [[round(s, 1), round(e, 1)] for s, e in _merge(sessions.get("laptop", []), MERGE_GAP_MIN)],
        "steps": round(steps),
        "unlocks": unlocks,
        "screen_min": sum(a["m"] for a in apps),
        "bed": None, "wake": None, "sleep": None,
    }
    sleep = _infer_sleep(db, d)
    if sleep:
        out.update(sleep)
    return out


def top_apps(db: Session, end: date, days: int) -> list[dict]:
    totals: dict[tuple[str, str], float] = defaultdict(float)
    for i in range(days):
        for a in daily_summary(db, end - timedelta(days=i))["apps"]:
            totals[(a["n"], a["d"])] += a["m"]
    return [{"n": n, "d": k, "m": round(m)}
            for (n, k), m in sorted(totals.items(), key=lambda x: x[1], reverse=True)]
