"""
Starts the Footprint API. Task Scheduler runs this at login with pythonw.exe (no console window).
Listens on all interfaces so the phone can reach it over its hotspot. The Windows firewall
rule created by setup.ps1 only lets in devices on the same local network.
"""
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "server"
LOGS = ROOT / "logs"
LOGS.mkdir(exist_ok=True)

log_path = LOGS / "server.log"
if log_path.exists() and log_path.stat().st_size > 5_000_000:
    log_path.replace(LOGS / "server.old.log")
sys.stdout = sys.stderr = open(log_path, "a", buffering=1, encoding="utf-8")

env_file = SERVER / ".env"
for line in env_file.read_text(encoding="utf-8-sig").splitlines():
    if "=" in line and not line.lstrip().startswith("#"):
        key, value = line.split("=", 1)
        os.environ.setdefault(key.strip(), value.strip())

os.chdir(SERVER)
sys.path.insert(0, str(SERVER))

import uvicorn  # noqa: E402

uvicorn.run("app.main:app", host="0.0.0.0", port=8000, access_log=False)
