#!/usr/bin/env python3
"""Draws the launcher icon at every density, plus the page icon. Needs Pillow."""
import math
import os
from PIL import Image, ImageDraw

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
S = 768
INK = (232, 226, 214, 255)
DARK = (16, 20, 24, 255)

im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(im)
d.ellipse((24, 24, S - 24, S - 24), fill=DARK)
# the lens ring
d.ellipse((150, 150, S - 150, S - 150), fill=INK)
d.ellipse((215, 215, S - 215, S - 215), fill=DARK)
# a sun over a shimmering horizon
d.ellipse((S / 2 - 70, S / 2 - 120, S / 2 + 70, S / 2 + 20), fill=(224, 168, 74, 255))
for k, y in enumerate((S / 2 + 40, S / 2 + 80, S / 2 + 118)):
    w = 150 - k * 30
    pts = [(S / 2 - w + i, y + 8 * math.sin(i / 14.0 + k)) for i in range(0, 2 * w + 1, 4)]
    d.line(pts, fill=(224, 168, 74, 255 - k * 60), width=14 - k * 3)

for name, px in SIZES.items():
    im.resize((px, px), Image.LANCZOS).save(os.path.join(ROOT, "res", "mipmap-" + name, "ic_launcher.png"))
im.resize((192, 192), Image.LANCZOS).save(os.path.join(ROOT, "docs", "icon.png"))
