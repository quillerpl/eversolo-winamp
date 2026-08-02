# The three windows

All in `:skin`, all drawn from the skin, all knowing nothing about tracks or the device:
each is handed rows and reports what the user did. `WinampUi` in `:app` is the window
manager that connects them to everything else.

**They take turns.** On a 6-inch screen there is no room for two windows at a size where a
row can be tapped, so PL swaps the main window for the playlist and the playlist's X swaps
back. The user chose this over shrinking everything.

| Window | Class | Frame | Geometry |
|---|---|---|---|
| Main | `MainWindowView` | `main.bmp` + `titlebar.bmp`, fixed 275×116 | `SkinSprites` |
| Playlist | `PlaylistWindowView` | `pledit.bmp` | `PlaylistGeometry` |
| Browser | `BrowserWindowView` | `gen.bmp` / `genex.bmp` | `GenGeometry` |

## Sizing

* The playlist is the only window Winamp lets you resize, and only in whole segments: legal
  sizes are **275 + 25n wide and 58 + 29n tall**, because its borders are repeating tiles
  that size. `PlaylistGeometry` is the single source of truth for every position inside it.
* The generic frame has no step size — any size at all works.
* **Size from the view's measured size, not `DisplayMetrics`.** See `device.md`.
* Zoom (×1/×1.5/×2) multiplies the window scale; `WindowScales` does the arithmetic and
  backs off when a scale would need a window narrower than Winamp's 275 px minimum.

## Touch

Nothing may be reachable only by a gesture — that rule came from hiding per-row actions
behind a long-press and regretting it.

* **Playlist:** a tap selects, a double tap plays, and the window's own ▶ plays whatever is
  selected. The five fly-out menus (ADD/REM/SEL/MISC/LIST) work as in Winamp; MISC → MISC
  OPTS holds the zoom chooser and the FULLSCR switch, where Winamp kept its options.
* **Browser:** tapping a folder or album opens it, tapping a track selects it, ADD puts the
  selection in the playlist — or everything listed, if nothing is selected. That second rule
  makes "add this whole album" one tap.
* **Main window:** the X quits, the clock toggles elapsed/remaining, the title toggles
  tags/file name, the little LCD toggles the analyser, and a long press on the title bar
  opens the log console.

## Full screen

**FULLSCR**, in the same fly-out as the zoom in both scrolling windows, hides the device's
side bar five seconds after the last touch and brings it straight back on the next one — a
touch anywhere, not a swipe, so rule 7 holds. It is remembered between runs and defaults off.

It is the **top** item, above x1. The fly-out is drawn over the button that opened it, so the
bottom item sits under the finger that just tapped and a second tap fires it. That has always
been true of x2; x2 is a survivable accident and silently going full screen is not, so the
zoom levels keep the positions they have always had.

`FullScreen` in `:app` owns it, and the interesting part is that it does not trust the idea to
work. It asks for the bar to go **without** also asking for a full-bleed layout, so that a
refusal leaves nothing drawn underneath the bar; only once the window is seen to grow past its
old width does it take the extra space for good. If the window has not grown after 2.5 s it
puts every flag back and logs why. Once proven, the window stays 2160 px wide even while the
bar is briefly visible — otherwise the whole layout would re-scale twice on every touch.

The main window does not get bigger (×7 either way, 1925 × 812) — it just stops sitting 80 px
left of the screen's real centre. The playlist goes 2000 → 2100 px and the browser fills 2160.

## Feedback

The player is an overlay drawn above everything, so **a Toast renders behind it and is no
feedback at all**. Messages are drawn in the window: the browser has a line along its bottom
bar and marks rows already in the playlist; the main window can flash a message in the title
strip.
