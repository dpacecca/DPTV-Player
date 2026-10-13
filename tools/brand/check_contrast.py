"""Contrast check for the Sportio Stage neutrals (the layer StageTokens.kt adds on top of M3).
M3 has no 'dim'/'body'/'navText' role, so these live in their own table and need their own proof."""
import sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

def lin(c):
    c /= 255.0
    return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

def lum(h):
    h = h.lstrip("#")
    r, g, b = int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16)
    return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)

def cr(a, b):
    la, lb = lum(a), lum(b)
    return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)

def tag(r):
    return "AAA" if r >= 7 else ("AA" if r >= 4.5 else ("AA-LG" if r >= 3 else "FAIL"))

PAGE_D, PAGE_L = "#060B07", "#F4F8F5"
S1D, S4D = "#101A13", "#28402F"      # a list row / a raised card, dark
S1L, S4L = "#FFFFFF", "#DCE6DF"

DARK = [
    ("text",        "#F1F6F3", 4.5), ("muted",   "#A9B8AF", 4.5),
    ("dim",         "#7E9C88", 3.0), ("itemText",  "#D8E2DC", 4.5),
    ("menuItemText","#DEE7E1", 4.5), ("tagText",  "#C6D3CC", 4.5),
    ("body",        "#CBD6D0", 4.5), ("navText",  "#BCCBC2", 4.5),
    ("fxLine",      "#DCE9E2", 4.5), ("knobOff",  "#CFD8D4", 3.0),
    ("ok",          "#7BE3A4", 4.5), ("warn",     "#FFB86B", 4.5),
    ("danger",      "#FF8A7A", 4.5), ("chipWarnText", "#FFD27A", 4.5),
]
LIGHT = [
    ("text",        "#0F1A16", 4.5), ("muted",   "#4C5A54", 4.5),
    ("dim",         "#5E7566", 3.0), ("itemText",  "#1C2824", 4.5),
    ("menuItemText","#1C2824", 4.5), ("tagText",  "#3D4B45", 4.5),
    ("body",        "#2D3A35", 4.5), ("navText",  "#55635D", 4.5),
    ("fxLine",      "#2D3A35", 4.5),
    # The four below are NOT upstream's. Upstream's #2B8A3E / #9A5A08 / #D23B2E / #FFCC4D reach
    # only 3.4-4.5:1 on these surfaces; these were re-solved at AA 4.5 by hue-preserving HSL search.
    ("ok",          "#1B743C", 4.5), ("warn",    "#8B5904", 4.5),
    ("danger",      "#BD2C1F", 4.5), ("chipWarnText", "#8B5904", 4.5),
]

def report(title, rows, page, raised, raised_label):
    print("=" * 72)
    print(title)
    print("=" * 72)
    bad = 0
    for name, hexv, need in rows:
        r1, r2 = cr(hexv, page), cr(hexv, raised)
        ok = r1 >= need and r2 >= need
        if not ok: bad += 1
        print(f"  {name:<14} {hexv}  page {r1:6.2f} {tag(r1):<5}  "
              f"{raised_label} {r2:6.2f} {tag(r2):<5}  need {need}  {'ok' if ok else '*** FAIL ***'}")
    print(f"  failures: {bad}\n")
    return bad

b = report("STAGE DARK  (page #060B07, raised #28402F)", DARK, PAGE_D, S4D, "raised")
b += report("STAGE LIGHT (page #F4F7F5, raised #DCE6DF)", LIGHT, PAGE_L, S4L, "raised")

print("Rating star - a different value per theme (upstream keeps one, which fails on light):")
for name, val, bgs in [("dark ", "#FFCC4D", (PAGE_D, "#16241B")),
                       ("light", "#906A09", (PAGE_L, "#FFFFFF"))]:
    for bg in bgs:
        r = cr(val, bg)
        ok = r >= 4.5
        if not ok: b += 1
        print(f"  {name} {val} on {bg}  {r:6.2f}  {tag(r):<5} {'ok' if ok else '*** FAIL ***'}")
print()
print("overall failures:", b)
sys.exit(1 if b else 0)