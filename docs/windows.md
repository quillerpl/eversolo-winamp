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
| Skins | `BrowserWindowView` again | `gen.bmp` / `genex.bmp` | `GenGeometry` |

The skin chooser is a **second instance of the browser window**, not a fourth class. The view
was already told nothing about what its rows mean, so all it needed was `setChrome` to relabel
the tabs and the three buttons — SKINS, and CLOSE / RESCAN / OPTIONS. Rows are marked
`container` so one tap applies a skin rather than selecting it. Everything else — the frame,
scrolling, hit-testing, the options fly-out — is shared.

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

It is the **second** item, below MAIN x8 and above x1. The fly-out is drawn over the button that opened it, so the
bottom item sits under the finger that just tapped and a second tap fires it. That has always
been true of x2; x2 is a survivable accident and silently going full screen is not, so the
zoom levels keep the positions they have always had.

`FullScreen` in `:app` owns it, and the interesting part is that it does not trust the idea to
work. It asks for the bar to go **without** also asking for a full-bleed layout, so that a
refusal leaves nothing drawn underneath the bar; only once the window is seen to grow past its
old width does it take the extra space for good. If the window has not grown after 2.5 s it
puts every flag back, says so in the title strip and ships the log.

It asks **twice, by two different mechanisms**. `SYSTEM_UI_FLAG_HIDE_NAVIGATION` is computed
by the system from the top *application* window, and `TYPE_APPLICATION_OVERLAY` sits well
above the application range. On this device that route is **inert** — the firmware reports no
insets at all (`R0 NAV0`) and the flags did nothing. `WindowInsetsController` (API 30, which
this device runs) routes through the *focused* window instead, and an overlay can be that:
**that is the one that works here.** Neither is trusted; the window growing is.

The same inertness is why the width has to be **pinned**. `LAYOUT_HIDE_NAVIGATION` should keep
the window full-width while the bar is briefly back, and does not: the window went
2160 → 2000 → 2160 on every single touch, re-scaling the whole layout each time. So once the
full width has been measured, `OverlayService.pinOverlayWidth` fixes the window there with
`FLAG_LAYOUT_NO_LIMITS`, and from then on only the bar moves. The bar draws over the
right-hand 160 px while it is visible — six pixels of frame on the main window, rather more of
the playlist's scrollbar. That was chosen over the whole UI jumping. Once proven, the window stays 2160 px wide even while the
bar is briefly visible — otherwise the whole layout would re-scale twice on every touch.

The main window does not get bigger (×7 either way, 1925 × 812) — it just stops sitting 80 px
left of the screen's real centre. The playlist goes 2000 → 2100 px and the browser fills 2160.

**MAIN x8** only does anything while full screen is actually working — at 2000 px wide it
would crop 100 px off each side, which answers nothing. It is a question rather than a
feature. 2160 ÷ 275 =
7.85, so the main window is stuck at ×7 with 117 px of black each side, and ×8 is 2200 px —
40 px too wide. The switch draws it at ×8 anyway and lets the screen crop 20 px off each edge,
so the cost can be looked at rather than argued about. It reports what it lost, and it is nudged one skin pixel left, because the two edges of
`main.bmp` are not the same drawing and an even crop does not read as even. If the owner does
not like it, the switch and `WindowScales.mainOversized` come out together.

**The two windows behave differently on purpose.** The main window takes the whole pinned
width and lets the bar float over its edge — you are not touching the screen while music
plays, so the bar is almost never up. The playlist and browser do the opposite: they are laid
out inside whatever is *not* under the bar, because you touch constantly while you are in a
list, so the bar is up nearly the whole time and being sat on would be the normal case.
Keeping them clear takes two things, and the second is easy to forget: size them to the usable
width **and** slide them over — a 2000 px list centred in a 2160 px window still runs to 2080,
and the bar starts at 2000. `WindowScales.centreShift` is that slide, and the JVM suite checks
the right-hand edge lands clear at every zoom.

## Feedback

The player is an overlay drawn above everything, so **a Toast renders behind it and is no
feedback at all**. Messages are drawn in the window: the browser has a line along its bottom
bar and marks rows already in the playlist; the main window can flash a message in the title
strip.
