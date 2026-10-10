#!/usr/bin/env python3
"""
A small kit for writing Lottie animations from code: shapes, gradients,
keyframes, trim paths, and vector outlines from fonts (letters, words) and
SVG paths (icons). Used by key_guide_lottie.py.

    pip install fonttools
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


def layer(name, shapes, p=(0, 0), a=(0, 0), s=(100, 100), r=0, o=100, op=None):
    return {"ddd": 0, "ty": 4, "nm": name, "sr": 1, "ao": 0, "ip": 0, "op": op or OP, "st": 0, "bm": 0,
            "ks": {"p": prop(list(p) + [0] if not isinstance(p, dict) else p),
                   "a": prop(list(a) + [0]),
                   "s": prop(list(s) + [100] if not isinstance(s, dict) else s),
                   "r": prop(r), "o": prop(o)},
            "shapes": shapes}


def composition(name, layers, op=None, fr=None, w=None, h=None):
    # Lottie draws the first layer on top: callers list layers bottom-up, so reverse.
    layers = list(reversed(layers))
    for i, l in enumerate(layers):
        l["ind"] = i + 1
    return {"v": "5.7.4", "fr": fr or FPS, "ip": 0, "op": op or OP, "w": w or W, "h": h or H, "nm": name, "ddd": 0,
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


def contour_shapes(contours, transform):
    """Pen contours as Lottie paths; [transform] maps a point (x, y) -> (x', y') and a tangent likewise."""
    point, vector = transform
    shapes = []
    for c in contours:
        shapes.append({"ty": "sh", "ks": static({
            "c": True,
            "v": [[round(v, 2) for v in point(x, y)] for x, y in c["v"]],
            "i": [[round(v, 2) for v in vector(x, y)] for x, y in c["i"]],
            "o": [[round(v, 2) for v in vector(x, y)] for x, y in c["o"]],
        })})
    return shapes


def text_shapes(font_path, text, size, location=None):
    """[text] in one line as Lottie paths, [size] px em, centred on (0, 0) (by advance width and cap height)."""
    font = TTFont(font_path)
    glyph_set = font.getGlyphSet(location=location) if location else font.getGlyphSet()
    cmap = font.getBestCmap()
    scale = size / font["head"].unitsPerEm
    contours, x = [], 0
    for ch in text:
        if ch == " ":
            x += glyph_set[cmap[32]].width
            continue
        name = cmap[ord(ch)]
        pen = LottiePen(glyph_set)
        glyph_set[name].draw(pen)
        for c in pen.contours:
            c["v"] = [(px + x, py) for px, py in c["v"]]
            contours.append(c)
        x += glyph_set[name].width
    cap = getattr(font["OS/2"], "sCapHeight", 0) or font["head"].unitsPerEm * 0.7
    cx, cy = x / 2, cap / 2
    return contour_shapes(contours, (lambda px, py: (( px - cx) * scale, -(py - cy) * scale),
                                     lambda px, py: (px * scale, -py * scale))), x * scale


def svg_shapes(d, size, viewbox=24):
    """An SVG path (e.g. a Material icon, 24 x 24 viewBox) as Lottie paths, [size] px, centred on (0, 0)."""
    from fontTools.svgLib.path import parse_path
    pen = LottiePen(None)
    parse_path(d, pen)
    k = size / viewbox
    return contour_shapes(pen.contours, (lambda x, y: ((x - viewbox / 2) * k, (y - viewbox / 2) * k),
                                         lambda x, y: (x * k, y * k)))


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
