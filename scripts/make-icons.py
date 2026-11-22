#!/usr/bin/env python3
"""Makes every app icon of the project from one master logo (phase I9).

Usage:  python3 scripts/make-icons.py [--check]
Needs:  Pillow (pip install pillow)

The master is `assets/branding/logo-master-1024.png`: the logo on a **transparent** background, 1024 x 1024. Replace it and run
this script to remake all icons. What it makes, and why it does not use the sets that icon generators produce:

  iOS     App/Shared/Resources/Assets.xcassets/AppIcon.appiconset: one 1024 px icon (Xcode makes every size from it) on an
          opaque white background. App Store Connect refuses an iOS icon that has an alpha channel, and the system rounds
          the corners itself, so the icon is a full square without transparency.
  macOS   the same set: ten sizes (16 to 1024), the logo on a white rounded tile with the margin and the shadow of Apple's
          template. A bare logo on transparency would float in the Dock without the tile every other Mac icon has.
  Android clients/android/app/src/main/res: an adaptive icon (white background, the logo inside the safe zone: the
          launcher cuts a circle, a squircle or a square out of the 108 dp, and only the inner 66 dp are always visible),
          a monochrome layer for themed icons (Android 13), the legacy PNGs for Android 7 and older, and the Play
          Store icon (512 px, full square: Play rounds it itself).

--check  makes nothing; it fails when an icon the project needs is missing, has the wrong size, or the iOS icon has alpha.
"""
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MASTER = os.path.join(ROOT, "assets", "branding", "logo-master-1024.png")
APPLE = os.path.join(ROOT, "clients", "apple", "App", "Shared", "Resources", "Assets.xcassets", "AppIcon.appiconset")
RES = os.path.join(ROOT, "clients", "android", "app", "src", "main", "res")
PLAY = os.path.join(ROOT, "clients", "android", "app", "src", "main", "ic_launcher-playstore.png")

MAC_SIZES = [(16, 1), (16, 2), (32, 1), (32, 2), (128, 1), (128, 2), (256, 1), (256, 2), (512, 1), (512, 2)]
DENSITIES = {"mdpi": 1.0, "hdpi": 1.5, "xhdpi": 2.0, "xxhdpi": 3.0, "xxxhdpi": 4.0}


def logo():
    """The logo cropped to what is drawn."""
    master = Image.open(MASTER).convert("RGBA")
    return master.crop(master.getchannel("A").getbbox())


def place(canvas, mark, fraction, centre=None):
    """Scales `mark` so that its longer side is `fraction` of the canvas and pastes it at the centre."""
    side = round(canvas.width * fraction)
    scale = side / max(mark.size)
    size = (max(1, round(mark.width * scale)), max(1, round(mark.height * scale)))
    resized = mark.resize(size, Image.LANCZOS)
    cx, cy = centre if centre else (canvas.width // 2, canvas.height // 2)
    canvas.alpha_composite(resized, (round(cx - size[0] / 2), round(cy - size[1] / 2)))
    return canvas


def white(size):
    return Image.new("RGBA", (size, size), (255, 255, 255, 255))


def save(image, path, mode=None):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    if mode:
        image = image.convert(mode)
    image.save(path, optimize=True)


# --- Apple ------------------------------------------------------------------------------------------------------------

def ios_icon(mark):
    # The master as drawn (about 70 % of the square), on white, without alpha.
    full = Image.open(MASTER).convert("RGBA")
    canvas = white(1024)
    canvas.alpha_composite(full.resize((1024, 1024), Image.LANCZOS))
    return canvas.convert("RGB")


def mac_tile(mark):
    """Apple's macOS template: 1024 canvas, an 824 square tile, corner radius 185.4, a soft shadow."""
    canvas = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    shadow = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    ImageDraw.Draw(shadow).rounded_rectangle((100, 112, 924, 936), radius=186, fill=(0, 0, 0, 70))
    canvas.alpha_composite(shadow.filter(ImageFilter.GaussianBlur(10)))
    tile = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
    ImageDraw.Draw(tile).rounded_rectangle((100, 100, 924, 924), radius=186, fill=(255, 255, 255, 255))
    canvas.alpha_composite(tile)
    place(canvas, mark, 0.58)
    return canvas


def apple(mark):
    images = [{"filename": "icon-ios-1024.png", "idiom": "universal", "platform": "ios", "size": "1024x1024"}]
    save(ios_icon(mark), os.path.join(APPLE, "icon-ios-1024.png"))
    base = mac_tile(mark)
    for size, scale in MAC_SIZES:
        pixels = size * scale
        name = f"icon-mac-{size}x{size}@{scale}x.png"
        save(base.resize((pixels, pixels), Image.LANCZOS), os.path.join(APPLE, name))
        images.append({"filename": name, "idiom": "mac", "scale": f"{scale}x", "size": f"{size}x{size}"})
    contents = {"images": images, "info": {"author": "xcode", "version": 1}}
    with open(os.path.join(APPLE, "Contents.json"), "w", encoding="utf-8") as handle:
        json.dump(contents, handle, indent=2)
        handle.write("\n")
    with open(os.path.join(os.path.dirname(APPLE), "Contents.json"), "w", encoding="utf-8") as handle:
        json.dump({"info": {"author": "xcode", "version": 1}}, handle, indent=2)
        handle.write("\n")


# --- Android ----------------------------------------------------------------------------------------------------------

def android(mark):
    for name, factor in DENSITIES.items():
        layer = round(108 * factor)
        # The foreground: the logo in the middle 58 dp of the 108 dp layer (the circle that is always visible is 66 dp).
        foreground = Image.new("RGBA", (layer, layer), (0, 0, 0, 0))
        place(foreground, mark, 58 / 108)
        save(foreground, os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png"))
        # The monochrome layer of themed icons: the same shape in one colour; the system tints it.
        mono = Image.new("RGBA", (layer, layer), (0, 0, 0, 0))
        mono.putalpha(foreground.getchannel("A"))
        save(mono, os.path.join(RES, f"mipmap-{name}", "ic_launcher_monochrome.png"))
        # The legacy icons (Android 7 and older, 48 dp): a rounded square and a circle, each with its own shape.
        px = round(48 * factor)
        big = px * 4
        square = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        ImageDraw.Draw(square).rounded_rectangle((0, 0, big - 1, big - 1), radius=round(big * 0.16), fill=(255, 255, 255, 255))
        place(square, mark, 0.78)
        save(square.resize((px, px), Image.LANCZOS), os.path.join(RES, f"mipmap-{name}", "ic_launcher.png"))
        circle = Image.new("RGBA", (big, big), (0, 0, 0, 0))
        ImageDraw.Draw(circle).ellipse((0, 0, big - 1, big - 1), fill=(255, 255, 255, 255))
        place(circle, mark, 0.68)
        save(circle.resize((px, px), Image.LANCZOS), os.path.join(RES, f"mipmap-{name}", "ic_launcher_round.png"))

    adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>
</adaptive-icon>
"""
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        path = os.path.join(RES, "mipmap-anydpi-v26", name)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(adaptive)
    colour = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- The background of the adaptive launcher icon (the logo is drawn on white). -->
    <color name="ic_launcher_background">#FFFFFF</color>
</resources>
"""
    path = os.path.join(RES, "values", "ic_launcher_background.xml")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as handle:
        handle.write(colour)
    # The Play Store icon: a full square (Play applies the shape), 512 px, 32-bit PNG.
    store = white(512)
    place(store, mark, 0.66)
    save(store, PLAY)


# --- Check ------------------------------------------------------------------------------------------------------------

def check():
    problems = []

    def need(path, size=None, alpha=None):
        if not os.path.exists(path):
            problems.append(f"missing: {os.path.relpath(path, ROOT)}")
            return
        if not path.endswith(".png"):
            return
        image = Image.open(path)
        if size and image.size != (size, size):
            problems.append(f"{os.path.relpath(path, ROOT)} is {image.size}, expected {size} x {size}")
        if alpha is False and ("A" in image.getbands() or image.mode in ("LA", "PA")):
            problems.append(f"{os.path.relpath(path, ROOT)} has an alpha channel (App Store Connect refuses it)")

    need(MASTER, 1024)
    need(os.path.join(APPLE, "icon-ios-1024.png"), 1024, alpha=False)
    for size, scale in MAC_SIZES:
        need(os.path.join(APPLE, f"icon-mac-{size}x{size}@{scale}x.png"), size * scale)
    for name, factor in DENSITIES.items():
        need(os.path.join(RES, f"mipmap-{name}", "ic_launcher_foreground.png"), round(108 * factor))
        need(os.path.join(RES, f"mipmap-{name}", "ic_launcher_monochrome.png"), round(108 * factor))
        need(os.path.join(RES, f"mipmap-{name}", "ic_launcher.png"), round(48 * factor))
        need(os.path.join(RES, f"mipmap-{name}", "ic_launcher_round.png"), round(48 * factor))
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        need(os.path.join(RES, "mipmap-anydpi-v26", name))
    need(os.path.join(RES, "values", "ic_launcher_background.xml"))
    need(PLAY, 512)
    contents = os.path.join(APPLE, "Contents.json")
    if os.path.exists(contents):
        listed = {item["filename"] for item in json.load(open(contents, encoding="utf-8"))["images"]}
        present = {name for name in os.listdir(APPLE) if name.endswith(".png")}
        if listed != present:
            problems.append(f"Contents.json and the PNG files of the icon set differ: {sorted(listed ^ present)}")
    for problem in problems:
        print("FAIL:", problem, file=sys.stderr)
    if problems:
        return 1
    print("ok: every icon is there, with the right size; the iOS icon has no alpha")
    return 0


def main():
    if "--check" in sys.argv[1:]:
        sys.exit(check())
    mark = logo()
    apple(mark)
    android(mark)
    print("icons made from", os.path.relpath(MASTER, ROOT))
    sys.exit(check())


if __name__ == "__main__":
    main()
