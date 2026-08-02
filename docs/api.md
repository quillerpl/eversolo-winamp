# The device's HTTP API

Base `http://127.0.0.1:9529` from the app, `http://<device>:9529` from a laptop. **No
authentication**, so you can query it yourself while debugging — reads are safe, anything
that changes state needs asking the owner first.

Full survey in `../API_FINDINGS.md`. This page is what the app actually relies on.

## What we use

| Endpoint | Params | Notes |
|---|---|---|
| `/ZidooFileControl/openFile` | `path`, `type=0` | **Play a specific file.** The key finding |
| `/ZidooMusicControl/v2/getState` | — | Now playing, position, volume, format. Poll it |
| `/ZidooMusicControl/v2/playOrPause` | — | A **toggle**, not "pause" — read the state first |
| `/ZidooMusicControl/v2/setLoopMode` | `loop` 0 or 1 | **`loop=1` is repeat-one**, which stops the device advancing by itself |
| `/ZidooMusicControl/v2/seekTo` | `time` (ms) | |
| `/ZidooMusicControl/v2/setDevicesVolume` | `volume` 0–200 | 200 = 0 dB = full output. **Not a percentage** |
| `/ZidooMusicControl/v2/playNext`, `playLast` | — | Only used when we are not driving the playlist |

## Gotchas, each of which cost real time

1. **HTTP 200 for everything.** The real status is in the JSON body: `405` means no such
   command, `805` means wrong parameters, `801` means wrong path family. That 405-vs-805
   distinction is how the API was mapped safely.
2. **`openFile` returns 200 for files it silently ignores** (`.m3u`, `.cue`). Always confirm
   against `getState` and compare the title.
3. **`openFile` replaces the device's whole queue** with the chosen track's containing
   folder. That is why the app drives the playlist one track at a time.
4. **There is no queue manipulation at all** — every `addToQueue`-style call is 405.
5. **`playMusic` accepts an `id` and ignores it.** Targeting is by file path only.
6. **Queue item IDs are hashes**, different from library IDs for the same track. Match on
   path or title, never ID.
7. **`getState` misspells a key: `currenttVolume`**, two t's. That is the device's spelling.
8. **Positions and durations are milliseconds.**

## What getState carries that is easy to miss

* `playingMusic.sampleRateNumber`, `bitrate` (text, e.g. `"1411.20 Kbps"`), `bits`,
  `channels` — everything the main window's kbps/kHz/mono-stereo displays need. Not every
  source fills them in, so fall back to our own parsed tags.
* `everSoloPlayInfo.isHasSpectrum` and `isHasDSP` — **both false on this unit**, which is
  the whole story behind `decisions.md`.

## Rate

Poll `getState` about twice a second; 0.15 s is the documented safe spacing over Wi-Fi, and
this is a small box that is also decoding audio. From the app it is a loopback call, which
is cheaper, but be considerate anyway.
