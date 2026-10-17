#!/usr/bin/env python3
"""Generate every launcher-icon PNG from tools/icon-source/anvex-icon-source.jpg (Pillow + numpy).

Outputs, for mdpi..xxxhdpi: ic_launcher.png, ic_launcher_round.png (48dp), ic_launcher_foreground.png and
ic_launcher_monochrome.png (108dp adaptive layers). The logo is cut out of its dark backdrop with a soft
radial feather so it blends into the solid adaptive-icon background colour (colors.xml: ic_launcher_background).
"""
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "tools/icon-source/anvex-icon-source.jpg"
RES = ROOT / "app/src/main/res"
BG = (11, 16, 32)                       # keep in sync with ic_launcher_background (#0B1020)
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}

src = Image.open(SRC).convert("RGB")
a = np.asarray(src).astype(np.int16)
bright = a.max(axis=2) > 90
# The emblem sits in the middle of the artwork; circuit traces and sparks reach far beyond it, so the
# centre comes from the bright pixels of the central region and the size is fixed for this artwork.
ys, xs = np.nonzero(bright[300:1750, 300:1750])
cx, cy = xs.mean() + 300, ys.mean() + 300
x0 = x1 = y0 = y1 = 0
logo = 1330.0                           # emblem diameter in source pixels (A frame + ring)


def cutout(side_src: float, out_px: int) -> Image.Image:
    """Square crop (side_src source px, centred on the logo) resized to out_px, with a feathered alpha edge."""
    half = side_src / 2
    box = (int(cx - half), int(cy - half), int(cx + half), int(cy + half))
    crop = src.crop(box).resize((out_px, out_px), Image.LANCZOS).convert("RGBA")
    yy, xx = np.mgrid[0:out_px, 0:out_px]
    r = np.hypot(xx - out_px / 2, yy - out_px / 2) / (out_px / 2)
    alpha = np.clip((1.0 - r) / 0.28, 0, 1)          # solid inside 72% radius, fades to 0 at the edge
    alpha = alpha * alpha * (3 - 2 * alpha)
    crop.putalpha(Image.fromarray((alpha * 255).astype(np.uint8)))
    return crop


def layer(px: int, logo_dp: float, canvas_dp: float, mono: bool = False) -> Image.Image:
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    side_dp = logo_dp * 1.40                          # crop includes the glow around the logo
    side_px = int(round(px * side_dp / canvas_dp))
    piece = cutout(logo * 1.40, side_px)
    if mono:
        lum = np.asarray(piece.convert("L")).astype(np.float32)
        a_ = np.clip((lum - 62) / 110.0, 0, 1) ** 0.8
        feather = np.asarray(piece.split()[3]).astype(np.float32) / 255
        out = np.zeros((side_px, side_px, 4), np.uint8)
        out[..., :3] = 255
        out[..., 3] = (a_ * feather * 255).astype(np.uint8)
        piece = Image.fromarray(out, "RGBA")
    canvas.alpha_composite(piece, ((px - side_px) // 2, (px - side_px) // 2))
    return canvas


def legacy(px: int, round_: bool) -> Image.Image:
    base = Image.new("RGBA", (px, px), BG + (255,))
    base.alpha_composite(layer(px, logo_dp=px * 0.78, canvas_dp=px))   # logo ~78% of the icon
    mask = Image.new("L", (px, px), 0)
    d = ImageDraw.Draw(mask)
    if round_:
        d.ellipse((0, 0, px - 1, px - 1), fill=255)
    else:
        d.rounded_rectangle((0, 0, px - 1, px - 1), radius=int(px * 0.22), fill=255)
    base.putalpha(mask)
    return base


for name, scale in DENS.items():
    d = RES / f"mipmap-{name}"
    s48, s108 = int(round(48 * scale)), int(round(108 * scale))
    legacy(s48, False).save(d / "ic_launcher.png")
    legacy(s48, True).save(d / "ic_launcher_round.png")
    layer(s108, logo_dp=64, canvas_dp=108).save(d / "ic_launcher_foreground.png")
    layer(s108, logo_dp=64, canvas_dp=108, mono=True).save(d / "ic_launcher_monochrome.png")
    print("wrote", name, s48, s108)
print("logo centre px:", int(cx), int(cy))
