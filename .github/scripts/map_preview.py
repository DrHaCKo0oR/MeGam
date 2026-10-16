#!/usr/bin/env python3
"""Renders the app's map page in desktop Chrome with sample pins and saves a screenshot.

The CI emulator cannot draw WebView content, so this shows the same page (same Leaflet
build, styles and pin markup) in Chrome with real map tiles. The page is started the way
the app starts it: initMap with the work pin, then setData with pins.
"""
import json
import pathlib
import shutil
import subprocess
import sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/assets/map"
OUT = ROOT / "build/map-preview"

WORK = {"lat": 24.7655, "lng": 46.6802}
PINS = [
    {"id": "a", "lat": 24.7712, "lng": 46.6935, "label": "45k", "color": "#1B7F4B", "z": 500},
    {"id": "b", "lat": 24.7588, "lng": 46.6651, "label": "48k", "color": "#1B7F4B", "z": 500},
    {"id": "c", "lat": 24.7801, "lng": 46.6720, "label": "50k", "color": "#1B7F4B", "z": 500},
    {"id": "d", "lat": 24.7520, "lng": 46.6950, "label": "?", "color": "#B8893B", "z": 100},
    {"id": "e", "lat": 24.7695, "lng": 46.6505, "label": "52k", "color": "#3A5A8C", "z": 100},
]


def main() -> int:
    if OUT.exists():
        shutil.rmtree(OUT)
    shutil.copytree(SRC, OUT)
    page = (OUT / "map.html").read_text(encoding="utf-8")
    start = (
        "<script>\n"
        f"initMap({json.dumps({**WORK, 'zoom': 13, 'key': None, 'lang': 'en', 'pick': False})});\n"
        f"setData({json.dumps({'work': WORK, 'pins': PINS, 'fit': False})});\n"
        "</script>\n</body>"
    )
    (OUT / "map.html").write_text(page.replace("</body>", start, 1), encoding="utf-8")

    shot = ROOT / "qa/browser-map.png"
    shot.parent.mkdir(parents=True, exist_ok=True)
    chrome = shutil.which("google-chrome") or shutil.which("chromium") or shutil.which("chromium-browser")
    if not chrome:
        print("no Chrome found")
        return 0
    cmd = [
        chrome, "--headless=new", "--no-sandbox", "--hide-scrollbars", "--window-size=412,760",
        "--virtual-time-budget=20000", "--allow-file-access-from-files",
        f"--screenshot={shot}", (OUT / "map.html").as_uri(),
    ]
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=120)
    print(result.stdout[-2000:], result.stderr[-2000:])
    print("saved" if shot.exists() else "no screenshot", shot)
    return 0


if __name__ == "__main__":
    sys.exit(main())
