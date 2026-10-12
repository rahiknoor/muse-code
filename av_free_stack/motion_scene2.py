"""Scene 2 test: "Humpty Dumpty had a great fall." Approved pattern 2026-09-29.

Motion-research applied (2026-09-29): prompt answers "what happens next?" as one
present-tense paragraph describing the motion arc with muscle-level face cues;
static camera; anchored stillness; face kept SIMPLE (surprise gasp, not a complex
grin) — never ask one pass for body motion + complex facial motion.
Face-specific negatives added. Same locked master, settings as scene 1.
"""
import os, subprocess, time

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

import httpx
from gradio_client import Client
from gradio_client.exceptions import AppError

WORK = os.path.expanduser("~/workspace/av_free_stack")
MASTER = os.path.join(WORK, "humpty_MASTER.png")
OUT = os.path.join(WORK, "humpty_scene2_test.mp4")

assert os.path.exists(MASTER), f"master missing: {MASTER}"

NEG = ("blurry, dark, scary, photorealistic, static image, "
       "morphing face, melting features, extra teeth, blurry mouth, flickering expression")
# Scene 2 — "Humpty Dumpty had a great fall."
PROMPT = ("Humpty Dumpty with a cheerful toothy grin sits on top of the red brick wall "
          "in the sunny storybook village street. He begins to wobble, tipping forward "
          "over the edge of the wall. His eyes widen in surprise and his mouth opens in "
          "a small gasp. He tumbles forward through the air toward the cobblestone street "
          "below, his arms flailing gently and his mustache bouncing with the motion. "
          "The camera stays completely static, framing the red brick wall and the village street.")


def upload_image(client, path):
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


def call_with_quota_retry(fn, label, tries=14):
    import re
    for attempt in range(1, tries + 1):
        try:
            return fn()
        except AppError as e:
            msg = str(e)
            if "quota" in msg.lower() and attempt < tries:
                m = re.search(r"try again in (\d+):(\d+):(\d+)", msg, re.I)
                wait = 10
                if m:
                    h, mi, s = map(int, m.groups())
                    wait = min(h * 3600 + mi * 60 + s + 5, 300)
                print(f"[{label}] quota hit, waiting {wait}s (attempt {attempt}/{tries})", flush=True)
                time.sleep(wait)
                continue
            raise
    raise RuntimeError("quota retries exhausted")


def main():
    ltx = Client("Lightricks/ltx-video-distilled", httpx_kwargs={"timeout": 900})
    server_image = upload_image(ltx, MASTER)
    print("rendering scene 2 from locked master...", flush=True)
    res = call_with_quota_retry(
        lambda: ltx.predict(PROMPT, NEG, server_image, None, 512, 704, "image-to-video",
                            3.0, 9, 43, True, 3.0, False, api_name="/text_to_video"),
        "scene2")
    vid = res[0]["video"] if isinstance(res, (list, tuple)) else res["video"]
    subprocess.run(["cp", vid, OUT], check=True)
    print("SCENE2:", OUT, flush=True)


if __name__ == "__main__":
    main()
