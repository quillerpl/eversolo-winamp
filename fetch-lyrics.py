#!/usr/bin/env python3
"""
Fetch time-synced lyrics for a music library and leave them beside the tracks.

Why this is a laptop script and not an app feature: the Eversolo shares its storage
over SMB, so a Mac can do the whole job in one pass - no build, no install, no ADB,
and it can be re-run and resumed. The player's job then shrinks to "if there is a
.lrc next to the track, show it", which is a much smaller thing to get right on a
device with no debugger.

Lyrics come from LRCLIB (https://lrclib.net) - free, no account, no API key, run by
volunteers for exactly this purpose. Be kind to it: the default pace is deliberate.

It never touches your audio files. It only ever writes `song.lrc` next to
`song.flac`. To undo the whole thing:

    find /path/to/music -name '*.lrc' -delete

Usage:
    ./fetch-lyrics.py                          # look, report, write nothing
    ./fetch-lyrics.py --limit 20               # try twenty tracks
    ./fetch-lyrics.py "/Volumes/Share/EF42-73B2/Leonard Cohen" --write
    ./fetch-lyrics.py --write                  # the whole library
"""

import argparse
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

AUDIO = (".flac", ".mp3", ".m4a", ".ogg", ".opus", ".wav", ".aiff", ".aif", ".ape", ".wv")

API = "https://lrclib.net/api"
UA = "EversoloWinamp/1.0.1 (https://github.com/quillerpl/eversolo-winamp)"

# Where the Eversolo usually turns up in Finder once you have connected to it.
LIKELY_MOUNTS = ["/Volumes/Share", "/Volumes/EF42-73B2", "/Volumes/Storage"]


def say(msg):
    """
    Print and flush. Without the flush, piping this to a file or to `tee` shows nothing
    for the first several thousand characters, and an hour-long run looks like a hang.
    """
    print(msg, flush=True)


def find_library(given):
    if given:
        if not os.path.isdir(given):
            sys.exit("No such folder: %s" % given)
        return given
    for m in LIKELY_MOUNTS:
        if os.path.isdir(m):
            return m
    sys.exit(
        "Could not find the Eversolo's drive.\n\n"
        "In Finder: Go > Connect to Server > smb://192.168.1.207 > Connect as Guest.\n"
        "Then run this again, or pass the folder as an argument."
    )


def tracks_in(root):
    """Every audio file, ignoring the ._ stubs macOS scatters around."""
    out = []
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = [d for d in dirnames if not d.startswith(".")]
        for f in sorted(filenames):
            if f.startswith("._"):
                continue
            if f.lower().endswith(AUDIO):
                out.append(os.path.join(dirpath, f))
    return sorted(out)


def probe(path):
    """Artist, title, album and duration, via ffprobe so every format reads the same."""
    try:
        raw = subprocess.run(
            ["ffprobe", "-v", "quiet", "-print_format", "json",
             "-show_format", "-show_entries", "format=duration:format_tags", path],
            capture_output=True, text=True, timeout=30).stdout
        fmt = json.loads(raw).get("format", {})
        tags = {k.lower(): v for k, v in (fmt.get("tags") or {}).items()}
        dur = fmt.get("duration")
        return {
            "artist": tags.get("artist") or tags.get("album_artist") or "",
            "title": tags.get("title") or "",
            "album": tags.get("album") or "",
            "duration": int(round(float(dur))) if dur else None,
        }
    except Exception:
        return None


def get_json(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def lookup(meta):
    """
    Exact match first - artist, title, album and duration together are what stop you
    getting the lyrics of a different recording of the same song. If that misses, search
    and accept a result whose length is within two seconds.
    """
    q = urllib.parse.urlencode({
        "artist_name": meta["artist"], "track_name": meta["title"],
        "album_name": meta["album"], "duration": meta["duration"],
    })
    try:
        d = get_json("%s/get?%s" % (API, q))
        if d.get("syncedLyrics"):
            return d["syncedLyrics"], "exact"
        plain = bool(d.get("plainLyrics"))
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
        plain = False

    q = urllib.parse.urlencode({"artist_name": meta["artist"], "track_name": meta["title"]})
    try:
        for hit in get_json("%s/search?%s" % (API, q)):
            if not hit.get("syncedLyrics"):
                continue
            got = hit.get("duration")
            if got and abs(got - meta["duration"]) <= 2:
                return hit["syncedLyrics"], "search"
    except urllib.error.HTTPError:
        pass
    return None, ("plain-only" if plain else "not-found")


def main():
    p = argparse.ArgumentParser(
        description="Fetch synced lyrics and leave a .lrc beside each track.")
    p.add_argument("folder", nargs="?", help="music folder (default: the Eversolo share)")
    p.add_argument("--write", action="store_true",
                   help="actually write the .lrc files (without this it only reports)")
    p.add_argument("--limit", type=int, help="stop after this many tracks - good for a trial")
    p.add_argument("--force", action="store_true", help="re-fetch even where a .lrc exists")
    p.add_argument("--pace", type=float, default=0.5,
                   help="seconds between requests (default 0.5 - please do not lower it much)")
    args = p.parse_args()

    if subprocess.run(["which", "ffprobe"], capture_output=True).returncode != 0:
        sys.exit("ffprobe is needed to read tags. Install it with:  brew install ffmpeg")

    root = find_library(args.folder)
    say("Library : %s" % root)
    say("Mode    : %s\n" % ("WRITING .lrc files" if args.write
                            else "looking only - nothing will be written"))

    files = tracks_in(root)
    say("Found %d audio files. Reading tags...\n" % len(files))

    done = skipped = written = plain_only = missing = untagged = failed = 0
    misses = []
    started = time.time()

    try:
        for path in files:
            if args.limit and done >= args.limit:
                break
            lrc = os.path.splitext(path)[0] + ".lrc"
            if os.path.exists(lrc) and not args.force:
                skipped += 1
                continue

            meta = probe(path)
            if not meta or not (meta["artist"] and meta["title"] and meta["duration"]):
                untagged += 1
                misses.append(("no usable tags", path))
                continue

            done += 1
            label = "%s - %s" % (meta["artist"], meta["title"])
            try:
                synced, how = lookup(meta)
            except Exception as e:
                failed += 1
                misses.append(("lookup failed: %s" % e, path))
                say("  !!  %s" % label)
                time.sleep(args.pace)
                continue

            if synced:
                if args.write:
                    with open(lrc, "w", encoding="utf-8") as fh:
                        fh.write(synced if synced.endswith("\n") else synced + "\n")
                    written += 1
                say("  ok  %s%s" % (label, "" if how == "exact" else "   (matched by search)"))
            elif how == "plain-only":
                plain_only += 1
                misses.append(("only untimed lyrics available", path))
                say("  --  %s   (no timings)" % label)
            else:
                missing += 1
                misses.append(("nothing in the database", path))
                say("  ??  %s" % label)

            if done % 50 == 0:
                say("      ... %d looked up, %d written, %.0f min elapsed"
                    % (done, written, (time.time() - started) / 60))
            time.sleep(args.pace)
    except KeyboardInterrupt:
        say("\nStopped. Anything already written is fine; run again to carry on.")

    mins = (time.time() - started) / 60
    print("\n---------------------------------------------")
    print("looked up            %d tracks in %.1f min" % (done, mins))
    print("synced lyrics found  %d" % (written if args.write else done - plain_only - missing - failed))
    if args.write:
        print("  .lrc files written %d" % written)
    print("untimed only         %d" % plain_only)
    print("nothing found        %d" % missing)
    print("unreadable tags      %d" % untagged)
    if failed:
        print("lookup errors        %d" % failed)
    if skipped:
        print("already had a .lrc   %d  (use --force to redo)" % skipped)

    if misses:
        report = os.path.join(os.path.expanduser("~"), "Downloads", "lyrics-not-found.txt")
        with open(report, "w", encoding="utf-8") as fh:
            for why, path in misses:
                fh.write("%s\t%s\n" % (why, path))
        print("\nWhat it could not get: %s" % report)

    if not args.write and done:
        print("\nNothing was written. Add --write to keep the lyrics.")


if __name__ == "__main__":
    main()
