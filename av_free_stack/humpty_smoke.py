"""Humpty Dumpty pipeline smoke test: TTS narration + 4 LTX-Video scenes + ffmpeg assembly."""
import os, subprocess, sys, time

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

from gradio_client import Client
from gradio_client.exceptions import AppError

WORK = os.path.expanduser("~/workspace/av_free_stack/humpty_smoke")
os.makedirs(WORK, exist_ok=True)

NEG = "blurry, dark, scary, photorealistic, static image"
STYLE = "cute cartoon egg character with big eyes and rosy cheeks, children's picture book style"

SCENES = [
    ("scene1_sitting", f"{STYLE} sitting happily on top of a red brick wall, wobbling side to side cheerfully, sunny day, gentle animation"),
    ("scene2_falling", f"{STYLE} wobbling precariously on the edge of a tall brick wall, tipping over and tumbling down, comedic fall, gentle animation"),
    ("scene3_cracked", f"{STYLE} cracked open lying at the bottom of a brick wall, toy horses and toy soldiers gathered around looking worried, gentle animation"),
    ("scene4_mended", f"{STYLE} with a bandage patch smiling again, toy horses and toy soldiers cheering around it, happy ending, sunny day, gentle animation"),
]

NARRATION = ("Humpty Dumpty sat on a wall. Humpty Dumpty had a great fall. "
             "All the king's horses and all the king's men, couldn't put Humpty together again.")


def call_with_quota_retry(fn, label, tries=8):
    for attempt in range(1, tries + 1):
        try:
            return fn()
        except AppError as e:
            if "quota" in str(e).lower() and attempt < tries:
                print(f"[{label}] quota hit, waiting 60s (attempt {attempt}/{tries})", flush=True)
                time.sleep(60)
                continue
            raise
    raise Runtime


def main():
    tts = Client("remsky/Kokoro-TTS-Zero", httpx_kwargs={"timeout": 300})
    ltx = Client("Lightricks/ltx-video-distilled", httpx_kwargs={"timeout": 900})

    print("== narration ==", flush=True)
    audio_path = os.path.join(WORK, "narration.wav")
    if not os.path.exists(audio_path):
        res = call_with_quota_retry(
            lambda: tts.predict(NARRATION, ["af_heart"], 1.0, api_name="/generate_speech_from_ui"),
            "tts")
        wav = res[0] if isinstance(res, (list, tuple)) else res
        subprocess.run(["cp", wav, audio_path], check=True)
    print("narration ok:", audio_path, flush=True)

    print("== scenes ==", flush=True)
    scene_files = []
    for name, prompt in SCENES:
        out = os.path.join(WORK, f"{name}.mp4")
        scene_files.append(out)
        if os.path.exists(out):
            print(f"{name} cached", flush=True)
            continue
        print(f"rendering {name}...", flush=True)
        res = call_with_quota_retry(
            lambda: ltx.predict(prompt, NEG, None, None, 512, 704, "text-to-video",
                                3.0, 9, 42, True, 3.0, False, api_name="/text_to_video"),
            name)
        vid = res[0]["video"] if isinstance(res, (list, tuple)) else res["video"]
        subprocess.run(["cp", vid, out], check=True)
        print(f"{name} done", flush=True)

    print("== assembly ==", flush=True)
    lst = os.path.join(WORK, "scenes.txt")
    with open(lst, "w") as f:
        for s in scene_files:
            f.write(f"file '{s}'\n")
    concat = os.path.join(WORK, "concat.mp4")
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0",
                    "-i", lst, "-c", "copy", concat], check=True)
    final = os.path.join(WORK, "humpty-smoke-final.mp4")
    subprocess.run(["ffmpeg", "-y", "-v", "error", "-i", concat, "-i", audio_path,
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "20",
                    "-c:a", "aac", "-af", "apad", "-shortest", final], check=True)
    print("FINAL:", final, flush=True)


if __name__ == "__main__":
    main()
