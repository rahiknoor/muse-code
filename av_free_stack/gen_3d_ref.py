#!/usr/bin/env python3
"""Generate the cinematic 3D Humpty character reference via FLUX.1-schnell."""
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
OUT = "/home/hatch/workspace/av_free_stack/humpty_3d_ref.png"


def main() -> None:
    client = Client("black-forest-labs/FLUX.1-schnell")
    last = None
    for attempt in range(1, 11):
        try:
            result, seed = client.predict(
                PROMPT, 7, False, 1024, 1024, 4, api_name="/infer"
            )
            path = result["path"] if isinstance(result, dict) else result
            shutil.copy(path, OUT)
            print(f"saved {OUT} (seed={seed})")
            return
        except Exception as exc:  # noqa: BLE001
            last = exc
            msg = str(exc).lower()
            if "quota" in msg:
                print(f"quota hit, waiting 90s (attempt {attempt}/10)", flush=True)
                time.sleep(90)
            else:
                traceback.print_exc()
                sys.exit(1)
    print(f"FAILED after retries: {last}", file=sys.stderr)
    sys.exit(1)


if __name__ == "__main__":
    main()
