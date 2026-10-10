#!/usr/bin/env python3
"""
Generates the API-key guide animations (app/src/main/res/raw):

  key_guide_<provider>.json  15 s, silent, 30 fps: a schematic of the
      provider's key page in a browser window; a hand taps "Sign in", then
      the provider's own "create key" button, then Copy; the key is saved.
      The app plays all of it as the video guide, and frames 90-345 (the
      create + copy part) on a loop as the hint above the in-app browser.
  key_saved.json  2.5 s: the big green check shown when a key is captured.

These are drawings, not screenshots: real dashboards change often and their
pages are not ours to ship. The button wording matches each provider's site.

    python3 tools/key_guide_lottie.py <fonts dir> <material icons dir> app/src/main/res/raw

<fonts dir>: NotoSans-Bold.ttf, NotoSans-Regular.ttf (Noto, SIL OFL).
<material icons dir>: touch_app.svg and content_copy.svg from
https://github.com/google/material-design-icons (Apache 2.0).
"""
import json
import math
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lottie_kit import (  # noqa: E402
    anim, composition, ellipse, fill, gradient, group, layer, path, rect, rgb, star, stroke, svg_shapes,
    text_shapes, trim,
)

FR = 30
OP = 450  # 15 s
W, H = 400, 260

PROVIDERS = {
    "gemini": {"host": "aistudio.google.com", "button": "Create API key", "key": "AIza••••••••", "accent": "#1A73E8"},
    "openai": {"host": "platform.openai.com", "button": "Create new secret key", "key": "sk-proj-••••••", "accent": "#10A37F"},
    "anthropic": {"host": "console.anthropic.com", "button": "Create Key", "key": "sk-ant-••••••", "accent": "#D97757"},
    "groq": {"host": "console.groq.com", "button": "Create API Key", "key": "gsk_••••••••", "accent": "#F55036"},
}

GREEN = "#22C55E"


def svg_path(file):
    """The drawing path of a Material icon file (skipping its transparent 24 x 24 box)."""
    data = open(file).read()
    paths = [d for d in re.findall(r'<path[^>]* d="([^"]+)"', data) if not d.startswith("M0 0h24v24H0z")]
    return " ".join(paths)


def L(name, shapes, **kw):
    return layer(name, shapes, op=OP, **kw)


def window(fonts, host):
    """A light browser window: title bar with three dots and the site's address."""
    bold = os.path.join(fonts, "NotoSans-Regular.ttf")
    url, _ = text_shapes(bold, host, 10)
    return [
        group(url + [fill(rgb("#5F6368"))], "url", p=(200, 33)),
        group([rect(170, 16, x=200, y=33, r=8), fill(rgb("#FFFFFF"))], "url bar"),
        group([ellipse(8, 8, 40, 33), fill(rgb("#FF5F57"))]),
        group([ellipse(8, 8, 54, 33), fill(rgb("#FEBC2E"))]),
        group([ellipse(8, 8, 68, 33), fill(rgb("#28C840"))]),
        group([rect(372, 28, x=200, y=33, r=0), fill(rgb("#ECEAF2"))], "title bar"),
        group([rect(372, 236, x=200, y=138, r=16), fill(rgb("#FFFFFF"))], "page"),
        group([rect(376, 240, x=200, y=140, r=18), fill(rgb("#000000"), 12)], "shadow"),
    ]


def button(fonts, label, accent, x, y, min_w=0):
    text, width = text_shapes(os.path.join(fonts, "NotoSans-Bold.ttf"), label, 13)
    w = max(min_w, width + 34)
    return [group(text + [fill(rgb("#FFFFFF"))], "label", p=(x, y)),
            group([rect(w, 34, x=x, y=y, r=17), fill(rgb(accent))], "button")]


def hand(icons, frames, target, start=(340, 250), tap_at=None, visible=(0, OP)):
    """The pointing hand (Material "touch_app"): moves to [target], taps at [tap_at]."""
    shapes = [group(svg_shapes(svg_path(os.path.join(icons, "touch_app.svg")), 58) +
                    [fill(rgb("#2B1B6B")), stroke(rgb("#FFFFFF"), 2.5)], "hand")]
    t0, t1 = frames
    p = anim([(0, list(start)), (t0, list(start)), (t1, list(target)), (OP, list(target))])
    scale = [(0, [100, 100])]
    if tap_at is not None:
        scale += [(tap_at, [100, 100]), (tap_at + 4, [84, 84]), (tap_at + 9, [100, 100])]
    scale += [(OP, [100, 100])]
    v0, v1 = visible
    o = anim([(0, 0), (max(v0, 0), 0), (v0 + 6, 100), (v1 - 6, 100), (v1, 0), (OP, 0)] if v0 > 0 else
             [(0, 100), (v1 - 6, 100), (v1, 0), (OP, 0)])
    # The fingertip (11.5, 5 in the 24-unit icon) is the layer's anchor, so p is where it touches.
    return L("hand", shapes, p=p, a=((11.5 - 12) * 58 / 24, (5 - 12) * 58 / 24), s=anim(scale), o=o)


def ripple(accent, at, x, y, size=120):
    s = anim([(0, [0, 0]), (at, [0, 0]), (at + 16, [100, 100]), (OP, [100, 100])])
    o = anim([(0, 0), (at - 1, 0), (at, 55), (at + 16, 0), (OP, 0)])
    return L("ripple", [group([ellipse(size, size), fill(rgb(accent))])], p=(x, y), s=s, o=o)


def fade(t_in, t_out, length=8):
    keys = [(0, 0 if t_in > 0 else 100)]
    if t_in > 0:
        keys += [(t_in, 0), (t_in + length, 100)]
    keys += [(t_out, 100), (t_out + length, 0), (OP, 0)]
    return anim(keys)


def guide(fonts, icons, provider):
    cfg = PROVIDERS[provider]
    accent = cfg["accent"]
    bold = os.path.join(fonts, "NotoSans-Bold.ttf")
    layers = [L("window", window(fonts, cfg["host"]), o=fade(0, 338))]

    # A (0-3 s): sign in.
    initial, _ = text_shapes(bold, provider[0].upper(), 20)
    layers.append(L("sign in", [
        group(initial + [fill(rgb("#FFFFFF"))], "initial", p=(200, 92)),
        group([ellipse(44, 44, 200, 92), fill(rgb(accent))], "badge"),
        group([rect(150, 8, x=200, y=128, r=4), fill(rgb("#E3E1EA"))], "line"),
    ] + button(fonts, "Sign in", accent, 200, 168, min_w=150), o=fade(0, 82)))
    layers.append(ripple(accent, 50, 200, 168))
    layers.append(hand(os.path.join(icons), (12, 42), (210, 172), tap_at=48, visible=(0, 86)))

    # B (3-6 s): the dashboard and its "create key" button.
    lines = [group([rect(46, 8, x=58, y=70 + n * 22, r=4), fill(rgb("#E3E1EA"))]) for n in range(6)]
    content = [group([rect(w, 8, x=118 + w / 2, y=118 + n * 20, r=4), fill(rgb("#ECEAF2"))])
               for n, w in enumerate((220, 180, 240, 150, 200))]
    layers.append(L("dashboard", lines + content + [
        group([rect(110, 12, x=173, y=72, r=6), fill(rgb("#CFCBDB"))], "title"),
        group([rect(1, 200, x=96, y=146), fill(rgb("#ECEAF2"))], "divider"),
    ] + button(fonts, cfg["button"], accent, 268, 98), o=fade(84, 338)))
    layers.append(ripple(accent, 136, 268, 98))
    layers.append(hand(icons, (100, 128), (276, 104), tap_at=134, visible=(92, 176)))

    # C (5.3-11.5 s): the new key, and Copy.
    layers.append(L("scrim", [group([rect(372, 208, x=200, y=152, r=0), fill(rgb("#000000"))])],
                    o=anim([(0, 0), (158, 0), (168, 32), (338, 32), (346, 0), (OP, 0)])))
    key_text, _ = text_shapes(bold, cfg["key"], 13)
    copy_icon = svg_shapes(svg_path(os.path.join(icons, "content_copy.svg")), 18)
    check = [path([(-6, 0), (-1.5, 4.5), (6, -4)]), stroke(rgb("#FFFFFF"), 2.5)]
    copied_text, copied_w = text_shapes(bold, "Copied", 11)
    dialog = [
        group(copied_text + [fill(rgb("#FFFFFF"))], "copied text", p=(210, 182)),
        group(check, "copied check", p=(210 - copied_w / 2 - 10, 182)),
        group([rect(copied_w + 44, 24, x=203, y=182, r=12), fill(rgb(GREEN))], "copied chip"),
    ]
    layers.append(L("dialog", [
        group(copy_icon + [fill(rgb("#FFFFFF"))], "copy icon", p=(298, 140)),
        group([rect(34, 34, x=298, y=140, r=10), fill(rgb(accent))], "copy button"),
        group(key_text + [fill(rgb("#3C3A44"))], "key", p=(176, 140)),
        group([rect(196, 34, x=176, y=140, r=8), fill(rgb("#F1F0F5"))], "key field"),
        group([rect(130, 10, x=150, y=104, r=5), fill(rgb("#CFCBDB"))], "dialog title"),
        group([rect(276, 132, x=200, y=146, r=16), fill(rgb("#FFFFFF"))], "card"),
    ], p=(200, 146), a=(200, 146),
        s=anim([(0, [80, 80]), (160, [80, 80]), (172, [100, 100]), (OP, [100, 100])]),
        o=anim([(0, 0), (160, 0), (170, 100), (338, 100), (346, 0), (OP, 0)])))
    layers.append(ripple(accent, 232, 298, 140, size=70))
    # Above the dialog card: the green "Copied" chip after the tap.
    layers.append(L("copied", dialog, o=anim([(0, 0), (240, 0), (246, 100), (338, 100), (346, 0), (OP, 0)])))
    layers.append(hand(icons, (192, 224), (304, 146), tap_at=230, visible=(184, 338)))

    # D (11.5-15 s): saved.
    layers += success_layers(center=(200, 120), scale=0.62, t0=350, op=OP)
    return composition(f"API key guide: {provider}", layers, op=OP, fr=FR, w=W, h=H)


def success_layers(center, scale, t0, op):
    """A green disc pops, a white check draws itself, confetti bursts out."""
    cx, cy = center
    k = scale
    out = []
    confetti = ["#F2A7D8", "#7FD4FF", "#FFD27A", "#C9B8FF", "#7BE0A8", "#FF8A80"]
    for i in range(12):
        a = i * 2 * math.pi / 12 + 0.2
        r = (150 + (i % 3) * 14) * k
        end = [cx + math.cos(a) * r, cy + math.sin(a) * r]
        p = anim([(0, [cx, cy]), (t0 + 8, [cx, cy]), (t0 + 34, end), (op, end)])
        o = anim([(0, 0), (t0 + 7, 0), (t0 + 8, 100), (t0 + 30, 100), (t0 + 44, 0), (op, 0)])
        shape = ellipse(14 * k, 14 * k) if i % 2 else rect(12 * k, 12 * k, r=3 * k)
        out.append(layer(f"confetti {i}", [group([shape, fill(rgb(confetti[i % len(confetti)]))])],
                         p=p, o=o, r=anim([(0, 0), (op, 180 + i * 20)], linear=True), op=op))
    ring_s = anim([(0, [40, 40]), (t0 + 6, [40, 40]), (t0 + 26, [150, 150]), (op, [150, 150])])
    ring_o = anim([(0, 0), (t0 + 5, 0), (t0 + 6, 80), (t0 + 26, 0), (op, 0)])
    out.append(layer("ring", [group([ellipse(190 * k, 190 * k), stroke(rgb(GREEN), 6 * k)])],
                     p=(cx, cy), s=ring_s, o=ring_o, op=op))
    disc_s = anim([(0, [0, 0]), (t0, [0, 0]), (t0 + 12, [115, 115]), (t0 + 18, [100, 100]), (op, [100, 100])])
    out.append(layer("disc", [
        group([path([(-38 * k, 2 * k), (-12 * k, 28 * k), (42 * k, -30 * k)]),
               trim(0, anim([(0, 0), (t0 + 14, 0), (t0 + 32, 100), (op, 100)])),
               stroke(rgb("#FFFFFF"), 18 * k)], "check"),
        group([ellipse(180 * k, 180 * k), gradient([(0, "#4ADE80"), (1, "#16A34A")], (-90 * k, -90 * k), (90 * k, 90 * k))],
              "disc"),
    ], p=(cx, cy), s=disc_s, op=op))
    return out


def key_saved():
    op = 75
    layers = success_layers(center=(200, 200), scale=1.0, t0=0, op=op)
    for i, (x, y) in enumerate([(92, 96), (318, 92), (330, 300), (70, 296)]):
        t = 14 + i * 6
        sc = anim([(0, [0, 0]), (t, [0, 0]), (t + 12, [100, 100]), (t + 28, [0, 0]), (op, [0, 0])])
        layers.append(layer(f"sparkle {i}", [group([star(4, 18, 4), fill(rgb("#FFD27A"))])], p=(x, y), s=sc, op=op))
    return composition("API key saved", layers, op=op, fr=FR, w=400, h=400)


def main():
    fonts, icons, out = sys.argv[1], sys.argv[2], sys.argv[3]
    os.makedirs(out, exist_ok=True)
    files = {f"key_guide_{p}": guide(fonts, icons, p) for p in PROVIDERS}
    files["key_saved"] = key_saved()
    for name, data in files.items():
        with open(os.path.join(out, name + ".json"), "w") as f:
            json.dump(data, f, separators=(",", ":"))
        print(name, os.path.getsize(os.path.join(out, name + ".json")), "bytes")


if __name__ == "__main__":
    main()
