#!/usr/bin/env python3
"""Generate per-scene Kokoro narration lines."""
import os
import subprocess
import sys
import time
import traceback

os.environ.pop("no_proxy", None)
os.environ.pop("NO_PROXY", None)

from gradio_client import Client

WORK = os.path.expanduser("~/workspace/av_free_stack/v1")

LINES = [
    ("line1.wav", "Humpty Dumpty sat on a wall."),
    ("line2.wav", "Humpty Dumpty had a great fall."),
    ("line3.wav", "All the king's horses and all the king's men gathered round."),
    ("line4.wav", "And Humpty was all better again! Hooray!"),
]


def main():
    tts = Client("remsky/Kokoro-TTS-Zero", httpx_kwargs={"timeout": 300})
    for name, text in LINES:
        out = os.path.join(WORK, name)
        if os.path.exists(out):
            print(f"{name} cached", flush=True)
            continue
        last = None
        for attempt in range(1, 9):
            try:
                res = tts.predict(text, ["af_heart"], 1.0,
                                  api_name="/generate_speech_from_ui")
                wav = res[0] if isinstance(res, (list, tuple)) else res
                subprocess.run(["cp", wav, out], check=True)
                print(f"saved {name}", flush=True)
                break
            except Exception as exc:  # noqa: BLE001
                last = exc
                if "quota" in str(exc).lower() and attempt < 8:
                    print(f"[{name}] quota hit, waiting 60s "
                          f"(attempt {attempt}/8)", flush=True)
                    time.sleep(60)
                else:
                    traceback.print_exc()
                    sys.exit(1)
        else:
            print(f"FAILED {name}: {last}", file=sys.stderr)
            sys.exit(1)


if __name__ == "__main__":
    main()
