"""Regenerates app icons and splash screens for web, Android and iOS from one design.
Run from bdo-visit-tracker/:  python3 scripts/make-native-assets.py   (needs Pillow)"""
from pathlib import Path
from PIL import Image, ImageDraw

BRAND = '#0b3d6e'
RES = Path('android/app/src/main/res')
IOS = Path('ios/App/App/Assets.xcassets')


def glyph(d: ImageDraw.ImageDraw, x0: float, y0: float, size: float):
    """Clipboard + tick, drawn in a size x size box (512-unit design grid)."""
    k = size / 512
    P = lambda x, y: (x0 + x * k, y0 + y * k)
    d.rounded_rectangle([*P(128, 112), *P(384, 416)], radius=24 * k, fill='white')
    d.rounded_rectangle([*P(176, 88), *P(336, 144)], radius=16 * k, fill='#f2b630')
    for y, x2 in ((216, 336), (272, 336), (328, 272)):
        d.line([P(176, y), P(x2, y)], fill=BRAND, width=max(2, round(24 * k)))
    d.ellipse([*P(296, 304), *P(400, 408)], fill='#1f9d55')
    d.line([P(324, 356), P(342, 374), P(374, 340)], fill='white', width=max(2, round(16 * k)))


def icon(size: int, shape: str = 'rounded', alpha: bool = True) -> Image.Image:
    im = Image.new('RGBA', (size, size), (0, 0, 0, 0) if alpha else BRAND)
    d = ImageDraw.Draw(im)
    if shape == 'rounded':
        d.rounded_rectangle([0, 0, size - 1, size - 1], radius=round(size * 0.1875), fill=BRAND)
    elif shape == 'circle':
        d.ellipse([0, 0, size - 1, size - 1], fill=BRAND)
    else:
        d.rectangle([0, 0, size, size], fill=BRAND)
    glyph(d, 0, 0, size)
    return im


def splash(w: int, h: int) -> Image.Image:
    im = Image.new('RGB', (w, h), BRAND)
    s = min(w, h) // 3
    glyph(ImageDraw.Draw(im), (w - s) / 2, (h - s) / 2, s)
    return im


# Web / PWA
for s in (192, 512):
    icon(s).save(f'public/icon-{s}.png')
icon(180, 'square', alpha=False).convert('RGB').save('public/apple-touch-icon.png')  # iOS rounds it itself

# Android launcher icons
for dpi, px in {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}.items():
    icon(px).save(RES / f'mipmap-{dpi}/ic_launcher.png')
    icon(px, 'circle').save(RES / f'mipmap-{dpi}/ic_launcher_round.png')
    fg = round(px * 108 / 48)  # adaptive icon layer is 108dp; artwork stays in the 66dp safe zone
    layer = Image.new('RGBA', (fg, fg), (0, 0, 0, 0))
    art = round(fg * 72 / 108)
    glyph(ImageDraw.Draw(layer), (fg - art) / 2, (fg - art) / 2, art)
    layer.save(RES / f'mipmap-{dpi}/ic_launcher_foreground.png')
(RES / 'values/ic_launcher_background.xml').write_text(
    '<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">#0B3D6E</color>\n</resources>\n')
for f in RES.glob('drawable*/splash.png'):
    w, h = Image.open(f).size
    splash(w, h).save(f)

# iOS (App Store icons must not have transparency)
if IOS.exists():
    icon(1024, 'square', alpha=False).convert('RGB').save(IOS / 'AppIcon.appiconset/AppIcon-512@2x.png')
    for f in (IOS / 'Splash.imageset').glob('*.png'):
        splash(*Image.open(f).size).save(f)
print('assets written')
