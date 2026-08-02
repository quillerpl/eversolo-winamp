# Index — read this first, then one or two others

**What this is:** a classic Winamp 2.x interface for the Eversolo DMP-A6, running as a
sideloaded Android app on the streamer's own touchscreen. It does not play audio; it drives
the device's built-in engine over a local HTTP API so the signal stays bit-perfect.

**How to use this folder.** Each file below answers one question and is small enough to read
in full. Read the one you need. **Do not read `PROJECT_PLAN.md`, `API_FINDINGS.md`,
`ANSWERS_Q1_Q7.md` or `ARCHITECTURE.md` unless a file here sends you there** — they are the
deep reference and the history, and between them they cost about 20,000 tokens.

| If you are… | Read |
|---|---|
| picking up the project cold | this file, then `status.md` |
| about to change playback, the playlist order, or the handover | `playback.md` |
| touching any window, sprite, font or layout | `skinning.md`, then `windows.md` |
| working on the library, the browser, tags or scanning | `library.md` |
| working on the spectrum analyser or anything DSP | `analyser.md` |
| calling the device's HTTP API | `api.md` |
| building, installing, or wondering why a change did nothing | `build-install.md` |
| adding or changing tests, previews or generators | `testing.md` |
| wondering "why is it done this way" or "can we add X" | `decisions.md` |
| trying to find which file does what | `modules.md` |
| dealing with the hardware, screen size, storage or ports | `device.md` |

## The five things that will bite you

1. **Bump `versionCode` in `player/app/build.gradle` before every install.** Unchanged, the
   device's installer reports success and silently keeps the old app.
2. **There is no ADB.** No logcat, no debugger. Prove things off-device or add logging.
3. **Never trust HTTP 200 from the device.** The real status is in the JSON body, and
   `openFile` returns success for files it silently refuses to play.
4. **You can query the device yourself** at `192.168.1.207:9529` — no authentication. Reads
   are safe and beat asking the user to read a log off a 6-inch screen. Anything that
   changes state needs asking first.
5. **Toasts are invisible** behind the overlay window. Feedback must be drawn in a window.

## Ground rules for changes

* Whole-number scaling only, nearest-neighbour: the UI is pixel art.
* Sprite coordinates are **generated, never typed** — see `skinning.md`.
* Nothing may be reachable only by a gesture.
* Anything provable on a laptop gets proved on a laptop — see `testing.md`.
