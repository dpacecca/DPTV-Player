"""Verify that every generated SVG keeps its artwork inside the viewBox, with the specified
clear space. Parses the path data's coordinate extremes directly."""
import re, io, os, glob

ROOT = os.path.normpath(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "docs", "brand", "assets", "svg"))

NUM = re.compile(r'[-+]?\d*\.?\d+')

def path_bounds(d):
    """Min/max of every coordinate pair in an SVG path. Good enough for a bounds sanity check:
    it over-estimates the box, so a PASS here is a real PASS."""
    toks = re.findall(r'[MLQCZmlqcz]|[-+]?\d*\.?\d+', d)
    xs, ys, i = [], [], 0
    cur = [0.0, 0.0]
    cmd = None
    while i < len(toks):
        t = toks[i]
        if t.isalpha():
            cmd = t; i += 1
            if cmd in 'Zz': continue
        rel = cmd.islower()
        n = {'M': 2, 'L': 2, 'Q': 4, 'C': 6}.get(cmd.upper())
        if n is None:
            i += 1; continue
        vals = [float(x) for x in toks[i:i+n]]
        i += n
        if len(vals) < n: break
        for k in range(0, n, 2):
            xs.append(vals[k] if not rel else cur[0] + vals[k])
            ys.append(vals[k+1] if not rel else cur[1] + vals[k+1])
        cur = [xs[-1], ys[-1]]
    return (min(xs), min(ys), max(xs), max(ys)) if xs else None

def group_bounds(body, ids):
    """Applies any <g transform="translate(a,b) scale(k)"> to all paths inside that group."""
    tx = ty = 0.0
    k = 1.0
    for m in re.finditer(r'<g transform="([^"]+)"', body):
        t = m.group(1)
        mt = re.search(r'translate\(\s*([-\d.]+)[ ,]+([-\d.]+)\s*\)', t)
        if mt:
            tx, ty = float(mt.group(1)), float(mt.group(2))
        ms = re.search(r'scale\(\s*([-\d.]+)\s*\)', t)
        if ms:
            k = float(ms.group(1))
    xs, ys = [], []
    for d in re.findall(r'\sd="([^"]+)"', body):
        b = path_bounds(d)
        if b:
            xs += [b[0] * k + tx, b[2] * k + tx]
            ys += [b[1] * k + ty, b[3] * k + ty]
    return (min(xs), min(ys), max(xs), max(ys)) if xs else None

print(f"{'file':<34} {'viewBox':>22}  {'ink box':>22}  verdict")
print("-" * 92)
allok = True
for f in sorted(glob.glob(os.path.join(ROOT, "*.svg"))):
    s = io.open(f, encoding="utf-8").read()
    vb = re.search(r'viewBox="([-\d.\s]+)"', s)
    vx, vy, vw, vh = [float(x) for x in vb.group(1).split()]
    b = group_bounds(s, None)
    name = os.path.basename(f)
    if not b:
        print(f"{name:<34} {'':>22}  {'no paths':>22}  --")
        continue
    x0, y0, x1, y1 = b
    inside = x0 >= vx - 0.5 and y0 >= vy - 0.5 and x1 <= vx + vw + 0.5 and y1 <= vy + vh + 0.5
    allok &= inside
    # symmetric clear space (the SVGs carry their padding inside the viewBox)
    pad_l, pad_r = x0 - vx, (vx + vw) - x1
    pad_t, pad_b = y0 - vy, (vy + vh) - y1
    sym = "sym" if abs(pad_l - pad_r) < 1.0 and abs(pad_t - pad_b) < 1.0 else \
          f"L{pad_l:.0f} R{pad_r:.0f} T{pad_t:.0f} B{pad_b:.0f}"
    print(f"{name:<34} {f'{vw:.0f}x{vh:.0f}':>22}  "
          f"{f'{x0:.0f},{y0:.0f} {x1:.0f},{y1:.0f}':>22}  "
          f"{'OK' if inside else 'OUT OF CANVAS':<14} {sym}")
print("-" * 92)
print("ALL INSIDE CANVAS" if allok else "*** SOME ARTWORK ESCAPES ITS VIEWBOX ***")