# Modules — what is where

Gradle modules under `player/`. The two marked **plain Java** carry no Android imports on
purpose, so their logic can be tested on a desktop JVM.

| Module | What it holds | Key classes | Doc |
|---|---|---|---|
| `:app` | Window management and the glue between everything | `WinampUi` (the window manager), `LibraryBrowser`, `MusicLibrary`, `FileSpectrum`, `PlaylistStore`, `OverlayService`, `FullScreen` | `windows.md`, `library.md` |
| `:core` | Logging that survives having no logcat | `Logs`, `LogShipper`, `CrashHandler` | `build-install.md` |
| `:library` | Finding and indexing music on disk | `LibraryScanner`, `MusicIndex`, `Track`, `VolumeDiscovery` | `library.md` |
| `:tags` | **Plain Java.** FLAC/ID3/m3u parsing | `FlacTagReader`, `Id3TagReader`, `M3uParser` | `library.md` |
| `:playback` | Talking to the device | `EversoloHttpEngine`, `PlaybackEngine`, `PlaybackState` | `playback.md`, `api.md` |
| `:playlist` | The playlist and the order of play | `Playlist`, `PlaylistController` | `playback.md` |
| `:skin` | Everything drawn | `Skin`, `BmpDecoder`, `SkinSprites`, `GenSprites`, the three `*WindowView`s | `skinning.md`, `windows.md` |
| `:dsp` | **Plain Java.** The analyser's maths | `Fft`, `SpectrumBands` | `analyser.md` |

## Where the layers meet

```
OverlayService ──► WinampUi ──► the three window views          (:app, :skin)
                      │
                      ├──► PlaylistController ──► PlaybackEngine ──► the device
                      │         (:playlist)          (:playback)
                      ├──► MusicLibrary ──► LibraryScanner ──► TagReaders
                      │         (:app)         (:library)        (:tags)
                      └──► FileSpectrum ──► Fft / SpectrumBands
                                (:app)          (:dsp)
```

`WinampUi` is the one class that knows about everything. If a change spans layers, it
usually lands there. The window views know nothing about tracks or the device: they are
handed rows and report what the user did, which is what keeps `:skin` reusable.

## Tools

`player/tools/` holds the generators, the desktop previews and the JVM test runner — all
covered in `testing.md`. Nothing there ships in the APK.
