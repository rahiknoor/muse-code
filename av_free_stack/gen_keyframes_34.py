"""Keyframes for scenes 3-4. FLUX.2-klein-4B (Apache 2.0), /infer endpoint.

Scene 3: "All the king's horses and all the king's men" — new characters,
text-to-image, same 3D Pixar storybook style as the locked master.
Scene 4: "Couldn't put Humpty together again" — multi-reference with the locked
master so Humpty stays consistent, horses + men gathered around him.
Rahik greenlit the full assembly 2026-09-29 ("final products whatever we have").
Provisional designs for this first assembly; full character pipeline in polish round.
"""
import os, shutil, time

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

from gradio_client import Client
from gradio_client.exceptions import AppError

from hf_auth import space_client

WORK = os.path.expanduser("~/workspace/av_free_stack")
MASTER = os.path.join(WORK, "humpty_MASTER.png")
S3_OUT = os.path.join(WORK, "humpty_scene3_keyframe.png")
S4_OUT = os.path.join(WORK, "humpty_scene4_keyframe.png")

STYLE = ("3D Pixar-style animated film still, ray-traced soft shadows, vibrant "
         "cinematic color grade, warm wholesome mood")

S3_PROMPT = (f"{STYLE}. Sunny storybook village street: a group of the king's horses "
             "— beautiful chestnut and white cartoon horses with big friendly expressive "
             "eyes — trot down the cobblestone lane toward a weathered red brick wall. "
             "Beside them march the king's men: cheerful storybook soldiers in royal blue "
             "and gold uniforms with tall hats. Window boxes overflowing with red geraniums, "
             "cozy cottages with slate roofs, a leafy green tree, clear blue sky. "
             "Warm golden-hour sunlight, eye-level wide shot.")

S4_PROMPT = (f"{STYLE}. At the base of the weathered red brick wall on the cobblestone "
             "street, Humpty Dumpty (the egg character from the reference image: keep his "
             "mustache, yellow bow tie, red shirt and blue overalls exactly) sits on the "
             "ground looking dazed, a small bump star swirling above his head. Around him "
             "gather the king's horses and the king's men, leaning in and trying to help, "
             "looking puzzled and concerned, one soldier scratching his head. Sunny "
             "storybook village, warm golden-hour sunlight, eye-level medium shot, "
             "gentle humor, wholesome.")


def gen(client, prompt, refs, out, label, seed):
    def call():
        return client.predict(
            prompt, refs, "Distilled (4 steps)", seed, False, 768, 1024, 4, 1.0, True,
            api_name="/infer")
    last = None
    for attempt in range(1, 11):
        try:
            res = call()
            img = res[0] if isinstance(res, (list, tuple)) else res
            path = img["path"] if isinstance(img, dict) else img
            shutil.copy(path, out)
            print(f"{label}: saved {out} (seed={seed})", flush=True)
            return
        except AppError as e:
            last = e
            if "quota" in str(e).lower() and attempt < 10:
                print(f"[{label}] quota hit, waiting 120s (attempt {attempt}/10)", flush=True)
                time.sleep(120)
                continue
            raise
    raise RuntimeError(f"{label} failed: {last}")


def main():
    client = space_client("black-forest-labs/FLUX.2-klein-4B", httpx_kwargs={"timeout": 300})
    if not os.path.exists(S3_OUT):
        gen(client, S3_PROMPT, [], S3_OUT, "scene3", 42)
    else:
        print("scene3 keyframe exists, skipping", flush=True)
    if not os.path.exists(S4_OUT):
        gen(client, S4_PROMPT, [MASTER], S4_OUT, "scene4", 43)
    else:
        print("scene4 keyframe exists, skipping", flush=True)


if __name__ == "__main__":
    main()
