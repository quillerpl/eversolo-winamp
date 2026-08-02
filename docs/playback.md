# Playback and the playlist

Two classes, and the interesting problem is the handover between tracks.

* `:playback/EversoloHttpEngine` — the only thing that talks to the device. Behind the
  `PlaybackEngine` interface (decision D4), which is also what makes the sequencer testable.
* `:playlist/PlaylistController` — the sequencer: which track plays next, and when.

## Why the app owns the playlist

The device's API has **no queue manipulation at all**, and `openFile` replaces its queue
with the chosen track's containing folder. So the app keeps the user's order and feeds the
device one track at a time. That is decision D1 and everything else follows from it.

## Starting a track

`engine.play(path, title)` calls `openFile`, then **confirms** by polling `getState` for up
to 2.5 s, because `openFile` answers 200 for files it silently refuses. It returns false if
the device never agrees, and the sequencer skips that track rather than stalling.

Two things about that confirm loop matter:

* It **publishes every reading to the listeners**, which includes the sequencer. States
  arrive from inside the call.
* Starts are **serialised through one worker with a generation counter**, so a newer request
  supersedes an older one. Without that, two quick taps produced a burst of tracks each
  playing for a fraction of a second.

## The handover

The device is put in **repeat-one** while the app is driving (`setLoopMode?loop=1`), because
otherwise it wanders into the next file in the folder — which for a cross-folder playlist is
the wrong track and actively fights us. With repeat-one on, being late merely repeats the
current track, which is recoverable.

The sequencer starts the next track `LEAD_MS` (400 ms) before the end, and keeps a **wrap
detector** as the safety net: if the position jumps backwards, repeat-one must have fired
and we were late.

## The four guards, and why each exists

A deliberate jump looks exactly like a wrap. Both of these were real bugs — tapping a track
played the *next* one, and so did dragging the position bar:

1. **A start claims the handover while it is in flight.** The confirm loop gets 2.5 s, which
   outlasts the settling window below, so this is what covers a device that stalls.
2. **A wrap must come from near the end of the track** (`WRAP_FROM_END_MS`). That is where a
   wrap comes from; a seek comes from anywhere.
3. **Jumps within 2 s of us moving the playhead are ours** (`SETTLE_MS`). The device reports
   the old position for a poll or two — the lag visible on the position bar.
4. **The end-of-track handover respects that window too.** Seek away from the last seconds
   and the stale reading otherwise says "400 ms left, hand over now".

`SequencerTest` pins each one: remove any and it fails. If you change this code, run it.

## Persistence

`PlaylistStore` writes the playlist to the app's own files as an `.m3u` of absolute paths —
debounced while adding, written synchronously on the way out — and restores it once the
library scan can resolve the paths back into tracks with tags.
