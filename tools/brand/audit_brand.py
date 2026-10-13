"""Audit: does the ramp/palette agree across BRAND_GUIDE, sportio.tokens.json and SportioPalette.kt?

Drift between a prose spec and its machine-readable twins is the classic way a brand guide rots.
This recomputes the authoritative ramp from the colour maths and then diffs all three files.
"""
import re, io, json, os, collections, sys
sys.stdout.reconfigure(encoding="utf-8", errors="replace")

ROOT = r"C:\Users\<user>\OwnTV\docs\brand"
GUIDE = os.path.join(ROOT, "BRAND_GUIDE_SPORTIO.md")
TOKENS = os.path.join(ROOT, "sportio.tokens.json")
KOTLIN = os.path.join(ROOT, "SportioPalette.kt")

# ---- recompute the authoritative ramp (hue 146, stadium-lit turf) ------------
def hsl(h, s, l):
    h, s, l = h % 360, s / 100, l / 100
    c = (1 - abs(2 * l - 1)) * s
    x = c * (1 - abs((h / 60) % 2 - 1))
    m = l - c / 2
    if h < 60:   r, g, b = c, x, 0
    elif h < 120: r, g, b = x, c, 0
    elif h < 180: r, g, b = 0, c, x
    elif h < 240: r, g, b = 0, x, c
    elif h < 300: r, g, b = x, 0, c
    else:          r, g, b = c, 0, x
    return "#%02X%02X%02X" % (round((r + m) * 255), round((g + m) * 255), round((b + m) * 255))

SPEC = {0:(60,97), 10:(64,92), 20:(70,83), 30:(76,72), 40:(82,62), 50:(80,55),
        60:(78,45), 70:(76,36), 80:(72,28), 90:(66,19), 95:(60,12), 100:(55,7)}
RAMP = {t: hsl(146, *SPEC[t]) for t in SPEC}

print("=" * 74)
print("AUTHORITATIVE RAMP  (hue 146)")
print("=" * 74)
for t in sorted(RAMP):
    print(f"  P{t:<4d} {RAMP[t]}")

# ---- collect hexes per file -------------------------------------------------
hexre = re.compile(r"#(?:[0-9A-Fa-f]{6})\b")

guide = io.open(GUIDE, encoding="utf-8").read()
kotlin = io.open(KOTLIN, encoding="utf-8").read()
tokens_raw = io.open(TOKENS, encoding="utf-8").read()
tokens = json.loads(tokens_raw)

def norm(h): return h.upper()

g_hex = collections.Counter(norm(h) for h in hexre.findall(guide))
t_hex = collections.Counter(norm(h) for h in hexre.findall(tokens_raw))
k_hex = collections.Counter(norm(x) for x in re.findall(r"0xFF([0-9A-Fa-f]{6})", kotlin))

# ---- ramp agreement ---------------------------------------------------------
print()
print("=" * 74)
print("RAMP AGREEMENT")
print("=" * 74)
tramp = {}
for k, v in tokens["color"]["ramp"].items():
    m = re.match(r"^P?(\d+)$", k)
    if m:
        tramp[int(m.group(1))] = v.upper()
kramp = {}
for name, val in re.findall(r"const val P(\d+)\s*=\s*0xFF([0-9A-F]{6})L", kotlin):
    kramp[int(name)] = "#" + val.upper()

bad = 0
for t in sorted(RAMP):
    want = RAMP[t].upper()
    a = tramp.get(t, "-")
    b = kramp.get(t, "-")
    ok = (a == want and b == want)
    if not ok:
        bad += 1
    flag = "ok" if ok else "DRIFT"
    print(f"  P{t:<4d} maths={want}  tokens={a:<9} kotlin={b:<9} {flag}")
print(f"\n  ramp drift: {bad}")

# ---- roles agreement -------------------------------------------------------
print()
print("=" * 74)
print("ROLE AGREEMENT  (guide / tokens / kotlin)")
print("=" * 74)
def hexes_of(obj, out, path=""):
    if isinstance(obj, dict):
        for k, v in obj.items(): hexes_of(v, out, f"{path}.{k}")
    elif isinstance(obj, list):
        for i, v in enumerate(obj): hexes_of(v, out, f"{path}[{i}]")
    elif isinstance(obj, str) and re.fullmatch(r"#(?:[0-9A-Fa-f]{6})", obj):
        out[path] = obj.upper()
    return out

roles = {}
hexes_of(tokens["color"], roles)

# A role may live in the M3 table or the Stage table. `dim` is a Stage role: M3 has no such
# slot, which is exactly why it lives in its own block.
def find(path):
    for key in ("." + path,
                "." + path.replace("dark.", "stage.dark."),
                "." + path.replace("light.", "stage.light.")):
        if key in roles:
            return roles[key], key.lstrip(".")
    return "-", "-"

ROLES = [
    ("dark.page",          "#060B07"),
    ("dark.text",          "#EAF7EF"),
    ("dark.muted",         "#A6C3AF"),
    ("dark.dim",           "#7E9C88"),
    ("dark.primary",       "#4FEE94"),
    ("dark.onPrimary",     "#081C11"),
    ("dark.primaryCont",   "#134D2C"),
    ("dark.onPrimaryCont", "#9BF5BE"),
    ("dark.focus",         "#81EEB0"),
    ("dark.glassOff",      "#101614"),
    ("light.page",         "#F4F8F4"),
    ("light.text",         "#081C11"),
    ("light.muted",        "#3C5546"),
    ("light.dim",          "#5E7566"),
    ("light.primary",      "#0E6B41"),
    ("light.onPrimary",    "#FFFFFF"),
    ("light.primaryCont",  "#C8EFD8"),
    ("light.onPrimaryCont","#08301C"),
    ("light.focus",         "#0E6B41"),
    ("stage.dark.ok",      "#7BE3A4"),
    ("stage.light.ok",     "#1B743C"),
    ("stage.light.warn",   "#8B5904"),
    ("stage.light.danger", "#BD2C1F"),
    ("stage.light.ratingStar", "#906A09"),
]
mis = 0
for path, want in ROLES:
    got, where = find(path)
    ing = g_hex.get(want, 0)
    ink = k_hex.get(want[1:], 0)
    ok = got == want and ing > 0 and ink > 0
    if not ok: mis += 1
    print(f"  {path:<26} {want}  tokens={got:<9} ({where.replace('color.','')}) "
          f"guide:{ing:<2} kotlin:{ink:<2} {'ok' if ok else 'CHECK'}")
print(f"\n  role drift: {mis}")

# ---- orphans: hexes the guide uses that no token or constant backs ---------
# Some hexes are SUPPOSED to have no token: gradient stops, the OwnTV comparison value, and the
# four upstream colours §5.5 deliberately rejects. They are listed with their reason so the gate
# stays meaningful instead of being widened into uselessness.
ALLOWED_ORPHANS = {
    "#000000": "mono-black variant",
    "#FFFFFF": "mono-white variant",
    "#0B3D22": "Pokal gradient stop (marketing variant)",
    "#10492B": "Pitch Hero gradient stop",
    "#52DBC8": "OwnTV petrol, cited in the fork comparison table",
    "#2B8A3E": "upstream Stage.Light.Ok - cited in 5.5 as the rejected value",
    "#9A5A08": "upstream Stage.Light.Warn - cited in 5.5 as the rejected value",
    "#D23B2E": "upstream Stage.Light.Danger - cited in 5.5 as the rejected value",
    "#105D2C": "the P90 typo that audit_brand.py caught, quoted in 13.5",
}
print()
print("=" * 74)
print("ORPHAN HEXES  (in the guide, in neither tokens.json nor SportioPalette.kt)")
print("=" * 74)
backed = set(roles.values()) | {v for v in RAMP.values()} | {"#" + h for h in k_hex}
orph = sorted(h for h in g_hex if h not in backed)
unexplained = []
for h in orph:
    if h in ALLOWED_ORPHANS:
        print(f"  {h}  x{g_hex[h]:<3} allowed: {ALLOWED_ORPHANS[h]}")
    else:
        unexplained.append(h)
        ctx = next((l.strip()[:100] for l in guide.splitlines() if h in l), "")
        print(f"  {h}  x{g_hex[h]:<3} *** UNEXPLAINED *** {ctx}")
print(f"\n  orphans: {len(orph)} of which {len(unexplained)} unexplained")

# ---- unused: tokens nobody documents --------------------------------------
print()
print("=" * 74)
print("TOKENS NOT MENTIONED IN THE GUIDE")
print("=" * 74)
unused = sorted({(v, p) for p, v in roles.items() if g_hex.get(v, 0) == 0})
for v, p in unused:
    print(f"  {v}  {p.replace('color.','')}")
print(f"\n  undocumented tokens: {len(unused)}")

sys.exit(1 if (bad or mis or unexplained) else 0)