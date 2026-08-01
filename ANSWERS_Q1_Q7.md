# Addendum: Answers to Q1–Q7

Recorded 31 July 2026, against the real device (DMP-A6, firmware v1.5.90, 192.168.1.207).
**What was actually observed, not what was expected.** Where something was not measured,
it says so.

---

## Q1. Can a sideloaded app read the storage volume directly? — **YES**

Answered by building and installing a purpose-made diagnostic APK
(`EversoloStorageTest`, source in `storagetest/`).

The route that works: **target SDK 29 + `android:requestLegacyExternalStorage="true"`
+ `READ_EXTERNAL_STORAGE`**. A sideloaded app has no Play Store targeting requirement, so
it can target 29, and Android 11 still honours legacy storage for apps that do.

Observed on the device:

```
DIRECT FILE ACCESS: YES
  legacy mode                 : true
  isExternalStorageManager    : false   (not needed)
  /storage/EF42-73B2 entries  : 76      canRead=true
  /storage/emulated/0 entries : 22      canRead=true
  read file bytes             : OK (32 bytes, starts 49 44 33 = "ID3")
```

`MANAGE_EXTERNAL_STORAGE` ("All files access") was **not** required and is not granted.
Plain legacy storage was sufficient.

**Full library scan: 5,037 audio files in 366 ms**, unbounded depth, across both volumes.
For comparison, walking a *fraction* of the library through the device's `getFileList` API
took ~25 seconds. Direct access is roughly two orders of magnitude faster.

**Decision D2 is confirmed and is strongly the right choice.**

### Q1a. Two music locations, not one (new)

Previous work only knew about the SSD. There are two:

| Path | Audio files | Notes |
|---|---|---|
| `/storage/EF42-73B2` | 4,869 | The SSD. Volume label `Internal` in the device API |
| `/storage/emulated/0/EverSoloMusic` | 168 | Internal flash. Where the Eversolo app stores music |
| `/storage/emulated/0/Music` | 0 | Standard Android folder, empty |

The library scanner must cover both.

### Q1b. Tags are NOT readable via standard Android APIs (new, and a real risk)

This was not anticipated by the brief and is the most consequential finding of the session.

`MediaMetadataRetriever` on a FLAC (`01 - AC-DC - Hells Bells.flac`):

```
  artist  = null
  album   = null
  title   = null
  track   = null   year = null
  embedded artwork: none
  durationMs = 312815      <- only this worked
```

So the file opens and decodes (duration is correct), but **Vorbis comments are not
exposed**. This agrees with MediaStore, which has 3,486 tracks indexed but reports
`<unknown>` for artist.

Both of Android's built-in metadata routes therefore fail on FLAC, which is the bulk of
this library. **A dedicated tag parser is required** — see `PROJECT_PLAN.md` Phase 1,
where it is scheduled first precisely because it is the largest remaining unknown.

Not tested: whether `MediaMetadataRetriever` reads ID3 tags from the MP3s correctly.
ID3 support is historically better than FLAC support, but it is unconfirmed here, and it
does not change the conclusion because the library is mostly FLAC.

### Q1c. MediaStore is not a usable index

3,486 tracks indexed vs 5,037 actually present — incomplete — and with broken artist
metadata. Do not build the library on it.

### Q1d. What the device API filtered out

Direct listing sees **76** root entries on the SSD; `getFileList` reports **68**. The 8
extra are all hidden/junk files: `.DS_Store`, `._`-prefixed macOS sidecars, and one
`.smbdeleteAAA…` temp file. **No music was hidden** — the API simply filters dotfiles,
which is sensible. This difference is not a reason to prefer direct access; speed and
tags are.

---

## Q2. Does a repeat-one / stop-after-track mode exist? — **YES**

Discovered with the 405-vs-805 oracle, then **proven behaviourally**, not assumed.

**`GET /ZidooMusicControl/v2/setLoopMode?loop=N`** — the parameter is `loop`
(not `mode`, `index`, `type`, `model` or `value`, all of which were tried and silently
ignored). `getState.loopModel` reflects the value.

The behavioural test — seek to 8 seconds before the end of a track and watch:

| Mode | Result at end of track |
|---|---|
| `loop=0` | **Advanced** to the next track (`03. Bluebird` → `04. Take Me On And On`) |
| `loop=1` | **Stayed** on the same track |

**`loop=1` is repeat-one and it does stop the device advancing on its own.**

This is the high-value outcome the brief hoped for. It does not eliminate the handover
entirely, but it changes the failure mode from *harmful* to *harmless*: if the app is late
issuing the next track, the device repeats the current one (recoverable) instead of
running off into the next file in the folder (wrong track, and it fights the app).

**Caveat: `setLoopMode` does no validation.** `loop=99` was accepted and stored, and
`getState` dutifully reported `loopModel: 99`. So the value being echoed back proves
nothing about it being a real mode. Values 2, 3 and 4 are accepted but their meanings
were **not** determined — presumably list-repeat and shuffle, but that is a guess and is
not recorded here as fact.

Left at `loop=0`.

---

## Q3. `openFile` latency, request to audible playback — **~0.2 s**

Six runs across three different FLAC files:

| Run | HTTP call returns | Playback confirmed |
|---|---|---|
| 1 | 0.06 s | 0.95 s (contaminated — track was already loaded, position 74 s) |
| 2 | 0.09 s | 0.19 s |
| 3 | 0.05 s | 0.14 s |
| 4 | 0.09 s | 0.20 s |
| 5 | 0.11 s | 0.21 s |
| 6 | 0.06 s | 0.18 s |

**The HTTP call returns in under 0.1 s; playback is confirmed within ~0.2 s.**
Run 1 is excluded — the file was already loaded and mid-track.

Two caveats, both of which mean the real figure is *better* than measured:

1. **Measured over Wi-Fi from a Mac.** The finished app runs *on* the device and will
   call `127.0.0.1:9529`, removing the network entirely.
2. **Polling resolution was 0.15 s**, so ~0.2 s is an upper bound, not a precise value.

**Measured device reliability over Wi-Fi:** 24/25 requests succeeded; one hard timeout,
and latency spikes to 1.4 s and 5.1 s. This is Wi-Fi power-saving (the device is on
Wi-Fi — the ARP MAC matches `wif_mac`, not `net_mac`). **The shipped app will not
experience this**, but any remote tooling must retry.

---

## Q4. Does Xenamp build, and is its skin engine separable? — **NO to the first**

### Xenamp (`djshaji/WinampSkin`) — not viable

* **Build FAILED.** `Unsupported class file major version 61` — it is pinned to
  Gradle 7.0.2 / AGP 7.0.2, which cannot run on the installed JDK 17. It also uses the
  shut-down `jcenter()` repository and Firebase Crashlytics.
* **It is abandoned.** The first line of its own README: *"This project has been written
  from scratch here: https://github.com/djshaji/eva"*.
* **Licence: GPL v2** (stated in README; there is no LICENSE file). Reusing it would make
  the whole player GPL v2.
* Single `:app` module — the skin engine is not packaged as a reusable library.

### `djshaji/eva` — the successor, and it works

Evaluated because Xenamp's own README points to it. **Flagged rather than silently
substituted**, per the brief's instruction.

* **Build SUCCEEDED** first attempt — Gradle 8.13, AGP 8.12.1, compileSdk 36, minSdk 26.
  Produced a working 10 MB APK.
* **Licence: MIT** — materially better than GPL v2 for this purpose.
* Last pushed 2 September 2025.
* **The skin engine is genuinely decoupled from the player**, which is what D4 needs:

| File | Lines | References to the audio player |
|---|---|---|
| `Skin.java` (`.wsz` parsing) | 167 | **0** |
| `UI.java` (skin rendering) | 1,289 | 1 |
| `MainActivity.java` | 952 | 3 |
| `Player.java` | 76 | 2 |
| `MediaService.java` | 72 | 5 |

The entire playback layer is **148 lines** (`Player` + `MediaService`, media3/ExoPlayer).
Replacing that with the Eversolo transport is a small, well-bounded change.

* Still a single `:app` module, so it is a **fork-and-adapt** base, not a drop-in library.
* Carries a Google Play Billing dependency that should be removed.

**D5 needs a decision from the user** — see `PROJECT_PLAN.md` Phase 4.

---

## Q5. Screen resolution and density — **2160×1080, 320 dpi, density 2.0**

Measured on-device via `Display.getRealMetrics()`.

```
real resolution : 2160 x 1080 px
app window      : 2000 x 1080 px      <- 160 px taken by system chrome
densityDpi      : 320   (xhdpi bucket)
density         : 2.0   -> 1 dp = 2 px
usable          : 1000 x 540 dp
rotation        : 0
```

**This is not the 1280×480 that the spec sheets and forums suggest.** It is a wide,
short 2:1 display — unusually shaped for a Winamp layout, which is itself wide and short,
so this works out well.

Ignore the "physical diagonal 7.55 inches" my tool printed: `xdpi`/`ydpi` were reported as
320.0, which is the density bucket rather than a true physical measurement, so that
derived figure is meaningless. The marketing figure is 6 inches.

**What fits, in the 2000×1080 usable area:**

| Layout | Native size | Max integer scale | Result |
|---|---|---|---|
| Main window only | 275 × 116 | **×7** | 1925 × 812 px |
| Main + equaliser + playlist stacked | 275 × 348 | **×3** | 825 × 1044 px |

Integer scaling matters: Winamp skins are pixel art, and non-integer scaling will look
soft and wrong. Both options are viable and this is a design choice, not a constraint.

---

## Q6. How does an app get installed and launched? — **Web installer, port 18888**

### ADB over the network — does NOT work

* Port **5555 is open** and accepts TCP connections, which is misleading.
* It **does not speak ADB**: a correctly-formed `CNXN` handshake packet got no reply at
  all (not even `AUTH`), and the connection timed out. `adb connect` reports the device as
  permanently `offline` across repeated attempts and a clean server restart.
* The user could not find Developer options anywhere in the device's menus.

**Consequence: there is no `adb`, and therefore no `logcat`, no `adb install`, and no
debugger.** This is a significant development constraint and shapes the architecture —
see `ARCHITECTURE.md` §7 on the on-device logging channel.

### The web installer — works

**`http://<device>:18888/`** — "Upload an app and install it on your device". A Zidoo
component using jQuery Huploadify. Upload endpoint: `POST /?fallback=1`, multipart form,
field name `file`.

Driving it from `curl` failed in four different ways (plain, `Expect:` suppressed,
HTTP/1.0, and via a headless browser): the server returns its static index page each time
and no APK appears anywhere on the device filesystem. **Uploading through a normal desktop
browser works** — that is how both diagnostic builds were installed.

A sideloaded app **does** get a launcher entry (the test app appeared and was launchable).

USB-stick sideloading via the device's File app is also available and is the route
[Eversolo document themselves](https://shop.zidoo.tv/blogs/news/third-party-software-installation).

**Practical dev loop: build on the Mac → open `http://<device>:18888` in a browser →
select the APK → open the app on the player.** Roughly 20–30 seconds and a few clicks per
iteration. Not scriptable so far.

Also noted: port **9530** is open but returns an empty reply to HTTP. Not identified.

---

## Q7. Is `seekTo` reliable, and is reported position accurate? — **YES**

`GET /ZidooMusicControl/v2/seekTo?time=<milliseconds>`

| Requested | Reported 1.2 s later | Drift |
|---|---|---|
| 30,000 ms | 31,531 ms | +1,531 ms |
| 120,000 ms | 121,554 ms | +1,554 ms |
| 60,000 ms | 61,616 ms | +1,616 ms |
| 5,000 ms | 6,355 ms | +1,355 ms |

Drift is consistently +1.4 to +1.6 s, which is exactly the ~1.2 s of playback that elapsed
during the measurement sleep plus network round-trip. **The seek itself is accurate and
position reporting is trustworthy** — good enough for a draggable seek bar.

Position and duration are both in milliseconds.

---

## Summary of what changed for the plan

| Decision | Status after testing |
|---|---|
| **D1** app-driven playlist | Confirmed, and made safer by Q2's repeat-one mode |
| **D2** native filesystem library | **Confirmed** — 5,037 files in 366 ms. But **tag parsing is now a real work item**, not a given |
| **D3** API for control only | Confirmed, plus `setLoopMode` added to the list |
| **D4** swappable playback layer | Confirmed and reinforced — eva's playback layer is only 148 lines |
| **D5** evaluate Xenamp | **Xenamp is not viable.** `eva` is, and is MIT rather than GPL v2. **Decided 31 Jul 2026: fork `eva`** |
| **D6** app parses `.m3u` | Unchanged |
| **D7** gapless deferred | Unchanged, and Q2 makes it easier to add later |

**No decision in section 2 of the brief was invalidated.** D5's named candidate failed,
but the decision itself (evaluate before writing a skin engine from scratch) survives with
a different, better candidate.
