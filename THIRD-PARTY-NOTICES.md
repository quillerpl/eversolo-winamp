# Third-party notices

## webamp — MIT

Sprite coordinates and window layout constants in

* `player/skin/src/main/java/org/eversolo/winamp/skin/SkinSprites.java`
* `player/skin/src/main/java/org/eversolo/winamp/skin/PlaylistGeometry.java`

are derived from [webamp](https://github.com/captbaritone/webamp) — specifically its
`skinSprites.ts`, `main-window.css` and `playlist-window.css`. `player/tools/gen-sprites.py`
regenerates them from that source.

They are used as reference data rather than copied code: webamp is a faithful
reimplementation of Winamp 2.9 and its numbers agree with the real bitmaps, where other
published tables do not. No webamp source is compiled into this project.

```
MIT License

Copyright (c) 2019 Jordan Eldredge

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Winamp skins — not redistributed

The app renders itself from a Winamp 2.x `.wsz` skin, which the user supplies. **No skin is
included in this repository.** The classic skin (`base-2.91.wsz`) is Nullsoft's artwork and
is not this project's to redistribute; `.wsz` and `.wal` files are in `.gitignore` so one
cannot be committed by accident.

See `player/app/src/main/assets/skins/README.md` for where to put yours.

The screenshots in `docs/images/` show the classic skin, as any screenshot of a skinnable
application does.

## djshaji/eva — considered, not used

An earlier plan was to fork [eva](https://github.com/djshaji/eva) (MIT) for its skin engine.
That is not what happened: the skin engine here — `.wsz` loading, the BMP decoder including
BI_RLE8, sprite mapping, the bitmap fonts and hit-testing — was written from scratch, and
eva's `skinformat.json` was found to contradict itself and was not used. It is named here
only because the project's decision log mentions it.

## Winamp

"Winamp" is a trademark of its owner. This project is an independent, unaffiliated homage
that reads the skin format; it contains no Winamp code.
