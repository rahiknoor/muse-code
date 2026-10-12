#!/usr/bin/env python3
"""Retry the Qwen-Image benchmark with the NEW character brief (older male w/ mustache).

Quota was exhausted 2026-09-29 ~09:35 EDT with ~24h retry-after; this run
assumes it has refilled. Backoff on quota errors: 60-90s, up to 10 attempts.
If the error message shows a long retry-after, exit promptly and report it.
"""
import re
import shutil
import sys
import time

from gradio_client import Client

PROMPT = (
    "3D animated family film character: Humpty Dumpty as a kindly older "
    "gentleman egg, big glossy expressive eyes with warm crow's feet, a "
    "prominent distinguished gray handlebar mustache, rosy cheeks, gentle "
    "warm smile, smooth eggshell skin with soft subsurface scattering, "
    "sitting on top of a red brick wall, sunny storybook village background, "
    "soft cinematic lighting, Pixar-style 3D render, ultra detailed, warm "
    "and charming"
)
OUT = "/home/hatch/workspace/av_free_stack/bench_qwen_v2.png"


def main() -> None:
    client = Client("Qwen/Qwen-Image")
    last = None
    for attempt in range(1, 11):
        try:
            # 1:1 aspect (=1024x1024 on this space), fixed seed 7,
            # prompt_enhance OFF to keep prompt verbatim
            result, seed = client.predict(
                PROMPT, 7, False, "1:1", 4.0, 50, False, api_name="/infer"
            )
            path = result["path"] if isinstance(result, dict) else result
            if not path:
                raise RuntimeError("empty result path from space")
            shutil.copy(path, OUT)
            print(f"saved {OUT} (seed={seed})", flush=True)
            return
        except Exception as exc:  # noqa: BLE001
            last = exc
            msg = str(exc)
            low = msg.lower()
            if "quota" in low or "try again in" in low:
                # Look for a long retry-after like "24:00:00" / "0:00:00"
                m = re.search(r"(\d+):(\d{2}):(\d{2})", msg)
                retry_after = m.group(0) if m else "?"
                print(f"QUOTA-EXHAUSTED (retry-after {retry_after}): {msg[:300]}", flush=True)
                if m and int(m.group(1)) > 0:
                    print("Long retry-after; not spinning. Exiting.", flush=True)
                    sys.exit(2)
                if attempt < 10:
                    wait = 60 + (attempt * 5)  # 65..105s-ish within 60-90s band
                    wait = min(wait, 90)
                    print(f"short retry-after, backing off {wait}s (attempt {attempt}/10)", flush=True)
                    time.sleep(wait)
            else:
                import traceback
                traceback.print_exc()
                sys.exit(1)
    print(f"FAILED after retries: {last}", file=sys.stderr)
    sys.exit(2)


if __name__ == "__main__":
    main()
