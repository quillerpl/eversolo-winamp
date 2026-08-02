# Eversolo Winamp

A classic **Winamp 2.x** player for the **Eversolo DMP-A6**, running as a sideloaded
Android app on the streamer's own 6-inch touchscreen.

![The player running at 2160x1080](docs/images/on-device.png)

*The player as it actually runs: the main window at ×7, 1925 × 812 on the DMP-A6's
2160 × 1080 screen.*

It does not play the audio itself. It drives the DMP-A6's built-in playback engine over the
device's local HTTP API, which bypasses Android's sample-rate conversion and reaches the
DACs bit-perfect. That is the whole point of the design: the stock engine still does all the
audio, and this only replaces its face.

> **This is a weekend project, built for fun and out of nostalgia.** One person, one
> streamer, an undocumented API and a strong memory of what a music player used to look
> like. It is not a product and it is not polished to one: expect rough edges, expect
> decisions made because they were interesting rather than because they were optimal, and
> expect it to break the moment Eversolo changes their firmware. If it is useful to you,
> that is a happy accident — please do not file it under "supported software".
>
> Where the hardware cannot do something, the app says
> so rather than faking it, and the reasoning is written down in `docs/decisions.md`.

## Download and install

**[Download the latest version](https://github.com/quillerpl/eversolo-winamp/releases/latest)**

Every version, with notes on what changed, is on the
[Releases page](https://github.com/quillerpl/eversolo-winamp/releases).

You do not need a cable, a special program, or developer mode. The Eversolo has an installer
built into it that you use from an ordinary web browser.

1. **Download the `.apk` file** from the link above, onto a computer that is on the same
   wi-fi as the streamer.
2. **Find the streamer's address.** It looks like `192.168.1.XXX` 
3. **In your browser, go to `http://` that address `:18888`** — so for the example above,
   `http://192.168.1.XXX:18888`. A page appears asking for a file. Choose the `.apk` you
   downloaded. It uploads and installs itself.
4. **On the streamer, open "Eversolo Winamp".** It is in the apps list.
5. **Say yes to "display over other apps."** The app explains why and shows a button that
   takes you straight to the switch. Turn it on and come back.
6. **Give it a skin** — it does not come with one, and the app will ask. See
   [Getting a skin](#getting-a-skin) just below.

To update later, download the newer `.apk` and upload it exactly the same way.

**A phone or tablet will not work for step 3** — the Eversolo's upload page needs a real
desktop browser. If you have no computer to hand, copy the `.apk` onto a USB stick and open
it with the Eversolo's own File app instead.

## Tested on

An **Eversolo DMP-A6**, `rockchip DMP-A6`, Android 11 (API 30), firmware **v1.5.90**, on its
own 2160 × 1080 touchscreen. That is the only hardware this has ever run on. Other Eversolo
models share the API family and may well work, but nobody has tried.

## Getting a skin

**Downloaded builds do not include one.** The player draws itself from a Winamp 2.x `.wsz`
skin, and those are other people's artwork — the classic skin belongs to Winamp's owner and
is not mine to hand out. See `THIRD-PARTY-NOTICES.md`.

On first run the app asks for one. Grab a `.wsz` from
[skins.webamp.org](https://skins.webamp.org) — there are tens of thousands — and put it on a
USB stick or anywhere on the device — it searches for it. Change it later from the **Winamp logo
in the player's bottom-right corner**:

![The skin chooser](docs/images/skin-chooser.png)


> **Status:** in daily use on the author's own DMP-A6. All three windows work, playback and
> the playlist work, and so does the spectrum analyser — which decodes the playing file
> itself, because the device's `getSpectrum` turns out to be a hollow endpoint
> (`isHasSpectrum` is false on every source). There is no equalizer and there will not be:
> the API has no tone control of any kind, and sliders wired to nothing would contradict the
> reason this exists. [`docs/status.md`](docs/status.md) has the current state and the short
> list of things not yet confirmed on hardware.

---

## The windows

Three, as Winamp had, drawn from an ordinary `.wsz` skin. On a 6-inch screen there is no
room for two at a size where a track row can be tapped, so they take turns: **PL** swaps the
main window for the playlist, and the playlist's **X** swaps back.

### Playlist editor

![The playlist window](docs/images/playlist-window.png)

Rendered from the skin's `pledit.bmp` at 2000×1044 — fifteen tracks, 52 px rows. The colours
come from the skin's own `pledit.txt`. All five fly-out menus work (ADD, REM, SEL, MISC and
LIST OPTS), and the running-time readout uses the skin's 5×6 bitmap font.

A single tap selects, a double tap plays, and the window's own ▶ button plays whatever is
selected — nothing is reachable only by a gesture. MISC → MISC OPTS scales the window
×1 / ×1.5 / ×2, because a 13-pixel row is about 3 mm on this screen: fine to read,
unpleasant to hit.

### Spectrum analyser

Nineteen bars in the main window's little LCD, coloured from the skin's own `viscolor.txt`,
with the peak markers falling as they used to. Tap it to turn it off.

The data does not come from the device. `getSpectrum` answers `{}` for every source on this
unit and `getState` reports `isHasSpectrum: false`, yet the stock player's analyser plainly
works — because the stock player is the thing decoding the audio. So this decodes the same
file a second time with MediaCodec and runs its own FFT, paced against the wall clock and
re-seeking whenever it drifts from the position the device reports. Nothing is played and
nothing touches the output, so the bit-perfect path is untouched.

The FFT and the band mapping live in `:dsp`, a plain-Java module with no Android in it, so
the maths is proved against sine waves of known pitch on a laptop rather than guessed at on
a device with no debugger.

### Library browser

![The browser window](docs/images/browser-window.png)

On Winamp's *generic* window frame (`gen.bmp`), with the title in that bitmap's own
alphabet. Four ways in — **artist, album, folder and .m3u** — because a library ripped over
twenty years is not consistently tagged, and sometimes the folder tree is the only view that
makes sense.

It is a browser and nothing else: no transport, and tapping a track does not play it.
Tapping a folder or album opens it, tapping a track selects it, and **ADD** puts the
selection into the playlist — or everything currently listed, if nothing is selected.

A square down the left of a row means it is already in the playlist: filled for all of it,
hollow for some. That, and the line along the bottom, are drawn inside the window rather
than shown as a Toast — the player is an overlay above everything, so a Toast behind it
would be no feedback at all.

---

## Why it is built the way it is

The DMP-A6 is a peculiar target, and most of the design follows from what it will not do.

| Constraint | Consequence |
|---|---|
| **No usable ADB.** Port 5555 accepts TCP but does not speak the protocol, and there is no reachable developer menu | No logcat and no debugger. The app carries its own ring-buffer log, an on-screen console (long-press the title bar) and an HTTP log shipper. Anything provable on a laptop is proved on a laptop |
| **The API has no queue manipulation at all** — every `addToQueue`-style call returns "Method Not Allowed" | The app owns the playlist and feeds the device one track at a time |
| **`openFile` replaces the device's whole queue** with the chosen track's containing folder | Playback is targeted by file path only; nothing can be addressed by id |
| **The API returns HTTP 200 for everything**, including files it silently refuses to play | Every play is confirmed against `getState` rather than trusted |
| **Starting playback brings the stock player to the front**, by either route, and an ordinary app cannot prevent it | The player runs as a floating overlay window, which sidesteps the fight entirely — and suits Winamp, which was always a floating window |
| **Android cannot read FLAC tags on this device** — `MediaMetadataRetriever` returns null | FLAC and ID3 tags are parsed by hand, in a module with no Android dependencies so it can be unit-tested |
| **Android cannot decode most Winamp skin bitmaps** — the classic skin is largely BI_RLE8, which `BitmapFactory` returns null for | The project ships its own BMP decoder |

---

## Finding your way around

The documentation is deliberately split in two, because most of it only matters when you are
touching a particular part — and because reading it all costs about 30,000 tokens if you are
an LLM.

**[`docs/`](docs/) is the front door.** [`docs/00-index.md`](docs/00-index.md) is a page long
and routes you to the one file that answers your question: `playback.md`, `skinning.md`,
`windows.md`, `library.md`, `analyser.md`, `api.md`, `device.md`, `build-install.md`,
`testing.md`, `modules.md`, `decisions.md`, `status.md`. Each is short enough to read whole.

The long documents at the root — [API_FINDINGS.md](API_FINDINGS.md) (the full endpoint
survey), [ANSWERS_Q1_Q7.md](ANSWERS_Q1_Q7.md) (what was measured on real hardware),
[ARCHITECTURE.md](ARCHITECTURE.md) and [PROJECT_PLAN.md](PROJECT_PLAN.md) (the history) —
are the deep reference. Go there when a `docs/` page sends you.

---

## Building

Android Studio's JDK 21, Gradle 8.13, AGP 8.12.1, `compileSdk 34`, `targetSdk 29`
(`targetSdk 29` plus `requestLegacyExternalStorage` is the combination that gives direct
file access on this firmware; a sideloaded app has no Play Store targeting requirement).

```bash
# 1. point Gradle at your SDK
echo "sdk.dir=$HOME/Library/Android/sdk" > player/local.properties
# 2. build
cd player && ./gradlew assembleDebug        # for yourself: bundles the skin below
cd player && ./gradlew assembleRelease      # for other people: no skin, needs a signing key
```

**The debug build bundles a skin, the release build does not.** Drop a `.wsz` into
`player/app/src/debug/assets/skins/` and `assembleDebug` will carry it, so the app runs the
moment it lands on your own device. `assembleRelease` deliberately ships without one — see
`THIRD-PARTY-NOTICES.md` — and asks the user for a skin on first run.

Release signing reads `player/keystore.properties`, which is git-ignored. Without it the
release build still runs; it just comes out unsigned.

**Bump `versionCode` in `player/app/build.gradle` before every build you intend to
install.** With an unchanged versionCode the device's installer reports success and quietly
keeps the app it already has, which looks exactly like the new code doing nothing.

### Installing a build you made yourself

There is no ADB, so builds go on through the device's own web installer: open
`http://<device>:18888` in a **real desktop browser** and choose the APK. Driving that
upload from `curl` does not work — four approaches were tried and the server returns its
index page every time. A USB stick and the device's File app is the documented fallback.

With nothing playing, the main window's title strip shows the version that is actually
running, which is the quickest way to confirm an install took.

### Testing, without a device

```bash
./player/tools/run-jvm-tests.sh     # 254 assertions on a desktop JVM
```

It generates real audio fixtures with ffmpeg and compiles only the Android-free sources: the
tag parsers, the `.m3u` parser, the playlist model, and the window geometry. This exists
because there is no debugger on the device.

Skins can be rendered on the desktop before an install cycle, from the same sprite tables
the app uses:

```bash
./player/tools/preview/playlist_preview.py --width 500 --height 261 --scale 4 --menu add
./player/tools/preview/browser_preview.py
```

That has caught five real bugs so far without an install cycle: the wrong skin being
bundled, the RLE8 decoding problem, buttons one pixel too tall for the frame they sat on,
button labels drawn in the skin's green LCD font (unreadable on grey), and a title bar
assembled from the wrong gen.bmp pieces, which broke the gold lines every 25 px.

Sprite coordinates are **generated, never typed** — `tools/gen-sprites.py` derives the main
and playlist tables from webamp, and `tools/gen-window-sprites.py` measures the generic
window frame out of `gen.bmp`, which marks its own pieces in a colour used nowhere else.

---

## Layout

```
player/                 the app
  app/                  window management, the browser model, the library scan
  core/                 logging, crash handler, log shipper
  library/              volume discovery, filesystem scan, the index
  tags/                 FLAC and ID3 parsers, .m3u parser   (no Android dependencies)
  playback/             the device's HTTP transport
  playlist/             the playlist model and sequencing   (no Android dependencies)
  dsp/                  FFT and band mapping                (no Android dependencies)
  skin/                 .wsz parsing, BMP decoding, and the three windows
  tools/                sprite generators, desktop previews, the JVM test runner
API_FINDINGS.md         the device's HTTP API: endpoints, schemas, limits
ANSWERS_Q1_Q7.md        what was measured on real hardware, and what was not
ARCHITECTURE.md         layer split and interfaces
PROJECT_PLAN.md         phased milestones and current status
CLAUDE.md               working rules and inherited facts
probe.py, enumerate.py  the API discovery tooling
discover2.py            SSDP discovery — find the device rather than hardcoding it
```

## Scope

Not a streaming client, not a replacement for the stock interface, not a remote control (it
runs *on* the device), not internet radio — the API rejects stream URLs — and not a firmware
project. No root, no patched system apps.

## Licence and attribution

MIT — see [LICENSE](LICENSE). Third-party material is listed in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md); in short, sprite coordinates were derived
from [webamp](https://github.com/captbaritone/webamp) (MIT), and **no Winamp skin is
redistributed here** — you supply your own.

Not affiliated with Eversolo/Zidoo or with Winamp/Nullsoft. It talks to an undocumented
local API on hardware its owner paid for, and it will very likely break on a firmware
update.
