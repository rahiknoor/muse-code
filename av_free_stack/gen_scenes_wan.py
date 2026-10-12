#!/usr/bin/env python3
"""Animate stills 1-4 with Wan2.2-Fast image-to-video (/generate_video).

LTX-Video's image-to-video is broken on its Space right now (both
/image_to_video and /handle_image_upload_for_dims raise generic upstream
errors; verified against the Space's own app.py that the wiring is correct
— the Space itself is erroring). Wan2.2-Fast takes image_b64 and works.
"""
import base64
import os
import subprocess
import sys
import time
import traceback

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

from gradio_client import Client

WORK = os.path.expanduser("~/workspace/av_free_stack/v1")
NEG = "blurry, distorted, deformed, static image, low quality, jittery"

SCENES = [
    ("still1.png", "scene1.mp4",
     "the cute egg character waves hello and wobbles happily side to side, "
     "gentle cheerful motion"),
    ("still2.png", "scene2.mp4",
     "the cute egg character tumbles and spins through the air, "
     "dynamic falling motion"),
    ("still3.png", "scene3.mp4",
     "the cute egg character blinks sadly with a slight tremble, "
     "subtle emotional motion"),
    ("still4.png", "scene4.mp4",
     "the cute egg character bounces joyfully and waves, "
     "happy celebratory motion"),
]


def extract_video(res):
    if isinstance(res, dict):
        for key in ("video", "path", "filepath"):
            v = res.get(key)
            if isinstance(v, str) and os.path.exists(v):
                return v
            if isinstance(v, dict) and "path" in v and os.path.exists(v["path"]):
                return v["path"]
        for v in res.values():
            if isinstance(v, str) and os.path.exists(v):
                return v
            if isinstance(v, dict) and "path" in v and os.path.exists(v["path"]):
                return v["path"]
    raise RuntimeError(f"could not find video in result: {str(res)[:300]}")


def main():
    wan = Client("prithivMLmods/Wan2.2-Fast", httpx_kwargs={"timeout": 900})
    for still, scene, motion in SCENES:
        still_path = os.path.join(WORK, still)
        out = os.path.join(WORK, scene)
        if os.path.exists(out):
            print(f"{scene} cached", flush=True)
            continue
        with open(still_path, "rb") as f:
            # Space parses this as a data URL ("a,b".split(",")) — raw b64
            # fails with "not enough values to unpack (expected 2, got 1)".
            b64 = "data:image/png;base64," + base64.b64encode(f.read()).decode()
        last = None
        # Quota math (observed): 20 steps x 3.0s requests ~120s GPU but the
        # anonymous ZeroGPU window only holds ~30-50s. Drop to 6 steps x 2.0s
        # (~24s GPU) so a single scene fits inside the available quota.
        for attempt in range(1, 31):
            try:
                res = wan.predict(
                    b64, motion, 6, NEG, 2.0, 5.0, 5.0, 42, False,
                    api_name="/generate_video")
                vid = extract_video(res)
                subprocess.run(["cp", vid, out], check=True)
                print(f"saved {scene}", flush=True)
                break
            except Exception as exc:  # noqa: BLE001
                last = exc
                msg = str(exc).lower()
                transient = ("quota" in msg or "runs limit" in msg
                             or "aborted" in msg or "overload" in msg
                             or "timeout" in msg or "temporarily" in msg)
                if transient and attempt < 20:
                    print(f"[{scene}] transient hit ({str(exc)[:80]}), "
                          f"waiting 90s (attempt {attempt}/20)", flush=True)
                    time.sleep(90)
                else:
                    traceback.print_exc()
                    sys.exit(1)
        else:
            print(f"FAILED {scene}: {last}", file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    main()
