#!/usr/bin/env python3
"""Video bake-off: LTX-Video-distilled vs LTX-2.5 (amisima Turbo Studio, ZeroGPU).
Same start frame, same approved motion prompt, 4s each."""
import os
import subprocess
import sys
import time
import traceback

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

import json

import httpx
from gradio_client import Client, handle_file
from hf_auth import space_client

WORK = os.path.expanduser("~/workspace/av_free_stack")
REF = os.environ.get("BAKEOFF_REF", os.path.join(WORK, "humpty_v2_ref.png"))
MOTION = ("Slow cinematic push-in, 85mm portrait feel: a warm capable "
          "middle-aged egg gentleman \u2014 ivory shell with soft subsurface glow, "
          "big glossy brown eyes with catchlights, thick dark eyebrows, a DARK "
          "STRONG THICK espresso-brown bushy mustache, rosy blushing cheeks, "
          "kind confident smile \u2014 wearing a cozy hand-knitted forest-green "
          "cardigan vest with wooden buttons, sitting comfortably on a "
          "weathered red brick wall with aged mortar. Behind him a sunny "
          "storybook village: cobblestone lane, cottages with slate roofs and "
          "geranium flower boxes, leafy tree, soft clouds. He wobbles gently "
          "side to side, his thick mustache bouncing with each wobble; he "
          "blinks happily, his smile widening, and waves one stubby "
          "three-fingered hand at the viewer. Camera arcs gently at eye "
          "level, shallow depth of field, creamy bokeh. Golden-hour sunlight "
          "from the upper left, warm rim light tracing his shell. Mood: warm, "
          "wholesome, whimsical. Pixar-style 3D animation, buttery smooth "
          "natural motion, ultra detailed, cinematic color grade. Sound: "
          "cheerful whimsical music-box nursery melody, soft and warm.")
NEG = ("blurry, dark, scary, photorealistic, static image, distorted face, "
       "deformed, jittery")


def _is_auth_error(exc):
    """True for credential failures (401/invalid token), never for quota."""
    msg = str(exc).lower()
    if "quota" in msg:
        return False
    return ("401" in msg or "unauthorized" in msg or "invalid token" in msg
            or "invalid_token" in msg or "bad credentials" in msg
            or "authentication" in msg)


# Which client mode each leg actually used; dumped to JSON in main().
AUTH_MODES = {}
_DISTILLED_ANON = False  # once the auth fallback fires, stay anonymous


def upload_image(client, path):
    """Pre-upload for Textbox (server-filepath) image params.

    Fix 2026-09-30: the Lightricks /text_to_video image input is a Textbox,
    not an upload component. Passing a local path makes the server look for
    /home/hatch/... on its own disk (Errno 2). Same pattern as
    motion_scene1.py (proven 2026-09-29 on this exact Space/endpoint).
    Prompts are NOT touched by this change.
    """
    with open(path, "rb") as f:
        r = httpx.post(
            client.upload_url,
            headers=client.headers,
            cookies=client.cookies,
            verify=client.ssl_verify,
            files=[("files", (os.path.basename(path), f))],
            timeout=120,
        )
    r.raise_for_status()
    server_path = r.json()[0]
    print("uploaded, server path:", server_path, flush=True)
    return server_path


def run_with_backoff(label, fn, out_path, tries=10, wait=75):
    if os.path.exists(out_path):
        print(f"{label}: cached -> {out_path}", flush=True)
        return True
    last = None
    for attempt in range(1, tries + 1):
        try:
            vid = fn()
            subprocess.run(["cp", vid, out_path], check=True)
            print(f"{label}: saved -> {out_path}", flush=True)
            return True
        except Exception as exc:  # noqa: BLE001
            last = exc
            msg = str(exc).lower()
            if "quota" in msg and attempt < tries:
                print(f"{label}: quota hit, waiting {wait}s "
                      f"(attempt {attempt}/{tries})", flush=True)
                time.sleep(wait)
            else:
                print(f"{label}: FAILED: {exc}", flush=True)
                traceback.print_exc()
                return False
    print(f"{label}: FAILED after {tries}: {last}", flush=True)
    return False


def _distilled_predict(ltx):
    server_image = upload_image(ltx, REF)  # Textbox param -> upload first
    res = ltx.predict(
        MOTION, NEG, server_image, None, 512, 704, "image-to-video",
        4.0, 9, 7, False, 3.0, False,
        api_name="/text_to_video")
    return res[0]["video"] if isinstance(res, (list, tuple)) else res["video"]


def distilled_leg():
    """Official Lightricks Space -> authenticated via Rahik's HF login.

    On auth errors (401/invalid token only) falls back to an anonymous
    Client; the fallback does not count as the one shot.
    """
    global _DISTILLED_ANON
    if not _DISTILLED_ANON:
        try:
            print("distilled: trying AUTHENTICATED space_client", flush=True)
            ltx = space_client("Lightricks/ltx-video-distilled",
                               httpx_kwargs={"timeout": 900})
            vid = _distilled_predict(ltx)
            AUTH_MODES["distilled"] = "authenticated"
            return vid
        except Exception as exc:  # noqa: BLE001
            if _is_auth_error(exc):
                print(f"distilled: auth error -> anonymous fallback ({exc})",
                      flush=True)
                _DISTILLED_ANON = True
            else:
                raise
    print("distilled: using ANONYMOUS Client (fallback)", flush=True)
    ltx = Client("Lightricks/ltx-video-distilled",
                 httpx_kwargs={"timeout": 900})
    vid = _distilled_predict(ltx)
    AUTH_MODES["distilled"] = "anonymous-fallback"
    return vid


def ltx25_leg():
    # Third-party Space (amisima) — NOT on Rahik's approved official-vendor
    # list, so this leg stays ANONYMOUS by his explicit rule.
    AUTH_MODES["ltx25"] = "anonymous (third-party, per rule)"
    c = Client("amisima/LTX_2.5_Turbo_Studio", httpx_kwargs={"timeout": 1200})
    # MOTION already ends with the approved "Sound: ..." direction for joint
    # video+audio; do not append a duplicate.
    prompt = MOTION
    res = c.predict(
        handle_file(REF),  # true Image component; gradio_client 2.7.1 needs
                           # handle_file to upload (raw path str fails pydantic
                           # ImageData validation server-side). Prompt unchanged.
        prompt,         # 1 scene prompt (motion & sound)
        NEG,            # 2 negative prompt
        "tuned",        # 3 identity preset
        4,              # 4 duration seconds
        704,            # 5 max width
        512,            # 6 max height
        "anchor only",  # 7 face mode
        api_name="/generate")
    # returns (video, status, used_seed)
    vid = res[0]["video"] if isinstance(res[0], dict) else res[0]
    print("ltx25 status:", str(res[1])[:200], flush=True)
    return vid


def main():
    if not os.path.exists(REF):
        print(f"MISSING {REF}", file=sys.stderr)
        sys.exit(2)
    ok1 = run_with_backoff("distilled",
                           distilled_leg,
                           os.path.join(WORK, "bakeoff_distilled.mp4"))
    ok2 = run_with_backoff("ltx25",
                           ltx25_leg,
                           os.path.join(WORK, "bakeoff_ltx25.mp4"))
    print(f"RESULT distilled={ok1} ltx25={ok2}", flush=True)
    modes_path = os.path.join(WORK, "bakeoff_auth_modes.json")
    with open(modes_path, "w") as f:
        json.dump(AUTH_MODES, f, indent=2)
    print(f"auth modes -> {modes_path}: {AUTH_MODES}", flush=True)


if __name__ == "__main__":
    main()
