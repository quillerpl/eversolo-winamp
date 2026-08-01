#!/usr/bin/env python3
"""
Read-only discovery probe for the Eversolo DMP-A6 local HTTP control API.

Safety rules enforced in code:
  * GET requests only.
  * Every path is checked against SAFE_PREFIXES; anything that looks like it
    mutates state (set*, play*, add*, remove*, seek*, reboot...) is refused.
  * Requests are serialised with a delay between them so the device is never
    flooded.

Discovery oracle (learned from the device):
  status 801 -> the API "family" (path prefix) is not registered at all
  status 405 -> the family exists, but that command name is wrong
  anything else -> a real endpoint responded
"""

import json
import sys
import time
import urllib.parse
import urllib.request

HOST = "192.168.1.207"
PORT = 9529
DELAY = 0.18          # seconds between requests - rate limit
TIMEOUT = 6

# --- safety -----------------------------------------------------------------
# Only command names starting with these verbs are allowed. Read-only by design.
SAFE_VERBS = ("get", "has", "is", "search", "query", "list", "find", "check")
# Explicit deny list of substrings that must never appear in a probed path.
FORBIDDEN = (
    "set", "play", "pause", "stop", "seek", "add", "remove", "delete", "clear",
    "insert", "next", "last", "open", "start", "reboot", "poweroff", "shutdown",
    "toggle", "chang", "update", "login", "logout", "mute", "sleep", "reset",
    "create", "write", "upload", "scan",
)


def is_safe(path: str) -> bool:
    name = path.split("?")[0].rstrip("/").split("/")[-1]
    low = name.lower()
    if not low.startswith(SAFE_VERBS):
        return False
    # allow the verb itself even if a forbidden word appears later, e.g.
    # getPlayQueue contains "play" but is a getter.
    return True


results = []


def call(path: str, note: str = "") -> dict:
    if not is_safe(path):
        raise SystemExit(f"REFUSED unsafe path: {path}")
    url = f"http://{HOST}:{PORT}{path}"
    rec = {"path": path, "note": note}
    try:
        req = urllib.request.Request(url, headers={"User-Agent": "eversolo-probe/1.0"})
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            raw = r.read()
            rec["http"] = r.status
            rec["bytes"] = len(raw)
            rec["ctype"] = r.headers.get("Content-Type", "")
    except Exception as e:  # noqa: BLE001
        rec["http"] = None
        rec["error"] = f"{type(e).__name__}: {e}"
        results.append(rec)
        time.sleep(DELAY)
        return rec

    try:
        data = json.loads(raw.decode("utf-8", "replace"))
        rec["json"] = data
        if isinstance(data, dict):
            rec["keys"] = sorted(data.keys())
            st = data.get("status", data.get("code"))
            rec["status"] = st
            if st == 801:
                rec["verdict"] = "family-missing"
            elif st == 405:
                rec["verdict"] = "no-such-command"
            else:
                rec["verdict"] = "OK"
        else:
            rec["verdict"] = "OK"
    except Exception:
        rec["verdict"] = "OK-nonjson"
        rec["sample"] = raw[:120].decode("utf-8", "replace")

    results.append(rec)
    time.sleep(DELAY)
    return rec


# --- phase 1: which API families exist? ------------------------------------
FAMILIES = [
    "/ZidooMusicControl/v2", "/ZidooMusicControl", "/ZidooMusicControl/v3",
    "/ZidooControlCenter", "/ControlCenter", "/ZidooControlCenter/RemoteControl",
    "/SystemSettings", "/SystemSettings/displaySettings",
    "/ZidooFileControl/v2", "/ZidooFileControl",
    "/ZidooPoster/v2", "/ZidooPoster", "/ZidooVideoPlay", "/VideoPlay",
    "/ZidooMediaCenter", "/MusicControl", "/ZidooLocalMusic", "/ZidooMusic",
    "/ZidooUpnp", "/ZidooDlna", "/ZidooSetting", "/ZidooScreen",
    "/MusicList", "/ZidooMusicList", "/EversoloControl", "/Eversolo",
]

# --- phase 2: candidate read-only command names -----------------------------
MUSIC_CMDS = [
    # state / queue
    "getState", "getPlayQueue", "getPlayQueueList", "getQueue", "getPlayingQueue",
    "getPlayQueueInfo", "getCurrentQueue", "getQueueList", "getTrackList",
    # library browsing
    "getMusicList", "getSongList", "getAlbumList", "getArtistList",
    "getGenreList", "getFolderList", "getPlaylist", "getPlaylistList",
    "getPlayList", "getPlayListList", "getAllMusic", "getAllSong",
    "getAllAlbum", "getAllArtist", "getLocalMusicList", "getLocalList",
    "getMediaList", "getMusicHome", "getHomeList", "getHomeData",
    "getMusicByAlbum", "getMusicByArtist", "getSongListByAlbum",
    "getCategoryList", "getClassifyList", "getTagList", "getGroupList",
    "getMusicCount", "getCount", "getMusicInfo", "getSongInfo",
    "getRecentList", "getHistoryList", "getRecentPlayList", "getRecentlyPlayed",
    "getFavorList", "getFavoriteList", "getFavourList", "getLoveList",
    "getMusicTypeList", "getSourceList", "getMusicSourceList", "getMusicSource",
    "getList", "getData", "getIndexList", "getLetterList",
    # search
    "searchMusic", "searchSong", "searchAlbum", "searchArtist", "searchList",
    "search", "searchKey", "searchAll", "searchLyric",
    # storage / files
    "getStorageList", "getDeviceList", "getDiskList", "getUsbList",
    "getMountList", "getSambaList", "getNasList", "getFileList",
    "getInternalStorage", "getPathList", "getDirList", "getMusicPath",
    "getScanState", "getScanProgress", "getMediaScanState", "getDbState",
    # radio / streaming
    "getRadioList", "getRadioStationList", "getFavorRadioList",
    # playback settings (read-only getters)
    "getLoopModel", "getPlayMode", "getPlayModeList", "getTrackIndex",
    "getVolume", "getDevicesVolume", "getMuteVolume", "getOutputList",
    "getInputAndOutputList", "getPowerOption", "getEQSetting", "getEQList",
    "getDspSetting", "getSubSetting", "getLyric", "getSearchLyric",
    "getVersion", "getModel", "getDeviceInfo", "getConfig", "getSettingList",
]

FILE_CMDS = [
    "getFileList", "getDeviceList", "getStorageList", "getDirList",
    "getFileInfo", "getPathList", "getMountList", "getDevices", "getList",
    "getSambaList", "getNasList", "getUsbList", "getDiskList", "getRootList",
]

SYS_CMDS = [
    "getSettingList", "getItemSettingIcon", "getDeviceInfo", "getVersion",
    "getSettingsList", "getSystemSettingList", "getList", "getConfig",
    "getStorageList", "getNetworkInfo", "getAboutInfo",
]


def main() -> None:
    print("=" * 78)
    print(f"PHASE 1 - which API families exist on {HOST}:{PORT}?")
    print("=" * 78)
    live_families = []
    for fam in FAMILIES:
        r = call(f"{fam}/getZzProbeXyz", note="family probe")
        v = r.get("verdict")
        mark = {"no-such-command": "EXISTS", "family-missing": "-",
                "OK": "EXISTS(!)"}.get(v, v)
        if v in ("no-such-command", "OK", "OK-nonjson"):
            live_families.append(fam)
        print(f"  {mark:12} {fam}   {r.get('error','')}")

    print()
    print("=" * 78)
    print("PHASE 2 - command discovery in live families")
    print("=" * 78)

    plan = []
    for fam in live_families:
        if "Music" in fam or fam in ("/ControlCenter", "/ZidooControlCenter"):
            plan += [(fam, c) for c in MUSIC_CMDS]
        elif "File" in fam:
            plan += [(fam, c) for c in FILE_CMDS]
        elif "SystemSettings" in fam:
            plan += [(fam, c) for c in SYS_CMDS]
        else:
            plan += [(fam, c) for c in MUSIC_CMDS[:40]]

    print(f"  ({len(plan)} probes, ~{int(len(plan) * DELAY)}s at {DELAY}s spacing)\n")
    hits = []
    for fam, cmd in plan:
        r = call(f"{fam}/{cmd}")
        if r.get("verdict", "").startswith("OK"):
            hits.append(r)
            keys = r.get("keys", [])
            print(f"  HIT  {fam}/{cmd}  ({r.get('bytes')}B) keys={keys[:10]}")

    print()
    print("=" * 78)
    print(f"SUMMARY: {len(hits)} working endpoints found")
    print("=" * 78)
    for h in hits:
        print(f"  {h['path']}")

    out = "/private/tmp/claude-501/-Volumes-SSD-Mac-AI-Experiments/94da23df-4204-45f3-a503-fbbaaff66d39/scratchpad/probe_results.json"
    with open(out, "w") as f:
        json.dump(results, f, indent=1, ensure_ascii=False)
    print(f"\nFull results -> {out}")


if __name__ == "__main__":
    main()
