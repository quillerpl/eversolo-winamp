# The library, the browser, and tags

## Scanning

`MusicLibrary` (`:app`) owns the scan and hands out the results; `LibraryScanner` and
`MusicIndex` (`:library`) do the work. It runs once per launch on a background thread:
**570 ms to walk, about 12.5 s to read tags** for 3,481 tracks. There is no disk cache yet —
measured, and deliberately not optimised.

Volumes are discovered by enumerating `/storage/*` (`VolumeDiscovery`). **Never hardcode the
volume ID**: `EF42-73B2` comes from how this particular SSD was formatted.

Reading the filesystem directly rather than through the API is decision D2, and the numbers
are why: 5,037 files scanned in 366 ms, versus about 25 s for a fraction of that through
`getFileList`.

## Tags

**Android cannot read FLAC tags on this device** — `MediaMetadataRetriever` returns null for
artist/album/title and MediaStore reports `<unknown>`. So `:tags` parses them itself:
`FlacTagReader` (Vorbis comments), `Id3TagReader` (v2.3/v2.4, incl. unicode and Latin-1),
and `PathTagReader` as a last resort for untagged files.

This is the project's main technical risk and the reason `:tags` is plain Java with 41
assertions against real ffmpeg-generated files.

`Track.absolutePath` is a track's identity. **Never a device ID** — the device's library IDs
are only reachable through a search capped at 30 results, and its queue IDs are hashes that
differ from them for the same track.

## The browser

`LibraryBrowser` (`:app`) is the model behind `BrowserWindowView`: it decides what the rows
are and what tapping them does. Four ways in, because a library ripped over twenty years is
not consistently tagged and the folder tree is sometimes the only view that makes sense:

* **ARTIST** → albums → tracks
* **ALBUM** → tracks
* **FOLDER** → the filesystem from the storage roots down
* **M3U** → the `.m3u` files found by the scan; ADD imports them

It is a browser and nothing else: **no transport, and tapping a track does not play it**.
The only outcome is tracks going into the playlist.

Rows carry an "already added" mark — filled for all of it, hollow for some. For albums and
artists that is counted from their track lists; for folders it is "does any playlist path
start with this folder", because answering properly would mean walking the tree for every
row on screen.

## .m3u

The device accepts `.m3u` files, returns success, and silently does nothing — decision D6 —
so the app parses them itself. Import resolves each line against the library and says what
was dropped (missing files, web links, tracks not in the library) rather than quietly
importing fewer tracks than the file listed.

## Lyrics

`fetch-lyrics.py` at the repo root fills the library with `.lrc` sidecar files, run from a
Mac over the Eversolo's SMB share — no build, no install, no ADB, and it can be re-run and
resumed. Lyrics come from [LRCLIB](https://lrclib.net): free, no account, no API key, run by
volunteers for this purpose. The default pace is deliberate; do not lower it much.

Measured on this library before writing anything:

* 3,510 tracks, **0** `.lrc` files.
* About two thirds of the FLACs already carry a `LYRICS` tag — and **none of it is timed**.
  Sampling 300 files found 192 with lyrics and 0 with timestamps. Embedded tags give text;
  they will never give the highlight.
* LRCLIB had **time-synced** lyrics for 20 of 25 randomly chosen tracks.

It never writes to an audio file. Undo is `find /Volumes/Share -name '*.lrc' -delete`.
Matching is artist + title + album + duration, falling back to a search that only accepts a
result within two seconds of the right length — which is what stops you getting the lyrics
of a different recording of the same song.
