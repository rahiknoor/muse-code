#!/bin/bash
# Final Humpty cut audio mix v3 (2026-09-30) — Rahik's "audio is not matching" verdict.
# Root causes found in v2: (1) the dramatic wind swell was mixed ~8dB HOTTER than the
# narration, burying lines 2-3; (2) the crash at 12.2s played over a tumbling shot —
# the video NEVER shows the landing (hard cut at 12.5s); (3) the swell built while he
# was already falling instead of causing the fall.
# v3 fixes: voice +6dB (always on top); wind builds 6.5-10s while he's on the wall,
# gust peaks ON the 10.0s cut (knocks him off), decays through the tumble; crash
# moved to 12.5s ON the hard cut (off-screen landing implied by the cut).
# Timeline (video_only.mp4 = 30.5s): seg1 0-10 | seg2 10-12.5 | seg3 12.5-17.5 | segR 17.5-20.5 | seg4 20.5-30.5
set -e
cd ~/workspace/av_free_stack/audio
BGM="${1:-humpty_bgm_greenorbs.mp3}"
OUT="final_audio_mix_v3.mp3"

# Line start times (ms): L1 wall | L2 fall | L3 horses | L4 aftermath
L1=1000; L2=10300; L3=13000; L4=21000
FX=12500   # impact ON the 12.5s hard cut (no landing is shown; the cut IS the impact)

ffmpeg -y -v error \
  -i wind_bed_v3.mp3 \
  -i recite/line1.mp3 -i recite/line2.mp3 -i recite/line3.mp3 -i recite/line4.mp3 \
  -i fall_impact.mp3 \
  -i "$BGM" \
  -filter_complex "\
[1:a]adelay=${L1}|${L1},volume=2.0[l1];\
[2:a]adelay=${L2}|${L2},volume=2.0[l2];\
[3:a]adelay=${L3}|${L3},volume=2.0[l3];\
[4:a]adelay=${L4}|${L4},volume=2.0[l4];\
[5:a]adelay=${FX}|${FX},volume=1.1[fx];\
[6:a]atrim=0:30.5,asetpts=PTS-STARTPTS,volume=0.25,afade=t=out:st=28.5:d=2[bgm];\
[0:a][l1][l2][l3][l4][fx][bgm]amix=inputs=7:duration=longest:normalize=0,alimiter=limit=0.9" \
  -t 30.5 "$OUT"
echo "wrote $OUT"
ffprobe -v error -show_entries format=duration -of csv=p=0 "$OUT"
