"""ACE-Step sung vocals for Humpty Dumpty — task 2026-09-29.
Space: ACE-Step/ACE-Step, endpoint /__call__ (text2music).
Params order: audio_duration, prompt, lyrics, infer_step, guidance_scale,
scheduler_type, cfg_type, omega_scale, manual_seeds, guidance_interval,
guidance_interval_decay, min_guidance_scale, use_erg_tag, use_erg_lyric,
use_erg_diffusion, oss_steps, guidance_scale_text, guidance_scale_lyric,
audio2audio_enable, ref_audio_strength, ref_audio_input, lora_name_or_path
"""
import os, shutil, subprocess

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

from gradio_client import Client
from gradio_client.exceptions import AppError

WORK = os.path.expanduser("~/workspace/av_free_stack/audio")
os.makedirs(WORK, exist_ok=True)
OUT = os.path.join(WORK, "humpty_vocals_acestep.mp3")

LYRICS = """[verse]
Humpty Dumpty sat on a wall,
Humpty Dumpty had a great fall.
[verse]
All the king's horses and all the king's men
Couldn't put Humpty together again."""

PROMPT = ("nursery rhyme, children's song, cheerful, playful, bright, "
          "warm friendly vocals, sing-along melody, acoustic")


def main():
    ace = Client("ACE-Step/ACE-Step", httpx_kwargs={"timeout": 900})
    print("generating vocals...", flush=True)
    try:
        res = ace.predict(
            16.0,            # audio_duration ~16s
            PROMPT,          # prompt
            LYRICS,          # lyrics
            60,              # infer_step
            15.0,            # guidance_scale
            "euler",         # scheduler_type
            "apg",           # cfg_type
            10.0,            # omega_scale
            "42",            # manual_seeds
            0.5,             # guidance_interval
            0.0,             # guidance_interval_decay
            3.0,             # min_guidance_scale
            True,            # use_erg_tag
            True,            # use_erg_lyric (lyric accuracy matters)
            True,            # use_erg_diffusion
            None,            # oss_steps
            0.0,             # guidance_scale_text
            0.0,             # guidance_scale_lyric
            False,           # audio2audio_enable
            0.5,             # ref_audio_strength
            None,            # ref_audio_input
            "none",          # lora_name_or_path
            api_name="/__call__",
        )
    except AppError as e:
        print("APPEAR_BLOCKED:", str(e)[:300], flush=True)
        raise SystemExit(2)
    audio = res[0]
    src = audio["path"] if isinstance(audio, dict) else audio
    shutil.copy(src, OUT)
    print("VOCALS:", OUT, flush=True)
    r = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=duration",
         "-of", "default=noprint_wrappers=1:nokey=1", OUT],
        capture_output=True, text=True)
    print("duration_s:", r.stdout.strip(), flush=True)


if __name__ == "__main__":
    main()
