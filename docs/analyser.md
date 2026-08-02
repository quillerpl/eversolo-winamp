# The spectrum analyser

Nineteen bars in the main window's LCD, coloured from the skin's `viscolor.txt`, with peak
markers. Tap the LCD to turn it off.

## Where the data comes from, and why

**Not from the device.** `getSpectrum` returns `{}` for every source on this unit and
`getState.everSoloPlayInfo.isHasSpectrum` is false in every capture ever taken. See
`decisions.md` for the full evidence.

But the stock player's analyser plainly works — because the stock player is the thing
decoding the audio. So we do the same: `FileSpectrum` (`:app`) decodes the playing file a
second time with MediaCodec and runs its own FFT. We know the path because we asked for that
file to be played.

**Nothing is played and nothing touches the output**, so the bit-perfect path is untouched.
It costs one FLAC decode, a few percent of one core.

It only works for tracks **started from this app** — that is when a path is known. `getState`
does not report one.

## Staying in step

Decoding runs many times faster than playback, so frames are paced against the wall clock
from the position the device reported, and the decoder **re-seeks when the two drift more
than `DRIFT_MS` (400 ms) apart** — which happens on every skip and seek. The display ends up
a split second behind, which is fine for something indicative.

## The maths

`:dsp` is plain Java with no Android in it, so it is provable on a laptop — `FftTest` feeds
it sine waves of known pitch and checks they land in the right bin and light the right bar.

* `Fft` — radix-2, Hann-windowed. Without the window, a tone that does not land exactly on a
  bin smears across the whole spectrum and every bar lights at once.
* `SpectrumBands` — bins to bars. Two decisions separate a Winamp-looking display from a
  wrong one, and the tests settled both:
  * **Log spacing.** Linear spacing puts fifteen of nineteen bars above 5 kHz, where there
    is usually nothing, and the display huddles at the left.
  * **Loudest bin per band, not the average.** Averaging buried a 1 kHz sine: the upper
    bands are dozens of bins wide, and one strong partial among fifty quiet ones reads as
    silence.
* Levels are dB against a slowly-decaying peak, so the display self-calibrates.

## If it misbehaves

`CANNOT DECODE THIS FILE` means MediaCodec would not open it — try an MP3 to tell "no FLAC
decoder" from "no decoder at all". Bars that lag or jump are pacing or `DRIFT_MS`.
Stuttering playback means the second decode is too expensive: raise `FFT_SIZE` or emit less
often. The maths is proved off-device, so a wrong-looking display is a decode, sync or
scaling problem, never an FFT one.
