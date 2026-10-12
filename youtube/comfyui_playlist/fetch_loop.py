#!/usr/bin/env python3
"""Fetch transcripts for all 74 playlist videos (one per TranscriptFetch call),
with uniform 30-90s jittered gaps between calls (hard compliance rule).
Writes one vault note per video immediately after its fetch resolves.
Resumable: skips videos whose note file already exists.
Progress: /tmp/fetch_progress.json + stdout lines (unbuffered).
"""
import json, os, random, re, subprocess, sys, time

HOME = os.environ["HOME"]
JAVA = HOME + "/workspace/java/jdk-21/bin/java"
CP = HOME + "/workspace/java-shared/classes:" + HOME + "/workspace/lib/json/json-20240303.jar"
TF = HOME + "/workspace/youtube/TranscriptFetch.java"
CACHE = HOME + "/workspace/youtube/cache/YoutubeCache.java"
JSON_JAR = HOME + "/workspace/lib/json/json-20240303.jar"
BREAKER = HOME + "/workspace/youtube/cache/circuit_breaker.json"
VAULT = HOME + "/workspace/atlas/09 🎓 Tutorials/ComfyUI Playlist"
JOB = "comfyui-playlist"
BASE = os.path.dirname(os.path.abspath(__file__))

# (episode label, short title) in playlist order — from Rahik's playlist page.
EPISODES = [
    ("Ep 01", "Introduction and Installation"),
    ("Ep 02", "Nodes and Workflow Basics"),
    ("Ep 03", "TXT2IMG Basics"),
    ("Ep 04", "IMG2IMG and LoRA Basics"),
    ("Ep 05", "Stable Diffusion 3 Medium"),
    ("Ep 06", "Get 300 Free Art Styles"),
    ("Ep 07", "Working With Text - Art Styles Update"),
    ("Ep 08", "Flux 1: Schnell and Dev Installation Guide"),
    ("Ep 09", "How to Use SDXL ControlNet Union"),
    ("Ep 10", "Flux GGUF and Custom Nodes"),
    ("Ep 11", "LLM, Prompt Generation, img2txt, txt2txt Overview"),
    ("Ep 12", "How to Upscale Your AI Images"),
    ("Ep 13", "Exploring Ollama, LLaVA, Gemma Models"),
    ("Ep 14", "How to Use Flux ControlNet Union Pro"),
    ("Ep 15", "Styles Update, Prompts from File & Batch Images"),
    ("Ep 16", "How to Create Seamless Patterns & Tileable Textures"),
    ("Ep 17", "Flux LoRA Explained! Best Settings & New UI"),
    ("Ep 18", "Easy Photo to Cartoon Transformation!"),
    ("Ep 19", "SDXL & Flux Inpainting Tips with ComfyUI"),
    ("Ep 20", "Sketch to Image Workflow with SDXL or Flux!"),
    ("Ep 21", "How to Use OmniGen in ComfyUI"),
    ("Ep 22", "Remove Image Backgrounds with ComfyUI or Photoshop"),
    ("Ep 23", "How to Install & Use Flux Tools, Fill, Redux, Depth, Canny"),
    ("Ep 24", "Unlock Flux Redux & Inpainting with LoRA"),
    ("Ep 25", "LTX Video - Fast AI Video Generator Model"),
    ("Ep 26", "Live Portrait & Face Expressions"),
    ("Ep 27", "Photo to Watercolor, Oil & Digital Paintings - Workflow"),
    ("Ep 28", "Create Consistent Characters with Flux + Train Loras Online"),
    ("Ep 29", "How to Replace Backgrounds with AI"),
    ("Ep 30", "Game Design with AI and Photoshop"),
    ("Ep 31", "ComfyUI Tips & Tricks You Need to Know"),
    ("Ep 32", "How to Create Vector SVG Files with AI"),
    ("Ep 33", "How to Use Free & Local Text-to-Speech for AI Voiceovers"),
    ("Ep 34", "Turn Images into Prompts Using DeepSeek Janus Pro"),
    ("Ep 35", "How to Run ComfyUI in the Cloud"),
    ("Ep 36", "WAN 2.1 Installation - Turn Text & Images into Video!"),
    ("Ep 37", "LTX 0.9.5 Installation - Images to Video Faster Than Ever!"),
    ("Ep 38", "Bring Portraits to Life! Talking Avatar with Sonic"),
    ("Ep 39", "Using WAN 2.1 with LoRAs for Wild Effects! (Squish, Crush & More)"),
    ("Ep 40", "TeaCache - Speed Up Your Workflows with Smart Caching"),
    ("Ep 41", "How to Generate Photorealistic Images - Fluxmania"),
    ("Ep 42", "Inpaint & Outpaint Update + Tips for Better Results"),
    ("Ep 43", "KayTool - Align Nodes, Tweak Image Colors, Monitor Resources"),
    ("Ep 44", "HiDream AI - How to Set Up & Choose the Best Model"),
    ("Ep 45", "Unlocking Flux Dev ControlNet Union Pro 2.0 Features"),
    ("Ep 46", "How to Upscale Your AI Images (Update)"),
    ("Ep 47", "Make Free AI Music with ACE-Step V1"),
    ("Ep 48", "LTX 0.9.7 - Turn Images into Video at Lightning Speed!"),
    ("Ep 49", "Master txt2video, img2video & video2video with Wan 2.1 VACE"),
    ("Ep 50", "Generate Stunning AI Images for Social Media (50+ Free Workflows)"),
    ("Ep 51", "Nvidia Cosmos Predict2 Image & Video Models in Action"),
    ("Ep 52", "Master Flux Kontext - Inpainting, Editing & Character Consistency"),
    ("Ep Nunchaku", "Speed Up Flux Dev & Kontext with This Trick"),
    ("Ep 53", "Flux Kontext LoRA Training with Fal AI - Tips & Tricks"),
    ("Ep 54", "Create Vector SVG Designs with Flux Dev & Kontext"),
    ("Ep 55", "Sage Attention, Wan Fusion X, Wan 2.2 & Video Upscale Tips"),
    ("Ep 56", "Flux Krea & Shuttle Jaguar Workflows"),
    ("Ep 57", "Qwen Image Generation Workflow for Stunning Results"),
    ("Ep 58", "Wan 2.2 Image Generation Workflows"),
    ("Ep 59", "Qwen Edit Workflows for Smarter Image Edits"),
    ("Ep 60", "Infinite Talk (Audio-Driven Talking AI Characters)"),
    ("Ep 61", "USO - Unified Style and Subject-Driven Generation"),
    ("Ep 62", "Nunchaku Update | Qwen Control Net, Qwen Edit & Inpaint"),
    ("Ep 63", "API Nodes - Run Nano Banana, GPT-5 & Seedream 4"),
    ("Ep 64", "Nunchaku Qwen Image Edit 2509"),
    ("Ep 65", "VibeVoice Free Text to Speech Workflow"),
    ("Ep 66", "Qwen Outpainting Workflow + Subgraph Tips"),
    ("Ep 67", "Fluxmania Nunchaku + Wan 2.2 and Rapid AIO Workflows"),
    ("Ep 68", "How to Create Anime Illustrations - NetaYume v3.5"),
    ("Ep 69", "Create Long Videos with LongCat Video Model"),
    ("Ep 70", "Nunchaku Qwen Loras - Relight, Camera Angle & Scene Change"),
    ("Ep 71", "QwenVL 3 - Get Prompts From Images & Video"),
    ("Ep 72", "Z-Image Turbo Workflows, ControlNet Essentials & LoRA Training"),
    ("Ep 73", "Final Episode & Z-Image ControlNet 2.0"),
]


def sanitize(s):
    s = re.sub(r'[\\/:*?"<>|]', "", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def breaker_open():
    if not os.path.exists(BREAKER):
        return False
    try:
        b = json.load(open(BREAKER))
        age = time.time() * 1000 - b.get("tripped_at_ms", 0)
        return age < 24 * 3600 * 1000
    except Exception:
        return False


def run(cmd):
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=300)
    return p.returncode, p.stdout, p.stderr


def cache_get(vid):
    rc, out, _ = run([JAVA, "--class-path", JSON_JAR, CACHE, "get", vid, "--job", JOB])
    if rc != 0:
        return None
    try:
        return json.loads(out.strip())
    except Exception:
        return None


def write_note(vid, label, title, rec):
    fname = sanitize(f"{label} - {title}") + ".md"
    path = os.path.join(VAULT, fname)
    transcript = rec.get("transcript") if rec else None
    has_t = bool(transcript)
    channel = (rec or {}).get("channel", "")
    reason = (rec or {}).get("noTranscriptReason", "")
    desc = (rec or {}).get("description", "") or ""
    fm = [
        "---",
        f'title: "{label} - {title}"',
        f'episode: "{label}"',
        f"video_url: https://www.youtube.com/watch?v={vid}",
        f'channel: "{channel}"',
        f"transcript_chars: {len(transcript) if has_t else 0}",
        f"has_transcript: {'true' if has_t else 'false'}",
        "fetched: 2026-10-01",
        "---",
        "",
        f"# {label} - {title}",
        "",
        f"Video: https://www.youtube.com/watch?v={vid}",
        "",
    ]
    if has_t:
        fm += ["## Transcript", "", transcript.strip(), ""]
    else:
        fm += ["## Transcript", "",
               f"*No transcript available ({reason}).*", "", "## Description", "",
               desc[:3000], ""]
    with open(path, "w", encoding="utf-8") as f:
        f.write("\n".join(fm))
    return fname


def main():
    ids = []
    for line in open(os.path.join(BASE, "playlist_ids.txt")):
        line = line.strip()
        if line:
            ids.append(line.split(" ", 1)[0])
    assert len(ids) == 74 == len(EPISODES), f"count mismatch: {len(ids)} ids, {len(EPISODES)} episodes"

    progress = {"done": [], "null": [], "failed": [], "filenames": {}}
    if os.path.exists(os.path.join(BASE, "fetch_progress.json")):
        progress = json.load(open(os.path.join(BASE, "fetch_progress.json")))
    done_set = set(progress["done"]) | set(progress["null"]) | set(progress["failed"])

    first = True
    for i, vid in enumerate(ids):
        label, title = EPISODES[i]
        fname = sanitize(f"{label} - {title}") + ".md"
        if vid in done_set or os.path.exists(os.path.join(VAULT, fname)):
            if vid not in done_set:
                progress["done"].append(vid)
                progress["filenames"][vid] = fname
            continue
        if breaker_open():
            print("BREAKER_OPEN: stopping all YouTube traffic", flush=True)
            break
        if not first:
            gap = random.uniform(30, 90)
            print(f"[{i+1}/74] sleeping {gap:.0f}s before {vid} ({label})", flush=True)
            time.sleep(gap)
        first = False
        rc, out, err = run([JAVA, "--class-path", CP, TF, "fetch", vid, "--job", JOB])
        if rc == 3:
            print("EXIT 3: circuit breaker tripped — stopping everything", flush=True)
            break
        try:
            res = json.loads(out.strip().splitlines()[-1])
        except Exception:
            res = {"ok": False, "error": "unparseable output: " + (err.strip()[:200] or out.strip()[:200])}
        if not res.get("ok"):
            print(f"[{i+1}/74] FAILED {vid} ({label}): {res.get('error')}", flush=True)
            progress["failed"].append(vid)
        else:
            rec = cache_get(vid)
            fn = write_note(vid, label, title, rec)
            progress["filenames"][vid] = fn
            if res.get("noTranscript"):
                progress["null"].append(vid)
                print(f"[{i+1}/74] NULL {vid} ({label}) — note written", flush=True)
            else:
                progress["done"].append(vid)
                print(f"[{i+1}/74] OK {vid} ({label}) chars={res.get('chars')} cached={res.get('cached')}", flush=True)
        done_set.add(vid)
        json.dump(progress, open(os.path.join(BASE, "fetch_progress.json"), "w"), indent=1)
    print(f"FINISHED: done={len(progress['done'])} null={len(progress['null'])} failed={len(progress['failed'])}", flush=True)


if __name__ == "__main__":
    main()
