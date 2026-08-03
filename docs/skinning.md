# Skins: how anything gets drawn

A Winamp skin is a `.wsz` — a zip of BMPs plus a few `.txt` files. `:skin/Skin.java` loads
one, keyed on the **lower-cased basename**, because skins in the wild are inconsistent about
case and nesting (`MAIN.BMP`, `Main.bmp`, or inside a folder).

None is committed: the classic skin is Nullsoft's artwork. Put one in
`player/app/src/main/assets/skins/`; a `.wsz` dropped in
`/storage/emulated/0/EverSoloWinamp/skins/` overrides it at runtime.

## The two rules

1. **Whole-number scale, nearest-neighbour.** Fractional scaling ruins pixel art. This is
   why the zoom setting multiplies the *window scale* rather than stretching text — ×1.5 of
   a ×4 window is drawn at ×6. See `WindowScales`.
2. **`BitmapFactory` cannot read most skin bitmaps.** The classic skin stores `MAIN.BMP`,
   `CBUTTONS.BMP`, `TEXT.BMP`, `NUMBERS.BMP`, `SHUFREP.BMP`, `POSBAR.BMP` and
   `MONOSTER.BMP` as **BI_RLE8**, which Android returns null for. `BmpDecoder` handles RLE8
   plus 4/8/24/32-bit uncompressed. Without it the windows render empty.

## Sprite coordinates are generated, never typed

Two sources, two generators, both in `player/tools/`:

* **`gen-sprites.py`** pulls the main and playlist tables from
  [webamp](https://github.com/captbaritone/webamp)'s `skinSprites.ts` (MIT). Do not use
  eva's `skinformat.json` — it contradicts itself (previous-track at both x=16 and x=93,
  balance at 174 instead of 177, mono/stereo at y=43 instead of 41).
* **`gen-window-sprites.py`** measures `gen.bmp` out of the bitmap, because webamp has no
  media library and so no table to copy. **gen.bmp marks its own pieces** in RGB(0,198,255),
  a colour used nowhere else.

The one table that was typed by hand was missing `:` and space — which would have rendered
the playlist's running time as `1234` instead of `12:34`.

**`--check` on the window generator** reports which columns of the title bar repeat
seamlessly. Only two of the six 25-px pieces do: the dark title plate and the gold bar.
Getting that wrong stamps a cap every 25 px and breaks the gold lines — it happened.

## Fonts and colours

| Where | What |
|---|---|
| Main window marquee, playlist running time | `text.bmp`, a 5×6 bitmap font. One case only — everything comes out upper case |
| Generic window titles | `gen.bmp`'s own alphabet. **Capitals only**, every letter a different width (I is 4 px, M and W are 8). Window titles must be plain A–Z words |
| Track lists, button labels | The real system font. Winamp names it in `pledit.txt`, and the skin's green LCD font is unreadable on a grey button |
| Playlist colours | `pledit.txt` → `PleditStyle` |
| Analyser colours | `viscolor.txt` → `VisColors`. 0 background, 1 grid, 2–17 the gradient, 23 the peak dots |

## Before you build

Render it on the desktop first — `testing.md` has the commands. A misplaced piece is obvious
in a preview and invisible in a diff.

## Where the skin comes from

`SkinStore` owns this. It looks for the chosen skin first, then walks **every** volume for
`.wsz`/`.zip`, then falls back to the one bundled in the APK. The chosen path is remembered in
prefs, so it is a decision rather than whatever `listFiles` returned first.

The walk is `SkinFinder` in `:library` — Android-free, so `SkinFinderTest` can run it against
a real directory tree rather than an imagined one. Breadth-first to depth 6, skipping
`Android/`, dot-folders and `LOST.DIR`, with a 60,000-file backstop that says so in the log if
it ever trips. **It looks everywhere on purpose**: the first version searched five named
folders, so a skin at `USB/Music/Winamp stuff/base.wsz` did not exist as far as the app was
concerned, and nothing on screen said why. Cost is not the objection it sounds like — the
music scanner already walks 5,000 files in under a second on this hardware, and an empty
emulator volume came back in 5 ms.

**Release builds carry no skin.** The classic one is Nullsoft's artwork and is not this
project's to redistribute, so it lives in `src/debug/assets/skins/` and ships only in the
owner's own sideloaded build. A downloaded release finds nothing, and `MainActivity` shows a
plain-Android first-run screen — plain because with no skin there is nothing to draw a skinned
window with. After that, the **Winamp logo in the main window's bottom-right corner** (hit
area 247,86 22x22, measured off `main.bmp`) opens the in-player chooser.

## Why sprite coordinates are generated and never typed

A bright blue block appeared beside the balance slider on a user-supplied skin. `drawBalance`
had `src.set(0, 0, 38, 13)` typed into it, but the balance groove starts at **x=9** in
`balance.bmp` — the first nine columns are spare. In the bundled classic skin those columns are
black, the same as the window behind them, so it looked perfect for as long as only one skin
was ever loaded. In a skin that fills its spare space with cyan, they are `(0, 198, 255)`.

The table had the right numbers all along — `MAIN_BALANCE_BACKGROUND` is `balance.bmp, 9, 0,
38, 420`. The drawing code just did not ask it.

**So: if a number describes where something is inside a skin bitmap, it comes from
`SkinSprites`.** Not from a comment, not from measuring a screenshot, and above all not from
the one skin you happen to have open. `PlaylistGeometryTest` now asserts that the balance
groove is not at x=0 and that it is narrower than the volume groove, which is the difference
that makes this trap possible.

