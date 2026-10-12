#!/usr/bin/env python3
"""Assemble v1: per-scene audio mux -> concat -> 1280x720 final."""
import os
import subprocess
import sys

WORK = os.path.expanduser("~/workspace/av_free_stack/v1")


def run(cmd):
    subprocess.run(cmd, check=True)


def main():
    segs = []
    for i in range(1, 5):
        scene = os.path.join(WORK, f"scene{i}.mp4")
        line = os.path.join(WORK, f"line{i}.wav")
        seg = os.path.join(WORK, f"seg{i}.mp4")
        for f in (scene, line):
            if not os.path.exists(f):
                print(f"MISSING {f}", file=sys.stderr)
                sys.exit(1)
        # mux narration padded to scene duration
        run(["ffmpeg", "-y", "-v", "error", "-i", scene, "-i", line,
             "-c:v", "copy", "-c:a", "aac", "-af", "apad",
             "-shortest", seg])
        segs.append(seg)
        print(f"muxed seg{i}", flush=True)

    lst = os.path.join(WORK, "segs.txt")
    with open(lst, "w") as f:
        for s in segs:
            f.write(f"file '{s}'\n")
    concat = os.path.join(WORK, "concat.mp4")
    run(["ffmpeg", "-y", "-v", "error", "-f", "concat", "-safe", "0",
         "-i", lst, "-c", "copy", concat])

    final = os.path.join(WORK, "humpty-v1-test.mp4")
    run(["ffmpeg", "-y", "-v", "error", "-i", concat,
         "-vf", "scale=1280:720", "-c:v", "libx264", "-pix_fmt", "yuv420p",
         "-crf", "20", "-c:a", "aac", final])
    print("FINAL:", final, flush=True)


if __name__ == "__main__":
    main()
