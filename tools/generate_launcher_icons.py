#!/usr/bin/env python3
"""Regenerate the FileForge launcher icons from art/fileforge-icon-source.jpg.

Usage: python3 tools/generate_launcher_icons.py

Requires Pillow. The source artwork is a 1024x1024 square that includes a
decorative frame and a wordmark; neither survives a launcher mask, so only the
forge scene is used. The scene is drawn at 72dp of the 108dp adaptive canvas so
that every mask shape (circle, squircle, rounded square) keeps the forge, the
folder, the anvil, and the rasp intact.
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter

ROOT = Path(__file__).resolve().parent.parent
SOURCE = ROOT / "art" / "fileforge-icon-source.jpg"
RES = ROOT / "app" / "src" / "main" / "res"

# Forge scene inside the 1024x1024 artwork: excludes the silver frame and the
# wordmark band that starts at y=824.
SCENE_BOX = (200, 120, 900, 820)

# Adaptive icons are 108dp; 72dp is the guaranteed-visible area.
ADAPTIVE_DP = 108
SCENE_DP = 88
FEATHER_DP = 5

LEGACY_DP = 48
LEGACY_SCENE_DP = 44
LEGACY_CORNER_DP = 11

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}


def scene() -> Image.Image:
    return Image.open(SOURCE).convert("RGB").crop(SCENE_BOX)


def feathered(source: Image.Image, size: int, feather: int) -> Image.Image:
    """The scene at `size` px with its outer `feather` px fading to transparent."""
    tile = source.resize((size, size), Image.LANCZOS).convert("RGBA")
    if feather <= 0:
        return tile
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (feather, feather, size - 1 - feather, size - 1 - feather),
        radius=feather * 2,
        fill=255,
    )
    tile.putalpha(mask.filter(ImageFilter.GaussianBlur(feather * 0.6)))
    return tile


def backdrop(source: Image.Image, size: int) -> Image.Image:
    """A blurred, dimmed full-bleed copy so no mask shape exposes bare canvas."""
    blurred = source.resize((size, size), Image.LANCZOS).filter(
        ImageFilter.GaussianBlur(size * 0.05)
    )
    return ImageEnhance.Brightness(blurred).enhance(0.72).convert("RGB")


def save(image: Image.Image, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    palette = image.convert("RGBA").quantize(colors=255, method=Image.FASTOCTREE)
    palette.save(path, optimize=True)


def main() -> None:
    source = scene()
    for density, scale in DENSITIES.items():
        adaptive = round(ADAPTIVE_DP * scale)
        background = backdrop(source, adaptive)
        foreground = Image.new("RGBA", (adaptive, adaptive), (0, 0, 0, 0))
        tile = feathered(source, round(SCENE_DP * scale), round(FEATHER_DP * scale))
        offset = (adaptive - tile.width) // 2
        foreground.alpha_composite(tile, (offset, offset))

        save(background, RES / f"mipmap-{density}" / "ic_launcher_background.png")
        save(foreground, RES / f"mipmap-{density}" / "ic_launcher_foreground.png")

        legacy = round(LEGACY_DP * scale)
        flat = backdrop(source, legacy)
        flat = flat.convert("RGBA")
        legacy_tile = feathered(source, round(LEGACY_SCENE_DP * scale), 0)
        legacy_offset = (legacy - legacy_tile.width) // 2
        flat.alpha_composite(legacy_tile, (legacy_offset, legacy_offset))

        square = Image.new("L", (legacy, legacy), 0)
        ImageDraw.Draw(square).rounded_rectangle(
            (0, 0, legacy - 1, legacy - 1), radius=round(LEGACY_CORNER_DP * scale), fill=255
        )
        squared = flat.copy()
        squared.putalpha(square)
        save(squared, RES / f"mipmap-{density}" / "ic_launcher.png")

        circle = Image.new("L", (legacy, legacy), 0)
        ImageDraw.Draw(circle).ellipse((0, 0, legacy - 1, legacy - 1), fill=255)
        rounded = flat.copy()
        rounded.putalpha(circle)
        save(rounded, RES / f"mipmap-{density}" / "ic_launcher_round.png")

    print(f"Wrote launcher icons for {len(DENSITIES)} densities under {RES}")


if __name__ == "__main__":
    main()
