# CLAUDE.md — Eversolo Winamp Player

Working rules and inherited facts for this project. Read this first, then
`ANSWERS_Q1_Q7.md` for what was measured, `API_FINDINGS.md` for the device API,
`ARCHITECTURE.md` for the layer split and `PROJECT_PLAN.md` for phasing.

**The user is not a developer.** Explain in plain language. When you need something from
them, say exactly what to type or click. Do not assume they can review code for
correctness — verify it yourself and show evidence.

---

## What this is

A local file player styled as classic Winamp 2.x, running as a **sideloaded Android app on
the Eversolo DMP-A6's own 6-inch touchscreen**, operated by touch.

It does **not** play audio itself. It drives the device's built-in playback engine, which
bypasses Android's sample-rate conversion and reaches the DACs bit-perfect. That is the
entire reason for the design.

---

## Device facts

| | |
|---|---|
| Model | Eversolo DMP-A6, `rockchip DMP-A6` |
| OS | Android 11 (API 30) |
| Firmware | v1.5.90 |
| Hardware | quad-core Cortex-A55, 4 GB RAM, 32 GB eMMC |
| Screen | **2160 × 1080 px**, 320 dpi, density 2.0 → 1 dp = 2 px |
| App window | **2000 × 1080 px** (160 px system chrome) = 1000 × 540 dp |
| Network | **Wi-Fi** (ARP MAC matches `wif_mac`, not `net_mac`) |
| Last seen at | 192.168.1.207 — **do not hardcode**, see below |

**The screen is NOT 1280×480**, despite what spec sheets and forums say. It was measured
on-device. It is a wide, short 2:1 display, which happens to suit Winamp's layout well.

### Ports

| Port | Service |
|---|---|
| **9529** | The control API. From the app itself use `http://127.0.0.1:9529` |
| 1118 | UPnP/DLNA MediaRenderer |
| **18888** | Web APK installer — **this is how builds get onto the device** |
| 5555 | Open but **dead** — accepts TCP, does not speak ADB |
| 9530 | Open, returns empty HTTP replies, unidentified |

### Storage

```
/storage/EF42-73B2                  ~4,869 audio files  (the SSD; volume ID is unit-specific)
/storage/emulated/0/EverSoloMusic   ~168 audio files    (internal flash)
```

Discover volumes at runtime by enumerating `/storage/*`. **Never hardcode `EF42-73B2`** —
that ID comes from how this particular SSD was formatted.

---

## Hard constraints

- **Ask before any call that changes device state.** The user's play queue — including six
  internet radio stations — was destroyed by an early probing session and could not be
  restored, because the API has no queue-manipulation endpoints. The originals are in
  `state_before.json`. Do not repeat this.
- **Never trust `status: 200` from `openFile`.** It returns success for files it silently
  refuses to play (`.m3u`, `.cue`). Always confirm via `getState` and compare
  `playingMusic.title` against what was requested.
- **Rate-limit requests: 0.15 s minimum spacing.** It is a small Android box.
- **Do not install anything on the device without asking first.**
- **Discover the device, do not hardcode it.** Use `discover2.py` (SSDP). Note that ping
  and TCP port scans are unreliable against it — Wi-Fi power-saving means it drops roughly
  1 request in 25 with occasional 5-second stalls. SSDP and real HTTP requests are
  reliable; bare `connect()` probes are not. **This affects remote tooling only** — the
  shipped app talks to `127.0.0.1` and sees none of it.
- **There is no authentication on this API.** Anything on the LAN can control the device.
  Do not treat the network as a security boundary.
- **Do not modify the device firmware.** No root, no patching system apps, no OTA
  tampering. Everything here is a normal sideloaded app.

## Out of scope

Not a streaming client (no Tidal/Qobuz/Spotify/Apple Music). Not a replacement for the
stock interface. Not a mobile remote — it runs on the device. Not internet radio (the API
rejects stream URLs). Not a settings app. Not a firmware project.

If you find yourself designing something in this list, stop and ask.

---

## Decisions (settled — do not relitigate)

- **D1. App-driven playback.** The app owns the playlist; the user adds files in any order
  across any folders; the app feeds the device one track at a time via `openFile`.
  *Because the API has no queue manipulation at all.*
- **D2. The library is read natively from the filesystem, not through the API.**
  *Confirmed by testing: 5,037 files scanned in 366 ms, versus ~25 s for a fraction of that
  through `getFileList`.*
- **D3. The API is used only for playback control and live state** — `openFile`,
  `playOrPause`, `playNext`, `playLast`, `seekTo`, volume, `setLoopMode`, `getState`,
  `getSpectrum`. Never for browsing.
- **D4. The playback layer sits behind an interface and stays swappable.**
- **D5. Skin engine: fork `djshaji/eva` (MIT). — SETTLED 31 Jul 2026.** The brief named
  `djshaji/WinampSkin` (Xenamp); it does not build, is abandoned by its own author, and is
  GPL v2, which would have forced the whole player to be GPL v2. The user chose `eva` after
  being shown the trade-off. Strip its ExoPlayer playback (148 lines) and its Play Billing
  dependency; keep `.wsz` parsing, sprite mapping, bitmap fonts and hit-testing.
  **What actually happened (1 Aug):** the fork never took place. `eva`'s `skinformat.json`
  turned out to contradict itself, so the sprite tables came from webamp instead and the
  engine — `.wsz` loading, the BMP decoder, the bitmap fonts, hit-testing — was written
  here. No eva code is in the tree; see `THIRD-PARTY-NOTICES.md`.
- **D6. The app parses `.m3u` files itself.** The device accepts them, returns success and
  silently does nothing.
- **D7. Gapless optimisation is deferred.** Design so it can be added inside
  `EversoloHttpEngine` later. Do not build it now.

---

## Gotchas that will bite you

1. **The API returns HTTP 200 for everything.** The real status is in the JSON body.
   `405` + `"Method Not Allowed: [name]"` = no such command; `805` = command exists but
   parameters are wrong; `801` = wrong path family. This 405-vs-805 distinction is how the
   API was mapped and is a safe way to test for a command's existence without running it.
2. **`playOrPause` is a toggle**, not "pause". Read the state first.
3. **`playMusic` accepts an `id` and ignores it.** It is "resume current", not
   "play track N". Playback targeting is **by file path only**.
4. **`openFile` replaces the device's whole queue** with the chosen track's containing
   folder. Confirmed folder-based, not a database album lookup.
5. **`setLoopMode` does no validation.** `loop=99` is accepted and echoed back by
   `getState`. Only `loop=0` (advance) and `loop=1` (repeat-one) have been verified
   behaviourally.
6. **Queue item IDs are hashes, not library database IDs** — the same track is `4466` in
   `searchMusic` and `1660025809` in the queue. Match on path or title, never on ID.
7. **`getState` misspells a key: `currenttVolume`** (two t's). That is the device's
   spelling.
8. **Volume is 0–200 where 200 = 0 dB = full output.** It is not a percentage.
9. **Android cannot read FLAC tags on this device.** `MediaMetadataRetriever` returns
   `null` for artist/album/title (duration works); MediaStore reports `<unknown>`. We parse
   tags ourselves. This is the project's main technical risk.
10. **Positions and durations are milliseconds** throughout.
11. **There is no equalizer, tone control or filter in this API.** Nothing to drive, and
    this unit reports `dspActive=false`, `hasDspSetting=false`, `isHasDSP=false`. Do not
    build an EQ window: it would be sliders wired to nothing, and it would contradict the
    reason the project exists — the path to the DACs stays untouched.
12. **MilkDrop, AVS and Geiss cannot be ported here.** They read live PCM at frame rate —
    butterchurn calls `getByteTimeDomainData` every frame — and this app never touches the
    audio. Do not promise presets; the light show behind the EQ button is FFT-driven and
    says so.
13. **This device has no spectrum. `getSpectrum` always returns `{}`.** Proven on the unit:
    while playing, eight samples in a row, with every parameter and display mode tried.
    `getState.everSoloPlayInfo.isHasSpectrum` is **false** in every capture ever taken, for
    local files and internet radio alike. There is no audio data available to this app at
    all — no waveform, no bands, no level. Anything "beat-synced" is off the table unless
    that flag ever turns true. **The analyser gets its data by decoding the playing file
    itself** (`FileSpectrum`) - which is what the stock player does, since it is the thing
    decoding the audio. Only works for tracks this app started: that is when we know a path.
14. **You can query the device yourself.** It is on the LAN with no authentication, so
    `curl http://192.168.1.207:9529/ZidooMusicControl/v2/getSpectrum` beats asking the user
    to read a log off the screen. Reads are safe; anything that changes state needs asking
    first - though the user granted control explicitly on 2 Aug.
15. **Poll it 80 ms full-screen, 180 ms for the LCD analyser**, and let it back off. Safe request spacing is 0.15 s; the window
    eases between frames to look continuous.
16. **`getState.playingMusic` carries the audio format** — `sampleRateNumber` (Hz),
    `bitrate` (text, e.g. `"1411.20 Kbps"`), `bits` and `channels`. That is everything the
    main window's kbps / kHz / mono-stereo displays need. Not every source fills them in,
    so fall back to our own parsed tags rather than showing a guess.

---

## The development loop

**There is no ADB.** Port 5555 accepts connections but does not speak the protocol, and
the device has no reachable Developer options. So: **no `logcat`, no debugger, no
`adb install`.**

To get a build onto the device:

1. **Bump `versionCode` in `player/app/build.gradle`.** Non-negotiable: with an unchanged
   versionCode the install completes and the device quietly keeps the app it already has.
   This cost a whole round trip once — the new code looked like it did nothing.
2. Build the APK on the Mac.
3. Open **`http://192.168.1.207:18888`** in a normal desktop browser.
4. Click **Select APK File** and choose it. It uploads and installs.
5. Open the app on the player's screen. With nothing playing, the title strip reads
   `EVERSOLO WINAMP <version>` — that is how to tell which build is actually running,
   and the same string is the first line in the on-device log.

Driving that upload from `curl` does **not** work — four approaches were tried (plain,
`Expect:` suppressed, HTTP/1.0, headless browser) and the server returns its static index
page every time with no file appearing on the device. A real browser works. USB-stick
sideloading via the device's File app is the documented fallback.

**Because there is no `logcat`, the app must carry its own diagnostics** — an in-memory log
ring buffer, an on-screen log console reachable by a gesture, and an optional HTTP log
shipper to a dev machine. Build this in Phase 0. Debugging blind is the biggest tax on
this project.

Toolchain that is known to work: **JDK 21** (Android Studio's bundled JBR), **Gradle 8.13**,
**AGP 8.12.1**, compileSdk 34–36. Build the app with **`targetSdk 29`** and
`requestLegacyExternalStorage="true"` — that is the configuration proven to give direct
file access, and a sideloaded app has no Play Store targeting requirement.

---

## The app (`player/`)

Modules: `:app` (UI hosts) · `:core` (logging) · `:tags` (pure-Java parsers) ·
`:library` (scan + index) · `:playback` (device transport) · `:playlist` (sequencing) ·
`:skin` (Winamp rendering) · `:dsp` (FFT and band mapping, plain Java so it can be tested).

**Build:** `cd player && ./gradlew assembleDebug` with JDK 21 (Android Studio's JBR),
Gradle 8.13, AGP 8.12.1, `targetSdk 29`. `player/local.properties` holds the SDK path;
without it the build stops with "SDK location not found".

**Test before you build:** `./player/tools/run-jvm-tests.sh` — 138 assertions on a desktop
JVM. It generates real fixtures with ffmpeg and compiles only the Android-free sources
(the tag parsers, the playlist model, and `PlaylistGeometry`/`PleditStyle`, which are
deliberately Android-free so they can be tested here). There is no debugger on the device.

**Preview skins before you build:** `player/tools/preview/bmpdec.py` is the BMP decoder in
Python; `playlist_preview.py` renders the whole playlist window from the same sprite table
the app uses. Compositing a preview catches layout and decoding problems without an install
cycle. It has already caught three.

**Never type sprite coordinates:** `player/tools/gen-sprites.py` generates them from
webamp's `skinSprites.ts` (and `--font` for the `text.bmp` character table). The one table
that was typed by hand was missing `:` and space.

### Skin gotchas (Phase 4)

* **`BitmapFactory` cannot read most skin bitmaps** — the classic skin is largely
  **BI_RLE8**, which Android returns null for. Use `skin/BmpDecoder.java`.
* **`skin/SkinSprites.java` is generated** from webamp's `skinSprites.ts` and
  `main-window.css` (MIT). Do not hand-edit it, and do not trust eva's `skinformat.json` —
  it contradicts itself in several places.
* **Filenames vary in case** (`MAIN.BMP` vs `main.bmp`); `Skin.java` lower-cases everything.
* **Scale by whole numbers only**, nearest-neighbour. Fractional scaling ruins pixel art.
* eva's bundled "classic" skin is *not* Winamp classic. The real one is `base-2.91.wsz`.
* **The playlist window is the only resizable one**, and only in steps: legal sizes are
  275 + 25n wide and 58 + 29n tall, because its borders are repeating tiles that size.
  `PlaylistGeometry` is the single source of truth for every position inside it.
* **The track list is real text, not bitmaps.** Winamp names the font and four colours in
  the skin's `pledit.txt`; `PleditStyle` reads them. Everything else in the window is
  pixel art and must stay nearest-neighbour, so the view keeps two Paints.
* **Size windows from the view's own measured size, not `DisplayMetrics`** — the overlay is
  2000 px wide on a 2160 px screen and anything sized for 2160 hangs off the edge.
* **`gen.bmp` marks its own pieces** in RGB(0,198,255), so `tools/gen-window-sprites.py`
  measures the generic frame rather than guessing it. Its title alphabet is **capitals
  only** and every letter is a different width — window titles must be plain A–Z words.
* **A `genex.bmp` button is 15 px; the generic frame's bottom bar is 14.** Buttons go inside
  the window, not on the bar.
* **The skin's 5×6 font is green, for a black LCD.** On the grey genex buttons it is
  unreadable — button labels use the real font in near-black, as Winamp draws them.

## Reference files in this folder

| File | What it is |
|---|---|
| `API_FINDINGS.md` | The device HTTP API — endpoints, schemas, limits |
| `ANSWERS_Q1_Q7.md` | What was measured on the real device, and what wasn't |
| `ARCHITECTURE.md` | Layer split and interfaces |
| `PROJECT_PLAN.md` | Phased milestones |
| `discover2.py` | **SSDP discovery — use this to find the device** |
| `probe.py`, `enumerate.py` | The API discovery tooling |
| `state_before.json` | The user's original play queue and radio URLs |
| `EversoloStorageTest.apk` | The Q1/Q5 diagnostic build (source in `storagetest/`) |
