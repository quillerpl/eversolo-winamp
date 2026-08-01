#!/usr/bin/env python3
"""
Phase 2 discovery: enumerate command names in /ZidooMusicControl/v2/.

Oracle discovered on the device:
    {"status":405,"msg":"Method Not Allowed: [name]"}  -> command does NOT exist
    anything else                                      -> command EXISTS

Read-only: only names beginning with get/search/has/is/query/list are probed,
so nothing here can start, stop or alter playback.
"""
import json
import time
import urllib.request

HOST, PORT, DELAY = "192.168.1.207", 9529, 0.15
BASE = f"http://{HOST}:{PORT}"

SAFE_VERBS = ("get", "search", "has", "is", "query", "list", "find")

NOUNS = [
    "Music", "MusicList", "Song", "SongList", "Album", "AlbumList", "Artist",
    "ArtistList", "Genre", "GenreList", "Folder", "FolderList", "Dir", "DirList",
    "Playlist", "PlayList", "Queue", "PlayQueue", "Track", "TrackList",
    "Media", "MediaList", "File", "FileList", "Library", "Local", "LocalMusic",
    "Favor", "Favorite", "Favour", "Love", "Recent", "History", "Category",
    "Type", "Tag", "Cover", "Image", "AlbumArt", "Lyric", "Info", "Detail",
    "State", "Status", "Setting", "Settings", "Config", "Device", "DeviceList",
    "Storage", "Disk", "Usb", "Nas", "Samba", "Smb", "Cd", "Sacd", "Dsd",
    "Radio", "Station", "Internet", "Stream", "Home", "Index", "Letter",
    "Count", "Total", "Sort", "Filter", "Group", "Aggregation", "Collection",
    "Source", "Input", "Output", "Volume", "Eq", "Dsp", "Sub", "Power",
    "Screen", "Vu", "Spectrum", "Knob", "Data", "List", "All", "AllMusic",
    "MusicInfo", "SongInfo", "AlbumInfo", "ArtistInfo", "AlbumSong",
    "AlbumMusic", "ArtistAlbum", "ArtistMusic", "MusicByAlbum", "MusicByArtist",
    "SongsByAlbum", "MusicDetail", "PlayMode", "LoopModel", "PlayState",
    "PlayInfo", "PlayingMusic", "CurrentMusic", "Progress", "Position",
    "Scan", "ScanState", "Db", "Database", "Path", "Root", "Mount",
]

EXTRA = [
    # names that follow the confirmed searchMusic / searchAlbum / searchArtist
    # convention, plus plausible browse verbs
    "searchMusic", "searchAlbum", "searchArtist", "searchSong", "searchAll",
    "searchFolder", "searchPlaylist", "searchGenre", "searchLyric",
    "getState", "getPlayQueue", "getVolume", "getPowerOption",
    "getInputAndOutputList", "getImage", "getLyric", "getSongLyric",
    "getMusicListByAlbumId", "getMusicListByArtistId", "getAlbumListByArtist",
    "getMusicByAlbumId", "getSongByAlbumId", "getListByType", "getMusicByType",
    "getMusicPlayList", "getMyPlaylist", "getMyPlayList", "getUserPlaylist",
    "getMusicLibrary", "getLibraryList", "getMusicDbList", "getDbMusicList",
    "getAllMusicList", "getAllAlbumList", "getAllArtistList",
    "getMusicFolderList", "getLocalFolderList", "getMusicFileList",
    "getPlayQueueIndex", "getQueueIndex", "getCurrentIndex",
    "getSacdArea", "getMqaMode", "getOutputInfo", "getDeviceCapability",
]


def probe(path):
    url = BASE + path
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "eversolo-probe/1.0"})
        with urllib.request.urlopen(req, timeout=6) as r:
            raw = r.read()
    except Exception as e:  # noqa: BLE001
        return {"path": path, "err": f"{type(e).__name__}"}
    try:
        j = json.loads(raw.decode("utf-8", "replace"))
    except Exception:
        return {"path": path, "exists": True, "note": "non-json", "bytes": len(raw)}
    msg = j.get("msg", "") if isinstance(j, dict) else ""
    if isinstance(j, dict) and j.get("status") == 405 and msg.startswith("Method Not Allowed"):
        return {"path": path, "exists": False}
    return {"path": path, "exists": True, "bytes": len(raw),
            "keys": sorted(j.keys())[:14] if isinstance(j, dict) else "array",
            "msg": msg, "status": j.get("status") if isinstance(j, dict) else None}


names = set(EXTRA)
for v in ("get", "search"):
    for n in NOUNS:
        names.add(v + n)
names = sorted(n for n in names if n.startswith(SAFE_VERBS))

print(f"Probing {len(names)} read-only command names under /ZidooMusicControl/v2/")
print(f"(~{int(len(names) * DELAY)}s at {DELAY}s spacing)\n")

found = []
for i, n in enumerate(names, 1):
    r = probe(f"/ZidooMusicControl/v2/{n}")
    time.sleep(DELAY)
    if r.get("exists"):
        found.append(r)
        print(f"  EXISTS  {n:32} status={r.get('status')} bytes={r.get('bytes')} "
              f"msg={r.get('msg','')[:30]!r} keys={r.get('keys')}")

print(f"\n{len(found)} existing commands found out of {len(names)} probed.")
with open("enumerate_results.json", "w") as f:
    json.dump(found, f, indent=1, ensure_ascii=False)
