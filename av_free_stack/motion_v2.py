"""Motion test from the locked master: humpty_cf_klein4b_v2.png -> LTX image-to-video."""
import os, subprocess, sys, time

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

from gradio_client import Client
from gradio_client.exceptions import AppError

WORK = os.path.expanduser("~/workspace/av_free_stack")
MASTER = os.path.join(WORK, "humpty_cf_klein4b_v2.png")
OUT = os.path.join(WORK, "humpty_master_motion.mp4")

NEG = "blurry, dark, scary, photorealistic, static image"
PROMPT = ("the cheerful egg character wobbles side to side on top of the red brick wall, "
          "smiling and blinking, tipping precariously as if about to fall, "
          "gentle cartoon animation, village street background stays still")


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
    print("rendering motion from master...", flush=True)
    res = call_with_quota_retry(
        lambda: ltx.predict(PROMPT, NEG, MASTER, None, 512, 704, "image-to-video",
                            3.0, 9, 42, True, 3.0, False, api_name="/text_to_video"),
        "motion")
    vid = res[0]["video"] if isinstance(res, (list, tuple)) else res["video"]
    subprocess.run(["cp", vid, OUT], check=True)
    print("MOTION:", OUT, flush=True)


if __name__ == "__main__":
    main()
