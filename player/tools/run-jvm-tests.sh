#!/bin/bash
# Runs the off-device test suites on a desktop JVM.
#
# Why these exist: the Eversolo has no usable ADB, so there is no logcat and no debugger.
# Anything that can be proven on a laptop should be proven on a laptop. These cover the
# parsers and the playlist model, which is where the subtle bugs live.
#
# 156 assertions total, all expected to pass:
#   TagTest               41  FLAC + ID3 tag parsing against real ffmpeg-generated files
#   M3uTest               17  .m3u parsing: Windows paths, CRLF, BOM, unicode, Latin-1, URLs
#   PlaylistTest          23  playlist model, incl. index bookkeeping around the playing track
#   PlaylistGeometryTest  75  window sizing, scrolling, hit-testing and pledit.txt colours
#                             for both the playlist and the browser windows
#
# Usage:  ./tools/run-jvm-tests.sh
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
BUILD="${TMPDIR:-/tmp}/eversolo-jvm-tests"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
JAVAC="$JAVA_HOME/bin/javac"
JAVA="$JAVA_HOME/bin/java"

FIXTURES="$BUILD/fixtures"
rm -rf "$BUILD"; mkdir -p "$BUILD" "$FIXTURES"

# ---------------------------------------------------------------- fixtures
# Real audio files, generated with ffmpeg. Testing the parsers against files we
# synthesised by hand would only prove the parsers agree with our assumptions.
if ! command -v ffmpeg >/dev/null; then
  echo "ffmpeg is required to generate test fixtures (brew install ffmpeg)"; exit 1
fi

mkdir -p "$FIXTURES/root/Leonard Cohen/[M] Old Ideas [32570025] [2012]"
mkdir -p "$FIXTURES/root/Gipsy Kings - The Real Gipsy Kings-3CD-2014 [FLAC]/CD2"
mkdir -p "$FIXTURES/root/ELO/ELO - Secret Messages (CSCS 6036)"
mkdir -p "$FIXTURES/root/playlists"

ffmpeg -v error -f lavfi -i color=c=red:s=64x64 -frames:v 1 "$FIXTURES/cover.png" -y
ffmpeg -v error -f lavfi -i anullsrc=r=44100:cl=stereo -t 2 \
  -metadata TITLE="Amen" -metadata ARTIST="Leonard Cohen" -metadata ALBUM="Old Ideas" \
  -metadata ALBUMARTIST="Leonard Cohen" -metadata DATE="2012" -metadata TRACKNUMBER="2" \
  -metadata GENRE="Folk" \
  "$FIXTURES/root/Leonard Cohen/[M] Old Ideas [32570025] [2012]/02 - Leonard Cohen - Amen.flac" -y
ffmpeg -v error -f lavfi -i anullsrc=r=44100:cl=stereo -t 3 -map_metadata -1 \
  "$FIXTURES/root/Leonard Cohen/[M] Old Ideas [32570025] [2012]/03 - Leonard Cohen - Show Me the Place.flac" -y
ffmpeg -v error -f lavfi -i anullsrc=r=48000:cl=stereo -t 1 -sample_fmt s32 \
  -metadata TITLE="Bem, bem, María" -metadata ARTIST="Gipsy Kings" \
  -metadata ALBUM="The Real Gipsy Kings" -metadata TRACKNUMBER="1/18" -metadata DATE="2014-06-01" \
  "$FIXTURES/root/Gipsy Kings - The Real Gipsy Kings-3CD-2014 [FLAC]/CD2/01. Bem, bem, María.flac" -y
metaflac --import-picture-from="$FIXTURES/cover.png" \
  "$FIXTURES/root/Gipsy Kings - The Real Gipsy Kings-3CD-2014 [FLAC]/CD2/01. Bem, bem, María.flac"
ffmpeg -v error -f lavfi -i anullsrc=r=44100:cl=stereo -t 2 -id3v2_version 3 \
  -metadata title="Bluebird" -metadata artist="Electric Light Orchestra" \
  -metadata album="Secret Messages" -metadata date="1983" -metadata track="3/11" \
  "$FIXTURES/root/ELO/ELO - Secret Messages (CSCS 6036)/03. Bluebird.mp3" -y
ffmpeg -v error -f lavfi -i anullsrc=r=44100:cl=stereo -t 2 -id3v2_version 4 \
  -metadata title="Träumerei — Ø" -metadata artist="Tëst Ärtist" \
  -metadata album="Ünicode Album" -metadata date="1999" -metadata track="4" \
  "$FIXTURES/root/ELO/ELO - Secret Messages (CSCS 6036)/04. Trest.mp3" -y

printf '#EXTM3U\n#EXTINF:455,Leonard Cohen - Amen\n../Leonard Cohen/[M] Old Ideas [32570025] [2012]/02 - Leonard Cohen - Amen.flac\n#EXTINF:180,ELO - Bluebird\n../ELO/ELO - Secret Messages (CSCS 6036)/03. Bluebird.mp3\n\n# a comment\n../Nope/missing.flac\nhttp://example.com/stream.mp3\n' > "$FIXTURES/root/playlists/normal.m3u"
printf '..\\Leonard Cohen\\[M] Old Ideas [32570025] [2012]\\02 - Leonard Cohen - Amen.flac\r\n..\\ELO\\ELO - Secret Messages (CSCS 6036)\\03. Bluebird.mp3\r\n' > "$FIXTURES/root/playlists/windows.m3u"
printf '\xEF\xBB\xBF#EXTM3U\n#EXTINF:60,Gipsy Kings - Bem, bem, Mar\xc3\xada\n../Gipsy Kings - The Real Gipsy Kings-3CD-2014 [FLAC]/CD2/01. Bem, bem, Mar\xc3\xada.flac\n' > "$FIXTURES/root/playlists/bom.m3u8"
printf '#EXTM3U\n#EXTINF:60,Caf\xe9 del Mar\n../Leonard Cohen/[M] Old Ideas [32570025] [2012]/02 - Leonard Cohen - Amen.flac\n' > "$FIXTURES/root/playlists/latin1.m3u"

# ---------------------------------------------------------------- compile + run
# Only pure-Java sources: :tags has no Android dependencies, and Track/Playlist
# deliberately avoid them too, precisely so they can be tested here.
SRC="$BUILD/sources.txt"
find "$HERE/tags/src/main/java" -name "*.java" -print0 | xargs -0 -I{} echo '"{}"' > "$SRC"
echo "\"$HERE/library/src/main/java/org/eversolo/winamp/library/Track.java\"" >> "$SRC"
echo "\"$HERE/playlist/src/main/java/org/eversolo/winamp/playlist/Playlist.java\"" >> "$SRC"
# The playlist window's geometry and its colour parsing carry no Android imports either.
echo "\"$HERE/skin/src/main/java/org/eversolo/winamp/skin/PlaylistGeometry.java\"" >> "$SRC"
echo "\"$HERE/skin/src/main/java/org/eversolo/winamp/skin/PleditStyle.java\"" >> "$SRC"
echo "\"$HERE/skin/src/main/java/org/eversolo/winamp/skin/ListMath.java\"" >> "$SRC"
echo "\"$HERE/skin/src/main/java/org/eversolo/winamp/skin/GenGeometry.java\"" >> "$SRC"
echo "\"$HERE/skin/src/main/java/org/eversolo/winamp/skin/GenSprites.java\"" >> "$SRC"
echo "\"$HERE/skin/src/main/java/org/eversolo/winamp/skin/SkinSprites.java\"" >> "$SRC"
echo "\"$HERE/skin/src/main/java/org/eversolo/winamp/skin/WindowScales.java\"" >> "$SRC"
for t in TagTest M3uTest PlaylistTest PlaylistGeometryTest; do
  echo "\"$HERE/tools/jvm-tests/$t.java\"" >> "$SRC"
done

"$JAVAC" -encoding UTF-8 -nowarn -d "$BUILD/classes" @"$SRC"

fail=0
for t in "TagTest $FIXTURES/root" "M3uTest $FIXTURES/root" "PlaylistTest" "PlaylistGeometryTest"; do
  set -- $t
  echo; echo "=== $1 ==="
  "$JAVA" -Dfile.encoding=UTF-8 -cp "$BUILD/classes" "$@" || fail=1
done
exit $fail
