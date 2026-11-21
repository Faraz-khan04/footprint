"""
Footprint laptop agent.

Every POLL seconds it asks the OS which window is in front and how long since your last
keyboard/mouse input. Consecutive identical samples are merged into one event
(the "heartbeat" idea from ActivityWatch), so you get one row per session, not one per poll.
Events go into a local SQLite queue first and a background thread syncs them to your API,
so nothing is lost when the server or network is down.

Supports: Windows (no extra deps), Linux on X11 (needs xdotool + xprintidle), macOS (osascript).
"""
import ctypes
import hashlib
import hmac
import json
import logging
import os
import platform
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import psutil
import requests

HERE = Path(__file__).resolve().parent
log = logging.getLogger("footprint")

# ---------------------------------------------------------------- config
DEFAULTS = {
    "api_url": "http://127.0.0.1:8000",
    "device_id": "",
    "device_secret": "",
    "poll_seconds": 5,
    "idle_seconds": 180,        # no input for this long = you're away, stop counting
    "sync_seconds": 60,
    "max_event_seconds": 300,   # split long sessions so the dashboard stays fresh
    "send_titles": True,
    # windows whose app or title contains any of these never send their title
    "redact": ["keepass", "bitwarden", "1password", "incognito", "inprivate", "private browsing", "bank"],
    # apps you never want logged at all
    "ignore": ["lockapp", "screensaver"],
}


def load_config() -> dict:
    cfg = dict(DEFAULTS)
    path = Path(os.getenv("FOOTPRINT_CONFIG", HERE / "config.json"))
    if path.exists():
        cfg.update(json.loads(path.read_text(encoding="utf-8-sig")))  # utf-8-sig: tolerate the BOM Windows PowerShell adds
    for key in ("api_url", "device_id", "device_secret"):
        env = os.getenv("FOOTPRINT_" + key.upper())
        if env:
            cfg[key] = env
    if not cfg["device_id"] or not cfg["device_secret"]:
        sys.exit("Missing device_id / device_secret. Register the device first (see README) and put them in config.json.")
    return cfg


# ---------------------------------------------------------------- friendly names
APP_NAMES = {
    "code": "VS Code", "cursor": "Cursor", "chrome": "Chrome", "msedge": "Edge", "firefox": "Firefox",
    "brave": "Brave", "windowsterminal": "Terminal", "cmd": "Terminal", "powershell": "Terminal",
    "pwsh": "Terminal", "gnome-terminal-server": "Terminal", "iterm2": "Terminal", "wireshark": "Wireshark",
    "explorer": "File Explorer", "spotify": "Spotify", "discord": "Discord", "telegram": "Telegram",
    "whatsapp": "WhatsApp", "winword": "Word", "excel": "Excel", "powerpnt": "PowerPoint",
    "notion": "Notion", "obsidian": "Obsidian", "vlc": "VLC", "virtualbox": "VirtualBox",
    "vmware": "VMware", "burpsuite": "Burp Suite", "pycharm64": "PyCharm",
}
CATEGORIES = {
    "VS Code": "Code", "Cursor": "Code", "Terminal": "Code", "PyCharm": "Code",
    "Wireshark": "Security", "Burp Suite": "Security", "VirtualBox": "Security", "VMware": "Security",
    "Chrome": "Browser", "Edge": "Browser", "Firefox": "Browser", "Brave": "Browser",
    "Spotify": "Music", "VLC": "Video", "Discord": "Chat", "Telegram": "Chat", "WhatsApp": "Chat",
    "Word": "Docs", "Excel": "Docs", "PowerPoint": "Docs", "Notion": "Docs", "Obsidian": "Docs",
}


def friendly(process_name: str) -> str:
    key = process_name.lower().removesuffix(".exe").removesuffix(".app")
    return APP_NAMES.get(key, process_name.removesuffix(".exe").title())


# ---------------------------------------------------------------- OS probes
SYSTEM = platform.system()


def _proc_name(pid: int) -> str:
    try:
        return psutil.Process(pid).name()
    except (psutil.NoSuchProcess, psutil.AccessDenied):
        return "unknown"


if SYSTEM == "Windows":
    user32, kernel32 = ctypes.windll.user32, ctypes.windll.kernel32

    class LASTINPUTINFO(ctypes.Structure):
        _fields_ = [("cbSize", ctypes.c_uint), ("dwTime", ctypes.c_uint)]

    def active_window():
        hwnd = user32.GetForegroundWindow()
        if not hwnd:
            return None
        n = user32.GetWindowTextLengthW(hwnd)
        buf = ctypes.create_unicode_buffer(n + 1)
        user32.GetWindowTextW(hwnd, buf, n + 1)
        pid = ctypes.c_ulong()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        return _proc_name(pid.value), buf.value

    def idle_seconds():
        lii = LASTINPUTINFO(cbSize=ctypes.sizeof(LASTINPUTINFO))
        user32.GetLastInputInfo(ctypes.byref(lii))
        return ((kernel32.GetTickCount() - lii.dwTime) & 0xFFFFFFFF) / 1000

elif SYSTEM == "Linux":
    if os.getenv("WAYLAND_DISPLAY") and not os.getenv("DISPLAY"):
        sys.exit("Wayland session detected. This agent needs X11 (log in with an 'X11/Xorg' session).")
    for tool in ("xdotool", "xprintidle"):
        if not shutil.which(tool):
            sys.exit(f"Missing '{tool}'. Install it: sudo apt install xdotool xprintidle")

    def _run(*args):
        return subprocess.run(args, capture_output=True, text=True, timeout=2).stdout.strip()

    def active_window():
        wid = _run("xdotool", "getactivewindow")
        if not wid:
            return None
        pid = _run("xdotool", "getwindowpid", wid)
        return _proc_name(int(pid)) if pid.isdigit() else "unknown", _run("xdotool", "getwindowname", wid)

    def idle_seconds():
        out = _run("xprintidle")
        return int(out) / 1000 if out.isdigit() else 0

elif SYSTEM == "Darwin":
    _AS = ('tell application "System Events" to set p to first application process whose frontmost is true\n'
           'set n to name of p\nset t to ""\n'
           'try\nset t to name of front window of p\nend try\nreturn n & "\t" & t')

    def active_window():
        out = subprocess.run(["osascript", "-e", _AS], capture_output=True, text=True, timeout=3).stdout.rstrip("\n")
        if not out:
            return None
        name, _, title = out.partition("\t")
        return name, title

    def idle_seconds():
        out = subprocess.run(["ioreg", "-c", "IOHIDSystem"], capture_output=True, text=True, timeout=3).stdout
        for line in out.splitlines():
            if "HIDIdleTime" in line:
                return int(line.split("=")[-1].strip()) / 1e9
        return 0
else:
    sys.exit(f"Unsupported OS: {SYSTEM}")


# ---------------------------------------------------------------- local queue
class Queue:
    def __init__(self, path: Path):
        self.lock = threading.Lock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("CREATE TABLE IF NOT EXISTS q (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
        self.db.commit()

    def put(self, event: dict):
        with self.lock:
            self.db.execute("INSERT INTO q (payload) VALUES (?)", (json.dumps(event),))
            self.db.commit()

    def peek(self, n: int):
        with self.lock:
            return self.db.execute("SELECT id, payload FROM q ORDER BY id LIMIT ?", (n,)).fetchall()

    def drop(self, ids):
        with self.lock:
            self.db.executemany("DELETE FROM q WHERE id = ?", [(i,) for i in ids])
            self.db.commit()

    def size(self) -> int:
        with self.lock:
            return self.db.execute("SELECT COUNT(*) FROM q").fetchone()[0]


# ---------------------------------------------------------------- sync
def sign(secret: str, ts: int, body: bytes) -> str:
    return hmac.new(secret.encode(), f"{ts}.".encode() + body, hashlib.sha256).hexdigest()


def sync_loop(cfg: dict, queue: Queue, stop: threading.Event):
    url = cfg["api_url"].rstrip("/") + "/v1/events"
    backoff = cfg["sync_seconds"]
    while not stop.wait(backoff):
        rows = queue.peek(500)
        if not rows:
            backoff = cfg["sync_seconds"]
            continue
        body = json.dumps({"events": [json.loads(p) for _, p in rows]}, separators=(",", ":")).encode()
        ts = int(time.time())
        headers = {"Content-Type": "application/json", "X-Device-Id": cfg["device_id"],
                   "X-Timestamp": str(ts), "X-Signature": sign(cfg["device_secret"], ts, body)}
        try:
            r = requests.post(url, data=body, headers=headers, timeout=15)
            if r.status_code == 200:
                queue.drop([i for i, _ in rows])
                log.info("synced %s events (%s new)", len(rows), r.json().get("stored"))
                backoff = 1 if len(rows) == 500 else cfg["sync_seconds"]   # drain fast after being offline
            elif r.status_code in (401, 403):
                log.error("server rejected credentials (%s): %s. Check device id/secret and your clock.", r.status_code, r.text)
                backoff = 300
            elif r.status_code == 422:
                log.error("server rejected batch as invalid, dropping it: %s", r.text[:500])
                queue.drop([i for i, _ in rows])
            else:
                log.warning("sync failed %s, will retry", r.status_code)
                backoff = min(backoff * 2, 600)
        except requests.RequestException as e:
            log.warning("server unreachable (%s); %s events waiting", e.__class__.__name__, queue.size())
            backoff = min(backoff * 2, 600)


# ---------------------------------------------------------------- tracking
@dataclass
class Current:
    app: str
    title: str | None
    start: datetime
    last: datetime


def main():
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    cfg = load_config()
    queue = Queue(HERE / "queue.db")
    stop = threading.Event()
    threading.Thread(target=sync_loop, args=(cfg, queue, stop), daemon=True).start()

    poll = cfg["poll_seconds"]
    redact = [r.lower() for r in cfg["redact"]]
    ignore = [i.lower() for i in cfg["ignore"]]
    cur: Current | None = None

    def flush(c: Current | None):
        if c is None:
            return
        duration = (c.last - c.start).total_seconds() + poll   # last sample covers one poll interval
        if duration < poll:
            return
        queue.put({
            "client_id": uuid.uuid4().hex,
            "type": "app",
            "app": c.app,
            "title": c.title,
            "category": CATEGORIES.get(c.app, "Other"),
            "started_at": c.start.isoformat(),
            "duration_s": round(duration, 1),
        })

    log.info("tracking on %s, syncing to %s as '%s'", SYSTEM, cfg["api_url"], cfg["device_id"])
    try:
        while True:
            now = datetime.now(timezone.utc)
            win = None
            try:
                if idle_seconds() < cfg["idle_seconds"]:
                    win = active_window()
            except Exception as e:                  # never let one bad probe kill the agent
                log.debug("probe failed: %s", e)

            if win:
                proc, title = win
                app = friendly(proc)
                hay = f"{proc} {title}".lower()
                if any(i in hay for i in ignore):
                    win = None
                else:
                    if not cfg["send_titles"] or any(r in hay for r in redact):
                        title = None
                    title = (title or None) and title[:500]

            if win:
                gap = (now - cur.last).total_seconds() if cur else None
                same = cur and cur.app == app and cur.title == title and gap <= poll * 2 + 1
                too_long = cur and (now - cur.start).total_seconds() >= cfg["max_event_seconds"]
                if same and not too_long:
                    cur.last = now
                else:
                    flush(cur)
                    cur = Current(app, title, now, now)
            else:
                flush(cur)
                cur = None
            time.sleep(poll)
    except KeyboardInterrupt:
        flush(cur)
        log.info("stopped; %s events still queued for next run", queue.size())
        stop.set()


if __name__ == "__main__":
    main()
