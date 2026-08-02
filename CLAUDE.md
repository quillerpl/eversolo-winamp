# CLAUDE.md — start here, and keep it cheap

A classic **Winamp 2.x interface for the Eversolo DMP-A6**, running as a sideloaded Android
app on the streamer's own 6-inch touchscreen. It does not play audio: it drives the device's
built-in engine over a local HTTP API, so the signal stays bit-perfect to the DACs. That is
the entire reason for the design.

## How to find things without reading the whole project

**Read `docs/00-index.md` first.** It is a page long and it will tell you which single file
answers your question. The `docs/` folder is a dozen small, single-topic files; each is
meant to be read whole, and one or two is normally enough.

**Do not read `PROJECT_PLAN.md`, `API_FINDINGS.md`, `ANSWERS_Q1_Q7.md` or `ARCHITECTURE.md`
unless a doc sends you there.** They are the deep reference and the history — about 20,000
tokens between them. The `docs/` summaries are current and were written from them.

If you change how something works, update the matching `docs/` file in the same commit.
That is what keeps this cheap.

## The user

**Jack is not a developer.** Explain in plain language. When you need something from him,
say exactly what to type or click. Do not ask him to review code for correctness — verify it
yourself and show the evidence. He notices UI details and is right about them.

This is a weekend project built for fun and out of nostalgia. Treat it seriously, but the
stakes are a nice thing to use, not a product.

## Hard rules

1. **Ask before any call that changes device state.** The user's play queue — including six
   internet radio stations — was destroyed by an early probing session and could not be
   restored; the API has no queue manipulation. The originals are in `state_before.json`.
   Reads are always fine, and the API has no authentication, so
   `curl http://192.168.1.207:9529/...` beats asking him to read a log off the screen.
2. **Bump `versionCode` in `player/app/build.gradle` before every install.** Unchanged, the
   installer reports success and the device silently keeps the old app.
3. **Never trust HTTP 200.** The real status is in the JSON body, and `openFile` returns
   success for files it silently refuses to play. Confirm against `getState`.
4. **Prove it off-device.** `./player/tools/run-jvm-tests.sh` (254 assertions) and the
   preview scripts. There is no debugger on the device and every build costs a browser
   upload. A test that passes against the broken code is not a test.
5. **Sprite coordinates are generated, never typed** — `player/tools/gen-*.py`.
6. **Whole-number scaling only**, nearest-neighbour: it is pixel art.
7. **Nothing may be reachable only by a gesture.**
8. **Toasts are invisible** behind the overlay. Draw feedback inside a window.
9. **Do not hardcode the device's address or the volume ID** (`EF42-73B2` is unit-specific).
   `discover2.py` finds the device over SSDP.
10. **Do not modify the firmware.** No root, no patched system apps, no OTA tampering.

## Before proposing a feature

Check `docs/decisions.md`. The equaliser, the device's spectrum endpoint and MilkDrop-style
visualisers have all been tried and disproved on this hardware, with the evidence recorded.
One of them is worth reopening and the file says which.

## Layout

```
docs/          the summaries — read these
player/        the app; module map in docs/modules.md
*.md at root   deep reference and history — read only when sent
probe.py, enumerate.py, discover2.py    the API discovery tooling
fetch-lyrics.py                         fills the library with .lrc sidecars over SMB
```
