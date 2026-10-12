"""Final assembly: 4 scenes + sung vocals + BGM -> one video, ready for unlisted upload.

- Concatenates the 4 scene clips (all 704x512/30fps from the same LTX pipeline).
- Pads video with a frozen last frame if shorter than the vocal track.
- Mix: vocals full, BGM ("Carefree" CC BY 4.0) at low bed volume with fade-out.
- Writes the CC BY attribution to a companion description file.
Rahik greenlit the full assembly 2026-09-29. First cut; polish after his verdict.
"""
import os, subprocess, sys

WORK = os.path.expanduser("~/workspace/av_free_stack")
AUD = os.path.join(WORK, "audio")
SCENES = [os.path.join(WORK, f"humpty_scene{i}_test.mp4") for i in range(1, 5)]
VOCALS = os.path.join(AUD, "humpty_vocals_acestep.mp3")
BGM = os.path.join(AUD, "humpty_bgm_carefree.mp3")
CONCAT = os.path.join(WORK, "humpty_scenes_concat.mp4")
PADDED = os.path.join(WORK, "humpty_scenes_padded.mp4")
FINAL = os.path.join(WORK, "humpty_full_test.mp4")
DESC = os.path.join(WORK, "humpty_full_test_description.txt")

ATTRIBUTION = ("Carefree by Kevin MacLeod is licensed under a Creative Commons "
               "Attribution 4.0 license. https://creativecommons.org/licenses/by/4.0/ "
               "Source: http://incompetech.com/music/royalty-free/index.html?isrc=USUAN1400037 "
               "Artist: http://incompetech.com/")


def dur(path):
    r = subprocess.run(["ffprobe", "-v", "error", "-show_entries", "format=duration",
                        "-of", "default=noprint_wrappers=1:nokey=1", path],
                       capture_output=True, text=True, check=True)
    return float(r.stdout.strip())


def main():
    for s in SCENES:
        assert os.path.exists(s), f"missing scene: {s}"
    assert os.path.exists(VOCALS) and os.path.exists(BGM), "missing audio"
    print("scenes + audio present", flush=True)

    # 1. concat
    with open(os.path.join(WORK, "concat_list.txt"), "w") as f:
        for s in SCENES:
            f.write(f"file '{s}'\n")
    subprocess.run(["ffmpeg", "-y", "-f", "concat", "-safe", "0",
                    "-i", os.path.join(WORK, "concat_list.txt"),
                    "-c", "copy", CONCAT], check=True, capture_output=True)
    dv, da = dur(CONCAT), dur(VOCALS)
    print(f"video {dv:.2f}s, vocals {da:.2f}s", flush=True)

    # 2. pad video to vocal length if needed
    v_in = CONCAT
    if dv < da:
        subprocess.run(["ffmpeg", "-y", "-i", CONCAT, "-vf",
                        f"tpad=stop_mode=clone:stop_duration={da - dv:.2f}",
                        "-c:a", "copy", PADDED], check=True, capture_output=True)
        v_in = PADDED
        print(f"padded video to {da:.2f}s", flush=True)
    final_d = max(dv, da)

    # 3. mix: vocals + BGM bed (trimmed, low, faded)
    subprocess.run([
        "ffmpeg", "-y", "-i", v_in, "-i", VOCALS, "-i", BGM,
        "-filter_complex",
        f"[2:a]atrim=0:{final_d:.2f},volume=0.12,afade=t=out:st={final_d-2:.2f}:d=2[bgm];"
        f"[1:a]atrim=0:{final_d:.2f}[vox];[vox][bgm]amix=inputs=2:normalize=0[a]",
        "-map", "0:v", "-map", "[a]", "-c:v", "libx264", "-pix_fmt", "yuv420p",
        "-c:a", "aac", "-shortest", FINAL,
    ], check=True, capture_output=True)
    print("FINAL:", FINAL, f"({dur(FINAL):.2f}s)", flush=True)

    with open(DESC, "w") as f:
        f.write("Humpty Dumpty — full test (unlisted)\n\nMusic: " + ATTRIBUTION + "\n")
    print("description:", DESC, flush=True)


if __name__ == "__main__":
    main()
