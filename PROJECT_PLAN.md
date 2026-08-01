# Project Plan — Winamp-Style Player for Eversolo DMP-A6

---

## STATUS — 1 August 2026, night (read this first)

**Build `v0.18-nospectrum`. Phases 0–3 complete and confirmed on the device. Phase 4: three
skinned windows — main, playlist editor and library browser — plus the spectrum analyser.
The playlist window is confirmed on the device; the browser and the analyser are not yet.**

### The spectrum feed is dead, and the device says so itself

**There is no spectrum on this device.** Sampled directly over the LAN with music playing:

```
GET /ZidooMusicControl/v2/getSpectrum  ->  {}
```

Eight times in a row, with `type`, `openType`, `index` and `nb_freqs` tried as parameters,
and in every mode `changVUDisplay` offers — `spDisplayMode` 0, 1 and 2, `vuDisplayMode` 0
and 1. Always `{}`. Paused, it returns a structure full of zeros in which `fft_value` is a
*string* containing `"{}"`, which is what defeated two parsers before this.

The device states it plainly: **`getState.everSoloPlayInfo.isHasSpectrum` is false** — here,
in `state_before.json`, and in `probe_results.json`, covering local files and internet radio.
`isHasDSP` is false too.

**So this app has no audio data of any kind.** Not a waveform, not bands, not a level. The
Android side is no help either: the source was 48 kHz/24-bit and the output 48000 Hz, so
nothing is being resampled and the path is not going through the mixer where
`android.media.audiofx.Visualizer` could tap it — which is the bit-perfect design working
exactly as intended.

What v0.18 does about it:

* The analyser is **off by default**. An empty LCD is what Winamp looks like at rest.
* The light show still runs, on **time alone**, and says so along the bottom of the screen
  rather than pretending to react.
* A real bug is fixed: the full-screen window took any measurement as proof the feed worked,
  and "let the bars fall away" - a row of zeros sent when playback stops - counted. It
  silenced its own message permanently, which is why it showed a black screen with no
  explanation.

**The one thing that would change this** is `isHasSpectrum` turning true on some future
firmware. Check it before writing another line of parser.

### No MilkDrop or AVS either, and the reason is the same one

Asked whether Winamp's beat-synced visualisers could be ported. **They cannot**, and the
blocker is not effort: those plugins read the *waveform*, sixty times a second. butterchurn
— the MilkDrop reimplementation — calls `getByteTimeDomainData` on every frame and runs its
own FFT over 1024 stereo samples (`src/audio/audioProcessor.js`). MilkDrop, Geiss and AVS
all want the same thing, live PCM, from playback or a microphone.

This player never has the audio. The device decodes it straight to its DACs — the entire
point of the design — and hands us a frequency snapshot over HTTP. Capturing it Android-side
is out for the same reason: the bit-perfect path does not go through the mixer, and asking a
hi-fi player for the microphone permission to find out is not a trade worth making.

So `skin/VisualiserView.java` is an honest substitute: three effects on the device's own FFT
with beat detection on the bass bins. It moves with the music; it does not know what the
music looks like. It lives behind the EQ button, which had nothing else to do.

**Two things the off-device simulation settled before any install.** The beat baseline must
move at the *measurement* rate, not the frame rate — updating it every frame lets the
average converge on the eased value, the ratio never rises, and nothing ever pulses. And the
poll rate matters more than expected: against 120 BPM material, 180 ms spacing detected
75 BPM of beats and 80 ms detected 112. The full-screen window polls at 80 ms, the little
LCD analyser at 180, and the poller backs off on its own if a request ever takes longer than
half the interval.

### There will be no equalizer window, and that is the finding

The plan called for the EQ next. It cannot be built honestly: **the device's API exposes no
equalizer, no tone control and no filters**, and this unit reports `dspActive=false`,
`hasDspSetting=false`, `isHasDSP=false`. Ten sliders wired to nothing would also promise the
one thing this player exists to avoid — the signal reaching the DACs untouched is the entire
reason for the design. The EQ button now says so in the title bar instead.

The visualiser was the other half of that phase and it is real: `getSpectrum` serves live
FFT from the device's own DSP.

**There is no unskinned interface left.** The plain green list from Phases 1–3 (`PlayerUi`)
is deleted: it was still reachable from the eject button, which was the first thing the
user noticed. Its scanning moved to `MusicLibrary`, its m3u import to `WinampUi`, and its
long-press-for-the-log gesture to the main window's title bar, where CLAUDE.md always said
it was.

Source: `player/`. Install: open `http://<device>:18888` in a desktop browser, choose
`~/Downloads/EversoloWinamp.apk`. Build: `cd player && ./gradlew assembleDebug`
(JDK 21 from Android Studio's JBR, Gradle 8.13, AGP 8.12.1; `local.properties` points at
the SDK — without it Gradle stops with "SDK location not found").

> **Bump `versionCode` before every install.** v0.11 was uploaded with the versionCode of
> the build already on the device; the install reported success and the device kept the old
> app, which looked exactly like the new window not working. With nothing playing, the main
> window's title strip now reads `EVERSOLO WINAMP <version>` so this is visible at a glance.

### Done and working on the device

| | |
|---|---|
| Library | 218 artists / 426 albums / 3,481 tracks. Walk 570 ms, tag pass 12.5 s |
| Playback | Tap a track and it plays through the device's own engine. Play/pause, next, previous, seek |
| Playlists | Cross-folder, add/remove/shuffle/clear, `.m3u` import, repeat-one handover |
| Overlay | Floating window above the stock player — the only way to keep our UI visible |
| **Main window** | **Classic base-2.91 skin: transport, LCD time, marquee, volume, position bar, PL toggle** |

### Built this session, awaiting a device check

**The skinned playlist editor** (`skin/PlaylistWindowView.java`), rendered from `pledit.bmp`:
tiled chrome at any legal size, track rows in the skin's own `pledit.txt` colours, the
selection blue, the playing track in white, the scrollbar, the running-time readout in the
skin's 5×6 font, the six mini transport buttons, and all five fly-out menus
(ADD / REM / SEL / MISC / LIST OPTS) drawn from the skin.

**How the two windows share the screen.** There is not room for both at a size where a
track row can be tapped: at ×4 a row is 52 px (about a finger), and a playlist worth
reading needs the whole 1080 px of height. So they take turns — PL swaps the main window
for the playlist, and the playlist's own X swaps back. The user chose this over shrinking
both to ×3 (12 rows of 39 px) or keeping both at ×4 (6 rows).

The playlist sizes itself from the space it is actually given rather than from
`DisplayMetrics`: the overlay is 2000 px wide on a 2160 px screen, and a window sized for
2160 would hang its scrollbar off the edge. On this device that lands on 500×261 skin px
at ×4 = 2000×1044, **15 rows**.

**Touch model:** a single tap selects, a double tap plays, and the window's own ▶ button
plays whatever is selected — so nothing is reachable only by a gesture (the v0.7 rule).

**The library browser** (`skin/BrowserWindowView.java`), on Winamp's *generic* window frame
from `gen.bmp` — the frame its library-style windows wear. Four ways in: ARTIST, ALBUM,
FOLDER and M3U, because a library ripped over twenty years is not consistently tagged and
sometimes the folder tree is the only thing that makes sense.

* **It is a browser and nothing else.** No transport, and tapping a track does not play it.
  Tapping a folder or album opens it; tapping a track selects it; ADD puts the selection in
  the playlist, or everything currently listed when nothing is selected — that second rule
  is what makes "add this whole album" one tap rather than thirteen.
* **gen.bmp marks its own pieces.** webamp has no media library and so no sprite table to
  copy, but every boundary in gen.bmp is drawn in RGB(0,198,255), a colour used nowhere
  else, so `tools/gen-window-sprites.py` measures the frame instead of guessing it. That
  matters most for the title alphabet, where every letter is a different width (I is 4 px,
  M and W are 8). The alphabet is capitals only, which is why window titles are plain words
  like ARTISTS and the album name goes in the bottom bar in the real font instead.
* **Two traps found in the preview, before any install:** a genex button is 15 px and the
  frame's bottom bar is 14, so buttons sit *inside* the window, not on the bar; and the
  skin's own 5×6 font is green for a black LCD and unreadable on a grey button, so button
  labels are drawn in the real font in near-black, as Winamp draws them.

**The spectrum analyser** (v0.14) — 19 bars in the 76×16 LCD at 24,43, coloured from the
skin's own `viscolor.txt` (0 background, 1 grid, 2–17 the gradient, 23 the peak dots — the
file's own comments confirm the roles). Tapping it turns it off and on, and the setting is
remembered.

Two things it has to get right: **the device is polled five times a second, not thirty** —
API_FINDINGS puts safe request spacing at 0.15 s and this is a small box that is also
decoding audio — and the drawn bars ease towards the measured ones at 25 fps, which is what
makes five updates a second look continuous. Only the vis rectangle is invalidated, so the
rest of the window is not repainted 25 times a second.

**The shape of the `getSpectrum` response was never captured** in the API survey, so the
parser takes whatever numeric array it finds (`fft_value`, `fft_level`, `freqs_value`) and
scales it against a slowly-decaying peak — self-calibrating whether the device reports
magnitudes or decibels. It logs the first raw response, so replace the guesswork with the
real shape from the on-device console once it has run.

**Zoom, ×1 / ×1.5 / ×2** (v0.14) — OPTIONS in the browser, MISC → MISC OPTS in the playlist.
A row 13 skin px tall is about 3 mm on this screen: fine to read, unpleasant to hit. Because
these are pixel art the zoom multiplies the *window scale* rather than stretching text, so
×1.5 of a ×4 window is drawn at ×6 — bigger everything, fewer rows, still crisp. ×2 asks for
×8, which would need a 250 px-wide window against Winamp's 275 px minimum, so it lands on
×7. `WindowScales` does that arithmetic and the JVM tests check every level fits.

**The browser marks what is already in the playlist** (v0.14): a filled square for all of
it, hollow for some. That is also the answer to "did ADD work?" — the message on the bottom
bar is the other half, and it is drawn *in the window* because this is an overlay above
everything and a Toast behind it is no feedback at all.

**The playlist survives a restart** (v0.16). The device has no queue to keep it in — that is
why the app owns the playlist at all — so it is written to the app's own files as an `.m3u`
of absolute paths, debounced 1.5 s while adding and written synchronously on the way out. It
is restored once the library scan finishes, because a path is only useful when it can be
turned back into a Track with its tags; anything since deleted is dropped with a count in
the log. REM → REM ALL and LIST OPTS → NEW LIST both clear it and delete the saved file.

**The light show's controls** (v0.16): a double tap changes the effect, a single tap brings a
CLOSE button to the corner for three seconds. The full screen is the point, so nothing sits
on top of it permanently.

**The two LCDs answer to a tap** (v0.15), as Winamp's did: the clock switches between
elapsed and remaining — with the minus sign, which really is a 5×1 sliver of pixels at 38,32
— and the title strip switches between the tags and the file name. The tags read
*song - album - artist*, taken from our own parsing where we have it, because the device
only reports the artist for local files.

**kbps / kHz / mono–stereo now show real figures** (v0.12). They had never been wired to
anything: `MainWindowView.setQuality` existed and nobody called it, so the two LCD boxes sat
empty. `getState.playingMusic` carries `sampleRateNumber`, `bitrate`, `bits` and `channels`;
when the device leaves them blank the values come from our own parsed tags instead, with the
bitrate as size over duration. The figures are right-aligned inside the boxes, which measure
x 109–126 and x 154–166 in `main.bmp`.

### Not done

* **Equalizer window** — not possible on this device, see the box at the top. The button
  explains itself rather than opening a decoration.
* **Oscilloscope mode** — Winamp's vis cycled analyser → oscilloscope → off. `getSpectrum`
  gives frequency data only, so there is nothing honest to draw an oscilloscope from; the
  tap toggles the analyser on and off instead.
* Playlist **windowshade** mode — the collapse button next to the X closes the window
  instead, which is honest but not what Winamp does.
* ADD URL, REM MISC and MISC OPTS toast an explanation instead of acting. ADD URL never
  will: the device's API refuses stream URLs.
* Visualiser (the device exposes live FFT via `getSpectrum` — see API_FINDINGS §4.4).
* Disk cache for the tag pass (12.5 s cold start; measured, deliberately not optimised yet).

### Phase 4 facts worth not rediscovering

1. **Android cannot decode most Winamp skin bitmaps.** The classic skin stores `MAIN.BMP`,
   `CBUTTONS.BMP`, `TEXT.BMP`, `NUMBERS.BMP`, `SHUFREP.BMP`, `POSBAR.BMP` and
   `MONOSTER.BMP` as **BI_RLE8**, which `BitmapFactory` returns null for. `skin/BmpDecoder.java`
   handles RLE8 plus 4/8/24/32-bit uncompressed. Without it the window renders empty.
2. **Sprite coordinates are GENERATED, not typed** — `skin/SkinSprites.java` comes from
   webamp's `skinSprites.ts` and `main-window.css` (both MIT). Do not use eva's
   `skinformat.json`: it contradicts itself (previous-track at both x=16 and x=93, balance
   at 174 instead of 177, mono/stereo at y=43 instead of 41).
3. **eva's bundled "classic" skin is not Winamp classic** — it is a light grey skin called
   *Audio Player*. The genuine article is `base-2.91.wsz`, bundled at
   `app/src/main/assets/skins/`. Nullsoft artwork; fine for personal use, worth noting if
   this ever goes further. A `.wsz` dropped in
   `/storage/emulated/0/EverSoloWinamp/skins/` overrides the bundled one.
4. **Skin filenames are inconsistently cased** (`MAIN.BMP` in the classic skin) — `Skin.java`
   keys everything on the lower-cased basename.
5. **Scaling must be by whole numbers** with nearest-neighbour. On this screen that is ×7
   (1925×812) with the panel closed and ×4 when it is open.
6. **Render previews on the desktop before building.** `tools/preview/bmpdec.py` is the same
   decoder in Python; compositing a preview from `SkinSprites.java` caught both the wrong
   skin and the RLE8 problem before either reached the device.
   `tools/preview/playlist_preview.py` does the same for the playlist window, including the
   fly-out menus (`--menu add`) and the exact on-device size (`--width 500 --height 261`).
7. **Sprite coordinates are generated, never typed.** `tools/gen-sprites.py` pulls them from
   webamp's `skinSprites.ts` and prints the Java. It regenerated the main-window table
   byte-for-byte, which is how the hand-typed character table was found to be missing `:`
   and space — the playlist's running-time readout would have shown `1234` for `12:34`.

### Test suite

`./tools/run-jvm-tests.sh` — **138 assertions, all passing.** Generates its own fixtures with
ffmpeg, compiles only the Android-free sources, runs on a desktop JVM. Covers FLAC/ID3 tag
parsing, `.m3u` parsing, the playlist model, and the playlist window's geometry (sizing in
25/29 px steps, scrolling, which row a tap landed on, scrollbar travel, `pledit.txt`
colours). There is no debugger on the device, so this is the safety net; run it before
every build.

---

## STATUS — 31 July 2026, end of first build session

**Phases 0, 1 and 2 are built and confirmed working on the device.** Source in `player/`,
current build `v0.5-transport`. Install by opening `http://<device>:18888` in a desktop
browser and selecting `~/Downloads/EversoloWinamp.apk`.

| | |
|---|---|
| Phase 0 dev harness | ✅ log ring buffer, on-device console (long-press the title), HTTP log shipper, crash handler |
| Phase 1 library | ✅ 218 artists / 426 albums / 3,481 tracks. Walk 570 ms, tags 12.5 s |
| Phase 2 playback | ✅ tap a track and it plays; play/pause, next, previous, seek all confirmed |
| Phase 3 playlists | ✅ cross-folder playlists, add/remove/shuffle/clear, .m3u import, repeat-one handover |
| Phase 4 skin | ⬜ **next** — see the "what done means" box below |

Current build `v0.8-fixes`. Confirmed working on the device 1 Aug 2026.

**Two Phase 3 bugs found on the device and fixed in v0.8** — both invisible off-device:

1. *Removing a track started playing others.* Two compounding faults: each play request
   spawned its own thread, so a second request while the first was still confirming meant
   two threads fighting over the device (a burst of tracks each playing for a fraction of a
   second); and removing the *playing* track left the current index at "none", from which
   the advance logic computed `-1 + 1 = 0` and started track one. Start requests are now
   serialised through one worker with a generation counter so stale ones bail, and
   advancing from "none" stops driving instead.
2. *The remove button was easy to miss.* Tapping the row means "play from here", so a
   near-miss started a track. The `✕` is now larger, red, and separated by a gap.

**Also fixed in v0.7:** per-row actions were hidden behind a long-press. Now every row has
a visible `+` (library) or `✕` (playlist) button. Long-press still works but nothing
depends on discovering it. Winamp used an explicit ADD button, not a gesture.

**Test coverage off-device: 81 assertions**, all green — 41 tag parsing, 17 `.m3u` parsing,
23 playlist model (including the index bookkeeping when inserting/removing around the
playing track). Run them with the harnesses in the session scratchpad; they need only a
desktop JVM because `:tags` and the playlist model carry no Android dependencies.

**The big architectural change this session: the player runs as a floating overlay window.**
Both ways of starting playback (`openFile` and DLNA) bring the stock Eversolo player to the
front, and an ordinary app cannot prevent that — the workaround of grabbing the screen back
looked broken. An overlay sidesteps it entirely: the stock player comes forward underneath
and is never seen. Confirmed working on the device. This also suits Winamp, which was
always a floating window rather than a full-screen app.

Requires the "display over other apps" permission, granted once via a button in the app.
There is a "Skip — run without it" fallback that runs the same UI as an ordinary screen.

**Two bugs found and fixed by running on the real device, both invisible from a laptop:**

1. The volume-root skip list was too aggressive and silently lost 29 real tracks sitting in
   a `Movies` folder. The walk takes 570 ms, so the optimisation was never worth it.
2. Transport commands were being issued from the UI thread, which Android forbids. The
   exception was swallowed into a log line, so the buttons did nothing *and said nothing*.
   All commands now go through a single-threaded queue and log their result.

**Next up:** Phase 3 (cross-folder playlists), where D7 matters more than expected — letting
the device advance within a folder avoids the stock player being triggered at all.

**Unresolved:** audible output was never independently confirmed. The device reports
volume 200/200 at 0 dB, unmuted, XLR — silence during testing may simply have been the
preamp turned down.

---

Ordered so the **riskiest unknowns are resolved earliest**. Every phase ends in something
you can see or hear on the device itself — no phase finishes with "the code compiles".

Companion documents: `ANSWERS_Q1_Q7.md` (what was measured), `ARCHITECTURE.md` (the layer
split), `API_FINDINGS.md` (the device API), `CLAUDE.md` (working rules).

---

## Where the risk actually sits, after testing

The brief expected the big unknown to be **whether an app can read the SSD** (Q1). It can,
easily — 5,037 files scanned in 366 ms. That risk is gone.

It has been replaced by a smaller but real one that nobody anticipated: **Android cannot
read the tags out of your FLAC files.** `MediaMetadataRetriever` returns `null` for
artist, album and title; MediaStore says `<unknown>`. Since the library is overwhelmingly
FLAC, we have to parse tags ourselves. That is now the first substantial piece of work.

The second risk is duller but will cost more time overall: **there is no ADB**, so no
`logcat`, no debugger, and every build has to be uploaded through a web page by hand.
Phase 0 exists entirely to stop that becoming a tax on every later phase.

---

## D5, the skin engine — DECIDED: fork `djshaji/eva` (MIT)

*Settled 31 July 2026.* The brief named `djshaji/WinampSkin` (Xenamp). It **does not
build** and its own README says it has been abandoned in favour of `djshaji/eva`. It was
flagged rather than silently substituted, and the user chose `eva` — Option A below.
Phase 4 proceeds on that basis; the alternatives are kept here for the record.

| Option | Licence | State | Effort |
|---|---|---|---|
| **A. Fork `eva`** *(recommended)* | **MIT** | Builds first time, maintained to Sept 2025, skin code has zero coupling to its player | Low — adapt an existing renderer |
| B. Revive Xenamp | GPL v2, no LICENSE file | Won't build; needs Gradle/AGP modernising and a Firebase strip; abandoned | Medium, on a dead codebase, and GPL v2 would infect the whole player |
| C. Write a `.wsz` renderer from scratch | Ours | Format is well documented | High — this is weeks, not days |

**Chosen: A.** MIT is materially better than GPL v2 here, it builds today, and its
playback layer is only 148 lines so replacing it with our transport is contained.

---

## Phase 0 — Get a build onto the device, and get its voice back

**Why first:** without `adb` there is no `logcat`. If Phase 1 misbehaves on the device we
would be debugging blind. Fix that before writing anything that can go wrong.

**Work**
- Create the Gradle project with the module split from `ARCHITECTURE.md` §3.
- `targetSdk 29`, `minSdk 26`, `requestLegacyExternalStorage="true"`,
  `READ_EXTERNAL_STORAGE` — the exact configuration proven to work in Q1.
- On-device log console: ring buffer, a screen reachable by a deliberate gesture, and an
  optional HTTP log shipper to a machine on the LAN (the technique the diagnostic APK
  used, which worked well).
- Crash handler that persists stack traces instead of losing them.
- A written, repeatable install recipe: build → `http://<device>:18888` → select APK →
  open on device.

**Done when:** the app is on the player, shows its version and screen metrics on screen,
and you can make it log a line that appears on the dev machine. Roughly 30 seconds per
build-install cycle, and it is documented.

**Risk:** low. Every part of this has already been done once with the diagnostic APK.

---

## Phase 1 — Read the library, with correct artist and album names

**Why now:** this is the largest remaining unknown. Everything visible in the app depends
on it, and if FLAC tag parsing turns out harder than expected, we want to know in week one
rather than after the UI is built.

**Work**
- Volume discovery: enumerate `/storage/*` at runtime, plus
  `/storage/emulated/0/EverSoloMusic`. Do **not** hardcode `EF42-73B2` — that ID is
  specific to how this unit's SSD was formatted.
- Recursive scan for audio extensions.
- **FLAC tag parser**, written directly against the format: `fLaC` marker, then metadata
  blocks — `STREAMINFO` (type 0) for sample rate / bit depth / duration, `VORBIS_COMMENT`
  (type 4) for the tags, `PICTURE` (type 6) for embedded art. A few hundred lines, no
  dependency, no licence question.
- Assess whether `MediaMetadataRetriever` handles the MP3s (untested — ID3 support is
  historically better than FLAC support). If not, extend to ID3v2.
- Path-derived fallback for untagged files, using this library's consistent
  `Artist/[M] Album [id] [year]/NN - Artist - Title.flac` convention. It must never
  override real tags.
- A plain list UI — no skin yet. Artist → album → track, and a scan-timing readout.

**Done when:** the player's screen shows your real library, browsable by artist and album,
with correct names for FLAC files, and reports how long the scan took.

**Risks**
- *Tag parsing is more work than estimated.* Mitigated by writing the FLAC parser first —
  it covers most of the library and the format is simple and stable.
- *Tag pass is slow.* Directory scanning is 366 ms, but opening ~5,000 files to read tags
  is a different cost. If it hurts, add a disk cache keyed on `path + size + mtime` and
  scan in the background. **Measure before optimising.**
- *DSD/DSF and APE files.* Present in the library (there is a DSD Pink Floyd folder).
  Lower priority than FLAC and MP3; handle or skip explicitly, don't crash.

---

## Phase 2 — Make it play

**Why now:** it is the whole point, and it proves the transport layer against the real
device before any UI complexity exists.

**Work**
- `PlaybackEngine` interface plus `EversoloHttpEngine` against `127.0.0.1:9529`.
- `play()` = `openFile` **then verify** via `getState` that `playingMusic.title` matches
  what was requested — `openFile` returns `200` for files it silently refuses to play.
  Return `false` on mismatch.
- Pause/resume (remember `playOrPause` is a **toggle** — read state first), seek, volume.
- The state poller: 500 ms baseline, publishing immutable snapshots.
- Wire it to the plain list from Phase 1.

**Done when:** you tap a track on the player's screen and it plays, bit-perfect, through
the Eversolo's own engine. Pause, resume, seek and volume all work, and the screen shows
live position and duration.

**Risk:** low. Every one of these calls has been exercised against the real device.

---

## Phase 3 — Playlists in your order, across folders

**Why now:** this is decision D1, the reason the app exists rather than just using the
stock interface. It is also the only genuinely delicate logic.

**Work**
- The app-owned playlist: add, insert, remove, reorder, shuffle, clear.
- `.m3u` import (D6): parse it ourselves, resolve relative paths, drop missing entries and
  say how many were dropped. The device silently ignores `.m3u` files entirely.
- Sequencing with the handover from `ARCHITECTURE.md` §5:
  - set `setLoopMode?loop=1` while the app is driving, so the device repeats instead of
    running off into the next file in the folder;
  - fire the next `openFile` at roughly `duration - 400 ms`;
  - safety net: if position suddenly drops to near zero, repeat-one fired and we were
    late — fire the next track immediately.
- **Tune the 400 ms lead on-device.** It is derived from a Wi-Fi measurement (~0.2 s) and
  loopback will be faster.
- Restore the user's loop mode when the app stops driving playback.

**Done when:** you build a playlist from tracks in several different folders, press play,
and it plays all the way through unattended, in your order, with clean transitions.

**Risks**
- *Transitions are audibly rough.* The lead time is the tuning knob; the safety net trades
  a small audible restart for never playing the wrong track.
- *The user's device queue gets replaced.* Unavoidable — `openFile` always replaces it.
  Worth a one-time note in the UI rather than a silent surprise.

---

## Phase 4 — Make it look like Winamp

> ### What "done" means (clarified by the user, 1 Aug 2026)
>
> **The main Winamp window IS the player.** It is what you see when the app opens: title
> bar with the scrolling track name, time display, visualiser, transport buttons, volume
> and balance sliders, and the EQ / PL toggles.
>
> **The playlist is a SEPARATE window**, opened with the **PL** button and closable again.
> The user can keep it on screen or dismiss it. It is not the primary view and must not
> behave like one.
>
> **The look is the classic default Winamp 2.x base skin** — the one everyone pictures.
> Not a reinterpretation, not "Winamp-inspired".
>
> Everything built in Phases 1–3 is **scaffolding for the layers underneath** (library,
> playback, sequencing). The plain list UI is a test harness and **none of it survives into
> Phase 4** — it exists only to prove the machinery works before the real face goes on.

**Why now:** deliberately after playback works. A beautiful interface over a broken player
is the classic way to waste a month.

**Work**
- Fork `eva`, strip its ExoPlayer playback (148 lines) and its Play Billing dependency,
  keep `.wsz` parsing, sprite mapping, bitmap fonts and hit-testing.
- Render at an **integer** scale with **nearest-neighbour** filtering — these are pixel-art
  skins and anything else looks soft.
- Choose the layout for a 2000 × 1080 usable area:
  - **×7** — main transport window only, 1925 × 812;
  - **×3** — the full classic stack (main + equaliser + playlist), 825 × 1044.
- Bind the skin's buttons and sliders to `PlaybackEngine` and the playlist.
- Check touch targets on the device early, especially playlist rows at ×3.

**Done when:** the player shows a real Winamp skin from the Skin Museum, and its buttons
actually drive playback.

**Risks**
- *`eva`'s renderer assumes phone-shaped screens.* This display is wide and short — which
  suits Winamp — but the layout code may need work.
- *Skin coverage varies.* Some of the 65,000 skins use features a clone does not
  implement. Pick two or three known-good skins as the target.

---

## Phase 5 — The things that make it feel finished

- **Spectrum analyser** driven by `getSpectrum`, which returns live FFT data
  (`fft_level`, `fft_value`, `freqs_value`, `nb_freqs`) from the device's own DSP. Real
  Winamp visualisation, driven by the actual audio engine.
- Draggable seek bar — Q7 confirmed position reporting is accurate enough.
- Album art from embedded `PICTURE` blocks, falling back to `Cover.jpg` in the folder.
- Skin switching at runtime.
- Search across the library.

---

## Explicitly deferred

**D7, the gapless optimisation.** When the next playlist track happens to be the next file
in the same folder, let the device's own queue carry it instead of issuing a fresh
`openFile`. **Do not build this now.** The architecture keeps it inside
`EversoloHttpEngine`, so it can be added later without touching the playlist, the library
or the UI. Q2's repeat-one finding makes it easier to add safely than it would have been.

---

## Rough sequencing

| Phase | Relative size | Gate |
|---|---|---|
| 0 — dev harness | Small | — |
| 1 — library + tags | **Largest remaining unknown** | — |
| 2 — playback | Small–medium | Phase 1 |
| 3 — playlist + handover | Medium, fiddly | Phase 2 |
| 4 — skin | Medium | Phase 3 |
| 5 — polish | Ongoing | Phase 4 |

Phases 0–3 give a working, ugly, genuinely useful player. Phase 4 makes it the thing you
actually want. If effort has to be cut, cut Phase 5, not Phase 0.
