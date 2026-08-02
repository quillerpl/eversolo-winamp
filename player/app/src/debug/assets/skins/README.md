# Put a Winamp skin here

The app draws itself from a classic Winamp 2.x skin — a `.wsz` file, which is a zip of
BMPs. One needs to be in this folder before you build, and it is deliberately **not** in
this repository: the classic skin is Nullsoft's artwork and this project does not
redistribute it.

Any Winamp 2.x skin will do. The one this was developed against is `base-2.91.wsz`, the
default classic skin, which ships inside Winamp and is bundled with several open-source
Winamp reimplementations.

```
player/app/src/main/assets/skins/base-2.91.wsz
```

The name does not matter — the first `.wsz` found here is used.

## Or skip the build step entirely

A skin dropped on the device at

```
/storage/emulated/0/EverSoloWinamp/skins/
```

overrides the bundled one at runtime, so you can try skins without rebuilding.

## What a usable skin has to contain

`Skin.isUsable()` checks for `main.bmp`, `cbuttons.bmp` and `titlebar.bmp`. Beyond those,
`pledit.bmp` draws the playlist window and `gen.bmp` / `genex.bmp` draw the browser; a skin
missing them renders those windows blank rather than crashing.

Filenames are matched case-insensitively, because skins in the wild are inconsistent about
it — the classic skin uses `MAIN.BMP`.
