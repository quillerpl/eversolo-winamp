#!/usr/bin/env python3
"""
Generates the GEN.BMP / GENEX.BMP sprite table in skin/GenSprites.java.

GEN.BMP is Winamp's *generic* window frame - the one the media library and similar windows
wear. Unlike the main and playlist windows, webamp does not implement it, so there is no
ready-made sprite table to copy. It does not need one: the format marks its own pieces.
Every boundary in GEN.BMP is drawn in RGB(0,198,255), a colour that appears nowhere else,
so the pieces can be measured out of the bitmap instead of guessed at.

That matters most for the title font. GEN.BMP carries its own A-Z alphabet in which every
letter is a different width (I is 4 px, M and W are 8), and those 26 widths are exactly the
kind of thing that is miserable to type and silently wrong if you do.

Usage:
    ./tools/gen-window-sprites.py            # print the Java for GenSprites.java
    ./tools/gen-window-sprites.py --report   # show what was measured, for checking

The output is pasted into GenSprites.java and checked in, so the build needs no bitmap.
"""

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "preview"))
from bmpdec import decode_bmp                                    # noqa: E402

SKIN = os.path.join(HERE, "..", "app/src/main/assets/skins/base-2.91.wsz")
MARKER = (0, 198, 255)

# Title-bar and body pieces, read off the marker columns. The six 25 px title pieces are
# separated by markers at x = 25, 51, 77, 103 and 129; the borders and the bottom bar by
# markers at x = 125/126, 138 and 147 in the band below.
#
# WHICH piece is which is not obvious from looking, and getting it wrong produces a title
# bar whose gold lines break every 25 px. The order is settled by --check, which reports
# where the bar is uniform column-to-column: only x 52-76 (the dark plate the title text
# sits on) and x 104-128 (the gold bar) repeat seamlessly, so those two are the tiles and
# the rest are transitions. Read left to right, the row is:
#
#   corner | title-left cap | TITLE FILL | title-right cap | TOP FILL | corner
#
# which is not the order they are used in - the top fill goes between the corners and the
# title, and the title fill is tiled to whatever the text needs.
FRAME = [
    # name,                     x,   y,   w,  h
    ("GEN_TOP_LEFT",             0,   0,  25, 20),
    ("GEN_TITLE_LEFT",          26,   0,  25, 20),
    ("GEN_TITLE_FILL",          52,   0,  25, 20),
    ("GEN_TITLE_RIGHT",         78,   0,  25, 20),
    ("GEN_TOP_FILL",           104,   0,  25, 20),
    ("GEN_TOP_RIGHT",          130,   0,  25, 20),
    ("GEN_TOP_LEFT_INACTIVE",    0,  21,  25, 20),
    ("GEN_TITLE_LEFT_INACTIVE", 26,  21,  25, 20),
    ("GEN_TITLE_FILL_INACTIVE", 52,  21,  25, 20),
    ("GEN_TITLE_RIGHT_INACTIVE",78,  21,  25, 20),
    ("GEN_TOP_FILL_INACTIVE",  104,  21,  25, 20),
    ("GEN_TOP_RIGHT_INACTIVE", 130,  21,  25, 20),
    # The close button lives inside the top-right corner at +14,+3; this is its held state.
    ("GEN_CLOSE_PRESSED",      148,  42,   9,  9),
    ("GEN_LEFT_BORDER",        127,  42,  11, 29),
    ("GEN_RIGHT_BORDER",       139,  42,   8, 29),
    ("GEN_BOTTOM_LEFT",          0,  57,  25, 14),
    ("GEN_BOTTOM_FILL",         50,  57,  25, 14),
    ("GEN_BOTTOM_RIGHT",       100,  57,  25, 14),
]

# GENEX.BMP: push buttons and scrollbar parts, measured as non-background blobs.
GENEX = [
    ("GENEX_BUTTON",             0,   0,  47, 15),
    ("GENEX_BUTTON_PRESSED",     0,  15,  47, 15),
    ("GENEX_SCROLL_UP",          0,  30,  15, 15),
    ("GENEX_SCROLL_DOWN",       15,  30,  15, 15),
    ("GENEX_SCROLL_UP_PRESSED", 28,  30,  15, 15),
    ("GENEX_SCROLL_DOWN_PRESSED", 43, 30, 15, 15),
    ("GENEX_SCROLL_HANDLE",     57,  30,  13, 30),
    ("GENEX_SCROLL_HANDLE_PRESSED", 70, 30, 13, 30),
]

TITLE_FONT_ROWS = {"GEN_LETTER": 88, "GEN_LETTER_INACTIVE": 96}
TITLE_FONT_H = 7


def load(name):
    import zipfile
    import tempfile
    with zipfile.ZipFile(SKIN) as z:
        for entry in z.namelist():
            if entry.split("/")[-1].upper() == name:
                with tempfile.NamedTemporaryFile(suffix=".bmp", delete=False) as f:
                    f.write(z.read(entry))
                    path = f.name
                try:
                    return decode_bmp(path)
                finally:
                    os.unlink(path)
    raise SystemExit(f"{name} not found in {SKIN}")


def letter_bounds(gen):
    """Where each of A-Z sits, from the marker columns that separate them."""
    w, h = gen.size
    marks = [x for x in range(w) if gen.getpixel((x, TITLE_FONT_ROWS["GEN_LETTER"])) == MARKER]
    if len(marks) < 26:
        raise SystemExit(f"expected 26 letter markers, found {len(marks)}")

    # Z has no marker after it: it ends where the row stops being background.
    y = TITLE_FONT_ROWS["GEN_LETTER"] + 3
    background = gen.getpixel((w - 1, y))
    last = marks[-1] + 1
    end = last
    for x in range(last, w):
        if gen.getpixel((x, y)) != background:
            end = x
    bounds = []
    for i, m in enumerate(marks):
        start = m + 1
        stop = marks[i + 1] if i + 1 < len(marks) else end + 1
        bounds.append((chr(ord("A") + i), start, stop - start))
    return bounds


def main():
    gen = load("GEN.BMP")
    letters = letter_bounds(gen)

    if "--check" in sys.argv:
        # Which columns of the title bar are identical to the one after them? A run of
        # them is a region that tiles seamlessly; anything else is a transition piece and
        # will show a seam every 25 px if you repeat it. This is how the pieces above were
        # identified, and how to re-identify them for a skin that looks wrong.
        def col(x):
            return tuple(gen.getpixel((x, y)) for y in range(0, 20))
        runs, start = [], None
        for x in range(0, 154):
            if col(x) == col(x + 1):
                start = x if start is None else start
            elif start is not None:
                runs.append((start, x))
                start = None
        print("title bar, seamlessly tileable runs:")
        for a, b in runs:
            print(f"  x {a}-{b}  ({b - a + 1} px)")
        return 0

    if "--report" in sys.argv:
        print(f"GEN.BMP {gen.size[0]}x{gen.size[1]}")
        print("letters:")
        for ch, x, w in letters:
            print(f"  {ch}  x={x:3d}  w={w}")
        widths = sorted({w for _, _, w in letters})
        print(f"widths seen: {widths}")
        return 0

    for name, x, y, w, h in FRAME:
        print(f'        s("{name}", "gen.bmp", {x}, {y}, {w}, {h});')
    print()
    for name, x, y, w, h in GENEX:
        print(f'        s("{name}", "genex.bmp", {x}, {y}, {w}, {h});')
    print()
    for ch, x, w in letters:
        print(f"        letter('{ch}', {x}, {w});")
    return 0


if __name__ == "__main__":
    sys.exit(main())
