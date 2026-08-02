# Status — what works, what is untested

Current build **v0.21-fullscr** (versionCode 21). With nothing playing, the title strip
shows the running version; that is the quickest check that an install took.

## Working, confirmed on the device

* **Library** — 218 artists / 426 albums / 3,481 tracks. Walk 570 ms, tag pass 12.5 s.
* **Playback** — tap a track and it plays through the device's own engine; play/pause,
  next, previous, seek.
* **Playlists** — cross-folder, add/remove/shuffle/clear, `.m3u` import, and the handover
  from track to track.
* **Three skinned windows** — the main window, the playlist editor, the library browser,
  with ×1 / ×1.5 / ×2 zoom on the two scrolling ones.
* **The spectrum analyser** — decoded from the file itself. A split second behind, which is
  fine for something indicative.

## Built but not yet confirmed on hardware

1. **Playlist persistence.** Add tracks, close from the main window's X, reopen: expect
   `PLAYLIST RESTORED - N TRACKS` about 13 s in, once the scan finishes.
2. **The two LCD taps.** The clock switches elapsed/remaining with a minus sign; the title
   switches tags/file name and should read *song - album - artist*.
3. **kbps / kHz / mono-stereo** — never confirmed since they were wired up.
4. **SAVE LIST / LOAD LIST**, which write and read `.m3u` in `EverSoloWinamp/playlists`.
5. **FULLSCR** — the whole point of v0.21, and the one thing here that could turn out to be
   impossible rather than merely broken: it depends on the firmware letting an overlay hide
   the side bar. It reverts itself and logs if not, so the failure is safe and legible. Turn
   it on in MISC OPTS; expect the bar to go five seconds later and the windows to widen.

## Not built, and why

The equaliser and any beat-synced visualiser are **settled as impossible or pointless on
this hardware** — see `decisions.md`, which has the evidence. Do not rebuild them without
reading it.

One thing there is worth reopening: the analyser decodes the file, so **a waveform is now
available**, which was the missing ingredient for a real visualiser.

Still open, in rough order of value: a disk cache for the tag pass (12.5 s cold start),
gapless handover (decision D7), and windowshade mode for the playlist.
