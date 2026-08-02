# Eversolo DMP-A6 — Local HTTP API Findings

> **Deep reference.** The endpoints the app actually uses, and the traps, are summarised in
> `docs/api.md` — start there. This is the full survey: every endpoint tried, what answered,
> and what it returned.
Investigated 31 July 2026 against a real device on the LAN.
Device: **DMP-A6**, firmware **v1.5.90**, Android 11, IP **192.168.1.207**, API port **9529**.

---

## 1. The verdict, in plain English

**Can the API list the music library?** **Yes.**

**Can the API start playback of a specific track you choose?** **Yes.**

Both halves of the key question are answered, and both were verified against the real
device — not inferred from documentation. A Winamp-style front end that drives the
device's own playback engine is therefore buildable on this API alone.

The one catch worth knowing up front: there are **two different ways to see your music**,
and only one of them can enumerate everything.

| Route | What it gives you | Verdict |
|---|---|---|
| **File browser** (`getFileList`) | Walks the SSD folder-by-folder. Complete, no limits. Returns filenames, sizes, folder structure. | **Use this to build the library.** |
| **Music database** (`searchMusic` / `searchAlbum` / `searchArtist`) | Rich tagged metadata — artist, album, title, year, sample rate, bit depth, track number, album ID. But **hard-capped at 30 results and it ignores paging.** | Use for the search box only. Cannot enumerate the library. |

The practical design is to walk the filesystem for structure, and use the database
search for metadata and for the search feature.

---

## 2. How to play a chosen track — the exact sequence

This is the answer to the core question. Three calls, all plain HTTP GETs.

```
# 1. Find the storage volumes
GET http://192.168.1.207:9529/ZidooFileControl/getDevices
    -> {"status":200,"devices":[
         {"name":"Flash",   "path":"/storage/emulated/0","type":1000},
         {"name":"Internal","path":"/storage/EF42-73B2", "type":1002}]}

# 2. Walk to the track you want (repeat per folder level)
GET http://192.168.1.207:9529/ZidooFileControl/getFileList?path=<URL-encoded folder>&type=0
    -> {"status":200,"isExists":true,"perentPath":"...","filelist":[
         {"name":"02 - Leonard Cohen - Amen.flac","type":1,"path":"%2Fstorage%2F...","length":82911232,"modifyDate":1706701090000}]}

# 3. Play it
GET http://192.168.1.207:9529/ZidooFileControl/openFile?path=<URL-encoded absolute path>&type=0
    -> {"status":200}
```

Verified working example (this actually played on the device):

```
GET /ZidooFileControl/openFile?path=%2Fstorage%2FEF42-73B2%2FLeonard%20Cohen%2F%5BM%5D%20Old%20Ideas%20%5B32570025%5D%20%5B2012%5D%2F02%20-%20Leonard%20Cohen%20-%20Amen.flac&type=0
```

### What `openFile` actually does — important behaviour

* It plays **the track you named**, starting immediately. Confirmed: `getState` then
  reported `state: 3` (playing) with the correct title, and `position` advancing.
* It **also loads every audio file in that track's folder into the play queue**, in
  folder order, and positions itself at your chosen track. Playing track 2 of a 10-track
  folder gives you a 10-item queue sitting at index 1.
* The queue is built from the **folder on disk**, *not* from a database album lookup.
  Proven with a 3-CD box set (`.../Gipsy Kings.../CD2`): playing a track inside `CD2`
  produced an 18-item queue — exactly `CD2`'s 18 files — not the whole 3-CD release.
  Folder = queue. This makes the behaviour predictable for a file-browser UI.
* It **replaces** the existing play queue. There is no merge or append.
* `type` is **required** (omitting it returns error 805) but its value appears to be
  **ignored for audio** — 0, 1, 2, 4 and 5 all behaved identically. Use `type=0`.
* Pointing it at a **folder path does not work** — returns error 805. Files only.
  (To play a folder, address a file inside it — see below.)
* It does **not** accept a stream URL — passing an `http://…` radio URL returns 805.
  Internet radio must be started some other way (not found in this session).
* **It brings the Eversolo's own player to the front.** *(Found 31 Jul 2026 when the first
  playing build ran on the device.)* `openFile` behaves like an Android "open this file"
  intent: it starts playback **and** foregrounds the stock music UI, pushing any other app
  into the background. For a remote controller this is invisible and harmless. For an app
  running **on** the device it is a real problem — the front end loses the screen every
  time it starts a track.
  - **There is no background variant.** Verified: names like `openFileNoUI`,
    `openFileSilent` and `openFileInBackground` all appear to exist, but so does
    `openFileZZZQQQNONSENSE` — the router **prefix-matches on `openFile`**, so those are
    false positives. `openFile` is the only real command. Beware this trap when probing
    this family; the 805-vs-804 oracle is unreliable for any name starting with a valid
    command's prefix.
  - Extra parameters (`background=1`, `showUI=0`, `silent=1`, …) are ignored.
  - **Workaround:** the app takes the screen back with `moveTaskToFront` (needs
    `REORDER_TASKS`, a normal permission) fired repeatedly after the call, because the
    stock player does not appear at a predictable moment. This costs a visible flash.
  - **Design consequence:** playing consecutive tracks from the *same folder* via the
    device's own queue advance (`playNext`, or simply letting it run on) causes **no**
    takeover. Only `openFile` does. That makes the deferred gapless optimisation (D7)
    considerably more valuable than originally thought — it removes the flash as well as
    the gap.

### Playing a whole folder

There is no "play this folder" call, but you don't need one: **call `openFile` on the
first audio file in the folder.** The device queues the entire folder and starts at
track 1. To start mid-folder, name that file instead.

```
GET /ZidooFileControl/getFileList?path=<folder>&type=0     # take entries with type == 1
GET /ZidooFileControl/openFile?path=<folder>/<first audio file>&type=0
```

Verified: `.../Guns N' Roses - Greatest Hits.../01.-Welcome To The Jungle.flac`
→ 18-item queue, playing track 1.

Sort the `filelist` entries yourself before picking the first — the device returns them
in its own order, and you want the same track order the user sees in your UI.

### Playlist files (.m3u, .cue) are NOT supported

**`openFile` silently ignores playlist files.** This is the one real trap in this API.

Tested against a real `.m3u` (`00.-Guns N' Roses - Greatest Hits.m3u`) and a real
`.cue`: both returned **`{"status":200}`** — an apparent success — while playback and the
queue **did not change at all**. Verified rigorously: reset to a distinct track
(Leonard Cohen), called `openFile` on the `.m3u`, and Leonard Cohen was still playing with
his 10-item queue intact. A real `.flac` in that same folder worked immediately, proving
the folder and the path encoding were fine and only the playlist file was rejected.

**Consequence: `status: 200` from `openFile` does not mean playback started.** Always
confirm with `getState` (check `playingMusic.title` / `state`) rather than trusting the
response code.

So playlist support has to be **your app's job**: parse the `.m3u` yourself, then drive
the tracks one at a time via `openFile`. Cue-sheet *rips* are unaffected in practice —
the `.cue` files seen here sit alongside already-split per-track FLACs, which play
normally.

---

## 3. How the device reports errors — the discovery oracle

Worth documenting because it is how the rest of this map was built, and you will hit
these codes while developing.

**The device returns HTTP 200 for essentially everything.** The real status is in the
JSON body. Never test on the HTTP status code.

| Body `status` | Meaning |
|---|---|
| `200` (or no `status` field at all) | Success |
| `401` | Item not found — e.g. `Audio not find when id = 735, type = LOCAL` |
| `405` + `msg: "Method Not Allowed: [name]"` | **That command does not exist** |
| `405` + `msg: "request error"` | The command exists, but the parameters are wrong |
| `801` | That whole API family is not registered (wrong path prefix) |
| `803` | Family exists, command returned nothing usable |
| `804` | `Url error` — malformed path for that family |
| `805` | **The command exists but a required parameter is missing or wrong** |
| `806` | The resource does not exist |

The 405-vs-805 distinction is what makes safe discovery possible: you can learn whether a
command exists by calling it with a deliberately invalid parameter, and it will be
rejected before it executes.

The device also leaks its own type enum via error text:
**0 = MEDIA, 1 = AUDIO, 2 = LOCAL, 3 = WEB, 4 = CD, 5 = M3U**

---

## 4. Working endpoints, grouped by function

Base URL for all of these: `http://192.168.1.207:9529`

**Verified** = I called it against the real device this session and saw the response.
**From HA integration** = present in the known-good `hchris1/Eversolo` Home Assistant
integration but *not* individually exercised here, because they change state.

### 4.1 Device information

| Endpoint | Params | Verified | Notes |
|---|---|---|---|
| `/ZidooControlCenter/getModel` | — | Yes | Model, firmware, MACs, capability flags |
| `/ControlCenter/getModel` | — | Yes | Identical alias, byte-for-byte |

### 4.2 Library — file browser (complete enumeration)

| Endpoint | Params | Verified | Notes |
|---|---|---|---|
| `/ZidooFileControl/getDevices` | — | Yes | Lists storage volumes |
| `/ZidooFileControl/getFileList` | `path`, `type=0` | Yes | Directory listing. `path` URL-encoded |

### 4.3 Library — tagged database (search only, capped at 30)

| Endpoint | Params | Verified | Notes |
|---|---|---|---|
| `/ZidooMusicControl/v2/searchMusic` | `key`, `start`, `count` | Yes | Matches **track titles only** — not album names |
| `/ZidooMusicControl/v2/searchAlbum` | `key`, `start`, `count` | Yes | Returns album id, name, artist, year, track count |
| `/ZidooMusicControl/v2/searchArtist` | `key`, `start`, `count` | Yes | Returns artist id, name, album count |
| `/ZidooMusicControl/v2/getDetail` | `id`, `type=1` | Yes | Technical info + absolute `filePath` for a track |
| `/ZidooMusicControl/v2/getImage` | `id`, `target=16` | Yes | Album art as PNG (563 KB observed) |
| `/ZidooMusicControl/v2/getLyric` | unknown | Existence only | Returns 805; parameters not determined |

`key` is required and must be non-empty — an empty key returns `total: 0`.

### 4.4 Playback state (read-only — safe to poll)

| Endpoint | Params | Verified | Notes |
|---|---|---|---|
| `/ZidooMusicControl/v2/getState` | — | Yes | The main one. Everything about now-playing |
| `/ZidooMusicControl/v2/getPlayQueue` | — | Yes | Current queue contents |
| `/ZidooMusicControl/v2/getVolume` | — | Yes | Just the `volumeData` block |
| `/ZidooMusicControl/v2/getSpectrum` | — | Yes | Live FFT data — real VU meters are possible |
| `/ZidooMusicControl/v2/getInputAndOutputList` | — | Yes | Available inputs and outputs |
| `/ZidooMusicControl/v2/getPowerOption` | — | Yes | Power menu; also used to infer screen on/off |

### 4.5 Playback control (these change state)

| Endpoint | Params | Verified | Notes |
|---|---|---|---|
| `/ZidooFileControl/openFile` | `path`, `type=0` | **Yes** | **Play a specific track.** The key finding |
| `/ZidooMusicControl/v2/playOrPause` | — | Yes | Toggles play/pause |
| `/ZidooMusicControl/v2/playMusic` | `type=1` | Yes | Resume/play current item. **`id` is ignored** — this is *not* play-by-id |
| `/ZidooMusicControl/v2/setLoopMode` | `loop` (0 or 1) | **Yes** | **`loop=1` is repeat-one** — stops the device advancing on its own. Proven behaviourally: at 8 s from the end, `loop=0` advanced to the next track, `loop=1` stayed put. Parameter is `loop`, not `mode`/`index`/`type`/`model`/`value`. **No validation** — `loop=99` is accepted and echoed back by `getState`, so values 2–4 mean nothing until tested |
| `/ZidooMusicControl/v2/playNext` | — | From HA integration | Next track |
| `/ZidooMusicControl/v2/playLast` | — | From HA integration | Previous track |
| `/ZidooMusicControl/v2/seekTo` | `time` (ms) | From HA integration | Seek |
| `/ZidooMusicControl/v2/setDevicesVolume` | `volume` (0–200) | From HA integration | Absolute volume |
| `/ZidooMusicControl/v2/setMuteVolume` | `isMute` (0/1) | From HA integration | Mute |
| `/ZidooMusicControl/v2/setInputList` | `tag`, `index` | From HA integration | Select input |
| `/ZidooMusicControl/v2/setOutInputList` | `tag`, `index` | From HA integration | Select output |
| `/ZidooMusicControl/v2/setPowerOption` | `tag` = `reboot`/`poweroff`/`screen` | From HA integration | Power. Handle with care |
| `/ZidooMusicControl/v2/changVUDisplay` | `openType` (0/1) | From HA integration | Cycle screen mode |
| `/ZidooControlCenter/RemoteControl/sendkey` | `key` | From HA integration | `Key.VolumeUp`, `Key.VolumeDown`, `Key.Screen.ON`, `Key.Screen.OFF` |

### 4.6 Display / hardware settings

All under `/SystemSettings/displaySettings/` — from the HA integration, not exercised here:
`getScreenBrightness`, `setScreenBrightness?index=` (max 115),
`getKnobBrightness`, `setKnobBrightness?index=` (max 255),
`getVUModeList`, `setVUMode?index=`,
`getSpPlayModeList`, `setSpPlayModeList?index=`,
`getKnobSettingOption`, `getKnobLightColorList`, `setKnobLightColor?index=`.

---

## 5. JSON schemas

### `getFileList` → directory listing
```json
{
  "status": 200,
  "isExists": true,
  "perentPath": "/storage/EF42-73B2",
  "filelist": [
    {
      "name": "02 - Leonard Cohen - Amen.flac",
      "type": 1,
      "path": "%2Fstorage%2FEF42-73B2%2FLeonard+Cohen%2F...",
      "isBDMV": false,
      "isBluray": false,
      "length": 82911232,
      "modifyDate": 1706701090000
    }
  ]
}
```
`type`: **0 = folder, 1 = audio file, 3 = image, 12 = playlist/cue sheet**
(other values likely exist for video and documents; these are the ones observed).
Filter on `type == 1` to get playable audio. Note `path` comes back already
URL-encoded, with `+` for spaces — decode with `unquote_plus` before reuse.
(`perentPath` is the device's spelling of "parent path".)

### `getDevices` → storage volumes
```json
{"status":200,"devices":[{"name":"Flash","path":"/storage/emulated/0","type":1000},
                          {"name":"Internal","path":"/storage/EF42-73B2","type":1002}]}
```

### `searchMusic` → track records
```json
{
  "key": "a", "start": 0, "count": 50, "total": 30,
  "array": [{
    "id": 4466, "type": 1, "title": "Amen", "artist": "Leonard Cohen",
    "album": "Old Ideas", "albumArtist": "Leonard Cohen", "albumId": 735,
    "artistIds": "10000437", "genreId": 32, "date": "2012", "number": 2,
    "diskNumber": 1, "duration": 455448, "extension": "flac",
    "bits": 24, "SampleRate": 44100, "bitrate": 1526995, "channels": 2,
    "isLossless": false, "isMQA": true, "mqaMode": 2, "mqaOutSampleRate": 88200,
    "favor": false, "exist": true, "deviceId": 2, "folderId": 2,
    "uri": "/Leonard Cohen/[M] Old Ideas [32570025] [2012]/02 - Leonard Cohen - Amen.flac",
    "databaseId": 4466, "modifiedTime": 1706701090000, "addedTime": 1704069626743
  }]
}
```
`duration` is in **milliseconds**. `uri` is **relative to the storage volume**, not an
absolute path — prepend the volume path (e.g. `/storage/EF42-73B2`) to use it with
`openFile`.

### `searchAlbum` / `searchArtist`
```json
{"id":735,"name":"Old Ideas","pinyinName":"Old Ideas","artist":"Leonard Cohen",
 "pubDate":"2012","count":10,"favor":false}

{"id":10000458,"name":"AC/DC","pinyinName":"AC/DC","count":5,
 "favor":false,"isAlbumArtist":false}
```

### `getState` → now-playing (the main polling endpoint)
```json
{
  "state": 3,
  "position": 43310,
  "duration": 455448,
  "trackIndex": -1,
  "playType": 5,
  "loopModel": 0,
  "hasPlayQueue": true,
  "playingMusic": {
    "id": 4466, "type": 1, "title": "02 - Leonard Cohen - Amen",
    "artist": "Leonard Cohen", "album": "Old Ideas", "albumId": 410,
    "extension": "flac", "bits": "24", "sampleRate": "44.1 kHz",
    "bitrate": "1.53 Mbps", "channels": 2, "albumArt": "…", "favor": false
  },
  "volumeData": {
    "maxVolume": 200, "currenttVolume": 200, "minVolume": 0,
    "isMute": false, "display": "0 dB", "volumeTag": "XLR", "isLock": true
  },
  "deviceInfo": {"deviceName":"DMP-A6","model":"DMP-A6","version":"v1.5.90"}
}
```

`state`: **0 = idle/stopped, 3 = playing, 4 = paused.**
`playType`: 4 = Bluetooth, 5 = internal player, 6 = Spotify Connect.
`position` / `duration` are **milliseconds**.
Note the misspelled key **`currenttVolume`** (two t's) — that is the device's own
spelling, not a typo here.

### `getPlayQueue`
```json
{"id":0,"start":0,"count":-1,"total":10,"totalDuration":…,"canAddPlaylist":…,
 "array":[{"id":646990398,"type":1,"title":"01 - Leonard Cohen - Going Home",
           "artist":"Leonard Cohen","fileName":"…","url":"…","uri":"…",
           "duration":…,"extension":"flac"}]}
```
**Warning:** queue item `id` values are hashes, **not** the library database IDs — the
same track is `4466` in `searchMusic` but `1660025809` in the queue. To highlight the
playing row, match on title or position, not on ID.

### `getDetail?id=<songId>&type=1` → technical info
```json
{"numberOfChannels":"2","samplingRate":"44.1 kHz","samplingRateNumber":"44100",
 "bitRate":"1.53 Mbps","fileSize":"82.91 MB (FLAC)","bits":"24",
 "filePath":"usb://Internal/Leonard Cohen/[M] Old Ideas [32570025] [2012]/02 - Leonard Cohen - Amen.flac"}
```
Note `filePath` uses a `usb://Internal/…` scheme, **not** the `/storage/EF42-73B2/…`
form that `openFile` needs. The two path styles are not interchangeable.

---

## 6. What is missing / limitations

Honest list of what this API cannot do, so you don't design around something that isn't there:

1. **No "list everything" database endpoint.** `searchMusic`, `searchAlbum` and
   `searchArtist` are all hard-capped at **30 results**, and `start`/`count` are accepted
   but **ignored** — `start=25&count=10` still returns the same 30 rows. Confirmed by
   testing `count=10/30/50/200`. Full enumeration must go through `getFileList`.
2. **Search matches track titles only.** Searching `"Old Ideas"` (an album that exists)
   returns `total: 0`. You cannot look up an album's tracks via search — use the file
   browser on the album folder instead.
3. **No queue manipulation.** Every one of `addToQueue`, `addPlayQueue`, `insertPlay`,
   `addMusicToQueue`, `insertToQueue`, `setPlayQueue`, `setQueue` returns
   "Method Not Allowed". You cannot append to the queue or build one programmatically —
   `openFile` replaces it wholesale with the chosen track's folder.
   **This only limits *arbitrary cross-folder* playlists.** Playing a folder, or a track
   within a folder, is fully supported and is the natural unit of playback here.
4. **Playlist files are silently ignored.** `openFile` on `.m3u` or `.cue` returns
   `status: 200` and does nothing (see §2). There is also no saved-playlist endpoint,
   despite `M3U` appearing in the type enum. Parse playlists in your own app.
5. **No play-by-database-ID.** `playMusic` accepts an `id` parameter and ignores it.
   Playback targeting is by **file path only**.
6. **No internet-radio start.** `openFile` rejects URLs. How the device starts a radio
   stream was not determined.
7. **No authentication of any kind.** Anyone on your LAN can control the device. Worth
   knowing; no bypass was attempted or needed.

### Endpoints that do NOT exist
Confirmed absent (all returned "Method Not Allowed") — don't waste time on them:
`getMusicList`, `getSongList`, `getAlbumList`, `getArtistList`, `getFolderList`,
`getPlaylist`, `getAllMusic`, `getFavorList`, `getRecentList`, `getLocalMusicList`,
`getMediaList`, `getHomeData`, `getCategoryList`, `getStorageList`, `getScanState`,
plus ~240 other plausible names probed.

Whole API families that do not exist: `/ZidooPoster`, `/ZidooVideoPlay`, `/VideoPlay`,
`/ZidooMediaCenter`, `/ZidooLocalMusic`, `/ZidooMusic`, `/ZidooUpnp`, `/ZidooDlna`,
`/ZidooSetting`, `/MusicList`, `/Eversolo`, `/EversoloControl`.

`/MusicControl`, `/ZidooMusicControl` (no `/v2`) and `/ZidooMusicControl/v3` all exist as
catch-alls but return error 805 for every command — they are not usable alternatives.

---

## 7. UPnP / DLNA — the fallback route

**Confirmed: the device is a working UPnP/DLNA MediaRenderer.** This is a genuine
fallback for triggering playback.

* **Discovery:** responds to SSDP `M-SEARCH` on `239.255.255.250:1900`.
  `SERVER: UPnP/1.0 DLNADOC/1.50 Platinum/1.0.5.13` (the Platinum UPnP SDK).
* **Description:** `http://192.168.1.207:1118/description.xml`
  — `friendlyName: DMP-A6`, `manufacturer: EVERSOLO`, `X_DLNADOC: DMR-1.50`.
* **Note the port is 1118, not 9529.**
* **UDN:** `uuid:A9DD1773-DC36-11F0-A7C6-800A805C0C65` — this appears in every control URL
  and will differ on another unit, so read it from `description.xml` rather than hardcoding.

Services and control URLs:

| Service | Control URL |
|---|---|
| AVTransport | `/AVTransport/<UDN>/control.xml` |
| RenderingControl | `/RenderingControl/<UDN>/control.xml` |
| ConnectionManager | `/ConnectionManager/<UDN>/control.xml` |

**AVTransport actions:** `Play`, `Stop`, `Pause`, `Next`, `Previous`, `Seek`,
`SetAVTransportURI`, `SetNextAVTransportURI`, `SetPlayMode`, `GetMediaInfo`,
`GetTransportInfo`, `GetPositionInfo`, `GetTransportSettings`,
`GetCurrentTransportActions`, `GetDeviceCapabilities`, `X_PrefetchURI`,
`X_DLNA_GetBytePositionInfo`, `X_GetStoppedReason`, `X_PlayerAppHint`

**RenderingControl actions:** `GetVolume`, `SetVolume`, `GetMute`, `SetMute`,
`GetVolumeDB`, `GetVolumeDBRange`, `ListPresets`, `SelectPreset`

Live SOAP calls were verified working (read-only actions only):

```
AVTransport.GetTransportInfo -> HTTP 200
    CurrentTransportState = NO_MEDIA_PRESENT
    CurrentTransportStatus = OK
AVTransport.GetPositionInfo  -> HTTP 200  (Track 1, 0:00:00)
RenderingControl.GetVolume   -> HTTP 200  CurrentVolume = 100
```

`NO_MEDIA_PRESENT` is expected — the *native* player was active, not the DLNA renderer.
The two are separate transports.

**Important limitation of this route:** `SetAVTransportURI` needs a URL the device can
fetch over the network. It is therefore the right tool for **pushing audio from elsewhere**
(your Mac, a NAS) to the device. An app running *on* the device could serve its own files
over `127.0.0.1` to use it, but see the finding below before planning around that.

### Proven end-to-end, 31 July 2026 — and it does NOT solve the UI takeover

`SetAVTransportURI` + `Play` were executed against a 24-bit/96 kHz FLAC served over HTTP:

* Both SOAP calls returned **HTTP 200**.
* The device **really did fetch the file** (two `GET`s arrived at the test server).
* `GetPositionInfo` showed the position advancing — 0:28 → 0:30 → 0:32 of a correct 3:00.
* The device's own API reported `state=3`, **`playType=7` (DLNA)** — a genuinely different
  path from `openFile`'s `playType=5`.

**But it still hands the screen to the stock player.** Confirmed by direct observation on
the device. So DLNA is *not* a way for an on-device front end to keep its own UI. Both
routes into playback take the screen.

One unresolved caveat: **no audio was heard** during this test, while the device reported
volume 200/200 at 0 dB, unmuted, XLR out. That may simply have been the preamp turned
down — audible output was never independently confirmed for the `openFile` route either —
so this is recorded as unresolved rather than as "DLNA produces no sound".

---

## 8. Notes for building the front end

* **The device is intermittently unreachable, because it is on Wi-Fi.** *(Corrected
  31 Jul 2026 — an earlier version of this document claimed it "does not respond to ping".
  That was wrong. It responds sometimes.)* Measured properly: **24 of 25 HTTP requests
  succeeded**, with one hard timeout and latency spikes to 1.4 s and 5.1 s against a
  typical 0.25 s. A 10-ping burst can return 100% loss and then work fine a minute later.
  The ARP entry matches the device's `wif_mac`, not `net_mac` — it is on Wi-Fi, and this
  is power-saving behaviour.
  - **Find it via SSDP discovery** (`discover2.py`), never by pinging or port-scanning —
    bare `connect()` probes time out against ports that are demonstrably serving HTTP.
  - **Retry every remote request.** Three attempts with ~0.3 s backoff was sufficient
    throughout this work.
  - **This affects remote tooling only.** An app running *on* the device calls
    `127.0.0.1:9529` and sees none of it.
* **The screen never needed to be awake.** Every request answered promptly regardless of
  screen state; screen state was not a factor at any point.
* **Poll `getState`** for now-playing, position, volume and state. Roughly 1 s is
  responsive; the whole response is ~2.8 KB.
* **`getSpectrum` — the response is nested twice, and empty unless something is playing.**
  Asked while paused, the real device answers:

  ```json
  {"fft_value":"{}","freqs_value":"{}","fft_level":0,"nb_freqs":0}
  ```

  Note the shapes: `fft_value` and `freqs_value` are **strings holding JSON**, and what is
  inside them is an **object**, not an array. A parser that looks for a key called
  `fft_value` and expects a JSON array — which is the obvious first attempt, and was
  ours — finds nothing and reports the endpoint broken. It is not: it is empty because the
  device was paused. `fft_level` is a plain number, an overall level rather than bands.

  **And while playing it returns `{}` — nothing at all.** Sampled on the real unit with a
  48 kHz/24-bit FLAC running, eight times in a row, with and without parameters
  (`type`, `openType`, `index`, `nb_freqs`), and in every display mode `changVUDisplay`
  offers (`spDisplayMode` 0, 1 and 2, `vuDisplayMode` 0 and 1). Always `{}`.

  **The device says why itself:** `getState.everSoloPlayInfo.isHasSpectrum` is **false** —
  in this capture, in `state_before.json` from the first survey, and in `probe_results.json`,
  which between them cover local files and internet radio. Alongside it, `isHasDSP: false`.
  This unit has no spectrum to give. The endpoint exists and answers 200; there is simply
  nothing behind it.

  Check `isHasSpectrum` before building anything on this. Do not spend another evening on
  the parser.
* **Album art:** `getImage?id=<songId>&target=16` returns a PNG. The `target` parameter
  appeared to make no difference (0, 1 and 16 all returned the identical 563 KB image),
  so it may not be a size selector.
* **Volume scale is 0–200**, displayed in dB, where **200 = 0 dB = full output**, not
  "200%". Do not treat it as a percentage.
* **Never trust `status: 200` from `openFile`.** It returns 200 for files it silently
  refuses to play (see §2). Confirm the result with `getState` and compare
  `playingMusic.title` against what you asked for.
* **Rate-limit your requests.** Everything here was done with 0.15–0.4 s spacing and the
  device stayed responsive; it is a small Android box, so don't hammer it. Walking 130
  folders (~1,044 audio files) at 0.18 s spacing took about 25 seconds — cache the tree
  in your app rather than re-walking it on every launch.
* **Encode paths carefully.** Album folders in a real library contain spaces, brackets,
  apostrophes, commas and accented characters (`[M] Can't Forget…`, `La Manic (Live at
  Québec City Show, 2012)`). Use proper percent-encoding.

---

## 9. What changed on the device during this session

Full disclosure of every state change made, with your approval, plus what could not be
put back:

* **Play queue was replaced.** It previously held **6 internet radio stations**; it now
  holds the 10 tracks of Leonard Cohen's *Old Ideas*.
  **This could not be restored via the API** — there is no add-to-queue endpoint (see
  §6.3), and `openFile` refuses stream URLs, so the stations cannot be re-queued
  programmatically. The 6 station names and their stream URLs are preserved in
  **`state_before.json`** in this folder. They were flagged as favourites on the device,
  so they should still be in your radio favourites in the UI.
* **Playback was started and stopped several times** while testing folder, `.m3u` and
  `.cue` behaviour. The device is currently **paused** on ELO — "01. Secret Messages",
  with that album's 11-track folder in the queue.
* **Volume was NOT changed.** Still 200/200 (0 dB), exactly as found.
* Nothing was installed, no settings were altered, no authentication was bypassed,
  and no destructive command (reboot, power off, delete) was ever sent.

---

## 10. Files in this folder

| File | What it is |
|---|---|
| `API_FINDINGS.md` | This document |
| `discover2.py` | SSDP/UPnP discovery — **use this to find the device's IP** |
| `probe.py` | Read-only family + endpoint discovery sweep |
| `enumerate.py` | Command-name enumeration using the 405 oracle |
| `probe_results.json` | Raw results, 746 probes |
| `enumerate_results.json` | The 12 read-only commands confirmed to exist |
| `state_before.json` | **Your original play queue and radio URLs — keep this** |

## Source

Starting map extracted from [hchris1/Eversolo](https://github.com/hchris1/Eversolo)
(Apache-2.0), a Home Assistant integration for this device. It provided 27 endpoints,
all confirmed present. Everything in §4.2, §4.3 and the `openFile` playback route —
i.e. the entire answer to the library and play-a-track questions — was discovered in
this session; none of it appears in that integration.
