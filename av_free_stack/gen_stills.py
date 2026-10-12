#!/usr/bin/env python3
"""Generate storyboard stills 2-4 via FLUX.1-schnell (still1 = reference copy)."""
import os
import shutil
import sys
import time
import traceback

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

from gradio_client import Client

WORK = os.path.expanduser("~/workspace/av_free_stack/v1")

CHAR = (
    "cute cartoon egg character named Humpty Dumpty, big glossy expressive "
    "eyes, rosy pink cheeks, smooth eggshell skin with soft subsurface "
    "scattering, Pixar-style 3D render, octane render, ultra detailed"
)

STILLS = [
    ("still2.png",
     f"{CHAR}, tumbling through the air off a red brick wall, surprised "
     "expression, tiny arms up in the air, dynamic motion feel, sunny "
     "storybook village background, soft cinematic lighting, warm and charming"),
    ("still3.png",
     f"{CHAR}, sad worried expression, sitting on green grass at the bottom "
     "of a tall red brick wall, a visible crack on the eggshell, small toy "
     "horses and toy soldiers gathered around looking worried, sunny day, "
     "soft cinematic lighting, warm and charming"),
    ("still4.png",
     f"{CHAR}, happy joyful expression, whole again with no crack, sitting "
     "on green grass with bright sun shining, small toy horses and toy "
     "soldiers celebrating around, sunny storybook village background, "
     "soft cinematic lighting, warm and charming"),
]


def gen(client, prompt, out):
    if os.path.exists(out):
        print(f"{out} cached", flush=True)
        return
    last = None
    for attempt in range(1, 11):
        try:
            result, seed = client.predict(prompt, 7, False, 1024, 1024, 4,
                                          api_name="/infer")
            path = result["path"] if isinstance(result, dict) else result
            shutil.copy(path, out)
            print(f"saved {out} (seed={seed})", flush=True)
            return
        except Exception as exc:  # noqa: BLE001
            last = exc
            if "quota" in str(exc).lower():
                print(f"[{out}] quota hit, waiting 90s (attempt {attempt}/10)",
                      flush=True)
                time.sleep(90)
            else:
                traceback.print_exc()
                sys.exit(1)
    print(f"FAILED {out}: {last}", file=sys.stderr)
    sys.exit(1)


def main():
    client = Client("black-forest-labs/FLUX.1-schnell")
    for name, prompt in STILLS:
        gen(client, prompt, os.path.join(WORK, name))


if __name__ == "__main__":
    main()
