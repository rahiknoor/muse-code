#!/bin/bash
# Orchestrator: keyframes 3-4 -> scene 3-4 videos -> final assembly.
# ZeroGPU quota is exhausted (anon + authed); this patiently retries until
# the quota resets. Each step runs until its output file exists.
# Rahik greenlit the full assembly 2026-09-29.
cd ~/workspace/av_free_stack || exit 1
PY=./venv/bin/python

wait_for() { # $1=file $2=script $3=label
  for i in $(seq 1 24); do
    if [ -f "$1" ]; then echo "[$3] exists, skipping"; return 0; fi
    echo "=== [$3] attempt $i == "
    $PY "$2" 2>&1 | tail -2
    if [ -f "$1" ]; then echo "[$3] DONE"; return 0; fi
    echo "[$3] waiting 15min for quota..."
    sleep 900
  done
  echo "[$3] FAILED after 24 attempts"
  return 1
}

wait_for humpty_scene3_keyframe.png gen_keyframes_34.py keyframes || exit 1
# gen_keyframes_34 does both; ensure scene4 keyframe too (script handles both)
wait_for humpty_scene4_keyframe.png gen_keyframes_34.py keyframes4 || exit 1
wait_for humpty_scene3_test.mp4 motion_scene3.py scene3video || exit 1
wait_for humpty_scene4_test.mp4 motion_scene4.py scene4video || exit 1
wait_for humpty_full_test.mp4 assemble_final.py assembly || exit 1
echo "ORCHESTRATOR: ALL DONE"
