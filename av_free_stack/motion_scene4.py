"""Scene 4: "Couldn't put Humpty together again."
Motion-research prompts (2026-09-29). Waits for the keyframe; quota-retries.
Rahik greenlit the full assembly 2026-09-29."""
import os, subprocess, time

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

import httpx
from gradio_client import Client
from gradio_client.exceptions import AppError

from hf_auth import space_client

WORK = os.path.expanduser("~/workspace/av_free_stack")
KEY = os.path.join(WORK, "humpty_scene4_keyframe.png")
OUT = os.path.join(WORK, "humpty_scene4_test.mp4")

NEG = ("blurry, dark, scary, photorealistic, static image, "
       "morphing face, melting features, extra teeth, blurry mouth, flickering expression")
PROMPT = ("Humpty Dumpty sits dazed on the cobblestones at the base of the red brick wall, "
          "a small bump star swirling above his head, his eyes blinking slowly. Around him "
          "the king's horses nuzzle him gently and the king's men lean in trying to help — "
          "one soldier scratches his head in puzzlement, another shakes his head slowly. "
          "The camera stays completely static, framing the group gathered around Humpty "
          "in the sunny village street.")


def upload_image(client, path):
    with open(path, "rb") as f:
        r = httpx.post(client.upload_url, headers=client.headers, cookies=client.cookies,
                       verify=client.ssl_verify,
                       files=[("files", (os.path.basename(path), f))], timeout=120)
    r.raise_for_status()
    return r.json()[0]


def main():
    assert os.path.exists(KEY), f"keyframe missing: {KEY}"
    ltx = space_client("Lightricks/ltx-video-distilled", httpx_kwargs={"timeout": 900})
    server_image = upload_image(ltx, KEY)
    print("rendering scene 4...", flush=True)
    import re
    for attempt in range(1, 15):
        try:
            res = ltx.predict(PROMPT, NEG, server_image, None, 512, 704, "image-to-video",
                              3.0, 9, 45, True, 3.0, False, api_name="/text_to_video")
            break
        except AppError as e:
            if "quota" in str(e).lower() and attempt < 14:
                m = re.search(r"try again in (\d+):(\d+):(\d+)", str(e), re.I)
                wait = min(int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3)) + 5, 300) if m else 10
                print(f"[scene4] quota hit, waiting {wait}s (attempt {attempt}/14)", flush=True)
                time.sleep(wait)
                continue
            raise
    vid = res[0]["video"] if isinstance(res, (list, tuple)) else res["video"]
    subprocess.run(["cp", vid, OUT], check=True)
    print("SCENE4:", OUT, flush=True)


if __name__ == "__main__":
    main()
