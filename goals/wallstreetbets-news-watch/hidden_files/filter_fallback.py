#!/usr/bin/env python3
"""Filter browser-fallback candidates for wallstreetbets-news-watch.

Reads candidates JSON (array of {id,title,permalink,flair,posted_time_text,live})
from stdin or a file arg, applies seen list + 72h recency, updates
seen_posts.json, and prints the deliverable posts (live, new, within 72h).
posted_time_text is parsed as 'N hours ago' / 'N days ago' relative to now;
the pipeline uses created_utc, so we approximate via epoch.
"""
import json, sys, time, re
from datetime import datetime, timezone, timedelta

HIDDEN = "/home/hatch/workspace/goals/wallstreetbets-news-watch/hidden_files"

def parse_time(text):
    text = (text or "").strip().lower()
    now = time.time()
    m = re.match(r"(\d+)\s*minutes?\s*ago", text)
    if m: return now - int(m.group(1)) * 60
    m = re.match(r"(\d+)\s*hours?\s*ago", text)
    if m: return now - int(m.group(1)) * 3600
    m = re.match(r"just now|a moment ago", text)
    if m: return now
    m = re.match(r"(\d+)\s*days?\s*ago", text)
    if m: return now - int(m.group(1)) * 86400
    # explicit datetime: "Thursday, October 8, 2026 at 9:01:26 PM EDT"
    t2 = re.sub(r"^(\w+day),\s+", "", text)      # drop leading weekday
    t2 = t2.replace(" at ", ", ")
    t2 = re.sub(r"\s+(EDT|EST|ET|UTC|GMT)$", "", t2)
    for fmt in ("%B %d, %Y, %I:%M:%S %p", "%B %d, %Y, %I:%M %p",
                "%b %d, %Y, %I:%M:%S %p", "%b %d, %Y, %I:%M %p",
                "%Y-%m-%dT%H:%M:%S%z", "%Y-%m-%d %H:%M:%S"):
        try:
            dt = datetime.strptime(t2, fmt)
            if dt.tzinfo is None:
                from zoneinfo import ZoneInfo
                dt = dt.replace(tzinfo=ZoneInfo("America/New_York"))
            return dt.timestamp()
        except Exception:
            pass
    return None

def main():
    src = sys.argv[1] if len(sys.argv) > 1 else "-"
    data = json.load(open(src)) if src != "-" else json.load(sys.stdin)
    if isinstance(data, dict):
        data = data.get("candidates", data.get("posts", []))

    seen_path = HIDDEN + "/seen_posts.json"
    seen = json.load(open(seen_path))
    seen_set = set(seen)
    cutoff = time.time() - 72 * 3600

    deliver, appended = [], []
    for c in data:
        pid = str(c.get("id", "")).strip()
        if not pid or pid in seen_set:
            continue
        seen_set.add(pid); seen.append(pid); appended.append(pid)
        created = parse_time(c.get("posted_time_text", ""))
        if created is None or created < cutoff:
            continue  # stale or unparseable -> appended but not reported
        if not c.get("live", False):
            continue  # removed
        deliver.append({
            "id": pid, "title": c.get("title", ""),
            "link": c.get("permalink", ""),
            "posted": datetime.fromtimestamp(created, tz=timezone.utc).isoformat(),
        })

    with open(seen_path, "w") as f:
        json.dump(seen, f)

    print(json.dumps({"deliver": deliver, "appended": appended}, indent=2))

main()
