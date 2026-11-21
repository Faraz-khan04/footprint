#!/usr/bin/env python3
"""
Checks the home-screen widget's layouts against what RemoteViews actually allows.

A widget layout is inflated by the launcher, not by the app, so anything outside the supported
list throws and the launcher shows "Can't load widget" with no clue why. This catches that
before it reaches the phone.

Run: python3 android/tools/widgetlint/check.py
"""
import re, sys, pathlib

# https://developer.android.com/develop/ui/views/appwidgets#CreatingLayout
ALLOWED_VIEWS = {
    "FrameLayout", "LinearLayout", "RelativeLayout", "GridLayout",
    "AnalogClock", "Button", "Chronometer", "ImageButton", "ImageView",
    "ProgressBar", "TextView", "ViewFlipper", "ListView", "GridView",
    "StackView", "AdapterViewFlipper", "ViewStub",
}

ROOT = pathlib.Path(__file__).resolve().parents[2]
LAYOUTS = sorted((ROOT / "app/src/main/res/layout").glob("widget_*.xml"))

problems = []
for f in LAYOUTS:
    text = f.read_text()
    for view in sorted(set(re.findall(r'<([A-Z][A-Za-z]*)', text))):
        if view not in ALLOWED_VIEWS:
            problems.append(f"{f.name}: <{view}> is not allowed in a widget layout")

# Attributes RemoteViews cannot set at runtime, if any code tries to
for kt in sorted((ROOT / "app/src/main/java").rglob("*.kt")):
    src = kt.read_text()
    for call, attr in re.findall(r'\.set(?:Float|Int|Boolean|String)\([^,]+,\s*"(\w+)"', src):
        problems.append(f"{kt.name}: sets \"{attr}\" reflectively; only @RemotableViewMethod setters work")

if not LAYOUTS:
    print("no widget layouts found"); sys.exit(1)
print(f"checked {len(LAYOUTS)} widget layout(s): {', '.join(f.name for f in LAYOUTS)}")
if problems:
    for p in problems: print("FAIL", p)
    sys.exit(1)
print("widget layouts use only supported views")
