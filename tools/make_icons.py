# -*- coding: utf-8 -*-
"""
Icon pipeline — photo-style treatment for full-bleed art.

Usage:
  python make_icons.py hill.png ../app/src/main/res          # release: 星际穿越
  python make_icons.py bill.png ../app/src/debug/res         # debug:   地心游记

Outputs (same resource names in both trees; build-type res overlay picks one):
  - mipmap-{dpi}/ic_launcher(.round).png    legacy square / circle
  - mipmap-{dpi}/ic_launcher_bg.png         adaptive background (the art)
  - mipmap-{dpi}/ic_launcher_fg.png         transparent adaptive foreground
  - drawable-{dpi}/ic_stat.png              luminance silhouette (status bar)
"""
from PIL import Image, ImageDraw, ImageOps
import sys
import os

LEGACY = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
ADAPTIVE = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}
STAT = {"mdpi": 24, "hdpi": 36, "xhdpi": 48, "xxhdpi": 72, "xxxhdpi": 96}


def load_square(src_path):
    img = Image.open(src_path).convert("RGBA")
    w, h = img.size
    side = min(w, h)
    return img.crop(((w - side) // 2, (h - side) // 2, (w + side) // 2, (h + side) // 2))


def round_mask(size):
    m = Image.new("L", (size, size), 0)
    ImageDraw.Draw(m).ellipse((0, 0, size - 1, size - 1), fill=255)
    return m


def cover_resize(img, size):
    return ImageOps.fit(img, (size, size), Image.LANCZOS)


def luminance_silhouette(img, size):
    small = cover_resize(img, size).convert("L")
    alpha = Image.eval(small, lambda v: max(0, 255 - int(v * 1.35)))
    out = Image.new("RGBA", (size, size), (255, 255, 255, 0))
    white = Image.new("RGBA", (size, size), (255, 255, 255, 255))
    white.putalpha(alpha)
    out.alpha_composite(white)
    return out


def main():
    src = sys.argv[1]
    res = sys.argv[2]
    art = load_square(src)

    for dpi, size in LEGACY.items():
        d = f"{res}/mipmap-{dpi}"
        os.makedirs(d, exist_ok=True)
        icon = cover_resize(art, size)
        icon.save(f"{d}/ic_launcher.png")
        r = icon.copy()
        r.putalpha(round_mask(size))
        r.save(f"{d}/ic_launcher_round.png")

    for dpi, size in ADAPTIVE.items():
        d = f"{res}/mipmap-{dpi}"
        os.makedirs(d, exist_ok=True)
        cover_resize(art, size).save(f"{d}/ic_launcher_bg.png")
        Image.new("RGBA", (size, size), (0, 0, 0, 0)).save(f"{d}/ic_launcher_fg.png")

    for dpi, size in STAT.items():
        d = f"{res}/drawable-{dpi}"
        os.makedirs(d, exist_ok=True)
        luminance_silhouette(art, size).save(f"{d}/ic_stat.png")

    print("generated icons from", src, "into", res)


if __name__ == "__main__":
    main()
