"""Scene 1 test: locked master -> LTX image-to-video. Approved 2026-09-29.

Fix 2026-09-29: the Space's input_image_filepath is a Textbox (server-side
filepath), not an upload component, so the image must be pre-uploaded via the
/gradio_api/upload endpoint and the returned server path passed as the arg.
Passing the local path made the server look for /home/hatch/... on its disk.
"""
import os, subprocess, time

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

import httpx
from gradio_client import Client
from gradio_client.exceptions import AppError

WORK = os.path.expanduser("~/workspace/av_free_stack")
MASTER = os.path.join(WORK, "humpty_MASTER.png")
OUT = os.path.join(WORK, "humpty_scene1_test.mp4")

assert os.path.exists(MASTER), f"master missing: {MASTER}"

NEG = "blurry, dark, scary, photorealistic, static image"
# Scene 1 — "Humpty Dumpty sat on a wall" (Rahik-approved 2026-09-29)
PROMPT = ("Humpty Dumpty with a cheerful toothy grin sits on top of the red brick wall, "
          "gentle 3D Pixar-style cartoon animation, sunny storybook village street behind him")


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
    print("rendering scene 1 from locked master...", flush=True)
    res = call_with_quota_retry(
        lambda: ltx.predict(PROMPT, NEG, server_image, None, 512, 704, "image-to-video",
                            3.0, 9, 42, True, 3.0, False, api_name="/text_to_video"),
        "scene1")
    vid = res[0]["video"] if isinstance(res, (list, tuple)) else res["video"]
    subprocess.run(["cp", vid, OUT], check=True)
    print("SCENE1:", OUT, flush=True)


if __name__ == "__main__":
    main()
