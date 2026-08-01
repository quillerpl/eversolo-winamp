#!/usr/bin/env python3
"""
Renders the Winamp playlist window on the desktop, from the same sprite table the app uses.

There is no debugger on the Eversolo and every build costs a browser upload, so layout is
proved here first. This reads the sprite rectangles straight out of SkinSprites.java, so if
the Java table is wrong the preview is wrong in the same way - which is the point: it caught
both the wrong bundled skin and the RLE8 decoding problem in Phase 4.

Usage:
    ./tools/preview/playlist_preview.py                     # 275x232, the Winamp default
    ./tools/preview/playlist_preview.py --width 500 --height 261 --scale 4
    ./tools/preview/playlist_preview.py --screen 2000x1080  # both windows, as on the device
"""

import argparse
import os
import re
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from bmpdec import decode_bmp  # noqa: E402

HERE = os.path.dirname(os.path.abspath(__file__))
PLAYER = os.path.abspath(os.path.join(HERE, "..", ".."))
SKIN_WSZ = os.path.join(PLAYER, "app/src/main/assets/skins/base-2.91.wsz")
SPRITES_JAVA = os.path.join(
    PLAYER, "skin/src/main/java/org/eversolo/winamp/skin/SkinSprites.java")

# Mirrors PlaylistGeometry.java. Kept in step by hand, like bmpdec.py mirrors BmpDecoder.
TOP_H, BOTTOM_H, LEFT_W, RIGHT_W = 20, 38, 12, 20
TRACK_H, PAD, CORNER_W, TITLE_W = 13, 3, 25, 100
BOTTOM_LEFT_W, BOTTOM_RIGHT_W = 125, 150
MENU_BTN_W, MENU_BTN_H = 22, 18
CHAR_W, CHAR_H = 5, 6

PLEDIT_DEFAULTS = {
    "normal": "#00FF00", "current": "#FFFFFF",
    "normalbg": "#000000", "selectedbg": "#0000C6",
}


def load_skin(path):
    """{lower-case filename: PIL image or text}."""
    out = {}
    with zipfile.ZipFile(path) as z:
        for name in z.namelist():
            base = name.replace("\\", "/").split("/")[-1].lower()
            if base.endswith(".bmp"):
                tmp = os.path.join("/tmp", base)
                with open(tmp, "wb") as f:
                    f.write(z.read(name))
                try:
                    out[base] = decode_bmp(tmp)
                except Exception as e:      # noqa: BLE001 - a skin can carry odd files
                    print(f"  ! {base}: {e}", file=sys.stderr)
            elif base.endswith(".txt"):
                out[base] = z.read(name).decode("latin-1")
    return out


def load_sprites():
    """The s("NAME", "file.bmp", x, y, w, h) table out of the Java source."""
    src = open(SPRITES_JAVA, encoding="utf-8").read()
    out = {}
    for m in re.finditer(
            r's\("(\w+)",\s*"([\w.]+)",\s*(\d+),\s*(\d+),\s*(\d+),\s*(\d+)\)', src):
        out[m.group(1)] = (m.group(2), int(m.group(3)), int(m.group(4)),
                           int(m.group(5)), int(m.group(6)))
    return out


def pledit_style(skin):
    style = dict(PLEDIT_DEFAULTS)
    for line in skin.get("pledit.txt", "").splitlines():
        if "=" in line and not line.strip().startswith("["):
            k, v = line.split("=", 1)
            k = k.strip().lower()
            if k in style:
                style[k] = v.strip()
    return style


class Window:
    def __init__(self, skin, sprites, width, height):
        self.skin, self.sprites = skin, sprites
        self.w, self.h = width, height
        self.img = Image.new("RGB", (width, height), "black")

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

    def bitmap_text(self, s, x, y):
        """The 5x6 skin font, for the running-time readout."""
        font = self.skin.get("text.bmp")
        if font is None:
            return
        lookup = font_lookup()
        for i, ch in enumerate(s.lower()):
            pos = lookup.get(ch, lookup.get(" "))
            if pos is None:
                continue
            row, col = pos
            cell = font.crop((col * CHAR_W, row * CHAR_H,
                              col * CHAR_W + CHAR_W, row * CHAR_H + CHAR_H))
            self.img.paste(cell, (x + i * CHAR_W, y))


def font_lookup():
    """f('c', row, col) out of SkinSprites.java, so the preview uses the same table."""
    src = open(SPRITES_JAVA, encoding="utf-8").read()
    out = {}
    for m in re.finditer(r"f\('(\\\\|\\'|\\u[0-9a-f]{4}|.)',\s*(\d+),\s*(\d+)\)", src):
        ch = m.group(1)
        if ch.startswith("\\u"):
            ch = chr(int(ch[2:], 16))
        elif ch == "\\\\":
            ch = "\\"
        elif ch == "\\'":
            ch = "'"
        out[ch] = (int(m.group(2)), int(m.group(3)))
    return out


def ui_font(size):
    for path in ("/System/Library/Fonts/Supplemental/Arial Narrow.ttf",
                 "/System/Library/Fonts/Supplemental/Arial.ttf",
                 "/System/Library/Fonts/Helvetica.ttc"):
        if os.path.exists(path):
            try:
                return ImageFont.truetype(path, size)
            except Exception:                       # noqa: BLE001
                pass
    return ImageFont.load_default()


SAMPLE = [
    ("Leonard Cohen - Amen", "7:35"),
    ("Leonard Cohen - Show Me the Place", "4:11"),
    ("Gipsy Kings - Bem, bem, María", "3:22"),
    ("Electric Light Orchestra - Bluebird", "4:23"),
    ("Pink Floyd - Shine On You Crazy Diamond (Parts I-V)", "13:32"),
    ("Miles Davis - So What", "9:22"),
    ("Nina Simone - Sinnerman", "10:20"),
    ("The Beatles - A Day in the Life", "5:38"),
    ("Radiohead - Everything In Its Right Place", "4:11"),
    ("Portishead - Glory Box", "5:06"),
    ("Massive Attack - Teardrop", "5:29"),
    ("Talk Talk - New Grass", "9:39"),
    ("Bill Evans Trio - Waltz for Debby", "6:57"),
    ("Kraftwerk - Autobahn", "22:43"),
    ("Steely Dan - Deacon Blues", "7:37"),
    ("Fleetwood Mac - The Chain", "4:28"),
    ("Joni Mitchell - A Case of You", "4:22"),
    ("Nick Drake - Riverman", "4:20"),
]


def render_playlist(skin, sprites, width, height, current=2, selected=(4,), offset=0):
    win = Window(skin, sprites, width, height)
    style = pledit_style(skin)

    # list background
    ImageDraw.Draw(win.img).rectangle(
        [LEFT_W, TOP_H, width - RIGHT_W - 1, height - BOTTOM_H - 1], fill=style["normalbg"])

    # top strip: tile, then corners, then the title in the middle
    win.tile_across("PLAYLIST_TOP_TILE_SELECTED", CORNER_W, 0, width - 2 * CORNER_W)
    win.blit("PLAYLIST_TOP_LEFT_SELECTED", 0, 0)
    win.blit("PLAYLIST_TOP_RIGHT_CORNER_SELECTED", width - CORNER_W, 0)
    win.blit("PLAYLIST_TITLE_BAR_SELECTED", (width - TITLE_W) // 2, 0)

    # sides
    win.tile_down("PLAYLIST_LEFT_TILE", 0, TOP_H, height - TOP_H - BOTTOM_H)
    win.tile_down("PLAYLIST_RIGHT_TILE", width - RIGHT_W, TOP_H, height - TOP_H - BOTTOM_H)

    # bottom bar
    by = height - BOTTOM_H
    win.tile_across("PLAYLIST_BOTTOM_TILE", BOTTOM_LEFT_W, by,
                    width - BOTTOM_LEFT_W - BOTTOM_RIGHT_W)
    win.blit("PLAYLIST_BOTTOM_LEFT_CORNER", 0, by)
    win.blit("PLAYLIST_BOTTOM_RIGHT_CORNER", width - BOTTOM_RIGHT_W, by)

    # tracks
    d = ImageDraw.Draw(win.img)
    font = ui_font(9)
    rows = SAMPLE[offset:]
    visible = max(1, (height - TOP_H - BOTTOM_H - 2 * PAD) // TRACK_H)
    x, y0, w = LEFT_W, TOP_H + PAD, width - LEFT_W - RIGHT_W
    time_w = max(d.textlength(r[1], font=font) for r in rows[:visible])
    for i, (title, length) in enumerate(rows[:visible]):
        index = offset + i
        y = y0 + i * TRACK_H
        if index in selected:
            d.rectangle([x, y, x + w - 1, y + TRACK_H - 1], fill=style["selectedbg"])
        colour = style["current"] if index == current else style["normal"]
        label = f"{index + 1}. {title}"
        while d.textlength(label, font=font) > w - time_w - 9 and len(label) > 4:
            label = label[:-2] + "…"
        d.text((x + 3, y + 2), label, font=font, fill=colour)
        d.text((x + w - 3, y + 2), length, font=font, fill=colour, anchor="ra")

    # scrollbar
    travel = height - TOP_H - BOTTOM_H - 18
    max_off = max(0, len(SAMPLE) - visible)
    handle_y = TOP_H + (travel * offset // max_off if max_off else 0)
    win.blit("PLAYLIST_SCROLL_HANDLE", width - RIGHT_W + 5, handle_y)

    # running time, in the skin's own 5x6 font
    win.bitmap_text("12:34/1:47:02".replace("1:47:02", "107:02"),
                    width - BOTTOM_RIGHT_W + 7, height - 28)
    return win.img


def render_menu(skin, sprites, width, height, menu="add"):
    """The same window with a fly-out menu open, to check the stacking."""
    img = render_playlist(skin, sprites, width, height)
    win = Window(skin, sprites, width, height)
    win.img = img
    menus = {
        "add": (14, ["PLAYLIST_ADD_URL", "PLAYLIST_ADD_DIR", "PLAYLIST_ADD_FILE"],
                "PLAYLIST_ADD_MENU_BAR"),
        "rem": (43, ["PLAYLIST_REMOVE_ALL", "PLAYLIST_CROP", "PLAYLIST_REMOVE_SELECTED",
                     "PLAYLIST_REMOVE_MISC"], "PLAYLIST_REMOVE_MENU_BAR"),
        "sel": (72, ["PLAYLIST_INVERT_SELECTION", "PLAYLIST_SELECT_ZERO",
                     "PLAYLIST_SELECT_ALL"], "PLAYLIST_SELECT_MENU_BAR"),
        "misc": (101, ["PLAYLIST_SORT_LIST", "PLAYLIST_FILE_INFO",
                       "PLAYLIST_MISC_OPTIONS"], "PLAYLIST_MISC_MENU_BAR"),
        "list": (width - 44, ["PLAYLIST_NEW_LIST", "PLAYLIST_SAVE_LIST",
                              "PLAYLIST_LOAD_LIST"], "PLAYLIST_LIST_BAR"),
    }
    x, items, bar = menus[menu]
    btn_y = height - 30
    bar_h = sprites[bar][4]             # (file, x, y, w, h)
    win.blit(bar, x - 3, btn_y + MENU_BTN_H - bar_h)
    for i, item in enumerate(items):
        win.blit(item, x, btn_y + MENU_BTN_H * (1 + i - len(items)))
    return win.img


def scaled(img, scale):
    return img.resize((img.width * scale, img.height * scale), Image.NEAREST)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--width", type=int, default=275)
    ap.add_argument("--height", type=int, default=232)
    ap.add_argument("--scale", type=int, default=3)
    ap.add_argument("--menu", default=None,
                    help="also render with this fly-out open: add/rem/sel/misc/list")
    ap.add_argument("--out", default="/tmp/playlist_preview.png")
    args = ap.parse_args()

    skin = load_skin(SKIN_WSZ)
    sprites = load_sprites()
    print(f"skin: {len([k for k in skin if k.endswith('.bmp')])} bitmaps, "
          f"{len(sprites)} sprites")

    img = render_playlist(skin, sprites, args.width, args.height)
    scaled(img, args.scale).save(args.out)
    print(f"wrote {args.out}  ({args.width}x{args.height} at x{args.scale})")

    if args.menu:
        out = args.out.replace(".png", f"_{args.menu}.png")
        scaled(render_menu(skin, sprites, args.width, args.height, args.menu),
               args.scale).save(out)
        print(f"wrote {out}")


if __name__ == "__main__":
    main()
