#!/usr/bin/env python3
"""
Generates the onboarding's Lottie animations (app/src/main/res/raw/onboarding_*.json).

Everything is drawn from code: shapes, gradients and letters. The letters of
the "book into languages" animation are vector outlines taken from the Noto
fonts (SIL Open Font License), so no images or third-party animation files
are needed.

    pip install fonttools
    python3 tools/onboarding_lottie.py <fonts dir> app/src/main/res/raw

<fonts dir> must hold NotoSans-Bold.ttf, NotoSansDevanagari-Bold.ttf,
NotoSansArabic-Bold.ttf, NotoSansTamil-Bold.ttf and NotoSansJP.ttf (variable),
from https://github.com/notofonts and https://github.com/google/fonts.
"""
import json
import math
import os
import sys

from fontTools.pens.basePen import BasePen
from fontTools.ttLib import TTFont

W = H = 400
FPS = 60
OP = 240  # 4 s loop

# --------------------------------------------------------------- primitives


def rgb(hex_color, alpha=1.0):
    h = hex_color.lstrip("#")
    return [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)] + [alpha]


def static(v):
    return {"a": 0, "k": v}


EASE_OUT = {"x": [0.33], "y": [0]}
EASE_IN = {"x": [0.67], "y": [1]}
LIN_OUT = {"x": [0], "y": [0]}
LIN_IN = {"x": [1], "y": [1]}


def anim(frames, linear=False):
    """frames: [(t, value)]; eased (or linear) between keyframes."""
    out = []
    for n, (t, v) in enumerate(frames):
        k = {"t": t, "s": v if isinstance(v, list) else [v]}
        if n < len(frames) - 1:
            k["o"] = LIN_OUT if linear else EASE_OUT
            k["i"] = LIN_IN if linear else EASE_IN
        out.append(k)
    return {"a": 1, "k": out}


def prop(v):
    return v if isinstance(v, dict) else static(v)


def tr(p=(0, 0), a=(0, 0), s=(100, 100), r=0, o=100):
    return {"ty": "tr", "p": prop(list(p)), "a": prop(list(a)), "s": prop(list(s) if not isinstance(s, dict) else s),
            "r": prop(r), "o": prop(o), "sk": static(0), "sa": static(0)}


def group(items, name="g", **t):
    return {"ty": "gr", "nm": name, "it": items + [tr(**t)]}


def rect(w, h, x=0, y=0, r=0):
    return {"ty": "rc", "d": 1, "s": static([w, h]), "p": static([x, y]), "r": static(r)}


def ellipse(w, h, x=0, y=0):
    return {"ty": "el", "d": 1, "s": static([w, h]), "p": static([x, y])}


def fill(color, o=100):
    return {"ty": "fl", "c": prop(color), "o": prop(o), "r": 1}


def stroke(color, w, o=100, dashes=None):
    s = {"ty": "st", "c": prop(color), "o": prop(o), "w": prop(w), "lc": 2, "lj": 2, "ml": 4}
    if dashes:
        s["d"] = [{"n": "d", "nm": "dash", "v": static(dashes[0])}, {"n": "g", "nm": "gap", "v": static(dashes[1])}]
    return s


def gradient(stops, start, end, radial=False, alphas=None):
    """stops: [(offset, '#rrggbb')], alphas: [(offset, alpha)]."""
    k = []
    for off, c in stops:
        k += [off] + rgb(c)[:3]
    for off, a in alphas or []:
        k += [off, a]
    g = {"ty": "gf", "o": static(100), "r": 1, "s": static(list(start)), "e": static(list(end)),
         "t": 2 if radial else 1, "g": {"p": len(stops), "k": static(k)}}
    if radial:
        g["h"] = static(0)
        g["a"] = static(0)
    return g


def path(points, closed=False):
    return {"ty": "sh", "ks": static({"c": closed, "v": [list(p) for p in points],
                                      "i": [[0, 0] for _ in points], "o": [[0, 0] for _ in points]})}


def trim(s, e):
    return {"ty": "tm", "s": prop(s), "e": prop(e), "o": static(0), "m": 1}


def star(points, outer, inner, x=0, y=0, rotation=0):
    return {"ty": "sr", "sy": 1, "d": 1, "pt": static(points), "p": static([x, y]), "r": static(rotation),
            "ir": static(inner), "is": static(0), "or": static(outer), "os": static(0)}


def layer(name, shapes, p=(0, 0), a=(0, 0), s=(100, 100), r=0, o=100):
    return {"ddd": 0, "ty": 4, "nm": name, "sr": 1, "ao": 0, "ip": 0, "op": OP, "st": 0, "bm": 0,
            "ks": {"p": prop(list(p) + [0] if not isinstance(p, dict) else p),
                   "a": prop(list(a) + [0]),
                   "s": prop(list(s) + [100] if not isinstance(s, dict) else s),
                   "r": prop(r), "o": prop(o)},
            "shapes": shapes}


def composition(name, layers):
    # Lottie draws the first layer on top: callers list layers bottom-up, so reverse.
    layers = list(reversed(layers))
    for i, l in enumerate(layers):
        l["ind"] = i + 1
    return {"v": "5.7.4", "fr": FPS, "ip": 0, "op": OP, "w": W, "h": H, "nm": name, "ddd": 0,
            "assets": [], "layers": layers, "markers": []}


def glow(color, size, x, y, alpha, period=120):
    g = group([ellipse(size, size), gradient([(0, color), (1, color)], (0, 0), (size / 2, 0), radial=True,
                                             alphas=[(0, alpha), (1, 0)])], "glow")
    s = anim([(0, [92, 92]), (period // 2, [106, 106]), (period, [92, 92]),
              (period + period // 2, [106, 106]), (2 * period, [92, 92])])
    return layer("glow", [g], p=(x, y), s=s)


# ----------------------------------------------------------- font outlines

class LottiePen(BasePen):
    def __init__(self, glyph_set):
        super().__init__(glyph_set)
        self.contours = []
        self.cur = None

    def _moveTo(self, p):
        self.cur = {"v": [p], "i": [(0, 0)], "o": [(0, 0)]}

    def _lineTo(self, p):
        c = self.cur
        c["v"].append(p)
        c["i"].append((0, 0))
        c["o"].append((0, 0))

    def _curveToOne(self, p1, p2, p3):
        c = self.cur
        last = c["v"][-1]
        c["o"][-1] = (p1[0] - last[0], p1[1] - last[1])
        c["v"].append(p3)
        c["i"].append((p2[0] - p3[0], p2[1] - p3[1]))
        c["o"].append((0, 0))

    def _closePath(self):
        c = self.cur
        if c and len(c["v"]) > 1 and math.dist(c["v"][-1], c["v"][0]) < 1e-6:
            c["i"][0] = c["i"][-1]
            c["v"].pop()
            c["i"].pop()
            c["o"].pop()
        if c:
            self.contours.append(c)
        self.cur = None

    _endPath = _closePath


def glyph_shapes(font_path, char, size, location=None):
    """The glyph for [char] as Lottie paths, [size] px tall (em), centred on (0, 0)."""
    font = TTFont(font_path)
    glyph_set = font.getGlyphSet(location=location) if location else font.getGlyphSet()
    name = font.getBestCmap()[ord(char)]
    pen = LottiePen(glyph_set)
    glyph_set[name].draw(pen)
    scale = size / font["head"].unitsPerEm
    xs = [v[0] for c in pen.contours for v in c["v"]]
    ys = [v[1] for c in pen.contours for v in c["v"]]
    cx, cy = (min(xs) + max(xs)) / 2, (min(ys) + max(ys)) / 2
    shapes = []
    for c in pen.contours:
        shapes.append({"ty": "sh", "ks": static({
            "c": True,
            "v": [[round((x - cx) * scale, 2), round(-(y - cy) * scale, 2)] for x, y in c["v"]],
            "i": [[round(x * scale, 2), round(-y * scale, 2)] for x, y in c["i"]],
            "o": [[round(x * scale, 2), round(-y * scale, 2)] for x, y in c["o"]],
        })})
    return shapes


# ------------------------------------------------- 1. a book into languages

def book_animation(fonts):
    white, page_tint, line = rgb("#FFFFFF"), rgb("#F3EEFF"), rgb("#CBC1EC")
    layers = [glow("#C9B8FF", 360, 200, 230, 0.45)]

    # Shadow and cover.
    layers.append(layer("shadow", [group([ellipse(250, 30), fill(rgb("#000000"), 35)])], p=(200, 352)))
    layers.append(layer("cover", [group([rect(250, 160, r=16),
                                         gradient([(0, "#7C5CFF"), (1, "#3A1873")], (-125, -80), (125, 80))])],
                        p=(200, 272)))

    def page(x_center, color, name):
        lines = [group([rect(76 if n < 5 else 48, 6, x=(0 if n < 5 else -14), y=-48 + n * 17, r=3),
                        fill(line)], "line") for n in range(6)]
        return [group(lines, "lines", p=(x_center, 0)),
                group([rect(112, 140, x=x_center, r=8), fill(color)], "paper")]

    # Static left and right pages.
    layers.append(layer("pages", page(-56, page_tint, "left") + page(56, white, "right") +
                        [group([rect(3, 140), fill(rgb("#D6CCF5"))], "spine")], p=(200, 265)))

    # Three pages flipping right to left (a page is mirrored around the spine).
    for n, start in enumerate((12, 92, 172)):
        flip = anim([(0, [100, 100]), (start, [100, 100]), (start + 46, [-100, 100]), (OP, [-100, 100])])
        color = anim([(0, white), (start, white), (start + 23, rgb("#D9CCFF")), (start + 46, page_tint),
                      (OP, page_tint)])
        layers.append(layer(f"flip {n}", page(56, color, "flip"), p=(200, 265), s=flip))

    # Letters of seven scripts burst out of the book and float above it.
    glyphs = [
        ("A", "NotoSans-Bold.ttf", None, "#C9B8FF"),
        ("अ", "NotoSansDevanagari-Bold.ttf", None, "#F2A7D8"),
        ("あ", "NotoSansJP.ttf", {"wght": 700}, "#7FD4FF"),
        ("ع", "NotoSansArabic-Bold.ttf", None, "#FFD27A"),
        ("Ж", "NotoSans-Bold.ttf", None, "#7BE0A8"),
        ("中", "NotoSansJP.ttf", {"wght": 700}, "#C9B8FF"),
        ("அ", "NotoSansTamil-Bold.ttf", None, "#F2A7D8"),
    ]
    targets = [(58, 196), (84, 116), (142, 64), (206, 44), (270, 64), (326, 116), (350, 196)]
    origin = [200, 250]
    for i, ((char, font, location, color), (tx, ty)) in enumerate(zip(glyphs, targets)):
        t0 = 22 + i * 14
        out = t0 + 48
        fade = 188 + i * 4
        shapes = [group(glyph_shapes(os.path.join(fonts, font), char, 34, location) + [fill(rgb(color))], "glyph"),
                  group([rect(54, 54, r=16), fill(white, 14), stroke(white, 1.5, 30)], "tile")]
        p = anim([(0, origin), (t0, origin), (out, [tx, ty]), (fade, [tx, ty - 10]), (fade + 20, [tx, ty - 22]),
                  (OP, [tx, ty - 22])])
        s = anim([(0, [30, 30]), (t0, [30, 30]), (out, [100, 100]), (OP, [100, 100])])
        o = anim([(0, 0), (t0, 0), (t0 + 16, 100), (fade, 100), (fade + 20, 0), (OP, 0)])
        layers.append(layer(f"glyph {char}", shapes, p=p, s=s, o=o))
    return composition("Book into languages", layers)


# ------------------------------------------------------- 2. glowing AI chip

def chip_animation(fonts):
    cyan, white, lavender = rgb("#7FD4FF"), rgb("#FFFFFF"), rgb("#C9B8FF")
    layers = [glow("#8B5CF6", 380, 200, 200, 0.55)]

    traces = [
        [(125, 165), (72, 165), (72, 104)],
        [(275, 165), (328, 165), (328, 104)],
        [(275, 235), (328, 235), (328, 296)],
        [(125, 235), (72, 235), (72, 296)],
    ]
    nodes = [(72, 92), (328, 92), (328, 308), (72, 308)]
    for i, (pts, (nx, ny)) in enumerate(zip(traces, nodes)):
        w0 = i * 60
        layers.append(layer(f"trace {i}", [group([path(pts), stroke(white, 3, 25)])]))
        s = anim([(0, 0), (w0, 0), (w0 + 14, 0), (w0 + 56, 100), (OP, 100)])
        e = anim([(0, 0), (w0, 0), (w0 + 42, 100), (OP, 100)])
        layers.append(layer(f"pulse {i}", [group([path(pts), trim(s, e), stroke(cyan, 5)])]))
        layers.append(layer(f"node {i}", [group([ellipse(22, 22), fill(white, 30)])], p=(nx, ny)))
        arrive = w0 + 40
        o = anim([(0, 0), (max(arrive - 1, 0), 0), (arrive + 6, 100), (arrive + 50, 100), (arrive + 70, 0), (OP, 0)])
        sc = anim([(0, [60, 60]), (max(arrive - 1, 0), [60, 60]), (arrive + 10, [130, 130]), (arrive + 30, [100, 100]),
                   (OP, [100, 100])])
        layers.append(layer(f"engine {i}", [
            group([ellipse(22, 22), fill(cyan)]),
            group([ellipse(40, 40), gradient([(0, "#7FD4FF"), (1, "#7FD4FF")], (0, 0), (20, 0), radial=True,
                                             alphas=[(0, 0.7), (1, 0)])]),
        ], p=(nx, ny), s=sc, o=o))

    # Pins on every side.
    pins = []
    for k in (-44, -22, 0, 22, 44):
        pins += [group([rect(6, 18, x=k, y=-84, r=3), fill(lavender, 75)]),
                 group([rect(6, 18, x=k, y=84, r=3), fill(lavender, 75)]),
                 group([rect(18, 6, x=-84, y=k, r=3), fill(lavender, 75)]),
                 group([rect(18, 6, x=84, y=k, r=3), fill(lavender, 75)])]
    breathe = anim([(0, [98, 98]), (60, [102, 102]), (120, [98, 98]), (180, [102, 102]), (OP, [98, 98])])
    letters = (glyph_shapes(os.path.join(fonts, "NotoSans-Bold.ttf"), "A", 46) +
               [fill(white)])
    letter_i = glyph_shapes(os.path.join(fonts, "NotoSans-Bold.ttf"), "I", 46) + [fill(white)]
    die_glow = anim([(0, 30), (60, 100), (120, 30), (180, 100), (OP, 30)])
    layers.append(layer("chip", [
        group([group(letters, "A", p=(-12, 0)), group(letter_i, "I", p=(18, 0))], "AI"),
        group([rect(94, 94, r=20), stroke(cyan, 2.5, die_glow)], "die glow"),
        group([rect(94, 94, r=20), gradient([(0, "#3B2A8F"), (1, "#160A3D")], (0, 0), (66, 0), radial=True)], "die"),
        group([rect(124, 36, y=-48, r=14), fill(white, 12)], "gloss"),
        group([rect(150, 150, r=30), stroke(white, 2, 35)], "edge"),
        group([rect(150, 150, r=30), gradient([(0, "#7C5CFF"), (1, "#2B1B6B")], (-75, -75), (75, 75))], "body"),
    ] + pins, p=(200, 200), s=breathe))
    return composition("Glowing AI chip", layers)


# -------------------------------------------------- 3. recommended badge

def badge_animation(fonts):
    white, indigo = rgb("#FFFFFF"), rgb("#1C0B4B")
    layers = [glow("#FFD27A", 360, 200, 190, 0.4)]
    layers.append(layer("ring", [group([ellipse(250, 250), stroke(rgb("#FFD27A"), 3, 55, dashes=(14, 16))])],
                        p=(200, 190), r=anim([(0, 0), (OP, 360)], linear=True)))
    ribbon_l = [(-34, 40), (-58, 132), (-34, 118), (-14, 140), (-2, 56)]
    ribbon_r = [(-x, y) for x, y in ribbon_l]
    layers.append(layer("ribbons", [group([path(ribbon_l, True), fill(rgb("#C27C0E"))]),
                                    group([path(ribbon_r, True), fill(rgb("#E0A030"))])], p=(200, 190)))
    pulse = anim([(0, [96, 96]), (60, [104, 104]), (120, [96, 96]), (180, [104, 104]), (OP, [96, 96])])
    layers.append(layer("badge", [
        group([star(5, 40, 17), fill(indigo)], "star"),
        group([ellipse(140, 140), stroke(white, 3, 60)], "inner ring"),
        group([ellipse(172, 172), stroke(white, 3, 70)], "edge"),
        group([ellipse(172, 172), gradient([(0, "#FFE29A"), (1, "#F59E0B")], (-86, -86), (86, 86))], "disc"),
    ], p=(200, 180), s=pulse))
    for i, (x, y) in enumerate([(92, 92), (318, 104), (334, 262), (76, 268)]):
        t0 = i * 60
        sc = anim([(0, [0, 0]), (t0, [0, 0]), (t0 + 24, [100, 100]), (t0 + 52, [0, 0]), (OP, [0, 0])])
        layers.append(layer(f"sparkle {i}", [group([star(4, 16, 4), fill(white if i % 2 else rgb("#FFE29A"))])],
                            p=(x, y), s=sc, r=anim([(0, 0), (OP, 90)], linear=True)))
    return composition("Recommended badge", layers)


def main():
    fonts, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for name, build in (("onboarding_book", book_animation), ("onboarding_chip", chip_animation),
                        ("onboarding_badge", badge_animation)):
        data = build(fonts)
        with open(os.path.join(out, name + ".json"), "w") as f:
            json.dump(data, f, separators=(",", ":"))
        print(name, os.path.getsize(os.path.join(out, name + ".json")), "bytes")


if __name__ == "__main__":
    main()
