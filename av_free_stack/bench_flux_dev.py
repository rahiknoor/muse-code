#!/usr/bin/env python3
"""Benchmark FLUX.1-dev for the 3D Humpty character reference."""
import random
import re
import shutil
import sys
import time
import traceback

from gradio_client import Client

PROMPT = (
    "3D animated family film character reference sheet: a cute cartoon egg "
    "character named Humpty Dumpty, big glossy expressive eyes, rosy pink "
    "cheeks, gentle happy smile, smooth eggshell skin with soft subsurface "
    "scattering, wearing no clothes, sitting on top of a red brick wall, "
    "sunny storybook village background, soft cinematic lighting, "
    "Pixar-style 3D render, octane render, ultra detailed, warm and charming"
)
OUT = "/home/hatch/workspace/av_free_stack/bench_flux-dev.png"


def site_retry_wait(msg: str) -> float:
    """Honor the site's own 'try again in H:MM:SS' signal (never guess the wait).

    Falls back to 75s when the message carries no usable time. Jittered ±~20%
    so retries don't land metronomically; floored at 5s, capped at 15 min.
    """
    m = re.search(r"try again in (\d+):(\d{2}):(\d{2})", msg)
    if m:
        base = int(m.group(1)) * 3600 + int(m.group(2)) * 60 + int(m.group(3))
    else:
        base = 75
    return min(max(5.0, base * random.uniform(0.9, 1.25)), 900.0)


def main() -> None:
    client = Client("black-forest-labs/FLUX.1-dev")
    last = None
    for attempt in range(1, 11):
        try:
            result, seed = client.predict(
                PROMPT, 7, False, 1024, 1024, 3.5, 28, api_name="/infer"
            )
            path = result["path"] if isinstance(result, dict) else result
            shutil.copy(path, OUT)
            print(f"saved {OUT} (seed={seed})")
            return
        except Exception as exc:  # noqa: BLE001
            last = exc
            msg = str(exc).lower()
            transient = "quota" in msg or "timeout" in msg or "timed out" in msg
            if transient:
                wait = site_retry_wait(msg)
                print(f"transient error, honoring site retry signal; waiting {wait:.0f}s (attempt {attempt}/10)", flush=True)
                time.sleep(wait)
            else:
                traceback.print_exc()
                sys.exit(1)
    print(f"FAILED after retries: {last}", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
