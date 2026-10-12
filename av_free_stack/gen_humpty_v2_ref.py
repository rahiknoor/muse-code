#!/usr/bin/env python3
"""Generate the v2 master character reference: older male Humpty with mustache (FLUX.1-dev)."""
import shutil
import sys
import time
import traceback

from gradio_client import Client

from hf_auth import space_client

SPACE_ID = "black-forest-labs/FLUX.1-dev"

PROMPT = """3D animated family film character portrait, character-sheet detail. CHARACTER — Humpty Dumpty, a warm capable middle-aged family-man egg. HEAD: large smooth egg-shaped head, ivory-white eggshell with a delicate matte finish, soft subsurface scattering glow, a few faint natural speckles near the crown. FACE: big warm brown glossy eyes, large and expressive with upper lids slightly heavy in calm confidence, sparkling white catchlights in each eye; thick dark eyebrows, gently arched; a DARK, STRONG, THICK bushy mustache — deep espresso-brown, bold and wide across the upper lip, neatly shaped with natural tapered ends, no curled handlebar tips; full rounded rosy cheeks with a soft peachy blush; kind confident closed-lip smile with gentle laugh lines at the eyes and mouth corners. BODY: plump sturdy egg body, slightly bottom-heavy, small stubby arms with simple rounded three-finger hands. CLOTHING: cozy hand-knitted forest-green cardigan vest, visible chunky knit texture and ribbed hem, three small light-wooden buttons down the front, fitting snugly over the shell. POSE: sitting comfortably on top of a weathered red brick wall, body angled three-quarters toward the viewer, head tilted slightly with warm direct eye contact, one arm resting on a raised knee, calm welcoming posture of a dad at ease. SETTING: sunny storybook village — weathered red brick wall with aged mortar and a touch of moss at the base; cobblestone lane winding past cozy cottages with slate roofs and window flower boxes overflowing with red geraniums; a leafy green tree to one side, soft white clouds in a clear blue sky, two distant birds in flight, warm morning haze. LIGHTING: golden-hour sunlight streaming from the upper left, warm key light sculpting the shell, soft luminous rim light tracing the right edge of the egg, gentle fill keeping shadows open, long soft shadows on the bricks. CAMERA: eye-level medium portrait shot, 85mm lens feel, head-and-torso framing with breathing room above, shallow depth of field, creamy bokeh on the village. STYLE: Pixar-style 3D animated film render, ray-traced soft shadows, physically-based materials, ultra-detailed knit and eggshell micro-texture, vibrant cinematic color grade, warm wholesome mood, 8k masterpiece."""
OUT = "/home/hatch/workspace/av_free_stack/humpty_v2_ref.png"


def is_auth_error(exc: Exception) -> bool:
    msg = str(exc).lower()
    if "quota" in msg:
        return False
    return any(
        k in msg
        for k in ("401", "unauthorized", "invalid token", "invalid_token", "forbidden", "access denied")
    )


def main() -> None:
    seed = int(sys.argv[1]) if len(sys.argv) > 1 else 7
    client = space_client(SPACE_ID)  # authenticated (official-vendor Space)
    authed = True
    last = None
    for attempt in range(1, 11):
        try:
            result, used_seed = client.predict(
                PROMPT, seed, False, 1024, 1024, 3.5, 28, api_name="/infer"
            )
            path = result["path"] if isinstance(result, dict) else result
            shutil.copy(path, OUT)
            print(f"saved {OUT} (seed={used_seed}) via {'AUTHENTICATED' if authed else 'ANONYMOUS'} client")
            return
        except Exception as exc:  # noqa: BLE001
            last = exc
            msg = str(exc).lower()
            if authed and is_auth_error(exc):
                # Live auth test failed: fall back to anonymous; not counted as the shot.
                print("AUTH FAILED — falling back to anonymous Client", flush=True)
                client = Client(SPACE_ID)
                authed = False
                continue
            transient = "quota" in msg or "timeout" in msg or "timed out" in msg
            if transient:
                wait = 5 if "0:00:00" in msg else 75
                print(f"transient error, waiting {wait}s (attempt {attempt}/10)", flush=True)
                time.sleep(wait)
            else:
                traceback.print_exc()
                sys.exit(1)
    print(f"FAILED after retries: {last}", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
