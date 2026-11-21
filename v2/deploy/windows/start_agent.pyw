"""Starts the laptop agent at login (pythonw.exe, no console window). Logs go to logs/agent.log."""
import os
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AGENT = ROOT / "agent"
LOGS = ROOT / "logs"
LOGS.mkdir(exist_ok=True)

log_path = LOGS / "agent.log"
if log_path.exists() and log_path.stat().st_size > 5_000_000:
    log_path.replace(LOGS / "agent.old.log")
sys.stdout = sys.stderr = open(log_path, "a", buffering=1, encoding="utf-8")

os.environ.setdefault("FOOTPRINT_CONFIG", str(AGENT / "config.json"))
os.chdir(AGENT)
sys.path.insert(0, str(AGENT))

import footprint_agent  # noqa: E402

footprint_agent.main()
