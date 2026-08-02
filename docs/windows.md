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
  OPTS holds the zoom chooser, where Winamp kept its options.
* **Browser:** tapping a folder or album opens it, tapping a track selects it, ADD puts the
  selection in the playlist — or everything listed, if nothing is selected. That second rule
  makes "add this whole album" one tap.
* **Main window:** the X quits, the clock toggles elapsed/remaining, the title toggles
  tags/file name, the little LCD toggles the analyser, and a long press on the title bar
  opens the log console.

## Feedback

The player is an overlay drawn above everything, so **a Toast renders behind it and is no
feedback at all**. Messages are drawn in the window: the browser has a line along its bottom
bar and marks rows already in the playlist; the main window can flash a message in the title
strip.
