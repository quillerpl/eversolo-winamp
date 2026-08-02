# The device

Eversolo DMP-A6, `rockchip DMP-A6`, Android 11 (API 30), firmware v1.5.90, quad-core
Cortex-A55, 4 GB RAM. Last seen at **192.168.1.207** — do not hardcode it; `discover2.py`
finds it over SSDP.

## Screen

**2160 × 1080 px**, 320 dpi, density 2.0. The app window is **2000 × 1080** (160 px of
system chrome). It is *not* 1280×480, whatever the spec sheets say — this was measured.

A wide, short 2:1 display, which happens to suit Winamp's layout. A 13-pixel skin row is
about 3 mm on it: readable, unpleasant to hit, which is why the windows can be scaled.

**Size windows from the view's own measured size, never `DisplayMetrics`** — the overlay is
2000 px wide on a 2160 px screen and anything sized for 2160 hangs off the edge.

## Ports

| Port | Service |
|---|---|
| **9529** | The control API. From the app itself, `http://127.0.0.1:9529` |
| **18888** | Web APK installer — this is how builds get on |
| 1118 | UPnP/DLNA MediaRenderer |
| 5555 | Open but **dead**: accepts TCP, does not speak ADB |
| 9530 | Open, returns empty replies, unidentified |

## Storage

```
/storage/EF42-73B2                  ~4,869 audio files  (the SSD; this ID is unit-specific)
/storage/emulated/0/EverSoloMusic   ~168 audio files    (internal flash)
```

Discover volumes by enumerating `/storage/*`. **Never hardcode `EF42-73B2`.**

The app keeps its own files in `/storage/emulated/0/EverSoloWinamp/` — `skins/` for user
`.wsz` files, `playlists/` for saved `.m3u`.

## Things this hardware will not do

* **No usable ADB**, so no logcat and no debugger. This shapes the whole workflow.
* **Android cannot read FLAC tags here** — `MediaMetadataRetriever` returns null. We parse
  them ourselves. (The FLAC *decoder* works fine; that is a different subsystem, and the
  analyser depends on it.)
* **Android cannot decode most Winamp skin bitmaps** — they are BI_RLE8.
* **No equaliser, no DSP, no spectrum** exposed by the API. See `decisions.md`.
