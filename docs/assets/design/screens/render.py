#!/usr/bin/env python3
"""Render docs/assets/design/screens/*.html to docs/assets/screens/*.png.

Uses headless Chrome (local), then verifies geometry with PIL.
Run:  python docs/assets/design/screens/render.py [screen-name ...]
"""
import os
import subprocess
import sys
from pathlib import Path

from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]          # repo root
OUT_DIR = ROOT / "docs" / "assets" / "screens"
CHROME = Path("C:/Program Files/Google/Chrome/Application/chrome.exe")

WIDTH, HEIGHT = 1600, 1100
MAX_BYTES = 1_200_000


def render(html: Path, out: Path) -> None:
    url = "file:///" + html.as_posix()
    cmd = [
        str(CHROME),
        "--headless=new",
        "--disable-gpu",
        "--hide-scrollbars",
        "--force-device-scale-factor=1",
        "--allow-file-access-from-files",
        "--virtual-time-budget=4000",
        f"--screenshot={out.as_posix()}",
        f"--window-size={WIDTH},{HEIGHT}",
        url,
    ]
    subprocess.run(cmd, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def verify(path: Path) -> tuple[bool, str]:
    size = path.stat().st_size
    with Image.open(path) as im:
        w, h = im.size
        extrema = im.convert("RGB").getextrema()
    ok = w == WIDTH and h >= 900 and h <= 1200 and size < MAX_BYTES and any(hi > 8 for _, hi in extrema)
    return ok, f"{w}x{h} {size/1024:.0f} KB"


def main() -> int:
    names = sys.argv[1:]
    pages = sorted(HERE.glob("*.html"))
    if names:
        pages = [p for p in pages if any(n in p.name for n in names)]
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    failed = 0
    for page in pages:
        out = OUT_DIR / (page.stem + ".png")
        render(page, out)
        ok, info = verify(out)
        print(("OK   " if ok else "FAIL ") + f"{out.relative_to(ROOT)}  {info}")
        failed += 0 if ok else 1
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())