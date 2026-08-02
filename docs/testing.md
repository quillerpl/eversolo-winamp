# Proving things without the device

The rule that keeps this project moving: **anything provable on a laptop gets proved on a
laptop.** There is no debugger on the device and every build costs a browser upload, so a
bug that reaches it costs ten minutes, while the same bug caught here costs seconds.

## The JVM suite

```bash
./player/tools/run-jvm-tests.sh          # 254 assertions
```

It generates real audio fixtures with ffmpeg (testing parsers against files we synthesised
by hand would only prove the parsers agree with our assumptions), compiles the Android-free
sources, and runs them on a desktop JVM.

| Suite | Covers |
|---|---|
| `TagTest` | FLAC and ID3 parsing against real ffmpeg-generated files |
| `M3uTest` | `.m3u` parsing: Windows paths, CRLF, BOM, unicode, Latin-1, URLs |
| `PlaylistTest` | The playlist model, including index bookkeeping around the playing track |
| `PlaylistGeometryTest` | Window sizing, scrolling, hit-testing, zoom, `pledit.txt` colours, and that the MISC OPTS fly-out fits at 2000 **and** 2160 px |
| `FftTest` | The analyser's maths, against sine waves of known pitch |
| `SequencerTest` | The handover, and the two ways a user moves the playhead |
| `SkinFinderTest` | The skin walk, against a real directory tree: nesting, USB volumes, the folders it refuses to enter, the depth limit |

Modules are kept Android-free specifically so they can be tested here. `Logs` is the one
exception, compiled against a stub `android.util.Log` in `tools/jvm-tests/stubs/`.

**A test that passes against the broken code is not a test.** `SequencerTest` did exactly
that at first — the fake engine returned success without publishing states, and the bug
lived in the states arriving mid-call. When you fix something, break it again deliberately
and watch the test fail.

## Desktop previews

The windows can be rendered from the same sprite tables the app uses:

```bash
./player/tools/preview/playlist_preview.py --width 500 --height 261 --scale 4 --menu add
./player/tools/preview/browser_preview.py
```

Between them these have caught five real bugs before an install: the wrong skin bundled, the
RLE8 decoding problem, buttons a pixel too tall for the frame they sat on, labels in a font
that was unreadable on grey, and a title bar assembled from the wrong pieces.

## Generators

Sprite coordinates are **generated, never typed**:

```bash
./player/tools/gen-sprites.py PLEDIT           # from webamp's skinSprites.ts
./player/tools/gen-sprites.py --font           # the text.bmp character table
./player/tools/gen-window-sprites.py           # measures gen.bmp itself
./player/tools/gen-window-sprites.py --check   # which parts of gen.bmp tile seamlessly
```

The one table that was typed by hand was missing `:` and space. See `skinning.md`.
