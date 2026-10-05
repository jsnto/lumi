#!/usr/bin/env python3
"""Generates the lumi iOS launcher icons.

The artwork is the Android app's adaptive icon, ported to the iOS flat-icon
surface: the full-bleed #FF8A50 background, the cream record face (#FFF3E0,
diameter 60 of the 108 grid), and the orange spindle (#FF8A50, diameter 18)
- the same concentric "sunny record face" as the in-app logo. iOS masks the
square PNG into the squircle itself (no visible square: the background is
full-bleed, like the Android adaptive icon's background layer).

Every size is rendered independently from the vector spec (the analytical
1-px-anti-aliased circle coverage), so no resampling blur creeps into the
small sizes. Pure stdlib (zlib + struct) - no PIL.
"""

import os
import struct
import zlib

# The icon's own palette (the Android app's adaptive icon layers).
BG = (0xFF, 0x8A, 0x50)       # the face's own orange (background layer)
FACE = (0xFF, 0xF3, 0xE0)     # the record face's cream
SPINDLE = (0xFF, 0x8A, 0x50)  # the spindle (the background showing through)

# The 108 grid's geometry (the adaptive icon's viewport).
GRID = 108.0
FACE_R = 30.0
SPINDLE_R = 9.0


def clamp(x, lo=0.0, hi=1.0):
    return max(lo, min(hi, x))


def coverage(radius, dist):
    """The 1-px-anti-aliased circle coverage at distance [dist] from the
    center (the standard half-pixel ramp)."""
    return clamp(radius - dist + 0.5)


def render(size):
    """Renders the icon at [size] px (the square PNG's side)."""
    scale = size / GRID
    fr = FACE_R * scale
    sr = SPINDLE_R * scale
    c = size / 2.0
    rows = []
    for y in range(size):
        row = bytearray()
        py = y + 0.5
        for x in range(size):
            d = ((x + 0.5 - c) ** 2 + (py - c) ** 2) ** 0.5
            f = coverage(fr, d)
            s = coverage(sr, d)
            # Compose: the background, then the face (cream), then the
            # spindle (the background's own color).
            r = BG[0] + (FACE[0] - BG[0]) * f
            g = BG[1] + (FACE[1] - BG[1]) * f
            b = BG[2] + (FACE[2] - BG[2]) * f
            r = r + (SPINDLE[0] - r) * s
            g = g + (SPINDLE[1] - g) * s
            b = b + (SPINDLE[2] - b) * s
            row += bytes((int(r + 0.5), int(g + 0.5), int(b + 0.5)))
        rows.append(bytes(row))
    return rows


def png(rows):
    """Encodes the row bytes as an 8-bit RGB PNG."""
    size = len(rows)
    raw = b"".join(b"\x00" + row for row in rows)

    def chunk(tag, payload):
        block = tag + payload
        return struct.pack(">I", len(payload)) + block + struct.pack(">I", zlib.crc32(block) & 0xFFFFFFFF)

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 2, 0, 0, 0)
    return (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", ihdr)
        + chunk(b"IDAT", zlib.compress(raw, 9))
        + chunk(b"IEND", b"")
    )


def main():
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "AppIcon")
    os.makedirs(out_dir, exist_ok=True)
    # The standard iOS icon pixel sizes (the pre-asset-catalog set: every
    # size its own unique file, listed in the Info.plist's
    # CFBundleIconFiles).
    sizes = [20, 29, 40, 58, 60, 76, 80, 87, 120, 152, 180, 1024]
    for size in sizes:
        path = os.path.join(out_dir, "Icon%dx%d.png" % (size, size))
        with open(path, "wb") as fh:
            fh.write(png(render(size)))
        print("wrote", path)


if __name__ == "__main__":
    main()
