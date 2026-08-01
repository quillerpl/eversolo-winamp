#!/usr/bin/env python3
"""
Generates the Java sprite tables in skin/SkinSprites.java from webamp's skinSprites.ts.

Sprite coordinates are the one part of a skin engine you cannot eyeball: a rectangle that
is two pixels out looks *nearly* right, and you only find out after an install cycle on a
device with no debugger. So they are generated from a source that is known to agree with
the real bitmaps rather than typed by hand.

webamp (MIT, https://github.com/captbaritone/webamp) is a faithful reimplementation of
Winamp 2.9 and its numbers are correct. eva's skinformat.json is NOT - it contradicts
itself (previous-track at both x=16 and x=93, balance at 174 instead of 177, mono/stereo
at y=43 instead of 41).

Usage:
    ./tools/gen-sprites.py CBUTTONS      # print the Java lines for one group
    ./tools/gen-sprites.py PLEDIT
    ./tools/gen-sprites.py --list        # what groups exist
    ./tools/gen-sprites.py --font        # the text.bmp character table

The output is pasted into SkinSprites.java. It is not run at build time: the file is
checked in so the build has no network dependency.
"""

import json
import re
import sys
import urllib.request

URL = ("https://raw.githubusercontent.com/captbaritone/webamp/master/"
       "packages/webamp/js/skinSprites.ts")

# webamp names its sprite groups after the bitmap they come from.
FILE_FOR_GROUP = {
    "CBUTTONS": "cbuttons.bmp",
    "MAIN": "main.bmp",
    "TITLEBAR": "titlebar.bmp",
    "SHUFREP": "shufrep.bmp",
    "TEXT": "text.bmp",
    "MONOSTER": "monoster.bmp",
    "PLAYPAUS": "playpaus.bmp",
    "NUMBERS": "numbers.bmp",
    "NUMS_EX": "nums_ex.bmp",
    "VOLUME": "volume.bmp",
    "BALANCE": "balance.bmp",
    "POSBAR": "posbar.bmp",
    "PLEDIT": "pledit.bmp",
    "EQMAIN": "eqmain.bmp",
    "EQ_EX": "eq_ex.bmp",
}


def fetch():
    with urllib.request.urlopen(URL, timeout=30) as r:
        return r.read().decode("utf-8")


def groups(src):
    """{group name: [ {name,x,y,width,height}, ... ]} straight out of the TypeScript."""
    out = {}
    # Start at the sprite map itself. The file opens with FONT_LOOKUP, which is also a
    # two-space-indented object of arrays and would otherwise swallow the first group.
    start = src.index("const sprites: SpriteMap = {")
    src = src[start:]
    # Each group is `NAME: [ ... ],` at two-space indentation inside one big object.
    for m in re.finditer(r"^  (\w+): \[(.*?)^  \],", src, re.M | re.S):
        entries = []
        for e in re.finditer(
                r"\{\s*name:\s*\"(\w+)\",\s*x:\s*(-?\d+),\s*y:\s*(-?\d+),"
                r"\s*width:\s*(-?\d+),\s*height:\s*(-?\d+),?\s*\}",
                m.group(2)):
            entries.append({
                "name": e.group(1), "x": int(e.group(2)), "y": int(e.group(3)),
                "width": int(e.group(4)), "height": int(e.group(5)),
            })
        out[m.group(1)] = entries
    return out


def font(src):
    """The text.bmp character table: {character: (row, column)}."""
    block = re.search(r"FONT_LOOKUP[^{]*\{(.*?)^\};", src, re.M | re.S).group(1)
    out = {}
    for m in re.finditer(r"^\s*(?:\"((?:[^\"\\]|\\.)+)\"|'([^']+)'|\[(\w+)\]|(\S+)):"
                         r"\s*\[(\d+),\s*(\d+)\]", block, re.M):
        ch = m.group(1) or m.group(2) or m.group(4)
        if m.group(3):                       # a computed key: [UTF8_ELLIPSIS]
            ch = {"UTF8_ELLIPSIS": "…"}.get(m.group(3))
            if ch is None:
                continue
        ch = ch.encode().decode("unicode_escape") if "\\" in ch else ch
        out[ch] = (int(m.group(5)), int(m.group(6)))
    return out


def java_char(ch):
    """A Java char literal. Non-ASCII goes in as \\uXXXX so the source stays ASCII."""
    if ch == "'":
        return "'\\''"
    if ch == "\\":
        return "'\\\\'"
    if ord(ch) < 128:
        return f"'{ch}'"
    return f"'\\u{ord(ch):04x}'"


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        return 1
    src = fetch()
    if sys.argv[1] == "--font":
        for ch, (row, col) in font(src).items():
            note = "  // space" if ch == " " else ""
            print(f"        f({java_char(ch)}, {row}, {col});{note}")
        return 0
    g = groups(src)
    if sys.argv[1] == "--list":
        for k, v in g.items():
            print(f"{k:12s} {len(v):3d} sprites")
        return 0
    if sys.argv[1] == "--json":
        print(json.dumps(g, indent=2))
        return 0

    name = sys.argv[1]
    if name not in g:
        print(f"no such group: {name}. Try --list", file=sys.stderr)
        return 1
    bmp = FILE_FOR_GROUP.get(name, name.lower() + ".bmp")
    for s in g[name]:
        print(f'        s("{s["name"]}", "{bmp}", '
              f'{s["x"]}, {s["y"]}, {s["width"]}, {s["height"]});')
    return 0


if __name__ == "__main__":
    sys.exit(main())
