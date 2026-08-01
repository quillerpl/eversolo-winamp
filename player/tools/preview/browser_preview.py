#!/usr/bin/env python3
"""
Renders the library browser window on the desktop, from the same tables the app uses.

The gen.bmp frame was measured out of the bitmap rather than copied from a reference
implementation, so this preview is the check on that work: if a piece is the wrong size or
in the wrong place, the bevels do not meet and it is obvious here rather than after an
install cycle on a device with no debugger.

Usage:
    ./tools/preview/browser_preview.py                       # the on-device size
    ./tools/preview/browser_preview.py --width 350 --height 220 --scale 3
"""

import argparse
import os
import re
import sys

from PIL import Image, ImageDraw

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from playlist_preview import (load_skin, pledit_style, ui_font, SKIN_WSZ,   # noqa: E402
                              font_lookup, CHAR_W, CHAR_H)

HERE = os.path.dirname(os.path.abspath(__file__))
PLAYER = os.path.abspath(os.path.join(HERE, "..", ".."))
GEN_JAVA = os.path.join(PLAYER, "skin/src/main/java/org/eversolo/winamp/skin/GenSprites.java")

# Mirrors GenSprites.java / GenGeometry.java.
TITLE_H, LEFT_W, RIGHT_W, BOTTOM_H, PIECE_W = 20, 11, 8, 14, 25
BUTTON_W, BUTTON_H = 47, 15
LETTER_H, LETTER_Y, SPACE_W = 7, 88, 5
ROW_H, PAD, SCROLL_W, SCROLL_HANDLE_H, BUTTON_GAP = 13, 3, 13, 30, 6
TABS = ["ARTIST", "ALBUM", "FOLDER", "M3U"]

SAMPLE = [
    ("Leonard Cohen", "94 tracks", True),
    ("Electric Light Orchestra", "142 tracks", True),
    ("Gipsy Kings", "54 tracks", True),
    ("Miles Davis", "88 tracks", True),
    ("Nina Simone", "61 tracks", True),
    ("Pink Floyd", "119 tracks", True),
    ("Portishead", "33 tracks", True),
    ("Radiohead", "97 tracks", True),
    ("Steely Dan", "72 tracks", True),
    ("Talk Talk", "41 tracks", True),
    ("The Beatles", "213 tracks", True),
    ("Bill Evans Trio", "58 tracks", True),
    ("Fleetwood Mac", "66 tracks", True),
    ("Joni Mitchell", "49 tracks", True),
    ("Kraftwerk", "37 tracks", True),
    ("Massive Attack", "44 tracks", True),
]


def load_gen_sprites():
    src = open(GEN_JAVA, encoding="utf-8").read()
    sprites = {}
    for m in re.finditer(
            r's\("(\w+)",\s*"([\w.]+)",\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)\)', src):
        sprites[m.group(1)] = (m.group(2), int(m.group(3)), int(m.group(4)),
                               int(m.group(5)), int(m.group(6)))
    letters = {}
    for m in re.finditer(r"letter\('(\w)',\s*(\d+),\s*(\d+)\)", src):
        letters[m.group(1)] = (int(m.group(2)), int(m.group(3)))
    return sprites, letters


class Win:
    def __init__(self, skin, sprites, w, h):
        self.skin, self.sprites, self.w, self.h = skin, sprites, w, h
        self.img = Image.new("RGB", (w, h), "black")

    def blit(self, name, x, y):
        s = self.sprites.get(name)
        if not s:
            print(f"  ! no sprite {name}", file=sys.stderr)
            return
        f, sx, sy, sw, sh = s
        bmp = self.skin.get(f)
        if bmp is None:
            print(f"  ! no bitmap {f}", file=sys.stderr)
            return
        self.img.paste(bmp.crop((sx, sy, sx + sw, sy + sh)), (x, y))

    def tile_across(self, name, x, y, w):
        f, sx, sy, sw, sh = self.sprites[name]
        strip = Image.new("RGB", (max(w, 1), sh))
        for i in range(0, w, sw):
            strip.paste(self.skin[f].crop((sx, sy, sx + sw, sy + sh)), (i, 0))
        self.img.paste(strip.crop((0, 0, w, sh)), (x, y))

    def tile_down(self, name, x, y, h):
        f, sx, sy, sw, sh = self.sprites[name]
        strip = Image.new("RGB", (sw, max(h, 1)))
        for i in range(0, h, sh):
            strip.paste(self.skin[f].crop((sx, sy, sx + sw, sy + sh)), (0, i))
        self.img.paste(strip.crop((0, 0, sw, h)), (x, y))

    def gen_text(self, letters, s, x, y):
        """The title, in gen.bmp's own capitals-only alphabet."""
        gen = self.skin["gen.bmp"]
        for ch in s.upper():
            if ch not in letters:
                x += SPACE_W + 1
                continue
            lx, lw = letters[ch]
            self.img.paste(gen.crop((lx, LETTER_Y, lx + lw, LETTER_Y + LETTER_H)), (x, y))
            x += lw + 1

    def small_text(self, s, x, y):
        """The 5x6 font from text.bmp, for the button labels."""
        font = self.skin.get("text.bmp")
        lookup = font_lookup()
        for i, ch in enumerate(s.lower()):
            pos = lookup.get(ch, lookup.get(" "))
            if pos is None:
                continue
            row, col = pos
            self.img.paste(font.crop((col * CHAR_W, row * CHAR_H,
                                      col * CHAR_W + CHAR_W, row * CHAR_H + CHAR_H)),
                           (x + i * CHAR_W, y))


def gen_text_width(letters, s):
    w = 0
    for ch in s.upper():
        w += (letters[ch][1] if ch in letters else SPACE_W) + 1
    return max(0, w - 1)


def render(skin, sprites, letters, W, H, title="ARTISTS", where="", tab=0, selected=(3,)):
    win = Win(skin, sprites, W, H)
    style = pledit_style(skin)
    d = ImageDraw.Draw(win.img)

    content_bottom = H - BOTTOM_H
    button_row_y = content_bottom - PAD - BUTTON_H
    tab_y = TITLE_H + PAD
    list_y = tab_y + BUTTON_H + PAD
    list_x = LEFT_W
    list_w = W - LEFT_W - RIGHT_W - SCROLL_W
    list_h = button_row_y - PAD - list_y

    d.rectangle([LEFT_W, TITLE_H, W - RIGHT_W - 1, content_bottom - 1], fill=style["normalbg"])

    # frame
    win.tile_across("GEN_TOP_FILL", PIECE_W, 0, W - 2 * PIECE_W)
    win.blit("GEN_TOP_LEFT", 0, 0)
    win.blit("GEN_TOP_RIGHT", W - PIECE_W, 0)
    tx = (W - PIECE_W * 3) // 2
    win.blit("GEN_TITLE_LEFT", tx, 0)
    win.blit("GEN_TITLE_FILL", tx + PIECE_W, 0)
    win.blit("GEN_TITLE_RIGHT", tx + 2 * PIECE_W, 0)
    win.tile_down("GEN_LEFT_BORDER", 0, TITLE_H, H - TITLE_H - BOTTOM_H)
    win.tile_down("GEN_RIGHT_BORDER", W - RIGHT_W, TITLE_H, H - TITLE_H - BOTTOM_H)
    win.tile_across("GEN_BOTTOM_FILL", PIECE_W, content_bottom, W - 2 * PIECE_W)
    win.blit("GEN_BOTTOM_LEFT", 0, content_bottom)
    win.blit("GEN_BOTTOM_RIGHT", W - PIECE_W, content_bottom)

    win.gen_text(letters, title, (W - gen_text_width(letters, title)) // 2,
                 (TITLE_H - LETTER_H) // 2)

    # tabs. Dark text in the real font, as Winamp draws these grey buttons.
    label_font = ui_font(8)
    for i, name in enumerate(TABS):
        x = LEFT_W + PAD + i * (BUTTON_W + BUTTON_GAP)
        win.blit("GENEX_BUTTON_PRESSED" if i == tab else "GENEX_BUTTON", x, tab_y)
        d.text((x + BUTTON_W // 2, tab_y + BUTTON_H // 2), name, font=label_font,
               fill="#101010", anchor="mm")

    # list
    font = ui_font(9)
    visible = max(1, list_h // ROW_H)
    for i, (label, meta, container) in enumerate(SAMPLE[:visible]):
        y = list_y + i * ROW_H
        if i in selected:
            d.rectangle([list_x, y, list_x + list_w - 1, y + ROW_H - 1],
                        fill=style["selectedbg"])
        colour = style["current"] if container else style["normal"]
        d.text((list_x + 4, y + 2), label, font=font, fill=colour)
        if meta:
            d.text((list_x + list_w - 4, y + 2), meta, font=font, fill=colour, anchor="ra")

    win.blit("GENEX_SCROLL_HANDLE", W - RIGHT_W - SCROLL_W, list_y)

    # bottom buttons
    for i, label in enumerate(["DONE", "ADD"]):
        x = W - RIGHT_W - PAD - (i + 1) * BUTTON_W - i * BUTTON_GAP
        win.blit("GENEX_BUTTON", x, button_row_y)
        d.text((x + BUTTON_W // 2, button_row_y + BUTTON_H // 2), label, font=label_font,
               fill="#101010", anchor="mm")
    if where:
        d.text((LEFT_W + 4, button_row_y + 4), "◀ " + where, font=font, fill=style["normal"])
    return win.img


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--width", type=int, default=500)
    ap.add_argument("--height", type=int, default=270)
    ap.add_argument("--scale", type=int, default=4)
    ap.add_argument("--where", default="")
    ap.add_argument("--out", default="/tmp/browser_preview.png")
    args = ap.parse_args()

    skin = load_skin(SKIN_WSZ)
    sprites, letters = load_gen_sprites()
    print(f"{len(sprites)} gen sprites, {len(letters)} letters")
    img = render(skin, sprites, letters, args.width, args.height, where=args.where)
    img.resize((img.width * args.scale, img.height * args.scale),
               Image.NEAREST).save(args.out)
    print(f"wrote {args.out}  ({args.width}x{args.height} at x{args.scale})")


if __name__ == "__main__":
    main()
