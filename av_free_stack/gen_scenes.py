#!/usr/bin/env python3
"""Animate stills 1-4 with LTX-Video image-to-video."""
import os
import subprocess
import sys
import time
import traceback

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

from gradio_client import Client

WORK = os.path.expanduser("~/workspace/av_free_stack/v1")
NEG = ("blurry, dark, scary, photorealistic, static image, distorted face, "
       "deformed, jittery")

SCENES = [
    ("still1.png", "scene1.mp4",
     "the cute egg character waves hello and wobbles happily side to side"),
    ("still2.png", "scene2.mp4",
     "the cute egg character tumbles and spins through the air"),
    ("still3.png", "scene3.mp4",
     "the cute egg character blinks sadly with a slight tremble"),
    ("still4.png", "scene4.mp4",
     "the cute egg character bounces joyfully and waves"),
]


def main():
    ltx = Client("Lightricks/ltx-video-distilled", httpx_kwargs={"timeout": 900})
    for still, scene, motion in SCENES:
        still_path = os.path.join(WORK, still)
        out = os.path.join(WORK, scene)
        if os.path.exists(out):
            print(f"{scene} cached", flush=True)
            continue
        if not os.path.exists(still_path):
            print(f"MISSING {still_path}", file=sys.stderr)
            sys.exit(1)
        last = None
        for attempt in range(1, 16):
            try:
                # NOTE: the dedicated /image_to_video endpoint is broken on
                # this Space (generic upstream AppError). The unified
                # /text_to_video endpoint with mode="image-to-video" and the
                # still path as arg 3 is the working route (proven).
                res = ltx.predict(
                    motion, NEG, still_path, None, 512, 704, "image-to-video",
                    2.5, 9, 42, False, 3.0, False,
                    api_name="/text_to_video")
                vid = res[0]["video"] if isinstance(res, (list, tuple)) else res["video"]
                subprocess.run(["cp", vid, out], check=True)
                print(f"saved {scene}", flush=True)
                break
            except Exception as exc:  # noqa: BLE001
                last = exc
                if "quota" in str(exc).lower() and attempt < 15:
                    print(f"[{scene}] quota hit, waiting 75s "
                          f"(attempt {attempt}/15)", flush=True)
                    time.sleep(75)
                else:
                    traceback.print_exc()
                    sys.exit(1)
        else:
            print(f"FAILED {scene}: {last}", file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    main()
