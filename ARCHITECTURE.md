# Architecture — Winamp-Style Player for Eversolo DMP-A6

> **Deep reference.** For the module map and how the layers meet, read `docs/modules.md`
> instead — it is a page. This is the long-form version of the same thing.
Companion documents: `API_FINDINGS.md` (the device API), `ANSWERS_Q1_Q7.md` (what was
measured on the real device), `PROJECT_PLAN.md` (phasing), `CLAUDE.md` (working rules).

---

## 1. The shape of it, in plain language

The app runs **on the Eversolo itself**, on its own screen, operated by touch. It does
**not** play the audio. It reads your music files to build a library, then tells the
Eversolo's own playback engine which file to play — because that engine is what gets the
audio to the DACs bit-perfect, and anything we played ourselves would go through Android's
mixer and lose that.

So there are four jobs, and they are kept apart on purpose:

1. **Library** — read the files on the SSD, work out artist/album/title, hold an index.
2. **Playlist** — the list the user builds, in the order they chose. Ours, not the device's.
3. **Playback** — tell the device what to play, and watch what it is doing.
4. **Skin** — draw the Winamp interface and turn touches into commands.

The reason for keeping them apart is that #3 is the one most likely to change later
(decision D4), and none of the other three should have to care if it does.

---

## 2. Why the playback layer is separated

Two changes are plausible later, and both should be invisible to the rest of the app:

* **The gapless optimisation (D7, deferred).** When the next playlist track happens to be
  the next file in the same folder, let the device's own queue carry it instead of issuing
  a fresh `openFile`. That is an internal detail of the transport.
* **Playing audio ourselves.** If it ever turns out we can reach the device's audio path
  directly, the transport swaps and nothing above it changes.

Hence a single interface, `PlaybackEngine`, with exactly one implementation for now.

---

## 3. Module layout

Gradle modules, so the boundaries are enforced by the compiler rather than by good
intentions:

```
:app        Activity, wiring, log console                       [built, Phase 0/1]
:core       Logging ring buffer, log shipper, crash handler      [built, Phase 0]
:tags       Pure-Java tag parsers - NO Android dependencies      [built, Phase 1]
:library    Volume discovery, scanner, in-memory index           [built, Phase 1]
:playlist   App-owned playlist and the sequencing/handover       [Phase 3]
:playback   PlaybackEngine interface + EversoloHttpEngine        [Phase 2]
:skin       .wsz parsing and the Winamp rendering surface        [Phase 4]
```

Two modules were added during Phase 0/1 that this document did not originally foresee:

* **`:core`** — the logging stack. It needed to be depended on by everything including
  `:library`, so it could not live in `:app` as first sketched (see §7 — with no ADB, this
  is not optional infrastructure).
* **`:tags`** — a **plain `java-library` module with no Android dependencies**, so the tag
  parsers can be compiled and tested on a desktop JVM. Given there is no debugger on the
  device, anything provable off-device should be proven off-device. This paid for itself
  immediately: 41 assertions against real ffmpeg-generated FLAC and MP3 files, covering
  unicode, 24-bit, embedded artwork, multi-disc paths and corrupt input.

Dependency direction, strictly one-way:

```
              :app
        /      |      \        \
  :library  :playlist  :skin   :core
     |  \        |
 :tags   :core  :playback
```

`:playback` knows nothing about the library, the playlist or the UI. `:skin` knows nothing
about playback — it renders state and emits UI events. This mirrors how `eva` is already
structured (its `Skin.java` has zero references to its player), which is why `eva` is a
credible base.

---

## 4. The library layer (`:library`)

**Confirmed feasible.** A sideloaded app targeting SDK 29 with
`requestLegacyExternalStorage` can read both volumes directly, and a full scan of
**5,037 files took 366 ms** (`ANSWERS_Q1_Q7.md` §Q1).

### Roots to scan

```
/storage/EF42-73B2                    ~4,869 audio files   (the SSD)
/storage/emulated/0/EverSoloMusic     ~168 audio files     (internal flash)
```

Discover volumes at runtime rather than hardcoding — the SSD's volume ID
(`EF42-73B2`) is specific to this unit's formatting. Enumerate `/storage/*`, skip
`self` and `emulated`, and always include `/storage/emulated/0/EverSoloMusic`.

### Tag parsing — the one real risk

Android's built-in metadata APIs **do not work** for this library:
`MediaMetadataRetriever` returns `null` for artist/album/title on FLAC (duration works),
and MediaStore reports `<unknown>` artists and indexes only 3,486 of 5,037 files.

So `:library` needs its own tag reading. Behind an interface, because the choice may
change once measured:

```java
public interface TagReader {
    /** Returns null if this reader cannot handle the file. */
    TrackTags read(File file);
}

public final class TrackTags {
    public final String title, artist, album, albumArtist, genre;
    public final Integer trackNumber, discNumber, year;
    public final long durationMs;
    public final int sampleRate, bitDepth, channels;
    public final byte[] embeddedArt;   // may be null
}
```

Candidate implementations, in the order Phase 1 should try them:

1. **A direct FLAC metadata parser.** FLAC's format is simple and well documented: a
   `fLaC` marker followed by metadata blocks; we need `STREAMINFO` (type 0, for sample
   rate / bit depth / duration) and `VORBIS_COMMENT` (type 4, for the tags), optionally
   `PICTURE` (type 6, for art). This is a few hundred lines, has no dependency and no
   licence question, and covers the overwhelming majority of the library.
2. **JAudioTagger** (or an Android fork) for the remaining formats. LGPL — needs a
   licence check before adoption.
3. **`MediaMetadataRetriever` for MP3/ID3 only**, if it proves to work there (untested).
4. **Path-derived fallback.** This library is consistently organised
   (`Artist/[M] Album [id] [year]/NN - Artist - Title.flac`), so a filename parser is a
   usable last resort and a good sanity check — but it must never silently override real
   tags.

### Index and cache

Scanning is fast enough (366 ms) that a cold scan on every launch is acceptable, so
**do not build a complex cache in Phase 1**. Hold the index in memory. If tag parsing
turns out to dominate the time — likely, since it means opening thousands of files —
add a disk cache keyed on `path + size + mtime`, refreshed in the background.

Measure before optimising. The scan is fast; the tag pass is the unknown.

---

## 5. The playlist layer (`:playlist`)

The playlist is **ours**. The device's queue is a side effect we tolerate, not a thing we
use (`API_FINDINGS.md` §6: there are no queue-manipulation endpoints, and `openFile`
replaces the device queue with the chosen track's whole folder).

```java
public interface Playlist {
    void add(Track t); void addAll(List<Track> t);
    void insert(int index, Track t);
    void remove(int index); void move(int from, int to);
    void clear(); void shuffle();
    List<Track> tracks(); int currentIndex();
}
```

`.m3u` import (D6) lives here: parse the file ourselves, resolve relative paths against
the playlist's own folder, drop entries that no longer exist, and report how many were
dropped rather than failing silently.

### Sequencing and the track handover

This is the only genuinely delicate piece, and Q2 and Q3 between them make it tractable.

**Set `setLoopMode?loop=1` (repeat-one) whenever the app is driving playback.** Proven in
Q2: with `loop=0` the device advances to the next file in the folder on its own — which is
almost always the *wrong* track for an app-owned playlist, and it fights us. With
`loop=1` it repeats instead. That converts a harmful failure into a harmless one.

The handover, given `openFile` becomes audible in ~0.2 s (Q3, and faster on-device since
it is a loopback call):

```
poll getState every 500 ms
  when (duration - position) < 5000 ms  -> tighten polling to 100 ms
  when (duration - position) < 400 ms   -> openFile(next track)     [primary path]
  if position suddenly drops near zero  -> repeat-one fired; openFile(next) now
                                                                    [safety net]
```

The 400 ms lead clips the last fraction of a second, which is nearly always a fade tail
or silence, and avoids the audible restart that the safety-net path produces. The safety
net exists because Wi-Fi stalls and GC pauses happen; without repeat-one it would be the
device playing an unrelated track, which is far worse.

**Tune the 400 ms once measured on-device.** It is a starting value derived from a
Wi-Fi measurement, not a tuned constant.

---

## 6. The playback layer (`:playback`)

```java
public interface PlaybackEngine {
    /** Start this specific track. Returns false if the device did not actually start it. */
    boolean play(Track track);
    void pause(); void resume(); void stop();
    void seekTo(long ms);
    void setVolume(int zeroTo200);
    PlaybackState state();          // snapshot: playing/paused/idle, position, duration, title
    void addListener(Listener l);   // fired from the state poller
}
```

### `EversoloHttpEngine` — the only implementation for now

Talks to **`http://127.0.0.1:9529`** — the app runs on the device, so this is a loopback
call. None of the Wi-Fi flakiness measured from the Mac (1 failure in 25, spikes to 5 s)
applies to the shipped app.

| Operation | Call |
|---|---|
| Play a track | `GET /ZidooFileControl/openFile?path=<urlencoded absolute path>&type=0` |
| Pause / resume | `GET /ZidooMusicControl/v2/playOrPause` |
| Seek | `GET /ZidooMusicControl/v2/seekTo?time=<ms>` |
| Volume | `GET /ZidooMusicControl/v2/setDevicesVolume?volume=<0..200>` |
| Repeat-one | `GET /ZidooMusicControl/v2/setLoopMode?loop=1` |
| State | `GET /ZidooMusicControl/v2/getState` |
| Spectrum | `GET /ZidooMusicControl/v2/getSpectrum` |

**Two rules this layer must enforce, both learned the hard way:**

1. **Never trust `status: 200` from `openFile`.** It returns success for files it silently
   refuses to play — `.m3u` and `.cue` return 200 and do nothing (`API_FINDINGS.md` §2).
   `play()` must confirm via `getState` that `playingMusic.title` matches what was asked
   for, and return `false` if it does not. This is why `play()` returns a boolean.
2. **`playOrPause` is a toggle, not "pause".** Read the state first, or you will start
   playback when you meant to stop it.

### State polling

One poller, owned by this layer, publishing immutable `PlaybackState` snapshots. 500 ms
baseline, 100 ms near a track boundary. `getState` is ~2.8 KB; over loopback that is
negligible, but it is still the busiest thing the app does, so it belongs in one place
rather than being called ad hoc from the UI.

---

## 7. Logging and telemetry — a consequence of having no ADB

**There is no `adb`, so there is no `logcat`, no debugger and no `adb install`**
(`ANSWERS_Q1_Q7.md` §Q6). This is an architectural constraint, not just an inconvenience:
if the app misbehaves on the device, there is by default no way to see why.

So the app carries its own diagnostics from Phase 0:

* An in-memory ring buffer of recent log lines.
* A **log console screen** reachable by a deliberate gesture (e.g. long-press the Winamp
  titlebar), so the device can be diagnosed standing in front of it.
* An **optional HTTP log shipper** that POSTs to a dev machine on the LAN, off by default
  and configurable. This is exactly the trick the diagnostic APK used to report its
  findings, and it worked well.
* A crash handler that writes the stack trace somewhere readable rather than vanishing.

Build this first. Debugging blind is the single biggest tax on this project.

---

## 8. The skin layer (`:skin`)

**Base: fork `djshaji/eva` (MIT).** Xenamp does not build and is abandoned by its own
author; `eva` builds first time and its skin code has zero coupling to its audio player
(`ANSWERS_Q1_Q7.md` §Q4). See `PROJECT_PLAN.md` Phase 4 — this needs a user decision
before work starts.

What to take: `.wsz` parsing (a zip of BMPs plus config), the bitmap/sprite mapping, the
bitmap font and number rendering, and the region/hit-testing for buttons and sliders.
What to discard: its ExoPlayer playback (148 lines) and its Google Play Billing dependency.

### Scaling — the display is 2160×1080, usable 2000×1080, density 2.0

Winamp skins are **pixel art**, so scaling must be by **integer factors** and use
**nearest-neighbour** filtering. Anything else looks soft and wrong.

| Layout | Native | Max integer scale | Rendered |
|---|---|---|---|
| Main window only | 275 × 116 | ×7 | 1925 × 812 |
| Main + equaliser + playlist | 275 × 348 | ×3 | 825 × 1044 |

Both fit. This is a design choice: one big beautiful transport, or the full classic stack.
A wide, short 2:1 screen suits the Winamp aesthetic unusually well.

Touch targets scale with the art. At ×7 even small Winamp buttons become comfortably
tappable; at ×3 the playlist rows will be the tightest thing and should be checked on the
device early.

---

## 9. Data model

```java
public final class Track {
    public final String absolutePath;   // what openFile needs; the identity of a track
    public final String title, artist, album, albumArtist;
    public final int trackNumber, discNumber, year;
    public final long durationMs, fileSize;
    public final String extension;
    public final int sampleRate, bitDepth, channels;
}
```

**`absolutePath` is the identity.** Not any device ID: the library database IDs are only
reachable through a search API capped at 30 results, and the play-queue IDs are hashes
that differ from the database IDs for the same track (`API_FINDINGS.md` §5). The file path
is the only stable, complete identifier, and it is exactly what `openFile` consumes.

---

## 10. What is deliberately absent

* No streaming services, no internet radio (the API rejects stream URLs).
* No settings application beyond what the player needs.
* No attempt to replace or modify the stock Eversolo interface.
* No firmware modification, no root.
* No use of the device's own library database for browsing — it cannot enumerate
  (30-result cap, title-only matching).
