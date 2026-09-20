#!/usr/bin/env python3
"""Generate the lumnik PWA's per-app icon pairs (192/512), per the formula in
docs/superpowers/specs/2026-09-17-kind-app-runtime-registration-design.md's "Icon set"
section: solid ground #14121f, a solid circle at radius 0.30*size in the app's own colour, and
a linear halo toward --accent (#8b7ff0) from 32% mix at the circle's edge down to 0% at
0.385*size.

Usage:
  python3 scripts/gen-app-icons.py                 # (re)generate the 4 new identifiers
  python3 scripts/gen-app-icons.py event alert      # generate only the named ones
  python3 scripts/gen-app-icons.py --verify         # regenerate clients/stock IN MEMORY and
                                                     # diff against the shipped files — this
                                                     # is what proves the formula, run on
                                                     # demand like check-fqn-hygiene.py
                                                     # --selftest, not a CI gate.

Never overwrites icon-192.png / icon-stock-192.png / their -512 siblings outside --verify —
those shipped before this script existed and this PR does not touch them.
"""
import sys
from pathlib import Path

from PIL import Image

APP_DIR = Path(__file__).resolve().parent.parent / \
    "lumnik-hub/src/main/resources/META-INF/resources/app"

GROUND = (0x14, 0x12, 0x1F)
ACCENT = (0x8B, 0x7F, 0xF0)
SUPERSAMPLE = 4
CIRCLE_FRACTION = 0.30
HALO_FRACTION = 0.385
EDGE_MIX = 0.32

# identifier -> (circle colour, generated filenames). clients/stock are listed only for
# --verify; NEW_IDENTIFIERS (below) excludes them from a plain run.
ICONS = {
    "clients":  {"color": (0xF5, 0xB8, 0x3D), "files": ["icon-192.png", "icon-512.png"]},
    "stock":    {"color": (0x6F, 0xD3, 0x9A), "files": ["icon-stock-192.png", "icon-stock-512.png"]},
    "generic":  {"color": (0x9A, 0x94, 0xB8), "files": ["icon-generic-192.png", "icon-generic-512.png"]},
    "event":    {"color": (0xB7, 0x9C, 0xF5), "files": ["icon-event-192.png", "icon-event-512.png"]},
    "alert":    {"color": (0xF0, 0x79, 0x6B), "files": ["icon-alert-192.png", "icon-alert-512.png"]},
    "contract": {"color": (0x6F, 0xB6, 0xE8), "files": ["icon-contract-192.png", "icon-contract-512.png"]},
}
NEW_IDENTIFIERS = ["generic", "event", "alert", "contract"]


def _mix(a, b, t):
    return tuple(round(a[i] + (b[i] - a[i]) * t) for i in range(3))


def render(size: int, circle_color) -> Image.Image:
    ss = size * SUPERSAMPLE
    img = Image.new("RGB", (ss, ss), GROUND)
    px = img.load()
    cx = cy = ss / 2
    r_circle = CIRCLE_FRACTION * ss
    r_halo = HALO_FRACTION * ss
    for y in range(ss):
        dy = y - cy + 0.5
        for x in range(ss):
            dx = x - cx + 0.5
            r = (dx * dx + dy * dy) ** 0.5
            if r <= r_circle:
                px[x, y] = circle_color
            elif r <= r_halo:
                t = EDGE_MIX * (1 - (r - r_circle) / (r_halo - r_circle))
                px[x, y] = _mix(GROUND, ACCENT, t)
    return img.resize((size, size), Image.LANCZOS)


def generate(names, out_dir: Path):
    for name in names:
        spec = ICONS[name]
        for filename in spec["files"]:
            size = 512 if "512" in filename else 192
            render(size, spec["color"]).save(out_dir / filename)
            print(f"wrote {out_dir / filename}")


def verify() -> bool:
    """Regenerate clients/stock in memory and diff against the shipped files. The mean
    per-channel difference stays under 3/255 for a faithful reproduction — a handful of
    single-pixel-wide edge/antialiasing differences are expected and harmless; a wrong formula
    shifts every pixel, which this threshold catches."""
    ok = True
    for name in ("clients", "stock"):
        spec = ICONS[name]
        for filename in spec["files"]:
            size = 512 if "512" in filename else 192
            shipped = Image.open(APP_DIR / filename).convert("RGB")
            regenerated = render(size, spec["color"])
            a, b = list(shipped.getdata()), list(regenerated.getdata())
            mean = sum(abs(pa[i] - pb[i]) for pa, pb in zip(a, b) for i in range(3)) / (len(a) * 3)
            status = "OK" if mean < 3.0 else "FAIL"
            if status == "FAIL":
                ok = False
            print(f"{filename}: mean per-channel diff {mean:.3f} — {status}")
    return ok


if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--verify"]:
        sys.exit(0 if verify() else 1)
    names = [a for a in args if not a.startswith("--")] or NEW_IDENTIFIERS
    unknown = [n for n in names if n not in ICONS]
    if unknown:
        sys.exit(f"unknown icon identifier(s): {', '.join(unknown)} — known: {', '.join(ICONS)}")
    generate(names, APP_DIR)
