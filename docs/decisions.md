# Decisions, and dead ends with the evidence

Read this before proposing a feature. Several obvious ideas have already been tried and
disproved on the hardware, and the evidence is here so nobody spends another evening on
them.

## Settled decisions

* **D1. The app owns the playlist** and feeds the device one track at a time. Forced: the
  API has no queue manipulation at all, and `openFile` replaces the device's queue with the
  chosen track's folder.
* **D2. The library is read from the filesystem, not the API.** 5,037 files in 366 ms versus
  about 25 s for a fraction of that through `getFileList`.
* **D3. The API is used only for playback control and live state.** Never for browsing.
* **D4. Playback sits behind the `PlaybackEngine` interface** and stays swappable. This is
  also what makes the sequencer testable with a fake.
* **D5. The skin engine was written here.** The plan was to fork `djshaji/eva` (MIT); its
  `skinformat.json` turned out to contradict itself, so the sprite tables come from webamp
  and the engine — `.wsz` loading, BMP decoding, fonts, hit-testing — is ours. No eva code
  is in the tree.
* **D6. The app parses `.m3u` itself.** The device accepts them, returns success, and does
  nothing.
* **D7. Gapless is deferred**, and should be added inside `EversoloHttpEngine`.
* **The windows take turns** rather than sharing the screen — there is no room for two at a
  size where a row can be tapped. The owner chose this over shrinking everything.
* **The browser is a browser only**: no transport, and tapping a track does not play it.
* **Nothing may be reachable only by a gesture.**
* **The window width is pinned, not left to the system.** `LAYOUT_HIDE_NAVIGATION` is inert
  on this firmware, so the window resized on every touch and re-scaled the whole UI with it.
  The measured full width is nailed down instead. See `windows.md`.
* **The main window cannot fill the screen.** 2160 ÷ 275 = 7.85: ×7 is 1925 px and ×8 is
  2200 px, 40 px too wide, and the clipped columns are frame artwork rather than margin
  (checked — the outer columns of `main.bmp` carry 21–38 distinct colours each). Filling the
  width exactly needs a fractional scale, which rule 6 forbids. The browser already fills
  2160 and the playlist reaches 2100; it is only the main window that is short.
* **Full screen is asked for, then verified — never assumed.** The firmware's side bar is
  almost certainly the navigation bar, but "almost certainly" is not shippable to a device
  with no debugger. `FullScreen` requests the hide without the full-bleed layout, watches for
  the window to actually grow, and reverts if it does not. The obvious shortcut —
  `FLAG_LAYOUT_NO_LIMITS`, take the space and hope — would leave the bar sitting on top of the
  playlist's right-hand edge on any firmware that says no.

## Dead ends — do not rebuild these

### No equaliser

The API has **no tone control of any kind** — no EQ, no filters, nothing — and the unit
reports `isHasDSP: false`. Ten sliders wired to nothing would also promise exactly what this
player exists to avoid: the signal reaching the DACs untouched. The EQ button says so.

### No `getSpectrum`

Sampled directly with music playing: `{}`, eight times in a row, with `type`, `openType`,
`index` and `nb_freqs` tried as parameters, and in every mode `changVUDisplay` offers
(`spDisplayMode` 0/1/2, `vuDisplayMode` 0/1). Paused, it returns a structure full of zeros
in which `fft_value` is a *string* containing `"{}"` — which defeated two parsers before the
real answer turned up.

**`getState.everSoloPlayInfo.isHasSpectrum` is false** in this capture, in
`state_before.json`, and in `probe_results.json` — covering local files and internet radio.
The endpoint exists and answers 200; there is nothing behind it.

The analyser therefore decodes the file itself. See `analyser.md`.

### No MilkDrop, AVS or Geiss presets

They read the **waveform at frame rate** — butterchurn calls `getByteTimeDomainData` every
frame and runs its own FFT over 1024 stereo samples. Android-side capture is out too: the
audio does not go through the mixer, which is the bit-perfect design working.

**The one thing worth reopening.** The analyser's file decode *does* give us a waveform, at
whatever rate we like. That was the missing ingredient. A real waveform-driven visualiser is
now possible — it just has to read from `FileSpectrum` rather than from the device.

### A full-screen light show on a timer

Built, then removed at the owner's request. With no audio data it could only move on a
timer, which is a screensaver, not a visualiser.

## Out of scope

Not a streaming client (no Tidal/Qobuz/Spotify). Not a replacement for the stock interface.
Not a mobile remote — it runs *on* the device. Not internet radio; the API rejects stream
URLs. Not a settings app, and not a firmware project: no root, no patched system apps.
