#!/usr/bin/env python3
"""Enumerate playlist video IDs via Innertube browse API (same ANDROID-client
pattern as TranscriptFetch.java). Output: JSON array of {videoId, title}."""
import json, subprocess, sys, time

PLAYLIST = "PL-pohOSaL8P9kLZP8tQ1K1QWdZEgwiBM0"
BROWSE_URL = ("https://www.youtube.com/youtubei/v1/browse"
              "?key=AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8&prettyPrint=false")
UA = "com.google.android.youtube/20.10.38 (Linux; U; Android 14; en_US)"


def post(body):
    p = subprocess.run(
        ["curl", "-sS", "-m", "60", "-X", "POST", "-A", UA,
         "-H", "Content-Type: application/json",
         "-H", "Accept-Language: en-US,en;q=0.9",
         "--data-binary", "@-", BROWSE_URL],
        input=json.dumps(body).encode(), capture_output=True)
    if p.returncode != 0:
        raise RuntimeError("curl failed: " + p.stderr.decode()[:200])
    return json.loads(p.stdout.decode())


def extract_items(resp):
    items = []
    # Continuation responses: onResponseReceivedActions OR continuationContents
    cc = resp.get("continuationContents") or {}
    pvlc = cc.get("playlistVideoListContinuation")
    if pvlc:
        for c in pvlc.get("contents", []):
            pvr = c.get("playlistVideoRenderer")
            if pvr and "videoId" in pvr:
                runs = pvr.get("title", {}).get("runs", [])
                title = "".join(r.get("text", "") for r in runs)
                items.append({"videoId": pvr["videoId"], "title": title})
        token = None
        for c in pvlc.get("continuations", []):
            token = (c.get("nextContinuationData") or {}).get("continuation")
        return items, token
    actions = resp.get("onResponseReceivedActions") or []
    for a in actions:
        ac = a.get("appendContinuationItemsAction")
        if ac:
            conts = ac.get("continuationItems", [])
            token = None
            for c in conts:
                pvr = c.get("playlistVideoRenderer")
                if pvr and "videoId" in pvr:
                    runs = pvr.get("title", {}).get("runs", [])
                    title = "".join(r.get("text", "") for r in runs)
                    items.append({"videoId": pvr["videoId"], "title": title})
                ncd = (c.get("continuationItemRenderer") or {}).get(
                    "continuationEndpoint", {}).get("continuationCommand", {})
                if ncd.get("token"):
                    token = ncd["token"]
            return items, token
    try:
        contents = resp["contents"]
        # ANDROID client: singleColumnBrowseResultsRenderer
        scr = contents.get("singleColumnBrowseResultsRenderer") or \
              contents.get("twoColumnBrowseResultsRenderer")
        tabs = scr["tabs"]
        sects = tabs[0]["tabRenderer"]["content"]["sectionListRenderer"]["contents"]
        first = sects[0]
        pvl = first.get("playlistVideoListRenderer") or \
              first["itemSectionRenderer"]["contents"][0]["playlistVideoListRenderer"]
    except (KeyError, TypeError):
        return items, None
    for c in pvl.get("contents", []):
        pvr = c.get("playlistVideoRenderer")
        if pvr and "videoId" in pvr:
            runs = pvr.get("title", {}).get("runs", [])
            title = "".join(r.get("text", "") for r in runs)
            items.append({"videoId": pvr["videoId"], "title": title})
    token = None
    for c in pvl.get("continuations", []):
        token = (c.get("nextContinuationData") or {}).get("continuation")
    return items, token


def main():
    all_items, token, seen = [], None, set()
    body = {"context": {"client": {"clientName": "ANDROID", "clientVersion": "20.10.38",
                                   "androidSdkVersion": 34, "hl": "en", "gl": "US"}},
            "browseId": "VL" + PLAYLIST}
    pages = 0
    while True:
        resp = post(body)
        items, token = extract_items(resp)
        for it in items:
            if it["videoId"] not in seen:
                seen.add(it["videoId"]); all_items.append(it)
        pages += 1
        if not token or pages > 5:
            break
        time.sleep(3)
        body = {"context": body["context"], "continuation": token}
    with open("/tmp/playlist_videos.json", "w") as f:
        json.dump(all_items, f, indent=1, ensure_ascii=False)
    print(f"pages={pages} videos={len(all_items)}", file=sys.stderr)
    for it in all_items:
        print(it["videoId"], it["title"])


main()
