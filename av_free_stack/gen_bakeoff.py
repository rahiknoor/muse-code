#!/usr/bin/env python3
"""Image bake-off: FLUX.2-klein-4B and Z-Image-Turbo, same approved mustache prompt."""
import os
import shutil
import sys
import time

from gradio_client import Client

from hf_auth import space_client

KLEIN_SPACE = "black-forest-labs/FLUX.2-klein-4B"
ZTURBO_SPACE = "Tongyi-MAI/Z-Image-Turbo"  # third-party: stays ANONYMOUS (not on approved list)


def is_auth_error(exc: Exception) -> bool:
    msg = str(exc).lower()
    if "quota" in msg:
        return False
    return any(
        k in msg
        for k in ("401", "unauthorized", "invalid token", "invalid_token", "forbidden", "access denied")
    )

# IDENTICAL prompt text to the approved mustache-Humpty prompt (no re-approval needed).
PROMPT = """3D animated family film character portrait, character-sheet detail. CHARACTER — Humpty Dumpty, a warm capable middle-aged family-man egg. HEAD: large smooth egg-shaped head, ivory-white eggshell with a delicate matte finish, soft subsurface scattering glow, a few faint natural speckles near the crown. FACE: big warm brown glossy eyes, large and expressive with upper lids slightly heavy in calm confidence, sparkling white catchlights in each eye; thick dark eyebrows, gently arched; a DARK, STRONG, THICK bushy mustache — deep espresso-brown, bold and wide across the upper lip, neatly shaped with natural tapered ends, no curled handlebar tips; full rounded rosy cheeks with a soft peachy blush; kind confident closed-lip smile with gentle laugh lines at the eyes and mouth corners. BODY: plump sturdy egg body, slightly bottom-heavy, small stubby arms with simple rounded three-finger hands. CLOTHING: cozy hand-knitted forest-green cardigan vest, visible chunky knit texture and ribbed hem, three small light-wooden buttons down the front, fitting snugly over the shell. POSE: sitting comfortably on top of a weathered red brick wall, body angled three-quarters toward the viewer, head tilted slightly with warm direct eye contact, one arm resting on a raised knee, calm welcoming posture of a dad at ease. SETTING: sunny storybook village — weathered red brick wall with aged mortar and a touch of moss at the base; cobblestone lane winding past cozy cottages with slate roofs and window flower boxes overflowing with red geraniums; a leafy green tree to one side, soft white clouds in a clear blue sky, two distant birds in flight, warm morning haze. LIGHTING: golden-hour sunlight streaming from the upper left, warm key light sculpting the shell, soft luminous rim light tracing the right edge of the egg, gentle fill keeping shadows open, long soft shadows on the bricks. CAMERA: eye-level medium portrait shot, 85mm lens feel, head-and-torso framing with breathing room above, shallow depth of field, creamy bokeh on the village. STYLE: Pixar-style 3D animated film render, ray-traced soft shadows, physically-based materials, ultra-detailed knit and eggshell micro-texture, vibrant cinematic color grade, warm wholesome mood, 8k masterpiece."""

OUT_DIR = "/home/hatch/workspace/av_free_stack"
MAX_ATTEMPTS = 10
BACKOFF = (60, 90)


def save_result(result_path, dest):
    shutil.copy(result_path, dest)
    print(f"SAVED {dest} ({os.path.getsize(dest)} bytes)")


def run_klein4b():
    client = space_client(KLEIN_SPACE)  # authenticated (official-vendor Space)
    authed = True
    last = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            # Distilled (4 steps) = the model's headline fast path; seed 7, no randomization.
            result, used_seed = client.predict(
                PROMPT, [], "Distilled (4 steps)", 7, False, 1024, 1024,
                4, 1.0, False, api_name="/infer",
            )
            save_result(result["path"], os.path.join(OUT_DIR, "bench_klein4b.png"))
            print(f"klein-4B OK on attempt {attempt}, seed={used_seed} via {'AUTHENTICATED' if authed else 'ANONYMOUS'} client")
            return True
        except Exception as e:
            last = e
            msg = str(e)[:200]
            print(f"klein-4B attempt {attempt} failed: {msg}")
            if authed and is_auth_error(e):
                print("klein-4B AUTH FAILED — falling back to anonymous Client")
                client = Client(KLEIN_SPACE)
                authed = False
                continue
            if "quota" in msg.lower() and attempt < MAX_ATTEMPTS:
                time.sleep(BACKOFF[0])
    print(f"klein-4B BLOCKED: {str(last)[:300]}")
    return False


def run_zturbo():
    client = Client(ZTURBO_SPACE)  # anonymous by design — third-party Space
    last = None
    for attempt in range(1, MAX_ATTEMPTS + 1):
        try:
            images, seed_used, seed = client.predict(
                PROMPT, "1024x1024 ( 1:1 )", 7, 8, 3.0, False, [],
                api_name="/generate",
            )
            if not images:
                raise RuntimeError("empty gallery returned")
            save_result(images[0]["image"]["path"], os.path.join(OUT_DIR, "bench_zturbo.png"))
            print(f"Z-Image-Turbo OK on attempt {attempt}, seed={seed_used}")
            return True
        except Exception as e:
            last = e
            msg = str(e)[:200]
            print(f"Z-Image-Turbo attempt {attempt} failed: {msg}")
            if "quota" in msg.lower() and attempt < MAX_ATTEMPTS:
                time.sleep(BACKOFF[0])
    print(f"Z-Image-Turbo BLOCKED: {str(last)[:300]}")
    return False


if __name__ == "__main__":
    which = sys.argv[1] if len(sys.argv) > 1 else "all"
    ok = True
    if which in ("all", "klein4b"):
        ok = run_klein4b() and ok
    if which in ("all", "zturbo"):
        ok = run_zturbo() and ok
    sys.exit(0 if ok else 1)
