#!/usr/bin/env python3
"""Verify Anvex launcher icon sources and, optionally, the generated APK.

Source checks are deliberately strict: AndroidManifest must point at the
resource names that exist in all density buckets, adaptive icons must resolve
all referenced layers, and image dimensions must match Android's density grid.
When --apk is supplied, the script checks that the same icon resources are in
that APK and compares decoded pixels to the committed source files so a stale
or wrong icon cannot silently pass CI.

Requires Pillow only for pixel-level validation. The CI workflow installs it.
"""
from __future__ import annotations

import argparse
import hashlib
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RES = ROOT / "app/src/main/res"
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"
DENSITIES = {
    "mdpi": 1,
    "hdpi": 1.5,
    "xhdpi": 2,
    "xxhdpi": 3,
    "xxxhdpi": 4,
}
BASE_SIZE = {"ic_launcher": 48, "ic_launcher_round": 48,
             "ic_launcher_foreground": 108, "ic_launcher_monochrome": 108}


def png_dimensions(path_or_bytes: Path | bytes) -> tuple[int, int]:
    data = path_or_bytes.read_bytes() if isinstance(path_or_bytes, Path) else path_or_bytes
    if len(data) < 24 or data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR":
        raise ValueError("not a valid PNG header")
    return struct.unpack(">II", data[16:24])


def check(ok: bool, label: str, details: str = "") -> bool:
    print(f"{'PASS' if ok else 'FAIL'} | {label}" + (f" | {details}" if details else ""))
    return ok


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk", type=Path, help="optional built APK to verify against source assets")
    args = parser.parse_args()
    failures = 0
    def mark(ok: bool, label: str, details: str = "") -> None:
        nonlocal failures
        failures += not check(ok, label, details)

    try:
        from PIL import Image
    except ImportError:
        Image = None

    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    application = manifest.find("application")
    if application is None:
        mark(False, "application element exists")
        return 1
    mark(application.get(ANDROID_NS + "icon") == "@mipmap/ic_launcher",
         "Manifest launcher icon", application.get(ANDROID_NS + "icon", "missing"))
    mark(application.get(ANDROID_NS + "roundIcon") == "@mipmap/ic_launcher_round",
         "Manifest round launcher icon", application.get(ANDROID_NS + "roundIcon", "missing"))
    mark(application.get(ANDROID_NS + "label") == "@string/app_name",
         "Manifest app label resource", application.get(ANDROID_NS + "label", "missing"))
    strings = ET.parse(RES / "values/strings.xml").getroot()
    labels = {el.get("name"): (el.text or "").strip() for el in strings.findall("string")}
    mark(labels.get("app_name") == "Anvex", "Application label", labels.get("app_name", "missing"))

    adaptive_files = [RES / "mipmap-anydpi-v26/ic_launcher.xml",
                      RES / "mipmap-anydpi-v26/ic_launcher_round.xml"]
    for path in adaptive_files:
        try:
            tree = ET.parse(path).getroot()
            mark(tree.tag == "adaptive-icon", f"Adaptive icon XML: {path.name}")
            refs = {child.tag: child.get(ANDROID_NS + "drawable") for child in tree}
            mark(refs.get("background") == "@color/ic_launcher_background" and
                 refs.get("foreground") == "@mipmap/ic_launcher_foreground" and
                 refs.get("monochrome") == "@mipmap/ic_launcher_monochrome",
                 f"Adaptive icon layers: {path.name}", str(refs))
        except (OSError, ET.ParseError) as exc:
            mark(False, f"Adaptive icon XML: {path.name}", str(exc))

    source_images: dict[str, dict[str, Path]] = {}
    for name, base_dp in BASE_SIZE.items():
        source_images[name] = {}
        for density, scale in DENSITIES.items():
            path = RES / f"mipmap-{density}/{name}.png"
            expected = (round(base_dp * scale), round(base_dp * scale))
            try:
                dims = png_dimensions(path)
                mark(dims == expected, f"Source image dimensions: {density}/{name}.png",
                     f"got={dims}, expected={expected}")
                if Image is not None:
                    with Image.open(path) as im:
                        rgba = im.convert("RGBA")
                        bbox = rgba.getchannel("A").getbbox()
                        mark(bbox is not None, f"Source image non-empty: {density}/{name}.png")
                        if name == "ic_launcher_round":
                            corners = [rgba.getpixel((0, 0))[3], rgba.getpixel((rgba.width - 1, 0))[3],
                                       rgba.getpixel((0, rgba.height - 1))[3],
                                       rgba.getpixel((rgba.width - 1, rgba.height - 1))[3]]
                            mark(all(alpha == 0 for alpha in corners),
                                 f"Round icon transparent corners: {density}", str(corners))
                source_images[name][density] = path
            except (OSError, ValueError, struct.error) as exc:
                mark(False, f"Source image: {density}/{name}.png", str(exc))

    if Image is None:
        if args.apk is None:
            print("SKIP | Optional pixel-level verification | install Pillow for decoded-pixel checks")
        else:
            mark(False, "APK pixel verification", "Pillow is required when --apk is supplied")
    elif args.apk is not None:
        if not args.apk.is_file():
            mark(False, "APK exists", str(args.apk))
        else:
            try:
                with zipfile.ZipFile(args.apk) as apk:
                    names = set(apk.namelist())
                    for name, variants in source_images.items():
                        for density, source_path in variants.items():
                            apk_path = f"res/mipmap-{density}-v4/{name}.png"
                            if apk_path not in names:
                                # Android/aapt builds can omit the -v4 qualifier in resource paths.
                                alternate = f"res/mipmap-{density}/{name}.png"
                                apk_path = alternate if alternate in names else apk_path
                            if apk_path not in names:
                                mark(False, f"APK contains {density}/{name}.png", "resource missing")
                                continue
                            try:
                                with Image.open(source_path) as src, Image.open(__import__('io').BytesIO(apk.read(apk_path))) as built:
                                    source_rgba = src.convert("RGBA")
                                    built_rgba = built.convert("RGBA")
                                    same_size = source_rgba.size == built_rgba.size
                                    same_pixels = same_size and source_rgba.tobytes() == built_rgba.tobytes()
                                    mark(same_pixels, f"APK icon pixels match source: {density}/{name}.png",
                                         f"resource={apk_path}; sha256={hashlib.sha256(apk.read(apk_path)).hexdigest()[:16]}" if same_pixels else f"resource={apk_path}; source={source_rgba.size}, APK={built_rgba.size}")
                            except Exception as exc:
                                mark(False, f"APK icon decode: {apk_path}", str(exc))
                    adaptive_apk_names = {
                        "res/mipmap-anydpi-v26/ic_launcher.xml",
                        "res/mipmap-anydpi-v26/ic_launcher_round.xml",
                    }
                    mark(adaptive_apk_names.issubset(names), "APK includes both adaptive icon XML resources",
                         ", ".join(sorted(adaptive_apk_names.intersection(names))))
            except (OSError, zipfile.BadZipFile) as exc:
                mark(False, "APK is a readable ZIP archive", str(exc))

    print(f"RESULT: {'PASS' if failures == 0 else 'FAIL'} ({failures} failing check(s))")
    return 0 if failures == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
